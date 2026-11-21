// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * The block centre of the terrain context (E4-B review item 2): Sodium passes the block's position inside its section
 * (0 to 15), and the vertices are section-local, so the centre is that position plus half a block, plus the model
 * offset for blocks that have one.
 */
class TerrainContextTest {

	@Test
	void theCentreIsTheInSectionPositionPlusHalfABlock() {
		assertEquals(0.5f, TerrainContext.centre(0, 0.0), 0.0f);
		assertEquals(15.5f, TerrainContext.centre(15, 0.0), 0.0f);
		assertEquals(7.5f, TerrainContext.centre(7, 0.0), 0.0f);
	}

	@Test
	void theModelOffsetAddsToTheCentre() {
		assertEquals(15.75f, TerrainContext.centre(15, 0.25), 0.0f);
		assertEquals(-0.125f + 0.5f, TerrainContext.centre(0, -0.125), 0.0f);
	}
}
