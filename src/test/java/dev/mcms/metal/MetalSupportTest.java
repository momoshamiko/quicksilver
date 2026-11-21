// Quicksilver: original code, Apache-2.0.
package dev.mcms.metal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MetalSupportTest {

	@Test
	void supportsAppleSiliconMacOs15AndNewerWithoutIris() {
		assertNull(MetalSupport.check("Mac OS X", "aarch64", "15.0", false));
		assertNull(MetalSupport.check("Mac OS X", "arm64", "26.1", false));
		assertNull(MetalSupport.check("Mac OS X", "aarch64", "27.0", false));
	}

	@Test
	void blocksUnsupportedPlatformsOldMacOsRosettaAndIris() {
		assertEquals(MetalSupport.Blocker.NOT_MACOS, MetalSupport.check("Windows 11", "amd64", "10.0", false));
		assertEquals(MetalSupport.Blocker.NOT_MACOS, MetalSupport.check("Linux", "aarch64", "6.8", false));
		assertEquals(MetalSupport.Blocker.NOT_APPLE_SILICON, MetalSupport.check("Mac OS X", "x86_64", "15.2", false));
		assertEquals(MetalSupport.Blocker.MACOS_TOO_OLD, MetalSupport.check("Mac OS X", "aarch64", "14.7", false));
		assertEquals(MetalSupport.Blocker.IRIS, MetalSupport.check("Mac OS X", "aarch64", "15.2", true));
	}

	@Test
	void parsesMetalEnableWordsConsistently() {
		assertTrue(MetalSupport.parseMetalWord(null));
		assertTrue(MetalSupport.parseMetalWord("true"));
		assertTrue(MetalSupport.parseMetalWord("on"));
		assertTrue(MetalSupport.parseMetalWord("ON"));
		assertTrue(MetalSupport.parseMetalWord("typo"));
		assertFalse(MetalSupport.parseMetalWord("false"));
		assertFalse(MetalSupport.parseMetalWord("FALSE"));
		assertFalse(MetalSupport.parseMetalWord("off"));
		assertFalse(MetalSupport.parseMetalWord(" off "));
	}

	@Test
	void readsMetalEnabledFromQuicksilverProperties(@TempDir Path dir) throws Exception {
		Path missing = dir.resolve("quicksilver.properties");
		assertTrue(MetalSupport.readMetalFromConfig(missing));

		Files.writeString(missing, "metal=false\ncolors=automatic\n");
		assertFalse(MetalSupport.readMetalFromConfig(missing));

		Files.writeString(missing, "metal=FALSE\n");
		assertFalse(MetalSupport.readMetalFromConfig(missing));

		Files.writeString(missing, "metal=off\n");
		assertTrue(MetalSupport.readMetalFromConfig(missing));

		Files.writeString(missing, "metal=true\n");
		assertTrue(MetalSupport.readMetalFromConfig(missing));

		// Malformed unicode escape does not throw during mixin prepare.
		Files.writeString(missing, "shaderPack=\\uZZZZ\nmetal=false\n");
		assertTrue(MetalSupport.readMetalFromConfig(missing));
	}
}
