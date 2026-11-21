// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.ast;

/** Postfix {@code ++} and {@code --} (GLSL 4.60 specification, section 5.8). */
public enum PostfixOp {
	INC("++"),
	DEC("--");

	private final String word;

	PostfixOp(String word) {
		this.word = word;
	}

	public String word() {
		return word;
	}
}
