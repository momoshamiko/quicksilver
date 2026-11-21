// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.gbuffers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import dev.mcms.metal.MetalBridge;
import dev.mcms.shaders.metal.HeadlessGpu;
import dev.mcms.shaders.metal.ShaderNative;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The binding path a program's textures take (MetalRenderPass, GbufferDelegate): a texture at index 17 samples through the
 * sampler the program's stage names (slot 0), and a sampler bound at an index past the stage's sixteen slots (here the
 * entity overlay's texture index, 27) is dropped while its texture still binds. Before the change, the overlay bound its
 * sampler at its texture index, and Metal refused it inside the draw.
 */
@Tag("gpu")
class SamplerSlotsGpuTest {

	private static final int W = 8, H = 8, RGBA8 = 70;
	private static final int RT = ShaderNative.USAGE_SHADER_READ | ShaderNative.USAGE_RENDER_TARGET;

	/** The fragment reads texture 17 through sampler 0, texture 5 through sampler 1 (its index is not its slot), texture 27 by fetch. */
	private static final String MSL = """
		#include <metal_stdlib>
		using namespace metal;
		struct VaryingOut { float4 position [[position]]; };
		vertex VaryingOut triangle_vs(uint vid [[vertex_id]]) {
			VaryingOut out;
			float2 p = float2(float((vid << 1) & 2), float(vid & 2));
			out.position = float4(p * 2.0 - 1.0, 0.5, 1.0);
			return out;
		}
		fragment float4 triangle_fs(VaryingOut in [[stage_in]], texture2d<float> far [[texture(17)]],
				texture2d<float> mid [[texture(5)]], sampler smp [[sampler(0)]], sampler smp1 [[sampler(1)]],
				texture2d<float> pack [[texture(27)]]) {
			float r = far.sample(smp, float2(0.5)).r;
			float b = mid.sample(smp1, float2(0.5)).b;
			float g = pack.read(uint2(0, 0)).g;
			return float4(r, g, b, 1.0);
		}
		""";

	@Test
	void aTextureAtIndexSeventeenSamplesThroughSlotZeroAndAnOutOfRangeSamplerIsDropped() {
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			long ctx = gpu.context();
			long enc = gpu.encoder();
			List<Long> owned = new java.util.ArrayList<>();
			try {
				long far = texture(ctx, new byte[] {(byte) 64, 0, 0, (byte) 0xff}, owned);   // red 0.25
				long pack = texture(ctx, new byte[] {0, (byte) 0xff, 0, (byte) 0xff}, owned); // green 1.0
				long mid = texture(ctx, new byte[] {0, 0, (byte) 0xff, (byte) 0xff}, owned);  // blue 1.0
				long sampler = MetalBridge.samplerNew(ctx, 0, 0, 0, 0, 0, 1, 0.25f);
				owned.add(sampler);
				long target = ShaderNative.textureNew(ctx, RGBA8, ShaderNative.TYPE_2D, W, H, 1, 1, RT, ShaderNative.STORAGE_PRIVATE);
				owned.add(target);
				long pso = pipeline(ctx, owned);

				MetalBridge.renderBegin(enc, new long[] {target}, new float[][] {MetalBridge.DONT_CARE}, 0, false, 0, W, H);
				MetalBridge.renderPipeline(enc, pso, 0, false, false, 0, 0, MetalBridge.primitive(PrimitiveTopology.TRIANGLES));
				MetalBridge.renderTextureSlots(enc, 17, far, sampler, -1, 0);
				// Texture 5 sits between the others by index but takes sampler slot 1: its sampler is never bound at index 5.
				MetalBridge.renderTextureSlots(enc, 5, mid, sampler, -1, 1);
				MetalBridge.renderTexture(enc, 27, pack);
				// An out-of-range sampler: the texture binds at 27, and the sampler is dropped (SamplerSlots), not sent to Metal.
				MetalBridge.renderTextureSlots(enc, 27, pack, sampler, 27, 27);
				MetalBridge.renderDraw(enc, 3, 1, 0, 0);
				MetalBridge.endEncoders(enc);
				MetalBridge.commitAndWait(enc);

				byte[] pixels = gpu.readTexture(target, 0, 0, W, H, 1, 4);
				int red = pixels[0] & 0xff, green = pixels[1] & 0xff, blue = pixels[2] & 0xff;
				assertTrue(Math.abs(red - 64) <= 2, "texture 17 sampled through slot 0 gives its red 0.25, got " + red);
				assertEquals(255, green, "texture 27 read by fetch gives its green 1.0");
				assertEquals(255, blue, "texture 5 sampled through slot 1 gives its blue 1.0");
			} finally {
				owned.forEach(handle -> {
					if (handle != 0) {
						MetalBridge.release(handle);
					}
				});
			}
		}
	}

	/** A 1x1 RGBA8 texture with the given texel. */
	private static long texture(long ctx, byte[] texel, List<Long> owned) {
		long texture = ShaderNative.textureNew(ctx, RGBA8, ShaderNative.TYPE_2D, 1, 1, 1, 1, ShaderNative.USAGE_SHADER_READ,
			ShaderNative.STORAGE_PRIVATE);
		assertTrue(texture != 0, "the device refused a texture");
		assertTrue(ShaderNative.textureUpload(ctx, texture, texel, 4L, 0, 0, 0, 0, 0, 0, 1, 1, 1));
		owned.add(texture);
		return texture;
	}

	private static long pipeline(long ctx, List<Long> owned) {
		int[] descriptor = GbufferPlans.descriptorWords(GbufferPlans.vertexWords(0, List.of()), new int[] {0}, new int[] {RGBA8},
			Set.of(0), target -> null, MetalBridge.topologyClass(PrimitiveTopology.TRIANGLES), true);
		ShaderNative.Compiled library = ShaderNative.libraryNew(ctx, MSL, ShaderNative.MSL_3_1, ShaderNative.MATH_PACKS, true);
		assertTrue(library.ok(), "library failed: " + library.error());
		owned.add(library.library());
		long pso = MetalBridge.pipelineNew(ctx, library.library(), "triangle_vs", library.library(), "triangle_fs", descriptor);
		assertTrue(pso != 0, "the pipeline builds");
		owned.add(pso);
		return pso;
	}
}
