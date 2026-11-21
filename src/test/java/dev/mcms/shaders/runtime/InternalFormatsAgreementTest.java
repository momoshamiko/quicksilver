// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcms.shaders.pack.textures.InternalFormats;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The two places that name the stored internal formats agree: the pack table's word set (InternalFormats, which refuses a raw
 * file at load) and the runtime's Metal table (TargetFormats, which maps the same words). A word in one and not the other
 * would load a raw file the engine then cannot store, or refuse one it stores.
 */
final class InternalFormatsAgreementTest {

	@Test
	void theStoredWordsAreTheRenderTargetWordsPlusRgba() {
		Set<String> expected = new HashSet<>(TargetFormats.formatWords());
		expected.add("RGBA");
		assertEquals(expected, InternalFormats.words());
	}

	@Test
	void bothSetsAcceptEveryTargetWordInAnyCaseAndWithPadding() {
		for (String word : TargetFormats.formatWords()) {
			String padded = " " + word.toLowerCase(Locale.ROOT) + " ";
			assertTrue(TargetFormats.known(word), word);
			assertTrue(InternalFormats.known(word), word);
			assertTrue(TargetFormats.known(padded), padded);
			assertTrue(InternalFormats.known(padded), padded);
		}
		assertTrue(TargetFormats.known("rgba"));
		assertTrue(InternalFormats.known("rgba"));
	}

	@Test
	void bothSetsRefuseWhatTheEngineDoesNotStore() {
		for (String word : List.of("NOTAFORMAT", "RGB8_E5", "R64", "RGBA_INTEGER", "", "   ")) {
			assertFalse(TargetFormats.known(word), word);
			assertFalse(InternalFormats.known(word), word);
		}
		assertFalse(TargetFormats.known(null));
		assertFalse(InternalFormats.known(null));
	}
}
