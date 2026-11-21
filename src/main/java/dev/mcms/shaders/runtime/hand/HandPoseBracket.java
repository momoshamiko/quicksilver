// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.hand;

import com.mojang.blaze3d.vertex.PoseStack;

/**
 * The hand's pose bracket (E4 review item 9): the pose is pushed, the body draws the hand's pose and submits its models,
 * and the pose is popped in a {@code finally}. The stack is reused every frame, so an exception between the push and the
 * pop would leave one pose on it for every later frame.
 */
final class HandPoseBracket {

	/** The hand's own drawing, between the push and the pop. */
	interface Body {
		void draw(PoseStack stack);
	}

	private HandPoseBracket() {
	}

	/** Runs {@code body} inside one pushed pose; the pose is popped whether {@code body} returns or throws. */
	static void run(PoseStack stack, Body body) {
		stack.pushPose();
		try {
			body.draw(stack);
		} finally {
			stack.popPose();
		}
	}
}
