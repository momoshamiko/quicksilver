// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.gbuffers;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/** The phase split's partition (ADR 0006 section 3, 2026-11-15): one group when the union fits, outliers out otherwise. */
class PhaseLayoutGroupsTest {

	private static Set<Integer> targets(int... values) {
		TreeSet<Integer> set = new TreeSet<>();
		for (int value : values) {
			set.add(value);
		}
		return set;
	}

	private static Set<Integer> range(int first, int last) {
		TreeSet<Integer> set = new TreeSet<>();
		for (int value = first; value <= last; value++) {
			set.add(value);
		}
		return set;
	}

	@Test
	void aPhaseWhoseUnionFitsKeepsTheSingleLayoutItHadBefore() {
		Map<String, Set<Integer>> programs = Map.of("gbuffers_basic", targets(0, 6), "gbuffers_textured", targets(0, 3, 6));
		PhaseLayout.Groups groups = PhaseLayout.groups("TRANSLUCENT", programs);
		assertEquals(1, groups.layouts().size());
		assertArrayEquals(PhaseLayout.of("TRANSLUCENT", targets(0, 3, 6)).targets(), groups.layouts().get(0).targets());
		assertEquals(Map.of("gbuffers_basic", 0, "gbuffers_textured", 0), groups.groupOf());
	}

	@Test
	void theClouds_writingEveryTargetUpToSeven_goToTheirOwnGroupAndTheRestStaysTogether() {
		// Complementary's clouds writes colortex0 to colortex7 (no DRAWBUFFERS, the default); the other translucent programs
		// write 0, 3, 4, 6, 8 and 13 between them, which fits, so only the clouds program is an outlier.
		Map<String, Set<Integer>> programs = Map.of("gbuffers_clouds", range(0, 7), "gbuffers_water", targets(0, 3, 4, 6, 8),
			"gbuffers_entities_translucent", targets(0, 3, 4, 6, 13), "gbuffers_basic", targets(0, 4, 6));
		PhaseLayout.Groups groups = PhaseLayout.groups("TRANSLUCENT", programs);
		assertEquals(2, groups.layouts().size());
		assertEquals(Map.of("gbuffers_clouds", 1, "gbuffers_water", 0, "gbuffers_entities_translucent", 0,
			"gbuffers_basic", 0), groups.groupOf());
		assertArrayEquals(new int[] {0, 3, 4, 6, 8, 13}, groups.layouts().get(0).targets());
		assertArrayEquals(new int[] {0, 1, 2, 3, 4, 5, 6, 7}, groups.layouts().get(1).targets());
	}

	@Test
	void onATieTheLaterNameGoesFirst_soBlissWaterAndHandWaterStayTogetherApartFromTheRest() {
		// Bliss's translucent programs (measured 2026-11-15): water and hand_water write the same targets; block and entities
		// write 1, 2, 8 and 15; textured and weather write 2 and 9. The union is nine targets.
		Map<String, Set<Integer>> programs = Map.of("gbuffers_water", targets(2, 7, 11, 14),
			"gbuffers_hand_water", targets(2, 7, 11, 14), "gbuffers_block_translucent", targets(1, 2, 8, 15),
			"gbuffers_entities_translucent", targets(1, 2, 8, 15), "gbuffers_textured", targets(2, 9),
			"gbuffers_weather", targets(2, 9));
		PhaseLayout.Groups groups = PhaseLayout.groups("TRANSLUCENT", programs);
		assertEquals(2, groups.layouts().size());
		int water = groups.groupOf().get("gbuffers_water");
		assertEquals(water, groups.groupOf().get("gbuffers_hand_water"), "water and hand_water are one group");
		assertNotEquals(water, groups.groupOf().get("gbuffers_block_translucent"), "block is apart from water");
		assertEquals(groups.groupOf().get("gbuffers_block_translucent"), groups.groupOf().get("gbuffers_entities_translucent"));
	}

	@Test
	void theOverflowIsPartitionedTheSameWayUntilEveryGroupFits() {
		// Three disjoint programs of four targets each: their union is thirteen, so two of them cannot share a group. The
		// later name is the outlier at every step, which leaves one program per group.
		Map<String, Set<Integer>> programs = Map.of("gbuffers_a", targets(1, 2, 3, 4), "gbuffers_b", targets(5, 6, 7, 8),
			"gbuffers_c", targets(9, 10, 11, 12));
		PhaseLayout.Groups groups = PhaseLayout.groups("TRANSLUCENT", programs);
		assertEquals(3, groups.layouts().size());
		assertEquals(Map.of("gbuffers_a", 0, "gbuffers_b", 1, "gbuffers_c", 2), groups.groupOf());
		for (PhaseLayout layout : groups.layouts()) {
			assertTrue(layout.size() <= PhaseLayout.MAX_ATTACHMENTS, "every group fits the attachment limit");
		}
	}

	@Test
	void aProgramWhoseOwnTargetsExceedTheLimitIsRefusedByName() {
		Map<String, Set<Integer>> programs = Map.of("gbuffers_huge", range(1, 9), "gbuffers_small", targets(0, 2));
		GbufferPlans.LayoutException refused = assertThrows(GbufferPlans.LayoutException.class,
			() -> PhaseLayout.groups("TRANSLUCENT", programs));
		assertTrue(refused.getMessage().contains("gbuffers_huge"), "the message names the program: " + refused.getMessage());
	}
}
