// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.metal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcms.metal.MetalBridge;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Metal's buffer index range, measured on the device (E4 slot plan): buffer indices 0 to 30 bind, 31 and up are refused
 * by the compiler. Any engine block or vertex buffer at a higher index cannot draw.
 */
@Tag("gpu")
class BufferIndexRangeGpuTest {

	private static String shader(int slot) {
		return """
			#include <metal_stdlib>
			using namespace metal;
			struct Out { float4 p [[position]]; };
			vertex Out v(uint vid [[vertex_id]], constant float *b [[buffer(%d)]]) {
				Out o; o.p = float4(float(vid) * 0.5 - 1.0, b[0], 0.0, 1.0); return o;
			}
			fragment float4 f() { return float4(1.0); }
			""".formatted(slot);
	}

	@Test
	void indicesZeroToThirtyBindAndThirtyOneIsRefused() {
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			for (int slot : new int[] {0, 26, 29, 30}) {
				assertTrue(compilesAndBuilds(gpu, slot), "buffer(" + slot + ") must bind");
			}
			assertFalse(compilesAndBuilds(gpu, 31), "buffer(31) must be refused: Metal's range ends at 30");
		}
	}

	private static boolean compilesAndBuilds(HeadlessGpu gpu, int slot) {
		ShaderNative.Compiled lib = ShaderNative.libraryNew(gpu.context(), shader(slot), ShaderNative.MSL_3_1, ShaderNative.MATH_PACKS, true);
		if (!lib.ok()) {
			return false;
		}
		try {
			long pso = MetalBridge.pipelineNew(gpu.context(), lib.library(), "v", lib.library(), "f",
				new int[] {0, 0, 1, HeadlessGpu.RGBA8_UNORM, 15, 0, 0, 0, 0, 0, 0, 0, 0, 3});
			MetalBridge.release(pso);
			return true;
		} catch (IllegalStateException e) {
			return false;
		} finally {
			MetalBridge.release(lib.library());
		}
	}
}
