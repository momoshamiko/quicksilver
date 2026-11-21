// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.expr;

/**
 * A syntax, name-resolution or type error in one expression. The message always names the expression text and the
 * 1-based column, so a pack that fails to load can be reported by expression (ADR 0006 §4: failures name the reason).
 */
public final class ExprError extends RuntimeException {
	private final String expressionText;
	private final int column;

	private ExprError(String expressionText, int column, String message) {
		super(message);
		this.expressionText = expressionText;
		this.column = column;
	}

	/** Builds the error for a 0-based character offset in {@code text}. */
	static ExprError at(String text, int offset, String problem) {
		int column = offset + 1;
		return new ExprError(text, column, "expression \"" + text + "\" column " + column + ": " + problem);
	}

	public String expressionText() {
		return expressionText;
	}

	/** 1-based column of the problem in {@link #expressionText()}. */
	public int column() {
		return column;
	}
}
