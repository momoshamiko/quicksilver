// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.terrain;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

/**
 * The light's section selection on a synthetic section layout (E5-B2): the sections wholly inside the light's frustum
 * are listed, and the sections wholly outside it (by more than a margin) are not. The frustum test is Sodium's own
 * {@code Viewport.isBoxVisible}, built by {@link ShadowTerrain#lightViewport} with {@link LightFrustum}. It takes a
 * section's centre in camera-relative blocks and tests it against the normalised frustum planes, padded by the section's
 * support (8 blocks times the sum of the absolute normal components) and a 1.125-block margin. So it lists every section
 * that touches the frustum and may list a section up to about 1.1 blocks outside it.
 *
 * <p>The expected answer is computed independently from the light's matrix: the signed world distance of each of the
 * eight box corners to each of the six clip planes. A box is inside when every corner is inside every plane, and outside
 * when every corner is more than {@link #MARGIN} blocks outside one plane. Boxes in between are not asserted.
 *
 * <p>The layout is a grid of 16-block sections around a camera at a non-integer position, under a rotated light, so the
 * camera offset and the rotation both take part.
 */
class ShadowTerrainSelectionTest {

	private static final double CAMERA_X = 37.4;
	private static final double CAMERA_Y = 80.9;
	private static final double CAMERA_Z = -21.2;

	/** Blocks a box must lie outside a plane by before the test requires it not to be listed (Sodium's margin is ~2). */
	private static final float MARGIN = 3f;

	@Test
	void sectionsInsideTheLightFrustumAreListedAndNoneOutsideIt() {
		Matrix4f projection = new Matrix4f().ortho(-40f, 40f, -30f, 30f, -120f, 120f);
		Matrix4f modelView = new Matrix4f().rotateX(0.6f).rotateY(-0.4f).translate(3.5f, -2f, 1.25f);
		Matrix4f viewProjection = new Matrix4f(projection).mul(modelView);
		Viewport light = ShadowTerrain.lightViewport(viewProjection, CAMERA_X, CAMERA_Y, CAMERA_Z);

		int inside = 0;
		int outside = 0;
		for (int sx = -12; sx <= 12; sx++) {
			for (int sy = -10; sy <= 10; sy++) {
				for (int sz = -12; sz <= 12; sz++) {
					int ox = sx * 16;
					int oy = sy * 16;
					int oz = sz * 16;
					String where = "section at block " + ox + "," + oy + "," + oz;
					// isBoxVisible takes the section's centre, as the padded radius assumes.
					boolean listed = light.isBoxVisible(ox + 8, oy + 8, oz + 8);
					switch (classify(viewProjection, ox, oy, oz)) {
						case INSIDE -> {
							inside++;
							assertTrue(listed, () -> where + " is inside the light frustum but not listed");
						}
						case OUTSIDE -> {
							outside++;
							assertFalse(listed, () -> where + " is outside the light frustum by more than " + MARGIN + " blocks but listed");
						}
						case CROSSING -> {
							// not asserted: either answer is a valid conservative result
						}
					}
				}
			}
		}
		// The layout exercises both answers, so the check cannot pass on an empty or a full list.
		assertTrue(inside > 50, "too few sections inside the light frustum: " + inside);
		assertTrue(outside > 50, "too few sections outside the light frustum: " + outside);
	}

	private enum Verdict { INSIDE, OUTSIDE, CROSSING }

	/**
	 * Classifies the section box {@code [origin, origin + 16]} (world blocks) against the light's six clip planes, in
	 * world distance: each clip plane is an affine function of the camera-relative position, divided by the length of its
	 * normal.
	 */
	private static Verdict classify(Matrix4f viewProjection, int ox, int oy, int oz) {
		Vector4f r0 = viewProjection.getRow(0, new Vector4f());
		Vector4f r1 = viewProjection.getRow(1, new Vector4f());
		Vector4f r2 = viewProjection.getRow(2, new Vector4f());
		Vector4f r3 = viewProjection.getRow(3, new Vector4f());
		Vector4f[] planes = {
			r3.add(r0, new Vector4f()), // left: x + w >= 0
			r3.sub(r0, new Vector4f()), // right: w - x >= 0
			r3.add(r1, new Vector4f()), // bottom
			r3.sub(r1, new Vector4f()), // top
			r3.add(r2, new Vector4f()), // near: z + w >= 0
			r3.sub(r2, new Vector4f()), // far: w - z >= 0
		};
		boolean everyCornerInsideEveryPlane = true;
		for (Vector4f plane : planes) {
			float length = (float) Math.sqrt(plane.x * plane.x + plane.y * plane.y + plane.z * plane.z);
			float minDistance = Float.POSITIVE_INFINITY;
			float maxDistance = Float.NEGATIVE_INFINITY;
			for (int corner = 0; corner < 8; corner++) {
				float x = ox + ((corner & 1) == 0 ? 0f : 16f) - (float) CAMERA_X;
				float y = oy + ((corner & 2) == 0 ? 0f : 16f) - (float) CAMERA_Y;
				float z = oz + ((corner & 4) == 0 ? 0f : 16f) - (float) CAMERA_Z;
				float distance = (plane.x * x + plane.y * y + plane.z * z + plane.w) / length;
				minDistance = Math.min(minDistance, distance);
				maxDistance = Math.max(maxDistance, distance);
			}
			if (maxDistance < -MARGIN) {
				return Verdict.OUTSIDE;
			}
			if (minDistance < 0f) {
				everyCornerInsideEveryPlane = false;
			}
		}
		return everyCornerInsideEveryPlane ? Verdict.INSIDE : Verdict.CROSSING;
	}
}
