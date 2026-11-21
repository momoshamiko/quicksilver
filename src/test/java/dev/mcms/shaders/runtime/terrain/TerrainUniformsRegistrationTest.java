// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.terrain;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcms.shaders.runtime.gbuffers.GbufferDelegate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The globals staging's registration with the active delegate (the E4-A {@code constantBytes} contract, 2026-11-14): a
 * delegate lives as long as its set, so the staging registers once per delegate instance, and a release forgets the
 * registration. The set's delegate is installed at the frame start, before the terrain latch, so the staging is
 * allocated and registered in the same sync: the set's first frame draws its terrain with the globals bound (E4 review
 * item 4: the first frame used to drop its terrain with "u_Globals mirror is not bound").
 */
class TerrainUniformsRegistrationTest {

	@BeforeEach
	void startBeforeAnyActivation() {
		TerrainUniforms.forgetStagingForTest();
	}

	@AfterEach
	void endTheSet() {
		GbufferDelegate.install(null);
		TerrainUniforms.forgetStagingForTest();
	}

	@Test
	void theStagingRegistersOncePerDelegateInstanceAndSteadyStateDoesNot() {
		// The delegates are identities here: the registration decision never reads their plans or bindings.
		GbufferDelegate first = new GbufferDelegate(null, null);
		GbufferDelegate second = new GbufferDelegate(null, null);
		assertFalse(TerrainUniforms.registrationNeeded(null, null), "no delegate and none registered: nothing");
		assertTrue(TerrainUniforms.registrationNeeded(first, null), "the set's delegate registers the staging");
		assertFalse(TerrainUniforms.registrationNeeded(first, first), "the next frame registers nothing");
		assertTrue(TerrainUniforms.registrationNeeded(second, first), "the next set's new delegate registers");
		assertFalse(TerrainUniforms.registrationNeeded(second, second), "steady state registers nothing");
		assertTrue(TerrainUniforms.registrationNeeded(null, second), "the engine's release forgets the registration");
	}

	@Test
	void theSetsFirstFrameHasTheGlobalsBoundBeforeItsFirstTerrainDraw() {
		GbufferDelegate delegate = new GbufferDelegate(null, null);
		GbufferDelegate.install(delegate);  // the set's install, at the frame start
		TerrainUniforms.syncConstantBlocks();  // the same frame's terrain latch, before the terrain layers draw
		assertNotNull(delegate.constantBytesAt(TerrainUniforms.GLOBALS_SLOT),
			"the globals are registered in the frame the set becomes active, not one frame later");
	}
}
