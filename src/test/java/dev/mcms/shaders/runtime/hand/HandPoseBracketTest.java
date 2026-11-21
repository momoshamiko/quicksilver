// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.hand;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.blaze3d.vertex.PoseStack;
import org.junit.jupiter.api.Test;

/**
 * The hand's pose bracket (E4 review item 9): whatever the hand draws, the pose stack the frame reuses comes back to its
 * base pose, so a throw inside the hand's submit cannot leak a pose into the next frame.
 */
class HandPoseBracketTest {

	@Test
	void aBodyThatThrowsStillLeavesThePoseStackBalanced() {
		PoseStack stack = new PoseStack();
		assertThrows(IllegalStateException.class, () -> HandPoseBracket.run(stack, s -> {
			s.translate(0.5f, 0.0f, 0.0f);
			throw new IllegalStateException("the hand's submit failed");
		}));
		assertTrue(stack.isEmpty(), "the pushed pose must be popped when the body throws");
	}

	@Test
	void aBodyThatReturnsNormallyLeavesThePoseStackBalancedToo() {
		PoseStack stack = new PoseStack();
		HandPoseBracket.run(stack, s -> s.translate(0.5f, 0.0f, 0.0f));
		assertTrue(stack.isEmpty());
	}

	@Test
	void theBodySeesTheStackOneLevelDeeper() {
		PoseStack stack = new PoseStack();
		HandPoseBracket.run(stack, s -> assertFalse(s.isEmpty(), "the hand draws inside its own pushed pose"));
		assertTrue(stack.isEmpty());
	}
}
