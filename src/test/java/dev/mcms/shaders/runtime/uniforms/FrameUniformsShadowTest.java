// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.uniforms;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import dev.mcms.shaders.runtime.shadow.ShadowCamera;

/**
 * The shadow uniforms of the frame block (E5): {@code shadowModelView}, {@code shadowProjection} and their inverses come
 * from the frame's camera and light; {@code shadowLightPosition} is the sun by day and the moon by night, and the shadow
 * camera looks along that same light. Read back through the std140 offsets, as the other frame tests do.
 */
class FrameUniformsShadowTest {
	private static final float TOL = 1e-4f;

	private static final Map<String, Integer> OFFSETS = offsets();

	private static Map<String, Integer> offsets() {
		Map<String, Integer> m = new HashMap<>();
		for (var f : FrameUniforms.schema(0).fields()) {
			m.put(f.name(), f.offset());
		}
		return m;
	}

	private static float floatAt(ByteBuffer b, String field) {
		return b.getFloat(OFFSETS.get(field));
	}

	private static Matrix4f matAt(ByteBuffer b, String field) {
		int o = OFFSETS.get(field);
		float[] m = new float[16];
		for (int i = 0; i < 16; i++) {
			m[i] = b.getFloat(o + 4 * i);
		}
		return new Matrix4f(m);
	}

	private static float[] vecAt(ByteBuffer b, String field) {
		int o = OFFSETS.get(field);
		return new float[] {b.getFloat(o), b.getFloat(o + 4), b.getFloat(o + 8)};
	}

	private static ByteBuffer block(FrameUniforms u) {
		ByteBuffer b = ByteBuffer.allocate(FrameUniforms.size()).order(ByteOrder.LITTLE_ENDIAN);
		u.write(b);
		b.flip();
		return b;
	}

	private static void assertVec(float ex, float ey, float ez, Vector3f actual) {
		assertEquals(ex, actual.x, TOL, "x");
		assertEquals(ey, actual.y, TOL, "y");
		assertEquals(ez, actual.z, TOL, "z");
	}

	/** Noon (worldTime 6000): the sun straight overhead, sunPathRotation 0, the shadow interval 2 (the default). */
	@Test
	void shadowModelViewCentresOnTheCameraSnappedToTheIntervalAtNoon() {
		FrameUniforms u = new FrameUniforms();
		FrameUniformsTest.TestInputs in = new FrameUniformsTest.TestInputs();
		in.dayTime = 6000;
		u.advance(in.build(), 0.016f, -1f);
		ByteBuffer b = block(u);
		// The camera at (100.5, 64.25, 200.75). Its offset from its grid cell's centre (2 blocks) is (-0.5, -0.75, -0.25).
		// At noon the view axes are X = (1,0,0), Y = (0,0,-1), Z = (0,1,0), so the translation is (-0.5, 0.25, -0.75).
		assertVec(-0.5f, 0.25f, -0.75f, matAt(b, "shadowModelView").transformPosition(new Vector3f(0f, 0f, 0f)));
	}

	@Test
	void shadowProjectionIsTheDefaultOrthographicBoxOfShadowDistance() {
		FrameUniforms u = new FrameUniforms();
		u.setShadowSettings(new ShadowCamera.Settings(1024, 0f, 128f, 2f, -1f, 0f));
		FrameUniformsTest.TestInputs in = new FrameUniformsTest.TestInputs();
		in.dayTime = 6000;
		u.advance(in.build(), 0.016f, -1f);
		Matrix4f projection = matAt(block(u), "shadowProjection");
		assertEquals(1f, projection.transformProject(new Vector3f(128f, 0f, 0f)).x, TOL);
	}

	@Test
	void shadowInversesUndoTheShadowMatrices() {
		FrameUniforms u = new FrameUniforms();
		FrameUniformsTest.TestInputs in = new FrameUniformsTest.TestInputs();
		in.dayTime = 6000;
		u.advance(in.build(), 0.016f, -1f);
		ByteBuffer b = block(u);
		Matrix4f mv = matAt(b, "shadowModelView");
		Matrix4f mvInv = matAt(b, "shadowModelViewInverse");
		Matrix4f p = matAt(b, "shadowProjection");
		Matrix4f pInv = matAt(b, "shadowProjectionInverse");
		Matrix4f id = new Matrix4f(mv).mul(mvInv);
		Matrix4f pid = new Matrix4f(p).mul(pInv);
		assertEquals(1f, id.m00(), TOL);
		assertEquals(1f, id.m33(), TOL);
		assertEquals(0f, id.m30(), TOL);
		assertEquals(1f, pid.m00(), TOL);
		assertEquals(1f, pid.m22(), TOL);
	}

	@Test
	void shadowLightIsTheSunByDayAndTheMoonByNight() {
		FrameUniforms day = new FrameUniforms();
		FrameUniformsTest.TestInputs dayIn = new FrameUniformsTest.TestInputs();
		dayIn.dayTime = 6000;
		day.advance(dayIn.build(), 0.016f, -1f);
		ByteBuffer dayBlock = block(day);
		assertVec(vecAt(dayBlock, "sunPosition")[0], vecAt(dayBlock, "sunPosition")[1], vecAt(dayBlock, "sunPosition")[2],
			new Vector3f(vecAt(dayBlock, "shadowLightPosition")[0], vecAt(dayBlock, "shadowLightPosition")[1],
				vecAt(dayBlock, "shadowLightPosition")[2]));

		FrameUniforms night = new FrameUniforms();
		FrameUniformsTest.TestInputs nightIn = new FrameUniformsTest.TestInputs();
		nightIn.dayTime = 18000;
		nightIn.sunAngleDegrees = 180f;
		night.advance(nightIn.build(), 0.016f, -1f);
		ByteBuffer nightBlock = block(night);
		float[] moon = vecAt(nightBlock, "moonPosition");
		float[] light = vecAt(nightBlock, "shadowLightPosition");
		assertVec(moon[0], moon[1], moon[2], new Vector3f(light[0], light[1], light[2]));
	}

	@Test
	void shadowCameraLooksAlongTheShadowLight() {
		// shadowLightPosition is the light in view space; the shadow view maps the light's world direction onto +Z.
		FrameUniforms u = new FrameUniforms();
		FrameUniformsTest.TestInputs in = new FrameUniformsTest.TestInputs();
		in.dayTime = 4000;
		in.sunAngleDegrees = 337.94f;
		u.advance(in.build(), 0.016f, -1f);
		ByteBuffer b = block(u);
		float[] view = vecAt(b, "shadowLightPosition");
		Matrix4f viewInv = matAt(b, "gbufferModelViewInverse");
		Vector3f world = viewInv.transformDirection(new Vector3f(view[0], view[1], view[2])).normalize();
		assertVec(0f, 0f, 1f, matAt(b, "shadowModelView").transformDirection(new Vector3f(world)));
	}

	@Test
	void shadowAngleFoldsTheSunAngleIntoTheHigherBody() {
		FrameUniforms u = new FrameUniforms();
		FrameUniformsTest.TestInputs in = new FrameUniformsTest.TestInputs();
		in.dayTime = 4000;
		in.sunAngleDegrees = 337.94f;
		u.advance(in.build(), 0.016f, -1f);
		ByteBuffer b = block(u);
		float sunAngle = floatAt(b, "sunAngle");
		assertEquals(sunAngle < 0.5f ? sunAngle : sunAngle - 0.5f, floatAt(b, "shadowAngle"), TOL);

		FrameUniforms night = new FrameUniforms();
		FrameUniformsTest.TestInputs nightIn = new FrameUniformsTest.TestInputs();
		nightIn.dayTime = 18000;
		nightIn.sunAngleDegrees = 180f;
		night.advance(nightIn.build(), 0.016f, -1f);
		ByteBuffer nightBlock = block(night);
		float nightSun = floatAt(nightBlock, "sunAngle");
		assertEquals(nightSun - 0.5f, floatAt(nightBlock, "shadowAngle"), TOL);
	}
}
