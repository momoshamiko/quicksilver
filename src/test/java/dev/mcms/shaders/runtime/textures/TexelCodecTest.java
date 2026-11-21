// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.textures;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.mcms.shaders.pack.textures.CustomTextureTable.RawData;
import dev.mcms.shaders.pack.textures.PixelLayouts;
import dev.mcms.shaders.pack.textures.TextureImage;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;

/** Raw texel conversion (pixel formats, pixel types, normalisation, integer targets, the stored internal formats). */
final class TexelCodecTest {

	private static final int RGBA8 = 70, RGBA16F = 115, RGBA32F = 125, R8UI = 13, RGBA8UI = 73, R32F = 55, RGB10A2UI = 91;

	private static float f32(byte[] b, int at) {
		return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).getFloat(at);
	}

	private static float f16(byte[] b, int at) {
		return Float.float16ToFloat(ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).getShort(at));
	}

	@Test
	void normalisedBytesReadAsGlReadsThemIntoAFloatFormat() {
		byte[] file = {(byte) 255, 0, (byte) 128, 64};
		byte[] out = TexelCodec.convertRaw(file, RGBA32F, 1, 1, 1, "RGBA", "UNSIGNED_BYTE");
		assertEquals(16, out.length);
		assertEquals(1f, f32(out, 0), 1e-6f);
		assertEquals(0f, f32(out, 4), 1e-6f);
		assertEquals(128 / 255f, f32(out, 8), 1e-6f);
		assertEquals(64 / 255f, f32(out, 12), 1e-6f);
	}

	@Test
	void aNonReversedFiveFiveFiveOneWordReadsItsFirstChannelInTheHighBits() {
		// Two texels: all channels 31 with alpha 1 (0xFFFF), then red 31 alone (0xF800). A reversed reading gives blue and alpha.
		ByteBuffer packed = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putShort((short) 0xFFFF).putShort((short) 0xF800);
		TextureImage legacy = TexelCodec.rawImage(new RawData(2, 1, 1, "RGB5_A1", "RGBA", "UNSIGNED_SHORT_5_5_5_1", packed.array()));
		assertNotNull(legacy);
		assertEquals(RGBA8, legacy.format(), "5-5-5-1 converts to RGBA8");
		assertArrayEquals(new byte[] {(byte) 255, (byte) 255, (byte) 255, (byte) 255, (byte) 255, 0, 0, 0}, legacy.bytes());
	}

	@Test
	void anRgbSourceIntoAnRgbaTargetGetsAlphaOne() {
		byte[] file = {10, 20, 30};
		byte[] out = TexelCodec.convertRaw(file, RGBA8, 1, 1, 1, "RGB", "UNSIGNED_BYTE");
		assertArrayEquals(new byte[] {10, 20, 30, (byte) 255}, out);
	}

	@Test
	void aSignedByteClampsAtMinusOne() {
		byte[] file = {(byte) -128, 127};
		byte[] out = TexelCodec.convertRaw(file, R32F, 1, 1, 1, "RED", "BYTE");
		// BYTE -128 is below -127/127; GL clamps the normalised value to -1.
		assertEquals(-1f, f32(out, 0), 1e-6f);
	}

	@Test
	void packed565ReadsItsFieldsAsNormalisedChannels() {
		// R=31 (max), G=0, B=0 in 5-6-5, stored little-endian as 0xF800.
		byte[] file = {0x00, (byte) 0xF8};
		byte[] out = TexelCodec.convertRaw(file, RGBA32F, 1, 1, 1, "RGB", "UNSIGNED_SHORT_5_6_5");
		assertEquals(1f, f32(out, 0), 1e-6f);
		assertEquals(0f, f32(out, 4), 1e-6f);
		assertEquals(0f, f32(out, 8), 1e-6f);
		assertEquals(1f, f32(out, 12), 1e-6f, "an RGB source has alpha one");
	}

	@Test
	void revPackedTypesPutTheFirstChannelInTheLowBits() {
		// UNSIGNED_INT_2_10_10_10_REV: R in bits 0-9, G 10-19, B 20-29, A 30-31. R=1023, G=0, B=512, A=3.
		int word = 1023 | (512 << 20) | (3 << 30);
		byte[] file = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(word).array();
		byte[] out = TexelCodec.convertRaw(file, RGBA32F, 1, 1, 1, "RGBA", "UNSIGNED_INT_2_10_10_10_REV");
		assertEquals(1f, f32(out, 0), 1e-6f);
		assertEquals(0f, f32(out, 4), 1e-6f);
		assertEquals(512 / 1023f, f32(out, 8), 1e-6f);
		assertEquals(1f, f32(out, 12), 1e-6f);
	}

	@Test
	void theRevFormOf332PutsRedInTheLowThreeBits() {
		// UNSIGNED_BYTE_2_3_3_REV is 3-3-2 reversed: R in bits 0-2 (3 bits), G in 3-5 (3 bits), B in 6-7 (2 bits).
		byte[] file = {0x01};
		byte[] out = TexelCodec.convertRaw(file, RGBA32F, 1, 1, 1, "RGB", "UNSIGNED_BYTE_2_3_3_REV");
		assertEquals(1 / 7f, f32(out, 0), 1e-6f, "R = 1 of 7");
		assertEquals(0f, f32(out, 4), 1e-6f);
		assertEquals(0f, f32(out, 8), 1e-6f);
		byte[] top = TexelCodec.convertRaw(new byte[] {(byte) 0xC0}, RGBA32F, 1, 1, 1, "RGB", "UNSIGNED_BYTE_2_3_3_REV");
		assertEquals(1f, f32(top, 8), 1e-6f, "B = 3 of 3 (the two top bits)");
	}

	@Test
	void theRevFormOf565AndOf4444AndOf5551PutTheFirstChannelLowest() {
		// 5-6-5 REV: R bits 0-4, G 5-10, B 11-15. Word 0x001F is R = 31 of 31, the rest zero.
		byte[] red = TexelCodec.convertRaw(new byte[] {0x1F, 0x00}, RGBA32F, 1, 1, 1, "RGB", "UNSIGNED_SHORT_5_6_5_REV");
		assertEquals(1f, f32(red, 0), 1e-6f);
		assertEquals(0f, f32(red, 4), 1e-6f);
		// 0xF800 is B = 31 of 31 in the REV form (bits 11-15).
		byte[] blue = TexelCodec.convertRaw(new byte[] {0x00, (byte) 0xF8}, RGBA32F, 1, 1, 1, "RGB", "UNSIGNED_SHORT_5_6_5_REV");
		assertEquals(1f, f32(blue, 8), 1e-6f);
		assertEquals(0f, f32(blue, 0), 1e-6f);
		// 4-4-4-4 REV: R bits 0-3, A bits 12-15. Word 0xF00F is R = 15 of 15 and A = 15 of 15.
		byte[] rgba = TexelCodec.convertRaw(new byte[] {0x0F, (byte) 0xF0}, RGBA32F, 1, 1, 1, "RGBA", "UNSIGNED_SHORT_4_4_4_4_REV");
		assertEquals(1f, f32(rgba, 0), 1e-6f);
		assertEquals(0f, f32(rgba, 4), 1e-6f);
		assertEquals(0f, f32(rgba, 8), 1e-6f);
		// 1-5-5-5 REV: R bits 0-4, A bit 15. Word 0x801F is R = 31 of 31 and A = 1.
		byte[] ra = TexelCodec.convertRaw(new byte[] {0x1F, (byte) 0x80}, RGBA32F, 1, 1, 1, "RGBA", "UNSIGNED_SHORT_1_5_5_5_REV");
		assertEquals(1f, f32(ra, 0), 1e-6f);
		assertEquals(1f, f32(ra, 12), 1e-6f);
	}

	@Test
	void aBgrSourceListsItsChannelsBlueFirst() {
		// BGR bytes B=10, G=20, R=30 come out as R, G, B in the target.
		byte[] out = TexelCodec.convertRaw(new byte[] {10, 20, 30}, RGBA8, 1, 1, 1, "BGR", "UNSIGNED_BYTE");
		assertArrayEquals(new byte[] {30, 20, 10, (byte) 255}, out);
		byte[] bgra = TexelCodec.convertRaw(new byte[] {10, 20, 30, 40}, RGBA8, 1, 1, 1, "BGRA", "UNSIGNED_BYTE");
		assertArrayEquals(new byte[] {30, 20, 10, 40}, bgra);
	}

	@Test
	void aBgraIntegerSourceKeepsItsIntegers() {
		byte[] out = TexelCodec.convertRaw(new byte[] {10, 20, 30, (byte) 200}, RGBA8UI, 1, 1, 1, "BGRA_INTEGER", "UNSIGNED_BYTE");
		assertArrayEquals(new byte[] {30, 20, 10, (byte) 200}, out);
	}

	@Test
	void halfFloatsRoundTripIntoA16FloatFormat() {
		ByteBuffer src = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
		src.putShort(Float.floatToFloat16(0.5f)).putShort(Float.floatToFloat16(-2f)).putShort(Float.floatToFloat16(3f))
			.putShort(Float.floatToFloat16(1f));
		byte[] out = TexelCodec.convertRaw(src.array(), RGBA16F, 1, 1, 1, "RGBA", "HALF_FLOAT");
		assertEquals(8, out.length);
		assertEquals(0.5f, f16(out, 0));
		assertEquals(-2f, f16(out, 2));
	}

	@Test
	void theR11fG11fB10fWordConvertsToRgba16f() {
		// R11F_G11F_B10F has no Metal format the codec stores; it converts to RGBA16F (as RGB9_E5 does).
		ByteBuffer src = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putFloat(1f).putFloat(0.5f).putFloat(0.25f);
		TextureImage image = TexelCodec.rawImage(new RawData(1, 1, 1, "R11F_G11F_B10F", "RGB", "FLOAT", src.array()));
		assertNotNull(image);
		assertEquals(RGBA16F, image.format());
		assertEquals(8, image.bytesPerTexel());
		assertEquals(1f, f16(image.bytes(), 0));
		assertEquals(0.5f, f16(image.bytes(), 2));
		assertEquals(0.25f, f16(image.bytes(), 4));
		assertEquals(1f, f16(image.bytes(), 6), "an RGB source has alpha one");
	}

	@Test
	void theRgb10A2uiWordIsStoredAsItIs() {
		assertEquals(RGB10A2UI, TexelCodec.formatOf("RGB10_A2UI"));
		// R=1023, G=0, B=512, A=3 as integers (RGBA_INTEGER with the REV packed type): the same word comes out.
		int word = 1023 | (512 << 20) | (3 << 30);
		byte[] file = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(word).array();
		TextureImage image = TexelCodec.rawImage(new RawData(1, 1, 1, "RGB10_A2UI", "RGBA_INTEGER", "UNSIGNED_INT_2_10_10_10_REV",
			file));
		assertNotNull(image);
		assertEquals(RGB10A2UI, image.format());
		assertArrayEquals(file, image.bytes());
	}

	@Test
	void anRgb10A2uiTargetTakesOnlyIntegerValues() {
		// The normalised form of the same packed word (RGBA, not integer) cannot feed an integer target.
		assertNull(TexelCodec.convertRaw(new byte[4], RGB10A2UI, 1, 1, 1, "RGBA", "UNSIGNED_INT_2_10_10_10_REV"));
	}

	@Test
	void integerSourceKeepsItsIntegersInAnIntegerTarget() {
		byte[] file = {7, (byte) 200};
		byte[] out = TexelCodec.convertRaw(file, R8UI, 2, 1, 1, "RED_INTEGER", "UNSIGNED_BYTE");
		assertArrayEquals(new byte[] {7, (byte) 200}, out);
	}

	@Test
	void aNormalisedSourceCannotFeedAnIntegerTarget() {
		assertNull(TexelCodec.convertRaw(new byte[4], RGBA8UI, 1, 1, 1, "RGBA", "UNSIGNED_BYTE"));
	}

	@Test
	void anIntegerSourceCannotFeedANormalisedTarget() {
		assertNull(TexelCodec.convertRaw(new byte[4], RGBA8, 1, 1, 1, "RGBA_INTEGER", "UNSIGNED_BYTE"));
	}

	@Test
	void aFileShorterThanItsTexelsIsRefused() {
		assertNull(TexelCodec.convertRaw(new byte[7], RGBA8, 2, 1, 1, "RGBA", "UNSIGNED_BYTE"));
	}

	@Test
	void theFormatWordsAreTheStoredMetalFormats() {
		assertEquals(RGBA32F, TexelCodec.formatOf("RGBA32F"));
		assertEquals(RGBA8, TexelCodec.formatOf("rgba8"));
		assertEquals(RGBA8, TexelCodec.formatOf("RGB8"), "Metal has no 24-bit format: RGB8 is RGBA8 (TargetFormats)");
		assertEquals(-1, TexelCodec.formatOf("NOTAFORMAT"));
		// E6-B: the words Metal has no format for convert as TargetFormats serves them (no refusal).
		assertEquals(RGBA16F, TexelCodec.formatOf("RGB9_E5"), "shared exponent converts to RGBA16F");
		assertEquals(RGBA16F, TexelCodec.formatOf("R11F_G11F_B10F"), "11-11-10 float converts to RGBA16F");
		assertEquals(RGBA8, TexelCodec.formatOf("R3_G3_B2"), "3-3-2 converts to RGBA8");
		assertEquals(RGBA8, TexelCodec.formatOf("RGB5_A1"), "5-5-5-1 converts to RGBA8");
	}

	@Test
	void rawTexelSizesFollowThePairsTheCodecReads() {
		assertEquals(16, PixelLayouts.rawTexelBytes("RGBA", "FLOAT"));
		assertEquals(3, PixelLayouts.rawTexelBytes("RGB", "UNSIGNED_BYTE"));
		assertEquals(2, PixelLayouts.rawTexelBytes("RGB", "UNSIGNED_SHORT_5_6_5"));
		assertEquals(2, PixelLayouts.rawTexelBytes("RGB", "UNSIGNED_SHORT_5_6_5_REV"));
		assertEquals(3, PixelLayouts.rawTexelBytes("BGR", "UNSIGNED_BYTE"));
		assertEquals(1, PixelLayouts.rawTexelBytes("RGB", "UNSIGNED_BYTE_2_3_3_REV"));
		assertEquals(-1, PixelLayouts.rawTexelBytes("RGBA", "UNSIGNED_SHORT_5_6_5"), "565 holds three channels");
		assertEquals(-1, PixelLayouts.rawTexelBytes("RGBA", "NOTATYPE"));
	}

	@Test
	void aRawFileWithAnUnknownInternalFormatBuildsNoTexture() {
		RawData raw = new RawData(1, 1, 1, "NOTAFORMAT", "RGBA", "UNSIGNED_BYTE", new byte[4]);
		assertNull(TexelCodec.rawImage(raw));
		RawData known = new RawData(1, 1, 1, "RGBA8", "RGBA", "UNSIGNED_BYTE", new byte[] {1, 2, 3, 4});
		assertArrayEquals(new byte[] {1, 2, 3, 4}, TexelCodec.rawImage(known).bytes());
	}

	@Test
	void theDefaultNormalIsTheDocumentedTexelAndTheDefaultSpecularIsZero() {
		assertArrayEquals(new byte[] {127, 127, (byte) 255, (byte) 255}, TextureImage.defaultNormal().bytes());
		assertArrayEquals(new byte[4], TextureImage.defaultSpecular().bytes());
	}
}
