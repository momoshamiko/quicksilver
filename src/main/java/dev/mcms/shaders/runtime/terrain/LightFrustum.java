// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.terrain;

import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import net.caffeinemc.mods.sodium.client.render.viewport.frustum.Frustum;
import org.joml.FrustumIntersection;
import org.joml.Matrix4fc;
import org.joml.Vector4f;

/**
 * The light's frustum for Sodium's viewport (E5-B2; the light's terrain list, {@link ShadowTerrain}). It takes the same
 * roles as {@code SimpleFrustum} for the light, with one difference in {@link #testSection}.
 *
 * <p>Sodium's {@code SimpleFrustum.testSection(x, y, z)} tests a section centre against its planes padded by a constant
 * {@code CHUNK_SECTION_PADDED_RADIUS} in plane units, so the padding is {@code 9.125 / |n|} blocks for a plane of normal
 * {@code n}. The planes of an orthographic light have small normals (|n| of about 0.01 to 0.03 for the test's frustum),
 * so the padding reaches about 130 blocks: measured 2026-11-14, the test's light listed sections 134 blocks outside its
 * frustum. This class normalises the six planes, so the padding is in blocks: a section centre is listed when it lies
 * within the section's own support along the plane's normal (8 blocks times the sum of the absolute normal components)
 * plus the margin {@code CHUNK_SECTION_MARGIN} (1.125 blocks), which is the conservative box test in world units.
 *
 * <p>{@link #testAab} and {@link #intersectAab} delegate to JOML's {@link FrustumIntersection}, as {@code SimpleFrustum}
 * does; the plane signs they use do not depend on the normal's length, so they are exact here. With an expansion, the box
 * is grown by the expansion on every side before the exact test, which keeps the answer conservative.
 *
 * <p>The arguments are camera-relative block coordinates, as {@code Viewport} passes them.
 */
public final class LightFrustum implements Frustum {

	/** Six unit planes, four floats each: {@code nx, ny, nz, d}; a point is inside a plane when {@code n.p + d >= 0}. */
	private final float[] planes = new float[24];
	private final FrustumIntersection exact;
	/** Blocks the frustum is widened by on every side (0 for the exact light frustum). */
	private final float expansion;

	/**
	 * @param viewProjection the light's matrix (player space to clip, OpenGL depth: the planes are the clip planes)
	 * @param expansion      blocks to widen the frustum by on every side; 0 for the exact frustum
	 * @throws IllegalArgumentException when a plane has no normal (the matrix is singular in that direction)
	 */
	public LightFrustum(Matrix4fc viewProjection, float expansion) {
		this.exact = new FrustumIntersection(viewProjection);
		this.expansion = expansion;
		Vector4f r0 = viewProjection.getRow(0, new Vector4f());
		Vector4f r1 = viewProjection.getRow(1, new Vector4f());
		Vector4f r2 = viewProjection.getRow(2, new Vector4f());
		Vector4f r3 = viewProjection.getRow(3, new Vector4f());
		store(0, r3, r0, 1f);  // left:   x + w >= 0
		store(1, r3, r0, -1f); // right:  w - x >= 0
		store(2, r3, r1, 1f);  // bottom: y + w >= 0
		store(3, r3, r1, -1f); // top:    w - y >= 0
		store(4, r3, r2, 1f);  // near:   z + w >= 0
		store(5, r3, r2, -1f); // far:    w - z >= 0
	}

	/** Stores plane {@code a + sign * b} normalised, at index {@code i}. */
	private void store(int i, Vector4f a, Vector4f b, float sign) {
		float nx = a.x + sign * b.x;
		float ny = a.y + sign * b.y;
		float nz = a.z + sign * b.z;
		float d = a.w + sign * b.w;
		float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
		if (length == 0f) {
			throw new IllegalArgumentException("the light's plane " + i + " has no normal");
		}
		planes[4 * i] = nx / length;
		planes[4 * i + 1] = ny / length;
		planes[4 * i + 2] = nz / length;
		planes[4 * i + 3] = d / length;
	}

	/**
	 * The section centre (camera-relative) lies within every plane, padded by the section's support plus the margin
	 * ({@code Viewport.CHUNK_SECTION_RADIUS} and {@code Viewport.CHUNK_SECTION_MARGIN}) and the expansion.
	 */
	@Override
	public boolean testSection(float x, float y, float z) {
		return testPadded(x, y, z, Viewport.CHUNK_SECTION_MARGIN + this.expansion);
	}

	/** As {@link #testSection}, with {@code expansion} blocks more on every plane. */
	@Override
	public boolean testSectionExpanded(float x, float y, float z, float expansion) {
		return testPadded(x, y, z, Viewport.CHUNK_SECTION_MARGIN + this.expansion + expansion);
	}

	private boolean testPadded(float x, float y, float z, float margin) {
		for (int i = 0; i < planes.length; i += 4) {
			float nx = planes[i];
			float ny = planes[i + 1];
			float nz = planes[i + 2];
			float reach = Viewport.CHUNK_SECTION_RADIUS * (Math.abs(nx) + Math.abs(ny) + Math.abs(nz)) + margin;
			if (nx * x + ny * y + nz * z + planes[i + 3] < -reach) {
				return false;
			}
		}
		return true;
	}

	@Override
	public boolean testAab(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
		return exact.testAab(minX - expansion, minY - expansion, minZ - expansion,
			maxX + expansion, maxY + expansion, maxZ + expansion);
	}

	@Override
	public int intersectAab(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
		return exact.intersectAab(minX - expansion, minY - expansion, minZ - expansion,
			maxX + expansion, maxY + expansion, maxZ + expansion);
	}
}
