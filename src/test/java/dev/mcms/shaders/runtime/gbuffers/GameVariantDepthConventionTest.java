// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.gbuffers;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

/**
 * A game variant draws with the engine's frame projection converted to zero-to-one ({@code GbufferDelegate.toZeroToOne},
 * the non-reversed convention): the near plane's depth is 0, the far plane's is 1. The flipped game compare (LESS, or
 * LESS_OR_EQUAL) against the engine's 1.0 clear keeps the nearest fragment, so the variant's depth matches a pack
 * program's. A reversed projection would invert the nearest fragment (the game's own convention, not the engine's).
 */
class GameVariantDepthConventionTest {

	private static final float NEAR = 0.1f, FAR = 100f;

	/** The depth of view-space point at distance {@code distance} down -z, after the variant's projection. */
	private static float depthAt(float distance) {
		Matrix4f perspective = new Matrix4f().perspective((float) Math.toRadians(70), 16f / 9f, NEAR, FAR);
		float[] m = new float[16];
		perspective.get(m);  // column-major, OpenGL's convention (clip z from -1 to 1)
		GbufferDelegate.toZeroToOne(m);
		float z = -distance, w = 1f;
		float clipZ = m[2] * 0 + m[6] * 0 + m[10] * z + m[14] * w;
		float clipW = m[3] * 0 + m[7] * 0 + m[11] * z + m[15] * w;
		return clipZ / clipW;
	}

	@Test
	void theNearPlaneIsZeroAndTheFarPlaneIsOne() {
		assertEquals(0f, depthAt(NEAR), 1e-5f, "the near plane maps to 0");
		assertEquals(1f, depthAt(FAR), 1e-5f, "the far plane maps to 1");
	}

	@Test
	void nearerFragmentsHaveSmallerDepth() {
		assertEquals(true, depthAt(1f) < depthAt(10f), "a fragment one block away is nearer than one ten blocks away");
	}
}
