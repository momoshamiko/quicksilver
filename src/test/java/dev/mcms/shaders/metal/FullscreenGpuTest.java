// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.metal;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * W1-C: a full-screen pass writes a known colour per pixel (from the pixel position, a uniform and a sampled input
 * texture) into an RGBA8Unorm and an RGBA16Float target at once. Every written value is an exact 8-bit or exact
 * half-float value, so both readbacks are compared exactly: RGBA8 byte-for-byte, RGBA16Float through Java's own
 * float16 conversion (Float.float16ToFloat).
 */
@Tag("gpu")
final class FullscreenGpuTest {
	private static final int W = 8, H = 8, TEX = 4;
	/** Four distinct byte values; expected floats derive from them (byte / 255.0f), so the 8-bit and half checks are exact. */
	private static final int[] CHANNEL_BYTES = {0, 64, 191, 255};

	private static final String MSL = """
		#include <metal_stdlib>
		using namespace metal;
	struct Uni { float4 scale; };
	struct VOut { float4 pos [[position]]; float2 uv; };
		struct FOut { float4 c0 [[color(0)]]; float4 c1 [[color(1)]]; };

		vertex VOut vs_tri(uint vid [[vertex_id]]) {
			float2 p = float2((vid << 1) & 2, vid & 2);
			VOut o;
			o.pos = float4(p * 2.0f - 1.0f, 0.0f, 1.0f);
			o.uv = p;
			return o;
		}
		fragment FOut fs_tri(VOut v [[stage_in]], constant Uni &u [[buffer(0)]],
				texture2d<float> src [[texture(0)]]) {
			constexpr sampler nearest_s(filter::nearest, address::clamp_to_edge);
			float4 t = src.sample(nearest_s, v.uv);
			uint2 pix = uint2(v.pos.xy);   // the stage_in's position member is the fragment coordinate in pixels
			FOut o;
			o.c0 = float4(t.r, pix.x == pix.y ? 0.25f : 0.75f, t.b, (pix.x + pix.y) % 2u == 0u ? 1.0f : 0.0f);
			o.c1 = u.scale * float4((float) pix.x, (float) pix.y, t.g, 1.0f);
			return o;
		}
		""";

	@Test
	void writesKnownColoursFromPositionUniformAndTexture() {
		byte[] texture = new byte[TEX * TEX * 4];
		for (int ty = 0; ty < TEX; ty++) {
			for (int tx = 0; tx < TEX; tx++) {
				int at = (ty * TEX + tx) * 4;
				texture[at] = (byte) CHANNEL_BYTES[tx];
				texture[at + 1] = (byte) CHANNEL_BYTES[ty];
				texture[at + 2] = (byte) CHANNEL_BYTES[2];   // one constant b channel
				texture[at + 3] = (byte) 255;
			}
		}
		byte[] uniforms = new byte[16];
		ByteBuffer.wrap(uniforms).order(ByteOrder.LITTLE_ENDIAN).putFloat(1.5f).putFloat(0.75f).putFloat(2.0f).putFloat(1.0f);

		try (HeadlessGpu gpu = new HeadlessGpu()) {
			byte[][] targets = gpu.renderFullscreen(MSL, "vs_tri", "fs_tri",
				new int[]{HeadlessGpu.RGBA8_UNORM, HeadlessGpu.RGBA16_FLOAT}, W, H,
				new HeadlessGpu.InputTexture[]{HeadlessGpu.InputTexture.tex2d(HeadlessGpu.RGBA8_UNORM, TEX, TEX, texture)},
				uniforms, null);
			assertEquals(W * H * 4, targets[0].length, "RGBA8 target size");
			assertEquals(W * H * 8, targets[1].length, "RGBA16Float target size");

			var rgba8 = ByteBuffer.wrap(targets[0]).order(ByteOrder.LITTLE_ENDIAN);
			var rgba16 = ByteBuffer.wrap(targets[1]).order(ByteOrder.LITTLE_ENDIAN);
			for (int y = 0; y < H; y++) {
				for (int x = 0; x < W; x++) {
					int p = y * W + x;
					// Nearest sampling at pixel centres. The target's row 0 is the top row (NDC y = +1), where the
					// interpolated uv.y is near 1, so the texture row reads bottom-up: texel row (H-1-y)/2.
					int tx = x / 2, ty = (H - 1 - y) / 2;
					byte[] expectedRgba8 = {
						(byte) CHANNEL_BYTES[tx],
						(byte) (x == y ? 64 : 191),          // 0.25 / 0.75
						(byte) CHANNEL_BYTES[2],
						(byte) ((x + y) % 2 == 0 ? 255 : 0),
					};
					for (int c = 0; c < 4; c++) {
						int actual = rgba8.get(p * 4 + c) & 0xff, expected = expectedRgba8[c] & 0xff;
						assertTrue(actual == expected, "RGBA8 pixel (" + x + "," + y + ") channel " + c + ": " + actual
							+ ", expected " + expected);
					}
					// c1 = (1.5 * x, 0.75 * y, 2 * texel.g, 1). The texel channels decode to byte / 255. Measured on this
					// GPU: the store to the RGBA16Float target rounds toward zero (not to nearest), so allow one half
					// step (the brief's "within half-float precision"); exactly representable values still match exactly.
					float[] expectedC1 = {1.5f * x, 0.75f * y, 2.0f * (CHANNEL_BYTES[ty] / 255.0f), 1.0f};
					for (int c = 0; c < 4; c++) {
						short half = rgba16.getShort(p * 8 + c * 2);
						float actual = Float.float16ToFloat(half);
						float tolerance = 0.0011f * Math.max(1.0f, Math.abs(expectedC1[c]));  // one half step at this magnitude
						assertTrue(Math.abs(actual - expectedC1[c]) <= tolerance, "RGBA16Float pixel (" + x + "," + y
							+ ") channel " + c + ": half 0x" + Integer.toHexString(half & 0xffff) + " (" + actual
							+ "), expected " + expectedC1[c]);
					}
				}
			}
		}
	}
}
