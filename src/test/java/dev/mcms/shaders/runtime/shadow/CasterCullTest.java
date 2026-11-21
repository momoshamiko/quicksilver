// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.shadow;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

/**
 * The cull of casters that cannot shade the view (E9, shaders.properties shadow.culling): a box is culled only when it is
 * wholly outside the view swept back along the light, by a side plane the light does not travel into or by the plane
 * through an edge of the view's outline parallel to the light.
 */
final class CasterCullTest {

	/** An orthographic camera looking down -Z: its left plane is x >= -10, its right plane x <= 10. */
	private static final Matrix4f ORTHO = new Matrix4f().setOrtho(-10f, 10f, -10f, 10f, 0.1f, 100f);
	/** A 90-degree perspective camera looking down -Z: inside when |x| <= -z and |y| <= -z. */
	private static final Matrix4f PERSPECTIVE = new Matrix4f().setPerspective((float) Math.toRadians(90.0), 1f, 0.05f, 512f);

	@Test
	void aBoxOutsideAPlaneIsCulledOnlyWhenTheLightDoesNotTravelIntoIt() {
		CasterCull cull = new CasterCull();
		// Overhead sun: toward the light is +Y, the light travels down; the left plane's normal (+X) is across it.
		cull.set(ORTHO, 0f, 1f, 0f, -1f);
		assertFalse(cull.mayShade(-30f, -5f, -40f, -20f, 5f, -30f), "left of the view, lit from above");
		assertTrue(cull.mayShade(-5f, -5f, -40f, 5f, 5f, -30f), "inside the view");
		// The light travels toward +X (toward the light is -X): the box's shadow sweeps into the view.
		cull.set(ORTHO, -1f, 0f, 0f, -1f);
		assertTrue(cull.mayShade(-30f, -5f, -40f, -20f, 5f, -30f), "left of the view, lit from the left");
		// The light travels toward -X: away from the view.
		cull.set(ORTHO, 1f, 0f, 0f, -1f);
		assertFalse(cull.mayShade(-30f, -5f, -40f, -20f, 5f, -30f), "left of the view, lit from the right");
	}

	@Test
	void aBoxBehindAPerspectiveCameraIsCulledUnlessTheLightCastsItForward() {
		CasterCull cull = new CasterCull();
		cull.set(PERSPECTIVE, 0f, 1f, 0f, -1f);
		assertFalse(cull.mayShade(-8f, -8f, 32f, 8f, 8f, 48f), "behind the camera, lit from above");
		assertTrue(cull.mayShade(-8f, -8f, -48f, 8f, 8f, -32f), "in front of the camera");
		// A low light behind the camera (toward the light is +Z): it travels toward -Z, into the view.
		cull.set(PERSPECTIVE, 0f, 0.2f, 0.98f, -1f);
		assertTrue(cull.mayShade(-8f, -8f, 32f, 8f, 8f, 48f), "behind the camera, lit from behind");
	}

	@Test
	void aBoxBesideTheViewIsCulledWhenItsShadowPassesUnderTheViewAndKeptWhenItReachesIt() {
		CasterCull cull = new CasterCull();
		// A nearly overhead light that travels slightly toward +X, into the view's left plane (x >= -10): that plane alone
		// culls nothing, so the left of the view is bounded by the plane through the view's bottom-left edge along the light.
		cull.set(ORTHO, -0.1f, 1f, 0f, -1f);
		// Its shadow moves 0.1 block toward +X per block it falls: it reaches x = -10 only 100 blocks lower, under the view.
		assertFalse(cull.mayShade(-30f, -5f, -40f, -20f, 5f, -30f), "left of the view at its height");
		// A tall box just left of the view: falling 100 to 120 blocks takes its shadow to x -1..1 inside the view's height.
		assertTrue(cull.mayShade(-12f, 100f, -40f, -11f, 110f, -30f), "a tall box whose shadow falls into the view");
	}

	@Test
	void aPerspectiveViewSweptAlongATiltedLightBoundsBothSides() {
		CasterCull cull = new CasterCull();
		// The light travels down and slightly toward +X: it enters the left plane (x >= z) and the top plane.
		cull.set(PERSPECTIVE, -0.1f, 1f, 0f, -1f);
		assertFalse(cull.mayShade(-60f, -2f, -20f, -50f, 2f, -10f), "left of the view at eye level, its shadow far below");
		assertTrue(cull.mayShade(-22f, 0f, -21f, -21f, 120f, -20f), "a pillar left of the view whose shadow falls into it");
		assertFalse(cull.mayShade(50f, -2f, -20f, 60f, 2f, -10f), "right of the view: the light travels away from it");
	}

	@Test
	void reversedKeepsTheCubeOfVoxelDistanceAroundTheCamera() {
		CasterCull cull = new CasterCull();
		cull.set(ORTHO, 0f, 1f, 0f, 32f);
		assertTrue(cull.mayShade(-30f, -5f, -40f, -20f, 5f, -30f), "within 32 blocks on every axis");
		cull.set(ORTHO, 0f, 1f, 0f, 16f);
		assertFalse(cull.mayShade(-30f, -5f, -40f, -20f, 5f, -30f), "beyond 16 blocks on X");
	}

	/**
	 * The cull's promise for the bench's cameras (70 degrees, 16:9; the open scene looks south 12 degrees down, the dense one
	 * north 30 degrees down) and any light, Unbound's at the bench's time among them: a box around any point whose shadow
	 * reaches a point of the view is kept. Each case takes a random point p of the view (any pixel, 0.5 to 300 blocks away)
	 * and a random distance t toward the light, and asks for the unit box around p - tL.
	 */
	@Test
	void everyBoxWhoseShadowReachesTheViewIsKept() {
		Random random = new Random(574);
		float tanV = (float) Math.tan(Math.toRadians(35.0));
		float tanH = tanV * 16f / 9f;
		// Toward the sun at time 5000 with sunPathRotation -40 (the game's celestial easing; ShadowCamera's path plane).
		Vector3f unbound = new Vector3f(0.1824f, 0.7532f, 0.6322f).normalize();
		float[][] cameras = {{0f, 12f}, {180f, 30f}, {90f, -20f}, {270f, 60f}};
		CasterCull cull = new CasterCull();
		for (float[] camera : cameras) {
			double yaw = Math.toRadians(camera[0]);
			double pitch = Math.toRadians(camera[1]);
			Vector3f forward = new Vector3f((float) (-Math.sin(yaw) * Math.cos(pitch)), (float) -Math.sin(pitch),
				(float) (Math.cos(yaw) * Math.cos(pitch)));
			Vector3f right = new Vector3f(forward).cross(0f, 1f, 0f).normalize();
			Vector3f up = new Vector3f(right).cross(forward).normalize();
			Matrix4f clip = new Matrix4f().setPerspective((float) Math.toRadians(70.0), 16f / 9f, 0.05f, 1024f)
				.lookAlong(forward, up);
			for (int light = 0; light < 64; light++) {
				Vector3f toward = light == 0 ? new Vector3f(unbound)
					: new Vector3f((float) random.nextGaussian(), (float) random.nextGaussian(), (float) random.nextGaussian())
						.normalize();
				assertTrue(cull.set(clip, toward.x, toward.y, toward.z, -1f));
				for (int i = 0; i < 400; i++) {
					float d = 0.5f + random.nextFloat() * 299.5f;
					float x = random.nextFloat() * 2f - 1f;
					float y = random.nextFloat() * 2f - 1f;
					Vector3f p = new Vector3f(forward).add(new Vector3f(right).mul(x * tanH)).add(new Vector3f(up).mul(y * tanV))
						.mul(d);
					float t = random.nextFloat() * 400f;
					Vector3f c = new Vector3f(toward).mul(t).add(p);
					assertTrue(cull.mayShade(c.x - 0.5f, c.y - 0.5f, c.z - 0.5f, c.x + 0.5f, c.y + 0.5f, c.z + 0.5f),
						() -> "camera yaw " + camera[0] + " pitch " + camera[1] + ", toward the light " + toward + ": the box at "
							+ c + " shades " + p + " in the view, and was culled");
				}
			}
		}
	}
}
