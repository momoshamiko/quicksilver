// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime;

import static dev.mcms.shaders.runtime.DumpTexels.BGRA8_UNORM;
import static dev.mcms.shaders.runtime.DumpTexels.R16_FLOAT;
import static dev.mcms.shaders.runtime.DumpTexels.R32_FLOAT;
import static dev.mcms.shaders.runtime.DumpTexels.R8_SNORM;
import static dev.mcms.shaders.runtime.DumpTexels.R8_UNORM;
import static dev.mcms.shaders.runtime.DumpTexels.RG11B10_FLOAT;
import static dev.mcms.shaders.runtime.DumpTexels.RG16_FLOAT;
import static dev.mcms.shaders.runtime.DumpTexels.RG32_FLOAT;
import static dev.mcms.shaders.runtime.DumpTexels.RGB10A2_UNORM;
import static dev.mcms.shaders.runtime.DumpTexels.RGBA16_FLOAT;
import static dev.mcms.shaders.runtime.DumpTexels.RGBA16_UNORM;
import static dev.mcms.shaders.runtime.DumpTexels.RGBA32_FLOAT;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;

/**
 * The picture dumps' colour layouts (2026-11-14, E4 debugging): the byte size of each format the dumps read, and the values
 * that known texels decode to. A wrong size would misread every texel after the first; a wrong decode would paint the wrong
 * colour or hide a NaN.
 */
class DumpTexelsTest {

	private static final int D32_FLOAT = 252;

	@Test
	void bytesPerTexelFollowTheFormatWidths() {
		assertEquals(1, DumpTexels.bytesPerTexel(R8_UNORM));
		assertEquals(2, DumpTexels.bytesPerTexel(R16_FLOAT));
		assertEquals(4, DumpTexels.bytesPerTexel(R32_FLOAT));
		assertEquals(4, DumpTexels.bytesPerTexel(RG11B10_FLOAT));
		assertEquals(4, DumpTexels.bytesPerTexel(RGB10A2_UNORM));
		assertEquals(4, DumpTexels.bytesPerTexel(RG16_FLOAT));
		assertEquals(4, DumpTexels.bytesPerTexel(BGRA8_UNORM));
		assertEquals(8, DumpTexels.bytesPerTexel(RGBA16_UNORM));
		assertEquals(8, DumpTexels.bytesPerTexel(RG32_FLOAT));
		assertEquals(16, DumpTexels.bytesPerTexel(RGBA32_FLOAT));
	}

	@Test
	void depthAndUnknownFormatsHaveNoColourLayout() {
		assertEquals(0, DumpTexels.bytesPerTexel(D32_FLOAT), "depth is read by the depth path, not as colour");
		assertEquals(0, DumpTexels.bytesPerTexel(0));
		assertEquals(0, DumpTexels.bytesPerTexel(-1));
		assertEquals(0, DumpTexels.bytesPerTexel(1000));
	}

	@Test
	void halfFloatTexelKeepsNanAndInfinityAndTheRest() {
		ByteBuffer bytes = little(8);
		bytes.putShort(Float.floatToFloat16(Float.NaN));
		bytes.putShort(Float.floatToFloat16(Float.POSITIVE_INFINITY));
		bytes.putShort(Float.floatToFloat16(Float.NEGATIVE_INFINITY));
		bytes.putShort(Float.floatToFloat16(0.5f));
		float[] rgba = decode(bytes, RGBA16_FLOAT);
		assertTrue(Float.isNaN(rgba[0]), "red NaN");
		assertEquals(Float.POSITIVE_INFINITY, rgba[1]);
		assertEquals(Float.NEGATIVE_INFINITY, rgba[2]);
		assertEquals(0.5f, rgba[3]);
	}

	@Test
	void elevenTenFloatDecodesOneInfinityAndNan() {
		// Red 11-bit exponent 15, mantissa 0 = 1.0; green 11-bit exponent 31, mantissa 0 = +Inf;
		// blue 10-bit exponent 31, mantissa 1 = NaN.
		ByteBuffer bytes = little(4);
		bytes.putInt(960 | (1984 << 11) | (993 << 22));
		float[] rgba = decode(bytes, RG11B10_FLOAT);
		assertEquals(1f, rgba[0]);
		assertEquals(Float.POSITIVE_INFINITY, rgba[1]);
		assertTrue(Float.isNaN(rgba[2]), "blue NaN");
		assertEquals(1f, rgba[3], "no alpha channel reads 1");
	}

	@Test
	void elevenTenFloatDecodesFractions() {
		// Blue 10-bit exponent 15, mantissa 16 = 1 + 16/32 = 1.5.
		ByteBuffer bytes = little(4);
		bytes.putInt(496 << 22);
		assertEquals(1.5f, decode(bytes, RG11B10_FLOAT)[2]);
	}

	@Test
	void packedUnormTenBitFieldsAndTwoBitAlpha() {
		// Red all ones = 1.0; green 0; blue 512 of 1023; alpha 3 of 3.
		ByteBuffer bytes = little(4);
		bytes.putInt(1023 | (512 << 20) | (3 << 30));
		assertArrayEquals(new float[] {1f, 0f, 512f / 1023f, 1f}, decode(bytes, RGB10A2_UNORM), 1e-6f);
	}

	@Test
	void unormEightBitMapsTo01AndMissingChannelsReadZeroAndOne() {
		ByteBuffer bytes = little(1);
		bytes.put((byte) 255);
		assertArrayEquals(new float[] {1f, 0f, 0f, 1f}, decode(bytes, R8_UNORM), 0f);
	}

	@Test
	void snormEightBitBottomsOutAtMinusOne() {
		ByteBuffer bytes = little(1);
		bytes.put((byte) -128);
		assertEquals(-1f, decode(bytes, R8_SNORM)[0], "-128 would be -1.0079; it reads as -1");
	}

	@Test
	void blueFirstFormatsSwapRedAndBlue() {
		ByteBuffer bytes = little(4);
		bytes.put((byte) 255).put((byte) 0).put((byte) 0).put((byte) 255);  // blue 255, green 0, red 0, alpha 255
		assertArrayEquals(new float[] {0f, 0f, 1f, 1f}, decode(bytes, BGRA8_UNORM), 0f);
	}

	@Test
	void singleFloatKeepsNanAndInfinity() {
		ByteBuffer bytes = little(4);
		bytes.putFloat(Float.NEGATIVE_INFINITY);
		assertEquals(Float.NEGATIVE_INFINITY, decode(bytes, R32_FLOAT)[0]);
	}

	private static ByteBuffer little(int size) {
		return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
	}

	/** Decodes the texel at byte 0 of a buffer the test filled. */
	private static float[] decode(ByteBuffer bytes, int format) {
		float[] rgba = new float[4];
		DumpTexels.decode(bytes, 0, format, rgba);
		return rgba;
	}
}
