// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime;

import java.nio.ByteBuffer;
import org.jspecify.annotations.Nullable;

/**
 * The colour formats the picture dumps read (2026-11-14, E4 debugging): the bytes per texel of each MTLPixelFormat word and
 * the decoding of one texel to four floats. Every colour format that {@code MetalConst} maps is listed, so a stage is not
 * skipped for its format.
 *
 * <p>A channel decodes to the value its format stores: unorm to 0..1, snorm to -1..1 (-128 and below read -1), float as
 * stored, integers as their value. A channel the format lacks reads 0, and alpha without a channel reads 1 (the GL texture
 * rule). Values are not clamped here: {@link DrawDumps} clamps them when it makes the image.
 */
final class DumpTexels {

	/** The MTLPixelFormat words of the colour formats (Metal's MTLPixelFormat header; the numbers MetalConst gives them). */
	static final int R8_UNORM = 10, R8_SNORM = 12, R8_UINT = 13, R8_SINT = 14;
	static final int R16_UNORM = 20, R16_SNORM = 22, R16_UINT = 23, R16_SINT = 24, R16_FLOAT = 25;
	static final int RG8_UNORM = 30, RG8_SNORM = 32, RG8_UINT = 33, RG8_SINT = 34;
	static final int R32_UINT = 53, R32_SINT = 54, R32_FLOAT = 55;
	static final int RG16_UNORM = 60, RG16_SNORM = 62, RG16_UINT = 63, RG16_SINT = 64, RG16_FLOAT = 65;
	static final int RGBA8_UNORM = 70, RGBA8_SNORM = 72, RGBA8_UINT = 73, RGBA8_SINT = 74, BGRA8_UNORM = 80;
	static final int RGB10A2_UNORM = 90, RGB10A2_UINT = 91, RG11B10_FLOAT = 92;
	static final int RG32_UINT = 103, RG32_SINT = 104, RG32_FLOAT = 105;
	static final int RGBA16_UNORM = 110, RGBA16_SNORM = 112, RGBA16_UINT = 113, RGBA16_SINT = 114, RGBA16_FLOAT = 115;
	static final int RGBA32_UINT = 123, RGBA32_SINT = 124, RGBA32_FLOAT = 125;

	/** How a format stores its channels. The PACKED kinds are decoded whole, not channel by channel. */
	enum Kind { UNORM, SNORM, UINT, SINT, FLOAT, PACKED_UNORM, PACKED_UINT, PACKED_RG11B10F }

	/**
	 * One format: its bytes per texel, its channel count, its bits per channel (0 for packed formats), its kind, and whether
	 * its channels are stored blue first.
	 */
	private record Layout(int bytes, int channels, int bits, Kind kind, boolean bgra) {
	}

	/** The layouts by MTLPixelFormat word; null where the dumps read nothing. */
	private static final Layout[] LAYOUTS = new Layout[256];

	static {
		plain(R8_UNORM, 1, 8, Kind.UNORM, false);
		plain(R8_SNORM, 1, 8, Kind.SNORM, false);
		plain(R8_UINT, 1, 8, Kind.UINT, false);
		plain(R8_SINT, 1, 8, Kind.SINT, false);
		plain(R16_UNORM, 1, 16, Kind.UNORM, false);
		plain(R16_SNORM, 1, 16, Kind.SNORM, false);
		plain(R16_UINT, 1, 16, Kind.UINT, false);
		plain(R16_SINT, 1, 16, Kind.SINT, false);
		plain(R16_FLOAT, 1, 16, Kind.FLOAT, false);
		plain(RG8_UNORM, 2, 8, Kind.UNORM, false);
		plain(RG8_SNORM, 2, 8, Kind.SNORM, false);
		plain(RG8_UINT, 2, 8, Kind.UINT, false);
		plain(RG8_SINT, 2, 8, Kind.SINT, false);
		plain(R32_UINT, 1, 32, Kind.UINT, false);
		plain(R32_SINT, 1, 32, Kind.SINT, false);
		plain(R32_FLOAT, 1, 32, Kind.FLOAT, false);
		plain(RG16_UNORM, 2, 16, Kind.UNORM, false);
		plain(RG16_SNORM, 2, 16, Kind.SNORM, false);
		plain(RG16_UINT, 2, 16, Kind.UINT, false);
		plain(RG16_SINT, 2, 16, Kind.SINT, false);
		plain(RG16_FLOAT, 2, 16, Kind.FLOAT, false);
		plain(RGBA8_UNORM, 4, 8, Kind.UNORM, false);
		plain(RGBA8_SNORM, 4, 8, Kind.SNORM, false);
		plain(RGBA8_UINT, 4, 8, Kind.UINT, false);
		plain(RGBA8_SINT, 4, 8, Kind.SINT, false);
		plain(BGRA8_UNORM, 4, 8, Kind.UNORM, true);
		packed(RGB10A2_UNORM, Kind.PACKED_UNORM);
		packed(RGB10A2_UINT, Kind.PACKED_UINT);
		packed(RG11B10_FLOAT, Kind.PACKED_RG11B10F);
		plain(RG32_UINT, 2, 32, Kind.UINT, false);
		plain(RG32_SINT, 2, 32, Kind.SINT, false);
		plain(RG32_FLOAT, 2, 32, Kind.FLOAT, false);
		plain(RGBA16_UNORM, 4, 16, Kind.UNORM, false);
		plain(RGBA16_SNORM, 4, 16, Kind.SNORM, false);
		plain(RGBA16_UINT, 4, 16, Kind.UINT, false);
		plain(RGBA16_SINT, 4, 16, Kind.SINT, false);
		plain(RGBA16_FLOAT, 4, 16, Kind.FLOAT, false);
		plain(RGBA32_UINT, 4, 32, Kind.UINT, false);
		plain(RGBA32_SINT, 4, 32, Kind.SINT, false);
		plain(RGBA32_FLOAT, 4, 32, Kind.FLOAT, false);
	}

	private DumpTexels() {
	}

	private static void plain(int word, int channels, int bits, Kind kind, boolean bgra) {
		LAYOUTS[word] = new Layout(channels * bits / 8, channels, bits, kind, bgra);
	}

	/** A packed format: four fields in one 32-bit word. */
	private static void packed(int word, Kind kind) {
		LAYOUTS[word] = new Layout(4, 4, 0, kind, false);
	}

	private static @Nullable Layout layoutOf(int format) {
		return format >= 0 && format < LAYOUTS.length ? LAYOUTS[format] : null;
	}

	/** Bytes per texel of a colour format the dumps read; 0 for any other format (depth formats included). */
	static int bytesPerTexel(int format) {
		Layout layout = layoutOf(format);
		return layout == null ? 0 : layout.bytes();
	}

	/**
	 * Decodes the texel at byte {@code at} of {@code bytes} (little-endian) into {@code rgba[0..3]} (red, green, blue, alpha).
	 * The format must have a layout ({@link #bytesPerTexel} is positive for it). Allocates nothing.
	 */
	static void decode(ByteBuffer bytes, int at, int format, float[] rgba) {
		Layout layout = layoutOf(format);
		if (layout == null) {
			throw new IllegalArgumentException("no colour layout for format " + format);
		}
		switch (layout.kind()) {
			case PACKED_UNORM -> {
				int v = bytes.getInt(at);
				rgba[0] = (v & 0x3ff) / 1023f;
				rgba[1] = ((v >>> 10) & 0x3ff) / 1023f;
				rgba[2] = ((v >>> 20) & 0x3ff) / 1023f;
				rgba[3] = ((v >>> 30) & 0x3) / 3f;
			}
			case PACKED_UINT -> {
				int v = bytes.getInt(at);
				rgba[0] = v & 0x3ff;
				rgba[1] = (v >>> 10) & 0x3ff;
				rgba[2] = (v >>> 20) & 0x3ff;
				rgba[3] = (v >>> 30) & 0x3;
			}
			case PACKED_RG11B10F -> {
				int v = bytes.getInt(at);
				rgba[0] = smallFloat(v & 0x7ff, 6);
				rgba[1] = smallFloat((v >>> 11) & 0x7ff, 6);
				rgba[2] = smallFloat((v >>> 22) & 0x3ff, 5);
				rgba[3] = 1f;
			}
			default -> {
				int channelBytes = layout.bits() / 8;
				for (int c = 0; c < 4; c++) {
					rgba[c] = c < layout.channels() ? channel(bytes, at + c * channelBytes, layout)
						: c == 3 ? 1f : 0f;
				}
				if (layout.bgra()) {
					float blue = rgba[0];
					rgba[0] = rgba[2];
					rgba[2] = blue;
				}
			}
		}
	}

	private static float channel(ByteBuffer bytes, int at, Layout layout) {
		int bits = layout.bits();
		return switch (layout.kind()) {
			case UNORM -> (float) unsigned(bytes, at, bits) / (float) ((1L << bits) - 1);
			case SNORM -> Math.max(-1f, (float) signed(bytes, at, bits) / (float) ((1L << (bits - 1)) - 1));
			case UINT -> (float) unsigned(bytes, at, bits);
			case SINT -> signed(bytes, at, bits);
			case FLOAT -> bits == 16 ? Float.float16ToFloat(bytes.getShort(at)) : bytes.getFloat(at);
			default -> throw new IllegalStateException("packed kind " + layout.kind() + " is decoded whole");
		};
	}

	private static long unsigned(ByteBuffer bytes, int at, int bits) {
		return switch (bits) {
			case 8 -> bytes.get(at) & 0xffL;
			case 16 -> bytes.getShort(at) & 0xffffL;
			default -> bytes.getInt(at) & 0xffffffffL;
		};
	}

	private static int signed(ByteBuffer bytes, int at, int bits) {
		return switch (bits) {
			case 8 -> bytes.get(at);
			case 16 -> bytes.getShort(at);
			default -> bytes.getInt(at);
		};
	}

	/**
	 * An unsigned float with five exponent bits and {@code mantissaBits} mantissa bits (the 11- and 10-bit fields of
	 * RG11B10Float), bias 15. Exponent 31 is infinity (no mantissa) or NaN.
	 */
	private static float smallFloat(int bits, int mantissaBits) {
		int exponent = bits >>> mantissaBits;
		int mantissa = bits & ((1 << mantissaBits) - 1);
		if (exponent == 31) {
			return mantissa == 0 ? Float.POSITIVE_INFINITY : Float.NaN;
		}
		if (exponent == 0) {
			return Math.scalb((float) mantissa, -14 - mantissaBits);
		}
		return Math.scalb(1f + mantissa / (float) (1 << mantissaBits), exponent - 15);
	}
}
