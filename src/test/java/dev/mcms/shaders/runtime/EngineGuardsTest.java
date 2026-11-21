// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The engine's two start-of-set refusals (ADR 0007 decisions 2 and 3, E4-B item 17 and 18) and the hooks' inertness
 * while no set is active. The refusal is the decision {@link Engine#sodiumRefusal(String, String)} makes from Sodium's
 * release word and the globals check; {@code Engine.frameStart} logs its message once and sets the state FAILED.
 */
class EngineGuardsTest {

	@Test
	void withoutSodiumNothingIsRefused() {
		assertNull(Engine.sodiumRefusal(null, null));
	}

	@Test
	void aReleaseOffTheAllowListIsRefusedAndNamed() {
		String refusal = Engine.sodiumRefusal("0.9.3-beta.2", null);
		assertNotNull(refusal);
		assertTrue(refusal.contains("0.9.3-beta.2"), "the refusal names the release: " + refusal);
		assertTrue(refusal.contains("allow-list"), "the refusal names the allow-list: " + refusal);
	}

	@Test
	void aFailedGlobalsBlockFailsTheSetsLoadWithItsReason() {
		assertEquals("the block lacks u_CameraOffset",
			Engine.sodiumRefusal("0.9.3-beta.5", "the block lacks u_CameraOffset"));
	}

	@Test
	void anAllowedReleaseWithAGoodGlobalsBlockMayLoad() {
		assertNull(Engine.sodiumRefusal("0.9.3-beta.1", null));
		assertNull(Engine.sodiumRefusal("0.9.3-beta.5", null));
	}

	@Test
	void theBuildPartOfTheReleaseWordDoesNotDecide() {
		assertNull(Engine.sodiumRefusal("0.9.3-beta.1+mc26.3", null));
		assertNotNull(Engine.sodiumRefusal("0.9.3-beta.4+mc26.3", null));
	}

	@Test
	void theHooksAreInertWhileNoSetIsActive() {
		Engine engine = new Engine();
		// Each hook returns on its state check before touching a device, a frame or a target (none exist here).
		engine.clearHook();
		engine.opaqueHook();
		engine.phaseHook(null, null);
		assertEquals(Engine.State.OFF, engine.state());
		assertFalse(engine.useClassicTransparency(), "the game answers its own transparency while no set is active");
	}
}
