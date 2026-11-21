// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.ast;

/** Prefix unary operators (GLSL 4.60 specification, section 5.1 and 5.8). */
public enum UnaryOp {
	PLUS("+"),
	MINUS("-"),
	NOT("!"),
	BIT_NOT("~"),
	PRE_INC("++"),
	PRE_DEC("--");

	private final String word;

	UnaryOp(String word) {
		this.word = word;
	}

	public String word() {
		return word;
	}
}
