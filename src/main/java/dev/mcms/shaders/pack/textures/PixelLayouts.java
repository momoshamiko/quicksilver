// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.pack.textures;

import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The pixel formats and pixel types a raw custom texture names (OptiFine shaders.txt "Pixel Formats" and "Pixel Types"; Iris's
 * image format page, "Additional pixel types"): how many channels a pixel format has, how many bytes one texel takes in the
 * file, and how each channel reads. Pure arithmetic: the conversion to a Metal format is the runtime's (TexelCodec).
 *
 * <p>A packed type's {@code bits} are listed in the order of the format's channels; a {@code _REV} type puts channel 0 in the
 * least significant bits (the type's name gives the reversed widths, as GL names them: {@code UNSIGNED_BYTE_2_3_3_REV} is
 * {@code 3, 3, 2} reversed).
 */
public final class PixelLayouts {

	/** How one scalar pixel type reads a channel. {@code NONE} marks a packed type (several channels in one word). */
	public enum Scalar { UBYTE, BYTE, USHORT, SHORT, UINT, INT, FLOAT, HALF, NONE }

	/**
	 * A pixel type: the channel widths in bits (most significant first, unless reversed), the bytes of one word and the scalar.
	 */
	public record PixelType(int[] bits, boolean reversed, int bytes, Scalar scalar) {
	}

	private static final Map<String, PixelType> PIXEL_TYPES = Map.ofEntries(
		Map.entry("UNSIGNED_BYTE", new PixelType(new int[] {8}, false, 1, Scalar.UBYTE)),
		Map.entry("BYTE", new PixelType(new int[] {8}, false, 1, Scalar.BYTE)),
		Map.entry("UNSIGNED_SHORT", new PixelType(new int[] {16}, false, 2, Scalar.USHORT)),
		Map.entry("SHORT", new PixelType(new int[] {16}, false, 2, Scalar.SHORT)),
		Map.entry("UNSIGNED_INT", new PixelType(new int[] {32}, false, 4, Scalar.UINT)),
		Map.entry("INT", new PixelType(new int[] {32}, false, 4, Scalar.INT)),
		Map.entry("FLOAT", new PixelType(new int[] {32}, false, 4, Scalar.FLOAT)),
		Map.entry("HALF_FLOAT", new PixelType(new int[] {16}, false, 2, Scalar.HALF)),
		Map.entry("UNSIGNED_BYTE_3_3_2", new PixelType(new int[] {3, 3, 2}, false, 1, Scalar.NONE)),
		Map.entry("UNSIGNED_BYTE_2_3_3_REV", new PixelType(new int[] {3, 3, 2}, true, 1, Scalar.NONE)),
		Map.entry("UNSIGNED_SHORT_5_6_5", new PixelType(new int[] {5, 6, 5}, false, 2, Scalar.NONE)),
		Map.entry("UNSIGNED_SHORT_5_6_5_REV", new PixelType(new int[] {5, 6, 5}, true, 2, Scalar.NONE)),
		Map.entry("UNSIGNED_SHORT_4_4_4_4", new PixelType(new int[] {4, 4, 4, 4}, false, 2, Scalar.NONE)),
		Map.entry("UNSIGNED_SHORT_4_4_4_4_REV", new PixelType(new int[] {4, 4, 4, 4}, true, 2, Scalar.NONE)),
		Map.entry("UNSIGNED_SHORT_5_5_5_1", new PixelType(new int[] {5, 5, 5, 1}, false, 2, Scalar.NONE)),
		Map.entry("UNSIGNED_SHORT_1_5_5_5_REV", new PixelType(new int[] {5, 5, 5, 1}, true, 2, Scalar.NONE)),
		Map.entry("UNSIGNED_INT_8_8_8_8", new PixelType(new int[] {8, 8, 8, 8}, false, 4, Scalar.NONE)),
		Map.entry("UNSIGNED_INT_8_8_8_8_REV", new PixelType(new int[] {8, 8, 8, 8}, true, 4, Scalar.NONE)),
		Map.entry("UNSIGNED_INT_10_10_10_2", new PixelType(new int[] {10, 10, 10, 2}, false, 4, Scalar.NONE)),
		Map.entry("UNSIGNED_INT_2_10_10_10_REV", new PixelType(new int[] {10, 10, 10, 2}, true, 4, Scalar.NONE)));

	private PixelLayouts() {
	}

	/** The pixel type a word names, or null. */
	public static @Nullable PixelType pixelType(String word) {
		return PIXEL_TYPES.get(word.trim().toUpperCase(Locale.ROOT));
	}

	/** The channel count a pixel format word names (RED 1 ... RGBA 4, BGR and BGRA alike, integer forms alike), or -1. */
	public static int channelsOf(String word) {
		return switch (word.trim().toUpperCase(Locale.ROOT)) {
			case "RED", "RED_INTEGER" -> 1;
			case "RG", "RG_INTEGER" -> 2;
			case "RGB", "RGB_INTEGER", "BGR", "BGR_INTEGER" -> 3;
			case "RGBA", "RGBA_INTEGER", "BGRA", "BGRA_INTEGER" -> 4;
			default -> -1;
		};
	}

	/** Whether a pixel format word lists its channels blue first ({@code BGR}, {@code BGRA} and their integer forms). */
	public static boolean blueFirst(String word) {
		return word.trim().toUpperCase(Locale.ROOT).startsWith("BGR");
	}

	/** Whether a pixel format word names integer values ({@code ..._INTEGER}). */
	public static boolean integerPixels(String word) {
		return word.trim().toUpperCase(Locale.ROOT).endsWith("_INTEGER");
	}

	/** Whether a pixel type packs several channels in one word. */
	public static boolean packed(String pixelType) {
		PixelType t = pixelType(pixelType);
		return t != null && t.scalar() == Scalar.NONE;
	}

	/**
	 * Whether a pixel type is a scalar integer type (read as integer channel values, neither normalised nor floating). A packed
	 * word is not one: {@link #integerValues} counts it too.
	 */
	public static boolean integerScalar(String pixelType) {
		PixelType t = pixelType(pixelType);
		return t != null && switch (t.scalar()) {
			case UBYTE, BYTE, USHORT, SHORT, UINT, INT -> true;
			default -> false;
		};
	}

	/**
	 * Whether a pixel format and type read integer channel values: an {@code _INTEGER} format with an integer scalar type or a
	 * packed word (the RGB10_A2UI route). Such values are never normalised.
	 */
	public static boolean integerValues(String pixelFormat, String pixelType) {
		return integerPixels(pixelFormat) && (integerScalar(pixelType) || packed(pixelType));
	}

	/**
	 * Bytes one raw texel takes in the file for a pixel format and type, or -1 when the pair is not one the layouts read. A
	 * packed type holds one word per texel and needs the matching channel count.
	 */
	public static int rawTexelBytes(String pixelFormat, String pixelType) {
		PixelType t = pixelType(pixelType);
		int channels = channelsOf(pixelFormat);
		if (t == null || channels < 0) {
			return -1;
		}
		if (t.scalar() == Scalar.NONE) {
			return t.bits().length == channels ? t.bytes() : -1;
		}
		return channels * t.bytes();
	}
}
