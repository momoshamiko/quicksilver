// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.gbuffers;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

/**
 * The game's reversed depth convention (its near plane is 1 and its far plane 0; the keep-own-depth passes clear to 0.0):
 * a game variant's projection maps GL's near plane to 1 and its far plane to 0 (plan.md E4, depth conventions).
 */
class ReversedDepthProjectionTest {

	@Test
	void theReversedRangeMapsTheNearPlaneToOneAndTheFarPlaneToZero() {
		float[] m = new float[16];
		new Matrix4f().get(m);
		GbufferDelegate.toReversedZeroToOne(m);
		// Clip z of the point (0, 0, z, w) with w = 1 is m[10] * z + m[14]: GL near (z = -1) must give 1, far (z = 1) 0.
		assertEquals(1f, m[10] * -1f + m[14], 1e-6f, "near plane");
		assertEquals(0f, m[10] * 1f + m[14], 1e-6f, "far plane");
	}

	@Test
	void theReversedZRowIsTheWRowMinusTheForwardZRow() {
		float[] forward = new float[16];
		float[] reversed = new float[16];
		new Matrix4f().get(forward);
		new Matrix4f().get(reversed);
		GbufferDelegate.toZeroToOne(forward);
		GbufferDelegate.toReversedZeroToOne(reversed);
		// Per column: forward z + reversed z = the w row (0.5(z + w) + 0.5(w - z) = w), for the identity's w row.
		for (int column = 0; column < 4; column++) {
			float wRow = column == 3 ? 1f : 0f;
			assertEquals(wRow, forward[4 * column + 2] + reversed[4 * column + 2], 1e-6f, "column " + column);
		}
	}
}
