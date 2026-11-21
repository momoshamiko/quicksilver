// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.metal;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcms.glsl.msl.builtins.Prelude;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Bliss 2.1.2's sky table averages nine lookups whose directions come from normalize() of a constant array, one entry the
 * zero vector. OpenGL on this device gives NaN there and the lookup reads 0 (Iris 1.11.7, measured with an instrumented
 * Bliss, E7); the engine must keep that NaN through the constant loop. Fast math folded it to the plain zero-vector result
 * even through the prelude's IEEE divide, which gave Bliss a brighter, bluer sky; pack programs compile with
 * {@link ShaderNative#MATH_PACKS}.
 */
@Tag("gpu")
final class NormalizeZeroGpuTest {

	private static final String KERNEL = """
		#include <metal_stdlib>
		using namespace metal;
		%s
		kernel void k(constant float4 &unused [[buffer(0)]], device float4 *out [[buffer(1)]],
				uint gid [[thread_position_in_grid]]) {
			array<float2, 3> a = array<float2, 3>{float2(1.0f, -0.3f), float2(0.0f, 0.0f), float2(1.0f, -1.0f)};
			float4 r = float4(0.0f);
			for (int i = 0; i < 3; i++) {
				float3 pos = float3(0.0f, 1.0f, 0.0f);
				pos.xy = pos.xy + (qs::normalize(a[i]) * float2(0.3183f, 0.9f));
				if (i == 1) r = float4(pos, 0.5f * pos.y + 0.5f);
			}
			out[gid] = r;
		}
		""";

	private static float[] run(String msl, int mathMode) {
		float[] v = new float[4];
		try (HeadlessGpu gpu = new HeadlessGpu().with(ShaderNative.MSL_3_1, mathMode, true)) {
			byte[][] out = gpu.runKernel(msl, "k", null, new byte[][] {new byte[16]}, new int[] {16}, 1, 1, 1, 1, 1, 1);
			ByteBuffer.wrap(out[0]).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(v);
		}
		return v;
	}

	@Test
	void aConstantZeroVectorStaysNaNThroughTheLoopInThePackMathMode() {
		String msl = KERNEL.formatted(Prelude.render(Set.of("normalize")));
		float[] v = run(msl, ShaderNative.MATH_PACKS);
		assertTrue(Float.isNaN(v[0]) && Float.isNaN(v[1]), "normalize(vec2(0)) keeps OpenGL's NaN: pos " + v[0] + ", " + v[1]);
		assertTrue(Float.isNaN(v[3]), "and the lookup coordinate built from it is NaN too: " + v[3]);
		// Recorded, not asserted: whether fast math still folds the NaN away (measured 2026-11-16: pos.y 1.0). If this ever
		// prints NaN, the compiler stopped folding and the test no longer tells the two modes apart.
		float[] fast = run(msl, ShaderNative.MATH_FAST);
		System.out.println("NormalizeZeroGpuTest: under MATH_FAST the folded pos.y is " + fast[1]);
	}
}
