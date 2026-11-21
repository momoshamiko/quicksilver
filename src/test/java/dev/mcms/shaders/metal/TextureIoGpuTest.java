// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.metal;

import dev.mcms.metal.MetalBridge;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * W1-C: the engine's own texture upload/read path round-trips boxes of every dimension (3D volumes, single slices of
 * arrays) and integer formats, and the native side rejects invalid texture arguments by returning nil instead of letting
 * Metal abort the process. Also the bounds check on the Java side: a short byte array is an argument error, not a native
 * out-of-bounds read.
 */
@Tag("gpu")
final class TextureIoGpuTest {
	@Test
	void volumeRoundTripsThroughEverySlice() {
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			long ctx = gpu.context();
			long tex = ShaderNative.textureNew(ctx, HeadlessGpu.R32_FLOAT, ShaderNative.TYPE_3D, 4, 4, 4, 1, 3, 2);
			assertTrue(tex != 0, "3D texture creation");
			byte[] up = new byte[4 * 4 * 4 * 4];
			float[] values = new float[64];
			for (int i = 0; i < 64; i++) {
				values[i] = i + 0.25f;
				ByteBuffer.wrap(up).order(ByteOrder.LITTLE_ENDIAN).putFloat(i * 4, values[i]);
			}
			assertTrue(ShaderNative.textureUpload(ctx, tex, up, 16, 64, 0, 0, 0, 0, 0, 4, 4, 4), "3D upload");

			assertArrayEquals(values, floats(gpu.readTexture(tex, 0, 0, 4, 4, 4, 4)), "the whole volume");
			// One z slice (origin z = 2) through the raw binding, which takes the origin.
			byte[] plane = new byte[4 * 4 * 4];
			assertTrue(ShaderNative.textureRead(ctx, tex, 0, 0, 0, 0, 2, 4, 4, 1, plane, 16, 0), "z-slice read");
			float[] expected = new float[16];
			System.arraycopy(values, 32, expected, 0, 16);
			assertArrayEquals(expected, floats(plane), "one z slice of the volume");
			MetalBridge.release(tex);
		}
	}

	@Test
	void arraySlicesRoundTripIndependently() {
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			long ctx = gpu.context();
			long tex = ShaderNative.textureNew(ctx, HeadlessGpu.R32_UINT, ShaderNative.TYPE_2D_ARRAY, 4, 4, 3, 1, 1, 2);
			assertTrue(tex != 0, "2D array texture creation");
			byte[] slice1 = new byte[4 * 4 * 4];
			int[] values = new int[16];
			for (int i = 0; i < 16; i++) {
				values[i] = 0xdead0000 + i;
				ByteBuffer.wrap(slice1).order(ByteOrder.LITTLE_ENDIAN).putInt(i * 4, values[i]);
			}
			// Upload to slice 1 only; slice 0 stays zero.
			assertTrue(ShaderNative.textureUpload(ctx, tex, slice1, 16, 0, 1, 0, 0, 0, 0, 4, 4, 1), "slice upload");
			assertArrayEquals(values, ints(gpu.readTexture(tex, 1, 0, 4, 4, 1, 4)), "slice 1 read back");
			assertArrayEquals(new int[16], ints(gpu.readTexture(tex, 0, 0, 4, 4, 1, 4)), "slice 0 untouched");
			MetalBridge.release(tex);
		}
	}

	@Test
	void shortByteArraysAreRejectedInJava() {
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			long ctx = gpu.context();
			long tex = ShaderNative.textureNew(ctx, HeadlessGpu.R32_FLOAT, ShaderNative.TYPE_2D, 4, 4, 1, 1, 1, 2);
			assertTrue(tex != 0);
			assertThrows(IllegalArgumentException.class,
				() -> ShaderNative.textureUpload(ctx, tex, new byte[10], 16, 0, 0, 0, 0, 0, 0, 4, 4, 1));
			assertThrows(IllegalArgumentException.class,
				() -> ShaderNative.textureRead(ctx, tex, 0, 0, 0, 0, 0, 4, 4, 1, new byte[10], 16, 0));
			MetalBridge.release(tex);
		}
	}

	@Test
	void invalidTextureArgumentsReturnNilWithoutCallingMetal() {
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			long ctx = gpu.context();
			assertEquals(0, ShaderNative.textureNew(ctx, HeadlessGpu.R32_FLOAT, ShaderNative.TYPE_2D, 4, 4, 1, 0, 1, 2),
				"mips = 0 must be rejected in the engine's code (Metal aborts on it)");
			assertEquals(0, ShaderNative.textureNew(ctx, HeadlessGpu.R32_FLOAT, ShaderNative.TYPE_2D, 0, 4, 1, 1, 1, 2),
				"width = 0 must be rejected");
			assertEquals(0, ShaderNative.textureNew(ctx, HeadlessGpu.R32_FLOAT, ShaderNative.TYPE_2D, 4, 4, 0, 1, 1, 2),
				"depth = 0 must be rejected");
		}
	}

	private static float[] floats(byte[] bytes) {
		float[] out = new float[bytes.length / 4];
		ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(out);
		return out;
	}

	private static int[] ints(byte[] bytes) {
		int[] out = new int[bytes.length / 4];
		ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(out);
		return out;
	}
}
