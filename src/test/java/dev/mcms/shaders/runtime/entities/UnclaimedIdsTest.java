// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.entities;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import org.junit.jupiter.api.Test;

/**
 * The ids of a draw no entity, block-entity or item submit claimed (terrain in the main and the shadow pass): Iris
 * 1.11.7's values, measured with the mcms-ids pack (plan.md E7), whatever the pack's id maps hold. Before E7 these were
 * the maps' "present but not listed" 65535, which a pack reads as an entity.
 */
class UnclaimedIdsTest {

	@Test
	void unclaimedVerticesReadNotAnEntityAndNoBlockEntityOrItem() {
		assertArrayEquals(new int[] { 0, -1, -1 }, EntityCapture.defaultIds(), "entityId, blockEntityId, currentRenderedItemId");
	}
}
