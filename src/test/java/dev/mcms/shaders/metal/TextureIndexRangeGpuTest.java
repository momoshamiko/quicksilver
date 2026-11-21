// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.metal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcms.metal.MetalBridge;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Metal's texture index range, measured on the device: a texture bound at index 30 is what the shader reads, and index 31 is
 * not bound at all (the shader compiles, and reads zero from it). The corpus test SamplerSlotsCorpusGpuTest asserts every
 * program's texture indices stay in 0 to 30.
 */
@Tag("gpu")
class TextureIndexRangeGpuTest {

	private static final int RGBA8 = 70;

	private static String shader(int index) {
		return """
			#include <metal_stdlib>
			using namespace metal;
			struct Out { float4 p [[position]]; };
			vertex Out v(uint vid [[vertex_id]]) {
				Out o;
				o.p = float4(float(vid & 1) * 4.0 - 1.0, float(vid >> 1) * 4.0 - 1.0, 0.0, 1.0);
				return o;
			}
			fragment float4 f(texture2d<float> t [[texture(%d)]]) { return t.read(uint2(0, 0)); }
			""".formatted(index);
	}

	@Test
	void indexThirtyBindsAndThirtyOneIsNotBound() {
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			assertEquals(255, greenSeenThrough(gpu, 30), "texture 30 is bound: the shader reads its green 1.0");
			assertEquals(0, greenSeenThrough(gpu, 31), "texture 31 is not bound: the shader reads zero from it");
		}
	}

	/** The green channel the fragment reads through texture {@code index} of a 1x1 green texture, drawn into a 4x4 target. */
	private static int greenSeenThrough(HeadlessGpu gpu, int index) {
		long ctx = gpu.context();
		ShaderNative.Compiled lib = ShaderNative.libraryNew(ctx, shader(index), ShaderNative.MSL_3_1, ShaderNative.MATH_PACKS, true);
		assertTrue(lib.ok(), "the shader compiles with texture(" + index + "): " + lib.error());
		try {
			long pso = MetalBridge.pipelineNew(ctx, lib.library(), "v", lib.library(), "f",
				new int[] {0, 0, 1, RGBA8, 15, 0, 0, 0, 0, 0, 0, 0, 0, 3});
			long texture = ShaderNative.textureNew(ctx, RGBA8, ShaderNative.TYPE_2D, 1, 1, 1, 1, ShaderNative.USAGE_SHADER_READ,
				ShaderNative.STORAGE_PRIVATE);
			ShaderNative.textureUpload(ctx, texture, new byte[] {0, (byte) 0xff, 0, (byte) 0xff}, 4L, 0, 0, 0, 0, 0, 0, 1, 1, 1);
			long target = ShaderNative.textureNew(ctx, RGBA8, ShaderNative.TYPE_2D, 4, 4, 1, 1,
				ShaderNative.USAGE_SHADER_READ | ShaderNative.USAGE_RENDER_TARGET, ShaderNative.STORAGE_PRIVATE);
			long enc = gpu.encoder();
			MetalBridge.renderBegin(enc, new long[] {target}, new float[][] {MetalBridge.DONT_CARE}, 0, false, 0, 4, 4);
			MetalBridge.renderPipeline(enc, pso, 0, false, false, 0, 0, MetalBridge.primitive(com.mojang.renderpearl.api.pipeline.PrimitiveTopology.TRIANGLES));
			MetalBridge.renderTexture(enc, index, texture);
			MetalBridge.renderDraw(enc, 3, 1, 0, 0);
			MetalBridge.endEncoders(enc);
			MetalBridge.commitAndWait(enc);
			byte[] pixels = gpu.readTexture(target, 0, 0, 4, 4, 1, 4);
			MetalBridge.release(texture);
			MetalBridge.release(target);
			MetalBridge.release(pso);
			return pixels[1] & 0xff;
		} finally {
			MetalBridge.release(lib.library());
		}
	}
}
