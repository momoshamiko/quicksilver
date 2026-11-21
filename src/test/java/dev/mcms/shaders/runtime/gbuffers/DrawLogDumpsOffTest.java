// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.gbuffers;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The picture dumps' switch (2026-11-14, E4 debugging): with the draw log off no frame takes dumps, and an open block takes
 * them on its first frame only.
 */
class DrawLogDumpsOffTest {

	@TempDir
	Path dir;

	@BeforeEach
	void setUp() {
		System.clearProperty(DrawLog.PROPERTY);
		DrawLog.reset();
		DrawLog.file(this.dir.resolve("mcms-drawlog.txt"));
	}

	@AfterEach
	void tearDown() {
		System.clearProperty(DrawLog.PROPERTY);
		DrawLog.reset();
	}

	@Test
	void withTheSwitchOffNoFrameTakesDumps() {
		for (int frame = 0; frame < 20; frame++) {
			DrawLog.frame(true);
			assertFalse(DrawLog.dumping());
		}
		assertFalse(Files.exists(this.dir.resolve("mcms-drawlog.txt")));
	}

	@Test
	void anOpenBlockDumpsItsFirstFrameOnly() {
		System.setProperty(DrawLog.PROPERTY, "2:open");
		DrawLog.frame(true);
		assertTrue(DrawLog.dumping());
		DrawLog.frame(true);
		assertFalse(DrawLog.dumping());
		DrawLog.frame(true);
		assertFalse(DrawLog.dumping());
	}
}
