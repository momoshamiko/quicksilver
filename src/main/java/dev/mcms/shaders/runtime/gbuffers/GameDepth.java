// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.gbuffers;

import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;

/**
 * The depth state a redirected pass draws with (plan.md E4, "Depth compare"): the game draws with reversed depth
 * (near = 1, far = 0, compare {@code GREATER_THAN_OR_EQUAL}, ADR 0006 section 2's context), while the pack's programs
 * see OpenGL's convention (far = 1) through the translator's vertex epilogue, and the engine's depth target clears to
 * 1.0 — so every game depth compare flips ({@code GREATER} ↔ {@code LESS} and their-or-equal forms) and the six game
 * pipelines with a depth bias get the bias negated with it. Pure functions: the unit tests check every flip.
 */
public final class GameDepth {

	private GameDepth() {
	}

	/** The compare a pack-convention pipeline uses where the game used {@code compare}. */
	public static CompareOp flip(CompareOp compare) {
		return switch (compare) {
			case GREATER_THAN -> CompareOp.LESS_THAN;
			case GREATER_THAN_OR_EQUAL -> CompareOp.LESS_THAN_OR_EQUAL;
			case LESS_THAN -> CompareOp.GREATER_THAN;
			case LESS_THAN_OR_EQUAL -> CompareOp.GREATER_THAN_OR_EQUAL;
			default -> compare;  // EQUAL, NOT_EQUAL, ALWAYS_PASS, NEVER_PASS flip to themselves
		};
	}

	/** The depth state the variant draws with: the flipped compare, the same write, the negated bias. */
	public static DepthStencilState flipped(DepthStencilState state) {
		if (state == null) {
			return null;
		}
		return new DepthStencilState(flip(state.depthTest()), state.writeDepth(), -state.depthBiasScaleFactor(),
			-state.depthBiasConstant());
	}
}
