package org.lucee.extension.osgi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;

/** a bundle that has Require-Bundle gets the required bundles loaded first, the load of a missing bundle keeps the reason as cause */
class RequireBundleTest {

	@TempDir
	File dir;

	private OSGiServiceImpl service;

	@BeforeEach
	void start() throws IOException {
		service = new OSGiServiceImpl(Files.createTempDirectory("osgi-require").toFile());
	}

	@AfterEach
	void stop() {
		service.shutdown();
	}

	private File bundle(String fileName, String symbolicName, String version, String... headers) throws IOException {
		Manifest mf = new Manifest();
		Attributes a = mf.getMainAttributes();
		a.put(Attributes.Name.MANIFEST_VERSION, "1.0");
		a.putValue("Bundle-ManifestVersion", "2");
		a.putValue("Bundle-SymbolicName", symbolicName);
		a.putValue("Bundle-Version", version);
		for (int i = 0; i < headers.length; i += 2) {
			a.putValue(headers[i], headers[i + 1]);
		}
		File jar = new File(dir, fileName);
		new JarOutputStream(new FileOutputStream(jar), mf).close();
		return jar;
	}

	@Test
	void requiredBundlesOfAJar() throws IOException {
		File jar = bundle("a.jar", "test.a", "1.0.0", "Require-Bundle", "test.b;bundle-version=\"[1.0.0,2)\",test.c,test.d;resolution:=optional");
		List<Object[]> required = OSGiServiceImpl.requiredBundles(jar);
		assertEquals(2, required.size());
		assertEquals("test.b", required.get(0)[0]);
		assertEquals(org.osgi.framework.VersionRange.valueOf("[1.0.0,2)"), required.get(0)[1]);
		assertEquals("test.c", required.get(1)[0]);
		assertEquals(null, required.get(1)[1]);
	}

	@Test
	void requiredBundleIsLoadedBeforeTheBundleThatImportsItsPackage() throws IOException {
		// the resolver names the missing package first and no bundle is known for it, only the Require-Bundle tells where it comes from
		bundle("b.jar", "test.b", "1.0.0", "Export-Package", "test.b.pkg;version=\"1.0.0\"");
		bundle("a.jar", "test.a", "1.0.0", "Import-Package", "test.b.pkg;version=\"[1.0,2)\"", "Require-Bundle", "test.b;bundle-version=\"1.0.0\"");
		assertNotNull(service.getClassLoader("test.a", "1.0.0", new File[] { dir }));

		BundleContext ctx = (BundleContext) service.getBundleContext();
		boolean b = false;
		for (Bundle bundle: ctx.getBundles()) {
			if ("test.b".equals(bundle.getSymbolicName())) b = true;
		}
		assertTrue(b, "test.b must be installed");
	}

	@Test
	void failedDownloadIsTheCauseOfTheError() {
		IOException e = assertThrows(IOException.class, () -> service.loadClass("org.lucee.ldev6313.does-not-exist", "1.2.3", "org.ldev6313.DoesNotExist", null, false));
		assertTrue(e.getMessage().contains("org.lucee.ldev6313.does-not-exist"), e.getMessage());
		assertNotNull(e.getCause(), "the failed download is attached as cause");
		assertTrue(e.getCause().getMessage() != null && !e.getCause().getMessage().isEmpty());
	}
}
