// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.metal;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.mcms.metal.MetalBridge;
import java.lang.foreign.MemorySegment;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * What Metal does with one render pass whose colour attachments differ in size (E6-drawbuffers, 2026-11-15). A
 * {@code size.buffer} target is half the render's size, so a gbuffers pass that attached it with colortex0 would do this.
 * The probe draws a full-screen triangle that writes red to attachment 0 (8 by 8) and green to attachment 1 (4 by 4), then
 * reads both back and counts the pixels each colour reached.
 *
 * <p>Measured on this Mac (2026-11-15): Metal reports no error and clips the pass to the smallest attachment. The 8 by 8
 * attachment gets red in its top-left 4 by 4 only (16 of 64 pixels) and keeps the clear value elsewhere; the 4 by 4
 * attachment gets all 16 of its pixels green. A gbuffers pass therefore never attaches two sizes: GbufferPlans does not run
 * a gbuffers program that writes a target sized apart from the render.
 */
@Tag("gpu")
final class AttachmentSizesGpuTest {
	private static final int RGBA8_UNORM = 70;
	private static final int TEXTURE_2D = 2;
	private static final int USAGE_SHADER_READ = 1, USAGE_RENDER_TARGET = 4, STORAGE_PRIVATE = 2;
	private static final int WRITE_MASK_ALL = 15, TOPOLOGY_TRIANGLE = 3, PRIMITIVE_TRIANGLE = 3;

	private static final String MSL = """
		#include <metal_stdlib>
		using namespace metal;
		struct VOut { float4 pos [[position]]; };
		struct FOut { float4 c0 [[color(0)]]; float4 c1 [[color(1)]]; };
		vertex VOut vs_tri(uint vid [[vertex_id]]) {
			float2 p = float2((vid << 1) & 2, vid & 2);
			VOut o;
			o.pos = float4(p * 2.0f - 1.0f, 0.0f, 1.0f);
			return o;
		}
		fragment FOut fs_tri(VOut v [[stage_in]]) {
			FOut o;
			o.c0 = float4(1.0f, 0.0f, 0.0f, 1.0f);
			o.c1 = float4(0.0f, 1.0f, 0.0f, 1.0f);
			return o;
		}
		""";

	@Test
	void aPassWithAttachmentsOfTwoSizesClipsToTheSmallest() {
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			long ctx = gpu.context();
			long enc = gpu.encoder();
			ShaderNative.Compiled lib = ShaderNative.libraryNew(ctx, MSL, ShaderNative.MSL_3_1, ShaderNative.MATH_PACKS, true);
			if (!lib.ok()) throw new IllegalStateException("probe source failed to compile: " + lib.error());
			int[] descriptor = {0, 0, 2, RGBA8_UNORM, WRITE_MASK_ALL, 0, 0, 0, 0, 0, 0, 0,
				RGBA8_UNORM, WRITE_MASK_ALL, 0, 0, 0, 0, 0, 0, 0, 0, TOPOLOGY_TRIANGLE};
			long pso = MetalBridge.pipelineNew(ctx, lib.library(), "vs_tri", lib.library(), "fs_tri", descriptor);
			long big = ShaderNative.textureNew(ctx, RGBA8_UNORM, TEXTURE_2D, 8, 8, 1, 1,
				USAGE_RENDER_TARGET | USAGE_SHADER_READ, STORAGE_PRIVATE);
			long small = ShaderNative.textureNew(ctx, RGBA8_UNORM, TEXTURE_2D, 4, 4, 1, 1,
				USAGE_RENDER_TARGET | USAGE_SHADER_READ, STORAGE_PRIVATE);
			try {
				float[] clear = {0f, 0f, 0f, 0f};
				MetalBridge.renderBegin(enc, new long[] {big, small}, new float[][] {clear, clear}, 0, false, 0, 8, 8);
				MetalBridge.renderPipeline(enc, pso, 0, false, false, 0, 0, PRIMITIVE_TRIANGLE);
				MetalBridge.renderDraw(enc, 3, 1, 0, 0);
				MetalBridge.endEncoders(enc);
				byte[] bigPixels = readback(ctx, enc, big, 8, 8);
				byte[] smallPixels = readback(ctx, enc, small, 4, 4);
				assertEquals(16, count(bigPixels, 255, 0), "the 8 by 8 attachment's red reaches only the 4 by 4 overlap");
				assertEquals(0, count(bigPixels, 0, 255), "the 8 by 8 attachment gets no green");
				assertEquals(16, count(smallPixels, 0, 255), "the 4 by 4 attachment is written in full");
				assertEquals(0, count(smallPixels, 255, 0), "the 4 by 4 attachment gets no red");
			} finally {
				MetalBridge.release(big);
				MetalBridge.release(small);
				MetalBridge.release(pso);
				MetalBridge.release(lib.library());
			}
		}
	}

	private static byte[] readback(long ctx, long enc, long texture, int width, int height) {
		long size = (long) width * height * 4;
		long buffer = MetalBridge.newBuffer(ctx, size);
		try {
			MetalBridge.readTexture(enc, texture, width, height, 4, buffer);
			MetalBridge.commitAndWait(enc);
			return MemorySegment.ofAddress(MetalBridge.bufferContents(buffer)).reinterpret(size).toArray(JAVA_BYTE);
		} finally {
			MetalBridge.release(buffer);
		}
	}

	/** The pixels whose red and green bytes are exactly the given values (blue and alpha are not compared). */
	private static int count(byte[] rgba, int red, int green) {
		int n = 0;
		for (int p = 0; p + 3 < rgba.length; p += 4) {
			if ((rgba[p] & 0xff) == red && (rgba[p + 1] & 0xff) == green) n++;
		}
		return n;
	}
}
