// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.lex;

/**
 * One lexed token. Line and column are 1-based and refer to the preprocessed text handed to the lexer; the
 * preprocessor's line map turns them into pack file positions.
 */
public record Token(TokenKind kind, String text, int line, int column) {
	@Override
	public String toString() {
		return kind + " '" + text + "' " + line + ":" + column;
	}
}
