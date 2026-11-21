// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.pack;

import static dev.mcms.shaders.pack.BiomeIds.IS_BEACH;
import static dev.mcms.shaders.pack.BiomeIds.IS_FOREST;
import static dev.mcms.shaders.pack.BiomeIds.IS_HILL;
import static dev.mcms.shaders.pack.BiomeIds.IS_JUNGLE;
import static dev.mcms.shaders.pack.BiomeIds.IS_MOUNTAIN;
import static dev.mcms.shaders.pack.BiomeIds.IS_OCEAN;
import static dev.mcms.shaders.pack.BiomeIds.IS_OVERWORLD;
import static dev.mcms.shaders.pack.BiomeIds.IS_RIVER;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * The biome numbers and categories the engine serves (BiomeIds) where Iris 1.11.7 was measured (mcms-uniforms, the
 * {@code biomes} and {@code biomes2} sets, 2026-11-16: plains and 18 more), and the ends of the game's registration order. The tag
 * bits are each biome's {@code worldgen/biome/is_*} tags in the 26.3 data.
 */
class BiomeIdsTest {

	@Test
	void numbersAreTheGamesRegistrationOrder() {
		assertEquals(0, BiomeIds.idOf("the_void"));
		assertEquals(66, BiomeIds.idOf("end_barrens"));
		assertEquals(67, BiomeIds.names().size());
		assertEquals("PLAINS", BiomeIds.names().get(1));
		// A name the game's list lacks has no number here (a mod's biome takes its registry id instead).
		assertEquals(-1, BiomeIds.idOf("not_a_biome"));
	}

	private record Measured(String biome, int number, int tags, int category) {
	}

	@Test
	void numbersAndCategoriesEqualIrissWhereMeasured() {
		int o = IS_OVERWORLD;
		Measured[] measured = {
			new Measured("plains", 1, o, 5), new Measured("desert", 5, o, 12), new Measured("swamp", 6, o, 14),
			new Measured("dark_forest", 12, o | IS_FOREST, 10), new Measured("jungle", 25, o | IS_JUNGLE, 3),
			new Measured("mushroom_fields", 52, o, 15), new Measured("cherry_grove", 32, o | IS_MOUNTAIN, 17),
			new Measured("meadow", 31, o | IS_MOUNTAIN, 17), new Measured("grove", 33, o | IS_FOREST, 10),
			new Measured("snowy_slopes", 34, o | IS_MOUNTAIN, 17), new Measured("jagged_peaks", 36, o | IS_MOUNTAIN, 17),
			new Measured("lush_caves", 54, o, 5), new Measured("deep_dark", 55, o, 5), new Measured("dripstone_caves", 53, o, 5),
			new Measured("frozen_ocean", 50, o | IS_OCEAN, 11), new Measured("frozen_river", 39, o | IS_RIVER, 13),
			new Measured("snowy_beach", 41, o | IS_BEACH, 9), new Measured("stony_shore", 42, o, 5),
			new Measured("windswept_forest", 23, o | IS_HILL, 2)};
		for (Measured m : measured) {
			assertEquals(m.number(), BiomeIds.idOf(m.biome()), m.biome());
			assertEquals(m.category(), BiomeIds.category(m.biome(), m.tags()), m.biome());
		}
	}
}
