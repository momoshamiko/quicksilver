// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.uniforms;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import dev.mcms.shaders.pack.ConstValue;
import dev.mcms.shaders.runtime.shadow.ShadowCamera;

/**
 * The shadow matrices of the frame block reproduce the matrices Vitrail's shadow pass writes in the mcms-shadow test
 * pack's map view, for the same scene (results/2026-11-14-e5-preview1-map and preview3-map, the metal-vitrail-shadow-map
 * runs; 2026-11-14-e5-d192, the same runs with the pack's shadowDistance set to 192; the printed rows are six
 * decimals). The inputs are the scene's:
 * the held time 5000 (plan.properties: "time set 5000", the clock frozen), the camera's eye at feet + 1.62 (dense:
 * 736, 100, 16; open: -48, 110, -272), the mcms-shadow pack's shadowMapResolution 2048 and shadowDistance 128, and no
 * other shadow constant (so shadowIntervalSize is 2, sunPathRotation 0, the map orthographic).
 *
 * <p>The sun angle is the game's timeline value at time 5000.05 (349.4873 degrees; the partial tick 0.05 of the
 * frame). With that value the matrices reproduce Vitrail's light. [fit: Vitrail's frame had partial about 0.05; see the
 * light's note in ShadowCamera]
 */
class ShadowVitrailMatchTest {

	/** Vitrail's shadowModelView (rows), the same in both scenes. */
	private static final float[][] VITRAIL_MODEL_VIEW = {
		{0.983215f, -0.182454f, 0f, -1.096336f},
		{0f, 0f, -1f, 1f},
		{0.182454f, 0.983215f, 0f, 0.427142f},
		{0f, 0f, 0f, 1f},
	};

	/** Vitrail's shadowProjection (rows), the same in both scenes. */
	private static final float[][] VITRAIL_PROJECTION = {
		{0.007813f, 0f, 0f, 0f},
		{0f, 0.007813f, 0f, 0f},
		{0f, 0f, -0.007811f, -0.218512f},
		{0f, 0f, 0f, 1f},
	};

	/** Vitrail's shadowProjection (rows) with shadowDistance 192: only the half-width changes, not the depth terms. */
	private static final float[][] VITRAIL_PROJECTION_192 = {
		{0.005208f, 0f, 0f, 0f},
		{0f, 0.005208f, 0f, 0f},
		{0f, 0f, -0.007811f, -0.218512f},
		{0f, 0f, 0f, 1f},
	};

	/** Six printed decimals: each entry is within half a unit of the last digit, plus float error. */
	private static final float TOL = 1.5e-6f;

	private static ShadowCamera.Settings sceneSettings(double distance) {
		return ShadowCamera.Settings.of(Map.of(
			"shadowMapResolution", new ConstValue("int", List.of(2048.0)),
			"shadowDistance", new ConstValue("float", List.of(distance))), 0f);
	}

	private static final Map<String, Integer> OFFSETS = offsets();

	private static Map<String, Integer> offsets() {
		Map<String, Integer> m = new HashMap<>();
		for (var f : FrameUniforms.schema(0).fields()) {
			m.put(f.name(), f.offset());
		}
		return m;
	}

	private static ByteBuffer block(FrameUniforms u) {
		ByteBuffer b = ByteBuffer.allocate(FrameUniforms.size()).order(ByteOrder.LITTLE_ENDIAN);
		u.write(b);
		b.flip();
		return b;
	}

	/** The matrix stored at a field, as JOML reads it (column-major). */
	private static Matrix4f matAt(ByteBuffer b, String field) {
		int o = OFFSETS.get(field);
		float[] m = new float[16];
		for (int i = 0; i < 16; i++) {
			m[i] = b.getFloat(o + 4 * i);
		}
		return new Matrix4f(m);
	}

	private static void assertRows(float[][] expected, Matrix4f actual, String name) {
		float[] column = new float[16];
		actual.get(column);
		for (int row = 0; row < 4; row++) {
			for (int col = 0; col < 4; col++) {
				assertEquals(expected[row][col], column[col * 4 + row], TOL,
					name + " row " + row + " column " + col);
			}
		}
	}

	private static void checkScene(double x, double eyeY, double z, double distance, float[][] projection) {
		FrameUniforms u = new FrameUniforms();
		u.setShadowSettings(sceneSettings(distance));
		FrameUniformsTest.TestInputs in = new FrameUniformsTest.TestInputs();
		in.cameraX = x;
		in.cameraY = (float) eyeY;  // Vitrail computes the eye in single precision: 101.62 is 101.6200027 in float32
		in.cameraZ = z;
		in.eyeX = x;
		in.eyeY = (float) eyeY;
		in.eyeZ = z;
		in.dayTime = 5000;
		in.sunAngleDegrees = 349.4873f;
		in.partialTick = 0.05f;
		u.advance(in.build(), 0.016f, -1f);
		ByteBuffer b = block(u);
		assertRows(VITRAIL_MODEL_VIEW, matAt(b, "shadowModelView"), "shadowModelView");
		assertRows(projection, matAt(b, "shadowProjection"), "shadowProjection");
	}

	@Test
	void theDenseSceneGivesVitrailsShadowMatrices() {
		checkScene(736.0, 100.0 + 1.62, 16.0, 128.0, VITRAIL_PROJECTION);
	}

	@Test
	void theOpenSceneGivesTheSameShadowMatricesAsVitrail() {
		checkScene(-48.0, 110.0 + 1.62, -272.0, 128.0, VITRAIL_PROJECTION);
	}

	@Test
	void aLongerShadowDistanceKeepsVitrailsDepthTerms() {
		checkScene(736.0, 100.0 + 1.62, 16.0, 192.0, VITRAIL_PROJECTION_192);
	}
}
