// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.pack;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The biome numbering of the {@code biome} uniform and the {@code BIOME_*} custom-uniform constants (engine-pack-format.md
 * section 13), and the rule that gives a biome its {@code biome_category} value ({@code CAT_*}).
 *
 * <p>A biome's number is its place in the game's biome registration order: the order of {@code Biomes}' keys (javap
 * 2026-11-16 of the 26.3 game jar, the static initialiser of {@code world.level.biome.Biomes}), the void 0 and plains 1.
 * Iris 1.11.7 reads every biome the {@code biomes} and {@code biomes2} sets measured (plains and 18 more) as its place here
 * (mcms-uniforms, 2026-11-16); Bliss 2.1.2's numeric checks (swamp 6, mangrove swamp 7, dark forest 11, the jungles 23 to
 * 25) follow the order from before the pale garden and the dappled forest came. A biome outside the game's own namespace has
 * no constant; its number is its registry id (FrameInputs).
 *
 * <p>The game removed biome categories in 1.19. The category rule {@link #category} reads the biome's tags (the game's
 * {@code worldgen/biome/is_*} tags), a few biomes by name, and falls back to plains in the overworld; it reproduces all 19
 * measured biomes. Its order between tags is free (no biome of the 26.3 data sits in two of those tags that give different
 * categories; the deep oceans are in both ocean tags); the named biomes (desert, swamp, mushroom fields) carry no such tag.
 * Unmeasured parts are marked [inference] where they stand.
 */
public final class BiomeIds {
	/** The registration order, lower case. */
	private static final String[] ORDER = ("the_void plains sunflower_plains snowy_plains ice_spikes desert swamp mangrove_swamp"
		+ " forest flower_forest birch_forest dappled_forest dark_forest pale_garden old_growth_birch_forest old_growth_pine_taiga"
		+ " old_growth_spruce_taiga taiga snowy_taiga savanna savanna_plateau windswept_hills windswept_gravelly_hills"
		+ " windswept_forest windswept_savanna jungle sparse_jungle bamboo_jungle badlands eroded_badlands wooded_badlands meadow"
		+ " cherry_grove grove snowy_slopes frozen_peaks jagged_peaks stony_peaks river frozen_river beach snowy_beach stony_shore"
		+ " warm_ocean lukewarm_ocean deep_lukewarm_ocean ocean deep_ocean cold_ocean deep_cold_ocean frozen_ocean"
		+ " deep_frozen_ocean mushroom_fields dripstone_caves lush_caves deep_dark sulfur_caves nether_wastes warped_forest"
		+ " crimson_forest soul_sand_valley basalt_deltas the_end end_highlands end_midlands small_end_islands end_barrens")
		.split(" ");

	/** The category constants in their {@code CAT_*} order: {@code CAT_NONE} is 0, {@code CAT_NETHER} 16. */
	public static final List<String> CATEGORIES = List.of("NONE", "TAIGA", "EXTREME_HILLS", "JUNGLE", "MESA", "PLAINS",
		"SAVANNA", "ICY", "THE_END", "BEACH", "FOREST", "OCEAN", "DESERT", "RIVER", "SWAMP", "MUSHROOM", "NETHER");

	/**
	 * The category of the game's mountain biomes ({@code is_mountain}): Iris reads 17 for the meadow, the cherry grove, the
	 * snowy slopes and the jagged peaks, a value past the documented {@code CAT_*} list, so no constant names it.
	 */
	public static final int MOUNTAIN = 17;

	/** The tag bits {@link #category} reads, one per {@code worldgen/biome} tag of the game's data. */
	public static final int IS_OCEAN = 1, IS_DEEP_OCEAN = 1 << 1, IS_RIVER = 1 << 2, IS_BEACH = 1 << 3, IS_MOUNTAIN = 1 << 4,
		IS_HILL = 1 << 5, IS_FOREST = 1 << 6, IS_TAIGA = 1 << 7, IS_JUNGLE = 1 << 8, IS_SAVANNA = 1 << 9,
		IS_BADLANDS = 1 << 10, IS_NETHER = 1 << 11, IS_END = 1 << 12, IS_OVERWORLD = 1 << 13;

	private static final List<String> NAMES;
	private static final Map<String, Integer> ID = new HashMap<>();

	static {
		String[] names = new String[ORDER.length];
		for (int i = 0; i < ORDER.length; i++) {
			names[i] = ORDER[i].toUpperCase(Locale.ROOT);
			ID.put(ORDER[i], i);
		}
		NAMES = List.of(names);
	}

	private BiomeIds() {
	}

	/** The biomes' constant names in number order ({@code THE_VOID}, {@code PLAINS}, ...): {@code BIOME_<name>} is its index. */
	public static List<String> names() {
		return NAMES;
	}

	/** The number of a biome registry path of the game's own namespace ({@code plains} is 1), or -1 when the list lacks it. */
	public static int idOf(String registryPath) {
		Integer id = ID.get(registryPath);
		return id == null ? -1 : id;
	}

	/**
	 * The {@code biome_category} value of a biome: {@code path} is its registry path in the game's namespace ("" for any
	 * other), {@code tags} the {@code IS_*} bits of the tags it is in. Measured against Iris (the biomes sets): desert,
	 * swamp and mushroom fields by name; the ocean, river, beach, mountain (17), hill, forest and jungle tags (frozen ocean
	 * 11, frozen river 13, snowy beach 9, the grove a forest, the windswept forest a hill); any other overworld biome plains
	 * (the stony shore and the three caves read 5). The taiga, savanna, badlands, nether and end tags, the mangrove swamp,
	 * the two icy biomes and the void (no category) follow the same reading [inference].
	 */
	public static int category(String path, int tags) {
		switch (path) {
			case "desert":
				return CATEGORIES.indexOf("DESERT");
			case "swamp", "mangrove_swamp":
				return CATEGORIES.indexOf("SWAMP");
			case "mushroom_fields":
				return CATEGORIES.indexOf("MUSHROOM");
			default:
				break;
		}
		if ((tags & IS_NETHER) != 0) return CATEGORIES.indexOf("NETHER");
		if ((tags & IS_END) != 0) return CATEGORIES.indexOf("THE_END");
		if ((tags & (IS_OCEAN | IS_DEEP_OCEAN)) != 0) return CATEGORIES.indexOf("OCEAN");
		if ((tags & IS_RIVER) != 0) return CATEGORIES.indexOf("RIVER");
		if ((tags & IS_BEACH) != 0) return CATEGORIES.indexOf("BEACH");
		if ((tags & IS_MOUNTAIN) != 0) return MOUNTAIN;
		if ((tags & IS_HILL) != 0) return CATEGORIES.indexOf("EXTREME_HILLS");
		if ((tags & IS_FOREST) != 0) return CATEGORIES.indexOf("FOREST");
		if ((tags & IS_TAIGA) != 0) return CATEGORIES.indexOf("TAIGA");
		if ((tags & IS_JUNGLE) != 0) return CATEGORIES.indexOf("JUNGLE");
		if ((tags & IS_SAVANNA) != 0) return CATEGORIES.indexOf("SAVANNA");
		if ((tags & IS_BADLANDS) != 0) return CATEGORIES.indexOf("MESA");
		if (path.equals("snowy_plains") || path.equals("ice_spikes")) return CATEGORIES.indexOf("ICY");
		return (tags & IS_OVERWORLD) != 0 ? CATEGORIES.indexOf("PLAINS") : CATEGORIES.indexOf("NONE");
	}
}
