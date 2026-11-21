// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.lex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.mcms.glsl.GlslSyntaxException;

/** Token kinds, literals, operators with maximal munch, comments, directives and the version-dependent keyword rule. */
class LexerTest {
	private static List<Token> lex(String text, int version) {
		return new Lexer(text, version).lex();
	}

	private static String kinds(String text, int version) {
		StringBuilder b = new StringBuilder();
		for (Token t : lex(text, version)) {
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
	void wordsOperatorsAndPunctuation() {
		assertEquals("KEYWORD IDENT ASSIGN IDENT PLUS IDENT SEMICOLON", kinds("vec4 x = a + b;", 460));
		assertEquals("IDENT LBRACKET INT RBRACKET", kinds("a[1]", 460));
		assertEquals("LBRACE RBRACE LPAREN RPAREN DOT COMMA COLON QUESTION", kinds("{}().,:?", 460));
	}

	@Test
	void maximalMunch() {
		assertEquals("IDENT INC PLUS IDENT", kinds("a+++b", 460));
		assertEquals("IDENT SHL_EQ INT", kinds("x<<=2", 460));
		assertEquals("IDENT SHR_EQ INT", kinds("x >>= 2", 460));
		assertEquals("IDENT LE IDENT LT IDENT", kinds("a<=b<c", 460));
		assertEquals("IDENT LOGAND AND IDENT", kinds("a&&&b", 460));
		assertEquals("IDENT LOGXOR LOGOR IDENT", kinds("a^^||b", 460));
		assertEquals("IDENT NE EQ", kinds("a!===", 460));
		assertEquals("IDENT DASH_EQ IDENT", kinds("a-=b", 460));
	}

	@Test
	void integerLiteralForms() {
		assertEquals("INT INT INT INT", kinds("0 1 42 07", 460));
		assertEquals("INT INT", kinds("0x1F 0X2A", 460));
		assertEquals("UINT UINT UINT", kinds("9u 0xFFu 07u", 460));
	}

	@Test
	void floatLiteralForms() {
		assertEquals("FLOAT FLOAT FLOAT FLOAT", kinds(".5 1. 1.5 1e3", 460));
		assertEquals("FLOAT FLOAT", kinds("1E-3 2.5e+2", 460));
		assertEquals("FLOAT FLOAT", kinds("3.f 1.0f", 460));
		assertEquals("DOUBLE DOUBLE", kinds("2.0lf 2.0LF", 460));
	}

	@Test
	void literalTextIsKept() {
		List<Token> ts = lex("0x1F 010 42u .5 1. 3.f", 460);
		String[] expect = {"0x1F", "010", "42u", ".5", "1.", "3.f"};
		for (int i = 0; i < expect.length; i++) {
			assertEquals(expect[i], ts.get(i).text());
		}
	}

	@Test
	void malformedLiteralsThrow() {
		assertThrows(GlslSyntaxException.class, () -> lex("1e", 460));
		assertThrows(GlslSyntaxException.class, () -> lex("0x", 460));
		assertThrows(GlslSyntaxException.class, () -> lex("08", 460));
		assertThrows(GlslSyntaxException.class, () -> lex("1.5e", 460));
	}

	@Test
	void unexpectedCharactersThrow() {
		GlslSyntaxException e = assertThrows(GlslSyntaxException.class, () -> lex("a @ b", 460));
		assertEquals(1, e.line());
		assertEquals(3, e.column());
		assertThrows(GlslSyntaxException.class, () -> lex("$", 460));
	}

	@Test
	void commentsAreSkipped() {
		assertEquals("IDENT IDENT", kinds("a /* comment */ b", 460));
		assertEquals("IDENT IDENT", kinds("a // line comment\n b", 460));
		List<Token> ts = lex("/*\n\n*/x", 460);
		assertEquals(TokenKind.IDENT, ts.get(0).kind());
		assertEquals(3, ts.get(0).line());
		assertEquals(3, ts.get(0).column());
		assertThrows(GlslSyntaxException.class, () -> lex("/* never closed", 460));
	}

	@Test
	void positionsAreOneBased() {
		List<Token> ts = lex("vec4 x;\nfloat y;", 460);
		assertEquals(1, ts.get(0).line());
		assertEquals(1, ts.get(0).column());
		assertEquals(1, ts.get(1).line());
		assertEquals(6, ts.get(1).column());
		assertEquals(2, ts.get(3).line());
		assertEquals(1, ts.get(3).column());
	}

	@Test
	void directiveLinesBecomeOneToken() {
		List<Token> ts = lex("#version 330 core\nfloat x;", 330);
		assertEquals(TokenKind.DIRECTIVE, ts.get(0).kind());
		assertEquals("#version 330 core", ts.get(0).text());
		assertEquals(1, ts.get(0).line());
		assertEquals(1, ts.get(0).column());
		assertEquals(TokenKind.KEYWORD, ts.get(1).kind());
		assertEquals("float", ts.get(1).text());
		assertEquals(2, ts.get(1).line());
	}

	@Test
	void directiveMayStartAfterSpaces() {
		List<Token> ts = lex("   #pragma once", 460);
		assertEquals("#pragma once", ts.get(0).text());
		assertEquals(4, ts.get(0).column());
	}

	@Test
	void hashInsideALineThrows() {
		assertThrows(GlslSyntaxException.class, () -> lex("a #b", 460));
	}

	@Test
	void laterKeywordsAreIdentifiersInEarlierVersions() {
		assertEquals(TokenKind.IDENT, lex("sample", 130).get(0).kind());
		assertEquals(TokenKind.KEYWORD, lex("sample", 400).get(0).kind());
		assertEquals(TokenKind.IDENT, lex("buffer", 400).get(0).kind());
		assertEquals(TokenKind.KEYWORD, lex("buffer", 430).get(0).kind());
		assertEquals(TokenKind.IDENT, lex("shared", 130).get(0).kind());
		assertEquals(TokenKind.KEYWORD, lex("shared", 430).get(0).kind());
		assertEquals(TokenKind.IDENT, lex("precise", 330).get(0).kind());
		assertEquals(TokenKind.KEYWORD, lex("precise", 400).get(0).kind());
		assertEquals(TokenKind.IDENT, lex("patch", 330).get(0).kind());
		assertEquals(TokenKind.IDENT, lex("subroutine", 330).get(0).kind());
		assertEquals(TokenKind.KEYWORD, lex("subroutine", 400).get(0).kind());
		assertEquals(TokenKind.IDENT, lex("image2D", 400).get(0).kind());
		assertEquals(TokenKind.KEYWORD, lex("image2D", 420).get(0).kind());
		assertEquals(TokenKind.IDENT, lex("atomic_uint", 400).get(0).kind());
		assertEquals(TokenKind.KEYWORD, lex("atomic_uint", 420).get(0).kind());
	}

	@Test
	void textureIsNeverAKeyword() {
		assertEquals(TokenKind.IDENT, lex("texture", 130).get(0).kind());
		assertEquals(TokenKind.IDENT, lex("texture", 460).get(0).kind());
		// The pack form that forces this rule (corpus §2): a sampler named texture.
		assertEquals("KEYWORD KEYWORD IDENT SEMICOLON", kinds("uniform sampler2D texture;", 130));
	}

	@Test
	void reservedWordsThrow() {
		assertThrows(GlslSyntaxException.class, () -> lex("switch", 120));
		assertEquals(TokenKind.KEYWORD, lex("switch", 130).get(0).kind());
		assertThrows(GlslSyntaxException.class, () -> lex("double", 330));
		assertEquals(TokenKind.KEYWORD, lex("double", 400).get(0).kind());
		assertThrows(GlslSyntaxException.class, () -> lex("dvec2", 330));
		assertThrows(GlslSyntaxException.class, () -> lex("volatile", 410));
		assertEquals(TokenKind.KEYWORD, lex("volatile", 420).get(0).kind());
		for (String w : new String[] {"half", "fixed", "unsigned", "superp", "input", "output", "hvec2", "fvec4",
				"filter", "sizeof", "cast", "namespace", "using", "common", "partition", "active", "sampler3DRect"}) {
			assertEquals(TokenKind.IDENT, lex(w + "x", 460).get(0).kind(), w + "x should lex as one identifier");
			assertThrows(GlslSyntaxException.class, () -> lex(w, 460), w + " is reserved");
		}
	}

	@Test
	void versionZeroLexesEveryWordAsAnIdentifier() {
		assertEquals(TokenKind.IDENT, lex("float", 0).get(0).kind());
		assertEquals(TokenKind.IDENT, lex("switch", 0).get(0).kind());
	}
}
