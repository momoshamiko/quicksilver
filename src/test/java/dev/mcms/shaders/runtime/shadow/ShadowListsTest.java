// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.shadow;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcms.shaders.runtime.terrain.ShadowTerrain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The light's two lists attach and detach together (E5 review item 11): a set's activation attaches both, and a detach
 * (the set's release, or a failed shadow step) leaves neither attached.
 */
class ShadowListsTest {

	private static final ShadowPassSequence.Content ALL = new ShadowPassSequence.Content(true, true, true, true, true);

	@AfterEach
	void reset() {
		ShadowLists.detach();
	}

	@Test
	void attachingBothListsThenDetachingLeavesNeitherAttached() {
		ShadowLists.attach(new ShadowCamera(), ALL);
		assertTrue(ShadowTerrain.get().attached(), "the terrain list attaches with the set");
		assertTrue(ShadowEntities.get().attached(), "the entity list attaches with the set");
		assertTrue(ShadowLists.attached());

		ShadowLists.detach();
		assertFalse(ShadowTerrain.get().attached());
		assertFalse(ShadowEntities.get().attached());
		assertFalse(ShadowLists.attached());
	}

	@Test
	void aDetachAfterAFailedFrameLeavesNoListAttached() {
		ShadowLists.attach(new ShadowCamera(), ALL);
		ShadowFrameDriverTest.Recorder ops = new ShadowFrameDriverTest.Recorder();
		ops.failAt = "openPass";
		ShadowFrameDriver driver = new ShadowFrameDriver(new ShadowFrame(ops), ALL, ShadowLists::detach);

		driver.run();

		assertFalse(driver.active());
		assertFalse(ShadowTerrain.get().attached(), "the failed frame drops the terrain list");
		assertFalse(ShadowEntities.get().attached(), "the failed frame drops the entity list");
	}
}
