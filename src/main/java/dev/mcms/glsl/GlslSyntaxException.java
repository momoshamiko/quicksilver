// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl;

/**
 * A GLSL syntax error: the lexer or the parser stopped at the first problem. Line and column are 1-based positions in
 * the preprocessed text; the message names what was expected or what is wrong. It lives above the {@code lex} and
 * {@code parse} packages because both throw it and neither may import the other.
 */
public final class GlslSyntaxException extends RuntimeException {
	private final int line;
	private final int column;

	public GlslSyntaxException(int line, int column, String message) {
		super(message + " at " + line + ":" + column);
		this.line = line;
		this.column = column;
	}

	public int line() {
		return line;
	}

	public int column() {
		return column;
	}
}
