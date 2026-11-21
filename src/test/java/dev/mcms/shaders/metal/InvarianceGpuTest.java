// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.metal;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * W1-C: the check behind the engine's preserveInvariance decision (mcshaders.m's comment block). Two separately compiled
 * libraries share the exact position expression (mvp * corner) but reach its operand through different code, the way two
 * pack programs (a shadow pass and the lit pass) share one GLSL invariant gl_Position. The vertex outputs carry
 * [[position, invariant]]: Metal's option covers only positions marked invariant, so the emitter must write that attribute
 * on every translated vertex output. The matrix holds inexact decimal factors, so a reassociation could change the depth
 * bits. With invariance preserved, Metal guarantees bit-identical Depth32Float values across the two libraries.
 */
@Tag("gpu")
final class InvarianceGpuTest {
	private static final int W = 8, H = 8;

	/** Inexact factors; depth = Z0 * x + Z1 * y + Z2 * 0.5 + Z3 stays inside (0, 1) for x, y in {-1, 3}. */
	private static final float Z0 = 0.0296f, Z1 = 0.0183f, Z2 = 0.2147f, Z3 = 0.5171f;

	private static final String DIRECT = """
		#include <metal_stdlib>
		using namespace metal;
		struct InvParams { float4x4 mvp; float pad; };
		struct VOut { float4 pos [[position, invariant]]; };   // the option covers only positions marked invariant
		vertex VOut inv_vs(uint vid [[vertex_id]], constant InvParams &pr [[buffer(0)]]) {
			float2 p = float2((vid << 1) & 2, vid & 2);
			float4 corner = float4(p * 2.0f - 1.0f, 0.5f, 1.0f);
			VOut o;
			o.pos = pr.mvp * corner;
			return o;
		}
		""";

	private static final String DETOURED = """
		#include <metal_stdlib>
		using namespace metal;
		struct InvParams { float4x4 mvp; float pad; };
		struct VOut { float4 pos [[position, invariant]]; };
		vertex VOut inv_vs(uint vid [[vertex_id]], constant InvParams &pr [[buffer(0)]]) {
			float2 p = float2((vid << 1) & 2, vid & 2) + pr.pad;   // pad = 0: same value, different code
			float2 xy = p * 2.0f - (1.0f + pr.pad);
			float4 corner = float4(xy, 0.5f + pr.pad, 1.0f);
			VOut o;
			o.pos = pr.mvp * corner;
			return o;
		}
		""";

	@Test
	void twoSeparatelyCompiledProgramsAgreeOnEveryDepthBit() {
		byte[] params = params();
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			float[] a = gpu.renderDepthOnly(DIRECT, "inv_vs", null, W, H, params);
			float[] b = gpu.renderDepthOnly(DETOURED, "inv_vs", null, W, H, params);
			int distinct = 0;
			float seen = Float.NaN;
			for (int i = 0; i < a.length; i++) {
				assertEquals(Float.floatToIntBits(a[i]), Float.floatToIntBits(b[i]),
					"pixel " + i + ": depth bits differ between the two programs (" + a[i] + " vs " + b[i] + ")");
				if (a[i] != seen) {
					seen = a[i];
					distinct++;
				}
			}
			assertTrue(distinct >= 3, "the triangle must produce several distinct depths, or the check is vacuous");
		}

		// Control, no assertion on the outcome: the same sources compiled WITHOUT the option, to measure what this GPU's
		// compiler does without the guarantee. Measured on this GPU (Apple silicon, 2026-11-13): the two programs still
		// agree bit-for-bit, so the compiler happens to schedule the shared expression identically and this test does NOT
		// prove the option is load-bearing here — it proves the option works. The engine keeps the option on because only
		// that carries Metal's cross-library guarantee (mcshaders.m's comment block).
		try (HeadlessGpu off = new HeadlessGpu().with(ShaderNative.MSL_3_1, ShaderNative.MATH_PACKS, false)) {
			float[] c = off.renderDepthOnly(DIRECT, "inv_vs", null, W, H, params);
			float[] d = off.renderDepthOnly(DETOURED, "inv_vs", null, W, H, params);
			for (int i = 0; i < c.length; i++) {
				assertEquals(Float.floatToIntBits(c[i]), Float.floatToIntBits(d[i]), "control pixel " + i
					+ ": without the option the compiler now differs (the option has become load-bearing — update this test)");
			}
		}
	}

	private static byte[] params() {
		byte[] params = new byte[80];
		var buffer = ByteBuffer.wrap(params).order(ByteOrder.LITTLE_ENDIAN);
		float[][] columns = {  // column-major float4x4; row 2 picks the depth factors
			{1.0f, 0.0f, Z0, 0.0f},
			{0.0f, 1.0f, Z1, 0.0f},
			{0.0f, 0.0f, Z2, 0.0f},
			{0.0f, 0.0f, Z3, 1.0f},
		};
		for (float[] column : columns) for (float v : column) buffer.putFloat(v);
		buffer.putFloat(0.0f);  // pad
		return params;
	}
}
