// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.gbuffers;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The line that names the programs that did not build (plan.md E7, S1's runtime half): counts per program, programs in
 * name order, and one reason per program that does not depend on the order the concurrent builds finished in.
 */
class FailureSummaryTest {

	@Test
	void theSummaryCountsPerProgramAndIsTheSameInAnyOrder() {
		List<Map.Entry<String, String>> failed = new ArrayList<>(List.of(
			Map.entry("gbuffers_clouds", "translation failed: B"),
			Map.entry("dh_water", "the pipeline state was refused"),
			Map.entry("gbuffers_clouds", "translation failed: A")));
		String summary = GbufferPlans.summarizeFailures(failed);
		assertEquals("dh_water (1): the pipeline state was refused; gbuffers_clouds (2): translation failed: A", summary);
		Collections.reverse(failed);
		assertEquals(summary, GbufferPlans.summarizeFailures(failed));
		assertEquals("", GbufferPlans.summarizeFailures(List.of()));
	}
}
