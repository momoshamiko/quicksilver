// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.parse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcms.glsl.GlslSyntaxException;
import dev.mcms.glsl.lex.Keywords;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The image, buffer and shared keywords under an older {@code #version} that enables the extension giving them (GLSL 4.20
 * and 4.30 spec, the extension lists of each word): the mcms-compute test pack declares
 * {@code layout(r8ui) writeonly uniform uimage2D} under {@code #version 400}. An enabled extension makes its own words
 * keywords and no others (plan.md E6, finding 15).
 */
final class ExtensionKeywordsTest {

	private static final String IMAGE_DECLARATION = "layout(r8ui) writeonly uniform uimage2D flag_img;\n";

	@Test
	void anImageDeclarationUnder400ParsesWhenTheImageExtensionIsEnabled() {
		String text = "#version 400 compatibility\n#extension GL_ARB_shader_image_load_store : enable\n"
			+ IMAGE_DECLARATION + "void main() {}\n";
		assertTrue(Parser.extensionWords(text).contains("uimage2D"));
		Parser.parse(text);
	}

	@Test
	void anImageDeclarationUnder400IsRefusedWithoutTheExtension() {
		String text = "#version 400 compatibility\n" + IMAGE_DECLARATION + "void main() {}\n";
		assertThrows(GlslSyntaxException.class, () -> Parser.parse(text));
	}

	@Test
	void theStorageBufferExtensionGivesTheWordBufferAndNothingElse() {
		assertEquals(Set.of("buffer"),
			Parser.extensionWords("#extension GL_ARB_shader_storage_buffer_object : require\n"));
		assertEquals(Set.of(), Parser.extensionWords("#extension GL_OES_standard_derivatives : enable\n"));
		assertEquals(Set.of(), Parser.extensionWords("// #extension GL_ARB_compute_shader : enable\n"));
	}

	@Test
	void anEnabledExtensionLeavesLaterWordsAsIdentifiers() {
		// Under #version 120, 'sample' and 'patch' are identifiers. The old rule raised the whole lexer to version 420 when
		// an image extension was enabled, which made them keywords; the extension must not do that now.
		String text = "#version 120\n#extension GL_ARB_shader_image_load_store : enable\n"
			+ "uniform float sample;\nuniform float patch;\nvoid main() { float filter = sample + patch; }\n";
		Parser.parse(text);
		assertFalse(Keywords.isKeyword("sample", 120));
	}

	@Test
	void theImageExtensionGivesTheImageWordsOnly() {
		Set<String> words = Keywords.extensionWords("GL_ARB_shader_image_load_store");
		assertTrue(words.containsAll(Set.of("writeonly", "readonly", "coherent", "uimage2D", "image3D")));
		assertFalse(words.contains("buffer"));
		assertFalse(words.contains("sample"));
		assertFalse(words.contains("shared"));
	}
}
