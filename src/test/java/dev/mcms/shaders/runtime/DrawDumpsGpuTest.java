// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcms.metal.MetalBridge;
import dev.mcms.shaders.metal.HeadlessGpu;
import dev.mcms.shaders.metal.ShaderNative;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The picture dump of an RGBA16F texture (2026-11-14, E4 debugging: float stages were skipped). A 4x4 texture of known texels
 * goes through the real readback to a PNG: the PNG's pixels are the clamped values, NaN pixels are magenta, Inf pixels cyan,
 * and the DUMP counts are nan=5 inf=3.
 */
@Tag("gpu")
class DrawDumpsGpuTest {

	private static final int W = 4, H = 4;
	private static final float NAN = Float.NaN, INF = Float.POSITIVE_INFINITY;
	private static final int MAGENTA = 0xffff00ff, CYAN = 0xff00ffff;

	/** Row-major texels (r, g, b, a): clamping, NaN in one channel, Inf in one, both, alpha 0, and an alpha NaN. */
	private static final float[][] TEXELS = {
		{0.5f, 0.25f, 2f, 1f}, {-0.5f, 1.5f, 0f, 1f}, {NAN, 0f, 0f, 1f}, {INF, 0.5f, 0f, 1f},
		{-INF, 0f, 0f, 1f}, {0.5f, NAN, INF, 1f}, {0f, 0f, 0f, NAN}, {1f, 1f, 1f, 1f},
		{0f, 0f, 0f, 0f}, {1f, 0f, 0f, 0.5f}, {INF, INF, INF, 1f}, {0.25f, 0.75f, 0.25f, 1f},
		{NAN, NAN, NAN, NAN}, {3f, -2f, 0.5f, 1f}, {0f, 0.5f, 1f, 0.5f}, {-INF, NAN, 0f, 1f},
	};

	/** The ARGB pixel each texel must paint. */
	private static final int[] EXPECTED = {
		0xff8040ff, 0xff00ff00, MAGENTA, CYAN,
		CYAN, MAGENTA, MAGENTA, 0xffffffff,
		0x00000000, 0x80ff0000, CYAN, 0xff40bf40,
		MAGENTA, 0xffff0080, 0x800080ff, MAGENTA,
	};

	@Test
	void rgba16FloatTextureIsDumpedAsClampedPixelsWithNonFiniteCounts(@TempDir Path dir) throws IOException {
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			long ctx = gpu.context();
			long texture = ShaderNative.textureNew(ctx, DumpTexels.RGBA16_FLOAT, ShaderNative.TYPE_2D, W, H, 1, 1,
				ShaderNative.USAGE_SHADER_READ, ShaderNative.STORAGE_PRIVATE);
			assertTrue(texture != 0, "texture creation");
			try {
				ByteBuffer up = ByteBuffer.allocate(W * H * 8).order(ByteOrder.LITTLE_ENDIAN);
				for (float[] texel : TEXELS) {
					for (float value : texel) {
						up.putShort(Float.floatToFloat16(value));
					}
				}
				assertTrue(ShaderNative.textureUpload(ctx, texture, up.array(), W * 8L, 0, 0, 0, 0, 0, 0, W, H, 1), "upload");

				DrawDumps.Dumped dumped = DrawDumps.dumpTo(dir, null, gpu.encoder(), texture, "rgba16", false);
				assertNotNull(dumped, "the RGBA16F texture is dumped, not skipped");
				assertEquals(5, dumped.nan(), "NaN pixels");
				assertEquals(3, dumped.inf(), "Inf pixels");
				assertTrue(dumped.line().contains("format=" + DumpTexels.RGBA16_FLOAT), dumped.line());
				assertTrue(dumped.line().contains("clamped 0..1") && dumped.line().contains("nan=5 inf=3"), dumped.line());

				BufferedImage png = ImageIO.read(dumped.file().toFile());
				assertNotNull(png, "the PNG file reads back");
				assertEquals(W, png.getWidth());
				assertEquals(H, png.getHeight());
				for (int i = 0; i < TEXELS.length; i++) {
					assertEquals(EXPECTED[i], png.getRGB(i % W, i / W), "pixel " + i);
				}
			} finally {
				MetalBridge.release(texture);
			}
		}
	}
}
