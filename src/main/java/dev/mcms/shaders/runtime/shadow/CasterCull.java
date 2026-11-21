// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.shadow;

import org.joml.Matrix4fc;

/**
 * The cull of shadow casters that cannot shade the camera's view (E9; shaders.properties {@code shadow.culling}: "Enabling
 * it will prevent geometry which would not cast a shadow through the view frustum from rendering").
 *
 * <p>A caster at p shades the points p + tL, t >= 0, for the direction L the light travels, so a box can shade the view only
 * when it meets the view swept back along L: the points p with p + tL in the view for some t >= 0. That swept volume is
 * convex, and its planes are the side planes of the view that L does not point into (n . L <= 0: moving along L never
 * brings a point across such a plane), and, at each edge between a side plane L points into and one it does not (the view's
 * outline seen along L), the plane through that edge parallel to L. A box wholly outside one of these planes is culled. The
 * test is conservative, as a frustum test is: past a corner it keeps some boxes the swept volume misses, and it never culls
 * one the volume meets; keeping too much costs time, culling too much would cost shadows.
 *
 * <p>The four side planes come from the camera's clip matrix as rows 3 + 0, 3 - 0, 3 + 1 and 3 - 1, which hold in any
 * depth convention; the edges are those of neighbouring planes (left and right each with bottom and top). The near and far
 * planes are left out: the light's box and the render distance bound the depth already, and a box behind the camera is
 * outside the side planes. An edge's plane holds the view on its inner side: its normal turns toward the sum of the two side
 * planes' normals, which points into the view at their edge. An edge that runs along L adds no plane.
 *
 * <p>{@code shadow.culling=reversed} keeps every box within {@code voxelDistance} of the camera (the voxelDistance page: "a
 * radius of voxelDistance will never be culled"). The kept area is the cube of that half-width, which holds the sphere of
 * that radius [choice: the docs say radius; a pack's voxel volume is a box, and the cube never culls what the sphere keeps].
 *
 * <p>Coordinates are camera-relative blocks, as Sodium's viewport passes them. Single owner: the render thread; no
 * allocation per test.
 */
public final class CasterCull {

	/** The neighbouring side planes by index (0 left, 1 right, 2 bottom, 3 top): the view's four edges. */
	private static final int[][] EDGES = {{0, 2}, {0, 3}, {1, 2}, {1, 3}};
	/** Below this, two side planes are parallel (no edge) or an edge runs along L (no plane). */
	private static final float EPSILON = 1e-6f;

	/** The four side planes, unit, four floats each: {@code nx, ny, nz, d}; a point is inside when {@code n.p + d >= 0}. */
	private final float[] sides = new float[16];
	/** Per side plane: whether the light travels into its inside ({@code n . L > 0}); such a plane culls nothing itself. */
	private final boolean[] lightEnters = new boolean[4];
	/** The planes that cull, laid out as {@link #sides}: the side planes L does not point into, then the edges' planes. */
	private final float[] culling = new float[4 * 8];
	private int cullingCount;
	/** The half-width of the kept cube around the camera ({@code reversed}), or a negative value for none. */
	private float keep = -1f;

	/**
	 * Sets this frame's camera and light.
	 *
	 * @param cameraClip    the camera's clip matrix from camera-relative world space (projection times the rotation-only
	 *                      model view; Sodium's {@code setupTerrain} receives it as its position matrix)
	 * @param towardLightX  the unit direction toward the light (the shadow view's Z axis), X
	 * @param towardLightY  Y
	 * @param towardLightZ  Z
	 * @param keepHalfWidth {@code voxelDistance} for {@code reversed}, or a negative value for none
	 * @return false when a plane of the matrix has no normal (nothing is culled then)
	 */
	public boolean set(Matrix4fc cameraClip, float towardLightX, float towardLightY, float towardLightZ,
			float keepHalfWidth) {
		this.keep = keepHalfWidth;
		this.cullingCount = 0;
		boolean ok = store(0, cameraClip.m03() + cameraClip.m00(), cameraClip.m13() + cameraClip.m10(),
			cameraClip.m23() + cameraClip.m20(), cameraClip.m33() + cameraClip.m30());
		ok &= store(1, cameraClip.m03() - cameraClip.m00(), cameraClip.m13() - cameraClip.m10(),
			cameraClip.m23() - cameraClip.m20(), cameraClip.m33() - cameraClip.m30());
		ok &= store(2, cameraClip.m03() + cameraClip.m01(), cameraClip.m13() + cameraClip.m11(),
			cameraClip.m23() + cameraClip.m21(), cameraClip.m33() + cameraClip.m31());
		ok &= store(3, cameraClip.m03() - cameraClip.m01(), cameraClip.m13() - cameraClip.m11(),
			cameraClip.m23() - cameraClip.m21(), cameraClip.m33() - cameraClip.m31());
		if (!ok) {
			return false;
		}
		// The light travels away from the light: L = -towardLight.
		float lx = -towardLightX;
		float ly = -towardLightY;
		float lz = -towardLightZ;
		for (int i = 0; i < 4; i++) {
			lightEnters[i] = sides[4 * i] * lx + sides[4 * i + 1] * ly + sides[4 * i + 2] * lz > 0f;
			if (!lightEnters[i]) {
				addCulling(sides[4 * i], sides[4 * i + 1], sides[4 * i + 2], sides[4 * i + 3]);
			}
		}
		for (int[] edge : EDGES) {
			if (lightEnters[edge[0]] != lightEnters[edge[1]]) {
				addEdgePlane(edge[0], edge[1], lx, ly, lz);
			}
		}
		return true;
	}

	/** Stores side plane i normalised; returns false for a plane without a normal. */
	private boolean store(int i, float nx, float ny, float nz, float d) {
		float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
		if (!(length > 0f)) {
			return false;
		}
		sides[4 * i] = nx / length;
		sides[4 * i + 1] = ny / length;
		sides[4 * i + 2] = nz / length;
		sides[4 * i + 3] = d / length;
		return true;
	}

	/**
	 * Adds the plane through the edge of side planes a and b that runs parallel to L (the swept volume's side at the view's
	 * outline). The edge's direction is a x b; its point closest to the origin solves both plane equations.
	 */
	private void addEdgePlane(int a, int b, float lx, float ly, float lz) {
		float ax = sides[4 * a], ay = sides[4 * a + 1], az = sides[4 * a + 2], ad = sides[4 * a + 3];
		float bx = sides[4 * b], by = sides[4 * b + 1], bz = sides[4 * b + 2], bd = sides[4 * b + 3];
		float cos = ax * bx + ay * by + az * bz;
		float sin2 = 1f - cos * cos;
		if (sin2 < EPSILON) {
			return;  // parallel planes meet at no edge
		}
		// A point of the edge: p = ka a + kb b with a.p = -ad and b.p = -bd.
		float ka = (-ad + bd * cos) / sin2;
		float kb = (-bd + ad * cos) / sin2;
		float px = ka * ax + kb * bx;
		float py = ka * ay + kb * by;
		float pz = ka * az + kb * bz;
		float rx = ay * bz - az * by;
		float ry = az * bx - ax * bz;
		float rz = ax * by - ay * bx;
		float mx = ry * lz - rz * ly;
		float my = rz * lx - rx * lz;
		float mz = rx * ly - ry * lx;
		float length = (float) Math.sqrt(mx * mx + my * my + mz * mz);
		if (!(length > EPSILON)) {
			return;  // the edge runs along L
		}
		mx /= length;
		my /= length;
		mz /= length;
		if (mx * (ax + bx) + my * (ay + by) + mz * (az + bz) < 0f) {
			mx = -mx;
			my = -my;
			mz = -mz;
		}
		addCulling(mx, my, mz, -(mx * px + my * py + mz * pz));
	}

	private void addCulling(float nx, float ny, float nz, float d) {
		int at = 4 * cullingCount++;
		culling[at] = nx;
		culling[at + 1] = ny;
		culling[at + 2] = nz;
		culling[at + 3] = d;
	}

	/**
	 * Whether the box (camera-relative blocks) may cast a shadow into the view: false only when the box is wholly outside a
	 * plane of the view swept along the light, and outside the kept cube.
	 */
	public boolean mayShade(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
		if (keep >= 0f && minX <= keep && maxX >= -keep && minY <= keep && maxY >= -keep && minZ <= keep && maxZ >= -keep) {
			return true;
		}
		for (int i = 0; i < cullingCount; i++) {
			float nx = culling[4 * i];
			float ny = culling[4 * i + 1];
			float nz = culling[4 * i + 2];
			// The box corner furthest along the normal: the box is outside when even that corner is.
			float far = nx * (nx >= 0f ? maxX : minX) + ny * (ny >= 0f ? maxY : minY) + nz * (nz >= 0f ? maxZ : minZ);
			if (far + culling[4 * i + 3] < 0f) {
				return false;
			}
		}
		return true;
	}

	/** {@link #mayShade} for the cube of half-width {@code radius} around a centre. */
	public boolean mayShade(float x, float y, float z, float radius) {
		return mayShade(x - radius, y - radius, z - radius, x + radius, y + radius, z + radius);
	}
}
