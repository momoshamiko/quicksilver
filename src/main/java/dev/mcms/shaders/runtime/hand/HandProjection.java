// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.hand;

import dev.mcms.shaders.pack.StandardMacros;
import org.joml.Matrix4f;

/**
 * The hand's projection matrix (plan.md E4, "Hand"): computed exactly as the game computes it in
 * {@code GameRenderer.render3dHud} — {@code hudProjection.setupPerspective(0.05f, cameraRenderState.depthFar,
 * cameraRenderState.hudFov, window.width, window.height)} (call at bytecode 52) — with the clip-space z column scaled
 * by {@code MC_HAND_DEPTH}.
 *
 * <p>The game's formula ({@code Projection.getMatrix}, javap of minecraft-merged-deobf-26.3.jar, checked 2026-11-14):
 * {@code setPerspective(perspectiveFov * 0.017453292f, width / height, zFar, zNear, isZZeroToOne)} — the game passes
 * {@code zFar} in {@code setPerspective}'s {@code zNear} slot and {@code zNear} (the 0.05 near plane) in its
 * {@code zFar} slot, which with the device's {@code isZZeroToOne} gives the backend's reversed zero-to-one depth. The
 * pack's {@code gl_ProjectionMatrix} is OpenGL's convention like every pack matrix (plan.md E5) and keeps
 * {@code setPerspective}'s own near/far order (near 0.05, far {@code depthFar}), like the engine's other pack
 * projections (FrameUniforms); the backend's own zero-to-one reversed variant of it is the takeover's (E4-A)
 * concern.
 *
 * <p>The hand-depth scale (engine-pack-format.md §3, "MC_HAND_DEPTH"): 0.125 by default (the middle documented value), and
 * since E8 the player's Hand depth setting (0.0625, 0.125 or 0.25; the loaded pack's value, {@code Engine.handDepth}),
 * applied "in clip space automatically through the projection matrix" (Iris docs), so a pack recovers NDC depth as
 * {@code (screenDepth * 2.0 - 1.0) / MC_HAND_DEPTH}: at 0.125 the hand's NDC z lies in [-0.125, 0.125] — window depth
 * 0.4375-0.5625 for NDC z in [-1, 1], and 0.375-0.625 at 0.25. The world cannot cover it: the player's hitbox keeps world
 * surfaces at least 0.3 blocks away, above window depth 0.83 with the near plane at 0.05 (plan.md E4).
 */
public final class HandProjection {
	/** {@code MC_HAND_DEPTH}'s default (engine-pack-format.md §3); the player's setting replaces it (E8). */
	public static final float HAND_DEPTH = StandardMacros.DEFAULT_HAND_DEPTH;

	/** The 3D HUD's near plane: the literal of {@code render3dHud} bytecode 19. */
	public static final float HUD_Z_NEAR = 0.05f;

	private HandProjection() {
	}

	/**
	 * The hand projection as {@code render3dHud} computes it, in OpenGL's convention, with clip z scaled by
	 * {@code handDepth} ({@link #HAND_DEPTH}).
	 */
	public static Matrix4f perspective(float hudFovDeg, float depthFar, float width, float height, float handDepth,
			Matrix4f out) {
		// Projection.getMatrix bytecode 64-92 calls setPerspective(fov, aspect, zFar, zNear, zZeroToOne) — the
		// backend's reversed zero-to-one depth. The pack's gl_ProjectionMatrix keeps setPerspective's own order
		// (zNear 0.05, zFar depthFar), like the engine's other pack projections (FrameUniforms), so a pack's
		// (screenDepth * 2 - 1) / MC_HAND_DEPTH reads NDC depth the way it expects.
		out.setPerspective(hudFovDeg * ((float) Math.PI / 180f), width / height, HUD_Z_NEAR, depthFar, false);
		scaleClipZ(out, handDepth);
		return out;
	}

	/**
	 * Multiplies the matrix's clip-z column (column 2) by {@code handDepth}: the {@code MC_HAND_DEPTH} scale applied
	 * "through the projection matrix" (engine-pack-format.md §3).
	 */
	public static void scaleClipZ(Matrix4f m, float handDepth) {
		m.m02(m.m02() * handDepth);
		m.m12(m.m12() * handDepth);
		m.m22(m.m22() * handDepth);
		m.m32(m.m32() * handDepth);
	}

	/** The GL window depth a hand vertex of NDC z maps to after the scale ({@code (z * handDepth + 1) / 2}). */
	public static float windowDepth(float ndcZ, float handDepth) {
		return (ndcZ * handDepth + 1f) * 0.5f;
	}
}
