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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.ServiceLoader;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleException;
import org.osgi.framework.BundleReference;
import org.osgi.framework.Constants;
import org.osgi.framework.VersionRange;
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

			FrameworkFactory factory = ServiceLoader.load(FrameworkFactory.class, getClass().getClassLoader()).iterator().next();
			Map<String, String> config = readDefaults();
			addFelixDefaults(config, factory.getClass());
			config.put("felix.cache.rootdir", root.getAbsolutePath());
			String delegation = config.get(Constants.FRAMEWORK_BOOTDELEGATION);
			config.put(Constants.FRAMEWORK_BOOTDELEGATION, delegation == null || delegation.trim().isEmpty() ? "lucee.*" : "lucee.*," + delegation);
			// system properties and environment variables override the defaults
			for (String key: new ArrayList<>(config.keySet())) {
				String over = System.getProperty(key);
				if (over == null) over = System.getenv(key.replace('.', '_').toUpperCase());
				if (over != null && !over.trim().isEmpty() && !Constants.FRAMEWORK_BOOTDELEGATION.equals(key)) config.put(key, over.trim());
			}

			File cache = new File(root, "felix-cache");
			boolean cacheExisted = cache.exists();
			Framework f;
			try {
				f = startFramework(factory, config);
			}
			catch (Exception e) {
				// an invalid felix cache (LDEV-6144, felix reports "Error creating bundle cache." or fails later with another error
				// depending on what is damaged), the cache can be rebuilt, so we delete it and try once more, the bundles are installed
				// again when they are needed
				if (!cacheExisted) throw e;
				deleteRecursive(cache);
				f = startFramework(factory, config);
			}
			return framework = f;
		}
		catch (IOException e) {
			throw e;
		}
		catch (Exception e) {
			throw new IOException("failed to start the OSGi framework: " + e.getMessage(), e);
		}
	}

	private static Framework startFramework(FrameworkFactory factory, Map<String, String> config) throws BundleException {
		Framework f = factory.newFramework(config);
		f.init();
		f.start();
		return f;
	}

	private static void deleteRecursive(File file) {
		File[] children = file.listFiles();
		if (children != null) {
			for (File child: children) {
				deleteRecursive(child);
			}
		}
		file.delete();
	}

	private static Map<String, String> readDefaults() throws IOException {
		Properties props = new Properties();
		URL url = ownResource(OSGiServiceImpl.class, "resources/default.properties");
		if (url != null) {
			try (InputStream is = url.openStream()) {
				props.load(is);
			}
		}
		Map<String, String> config = new HashMap<>();
		for (String key: props.stringPropertyNames()) {
			if (!key.startsWith("org.") && !key.startsWith("felix.")) continue;
			config.put(key, removeQuotes(props.getProperty(key)));
		}
		return config;
	}

	/**
	 * Felix reads its own configuration (system packages, the osgi.ee capabilities of the running Java,
	 * ...) from "/default.properties" with its own class loader. The extension is loaded parent first,
	 * so that finds the default.properties of the Lucee core instead, which has none of these settings.
	 * Bundles that need an osgi.ee capability or a JDK package (javax.crypto, ...) can then not be
	 * resolved. The defaults of Felix are read here from the Felix jar itself and given to the framework
	 * as configuration, with property substitution enabled, so Felix still adds the packages and
	 * capabilities it calculates for the running Java.
	 */
	static void addFelixDefaults(Map<String, String> config, Class<?> felixClass) throws IOException {
		URL url = ownResource(felixClass, "default.properties");
		if (url == null) return;
		Properties props = new Properties();
		try (InputStream is = url.openStream()) {
			props.load(is);
		}
		// nothing to add when that is not the file of Felix
		if (props.getProperty(Constants.FRAMEWORK_SYSTEMPACKAGES) == null && props.getProperty(Constants.FRAMEWORK_SYSTEMCAPABILITIES) == null) return;
		for (String key: props.stringPropertyNames()) {
			if (!config.containsKey(key)) config.put(key, props.getProperty(key));
		}
		// system properties and environment variables still override these values later in framework()
		// the values above contain placeholders like ${jre-${felix.detect.jpms}}
		if (!config.containsKey("felix.systempackages.substitution")) config.put("felix.systempackages.substitution", "true");
	}

	/**
	 * a resource from the jar the given class is loaded from, not one with the same name from a parent
	 * class loader
	 */
	static URL ownResource(Class<?> clazz, String path) throws IOException {
		ClassLoader cl = clazz.getClassLoader();
		if (cl == null) return null;
		String location = null;
		try {
			java.security.CodeSource cs = clazz.getProtectionDomain().getCodeSource();
			if (cs != null && cs.getLocation() != null) location = cs.getLocation().toExternalForm();
		}
		catch (SecurityException se) {
		}
		URL first = null;
		Enumeration<URL> urls = cl.getResources(path);
		while (urls.hasMoreElements()) {
			URL u = urls.nextElement();
			if (first == null) first = u;
			if (location != null && u.toExternalForm().startsWith("jar:" + location + "!/")) return u;
			if (location != null && u.toExternalForm().startsWith(location)) return u;
		}
		return first;
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

	/**
	 * The BundleContext of the framework, for code written for Lucee 8 and earlier that still calls
	 * methods on it directly. Not part of OSGiService, the core finds it reflectively from
	 * CFMLEngineImpl.getBundleContext() and hands it out unchanged.
	 */
	public Object getBundleContext() {
		return runningContext();
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

	/**
	 * the version to download when the bundle is not mapped to a Maven artifact. For a closed
	 * lower bound this is the lower bound itself, so a requirement of "&gt;=X" is treated as the
	 * exact version X. Null when there is no range or the lower bound is open; download() then
	 * fails with a readable message.
	 */
	static String unmappedVersion(VersionRange range) {
		if (range != null && range.getLeftType() == VersionRange.LEFT_CLOSED) return toLucee(range.getLeft());
		return null;
	}

	private static boolean matches(String symbolicName, org.osgi.framework.Version bundleVersion, String name, String version) {
		if (!name.equals(symbolicName)) return false;
		if (version == null || version.trim().isEmpty()) return true;
		String v = version.trim();
		if (v.equalsIgnoreCase(toLucee(bundleVersion)) || v.equalsIgnoreCase(bundleVersion.toString())) return true;
		// the Maven spelling of the same version (33.8.0-jre is 33.8.0.jre, 2.17 is 2.17.0)
		org.osgi.framework.Version parsed = BundleProvider.toVersion(v, null);
		return parsed != null && parsed.equals(bundleVersion);
	}

	private static boolean matches(String symbolicName, org.osgi.framework.Version bundleVersion, String name, VersionRange range) {
		if (!name.equals(symbolicName)) return false;
		return range == null || range.includes(bundleVersion);
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

	/** the newest installed bundle with that name inside the range, any version when the range is null */
	private static Bundle find(BundleContext ctx, String name, VersionRange range) {
		if (ctx == null) return null;
		Bundle best = null;
		for (Bundle b: ctx.getBundles()) {
			if (b.getState() == Bundle.UNINSTALLED || !matches(b.getSymbolicName(), b.getVersion(), name, range)) continue;
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

	/**
	 * the bundle jars of an additional bundle location: the jars in a directory, or the jar itself when the location is a jar file (this.javasettings.bundles accepts both)
	 */
	static File[] jars(File location) {
		if (location == null) return null;
		if (location.isFile()) return location.getName().endsWith(".jar") ? new File[] { location } : null;
		return location.listFiles((d, n) -> n.endsWith(".jar"));
	}

	static File findJar(File dir, String name, String version) {
		File[] jars = jars(dir);
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

	private static final class JarVersion {
		final File jar;
		final org.osgi.framework.Version version;

		JarVersion(File jar, org.osgi.framework.Version version) {
			this.jar = jar;
			this.version = version;
		}
	}

	/** the jar with the newest version of the bundle inside the range, any version when the range is null */
	private static File findJar(File dir, String name, VersionRange range) {
		JarVersion best = findJarVersion(dir, name, range);
		return best == null ? null : best.jar;
	}

	private static JarVersion findJarVersion(File dir, String name, VersionRange range) {
		File[] jars = jars(dir);
		if (jars == null) return null;
		File best = null;
		org.osgi.framework.Version bestVersion = null;
		for (File jar: jars) {
			Object[] nv = read(jar);
			if (nv == null || !matches((String) nv[0], (org.osgi.framework.Version) nv[1], name, range)) continue;
			org.osgi.framework.Version v = (org.osgi.framework.Version) nv[1];
			if (best == null || v.compareTo(bestVersion) > 0) {
				best = jar;
				bestVersion = v;
			}
		}
		return best == null ? null : new JarVersion(best, bestVersion);
	}

	private Bundle install(BundleContext ctx, File jar) throws IOException {
		try {
			return ctx.installBundle(jar.toURI().toString());
		}
		catch (BundleException e) {
			throw new IOException(e.getMessage(), e);
		}
	}

	/**
	 * the installed bundle, installing it from the local bundle directory, one of the given directories or a
	 * download when necessary
	 *
	 * @param versionOnlyMatters when true any version already available is good enough, the version only
	 *            matters when the bundle has to be downloaded
	 */
	private Bundle load(String name, String version, File[] dirs, boolean versionOnlyMatters) throws IOException {
		BundleContext ctx = context();
		Bundle b = find(ctx, name, version);
		if (b == null && versionOnlyMatters) b = find(ctx, name, (String) null);
		if (b != null) return b;

		File jar = findJar(bundleDirectory, name, version);
		if (jar == null && dirs != null) {
			for (File dir: dirs) {
				jar = findJar(dir, name, version);
				if (jar != null) break;
			}
		}
		if (jar == null && versionOnlyMatters) jar = findJar(bundleDirectory, name, (String) null);
		if (jar == null) jar = download(name, version);
		return install(ctx, jar);
	}

	/**
	 * the newest bundle with that name inside the range (any version when the range is null) that is
	 * installed, in the local bundle directory or in one of the given directories, otherwise the newest
	 * release inside the range is downloaded
	 */
	private Bundle load(String name, VersionRange range, File[] dirs) throws IOException {
		BundleContext ctx = context();
		Bundle b = find(ctx, name, range);
		if (b != null) return b;

		JarVersion best = findJarVersion(bundleDirectory, name, range);
		if (dirs != null) {
			for (File dir: dirs) {
				JarVersion candidate = findJarVersion(dir, name, range);
				if (candidate != null && (best == null || candidate.version.compareTo(best.version) > 0)) best = candidate;
			}
		}
		File jar = best == null ? null : best.jar;
		if (jar == null) {
			String version = BundleProvider.getInstance().latestVersion(name, range);
			// not a Maven artifact we know, the lower end of a closed range is used as the exact
			// version (a documented limitation: ">=X" is still exact X for unmapped bundles).
			// an open lower bound leaves version null and download() fails with a readable message.
			if (version == null) version = unmappedVersion(range);
			jar = download(name, version);
		}
		return install(ctx, jar);
	}

	/** download a bundle into the bundle directory */
	private File download(String name, String version) throws IOException {
		BundleProvider provider = BundleProvider.getInstance();
		if (version == null || version.trim().isEmpty()) version = provider.latestVersion(name);
		if (version == null) throw new IOException("the bundle [" + name + "] is not available and there is no version to download");
		URL url;
		try {
			url = provider.getBundleAsURL(name, version);
		}
		catch (BundleException | RuntimeException e) {
			throw new IOException("no download for the bundle [" + name + ":" + version + "]: " + e.getMessage(), e);
		}
		// a unique file, the same bundle may be downloaded by two threads at the same time
		File tmp = File.createTempFile(safe(name) + "-", ".jar.tmp", bundleDirectory);
		try {
			java.net.URLConnection conn = url.openConnection();
			conn.setConnectTimeout(BundleProvider.CONNECTION_TIMEOUT);
			conn.setReadTimeout(BundleProvider.CONNECTION_TIMEOUT * 3);
			try (InputStream is = conn.getInputStream(); OutputStream os = new FileOutputStream(tmp)) {
				byte[] buf = new byte[8192];
				int len;
				while ((len = is.read(buf)) != -1) {
					os.write(buf, 0, len);
				}
			}
			Object[] nv = read(tmp);
			if (nv == null) throw new IOException("the file downloaded from [" + url + "] is not an OSGi bundle");
			File target = new File(bundleDirectory, nv[0] + "-" + toLucee((org.osgi.framework.Version) nv[1]) + ".jar");
			if (!target.isFile() && !tmp.renameTo(target) && !target.isFile()) throw new IOException("failed to move the downloaded bundle to [" + target + "]");
			return target;
		}
		finally {
			if (tmp.isFile()) tmp.delete();
		}
	}

	private static String safe(String name) {
		String str = name.replaceAll("[^A-Za-z0-9._-]", "_");
		// createTempFile needs a prefix of at least 3 characters
		return str.length() < 3 ? str + "___" : str;
	}

	private Bundle loadAndStart(String name, String version, File[] dirs, boolean versionOnlyMatters) throws IOException {
		Bundle b = load(name, version, dirs, versionOnlyMatters);
		Set<String> tried = new HashSet<>();
		List<String> problems = new ArrayList<>();
		while (true) {
			try {
				startIfNecessary(b);
				return b;
			}
			catch (BundleException be) {
				// a bundle this bundle depends on may be missing, load it and try again
				if (!loadMissing(be.getMessage(), dirs, tried, problems)) {
					String msg = be.getMessage();
					if (!problems.isEmpty()) msg += " (failed to load what is missing: " + String.join("; ", problems) + ")";
					throw new IOException(msg, be);
				}
			}
		}
	}

	// one requirement from the message of a failed start, for example
	// (&(osgi.wiring.bundle=a.b)(bundle-version>=2.17.0)(!(bundle-version>=3.0.0)))
	// (&(osgi.wiring.package=a.b)(version>=2.17.0)(!(version>=3.0.0)))
	private static final Pattern MISSING = Pattern.compile("osgi\\.wiring\\.(bundle|package)=([^)]+)\\)((?:\\((?:!\\()?(?:bundle-)?version[<>]?=[^)]+\\)\\)?)*)");
	private static final Pattern CONSTRAINT = Pattern.compile("\\((!\\()?(?:bundle-)?version([<>]?=)([^)]+)\\)");

	/**
	 * the version range of the constraints of a requirement filter, like (version>=2.17.0)(!(version>=3.0.0)),
	 * null when there is no constraint
	 */
	static VersionRange toRange(String constraints) {
		if (constraints == null || constraints.isEmpty()) return null;
		org.osgi.framework.Version floor = null, ceiling = null;
		boolean floorClosed = true, ceilingClosed = false;
		Matcher m = CONSTRAINT.matcher(constraints);
		boolean any = false;
		while (m.find()) {
			boolean not = m.group(1) != null;
			String op = m.group(2);
			org.osgi.framework.Version v;
			try {
				v = org.osgi.framework.Version.parseVersion(m.group(3).trim());
			}
			catch (IllegalArgumentException iae) {
				continue;
			}
			any = true;
			if ("=".equals(op) && !not) {
				floor = ceiling = v;
				floorClosed = ceilingClosed = true;
			}
			else if (">=".equals(op)) {
				if (not) { // (!(version>=X)) is version<X
					ceiling = v;
					ceilingClosed = false;
				}
				else {
					floor = v;
					floorClosed = true;
				}
			}
			else if ("<=".equals(op)) {
				if (not) { // (!(version<=X)) is version>X
					floor = v;
					floorClosed = false;
				}
				else {
					ceiling = v;
					ceilingClosed = true;
				}
			}
		}
		if (!any) return null;
		if (floor == null) floor = org.osgi.framework.Version.emptyVersion;
		return new VersionRange(floorClosed ? VersionRange.LEFT_CLOSED : VersionRange.LEFT_OPEN, floor, ceiling, ceilingClosed ? VersionRange.RIGHT_CLOSED : VersionRange.RIGHT_OPEN);
	}

	// packages of bundles that are no longer part of Lucee, extensions that still import them get the bundle
	private static final Map<String, String> PACKAGE_BUNDLE = new HashMap<>();
	static {
		String[] bcprov = { "asn1", "crypto", "i18n", "jce", "math", "mozilla", "ocsp", "openssl", "voms", "x509" };
		for (String p: bcprov) {
			PACKAGE_BUNDLE.put("org.bouncycastle." + p, "bouncycastle.prov");
		}
		PACKAGE_BUNDLE.put("org.bouncycastle.tsp", "bouncycastle.tsp");
		PACKAGE_BUNDLE.put("org.bouncycastle.cms", "bouncycastle.mail");
		PACKAGE_BUNDLE.put("org.bouncycastle.mail", "bouncycastle.mail");
		PACKAGE_BUNDLE.put("org.bouncycastle.sasn1", "bouncycastle.mail");
		PACKAGE_BUNDLE.put("org.bouncycastle", "bcprov");
		PACKAGE_BUNDLE.put("com.fasterxml.jackson.annotation", "com.fasterxml.jackson.core.jackson-annotations");
		PACKAGE_BUNDLE.put("com.fasterxml.jackson.core", "com.fasterxml.jackson.core.jackson-core");
		PACKAGE_BUNDLE.put("com.fasterxml.jackson.databind", "com.fasterxml.jackson.core.jackson-databind");
		PACKAGE_BUNDLE.put("com.google.common.util.concurrent.internal", "com.google.guava.failureaccess");
		PACKAGE_BUNDLE.put("org.apache.lucene.analysis", "apache.lucene");
		PACKAGE_BUNDLE.put("com.sun.jna", "com.sun.jna");
		PACKAGE_BUNDLE.put("org.apache.commons.lang", "org.apache.commons.lang");
		PACKAGE_BUNDLE.put("jcifs.smb", "org.lucee.jcifs");
		PACKAGE_BUNDLE.put("org.apache.commons.logging", "org.lucee.commons.logging");
		PACKAGE_BUNDLE.put("javax.activation", "com.sun.activation.javax.activation");
	}

	/**
	 * loads the bundles and packages the message of a failed start says are missing, false when nothing
	 * could be loaded, the reasons are added to problems
	 */
	private boolean loadMissing(String message, File[] dirs, Set<String> tried, List<String> problems) {
		boolean loaded = false;
		Matcher m = MISSING.matcher(message);
		while (m.find()) {
			boolean isBundle = "bundle".equals(m.group(1));
			String name = m.group(2).trim();
			VersionRange range = toRange(m.group(3));
			if (!tried.add((isBundle ? "b:" : "p:") + name + ":" + range)) continue;
			try {
				if (isBundle) {
					load(name, range, dirs);
					loaded = true;
					continue;
				}
				// a package
				File jar = findExporting(bundleDirectory, name, range);
				if (jar != null) {
					context().installBundle(jar.toURI().toString());
					loaded = true;
					continue;
				}
				String bundle = PACKAGE_BUNDLE.get(name);
				// a bundle that has the name of the package, like com.google.gson
				if (bundle == null && BundleProvider.getMappings().containsKey(name)) bundle = name;
				if (bundle != null) {
					// the version of the package is the version of the bundle for most bundles.
					// do not fall back to any version when nothing fits the range (that would
					// load a 3.x for a [2.17,3) package). local jars are only used when
					// findExporting confirms Export-Package is inside the range.
					load(bundle, range, dirs);
					loaded = true;
				}
				else {
					problems.add("no bundle known that exports the package [" + name + "]" + (range == null ? "" : " " + range));
				}
			}
			catch (IOException | BundleException | RuntimeException e) {
				problems.add((isBundle ? "bundle [" : "package [") + name + "]" + (range == null ? "" : " " + range) + ": " + e.getMessage());
			}
		}
		return loaded;
	}

	/** a bundle in the directory that exports the package in a version inside the range */
	private static File findExporting(File dir, String pkg, VersionRange range) {
		File[] jars = jars(dir);
		if (jars == null) return null;
		for (File jar: jars) {
			try (JarFile jf = new JarFile(jar)) {
				Manifest mf = jf.getManifest();
				String exports = mf == null ? null : mf.getMainAttributes().getValue("Export-Package");
				if (exports == null) continue;
				for (String part: exports.split(",(?=[^\"]*(?:\"[^\"]*\"[^\"]*)*$)")) {
					String[] attrs = part.split(";(?=[^\"]*(?:\"[^\"]*\"[^\"]*)*$)");
					if (!pkg.equals(attrs[0].trim())) continue;
					if (range == null) return jar;
					org.osgi.framework.Version v = org.osgi.framework.Version.emptyVersion;
					for (int i = 1; i < attrs.length; i++) {
						String a = attrs[i].trim();
						if (a.startsWith("version=")) {
							try {
								v = org.osgi.framework.Version.parseVersion(removeQuotes(a.substring(8)));
							}
							catch (IllegalArgumentException iae) {
							}
						}
					}
					if (range.includes(v)) return jar;
				}
			}
			catch (IOException e) {
			}
		}
		return null;
	}

	// OSGiService

	@Override
	public Class<?> loadClass(String name, String version, String className) throws IOException, ClassNotFoundException {
		return loadClass(name, version, className, null, false);
	}

	@Override
	public Class<?> loadClass(String name, String version, String className, File[] additionalBundleDirectories, boolean versionOnlyMattersForDownload)
			throws IOException, ClassNotFoundException {
		return loadAndStart(name, version, additionalBundleDirectories, versionOnlyMattersForDownload).loadClass(className);
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
			return loadAndStart(name, version, null, false).getResource(path);
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
		Bundle b = loadAndStart(name, version, additionalBundleDirectories, false);
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
