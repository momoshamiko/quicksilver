// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.terrain;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.net.URL;
import java.net.URLClassLoader;
import org.junit.jupiter.api.Test;

/**
 * The per-release adapter's two halves (ADR 0007): the version gate (the build part after {@code +} is stripped, the
 * two allowed releases pass, everything else fails), and the member lookup per release — {@code update} and
 * {@code traverse} exist with the release's own shape. Each jar the build sets ({@code mcms.test.sodium.beta1} and
 * {@code mcms.test.sodium.beta5}) loads through its own child-first loader: the two releases never share a classpath.
 */
class SodiumAdapterTest {

	@Test
	void theVersionGateStripsTheBuildPartAndAllowsTheTwoCheckedReleases() {
		assertTrue(SodiumAdapter.allowedRelease("0.9.3-beta.1+mc26.3"));
		assertTrue(SodiumAdapter.allowedRelease("0.9.3-beta.5+mc26.3"));
		assertTrue(SodiumAdapter.allowedRelease("0.9.3-beta.5"));
		assertFalse(SodiumAdapter.allowedRelease("0.9.3-beta.2+mc26.3"));
		assertFalse(SodiumAdapter.allowedRelease("0.9.3-beta.4"));
		assertFalse(SodiumAdapter.allowedRelease("0.10.0"));
		assertFalse(SodiumAdapter.allowedRelease(null));
	}

	@Test
	void theBeta5MembersResolveFromItsJar() throws Exception {
		String jar = System.getProperty("mcms.test.sodium.beta5");
		assumeTrue(jar != null, "the beta.5 jar path was not set");
		try (ChildFirst loader = new ChildFirst("sodium-beta5", new URL[] { new java.io.File(jar).toURI().toURL() })) {
			SodiumAdapter.Lookup beta5 = SodiumAdapter.members("0.9.3-beta.5", loader);
			assertTrue(beta5.present(), () -> "lookup failed: " + beta5.failure());
			// The beta.1 shape must NOT resolve on beta.5 classes: that is the NoSuchMethodError the adapter avoids.
			assertFalse(SodiumAdapter.members("0.9.3-beta.1", loader).present());
		}
	}

	@Test
	void theBeta1MembersResolveFromItsJar() throws Exception {
		String jar = System.getProperty("mcms.test.sodium.beta1");
		assumeTrue(jar != null, "the beta.1 jar path was not set");
		try (ChildFirst loader = new ChildFirst("sodium-beta1", new URL[] { new java.io.File(jar).toURI().toURL() })) {
			SodiumAdapter.Lookup beta1 = SodiumAdapter.members("0.9.3-beta.1", loader);
			assertTrue(beta1.present(), () -> "lookup failed: " + beta1.failure());
			assertFalse(SodiumAdapter.members("0.9.3-beta.5", loader).present());
		}
	}

	/** A loader whose own jar answers before the parent classpath, so the jar's Sodium classes are the ones looked up. */
	private static final class ChildFirst extends URLClassLoader {
		ChildFirst(String name, URL[] urls) {
			super(name, urls, ClassLoader.getSystemClassLoader());
		}

		@Override
		protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
			synchronized (getClassLoadingLock(name)) {
				Class<?> loaded = findLoadedClass(name);
				if (loaded == null && name.startsWith("net.caffeinemc.mods.sodium.")) {
					try {
						loaded = findClass(name);
					} catch (ClassNotFoundException e) {
						loaded = null;
					}
				}
				if (loaded == null) {
					loaded = super.loadClass(name, false);
				}
				if (resolve) {
					resolveClass(loaded);
				}
				return loaded;
			}
		}
	}
}
