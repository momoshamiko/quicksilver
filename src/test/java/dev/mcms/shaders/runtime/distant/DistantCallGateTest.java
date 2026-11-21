// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.distant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The pass-state decision of Distant Horizons' calls (plan.md E7): a DH call runs only with no game render pass open, and a refused call is
 * counted (the first one is logged).
 */
class DistantCallGateTest {

	@Test
	void aCallRunsWhereNoRenderPassIsOpen() {
		DistantCallGate gate = new DistantCallGate();
		assertTrue(gate.allow(false, "opaque"));
		assertTrue(gate.allow(false, "transparent"));
		assertEquals(0, gate.refusals());
	}

	@Test
	void aCallInsideAnOpenRenderPassIsRefusedAndCounted() {
		DistantCallGate gate = new DistantCallGate();
		assertFalse(gate.allow(true, "transparent"));
		assertFalse(gate.allow(true, "opaque"));
		assertEquals(2, gate.refusals());
	}

	@Test
	void aRefusalDoesNotStopLaterCallsWhenThePassCloses() {
		DistantCallGate gate = new DistantCallGate();
		assertFalse(gate.allow(true, "transparent"));
		assertTrue(gate.allow(false, "transparent"));
		assertEquals(1, gate.refusals());
	}
}
