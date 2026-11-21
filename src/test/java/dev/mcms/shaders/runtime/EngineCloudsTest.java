// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcms.shaders.pack.PackModel;
import dev.mcms.shaders.pack.PackSource;
import dev.mcms.shaders.pack.StandardMacros;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The decision the cloud renderer mixin asks ({@link Engine#skipsVanillaClouds}): the game's clouds are skipped only while an
 * active set says {@code clouds=off} (plan E6). Fast and fancy leave them drawn, and so does a set that is not active.
 */
class EngineCloudsTest {

	@TempDir
	Path dir;

	@AfterEach
	void turnOff() {
		Engine.get().shutdown();
	}

	/** A pack whose shaders.properties holds {@code line}, loaded from its own folder under the test's temp folder. */
	private PackModel model(String name, String line) throws IOException {
		Path root = dir.resolve(name);
		Files.createDirectories(root.resolve("shaders"));
		Files.writeString(root.resolve("shaders").resolve("shaders.properties"), line);
		try (PackSource source = PackSource.open(root)) {
			return PackModel.load(source, StandardMacros.engine(), "en_us", new Properties());
		}
	}

	@Test
	void anActiveSetThatSaysCloudsOffSkipsTheVanillaClouds() throws IOException {
		Engine.get().activeWithModelForTest(model("off", "clouds=off\n"));
		assertTrue(Engine.get().skipsVanillaClouds(), "clouds=off: the game's clouds are not drawn");
	}

	@Test
	void fastAndFancyCloudsLeaveTheVanillaCloudsDrawn() throws IOException {
		Engine.get().activeWithModelForTest(model("fast", "clouds=fast\n"));
		assertFalse(Engine.get().skipsVanillaClouds(), "clouds=fast: the video setting decides, so the clouds are drawn");
		Engine.get().activeWithModelForTest(model("fancy", "clouds=fancy\n"));
		assertFalse(Engine.get().skipsVanillaClouds(), "clouds=fancy: the video setting decides, so the clouds are drawn");
	}

	@Test
	void aPackWithNoCloudsLineLeavesTheVanillaCloudsDrawn() throws IOException {
		Engine.get().activeWithModelForTest(model("none", "# no clouds line\n"));
		assertFalse(Engine.get().skipsVanillaClouds());
	}

	@Test
	void noActiveSetSkipsNothing() {
		assertFalse(Engine.get().skipsVanillaClouds(), "no set installed: the game draws its clouds");
	}
}
