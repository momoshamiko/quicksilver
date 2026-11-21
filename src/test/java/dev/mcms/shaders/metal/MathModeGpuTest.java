// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.metal;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The evidence for the engine's math modes (mcshaders.m's comment block): W1-C chose MTLMathModeFast, E7 moved pack render
 * programs to MTLMathModeRelaxed ({@link ShaderNative#MATH_PACKS}). The same kernel runs compiled with MATH_SAFE,
 * MATH_RELAXED and MATH_FAST.
 *
 * <p>Identical in all modes (measured on this GPU, Apple silicon, MSL 3.1): a single division returns Java's
 * correctly-rounded result bit-for-bit, subtraction is exact, float comparisons do not change, and isnan()/isinf() still
 * classify plain run-time values (a NaN read from a buffer, sqrt(-1), 1/0) — 4 of the 8 corpus packs call isnan/isinf,
 * so this matters.
 *
 * <p>What fast math changes (measured): self-compares fold — x != x is false even for a run-time NaN and x == x is true —
 * x/x is refined to 1.0 even at x = 0, inf - inf gives 0.0, and normalize() of the zero vector is not NaN. Relaxed math
 * (measured 2026-11-16) keeps the NaNs and infinities (0 / 0 and inf - inf are NaN, normalize(0) is NaN, as OpenGL gives
 * them on this device) but still folds self-compares, so the emitter must keep mapping GLSL's x != x to isnan(x).
 */
@Tag("gpu")
final class MathModeGpuTest {
	private static final float A = 8.375876f, B = 2.4680901f;
	private static final int NAN_BITS = 0x7fc00000;

	private static final String KERNEL = """
		#include <metal_stdlib>
		using namespace metal;

		struct MM { float2 ab; float nan; float zero; float one; float neg; };

		kernel void mm_kernel(constant MM &v [[buffer(0)]], device uint4 *ops [[buffer(1)]], device uint4 *flags [[buffer(2)]],
				device float4 *vals [[buffer(3)]], uint gid [[thread_position_in_grid]]) {
			float a = v.ab.x, b = v.ab.y, nan = v.nan, zero = v.zero, one = v.one, neg = v.neg;
			float inf = one / zero;
			ops[gid] = uint4(as_type<uint>(a / b), as_type<uint>(b - a), a < b ? 1u : 0u, a == b ? 1u : 0u);
			flags[gid] = uint4(
				isnan(nan) ? 1u : 0u,                             // classification works under fast math
				isnan(sqrt(neg)) ? 1u : 0u,
				isinf(inf) ? 1u : 0u,
				isnan(normalize(float3(0.0f)).x) ? 1u : 0u);
			vals[gid] = float4(
				nan != nan ? 1.0f : 0.0f,   // fast: folds to false; safe: IEEE true
				nan == nan ? 1.0f : 0.0f,   // fast: folds to true;  safe: IEEE false
				zero / zero,                // fast: refined to 1.0; safe: NaN
				inf - inf);                 // fast: 0.0;             safe: NaN
		}
		""";

	private record Result(int[] ops, int[] flags, float[] vals) {
	}

	@Test
	void eachModeKeepsClassificationAndDivisionAndTheModesDifferInNaNsAndSelfCompares() {
		Result safe = run(ShaderNative.MATH_SAFE);
		Result relaxed = run(ShaderNative.MATH_RELAXED);
		Result fast = run(ShaderNative.MATH_FAST);
		int javaDiv = Float.floatToIntBits(A / B), javaSub = Float.floatToIntBits(B - A);

		// Identical in every mode: division, subtraction, comparisons.
		assertEquals(javaDiv, safe.ops[0], "safe-mode division must be Java's correctly-rounded result");
		assertEquals(javaSub, safe.ops[1], "subtraction is exact in every mode");
		for (Result other : new Result[] {relaxed, fast}) {
			assertEquals(safe.ops[0], other.ops[0], "division does not change with the math mode on this GPU");
			assertEquals(safe.ops[1], other.ops[1]);
			assertEquals(safe.ops[2], other.ops[2], "a < b must not depend on the math mode");
			assertEquals(safe.ops[3], other.ops[3], "a == b must not depend on the math mode");
		}
		assertEquals(safe.ops[2], A < B ? 1 : 0);
		assertEquals(safe.ops[3], A == B ? 1 : 0);

		// isnan/isinf classify plain run-time values in every mode (packs call these).
		for (int i = 0; i < 3; i++) {
			assertEquals(1, safe.flags[i], "isnan/isinf flag " + i + " under MATH_SAFE");
			assertEquals(1, relaxed.flags[i], "isnan/isinf flag " + i + " under MATH_RELAXED (the pack programs' mode)");
			assertEquals(1, fast.flags[i], "isnan/isinf flag " + i + " under MATH_FAST");
		}
		assertEquals(1, safe.flags[3], "isnan(normalize(0)) under MATH_SAFE");
		assertEquals(1, relaxed.flags[3], "normalize(0) is NaN under MATH_RELAXED, as OpenGL gives it");
		assertEquals(0, fast.flags[3], "normalize(0) is not NaN under MATH_FAST");

		// MATH_SAFE: IEEE everywhere. Self-compares of a NaN follow the standard.
		assertEquals(1.0f, safe.vals[0], "NaN != NaN is true under IEEE");
		assertEquals(0.0f, safe.vals[1], "NaN == NaN is false under IEEE");
		assertTrue(Float.isNaN(safe.vals[2]), "0 / 0 is NaN under IEEE");
		assertTrue(Float.isNaN(safe.vals[3]), "inf - inf is NaN under IEEE");

		// MATH_RELAXED (the pack programs' mode): NaNs and infinities kept, self-compares still fold. The emitter maps GLSL
		// x != x to isnan(x) (and friends); it cannot rely on NaN self-inequality in this mode either.
		assertEquals(0.0f, relaxed.vals[0], "x != x still folds to false under relaxed math");
		assertEquals(1.0f, relaxed.vals[1], "x == x still folds to true under relaxed math");
		assertTrue(Float.isNaN(relaxed.vals[2]), "0 / 0 stays NaN under relaxed math");
		assertTrue(Float.isNaN(relaxed.vals[3]), "inf - inf stays NaN under relaxed math");

		// MATH_FAST (the engine's own pipelines): self-compares fold, x/x is 1.0 at 0, inf - inf is 0.
		assertEquals(0.0f, fast.vals[0], "x != x folds to false under fast math");
		assertEquals(1.0f, fast.vals[1], "x == x folds to true under fast math");
		assertEquals(1.0f, fast.vals[2], "x / x is refined to 1.0 under fast math, even at x = 0");
		assertEquals(0.0f, fast.vals[3], "inf - inf is 0.0 under fast math");
	}

	private static Result run(int mathMode) {
		byte[] input = new byte[32];
		ByteBuffer.wrap(input).order(ByteOrder.LITTLE_ENDIAN)
			.putFloat(A).putFloat(B).putFloat(Float.intBitsToFloat(NAN_BITS)).putFloat(0.0f).putFloat(1.0f).putFloat(-1.0f);
		try (HeadlessGpu gpu = new HeadlessGpu().with(ShaderNative.MSL_3_1, mathMode, true)) {
			byte[][] out = gpu.runKernel(KERNEL, "mm_kernel", null, new byte[][]{input},
				new int[]{16, 16, 16}, 1, 1, 1, 1, 1, 1);
			int[] ops = new int[4], flags = new int[4];
			float[] vals = new float[4];
			ByteBuffer.wrap(out[0]).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(ops);
			ByteBuffer.wrap(out[1]).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(flags);
			ByteBuffer.wrap(out[2]).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(vals);
			return new Result(ops, flags, vals);
		}
	}
}
