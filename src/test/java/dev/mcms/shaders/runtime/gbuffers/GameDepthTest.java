// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.gbuffers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The depth state flip and the bias negation (plan.md E4, "Depth compare"; ADR 0006 section 2). */
class GameDepthTest {

	@Test
	void everyReversedCompareFlipsToItsOpenGlMirror() {
		assertSame(CompareOp.LESS_THAN_OR_EQUAL, GameDepth.flip(CompareOp.GREATER_THAN_OR_EQUAL));
		assertSame(CompareOp.LESS_THAN, GameDepth.flip(CompareOp.GREATER_THAN));
		assertSame(CompareOp.GREATER_THAN_OR_EQUAL, GameDepth.flip(CompareOp.LESS_THAN_OR_EQUAL));
		assertSame(CompareOp.GREATER_THAN, GameDepth.flip(CompareOp.LESS_THAN));
	}

	@Test
	void symmetricComparesKeepTheirMeaning() {
		assertSame(CompareOp.EQUAL, GameDepth.flip(CompareOp.EQUAL));
		assertSame(CompareOp.NOT_EQUAL, GameDepth.flip(CompareOp.NOT_EQUAL));
		assertSame(CompareOp.ALWAYS_PASS, GameDepth.flip(CompareOp.ALWAYS_PASS));
		assertSame(CompareOp.NEVER_PASS, GameDepth.flip(CompareOp.NEVER_PASS));
	}

	@Test
	void flipTwiceIsTheIdentity() {
		for (CompareOp op : CompareOp.values()) {
			assertSame(op, GameDepth.flip(GameDepth.flip(op)), op.name());
		}
	}

	@Test
	void flippedStateKeepsWriteAndNegatesBothBiasTerms() {
		DepthStencilState game = new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, true, 2.5f, -4.0f);
		DepthStencilState pack = GameDepth.flipped(game);
		assertSame(CompareOp.LESS_THAN_OR_EQUAL, pack.depthTest());
		assertTrue(pack.writeDepth());
		assertEquals(-2.5f, pack.depthBiasScaleFactor(), 0f);
		assertEquals(4.0f, pack.depthBiasConstant(), 0f);
	}

	@Test
	void unbiasedStateStaysUnbiased() {
		DepthStencilState pack = GameDepth.flipped(new DepthStencilState(CompareOp.GREATER_THAN, false));
		assertSame(CompareOp.LESS_THAN, pack.depthTest());
		assertFalse(pack.writeDepth());
		assertEquals(0f, pack.depthBiasScaleFactor(), 0f);
		assertEquals(0f, pack.depthBiasConstant(), 0f);
	}

	@Test
	void noStateStaysNull() {
		assertEquals(null, GameDepth.flipped(null));
	}

	@Test
	void phaseLayoutPinsColourtexZeroAndSortsTheRest() {
		PhaseLayout layout = PhaseLayout.of("opaque", Set.of(6, 2, 4));
		assertEquals(0, layout.targets()[0]);
		assertEquals(java.util.List.of(0, 2, 4, 6), java.util.Arrays.stream(layout.targets()).boxed().toList());
		assertEquals(2, layout.attachmentOf(4));
		assertEquals(-1, layout.attachmentOf(5));
	}

	@Test
	void eightAttachmentsFitAndNineFailWithTheirPhaseAndTargets() {
		PhaseLayout eight = PhaseLayout.of("translucent", Set.of(1, 2, 3, 4, 5, 6, 7));
		assertEquals(8, eight.size());
		GbufferPlans.LayoutException e = assertThrows(GbufferPlans.LayoutException.class,
			() -> PhaseLayout.of("translucent", Set.of(1, 2, 3, 4, 5, 6, 7, 8)));
		assertTrue(e.getMessage().contains("translucent"), e.getMessage());
		assertTrue(e.getMessage().contains("9"), e.getMessage());
	}

	@Test
	void feedbackIsTheTargetsAProgramBothReadsAndWrites() {
		assertEquals(Set.of(6), PhaseLayout.feedback(Set.of(1, 6), Set.of(6, 7)));
		assertEquals(Set.of(), PhaseLayout.feedback(Set.of(1), Set.of(2)));
	}
}
