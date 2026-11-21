// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.lex;

/**
 * Every token kind the lexer produces. GLSL 1.10-4.60 keywords all share {@link #KEYWORD}: the GLSL version given to the
 * lexer decides which words are keywords ({@link Keywords}); operators and punctuation are their own kinds because their
 * maximal munch never depends on the version.
 */
public enum TokenKind {
	IDENT,
	KEYWORD,
	INT,
	UINT,
	FLOAT,
	DOUBLE,
	DIRECTIVE,
	EOF,

	LBRACKET("["),
	RBRACKET("]"),
	LPAREN("("),
	RPAREN(")"),
	LBRACE("{"),
	RBRACE("}"),
	DOT("."),
	COMMA(","),
	COLON(":"),
	SEMICOLON(";"),
	QUESTION("?"),
	ASSIGN("="),
	LOGOR("||"),
	LOGXOR("^^"),
	LOGAND("&&"),
	OR("|"),
	XOR("^"),
	AND("&"),
	EQ("=="),
	NE("!="),
	LE("<="),
	GE(">="),
	LT("<"),
	GT(">"),
	SHL("<<"),
	SHR(">>"),
	PLUS("+"),
	DASH("-"),
	STAR("*"),
	SLASH("/"),
	PERCENT("%"),
	TILDE("~"),
	BANG("!"),
	INC("++"),
	DEC("--"),
	PLUS_EQ("+="),
	DASH_EQ("-="),
	STAR_EQ("*="),
	SLASH_EQ("/="),
	PERCENT_EQ("%="),
	SHL_EQ("<<="),
	SHR_EQ(">>="),
	AND_EQ("&="),
	XOR_EQ("^="),
	OR_EQ("|=");

	private final String symbol;

	TokenKind() {
		this("");
	}

	TokenKind(String symbol) {
		this.symbol = symbol;
	}

	/** The source text of operators and punctuation; empty for the word and literal kinds. */
	public String symbol() {
		return symbol;
	}
}
