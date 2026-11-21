// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.metal;

import com.mojang.renderpearl.api.GpuFormat;
import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The one mapping between a raw MTLPixelFormat word (the pack's {@code <target>Format} words, engine-pack-format.md
 * section 7) and renderpearl's {@link GpuFormat} (plan.md E4, game-pipeline variants: a copy of a game pipeline writes
 * colortex0 in colortex0's format). The word of each format is {@link MetalConst#pixelFormat}; the inverse is built once
 * from that same table, so the two directions cannot disagree. Formats Metal has no word for are absent.
 */
final class GpuFormatWords {

	private static final Map<Integer, GpuFormat> BY_WORD = byWord();

	private GpuFormatWords() {
	}

	/** The format a raw pixel format word names; null for a word no format has. */
	static @Nullable GpuFormat ofWord(int word) {
		return BY_WORD.get(word);
	}

	private static Map<Integer, GpuFormat> byWord() {
		Map<Integer, GpuFormat> words = new HashMap<>();
		for (GpuFormat format : GpuFormat.values()) {
			int word;
			try {
				word = MetalConst.pixelFormat(format);
			} catch (IllegalArgumentException unsupported) {
				continue;  // Metal has no texture format for it
			}
			words.putIfAbsent(word, format);
		}
		return Map.copyOf(words);
	}
}
