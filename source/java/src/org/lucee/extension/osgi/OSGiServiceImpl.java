package org.lucee.extension.osgi;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Dictionary;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.ServiceLoader;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

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

import lucee.loader.engine.CFMLEngineFactory;
import lucee.runtime.osgi.OSGiService;

/**
 * The OSGiService of the OSGi extension, based on Apache Felix. The framework is started when the
 * first bundle is needed and not before.
 */
public final class OSGiServiceImpl implements OSGiService {

	private Framework framework;
	private File root;
	private File bundleDirectory;

	/** used by the ServiceLoader, the root is the one of the running Lucee */
	public OSGiServiceImpl() {
	}

	/** for tests: a service that keeps its bundles and the framework cache under the given directory */
	public OSGiServiceImpl(File root) {
		this.root = root;
	}

	// framework

	private synchronized Framework framework() throws IOException {
		if (framework != null) return framework;
		try {
			if (root == null) root = CFMLEngineFactory.getInstance().getCFMLEngineFactory().getResourceRoot();
			bundleDirectory = new File(root, "bundles");
			if (!bundleDirectory.isDirectory()) bundleDirectory.mkdirs();

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
		try (InputStream is = OSGiServiceImpl.class.getResourceAsStream("/resources/default.properties")) {
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

	/** OSGi version to the version style of Lucee: 1.0.0.5 stays, 1.0.0.RC becomes 1.0.0-RC */
	static String toLucee(org.osgi.framework.Version v) {
		String base = v.getMajor() + "." + v.getMinor() + "." + v.getMicro();
		String q = v.getQualifier();
		if (q.isEmpty()) return base;
		char c = q.charAt(0);
		return base + (c >= '0' && c <= '9' ? "." : "-") + q;
	}

	private static boolean matches(String symbolicName, org.osgi.framework.Version bundleVersion, String name, String version) {
		if (!name.equals(symbolicName)) return false;
		if (version == null || version.trim().isEmpty()) return true;
		String v = version.trim();
		return v.equalsIgnoreCase(toLucee(bundleVersion)) || v.equalsIgnoreCase(bundleVersion.toString());
	}

	private static Bundle find(BundleContext ctx, String name, String version) {
		if (ctx == null) return null;
		Bundle best = null;
		for (Bundle b: ctx.getBundles()) {
			if (b.getState() == Bundle.UNINSTALLED || !matches(b.getSymbolicName(), b.getVersion(), name, version)) continue;
			if (best == null || b.getVersion().compareTo(best.getVersion()) > 0) best = b;
		}
		return best;
	}

	private static void startIfNecessary(Bundle b) throws BundleException {
		if (b.getState() == Bundle.ACTIVE || b.getState() == Bundle.STARTING) return;
		BundleRevision rev = b.adapt(BundleRevision.class);
		if (rev != null && (rev.getTypes() & BundleRevision.TYPE_FRAGMENT) != 0) return;
		b.start();
	}

	// bundle files

	/** symbolic name and OSGi version of a bundle jar, null when the jar is not a bundle */
	private static Object[] read(File jar) {
		try (JarFile jf = new JarFile(jar)) {
			Manifest m = jf.getManifest();
			if (m == null) return null;
			String name = m.getMainAttributes().getValue("Bundle-SymbolicName");
			if (name == null) return null;
			int index = name.indexOf(';');
			if (index != -1) name = name.substring(0, index);
			String v = m.getMainAttributes().getValue("Bundle-Version");
			return new Object[] { name.trim(), org.osgi.framework.Version.parseVersion(v) };
		}
		catch (Exception e) {
			return null;
		}
	}

	private static File findJar(File dir, String name, String version) {
		File[] jars = dir == null ? null : dir.listFiles((d, n) -> n.endsWith(".jar"));
		if (jars == null) return null;
		File best = null;
		org.osgi.framework.Version bestVersion = null;
		for (File jar: jars) {
			Object[] nv = read(jar);
			if (nv == null || !matches((String) nv[0], (org.osgi.framework.Version) nv[1], name, version)) continue;
			org.osgi.framework.Version v = (org.osgi.framework.Version) nv[1];
			if (best == null || v.compareTo(bestVersion) > 0) {
				best = jar;
				bestVersion = v;
			}
		}
		return best;
	}

	/** the installed bundle, installing it from the local bundle directory or one of the given directories when necessary */
	private Bundle load(String name, String version, File[] dirs) throws IOException {
		BundleContext ctx = context();
		Bundle b = find(ctx, name, version);
		if (b == null) {
			File jar = findJar(bundleDirectory, name, version);
			if (jar == null && dirs != null) {
				for (File dir: dirs) {
					jar = findJar(dir, name, version);
					if (jar != null) break;
				}
			}
			if (jar == null) throw new IOException("the bundle [" + name + ":" + version + "] is not available");
			try {
				b = ctx.installBundle(jar.toURI().toString());
			}
			catch (BundleException e) {
				throw new IOException(e.getMessage(), e);
			}
		}
		return b;
	}

	private Bundle loadAndStart(String name, String version, File[] dirs) throws IOException {
		Bundle b = load(name, version, dirs);
		try {
			startIfNecessary(b);
		}
		catch (BundleException e) {
			throw new IOException(e.getMessage(), e);
		}
		return b;
	}

	// OSGiService

	@Override
	public Class<?> loadClass(String name, String version, String className) throws IOException, ClassNotFoundException {
		return loadClass(name, version, className, null, false);
	}

	@Override
	public Class<?> loadClass(String name, String version, String className, File[] additionalBundleDirectories, boolean versionOnlyMattersForDownload)
			throws IOException, ClassNotFoundException {
		return loadAndStart(name, version, additionalBundleDirectories).loadClass(className);
	}

	@Override
	public Class<?> findClass(String className) {
		return findClass(className, (String[]) null);
	}

	@Override
	public Class<?> findClass(String className, String... bundleNames) {
		BundleContext ctx = runningContext();
		if (ctx == null) return null;
		List<String> names = bundleNames == null || bundleNames.length == 0 ? null : Arrays.asList(bundleNames);
		for (Bundle b: ctx.getBundles()) {
			if (b.getBundleId() == 0 || b.getState() == Bundle.UNINSTALLED || b.getState() == Bundle.INSTALLED) continue;
			if (names != null && !names.contains(b.getSymbolicName())) continue;
			try {
				return b.loadClass(className);
			}
			catch (ClassNotFoundException | IllegalStateException e) {
			}
		}
		return null;
	}

	@Override
	public URL getResource(String name, String version, String path) {
		try {
			return loadAndStart(name, version, null).getResource(path);
		}
		catch (IOException e) {
			return null;
		}
	}

	@Override
	public InputStream getResourceAsStream(String name, String version, String path) throws IOException {
		URL url = getResource(name, version, path);
		return url == null ? null : url.openStream();
	}

	@Override
	public ClassLoader getClassLoader(String name, String version) throws IOException {
		return getClassLoader(name, version, null);
	}

	@Override
	public ClassLoader getClassLoader(String name, String version, File[] additionalBundleDirectories) throws IOException {
		Bundle b = loadAndStart(name, version, additionalBundleDirectories);
		BundleWiring wiring = b.adapt(BundleWiring.class);
		if (wiring == null) {
			framework().adapt(FrameworkWiring.class).resolveBundles(Arrays.asList(b));
			wiring = b.adapt(BundleWiring.class);
		}
		if (wiring == null) throw new IOException("the bundle [" + name + ":" + version + "] is not resolved");
		return wiring.getClassLoader();
	}

	@Override
	public boolean install(File jar, boolean start) throws IOException {
		Object[] nv = read(jar);
		if (nv == null) return false;
		String name = (String) nv[0];
		org.osgi.framework.Version version = (org.osgi.framework.Version) nv[1];
		BundleContext ctx = context();
		try {
			Bundle b = find(ctx, name, version.toString());
			if (b == null) {
				// keep a copy in the bundle directory, so the bundle is found after a restart
				File target = new File(bundleDirectory, name + "-" + toLucee(version) + ".jar");
				if (!target.isFile()) {
					try (InputStream is = new java.io.FileInputStream(jar); OutputStream os = new FileOutputStream(target)) {
						byte[] buf = new byte[8192];
						int len;
						while ((len = is.read(buf)) != -1) {
							os.write(buf, 0, len);
						}
					}
				}
				b = ctx.installBundle(target.toURI().toString());
			}
			if (start) startIfNecessary(b);
		}
		catch (BundleException e) {
			throw new IOException(e.getMessage(), e);
		}
		return true;
	}

	@Override
	public void uninstall(File jar) throws IOException {
		Object[] nv = read(jar);
		if (nv == null) return;
		String name = (String) nv[0];
		org.osgi.framework.Version version = (org.osgi.framework.Version) nv[1];
		BundleContext ctx = runningContext();
		Bundle b = find(ctx, name, version.toString());
		try {
			if (b != null) {
				if (b.getState() == Bundle.ACTIVE) b.stop();
				b.uninstall();
			}
		}
		catch (BundleException e) {
			throw new IOException(e.getMessage(), e);
		}
		// the local copy
		File dir = bundleDirectory == null ? new File(root == null ? CFMLEngineFactory.getInstance().getCFMLEngineFactory().getResourceRoot() : root, "bundles") : bundleDirectory;
		File local = findJar(dir, name, version.toString());
		if (local != null) local.delete();
	}

	@Override
	public String getId(Class<?> clazz) {
		if (clazz == null) return null;
		ClassLoader cl = clazz.getClassLoader();
		return cl == null ? null : getId(cl);
	}

	@Override
	public String getId(ClassLoader classLoader) {
		if (!(classLoader instanceof BundleReference)) return null;
		return toId(((BundleReference) classLoader).getBundle());
	}

	@Override
	public String getId(File jar) {
		Object[] nv = read(jar);
		return nv == null ? null : nv[0] + ":" + toLucee((org.osgi.framework.Version) nv[1]);
	}

	private static String toId(Bundle b) {
		// the framework itself and Lucee are not bundles from Lucee's point of view
		if (b == null || b.getBundleId() == 0 || b.getState() == Bundle.UNINSTALLED || "lucee.core".equals(b.getSymbolicName())) return null;
		return b.getSymbolicName() + ":" + toLucee(b.getVersion());
	}

	@Override
	public Map<String, Object> getInfo(Class<?> clazz) {
		if (clazz == null) return null;
		ClassLoader cl = clazz.getClassLoader();
		if (!(cl instanceof BundleReference)) return null;
		Bundle b = ((BundleReference) cl).getBundle();
		if (toId(b) == null) return null;
		Map<String, Object> info = new LinkedHashMap<>();
		info.put("name", b.getSymbolicName());
		info.put("version", toLucee(b.getVersion()));
		info.put("id", Long.valueOf(b.getBundleId()));
		info.put("state", toState(b.getState()));
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
			Dictionary<String, String> dict = b.getHeaders();
			Enumeration<String> keys = dict.keys();
			while (keys.hasMoreElements()) {
				String key = keys.nextElement();
				headers.put(key, dict.get(key));
			}
		}
		catch (IllegalStateException ise) {
		}
		info.put("headers", headers);
		return info;
	}

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

	@Override
	public synchronized void shutdown() {
		if (framework != null) {
			try {
				framework.stop();
			}
			catch (BundleException e) {
			}
			framework = null;
		}
	}
}
