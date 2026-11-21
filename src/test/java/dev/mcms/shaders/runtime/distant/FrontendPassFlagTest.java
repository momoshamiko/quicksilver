// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.distant;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The frontend's in-pass field resolves in the game version on the test classpath (the game jar is there), so the phase hook can run. */
class FrontendPassFlagTest {

	@Test
	void theInPassFieldHandleResolves() {
		assertTrue(FrontendPassFlag.handleResolved(), "com.mojang.renderpearl.frontend.FrontendCommandEncoder.isInRenderPass not found");
	}
}
