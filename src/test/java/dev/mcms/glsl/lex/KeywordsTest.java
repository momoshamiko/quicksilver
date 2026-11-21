// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.lex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import dev.mcms.glsl.GlslSyntaxException;

/** The keyword and reserved-word rule per GLSL version, checked against the specification's keyword lists. */
class KeywordsTest {
	private static TokenKind kind(String word, int version) {
		return new Lexer(word, version).lex().get(0).kind();
	}

	@Test
	void precisionAndInvariantWordsAreNewIn120() {
		for (String w : new String[] {"lowp", "mediump", "highp", "precision", "invariant"}) {
			assertEquals(TokenKind.IDENT, kind(w, 110), w + " is not a GLSL 1.10 keyword");
			assertEquals(TokenKind.KEYWORD, kind(w, 120), w + " is a GLSL 1.20 keyword");
		}
		// A source without #version (110) may use the word as a name.
		assertEquals("KEYWORD IDENT ASSIGN FLOAT SEMICOLON",
				kinds(new Lexer("float precision = 0.5;", 110).lex()));
	}

	private static String kinds(java.util.List<Token> tokens) {
		StringBuilder b = new StringBuilder();
		for (Token t : tokens) {
			if (t.kind() == TokenKind.EOF) {
				continue;
			}
			if (!b.isEmpty()) {
				b.append(' ');
			}
			b.append(t.kind());
		}
		return b.toString();
	}

	@Test
	void centroidIsAKeywordIn120() {
		assertEquals(TokenKind.KEYWORD, kind("centroid", 120));
		assertEquals(TokenKind.KEYWORD, kind("centroid", 130));
	}

	@Test
	void laterReservedWordsAreIdentifiersIn110And120() {
		for (String w : new String[] {"filter", "active", "common", "partition", "superp"}) {
			assertEquals(TokenKind.IDENT, kind(w, 110), w + " is an identifier in 1.10");
			assertEquals(TokenKind.IDENT, kind(w, 120), w + " is an identifier in 1.20");
			assertThrows(GlslSyntaxException.class, () -> kind(w, 130), w + " is reserved from 1.30");
		}
		// resource is an identifier through 4.10 and first reserved in 4.20.
		assertEquals(TokenKind.IDENT, kind("resource", 130));
		assertEquals(TokenKind.IDENT, kind("resource", 330));
		assertEquals(TokenKind.IDENT, kind("resource", 410));
		assertThrows(GlslSyntaxException.class, () -> kind("resource", 420));
	}

	@Test
	void caseIsOnlyReservedWithSwitch() {
		assertEquals(TokenKind.IDENT, kind("case", 120));
		assertThrows(GlslSyntaxException.class, () -> kind("switch", 120));
		assertThrows(GlslSyntaxException.class, () -> kind("default", 120));
		assertEquals(TokenKind.KEYWORD, kind("case", 130));
	}

	@Test
	void dmatWordsAreIdentifiersBefore400() {
		assertEquals(TokenKind.IDENT, kind("dmat2", 130));
		assertEquals(TokenKind.IDENT, kind("dmat4x4", 330));
		assertEquals(TokenKind.KEYWORD, kind("dmat2", 400));
		// double and dvec* were reserved before 4.00 and stay errors.
		assertThrows(GlslSyntaxException.class, () -> kind("double", 330));
		assertThrows(GlslSyntaxException.class, () -> kind("dvec2", 330));
	}

	@Test
	void versionNumberOutOfRangeIsASyntaxError() {
		GlslSyntaxException e = assertThrows(GlslSyntaxException.class,
				() -> dev.mcms.glsl.parse.Parser.parse("#version 99999999999\nvoid main() { }"));
		if (!e.getMessage().contains("#version")) {
			throw new AssertionError("expected a #version diagnostic, got: " + e.getMessage());
		}
	}
}
