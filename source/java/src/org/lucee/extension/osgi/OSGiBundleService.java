package org.lucee.extension.osgi;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.ServiceLoader;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleException;
import org.osgi.framework.BundleReference;
import org.osgi.framework.Constants;
import org.osgi.framework.launch.Framework;
import org.osgi.framework.launch.FrameworkFactory;
import org.osgi.framework.wiring.BundleRevision;
import org.osgi.framework.wiring.BundleWiring;
import org.osgi.framework.wiring.FrameworkWiring;
import org.osgi.resource.Requirement;

import lucee.loader.engine.CFMLEngineFactory;
import lucee.runtime.osgi.BundleService;

/**
 * The BundleService of the OSGi extension, based on Apache Felix. The framework is started when the
 * first bundle is needed and not before.
 */
public final class OSGiBundleService implements BundleService {

	private static final Pattern BUNDLE_NAME = Pattern.compile("osgi\\.wiring\\.bundle=([^)]+)\\)");
	private static final Pattern PACKAGE_NAME = Pattern.compile("osgi\\.wiring\\.package=([^)]+)\\)");
	private static final Pattern VERSION = Pattern.compile("(?:bundle-)?version>=([^)]+)\\)");

	private Framework framework;
	private File root;
	private File bundleDirectory;

	/** used by the ServiceLoader, the root is the one of the running Lucee */
	public OSGiBundleService() {
	}

	/** for tests: a service that keeps its bundles and the framework cache under the given directory */
	public OSGiBundleService(File root) {
		this.root = root;
	}

	@Override
	public boolean isAvailable() {
		return true;
	}

	// framework

	private synchronized Framework framework() throws IOException {
		if (framework != null) return framework;
		try {
			if (root == null) root = CFMLEngineFactory.getInstance().getCFMLEngineFactory().getResourceRoot();
			bundleDirectory = new File(root, "bundles");

			Map<String, String> config = readDefaults();
			config.put("felix.cache.rootdir", root.getAbsolutePath());
			String delegation = config.get(Constants.FRAMEWORK_BOOTDELEGATION);
			config.put(Constants.FRAMEWORK_BOOTDELEGATION, delegation == null || delegation.trim().isEmpty() ? "lucee.*" : "lucee.*," + delegation);
			// system properties and environment variables override the defaults
			for (String key: new ArrayList<>(config.keySet())) {
				String over = System.getProperty(key);
				if (over == null) over = System.getenv(key.replace('.', '_').toUpperCase());
				if (over != null && !over.trim().isEmpty() && !Constants.FRAMEWORK_BOOTDELEGATION.equals(key)) config.put(key, over.trim());
			}

			FrameworkFactory factory = ServiceLoader.load(FrameworkFactory.class, getClass().getClassLoader()).iterator().next();
			Framework f = factory.newFramework(config);
			f.init();
			f.start();
			return framework = f;
		}
		catch (IOException e) {
			throw e;
		}
		catch (Exception e) {
			throw new IOException("failed to start the OSGi framework: " + e.getMessage(), e);
		}
	}

	private static Map<String, String> readDefaults() throws IOException {
		Properties props = new Properties();
		try (InputStream is = OSGiBundleService.class.getResourceAsStream("/resources/default.properties")) {
			if (is != null) props.load(is);
		}
		Map<String, String> config = new HashMap<>();
		for (String key: props.stringPropertyNames()) {
			if (!key.startsWith("org.") && !key.startsWith("felix.")) continue;
			config.put(key, removeQuotes(props.getProperty(key)));
		}
		return config;
	}

	private static String removeQuotes(String str) {
		str = str.trim();
		if (str.length() > 1 && str.startsWith("\"") && str.endsWith("\"")) str = str.substring(1, str.length() - 1);
		return str;
	}

	private BundleContext context() throws IOException {
		return framework().getBundleContext();
	}

	/** the framework if it is running, otherwise null (never starts it) */
	private synchronized BundleContext runningContext() {
		return framework == null ? null : framework.getBundleContext();
	}

	// versions

	static String toLucee(org.osgi.framework.Version v) {
		String base = v.getMajor() + "." + v.getMinor() + "." + v.getMicro();
		String q = v.getQualifier();
		if (q.isEmpty()) return base;
		char c = q.charAt(0);
		return base + (c >= '0' && c <= '9' ? "." : "-") + q;
	}

	private static boolean matches(Bundle b, String name, String version) {
		if (!name.equals(b.getSymbolicName())) return false;
		if (version == null || version.trim().isEmpty()) return true;
		String v = version.trim();
		return v.equalsIgnoreCase(toLucee(b.getVersion())) || v.equalsIgnoreCase(b.getVersion().toString());
	}

	private static Bundle find(BundleContext ctx, String name, String version) {
		if (ctx == null) return null;
		Bundle best = null;
		for (Bundle b: ctx.getBundles()) {
			if (!matches(b, name, version)) continue;
			if (best == null || b.getVersion().compareTo(best.getVersion()) > 0) best = b;
		}
		return best;
	}

	private Bundle require(String name, String version) throws IOException {
		Bundle b = find(runningContext(), name, version);
		if (b == null) b = findLocal(name, version, true);
		if (b == null) throw new IOException("the bundle [" + name + ":" + version + "] is not available");
		return b;
	}

	private static void startIfNecessary(Bundle b) throws BundleException {
		if (b.getState() == Bundle.ACTIVE || b.getState() == Bundle.STARTING) return;
		if ((b.adapt(BundleRevision.class).getTypes() & BundleRevision.TYPE_FRAGMENT) != 0) return;
		b.start();
	}

	// local directory

	private Bundle findLocal(String name, String version, boolean install) throws IOException {
		BundleContext ctx = context();
		File[] jars = bundleDirectory == null ? null : bundleDirectory.listFiles((dir, n) -> n.endsWith(".jar"));
		if (jars == null) return null;
		for (File jar: jars) {
			String[] nv = nameAndVersion(jar);
			if (nv == null || !name.equals(nv[0])) continue;
			Bundle candidate = null;
			if (version == null || version.trim().isEmpty() || version.trim().equalsIgnoreCase(nv[2]) || version.trim().equals(nv[1])) {
				try {
					candidate = ctx.installBundle(jar.toURI().toString());
				}
				catch (BundleException e) {
					throw new IOException(e.getMessage(), e);
				}
			}
			if (candidate != null) return candidate;
		}
		return null;
	}

	/** symbolic name, OSGi version and Lucee style version of a bundle jar, null when it is not a bundle */
	private static String[] nameAndVersion(File jar) {
		try (JarFile jf = new JarFile(jar)) {
			Manifest m = jf.getManifest();
			if (m == null) return null;
			String name = m.getMainAttributes().getValue("Bundle-SymbolicName");
			if (name == null) return null;
			int index = name.indexOf(';');
			if (index != -1) name = name.substring(0, index);
			String v = m.getMainAttributes().getValue("Bundle-Version");
			org.osgi.framework.Version ov = org.osgi.framework.Version.parseVersion(v);
			return new String[] { name.trim(), ov.toString(), toLucee(ov) };
		}
		catch (Exception e) {
			return null;
		}
	}

	// BundleService

	@Override
	public Class<?> loadClass(String bundleName, String bundleVersion, String className) throws IOException, ClassNotFoundException {
		Bundle b = require(bundleName, bundleVersion);
		try {
			startIfNecessary(b);
		}
		catch (BundleException e) {
			throw new IOException(e.getMessage(), e);
		}
		return b.loadClass(className);
	}

	@Override
	public Map<String, Object> install(File file, boolean start) throws IOException {
		BundleContext ctx = context();
		try {
			String[] nv = nameAndVersion(file);
			if (nv == null) throw new IOException(file + " is not a valid bundle");
			Bundle b = find(ctx, nv[0], nv[1]);
			if (b == null) b = ctx.installBundle(file.toURI().toString());
			if (start) startIfNecessary(b);
			return toInfo(b);
		}
		catch (BundleException e) {
			throw new IOException(e.getMessage(), e);
		}
	}

	@Override
	public void start(String bundleName, String bundleVersion) throws IOException {
		try {
			startIfNecessary(require(bundleName, bundleVersion));
		}
		catch (BundleException e) {
			throw new IOException(e.getMessage(), e);
		}
	}

	@Override
	public void stop(String bundleName, String bundleVersion) throws IOException {
		Bundle b = find(runningContext(), bundleName, bundleVersion);
		if (b == null) return;
		try {
			if (b.getState() == Bundle.ACTIVE) b.stop();
		}
		catch (BundleException e) {
			throw new IOException(e.getMessage(), e);
		}
	}

	@Override
	public void uninstall(String bundleName, String bundleVersion) throws IOException {
		Bundle b = find(runningContext(), bundleName, bundleVersion);
		if (b == null) return;
		try {
			if (b.getState() == Bundle.ACTIVE) b.stop();
			b.uninstall();
		}
		catch (BundleException e) {
			throw new IOException(e.getMessage(), e);
		}
	}

	@Override
	public Map<String, Object> getBundleInfo(String bundleName, String bundleVersion) {
		Bundle b = find(runningContext(), bundleName, bundleVersion);
		return b == null ? null : toInfo(b);
	}

	@Override
	public Map<String, Object> getBundleInfo(Class<?> clazz) {
		if (clazz == null) return null;
		ClassLoader cl = clazz.getClassLoader();
		if (cl instanceof BundleReference) return toInfo(((BundleReference) cl).getBundle());
		return null;
	}

	@Override
	public Map<String, Object> getBundleInfo(ClassLoader classLoader) {
		if (classLoader instanceof BundleReference) return toBasicInfo(((BundleReference) classLoader).getBundle());
		return null;
	}

	@Override
	public String getBundleState(Class<?> clazz) {
		if (clazz == null) return null;
		ClassLoader cl = clazz.getClassLoader();
		if (cl instanceof BundleReference) return toState(((BundleReference) cl).getBundle().getState());
		return null;
	}

	@Override
	public Map<String, Object> getRequirements(String bundleName, String bundleVersion) throws IOException {
		Bundle b = find(runningContext(), bundleName, bundleVersion);
		if (b == null) return null;
		List<Object> bundles = new ArrayList<>();
		List<Object> packages = new ArrayList<>();
		BundleRevision revision = b.adapt(BundleRevision.class);
		if (revision != null) {
			for (Requirement r: revision.getRequirements(null)) {
				String filter = r.getDirectives().get(Constants.FILTER_DIRECTIVE);
				if (filter == null) continue;
				if ("osgi.wiring.bundle".equals(r.getNamespace())) {
					Map<String, Object> entry = new LinkedHashMap<>();
					entry.put("bundleName", first(BUNDLE_NAME, filter));
					String v = first(VERSION, filter);
					if (v != null) {
						entry.put("bundleVersion", v);
						entry.put("operator", ">=");
					}
					bundles.add(entry);
				}
				else if ("osgi.wiring.package".equals(r.getNamespace())) {
					Map<String, Object> entry = new LinkedHashMap<>();
					entry.put("package", first(PACKAGE_NAME, filter));
					List<Object> versions = new ArrayList<>();
					String v = first(VERSION, filter);
					if (v != null) {
						Map<String, Object> ve = new LinkedHashMap<>();
						ve.put("bundleVersion", v);
						ve.put("operator", ">=");
						versions.add(ve);
					}
					entry.put("versions", versions);
					packages.add(entry);
				}
			}
		}
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("requiredBundles", bundles);
		result.put("requiredPackages", packages);
		return result;
	}

	private static String first(Pattern p, String str) {
		Matcher m = p.matcher(str);
		return m.find() ? m.group(1) : null;
	}

	@Override
	public Map<String, Object> loadBundle(String bundleName, String bundleVersion) throws IOException {
		return toInfo(require(bundleName, bundleVersion));
	}

	@Override
	public List<Map<String, Object>> listCoreBundles() {
		List<Map<String, Object>> list = new ArrayList<>();
		BundleContext ctx = runningContext();
		if (ctx == null) return list;
		for (Bundle b: ctx.getBundles()) {
			if (b.getBundleId() == 0 || "lucee.core".equals(b.getSymbolicName())) list.add(toInfo(b));
		}
		return list;
	}

	@Override
	public List<Map<String, Object>> listBundles() {
		List<Map<String, Object>> list = new ArrayList<>();
		BundleContext ctx = runningContext();
		if (ctx == null) return list;
		for (Bundle b: ctx.getBundles()) {
			list.add(toInfo(b));
		}
		return list;
	}

	@Override
	public URL getResource(String bundleName, String bundleVersion, String path) {
		Bundle b = find(runningContext(), bundleName, bundleVersion);
		return b == null ? null : b.getResource(path);
	}

	@Override
	public ClassLoader getClassLoader(String bundleName, String bundleVersion) throws IOException {
		Bundle b = find(runningContext(), bundleName, bundleVersion);
		if (b == null) throw new IOException("there is no bundle [" + bundleName + ":" + bundleVersion + "]");
		BundleWiring wiring = b.adapt(BundleWiring.class);
		if (wiring == null) {
			List<Bundle> list = new ArrayList<>();
			list.add(b);
			framework().adapt(FrameworkWiring.class).resolveBundles(list);
			wiring = b.adapt(BundleWiring.class);
		}
		if (wiring == null) throw new IOException("the bundle [" + bundleName + ":" + bundleVersion + "] is not resolved");
		return wiring.getClassLoader();
	}

	@Override
	public Map<String, Object> loadFromLocal(String bundleName, String bundleVersion, boolean loadIfNecessary) {
		try {
			Bundle b = find(runningContext(), bundleName, bundleVersion);
			if (b == null && loadIfNecessary) b = findLocal(bundleName, bundleVersion, true);
			return b == null ? null : toInfo(b);
		}
		catch (IOException e) {
			return null;
		}
	}

	@Override
	public void removeLocal(String bundleName, String bundleVersion, boolean removeFile) throws IOException {
		Bundle b = find(runningContext(), bundleName, bundleVersion);
		String location = b == null ? null : b.getLocation();
		uninstall(bundleName, bundleVersion);
		if (removeFile && location != null && location.startsWith("file:")) {
			try {
				File f = new File(new java.net.URI(location));
				if (f.getParentFile() != null && f.getParentFile().equals(bundleDirectory)) f.delete();
			}
			catch (Exception ignored) {}
		}
	}

	// info

	private static String toState(int state) {
		switch (state) {
		case Bundle.ACTIVE:
			return "active";
		case Bundle.INSTALLED:
			return "installed";
		case Bundle.UNINSTALLED:
			return "uninstalled";
		case Bundle.RESOLVED:
			return "resolved";
		case Bundle.STARTING:
			return "starting";
		case Bundle.STOPPING:
			return "stopping";
		}
		return "unknown";
	}

	private static Map<String, Object> toBasicInfo(Bundle b) {
		Map<String, Object> info = new LinkedHashMap<>();
		info.put("name", b.getSymbolicName());
		info.put("version", toLucee(b.getVersion()));
		info.put("id", Long.valueOf(b.getBundleId()));
		info.put("state", toState(b.getState()));
		return info;
	}

	private static Map<String, Object> toInfo(Bundle b) {
		Map<String, Object> info = toBasicInfo(b);
		try {
			info.put("location", b.getLocation());
		}
		catch (IllegalStateException ise) {
			info.put("location", null);
		}
		BundleRevision revision = b.adapt(BundleRevision.class);
		info.put("fragment", Boolean.valueOf(revision != null && (revision.getTypes() & BundleRevision.TYPE_FRAGMENT) != 0));
		Map<String, Object> headers = new LinkedHashMap<>();
		try {
			java.util.Dictionary<String, String> dict = b.getHeaders();
			java.util.Enumeration<String> keys = dict.keys();
			while (keys.hasMoreElements()) {
				String key = keys.nextElement();
				headers.put(key, dict.get(key));
			}
		}
		catch (IllegalStateException ise) {}
		info.put("headers", headers);
		return info;
	}

	/** stops the framework, called when Lucee shuts down */
	public synchronized void shutdown() throws BundleException {
		if (framework != null) {
			framework.stop();
			framework = null;
		}
	}
}
