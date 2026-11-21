// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.gbuffers;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

/** A game variant's projection maps clip z from [-w, w] to [0, w] (plan.md E4), and the other matrix terms stay put. */
class ZeroToOneProjectionTest {

	@Test
	void identityBecomesHalfDepthAtTheMiddleOfTheRange() {
		float[] m = new float[16];
		new Matrix4f().get(m);
		GbufferDelegate.toZeroToOne(m);
		// Column-major: the z row (index 2 of each column) becomes (z + w) / 2; the w row (index 3) is unchanged.
		assertArrayEquals(new float[] {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0.5f, 0, 0, 0, 0.5f, 1}, m, 1e-6f);
	}

	@Test
	void theGlNearAndFarPlanesMapToZeroAndOne() {
		float[] m = new float[16];
		new Matrix4f().get(m);
		GbufferDelegate.toZeroToOne(m);
		// Clip z of the point (0, 0, z, w) with w = 1 is m[10] * z + m[14]: GL near (z = -1) must give 0, far (z = 1) 1.
		assertEquals(0f, m[10] * -1f + m[14], 1e-6f, "near plane");
		assertEquals(1f, m[10] * 1f + m[14], 1e-6f, "far plane");
	}
}
