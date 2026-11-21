// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcms.metal.MetalBridge;
import dev.mcms.shaders.metal.HeadlessGpu;
import dev.mcms.shaders.metal.ShaderNative;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The engine's own copy draw (Engine.COPY_MSL) keeps the picture upright between two OpenGL-row-order textures: the
 * world copy into a colortex0 whose format differs from the main colour's (EngineFrame.copyWorld's draw branch) and
 * the no-final copy back onto a main colour of another format (EngineFrame.copyToMain's draw branch). The draw is the
 * one EngineFrame records: renderBegin over the destination, the copy pipeline of the destination's format, the
 * source at texture 0, one full-screen triangle. The source is asymmetric (bottom rows blue, top rows red, the left
 * columns dark), so a vertical or horizontal flip cannot pass.
 */
@Tag("gpu")
class EngineCopyOrientationGpuTest {

	private static final int W = 256, H = 160;
	private static final int RGBA8 = 70, RGBA16F = 115;
	private static final int RT_READ = ShaderNative.USAGE_SHADER_READ | ShaderNative.USAGE_RENDER_TARGET;

	/** The world-copy draw: an RGBA16F main colour into a colortex0 of another format (Complementary's situation). */
	@Test
	void worldCopyIntoAFormatChangingTargetIsUpright() {
		checkOneCopy(RGBA16F, RGBA8);
	}

	/** The no-final copy: a colortex0 of another format onto an RGBA8 main colour. */
	@Test
	void copyToMainOfAnotherFormatIsUpright() {
		checkOneCopy(RGBA8, RGBA16F);
	}

	private void checkOneCopy(int sourceFormat, int destFormat) {
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			long ctx = gpu.context();
			long enc = gpu.encoder();
			List<Long> toRelease = new ArrayList<>();
			try {
				long source = picture(ctx, sourceFormat, toRelease);
				long dest = ShaderNative.textureNew(ctx, destFormat, ShaderNative.TYPE_2D, W, H, 1, 1, RT_READ,
					ShaderNative.STORAGE_PRIVATE);
				assertTrue(dest != 0, "the device refused the destination");
				toRelease.add(dest);
				long pipeline = Engine.copyPipeline(ctx, destFormat);
				assertTrue(pipeline != 0, "the copy pipeline of the destination's format");
				toRelease.add(pipeline);
				long[] colors = {dest};
				float[][] clears = {MetalBridge.DONT_CARE};
				MetalBridge.renderBegin(enc, colors, clears, 0, false, 0, W, H);
				MetalBridge.renderPipeline(enc, pipeline, 0, false, false, 0, 0, 3);
				MetalBridge.renderTexture(enc, 0, source);
				MetalBridge.renderDraw(enc, 3, 1, 0, 0);
				MetalBridge.commitAndWait(enc);
				assertEquals("blue", texel(dest, W / 2, 8, destFormat, gpu), "the bottom rows must stay at the bottom");
				assertEquals("red", texel(dest, W / 2, H - 9, destFormat, gpu), "the top rows must stay at the top");
				assertEquals("dark", texel(dest, 4, 8, destFormat, gpu), "the left columns must stay at the left");
			} finally {
				toRelease.forEach(handle -> {
					if (handle != null && handle != 0) {
						MetalBridge.release(handle);
					}
				});
			}
		}
	}

	/** Which primary colour a texel holds ("blue", "red", "dark" or other), independent of the format's encoding. */
	private static String texel(long texture, int x, int y, int format, HeadlessGpu gpu) {
		float[] rgba = format == RGBA8 ? rgba8(texture, x, y, gpu) : rgba16(texture, x, y, gpu);
		if (rgba[2] > 0.75f && rgba[0] < 0.25f) {
			return "blue";
		}
		if (rgba[0] > 0.75f && rgba[2] < 0.25f) {
			return "red";
		}
		if (rgba[0] < 0.25f && rgba[2] < 0.25f) {
			return "dark";
		}
		return String.format(java.util.Locale.ROOT, "other(%.2f,%.2f,%.2f)", rgba[0], rgba[1], rgba[2]);
	}

	private static float[] rgba8(long texture, int x, int y, HeadlessGpu gpu) {
		byte[] bytes = gpu.readTexture(texture, 0, 0, W, H, 1, 4);
		int at = (y * W + x) * 4;
		return new float[] {(bytes[at] & 0xff) / 255f, (bytes[at + 1] & 0xff) / 255f, (bytes[at + 2] & 0xff) / 255f};
	}

	private static float[] rgba16(long texture, int x, int y, HeadlessGpu gpu) {
		byte[] bytes = gpu.readTexture(texture, 0, 0, W, H, 1, 8);
		int at = (y * W + x) * 8;
		float[] out = new float[3];
		for (int c = 0; c < 3; c++) {
			int lo = bytes[at + c * 2] & 0xff, hi = bytes[at + c * 2 + 1] & 0xff;
			short half = (short) (lo | hi << 8);
			int exponent = (half >> 10) & 0x1f, mantissa = half & 0x3ff;
			out[c] = exponent == 0 ? 0f : (float) (Math.pow(2, exponent - 15) * (1 + mantissa / 1024.0));
		}
		return out;
	}

	/** The asymmetric picture in OpenGL's row order: bottom rows blue, top rows red, the left 16 columns dark. */
	private static long picture(long ctx, int format, List<Long> tracker) {
		int bpp = format == RGBA8 ? 4 : 8;
		byte[] texels = new byte[W * H * bpp];
		for (int y = 0; y < H; y++) {
			for (int x = 0; x < W; x++) {
				boolean blue = y < H / 2, dark = x < 16;
				float r = dark ? 0f : blue ? 0f : 1f, g = 0f, b = dark ? 0f : blue ? 1f : 0f, a = 1f;
				int at = (y * W + x) * bpp;
				if (format == RGBA8) {
					texels[at] = (byte) Math.round(r * 255);
					texels[at + 1] = (byte) Math.round(g * 255);
					texels[at + 2] = (byte) Math.round(b * 255);
					texels[at + 3] = (byte) 0xff;
				} else {
					short[] channels = {half(r), half(g), half(b), half(a)};
					for (int c = 0; c < 4; c++) {
						texels[at + c * 2] = (byte) channels[c];
						texels[at + c * 2 + 1] = (byte) (channels[c] >> 8);
					}
				}
			}
		}
		long texture = ShaderNative.textureNew(ctx, format, ShaderNative.TYPE_2D, W, H, 1, 1, RT_READ,
			ShaderNative.STORAGE_PRIVATE);
		assertTrue(texture != 0, "the device refused the synthetic picture");
		assertTrue(ShaderNative.textureUpload(ctx, texture, texels, (long) W * bpp, 0, 0, 0, 0, 0, 0, W, H, 1));
		tracker.add(texture);
		return texture;
	}

	private static short half(float v) {
		if (v == 0f) {
			return 0;
		}
		int bits = Float.floatToIntBits(v);
		return (short) (((bits >>> 16) & 0x8000) | ((((bits >>> 23) & 0xff) - 127 + 15) << 10) | ((bits & 0x7fffff) >>> 13));
	}
}
