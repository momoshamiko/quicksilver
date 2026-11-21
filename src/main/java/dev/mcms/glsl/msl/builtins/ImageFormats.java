// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.msl.builtins;

import java.util.Map;

/**
 * The GLSL image format qualifiers (GLSL 4.60, section 4.3.9 "Omitting Qualifiers" table) and the channel kind each
 * one reads and writes. The kind decides the MSL texture element type (float/int/uint) of an image call: imageLoad
 * on {@code image2D(r32ui)} returns {@code uint4} even though the GLSL type is not uimage2D.
 */
final class ImageFormats {
	enum Channels { FLOAT, INT, UINT }

	private static final Map<String, Channels> FORMATS = Map.ofEntries(
		Map.entry("rgba32f", Channels.FLOAT), Map.entry("rgba16f", Channels.FLOAT),
		Map.entry("rg32f", Channels.FLOAT), Map.entry("rg16f", Channels.FLOAT),
		Map.entry("r11f_g11f_b10f", Channels.FLOAT), Map.entry("r32f", Channels.FLOAT),
		Map.entry("r16f", Channels.FLOAT), Map.entry("rgba16", Channels.FLOAT),
		Map.entry("rgb10_a2", Channels.FLOAT), Map.entry("rgba8", Channels.FLOAT),
		Map.entry("rg16", Channels.FLOAT), Map.entry("rg8", Channels.FLOAT),
		Map.entry("r16", Channels.FLOAT), Map.entry("r8", Channels.FLOAT),
		Map.entry("rgba16_snorm", Channels.FLOAT), Map.entry("rgba8_snorm", Channels.FLOAT),
		Map.entry("rg16_snorm", Channels.FLOAT), Map.entry("rg8_snorm", Channels.FLOAT),
		Map.entry("r16_snorm", Channels.FLOAT), Map.entry("r8_snorm", Channels.FLOAT),
		Map.entry("rgba32i", Channels.INT), Map.entry("rgba16i", Channels.INT),
		Map.entry("rgba8i", Channels.INT), Map.entry("rg32i", Channels.INT),
		Map.entry("rg16i", Channels.INT), Map.entry("rg8i", Channels.INT),
		Map.entry("r32i", Channels.INT), Map.entry("r16i", Channels.INT),
		Map.entry("r8i", Channels.INT),
		Map.entry("rgba32ui", Channels.UINT), Map.entry("rgba16ui", Channels.UINT),
		Map.entry("rgb10_a2ui", Channels.UINT), Map.entry("rgba8ui", Channels.UINT),
		Map.entry("rg32ui", Channels.UINT), Map.entry("rg16ui", Channels.UINT),
		Map.entry("rg8ui", Channels.UINT), Map.entry("r32ui", Channels.UINT),
		Map.entry("r16ui", Channels.UINT), Map.entry("r8ui", Channels.UINT));

	static boolean isKnown(String format) {
		return FORMATS.containsKey(format);
	}

	/** The MSL element name ("float", "int", "uint") an image with this format reads and writes. */
	static String element(String format) {
		return switch (FORMATS.get(format)) {
			case FLOAT -> "float";
			case INT -> "int";
			case UINT -> "uint";
		};
	}

	private ImageFormats() {
	}
}
