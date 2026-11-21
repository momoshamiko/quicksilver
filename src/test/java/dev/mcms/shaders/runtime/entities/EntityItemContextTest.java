// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.entities;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * The item context the armour, cape and trim brackets share (plan.md E4, currentRenderedItemId): an armour trim nested
 * in an armour piece names the trim, and leaving the trim restores the armour. Every test leaves the context empty.
 */
class EntityItemContextTest {

	private static final int NONE = -1;

	@Test
	void theInnermostItemIsTheOneTheSubmitsCarry() {
		EntityCapture.pushItem(5);
		EntityCapture.pushItem(9);
		assertEquals(9, EntityCapture.itemContextOr(NONE));
		EntityCapture.popItem();
		assertEquals(5, EntityCapture.itemContextOr(NONE));
		EntityCapture.popItem();
		assertEquals(NONE, EntityCapture.itemContextOr(NONE));
	}

	@Test
	void deepNestingGrowsAndPopOnAnEmptyContextStaysEmpty() {
		for (int i = 0; i < 20; i++) {
			EntityCapture.pushItem(i);
		}
		assertEquals(19, EntityCapture.itemContextOr(NONE));
		for (int i = 0; i < 20; i++) {
			EntityCapture.popItem();
		}
		assertEquals(NONE, EntityCapture.itemContextOr(NONE));
		EntityCapture.popItem();
		EntityCapture.popItem();
		assertEquals(NONE, EntityCapture.itemContextOr(NONE));
		EntityCapture.pushItem(3);
		assertEquals(3, EntityCapture.itemContextOr(NONE));
		EntityCapture.popItem();
		assertEquals(NONE, EntityCapture.itemContextOr(NONE));
	}
}
