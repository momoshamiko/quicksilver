// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.shadow;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import dev.mcms.shaders.pack.ConstValue;
import dev.mcms.shaders.pack.ShaderProperties.ShadowCulling;

/**
 * The shadow camera against hand-computed values. Noon is the default path (sunPathRotation 0): the light straight
 * overhead, L = (0, 1, 0). The up axis is the sun path's normal, negated: (0, 0, -1), so the shadow view's axes are
 * X = (1, 0, 0), Y = (0, 0, -1), Z = L (the same basis Vitrail's observed matrices use; see ShadowVitrailMatchTest).
 * The centre is the cell centre of the world grid of shadowIntervalSize, per world axis; the translation is R times
 * the offset of the camera from that centre. The orthographic depth reach is {@code EYE_OFFSET + shadowNearPlane}
 * toward the light and {@code shadowFarPlane - EYE_OFFSET} away from it, whatever the shadowDistance (Vitrail's depth
 * terms at shadowDistance 128 and 192).
 */
class ShadowCameraTest {
	private static final float TOL = 1e-4f;
	private static final float NOON_X = 0f, NOON_Y = 1f, NOON_Z = 0f;

	/** How far the perspective shadow eye stands from the centre toward the light. */
	private static final float EYE = ShadowCamera.EYE_OFFSET;

	private static Vector3f at(Matrix4f m, float x, float y, float z) {
		return m.transformProject(new Vector3f(x, y, z));
	}

	private static void assertVec(float ex, float ey, float ez, Vector3f actual) {
		assertEquals(ex, actual.x, TOL, "x");
		assertEquals(ey, actual.y, TOL, "y");
		assertEquals(ez, actual.z, TOL, "z");
	}

	private static ShadowCamera.Settings orthoAt(float distance, float interval) {
		return new ShadowCamera.Settings(1024, 0f, distance, interval, -1f, 0f);
	}

	@Test
	void settingsReadTheDeclaredConstantsAndDefaultTheRest() {
		Map<String, ConstValue> declared = Map.of(
			"shadowMapResolution", new ConstValue("int", List.of(2048.0)),
			"shadowMapFov", new ConstValue("float", List.of(90.0)),
			"shadowDistance", new ConstValue("float", List.of(128.0)),
			"shadowIntervalSize", new ConstValue("float", List.of(0.0)),
			"shadowDistanceRenderMul", new ConstValue("float", List.of(1.0)),
			"shadowFarPlane", new ConstValue("float", List.of(512.0)),
			"voxelDistance", new ConstValue("float", List.of(64.0)));
		assertEquals(new ShadowCamera.Settings(2048, 90f, 128f, 0f, 1f, 15f, 0.05f, 512f, ShadowCulling.TRUE, 64f),
			ShadowCamera.Settings.of(declared, 15f));
		assertEquals(ShadowCamera.Settings.DEFAULTS, ShadowCamera.Settings.of(Map.of(), 0f));
		assertEquals(new ShadowCamera.Settings(1024, 0f, 160f, 2f, -1f, 0f, 0.05f, 256f, ShadowCulling.TRUE, 160f),
			ShadowCamera.Settings.DEFAULTS);
	}

	@Test
	void lightDirectionBecomesTheViewsForwardAxis() {
		// Any light direction: the shadow view looks along the light, so the light maps to +Z.
		ShadowCamera camera = new ShadowCamera();
		camera.configure(orthoAt(160f, 0f));
		float lx = 0.6f, ly = 0.8f, lz = 0f;
		camera.update(10.25, 64.0, -7.5, lx, ly, lz);
		assertVec(0f, 0f, 1f, camera.modelView().transformDirection(new Vector3f(lx, ly, lz)));
	}

	@Test
	void aTiltedPathKeepsTheViewUprightForEveryTilt() {
		// The light on a path tilted by p lies in the plane of east and (0, cos p, -sin p) (FrameUniforms). For every tilt
		// a pack offers (BSL: -85 to 85), the light maps to +Z, the path's negated normal stays the view's up axis (so the
		// map is never mirrored) and no matrix element is NaN, the tilts 45 and -45 included.
		for (float tilt : new float[] { -85f, -45f, -40f, -30f, 0f, 30f, 45f, 85f }) {
			ShadowCamera camera = new ShadowCamera();
			camera.configure(new ShadowCamera.Settings(1024, 0f, 160f, 0f, -1f, tilt));
			double p = Math.toRadians(tilt), a = Math.toRadians(70);
			float lx = (float) Math.cos(a), ly = (float) (Math.sin(a) * Math.cos(p)), lz = (float) (-Math.sin(a) * Math.sin(p));
			camera.update(0.0, 64.0, 0.0, lx, ly, lz);
			Matrix4f view = camera.modelView();
			for (int c = 0; c < 4; c++) {
				for (int r = 0; r < 4; r++) {
					assertEquals(false, Float.isNaN(view.get(c, r)), "tilt " + tilt + ": element " + c + "," + r);
				}
			}
			Vector3f forward = view.transformDirection(new Vector3f(lx, ly, lz));
			assertEquals(1f, forward.z, TOL, "tilt " + tilt + ": the light maps to +Z");
			Vector3f up = view.transformDirection(new Vector3f(0f, (float) -Math.sin(p), (float) -Math.cos(p)));
			assertEquals(1f, up.y, TOL, "tilt " + tilt + ": the path's negated normal is the view's up axis");
		}
	}

	@Test
	void noonOrthographicViewMatchesTheHandComputedAxesAndCentre() {
		// Camera (0,64,0), interval 2: the offset from the cell centre (1,65,1) is o = (-1,-1,-1). The translation is
		// R o = (X.o, Y.o, Z.o) = (-1, 1, -1). A player-space point (3,6,3) is R(3,6,3) + R o = (3,-3,6) + (-1,1,-1).
		ShadowCamera camera = new ShadowCamera();
		camera.configure(orthoAt(160f, 2f));
		camera.update(0.0, 64.0, 0.0, NOON_X, NOON_Y, NOON_Z);
		assertVec(2f, -2f, 5f, at(camera.modelView(), 3f, 6f, 3f));
	}

	@Test
	void theCentreIsTheCellCentreOfTheWorldGrid() {
		// Camera x 0.7 in the cell [0, 2): its centre is 1. The offset is -0.3 along X and the translation is (-0.3, 1, -1).
		ShadowCamera camera = new ShadowCamera();
		camera.configure(orthoAt(160f, 2f));
		camera.update(0.7, 64.0, 0.0, NOON_X, NOON_Y, NOON_Z);
		assertVec(-0.3f, 1f, -1f, at(camera.modelView(), 0f, 0f, 0f));
	}

	@Test
	void negativeCoordinatesSnapAsIrisDoes() {
		// Measured (mcms-uniforms, biomes set, 2026-11-16): at camera (-100.5, 202.12, -274.5) with the light along
		// (0.182454, 0.983214, 0), Iris 1.11.7's shadowModelView translation is (-1.31426, 1.5, -1.13891): the remainder takes
		// the coordinate's sign (-100.5 % 2 = -0.5), so x and z sit one interval from the positive-modulus cell.
		ShadowCamera camera = new ShadowCamera();
		camera.configure(orthoAt(160f, 2f));
		camera.update(-100.5, 202.12, -274.5, 0.182454f, 0.983214f, 0f);
		Matrix4f view = camera.modelView();
		assertEquals(-1.31426f, view.m30(), 1e-4f);
		assertEquals(1.5f, view.m31(), 1e-4f);
		assertEquals(-1.13891f, view.m32(), 1e-4f);
	}

	@Test
	void geometryDoesNotMoveWhileTheCameraStaysInItsSnapCell() {
		// Cameras (0.2,64.3,0.4) and (0.9,64.9,1.1) share the cell centre (1,65,1). A block at (3,70,3) lands on the
		// same shadow-view point for both: R(w - centre) = (2,-2,5). The matrices themselves differ.
		ShadowCamera camera = new ShadowCamera();
		camera.configure(orthoAt(160f, 2f));
		camera.update(0.2, 64.3, 0.4, NOON_X, NOON_Y, NOON_Z);
		Vector3f first = at(camera.modelView(), 3f - 0.2f, 70f - 64.3f, 3f - 0.4f);
		camera.update(0.9, 64.9, 1.1, NOON_X, NOON_Y, NOON_Z);
		Vector3f second = at(camera.modelView(), 3f - 0.9f, 70f - 64.9f, 3f - 1.1f);
		assertVec(2f, -2f, 5f, first);
		assertVec(2f, -2f, 5f, second);
	}

	@Test
	void intervalZeroDisablesTheSnapping() {
		ShadowCamera camera = new ShadowCamera();
		camera.configure(orthoAt(160f, 0f));
		camera.update(0.7, 64.0, 0.0, NOON_X, NOON_Y, NOON_Z);
		assertVec(0f, 0f, 0f, at(camera.modelView(), 0f, 0f, 0f));
	}

	@Test
	void orthographicHalfWidthIsTheShadowDistance() {
		// shadowDistance is the radius: a point at distance D to the side of the centre is at the edge of the view.
		ShadowCamera camera = new ShadowCamera();
		camera.configure(orthoAt(128f, 0f));
		camera.update(0.0, 64.0, 0.0, NOON_X, NOON_Y, NOON_Z);
		assertEquals(1f, at(camera.projection(), 128f, 0f, 0f).x, TOL);
		assertEquals(0.5f, at(camera.projection(), 64f, 0f, 0f).x, TOL);
	}

	@Test
	void orthographicDepthRangeIsTheSameAtEveryShadowDistance() {
		// The reach toward the light is 100 + 0.05 and away from it 256 - 100 = 156 at shadowDistance 128 and at 192
		// (Vitrail's terms at both: -2/(f - n) = -0.007811 and -(f + n)/(f - n) = -0.218512 with n = -100.05, f = 156).
		for (float distance : new float[] {128f, 192f}) {
			ShadowCamera camera = new ShadowCamera();
			camera.configure(orthoAt(distance, 0f));
			camera.update(0.0, 64.0, 0.0, NOON_X, NOON_Y, NOON_Z);
			assertEquals(-1f, at(camera.projection(), 0f, 0f, 100.05f).z, TOL, "near reach at " + distance);
			assertEquals(1f, at(camera.projection(), 0f, 0f, -156f).z, TOL, "far reach at " + distance);
			assertEquals(-0.218512f, at(camera.projection(), 0f, 0f, 0f).z, 1e-5f, "centre depth at " + distance);
			assertEquals(1f, at(camera.projection(), distance, 0f, 0f).x, TOL, "half-width at " + distance);
		}
	}

	@Test
	void aDeclaredFarPlaneMovesOnlyTheFarReach() {
		ShadowCamera camera = new ShadowCamera();
		camera.configure(new ShadowCamera.Settings(1024, 0f, 128f, 0f, -1f, 0f, 0.05f, 512f, ShadowCulling.TRUE, 160f));
		camera.update(0.0, 64.0, 0.0, NOON_X, NOON_Y, NOON_Z);
		assertEquals(-1f, at(camera.projection(), 0f, 0f, 100.05f).z, TOL);
		assertEquals(1f, at(camera.projection(), 0f, 0f, -412f).z, TOL);
	}

	@Test
	void perspectiveFovWidensTheViewAndPlacesTheCentreBehindTheEye() {
		// shadowMapFov 90: at depth d the view's half-width is d. The eye stands EYE blocks from the centre toward the
		// light, so a point beside the centre at depth EYE is at NDC x = 1, which an orthographic view would not give.
		ShadowCamera camera = new ShadowCamera();
		camera.configure(new ShadowCamera.Settings(1024, 90f, 128f, 0f, -1f, 0f));
		camera.update(0.0, 64.0, 0.0, NOON_X, NOON_Y, NOON_Z);
		Vector3f sideways = at(camera.projection(), EYE, 0f, -EYE);
		assertEquals(1f, sideways.x, TOL);
		assertEquals(-EYE, at(camera.modelView(), 0f, 0f, 0f).z, TOL, "the centre lies EYE in front of the eye");
	}

	@Test
	void perspectiveDiffersFromOrthographicAtTheSameDistance() {
		ShadowCamera ortho = new ShadowCamera();
		ortho.configure(orthoAt(128f, 0f));
		ortho.update(0.0, 64.0, 0.0, NOON_X, NOON_Y, NOON_Z);
		ShadowCamera perspective = new ShadowCamera();
		perspective.configure(new ShadowCamera.Settings(1024, 90f, 128f, 0f, -1f, 0f));
		perspective.update(0.0, 64.0, 0.0, NOON_X, NOON_Y, NOON_Z);
		Vector3f o = at(ortho.projection(), 50f, 0f, -EYE);
		Vector3f p = at(perspective.projection(), 50f, 0f, -EYE);
		assertEquals(50f / 128f, o.x, TOL);
		assertEquals(50f / EYE, p.x, TOL);
	}

	@Test
	void inversesUndoTheMatrices() {
		ShadowCamera camera = new ShadowCamera();
		camera.configure(new ShadowCamera.Settings(1024, 60f, 128f, 2f, -1f, 20f));
		camera.update(12.3, 70.1, -4.4, 0.6f, 0.8f, 0f);
		Matrix4f id = new Matrix4f(camera.modelView()).mul(camera.modelViewInverse());
		Matrix4f pid = new Matrix4f(camera.projection()).mul(camera.projectionInverse());
		assertEquals(1f, id.m00(), TOL);
		assertEquals(1f, id.m11(), TOL);
		assertEquals(1f, id.m22(), TOL);
		assertEquals(0f, id.m03(), TOL);
		assertEquals(1f, pid.m00(), TOL);
		assertEquals(1f, pid.m22(), TOL);
	}

	@Test
	void viewProjectionIsProjectionTimesModelView() {
		ShadowCamera camera = new ShadowCamera();
		camera.configure(orthoAt(96f, 1f));
		camera.update(3.0, 65.0, 9.0, NOON_X, NOON_Y, NOON_Z);
		Matrix4f product = new Matrix4f(camera.projection()).mul(camera.modelView());
		assertEquals(product.m00(), camera.viewProjection().m00(), TOL);
		assertEquals(product.m13(), camera.viewProjection().m13(), TOL);
		assertEquals(product.m32(), camera.viewProjection().m32(), TOL);
	}

	@Test
	void cullRadiusIsDistanceTimesTheRenderMultiplierOnlyWhenPositive() {
		ShadowCamera camera = new ShadowCamera();
		camera.configure(new ShadowCamera.Settings(1024, 0f, 128f, 2f, 1f, 0f));
		assertEquals(128f, camera.cullRadius(), TOL);
		camera.configure(new ShadowCamera.Settings(1024, 0f, 160f, 2f, 0.5f, 0f));
		assertEquals(80f, camera.cullRadius(), TOL);
		camera.configure(new ShadowCamera.Settings(1024, 0f, 160f, 2f, -1f, 0f));
		assertEquals(-1f, camera.cullRadius(), TOL);
	}
}
