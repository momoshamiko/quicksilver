// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime;

import dev.mcms.shaders.pack.textures.InternalFormats;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The pack's {@code <target>Format} words (engine-pack-format.md section 7; OptiFine doc shaders.txt "Texture Formats")
 * as raw MTLPixelFormat values (the numbers Metal.framework Headers/MTLPixelFormat.h gives; the same table the backend's
 * MetalConst uses for renderpearl's GpuFormat). Unknown or unsupported words fall back to RGBA8Unorm with one WARN per
 * word, so a pack with an exotic format still loads; the fallbacks are recorded in {@link #fallback()}.
 *
 * <p>Metal has no 24-bit formats on Apple GPUs: the three-component words (RGB8, RGB16, RGB32) and the packed legacy
 * words (R3_G3_B2, RGB5_A1, RGB9_E5) map to the four-component form of the same precision, and the alpha channel the
 * pack never writes reads as 1. RGB10_A2 and R11F_G11F_B10F exist as such.
 *
 * @param format     the raw MTLPixelFormat
 * @param components the channels the format stores (readbacks; integer outputs, see {@link #outputComponents})
 * @param bytes      bytes per texel (readbacks)
 */
public record TargetFormats(int format, int components, int bytes) {

	/** RGBA8Unorm, the documented default of every target without a Format constant. */
	public static final TargetFormats RGBA8 = new TargetFormats(70, 4, 4);
	/**
	 * MTLPixelFormatDepth32Float (252), the engine's depth targets (plan.md E4, the depth bullet): {@code depthtex0},
	 * the live depth the redirected world passes write, and the {@code depthtex1}/{@code depthtex2} copies. Not a pack
	 * format word — the depth targets' format is the engine's own (the documented {@code depthtex*} semantics carry no
	 * format choice; engine-pack-format.md section 7).
	 */
	public static final int DEPTH32_FLOAT = 252;

	/** The word as the pack wrote it when this format is a fallback for an unsupported one, or null. */
	public @Nullable String fallback() {
		return null;
	}

	/** Whether the format holds integers (the {@code I}/{@code UI} words and RGB10_A2UI): no blending, no conversion. */
	public boolean integer() {
		return switch (this.format) {
			case 13, 14, 23, 24, 33, 34, 53, 54, 63, 64, 73, 74, 91, 103, 104, 113, 114, 123, 124 -> true;
			default -> false;
		};
	}

	/**
	 * The components a fragment output writes for this format (ColorTarget.components): four for every float and
	 * normalised format, whatever channels it stores, because GL blends with the shader's alpha even when the target has
	 * no alpha channel, and Metal takes the blend's source alpha from the output's fourth component (a {@code float3}
	 * output into R11F_G11F_B10F left it undefined: NaN water and clouds in Complementary, E5). Integer formats keep
	 * their own count: they never blend.
	 */
	public int outputComponents() {
		return integer() ? this.components : 4;
	}

	private static final Map<String, TargetFormats> FORMATS = Map.ofEntries(
		Map.entry("R8", new TargetFormats(10, 1, 1)),
		Map.entry("R8_SNORM", new TargetFormats(12, 1, 1)),
		Map.entry("R8I", new TargetFormats(14, 1, 1)),
		Map.entry("R8UI", new TargetFormats(13, 1, 1)),
		Map.entry("RG8", new TargetFormats(30, 2, 2)),
		Map.entry("RG8_SNORM", new TargetFormats(32, 2, 2)),
		Map.entry("RG8I", new TargetFormats(34, 2, 2)),
		Map.entry("RG8UI", new TargetFormats(33, 2, 2)),
		Map.entry("RGB8", new TargetFormats(70, 4, 4)),
		Map.entry("RGB8_SNORM", new TargetFormats(72, 4, 4)),
		Map.entry("RGB8I", new TargetFormats(74, 4, 4)),
		Map.entry("RGB8UI", new TargetFormats(73, 4, 4)),
		Map.entry("RGBA8", RGBA8),
		Map.entry("RGBA8_SNORM", new TargetFormats(72, 4, 4)),
		Map.entry("RGBA8I", new TargetFormats(74, 4, 4)),
		Map.entry("RGBA8UI", new TargetFormats(73, 4, 4)),
		Map.entry("R16", new TargetFormats(20, 1, 2)),
		Map.entry("R16_SNORM", new TargetFormats(22, 1, 2)),
		Map.entry("R16F", new TargetFormats(25, 1, 2)),
		Map.entry("R16I", new TargetFormats(24, 1, 2)),
		Map.entry("R16UI", new TargetFormats(23, 1, 2)),
		Map.entry("RG16", new TargetFormats(60, 2, 4)),
		Map.entry("RG16_SNORM", new TargetFormats(62, 2, 4)),
		Map.entry("RG16F", new TargetFormats(65, 2, 4)),
		Map.entry("RG16I", new TargetFormats(64, 2, 4)),
		Map.entry("RG16UI", new TargetFormats(63, 2, 4)),
		Map.entry("RGB16", new TargetFormats(110, 4, 8)),
		Map.entry("RGB16_SNORM", new TargetFormats(112, 4, 8)),
		Map.entry("RGB16F", new TargetFormats(115, 4, 8)),
		Map.entry("RGB16I", new TargetFormats(114, 4, 8)),
		Map.entry("RGB16UI", new TargetFormats(113, 4, 8)),
		Map.entry("RGBA16", new TargetFormats(110, 4, 8)),
		Map.entry("RGBA16_SNORM", new TargetFormats(112, 4, 8)),
		Map.entry("RGBA16F", new TargetFormats(115, 4, 8)),
		Map.entry("RGBA16I", new TargetFormats(114, 4, 8)),
		Map.entry("RGBA16UI", new TargetFormats(113, 4, 8)),
		Map.entry("R32F", new TargetFormats(55, 1, 4)),
		Map.entry("R32I", new TargetFormats(54, 1, 4)),
		Map.entry("R32UI", new TargetFormats(53, 1, 4)),
		Map.entry("RG32F", new TargetFormats(105, 2, 8)),
		Map.entry("RG32I", new TargetFormats(104, 2, 8)),
		Map.entry("RG32UI", new TargetFormats(103, 2, 8)),
		Map.entry("RGB32F", new TargetFormats(125, 4, 16)),
		Map.entry("RGB32I", new TargetFormats(124, 4, 16)),
		Map.entry("RGB32UI", new TargetFormats(123, 4, 16)),
		Map.entry("RGBA32F", new TargetFormats(125, 4, 16)),
		Map.entry("RGBA32I", new TargetFormats(124, 4, 16)),
		Map.entry("RGBA32UI", new TargetFormats(123, 4, 16)),
		Map.entry("R3_G3_B2", new TargetFormats(70, 4, 4)),
		Map.entry("RGB5_A1", new TargetFormats(70, 4, 4)),
		Map.entry("RGB10_A2", new TargetFormats(90, 4, 4)),
		Map.entry("RGB10_A2UI", new TargetFormats(91, 4, 4)),
		Map.entry("R11F_G11F_B10F", new TargetFormats(92, 3, 4)),
		Map.entry("RGB9_E5", new TargetFormats(115, 4, 8)));

	private static final Map<String, String> FALLBACKS = Map.ofEntries(
				Map.entry("R3_G3_B2", "RGBA8 (Metal has no 3-3-2 format)"),
		Map.entry("RGB5_A1", "RGBA8 (Metal has no 5-5-5-1 format)"),
		Map.entry("RGB9_E5", "RGBA16F (Metal has no shared-exponent format)"),
		Map.entry("RGB8", "RGBA8 (Metal has no 24-bit formats)"),
		Map.entry("RGB8_SNORM", "RGBA8_SNORM (Metal has no 24-bit formats)"),
		Map.entry("RGB8I", "RGBA8I (Metal has no 24-bit formats)"),
		Map.entry("RGB8UI", "RGBA8UI (Metal has no 24-bit formats)"),
		Map.entry("RGB16", "RGBA16 (Metal has no 24-bit formats)"),
		Map.entry("RGB16_SNORM", "RGBA16_SNORM (Metal has no 24-bit formats)"),
		Map.entry("RGB16F", "RGBA16F (Metal has no 24-bit formats)"),
		Map.entry("RGB16I", "RGBA16I (Metal has no 24-bit formats)"),
		Map.entry("RGB16UI", "RGBA16UI (Metal has no 24-bit formats)"),
		Map.entry("RGB32F", "RGBA32F (Metal has no 24-bit formats)"),
		Map.entry("RGB32I", "RGBA32I (Metal has no 24-bit formats)"),
		Map.entry("RGB32UI", "RGBA32UI (Metal has no 24-bit formats)"));

	/**
	 * The format of one target. {@code word} is the pack's Format constant (null when it sets none: RGBA8, the
	 * documented default), upper-cased as packs write it in either case. The legacy rule for {@code gdepth}
	 * (engine-pack-format.md section 7: "colortex1 ... upgraded to RGBA32F if declared as RGBA") applies when the
	 * caller passes the target index.
	 */
	public static TargetFormats of(@Nullable String word, int target) {
		if (word == null || word.isBlank()) {
			return RGBA8;
		}
		String key = word.trim().toUpperCase(Locale.ROOT);
		if (target == 1 && key.equals("RGBA")) {
			return new TargetFormats(125, 4, 16);
		}
		if (key.equals("RGBA")) {
			return RGBA8;
		}
		return FORMATS.getOrDefault(key, RGBA8);
	}

	/** Why a word is served by another format, or null when the word maps as written. */
	public static @Nullable String fallbackWord(String word) {
		if (word == null) {
			return null;
		}
		return FALLBACKS.get(word.trim().toUpperCase(Locale.ROOT));
	}

	/**
	 * Whether the word names a format this table knows (E6-B: raw custom textures refuse an unknown internal format instead
	 * of taking RGBA8). The word set is {@link InternalFormats}'s; the pack table checks it at load.
	 */
	public static boolean known(@Nullable String word) {
		return InternalFormats.known(word);
	}

	/** The keys of this table, for the test that keeps it equal to {@link InternalFormats#words()}. */
	static Set<String> formatWords() {
		return FORMATS.keySet();
	}
}
