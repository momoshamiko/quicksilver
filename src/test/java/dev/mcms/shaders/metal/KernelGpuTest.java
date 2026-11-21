// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.metal;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * W1-C: a hand-written kernel does float and uint math and crosses one threadgroup barrier; the readback must match values
 * computed in Java. Integer sums and single float multiplies stay correctly rounded under any math mode, so the check is
 * bit-exact (the barrier's sum runs over exact small integers, which no reassociation can change).
 */
@Tag("gpu")
final class KernelGpuTest {
	private static final int THREADS = 128, GROUP = 64, GROUPS = THREADS / GROUP;
	private static final float SCALE = 0.1f;
	private static final int BASE = 1000;

	private static final String KERNEL = """
		#include <metal_stdlib>
		using namespace metal;

		struct Params { float scale; uint base; };

		kernel void math_kernel(constant Params &p [[buffer(0)]],
				device uint *ints [[buffer(1)]], device float *floats [[buffer(2)]],
				uint gid [[thread_position_in_grid]], uint lid [[thread_position_in_threadgroup]],
				uint lsize [[threads_per_threadgroup]]) {
			threadgroup uint sums[64];
			ints[gid] = gid * 7u + p.base;          // uint math
			sums[lid] = gid;
			threadgroup_barrier(mem_flags::mem_threadgroup);
			uint total = 0;
			for (uint i = 0; i < lsize; i++) total += sums[i];   // every lane reads the whole group's writes
			ints[THREADS + gid] = total;
			floats[gid] = (float) gid * p.scale;    // one float multiply
		}
		""".replace("THREADS", String.valueOf(THREADS));

	@Test
	void kernelMathAndBarrierMatchJava() {
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			byte[] params = new byte[16];
			ByteBuffer.wrap(params).order(ByteOrder.LITTLE_ENDIAN).putFloat(SCALE).putInt(4, BASE);
			byte[][] out = gpu.runKernel(KERNEL, "math_kernel", null, new byte[][]{params},
				new int[]{THREADS * 2 * 4, THREADS * 4}, GROUPS, 1, 1, GROUP, 1, 1);
			int[] ints = new int[THREADS * 2];
			ByteBuffer.wrap(out[0]).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(ints);
			float[] floats = new float[THREADS];
			ByteBuffer.wrap(out[1]).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(floats);

			int[] expectedInts = new int[THREADS * 2];
			float[] expectedFloats = new float[THREADS];
			for (int gid = 0; gid < THREADS; gid++) {
				expectedInts[gid] = gid * 7 + BASE;
				expectedFloats[gid] = gid * SCALE;  // one IEEE multiply, exact in fast math too
			}
			for (int g = 0; g < GROUPS; g++) {
				int total = 0;
				for (int i = 0; i < GROUP; i++) total += g * GROUP + i;   // integers: any summation order is exact
				for (int i = 0; i < GROUP; i++) expectedInts[THREADS + g * GROUP + i] = total;
			}
			assertArrayEquals(expectedInts, ints, "uint math and the barrier sum");
			assertArrayEquals(expectedFloats, floats, "float multiplies");
		}
	}
}
