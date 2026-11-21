// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.metal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.renderpearl.api.GpuFormat;
import org.junit.jupiter.api.Test;

/** The raw MTLPixelFormat words and renderpearl formats map one way and back (plan.md E4, game-pipeline variants). */
class GpuFormatWordsTest {

	/** The raw words the pack's Format constants name (TargetFormats' table). */
	private static final int[] PACK_WORDS = {10, 12, 13, 14, 20, 22, 23, 24, 25, 30, 32, 33, 34, 53, 54, 55, 60, 62, 63, 64,
		65, 70, 72, 73, 74, 90, 91, 92, 103, 104, 105, 110, 112, 113, 114, 115, 123, 124, 125};

	@Test
	void everyPackWordNamesAFormatThatNamesTheSameWordBack() {
		for (int word : PACK_WORDS) {
			GpuFormat format = GpuFormatWords.ofWord(word);
			assertNotNull(format, "word " + word + " names no format");
			assertEquals(word, MetalConst.pixelFormat(format), "word " + word + " through " + format + " back to a word");
		}
	}

	@Test
	void everyFormatWithAWordMapsBackToAFormatWithTheSameWord() {
		int checked = 0;
		for (GpuFormat format : GpuFormat.values()) {
			int word;
			try {
				word = MetalConst.pixelFormat(format);
			} catch (IllegalArgumentException noWord) {
				continue;  // Metal has no texture format for it
			}
			GpuFormat back = GpuFormatWords.ofWord(word);
			assertNotNull(back, format + " has word " + word + " but the inverse names nothing");
			assertEquals(word, MetalConst.pixelFormat(back), format + " through its word and back");
			checked++;
		}
		assertTrue(checked > 10, "only " + checked + " formats have a word");
	}

	@Test
	void aWordNoFormatHasNamesNothing() {
		assertNull(GpuFormatWords.ofWord(999));
	}
}
