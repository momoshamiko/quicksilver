// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.textures;

import dev.mcms.shaders.pack.textures.CustomTextureTable.RawData;
import dev.mcms.shaders.pack.textures.PixelLayouts;
import dev.mcms.shaders.pack.textures.PixelLayouts.PixelType;
import dev.mcms.shaders.pack.textures.PixelLayouts.Scalar;
import dev.mcms.shaders.pack.textures.TextureImage;
import dev.mcms.shaders.runtime.TargetFormats;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Converts raw custom texture bytes (OptiFine shaders.txt "Pixel Formats" and "Pixel Types", Iris's image format page) into
 * the Metal layout of an internal format. A value of a normalised source type (UNSIGNED_BYTE, SHORT, ...) is read as GL reads
 * it: divided by the type's maximum, signed types clamped to -1. A value of an integer source format ({@code RED_INTEGER},
 * ...), or of a packed word read through an integer format, keeps its integer value and fills an integer target. A
 * normalised source into an integer target, or an integer source into a normalised one, is refused: the documents name no
 * conversion for either. A {@code BGR}/{@code BGRA} format lists its channels blue first; the codec puts them in R, G, B order.
 *
 * <p>Metal has no 24-bit formats, so an RGB internal format is stored as its RGBA counterpart with alpha one (the
 * {@link TargetFormats} rule). The pixel layout arithmetic is {@link PixelLayouts}'s.
 */
public final class TexelCodec {

	/** How one Metal format stores a channel. */
	enum Kind { UNORM8, SNORM8, UNORM16, SNORM16, UNORM1010102, UINT1010102, FLOAT16, FLOAT32, UINT8, SINT8, UINT16, SINT16, UINT32, SINT32 }

	/** A Metal format this codec stores: its kind, channels written and bytes per texel. */
	record Layout(Kind kind, int components, int bytes) {
		boolean integer() {
			return switch (kind) {
				case UINT1010102, UINT8, SINT8, UINT16, SINT16, UINT32, SINT32 -> true;
				default -> false;
			};
		}
	}

	/** MTLPixelFormatRG11B10Float: the R11F_G11F_B10F words name it; the codec stores it as RGBA16F (see {@link #formatOf}). */
	private static final int RG11B10_FLOAT = 92;
	/** MTLPixelFormatRGBA16Float: the codec's form of R11F_G11F_B10F and RGB9_E5 (Metal has no shared-exponent format there). */
	private static final int RGBA16_FLOAT = 115;

	/** The Metal pixel formats the codec stores (raw MTLPixelFormat values, as TargetFormats uses). */
	private static final Map<Integer, Layout> LAYOUTS = Map.ofEntries(
		Map.entry(10, new Layout(Kind.UNORM8, 1, 1)), Map.entry(12, new Layout(Kind.SNORM8, 1, 1)),
		Map.entry(13, new Layout(Kind.UINT8, 1, 1)), Map.entry(14, new Layout(Kind.SINT8, 1, 1)),
		Map.entry(30, new Layout(Kind.UNORM8, 2, 2)), Map.entry(32, new Layout(Kind.SNORM8, 2, 2)),
		Map.entry(33, new Layout(Kind.UINT8, 2, 2)), Map.entry(34, new Layout(Kind.SINT8, 2, 2)),
		Map.entry(70, new Layout(Kind.UNORM8, 4, 4)), Map.entry(72, new Layout(Kind.SNORM8, 4, 4)),
		Map.entry(73, new Layout(Kind.UINT8, 4, 4)), Map.entry(74, new Layout(Kind.SINT8, 4, 4)),
		Map.entry(20, new Layout(Kind.UNORM16, 1, 2)), Map.entry(22, new Layout(Kind.SNORM16, 1, 2)),
		Map.entry(23, new Layout(Kind.UINT16, 1, 2)), Map.entry(24, new Layout(Kind.SINT16, 1, 2)),
		Map.entry(25, new Layout(Kind.FLOAT16, 1, 2)),
		Map.entry(60, new Layout(Kind.UNORM16, 2, 4)), Map.entry(62, new Layout(Kind.SNORM16, 2, 4)),
		Map.entry(63, new Layout(Kind.UINT16, 2, 4)), Map.entry(64, new Layout(Kind.SINT16, 2, 4)),
		Map.entry(65, new Layout(Kind.FLOAT16, 2, 4)),
		Map.entry(110, new Layout(Kind.UNORM16, 4, 8)), Map.entry(112, new Layout(Kind.SNORM16, 4, 8)),
		Map.entry(113, new Layout(Kind.UINT16, 4, 8)), Map.entry(114, new Layout(Kind.SINT16, 4, 8)),
		Map.entry(RGBA16_FLOAT, new Layout(Kind.FLOAT16, 4, 8)),
		Map.entry(53, new Layout(Kind.UINT32, 1, 4)), Map.entry(54, new Layout(Kind.SINT32, 1, 4)),
		Map.entry(55, new Layout(Kind.FLOAT32, 1, 4)),
		Map.entry(103, new Layout(Kind.UINT32, 2, 8)), Map.entry(104, new Layout(Kind.SINT32, 2, 8)),
		Map.entry(105, new Layout(Kind.FLOAT32, 2, 8)),
		Map.entry(123, new Layout(Kind.UINT32, 4, 16)), Map.entry(124, new Layout(Kind.SINT32, 4, 16)),
		Map.entry(125, new Layout(Kind.FLOAT32, 4, 16)),
		Map.entry(90, new Layout(Kind.UNORM1010102, 4, 4)),
		Map.entry(91, new Layout(Kind.UINT1010102, 4, 4)));

	private TexelCodec() {
	}

	/** The layout of a Metal format, or null when the codec does not store it. */
	static @Nullable Layout layout(int format) {
		return LAYOUTS.get(format);
	}

	/**
	 * The Metal format an internal format word names (the pack's {@code <internalFormat>} of a raw texture), or -1 when the
	 * word is not an image format the codec stores. The words Metal has no format for are converted as TargetFormats
	 * serves them: the 24-bit RGB words and the packed legacy words (R3_G3_B2, RGB5_A1) to RGBA8, RGB9_E5 and R11F_G11F_B10F to
	 * RGBA16F. RGB10_A2 and RGB10_A2UI keep their Metal formats (normalised and integer).
	 */
	public static int formatOf(String word) {
		String key = word.trim().toUpperCase(Locale.ROOT);
		if (!TargetFormats.known(key)) {
			return -1;
		}
		int format = TargetFormats.of(key, -1).format();
		if (format == RG11B10_FLOAT) {
			format = RGBA16_FLOAT;
		}
		return LAYOUTS.containsKey(format) ? format : -1;
	}

	/**
	 * The texture a raw pack file describes: its internal format converted from the file's bytes, with the type its
	 * dimensions name (a 3D volume or a 2D texture; a 1D texture is 2D of height 1, and a rectangle is 2D). Null when the
	 * internal format is not one the codec stores or the bytes do not convert.
	 */
	public static @Nullable TextureImage rawImage(RawData raw) {
		int format = formatOf(raw.internalFormat());
		Layout layout = format < 0 ? null : layout(format);
		if (layout == null) {
			return null;
		}
		byte[] texels = convertRaw(raw.bytes(), format, raw.width(), raw.height(), raw.depth(), raw.pixelFormat(),
			raw.pixelType());
		if (texels == null) {
			return null;
		}
		int type = raw.depth() > 1 ? TextureImage.TYPE_3D : TextureImage.TYPE_2D;
		return new TextureImage(format, type, raw.width(), raw.height(), raw.depth(), layout.bytes(), texels);
	}

	/**
	 * Decodes one raw texel into four doubles, in the format's channel order R, G, B, A (missing channels: zero, alpha one).
	 * Normalised as GL reads a pixel type, unless the pixel format and type are integer ones (then the integer values).
	 */
	static void decodeRaw(byte[] src, int at, String pixelFormat, String pixelType, double[] out) {
		PixelType t = PixelLayouts.pixelType(pixelType);
		int channels = PixelLayouts.channelsOf(pixelFormat);
		ByteBuffer in = ByteBuffer.wrap(src, at, src.length - at).order(ByteOrder.LITTLE_ENDIAN);
		out[0] = 0;
		out[1] = 0;
		out[2] = 0;
		out[3] = 1;
		boolean integer = PixelLayouts.integerPixels(pixelFormat);
		if (t.scalar() == Scalar.NONE) {
			long word = switch (t.bytes()) {
				case 1 -> in.get() & 0xffL;
				case 2 -> in.getShort() & 0xffffL;
				default -> in.getInt() & 0xffffffffL;
			};
			decodePacked(word, t, integer, out);
		} else {
			for (int c = 0; c < channels; c++) {
				out[c] = switch (t.scalar()) {
					case UBYTE -> integer ? in.get() & 0xff : (in.get() & 0xff) / 255.0;
					case BYTE -> integer ? in.get() : Math.max(in.get() / 127.0, -1.0);
					case USHORT -> integer ? in.getShort() & 0xffff : (in.getShort() & 0xffff) / 65535.0;
					case SHORT -> integer ? in.getShort() : Math.max(in.getShort() / 32767.0, -1.0);
					case UINT -> integer ? in.getInt() & 0xffffffffL : (in.getInt() & 0xffffffffL) / 4294967295.0;
					case INT -> integer ? in.getInt() : Math.max(in.getInt() / 2147483647.0, -1.0);
					case FLOAT -> in.getFloat();
					case HALF -> Float.float16ToFloat(in.getShort());
					case NONE -> 0;
				};
			}
		}
		if (PixelLayouts.blueFirst(pixelFormat)) {
			double blue = out[0];
			out[0] = out[2];
			out[2] = blue;
		}
	}

	/**
	 * Splits a packed word into its channels, in the format's order. Channel 0 (R) is the most significant field unless the
	 * type is a REV form, where it is the least significant (the GL pixel type names say so). A three-channel packed type
	 * leaves alpha at one. An integer pixel format keeps each field's integer value; otherwise each field is normalised to [0, 1].
	 */
	private static void decodePacked(long word, PixelType t, boolean integer, double[] out) {
		int[] bits = t.bits();
		for (int i = 0; i < bits.length && i < 4; i++) {
			int low = 0;
			for (int k = 0; k < bits.length; k++) {
				if (t.reversed() ? k < i : k > i) {
					low += bits[k];
				}
			}
			long mask = (1L << bits[i]) - 1;
			long field = (word >>> low) & mask;
			out[i] = integer ? field : field / (double) mask;
		}
	}

	/**
	 * Encodes one texel of the four channel values into a Metal format of {@code layout}, writing its bytes at {@code at}.
	 * Integer targets take the rounded value; normalised targets clamp to their range first.
	 */
	static void encode(double[] v, Layout layout, ByteBuffer out, int at) {
		int n = layout.components();
		if (layout.kind() == Kind.UNORM1010102) {
			long r = Math.round(clamp01(v[0]) * 1023), g = Math.round(clamp01(v[1]) * 1023);
			long b = Math.round(clamp01(v[2]) * 1023), a = Math.round(clamp01(v[3]) * 3);
			out.putInt(at, (int) (r | (g << 10) | (b << 20) | (a << 30)));
			return;
		}
		if (layout.kind() == Kind.UINT1010102) {
			long r = clampLong(Math.round(v[0]), 0, 1023), g = clampLong(Math.round(v[1]), 0, 1023);
			long b = clampLong(Math.round(v[2]), 0, 1023), a = clampLong(Math.round(v[3]), 0, 3);
			out.putInt(at, (int) (r | (g << 10) | (b << 20) | (a << 30)));
			return;
		}
		for (int c = 0; c < n; c++) {
			int offset = at + c * bytesOf(layout.kind());
			double x = v[c];
			switch (layout.kind()) {
				case UNORM8 -> out.put(offset, (byte) Math.round(clamp01(x) * 255));
				case SNORM8 -> out.put(offset, (byte) Math.round(clamp(x, -1, 1) * 127));
				case UNORM16 -> out.putShort(offset, (short) Math.round(clamp01(x) * 65535));
				case SNORM16 -> out.putShort(offset, (short) Math.round(clamp(x, -1, 1) * 32767));
				case FLOAT16 -> out.putShort(offset, Float.floatToFloat16((float) x));
				case FLOAT32 -> out.putFloat(offset, (float) x);
				case UINT8 -> out.put(offset, (byte) clampLong(Math.round(x), 0, 255));
				case SINT8 -> out.put(offset, (byte) clampLong(Math.round(x), -128, 127));
				case UINT16 -> out.putShort(offset, (short) clampLong(Math.round(x), 0, 65535));
				case SINT16 -> out.putShort(offset, (short) clampLong(Math.round(x), -32768, 32767));
				case UINT32 -> out.putInt(offset, (int) clampLong(Math.round(x), 0, 0xffffffffL));
				case SINT32 -> out.putInt(offset, (int) clampLong(Math.round(x), Integer.MIN_VALUE, Integer.MAX_VALUE));
				case UNORM1010102, UINT1010102 -> throw new IllegalStateException("packed above");
			}
		}
	}

	/** Bytes one channel of a kind takes (a packed kind takes its whole word). */
	static int bytesOf(Kind kind) {
		return switch (kind) {
			case UNORM8, SNORM8, UINT8, SINT8 -> 1;
			case UNORM16, SNORM16, FLOAT16, UINT16, SINT16 -> 2;
			case UNORM1010102, UINT1010102, FLOAT32, UINT32, SINT32 -> 4;
		};
	}

	/**
	 * Converts a raw file into the bytes of a Metal format: width x height x depth texels, row-major. Returns null when the
	 * file is shorter than the texels it must hold, when the codec does not store the format, or when the source values and
	 * the target disagree on integer or normalised.
	 */
	static byte @Nullable [] convertRaw(byte[] file, int format, int width, int height, int depth, String pixelFormat,
			String pixelType) {
		Layout layout = layout(format);
		int texelBytes = PixelLayouts.rawTexelBytes(pixelFormat, pixelType);
		if (layout == null || texelBytes < 0 || width < 1 || height < 1 || depth < 1) {
			return null;
		}
		long texels = (long) width * height * depth;
		if (file.length < texels * texelBytes) {
			return null;
		}
		boolean integerValues = PixelLayouts.integerValues(pixelFormat, pixelType);
		if (layout.integer() != integerValues) {
			return null;
		}
		byte[] out = new byte[(int) (texels * layout.bytes())];
		ByteBuffer dst = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN);
		double[] v = new double[4];
		for (long i = 0; i < texels; i++) {
			decodeRaw(file, (int) (i * texelBytes), pixelFormat, pixelType, v);
			encode(v, layout, dst, (int) (i * layout.bytes()));
		}
		return out;
	}

	private static double clamp01(double x) {
		return clamp(x, 0, 1);
	}

	private static double clamp(double x, double lo, double hi) {
		return Double.isNaN(x) ? 0 : Math.max(lo, Math.min(hi, x));
	}

	private static long clampLong(long x, long lo, long hi) {
		return Math.max(lo, Math.min(hi, x));
	}
}
