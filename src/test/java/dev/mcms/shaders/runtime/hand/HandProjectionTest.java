// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.hand;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

/**
 * The hand projection against the game's own formula and the {@code MC_HAND_DEPTH} scale (plan.md E4, "Hand",
 * projection and depth bullets). The formula is {@code Projection.getMatrix}'s, read with javap of
 * minecraft-merged-deobf-26.3.jar (2026-11-14): {@code setPerspective(fov * PI/180, width / height, zFar, zNear,
 * zZeroToOne)} — the game passes {@code zFar} ({@code depthFar}) in {@code setPerspective}'s {@code zNear} slot and
 * the 0.05 near plane in its {@code zFar} slot; the pack's matrix adds the GL convention ({@code zZeroToOne = false})
 * and the clip-z scale.
 */
class HandProjectionTest {
	private static final float EPS = 1e-6f;


	@Test
	void handDepthScalesOnlyTheClipZColumn() {
		Matrix4f plain = HandProjection.perspective(70f, 256f, 16f, 9f, 1f, new Matrix4f());
		Matrix4f hand = HandProjection.perspective(70f, 256f, 16f, 9f, HandProjection.HAND_DEPTH, new Matrix4f());
		for (int row = 0; row < 4; row++) {
			for (int col = 0; col < 4; col++) {
				float a = plain.get(row, col), b = hand.get(row, col);
				if (col == 2) {
					assertEquals(a * HandProjection.HAND_DEPTH, b, EPS);
				} else {
					assertEquals(a, b, EPS);
				}
			}
		}
	}

	@Test
	void handDepthIsTheMiddleDocumentedValue() {
		assertEquals(0.125f, HandProjection.HAND_DEPTH, 0f);
	}

	@Test
	void theHandLiesInTheDocumentedWindowDepthBand() {
		// A pack recovers NDC depth as (screenDepth * 2 - 1) / MC_HAND_DEPTH (engine-pack-format.md §3): the hand's
		// NDC z spans [-handDepth, handDepth], window depth 0.4375-0.5625 (plan.md E4).
		assertEquals(0.4375f, HandProjection.windowDepth(-1f, HandProjection.HAND_DEPTH), 1e-6f);
		assertEquals(0.5625f, HandProjection.windowDepth(1f, HandProjection.HAND_DEPTH), 1e-6f);
		// A real hand vertex: project a view-space point and read its window depth back out of the matrix's z row.
		Matrix4f hand = HandProjection.perspective(70f, 256f, 16f, 9f, HandProjection.HAND_DEPTH, new Matrix4f());
		float ndc = transformZ(hand, 0f, 0f, -0.3f, 1f); // 0.3 blocks in front, the hitbox's closest world surface
		// The pack's recovery (engine-pack-format.md §3): (screenDepth * 2 - 1) / MC_HAND_DEPTH gives back the plain NDC depth
		// of the same point, so the hand's scale is undone exactly by the pack.
		Matrix4f plain = HandProjection.perspective(70f, 256f, 16f, 9f, 1f, new Matrix4f());
		float plainNdc = transformZ(plain, 0f, 0f, -0.3f, 1f);
		float screenDepth = HandProjection.windowDepth(ndc, 1f);
		assertEquals(plainNdc, (screenDepth * 2 - 1) / HandProjection.HAND_DEPTH, 1e-5f, "the pack recovers the plain NDC depth");
	}

	@Test
	void theNearAndFarPlanesMapLikeEveryPackMatrix() {
		assertEquals(0.05f, HandProjection.HUD_Z_NEAR, 0f);
		// The pack's matrix keeps setPerspective's own order (zNear 0.05, zFar depthFar) like the engine's other
		// pack projections (FrameUniforms): a point on the near plane maps to NDC -1 and one at depthFar (256) to +1,
		// after the w divide, before the scale.
		Matrix4f plain = HandProjection.perspective(70f, 256f, 16f, 9f, 1f, new Matrix4f());
		assertEquals(-1f, ndcZ(plain, -0.05f), 1e-4f);
		assertEquals(1f, ndcZ(plain, -256f), 1e-4f);
	}

	@Test
	void matchesSetPerspectiveWithThePacksArgumentOrder() {
		// handDepth 1: the matrix equals the plain GL-convention setPerspective(fov, aspect, zNear, zFar, false).
		Matrix4f expected = new Matrix4f().setPerspective(70f * ((float) Math.PI / 180f), 16f / 9f, 0.05f, 256f, false);
		Matrix4f hand = HandProjection.perspective(70f, 256f, 16f, 9f, 1f, new Matrix4f());
		for (int row = 0; row < 4; row++) {
			assertArrayEquals(row(expected, row), row(hand, row), EPS);
		}
	}
	private static float[] row(Matrix4f m, int r) {
		return new float[] {m.get(r, 0), m.get(r, 1), m.get(r, 2), m.get(r, 3)};
	}

	private static float transformZ(Matrix4f m, float x, float y, float z, float w) {
		return m.m02() * x + m.m12() * y + m.m22() * z + m.m32() * w;
	}

	/** NDC z of a view-space point on the -z axis, with the perspective w divide. */
	private static float ndcZ(Matrix4f m, float viewZ) {
		return transformZ(m, 0f, 0f, viewZ, 1f) / (m.m23() * viewZ + m.m33());
	}
}
