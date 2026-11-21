// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.gbuffers;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Set;
import org.junit.jupiter.api.Test;

/** The shadow pass's attachment layout: its targets are the shadowcolor indices the programs write, with nothing pinned. */
final class PhaseLayoutShadowTest {

	@Test
	void shadowLayoutIsTheWrittenShadowcolorsInOrderWithNoPinnedTarget() {
		// shadowcolor2 and shadowcolor0 are written: attachment 0 is shadowcolor0, not a pinned colortex0.
		assertArrayEquals(new int[] {0, 2}, PhaseLayout.ofShadow(Set.of(2, 0)).targets());
	}

	@Test
	void shadowLayoutWithNoWriteIsDepthOnly() {
		PhaseLayout layout = PhaseLayout.ofShadow(Set.of());
		assertEquals(0, layout.size());
	}

	@Test
	void shadowLayoutDoesNotAddColortex0() {
		PhaseLayout layout = PhaseLayout.ofShadow(Set.of(3));
		assertArrayEquals(new int[] {3}, layout.targets());
		assertEquals(-1, layout.attachmentOf(0));
		assertEquals(0, layout.attachmentOf(3));
	}

	@Test
	void shadowLayoutAboveTheAttachmentLimitFailsNamingTheShadowPass() {
		Set<Integer> nine = Set.of(0, 1, 2, 3, 4, 5, 6, 7, 8);
		GbufferPlans.LayoutException failure = assertThrows(GbufferPlans.LayoutException.class,
			() -> PhaseLayout.ofShadow(nine));
		assertEquals(true, failure.getMessage().startsWith("the shadow pass writes 9 shadowcolor targets"));
	}
}
