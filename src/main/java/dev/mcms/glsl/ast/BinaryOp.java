// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.ast;

/**
 * Binary operators with their GLSL precedence level: the smaller the number the tighter the operator binds (GLSL 4.60
 * specification, section 5.1). All of them are left-associative; the printer adds parentheses from these levels.
 */
public enum BinaryOp {
	MUL(3, "*"),
	DIV(3, "/"),
	MOD(3, "%"),
	ADD(4, "+"),
	SUB(4, "-"),
	SHL(5, "<<"),
	SHR(5, ">>"),
	LT(6, "<"),
	GT(6, ">"),
	LE(6, "<="),
	GE(6, ">="),
	EQ(7, "=="),
	NE(7, "!="),
	BIT_AND(8, "&"),
	BIT_XOR(9, "^"),
	BIT_OR(10, "|"),
	LOG_AND(11, "&&"),
	LOG_XOR(12, "^^"),
	LOG_OR(13, "||");

	private final int level;
	private final String word;

	BinaryOp(int level, String word) {
		this.level = level;
		this.word = word;
	}

	/** Precedence level, 3 (tightest binary) to 13 (loosest binary); matches {@link GlslPrinter}'s levels. */
	public int level() {
		return level;
	}

	public String word() {
		return word;
	}
}
