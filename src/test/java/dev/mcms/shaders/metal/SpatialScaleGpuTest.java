// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.metal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.renderpearl.api.GpuFormat;
import dev.mcms.metal.MetalBridge;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * ADR 0008's upscale on this device: MetalFX's spatial scaler from a render-size texture into a window-size one, both made the
 * way the engine's stand-in and the game's main colour are (render target and shader read, private). Four coloured quarters
 * land in the same quarters at the output size (no flip, the whole input content scaled to the whole output), a flat area
 * stays flat, the second call reuses the scaler, and a refusal encodes nothing, so the engine's fallback draws on an
 * untouched main colour.
 */
@Tag("gpu")
final class SpatialScaleGpuTest {

	private static final int IN_W = 64, IN_H = 48, OUT_W = 96, OUT_H = 72;
	/** Top left, top right, bottom left, bottom right (rows from the top: texture row 0 is the top). */
	private static final int[][] QUARTERS = {{255, 0, 0, 255}, {0, 255, 0, 255}, {0, 0, 255, 255}, {255, 255, 255, 255}};

	private static void fill(long enc, long texture, int width, int height, float[] colour) {
		MetalBridge.renderBegin(enc, new long[] {texture}, new float[][] {colour}, 0, false, 0, width, height);
		MetalBridge.endEncoders(enc);
	}

	private static byte[] quarters() {
		byte[] texels = new byte[IN_W * IN_H * 4];
		for (int y = 0; y < IN_H; y++) {
			for (int x = 0; x < IN_W; x++) {
				int[] c = QUARTERS[(y < IN_H / 2 ? 0 : 2) + (x < IN_W / 2 ? 0 : 1)];
				for (int k = 0; k < 4; k++) {
					texels[4 * (y * IN_W + x) + k] = (byte) c[k];
				}
			}
		}
		return texels;
	}

	/** The colour at an output pixel, as 0-255 RGBA. */
	private static int[] at(byte[] pixels, int x, int y) {
		int i = 4 * (y * OUT_W + x);
		return new int[] {pixels[i] & 0xff, pixels[i + 1] & 0xff, pixels[i + 2] & 0xff, pixels[i + 3] & 0xff};
	}

	@Test
	void eachQuarterLandsInItsQuarterAndARefusalEncodesNothing() {
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			long ctx = gpu.context();
			long enc = gpu.encoder();
			long in = MetalBridge.newTexture(ctx, GpuFormat.RGBA8_UNORM, IN_W, IN_H, true);
			long out = MetalBridge.newTexture(ctx, GpuFormat.RGBA8_UNORM, OUT_W, OUT_H, true);
			try {
				assertTrue(ShaderNative.textureUpload(ctx, in, quarters(), IN_W * 4L, 0, 0, 0, 0, 0, 0, IN_W, IN_H, 1),
					"the input quarters upload");
				fill(enc, out, OUT_W, OUT_H, new float[] {0f, 0f, 0f, 1f});
				int first = ShaderNative.spatialScale(enc, in, out, false);
				assertEquals(ShaderNative.SPATIAL_BUILT, first,
					"the first upscale builds a scaler (" + (first < 0 ? ShaderNative.spatialRefusal(first) : "") + ")");
				MetalBridge.commitAndWait(enc);
				byte[] pixels = gpu.readTexture(out, 0, 0, OUT_W, OUT_H, 1, 4);
				// The centre of each output quarter, a quarter of the output away from every edge between colours.
				int[][] centres = {{OUT_W / 4, OUT_H / 4}, {3 * OUT_W / 4, OUT_H / 4}, {OUT_W / 4, 3 * OUT_H / 4},
					{3 * OUT_W / 4, 3 * OUT_H / 4}};
				for (int q = 0; q < 4; q++) {
					int[] got = at(pixels, centres[q][0], centres[q][1]);
					for (int c = 0; c < 4; c++) {
						assertTrue(Math.abs(got[c] - QUARTERS[q][c]) <= 3, "quarter " + q + " channel " + c + " is " + got[c]
							+ ", the input quarter's " + QUARTERS[q][c] + " (a flip or a wrong content size moves the quarters)");
					}
				}
				assertEquals(ShaderNative.SPATIAL_REUSED, ShaderNative.spatialScale(enc, in, out, false),
					"the same sizes and formats reuse the scaler");
				MetalBridge.commitAndWait(enc);

				fill(enc, out, OUT_W, OUT_H, new float[] {0f, 0f, 0f, 1f});
				assertEquals(ShaderNative.SPATIAL_UNSUPPORTED, ShaderNative.spatialScale(enc, in, out, true),
					"refuse answers as a device without MetalFX");
				MetalBridge.commitAndWait(enc);
				byte[] untouched = gpu.readTexture(out, 0, 0, OUT_W, OUT_H, 1, 4);
				for (int i = 0; i < OUT_W * OUT_H; i++) {
					assertEquals(0, untouched[4 * i] & 0xff, "a refusal encodes nothing: pixel " + i + " keeps the black fill");
				}
			} finally {
				ShaderNative.spatialRelease();
				MetalBridge.release(in);
				MetalBridge.release(out);
			}
		}
	}
}
