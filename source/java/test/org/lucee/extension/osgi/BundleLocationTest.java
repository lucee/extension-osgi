package org.lucee.extension.osgi;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** an additional bundle location (this.javasettings.bundles) can be a directory with bundle jars or a bundle jar */
class BundleLocationTest {

	@TempDir
	File dir;

	private File bundle(String fileName, String symbolicName, String version) throws IOException {
		Manifest mf = new Manifest();
		Attributes a = mf.getMainAttributes();
		a.put(Attributes.Name.MANIFEST_VERSION, "1.0");
		a.putValue("Bundle-ManifestVersion", "2");
		a.putValue("Bundle-SymbolicName", symbolicName);
		a.putValue("Bundle-Version", version);
		File jar = new File(dir, fileName);
		new JarOutputStream(new FileOutputStream(jar), mf).close();
		return jar;
	}

	@Test
	void findsABundleInADirectory() throws IOException {
		File jar = bundle("a.jar", "test.a", "1.0.0");
		assertEquals(jar, OSGiServiceImpl.findJar(dir, "test.a", "1.0.0"));
	}

	@Test
	void findsABundleWhenTheLocationIsTheJarItself() throws IOException {
		File jar = bundle("a.jar", "test.a", "1.0.0");
		assertEquals(jar, OSGiServiceImpl.findJar(jar, "test.a", "1.0.0"));
	}

	@Test
	void aJarLocationOnlyMatchesItsOwnBundle() throws IOException {
		File jar = bundle("a.jar", "test.a", "1.0.0");
		assertNull(OSGiServiceImpl.findJar(jar, "test.b", "1.0.0"));
		assertNull(OSGiServiceImpl.findJar(jar, "test.a", "2.0.0"));
	}

	@Test
	void locationsThatAreNoBundleSource() throws IOException {
		File txt = new File(dir, "a.txt");
		Files.write(txt.toPath(), new byte[0]);
		assertNull(OSGiServiceImpl.jars(txt));
		assertNull(OSGiServiceImpl.jars(null));
		assertNull(OSGiServiceImpl.jars(new File(dir, "missing")));
		assertArrayEquals(new File[0], OSGiServiceImpl.jars(dir));
	}
}
