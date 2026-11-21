// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.pack.textures;

import java.util.Locale;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The internal format words the engine stores for a raw custom texture (the pack's {@code <internalFormat>}): every word a
 * render target's {@code <target>Format} may name, and RGBA. The pack table refuses a raw file whose word is not in this set,
 * at load. The runtime's {@code TargetFormats} maps the same words to Metal formats; a test keeps the two sets equal. This
 * package is pure, so the set lives here and not with the runtime's table.
 */
public final class InternalFormats {

	private static final Set<String> WORDS = Set.of(
		"R8", "R8_SNORM", "R8I", "R8UI",
		"RG8", "RG8_SNORM", "RG8I", "RG8UI",
		"RGB8", "RGB8_SNORM", "RGB8I", "RGB8UI",
		"RGBA", "RGBA8", "RGBA8_SNORM", "RGBA8I", "RGBA8UI",
		"R16", "R16_SNORM", "R16F", "R16I", "R16UI",
		"RG16", "RG16_SNORM", "RG16F", "RG16I", "RG16UI",
		"RGB16", "RGB16_SNORM", "RGB16F", "RGB16I", "RGB16UI",
		"RGBA16", "RGBA16_SNORM", "RGBA16F", "RGBA16I", "RGBA16UI",
		"R32F", "R32I", "R32UI",
		"RG32F", "RG32I", "RG32UI",
		"RGB32F", "RGB32I", "RGB32UI",
		"RGBA32F", "RGBA32I", "RGBA32UI",
		"R3_G3_B2", "RGB5_A1", "RGB10_A2", "RGB10_A2UI", "R11F_G11F_B10F", "RGB9_E5");

	private InternalFormats() {
	}

	/** The stored words, upper case. */
	public static Set<String> words() {
		return WORDS;
	}

	/** Whether the word names a stored internal format, compared trimmed and upper case (null and blank name none). */
	public static boolean known(@Nullable String word) {
		if (word == null || word.isBlank()) {
			return false;
		}
		return WORDS.contains(word.trim().toUpperCase(Locale.ROOT));
	}
}
