// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.distant;

import org.joml.Matrix4f;

/**
 * The matrices the Distant Horizons draws hand the pack (plan.md E7, "Distant Horizons, as built"; engine-pack-format.md
 * section 17). Both are pure functions, checked by the unit tests.
 *
 * <ul>
 * <li>{@link #toOpenGl}: DH 3.3.4's Blaze3D projection is reverse-Z with a zero-to-one depth range
 * ({@code EDhApiDepthDirection.REVERSE_Z}, {@code EDhApiDepthRange.ZERO_TO_POS_ONE}, {@code BlazeDhRenderApiDefinition},
 * javap 2026-11-15); the pack's depth targets and its {@code dhProjectionInverse} use OpenGL's convention (near -1, far 1,
 * the engine's depth target cleared to 1). The conversion keeps the eye-space mapping and changes only the clip z row:
 * {@code clip_gl.z = clip_dh.w - 2 * clip_dh.z}, which sends DH's near (z_ndc 1) to -1 and far (0) to 1.
 * <li>{@link #modelView}: DH's terrain vertices are section-relative, and DH's shader places them with
 * {@code vPosition + (uModelOffset - uCameraPos)} ({@code terrain/blaze/vert.vsh}). The pack's vertex stage computes
 * {@code gbufferModelViewInverse * gl_ModelViewMatrix * gl_Vertex}, so the draw's model-view is the game's view rotation
 * times the translation {@code uModelOffset - uCameraPos}, and that inverse term leaves the translation alone.
 * </ul>
 */
public final class DistantProjection {

	private DistantProjection() {
	}

	/**
	 * The projection in OpenGL's depth convention, from DH's reverse-Z zero-to-one projection. The input is not changed.
	 */
	public static Matrix4f toOpenGl(Matrix4f reverseZeroToOne) {
		Matrix4f out = new Matrix4f(reverseZeroToOne);
		for (int column = 0; column < 4; column++) {
			out.set(column, 2, reverseZeroToOne.get(column, 3) - 2.0f * reverseZeroToOne.get(column, 2));
		}
		return out;
	}

	/**
	 * Reads DH's matrix array into {@code dest}. DH's {@code DhApiMat4f.getValuesAsArray} is row-major: element {@code 4 * row + column}
	 * (its fields are {@code m<row><column>}, javap 2026-11-15). {@link Matrix4f#set(float[])} reads column-major, so the read is
	 * transposed. {@code dest} is returned.
	 */
	public static Matrix4f fromRowMajor(float[] values, Matrix4f dest) {
		return dest.set(values).transpose();
	}

	/**
	 * The draw's model-view: the game's view rotation with the translation {@code modelOffset - cameraPosition} (block
	 * units, both in world space). The translation is taken in double and rounded once, so a far region keeps its
	 * precision.
	 *
	 * @param viewRotation the game's model-view of the frame (rotation only, as the pack's {@code gbufferModelView})
	 */
	public static Matrix4f modelView(Matrix4f viewRotation, double offsetX, double offsetY, double offsetZ,
			double cameraX, double cameraY, double cameraZ) {
		Matrix4f out = new Matrix4f(viewRotation);
		out.translate((float) (offsetX - cameraX), (float) (offsetY - cameraY), (float) (offsetZ - cameraZ));
		return out;
	}
}
