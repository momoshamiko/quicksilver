// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.ast;

/** Simple and compound assignment operators (GLSL 4.60 specification, section 5.8). */
public enum AssignOp {
	ASSIGN("="),
	ADD("+="),
	SUB("-="),
	MUL("*="),
	DIV("/="),
	MOD("%="),
	SHL("<<="),
	SHR(">>="),
	AND("&="),
	XOR("^="),
	OR("|=");

	private final String word;

	AssignOp(String word) {
		this.word = word;
	}

	public String word() {
		return word;
	}
}
