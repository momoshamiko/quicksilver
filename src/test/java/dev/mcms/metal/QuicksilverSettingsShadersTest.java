// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.metal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The shader engine's two settings keys (ADR 0006 section 5): the file's {@code shaders}/{@code shaderPack} under the
 * {@code -Dmcms.shaders[.pack]} launch flags, which win (ADR 0002's rule). The static fields keep the last loaded
 * values, so every scenario writes both keys: an absent key keeps its earlier value, which these tests never rely on.
 * The gate arm that needs the game (the Metal device) is exercised by the game runs.
 */
class QuicksilverSettingsShadersTest {

	@AfterEach
	void cleanUp() {
		System.clearProperty("mcms.shaders");
		System.clearProperty("mcms.shaders.pack");
		System.clearProperty("mcms.shaders.upscale");
		// The static setting outlives the test: an engine test later in the JVM would install at its scale (ADR 0008 review).
		QuicksilverSettings.setUpscale(QuicksilverSettings.Upscale.OFF);
	}

	private static void load(Path dir, String text) throws IOException {
		// Each load stands alone like its own process: the properties the previous load applied are cleared first
		// (load treats an existing system property as a launch flag, which wins).
		System.clearProperty("mcms.shaders");
		System.clearProperty("mcms.shaders.pack");
		Path file = dir.resolve("quicksilver.properties");
		Files.writeString(file, text);
		QuicksilverSettings.load(file);
	}

	@Test
	void theFileSwitchesTheEngineOnAndNamesItsPack(@TempDir Path dir) throws IOException {
		load(dir, "shaders=on\nshaderPack=My Pack.zip\n");
		assertTrue(QuicksilverSettings.shadersOn());
		assertEquals("My Pack.zip", QuicksilverSettings.shaderPackName());
	}

	@Test
	void launchFlagsWinOverTheFileAndLockTheScreens(@TempDir Path dir) throws IOException {
		Path file = dir.resolve("quicksilver.properties");
		Files.writeString(file, "shaders=on\nshaderPack=My Pack.zip\n");
		System.setProperty("mcms.shaders", "off");         // the launch command set the flags before preLaunch
		System.setProperty("mcms.shaders.pack", "Other.zip");
		QuicksilverSettings.load(file);
		assertFalse(QuicksilverSettings.shadersOn());
		assertEquals("Other.zip", QuicksilverSettings.shaderPackName());
		assertTrue(QuicksilverSettings.shadersLocked());
		assertTrue(QuicksilverSettings.packLocked());
		// A screen cannot change a locked value.
		QuicksilverSettings.setShaders(true);
		QuicksilverSettings.setShaderPack("Third.zip");
		assertFalse(QuicksilverSettings.shadersOn());
		assertEquals("Other.zip", QuicksilverSettings.shaderPackName());
	}

	@Test
	void aScreenChangeIsLiveWhenNoFlagIsSet(@TempDir Path dir) throws IOException {
		// E8: preLaunch no longer copies the file into the properties, so the setters win over the file's values at once.
		load(dir, "shaders=on\nshaderPack=My Pack.zip\n");
		assertFalse(QuicksilverSettings.shadersLocked());
		assertFalse(QuicksilverSettings.packLocked());
		QuicksilverSettings.setShaders(false);
		QuicksilverSettings.setShaderPack("Other.zip");
		assertFalse(QuicksilverSettings.shadersOn());
		assertEquals("Other.zip", QuicksilverSettings.shaderPackName());
		assertEquals(null, System.getProperty("mcms.shaders"), "the file's value is not a launch flag");
	}

	@Test
	void saveWritesEveryKeySoNoWriterDropsAnother(@TempDir Path dir) throws IOException {
		// E8: the page's save used to write metal, colors and hud only, so its Apply dropped shaders and shaderPack.
		load(dir, "shaders=on\nshaderPack=My Pack.zip\nhandDepth=0.25\n");
		QuicksilverSettings.setHandDepth(0.0625f);
		QuicksilverSettings.save();
		java.util.Properties saved = new java.util.Properties();
		try (java.io.Reader in = Files.newBufferedReader(dir.resolve("quicksilver.properties"))) {
			saved.load(in);
		}
		assertEquals("on", saved.getProperty("shaders"));
		assertEquals("My Pack.zip", saved.getProperty("shaderPack"));
		assertEquals("0.0625", saved.getProperty("handDepth"));
		assertTrue(saved.containsKey("metal") && saved.containsKey("colors") && saved.containsKey("hud"));
		load(dir, Files.readString(dir.resolve("quicksilver.properties")));
		assertEquals(0.0625f, QuicksilverSettings.handDepth());
	}

	@Test
	void handDepthTakesOnlyTheDocumentedValues(@TempDir Path dir) throws IOException {
		load(dir, "shaders=on\nshaderPack=p.zip\nhandDepth=0.125\n");
		QuicksilverSettings.setHandDepth(0.3f);
		assertEquals(0.125f, QuicksilverSettings.handDepth());
		load(dir, "shaders=on\nshaderPack=p.zip\nhandDepth=7\n");
		assertEquals(0.125f, QuicksilverSettings.handDepth(), "a value outside the three keeps the earlier one");
		load(dir, "shaders=on\nshaderPack=p.zip\nhandDepth=0.25\n");
		assertEquals(0.25f, QuicksilverSettings.handDepth());
	}

	@Test
	void offInTheFileWinsOverAnEarlierOn(@TempDir Path dir) throws IOException {
		load(dir, "shaders=on\nshaderPack=p.zip\n");
		assertTrue(QuicksilverSettings.shadersOn());
		load(dir, "shaders=off\nshaderPack=p.zip\n");
		assertFalse(QuicksilverSettings.shadersOn());
		// An absent file keeps the last loaded values (the page writes the file; the engine reads the settings live).
		QuicksilverSettings.load(dir.resolve("never-written.properties"));
		assertFalse(QuicksilverSettings.shadersOn());
	}

	@Test
	void trueFalseAndOnOffAreAllAcceptedWords(@TempDir Path dir) throws IOException {
		load(dir, "shaders=true\nshaderPack=p.zip\n");
		assertTrue(QuicksilverSettings.shadersOn());
		load(dir, "shaders=false\nshaderPack=p.zip\n");
		assertFalse(QuicksilverSettings.shadersOn());
		// "maybe" is neither: the earlier value stands (the page's own words are on and off).
		load(dir, "shaders=maybe\nshaderPack=p.zip\n");
		assertFalse(QuicksilverSettings.shadersOn());
	}

	@Test
	void shadersOnLogsNoFalseWarning(@TempDir Path dir) throws IOException {
		// The E3 review: shaders=on went through the true/false parser first and warned "neither true nor false".
		org.apache.logging.log4j.core.Logger logger = (org.apache.logging.log4j.core.Logger) org.apache.logging.log4j.LogManager
			.getLogger("Quicksilver");
		List<String> messages = new java.util.ArrayList<>();
		org.apache.logging.log4j.core.appender.AbstractAppender appender = new org.apache.logging.log4j.core.appender.AbstractAppender(
			"mcms-test", null, null, true, org.apache.logging.log4j.core.config.Property.EMPTY_ARRAY) {
			@Override
			public void append(org.apache.logging.log4j.core.LogEvent event) {
				messages.add(event.getMessage().getFormattedMessage());
			}
		};
		appender.start();
		logger.addAppender(appender);
		try {
			load(dir, "shaders=on\nshaderPack=p.zip\n");
		} finally {
			logger.removeAppender(appender);
			appender.stop();
		}
		assertTrue(messages.stream().noneMatch(m -> m.contains("neither")),
			"shaders=on is a valid value and must not warn: " + messages);
	}

	@Test
	void upscaleIsReadSavedAndHeldByItsProperty(@TempDir Path dir) throws IOException {
		// ADR 0008: the file's upscale, the page's change (live, saved), and -Dmcms.shaders.upscale, which the bench harness sets
		// per scene and which wins while it names a value.
		load(dir, "shaders=on\nshaderPack=p.zip\nupscale=quality\n");
		assertEquals(QuicksilverSettings.Upscale.QUALITY, QuicksilverSettings.upscale());
		QuicksilverSettings.setUpscale(QuicksilverSettings.Upscale.PERFORMANCE);
		assertEquals(2f, QuicksilverSettings.upscale().scale);
		QuicksilverSettings.save();
		load(dir, Files.readString(dir.resolve("quicksilver.properties")));
		assertEquals(QuicksilverSettings.Upscale.PERFORMANCE, QuicksilverSettings.upscale(), "the saved value reads back");
		System.setProperty("mcms.shaders.upscale", "off");
		assertEquals(QuicksilverSettings.Upscale.OFF, QuicksilverSettings.upscale(), "the property wins over the file");
		QuicksilverSettings.setUpscale(QuicksilverSettings.Upscale.QUALITY);
		System.clearProperty("mcms.shaders.upscale");
		assertEquals(QuicksilverSettings.Upscale.PERFORMANCE, QuicksilverSettings.upscale(),
			"a change while the property held the row is not taken");
		System.setProperty("mcms.shaders.upscale", "ultra");
		assertEquals(QuicksilverSettings.Upscale.PERFORMANCE, QuicksilverSettings.upscale(), "a word that names no value is no flag");
	}

	@Test
	void savedConfigHasNoPrivateRepoPathsAndIgnoresInvalidShadersFlag(@TempDir Path dir) throws IOException {
		load(dir, "shaders=on\nshaderPack=p.zip\n");
		QuicksilverSettings.save();
		String saved = Files.readString(dir.resolve("quicksilver.properties"));
		assertFalse(saved.contains("docs/decisions/"), "saved user config must not cite internal repo paths");
		System.setProperty("mcms.shaders", "invalid-word");
		assertFalse(QuicksilverSettings.shadersLocked(), "invalid -Dmcms.shaders word must not lock the setting");
		assertTrue(QuicksilverSettings.shadersOn(), "invalid -Dmcms.shaders word falls back to file value");
	}
}
