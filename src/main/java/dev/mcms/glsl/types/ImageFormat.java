// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.types;

import java.util.HashMap;
import java.util.Map;

/**
 * The image memory format layout qualifiers of GLSL 4.30 (specification, section 4.4.6): the words allowed in
 * {@code layout(<format>) uniform image2D ...}. The kind says which image type word the format can qualify
 * ({@code iimage} takes signed formats, {@code uimage} unsigned, {@code image} the rest).
 */
public enum ImageFormat {
	RGBA32F("rgba32f", Texel.FLOAT),
	RGBA16F("rgba16f", Texel.FLOAT),
	RG32F("rg32f", Texel.FLOAT),
	RG16F("rg16f", Texel.FLOAT),
	R11F_G11F_B10F("r11f_g11f_b10f", Texel.FLOAT),
	R32F("r32f", Texel.FLOAT),
	R16F("r16f", Texel.FLOAT),
	RGBA16("rgba16", Texel.FLOAT),
	RGB10_A2("rgb10_a2", Texel.FLOAT),
	RGBA8("rgba8", Texel.FLOAT),
	RG16("rg16", Texel.FLOAT),
	RG8("rg8", Texel.FLOAT),
	R16("r16", Texel.FLOAT),
	R8("r8", Texel.FLOAT),
	RGBA16_SNORM("rgba16_snorm", Texel.FLOAT),
	RGBA8_SNORM("rgba8_snorm", Texel.FLOAT),
	RG16_SNORM("rg16_snorm", Texel.FLOAT),
	RG8_SNORM("rg8_snorm", Texel.FLOAT),
	R16_SNORM("r16_snorm", Texel.FLOAT),
	R8_SNORM("r8_snorm", Texel.FLOAT),
	RGBA32I("rgba32i", Texel.INT),
	RGBA16I("rgba16i", Texel.INT),
	RGBA8I("rgba8i", Texel.INT),
	RG32I("rg32i", Texel.INT),
	RG16I("rg16i", Texel.INT),
	RG8I("rg8i", Texel.INT),
	R32I("r32i", Texel.INT),
	R16I("r16i", Texel.INT),
	R8I("r8i", Texel.INT),
	RGBA32UI("rgba32ui", Texel.UINT),
	RGBA16UI("rgba16ui", Texel.UINT),
	RGB10_A2UI("rgb10_a2ui", Texel.UINT),
	RGBA8UI("rgba8ui", Texel.UINT),
	RG32UI("rg32ui", Texel.UINT),
	RG16UI("rg16ui", Texel.UINT),
	RG8UI("rg8ui", Texel.UINT),
	R32UI("r32ui", Texel.UINT),
	R16UI("r16ui", Texel.UINT),
	R8UI("r8ui", Texel.UINT);

	/** Which image words a format can qualify. */
	public enum Texel {
		FLOAT, INT, UINT;

		boolean matches(ScalarKind kind) {
			return switch (this) {
				case FLOAT -> kind == ScalarKind.FLOAT;
				case INT -> kind == ScalarKind.INT;
				case UINT -> kind == ScalarKind.UINT;
			};
		}
	}

	private static final Map<String, ImageFormat> BY_WORD = new HashMap<>();

	static {
		for (ImageFormat f : values()) {
			BY_WORD.put(f.word, f);
		}
	}

	private final String word;
	private final Texel texel;

	ImageFormat(String word, Texel texel) {
		this.word = word;
		this.texel = texel;
	}

	/** The GLSL layout word. */
	public String word() {
		return word;
	}

	/** True when this format belongs to the given image component kind. */
	public boolean matches(ScalarKind imageTexel) {
		return texel.matches(imageTexel);
	}

	/** The format with this layout word, or null. */
	public static ImageFormat fromWord(String word) {
		return BY_WORD.get(word);
	}
}
