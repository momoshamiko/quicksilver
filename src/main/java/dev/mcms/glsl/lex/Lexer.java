// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.lex;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import dev.mcms.glsl.GlslSyntaxException;

/**
 * Lexer for preprocessed GLSL text (macros expanded, inactive code removed; comments may remain; the {@code #version},
 * {@code #extension} and {@code #pragma} lines remain as lines starting with {@code #}). Comments are skipped, and each
 * line whose first non-space character is {@code #} becomes one {@link TokenKind#DIRECTIVE} token holding the line from
 * the {@code #} to the end. The GLSL version selects the keyword set ({@link Keywords}); version 0 lexes every word as
 * an identifier, which the parser uses to find the {@code #version} line before the real pass.
 */
public final class Lexer {
	private final String text;
	private final int version;
	/** Words an enabled extension makes keywords under any version ({@link Keywords#extensionWords}). */
	private final Set<String> extensionWords;

	private int i;
	private int line = 1;
	private int column = 1;
	private boolean lineStart = true;
	private final List<Token> out = new ArrayList<>();

	public Lexer(String text, int version) {
		this(text, version, Set.of());
	}

	public Lexer(String text, int version, Set<String> extensionWords) {
		this.text = text;
		this.version = version;
		this.extensionWords = Set.copyOf(extensionWords);
	}

	/** Lexes the whole text; the last token is {@link TokenKind#EOF}. Throws at the first bad word or literal. */
	public List<Token> lex() {
		while (step()) {
			// every token lands in out
		}
		return out;
	}

	/**
	 * The text's first token ({@link TokenKind#EOF} for an empty text). The parser's {@code #version} scan reads only this: it
	 * lexed the whole text before the real pass, a second full lex of every translation (E9, load time).
	 */
	public Token first() {
		step();
		return out.getFirst();
	}

	/** Lexes one token into out (the EOF token at the end, then false). */
	private boolean step() {
		skipSpaceAndComments();
		if (i >= text.length()) {
			out.add(new Token(TokenKind.EOF, "", line, column));
			return false;
		}
		char c = text.charAt(i);
		if (c == '#') {
			if (!lineStart) {
				throw error("a '#' may only start a line");
			}
			directive();
			return true;
		}
		lineStart = false;
		if (isWordStart(c)) {
			word();
		} else if (isAsciiDigit(c) || (c == '.' && i + 1 < text.length() && isAsciiDigit(text.charAt(i + 1)))) {
			number();
		} else {
			operator();
		}
		return true;
	}

	private void skipSpaceAndComments() {
		while (i < text.length()) {
			char c = text.charAt(i);
			if (c == ' ' || c == '\t' || c == '\f' || c == '\u000B') {
				i++;
				column++;
			} else if (c == '\n') {
				i++;
				line++;
				column = 1;
				lineStart = true;
			} else if (c == '\r') {
				i++;
				if (i < text.length() && text.charAt(i) == '\n') {
					i++;
				}
				line++;
				column = 1;
				lineStart = true;
			} else if (c == '/' && i + 1 < text.length() && text.charAt(i + 1) == '/') {
				while (i < text.length() && text.charAt(i) != '\n' && text.charAt(i) != '\r') {
					i++;
					column++;
				}
			} else if (c == '/' && i + 1 < text.length() && text.charAt(i + 1) == '*') {
				i += 2;
				column += 2;
				boolean closed = false;
				while (i < text.length()) {
					char d = text.charAt(i);
					if (d == '*' && i + 1 < text.length() && text.charAt(i + 1) == '/') {
						i += 2;
						column += 2;
						closed = true;
						break;
					}
					if (d == '\n') {
						i++;
						line++;
						column = 1;
					} else if (d == '\r') {
						i++;
						if (i < text.length() && text.charAt(i) == '\n') {
							i++;
						}
						line++;
						column = 1;
					} else {
						i++;
						column++;
					}
				}
				if (!closed) {
					throw error("unterminated block comment");
				}
			} else {
				return;
			}
		}
	}

	private void directive() {
		int start = i;
		int startColumn = column;
		while (i < text.length() && text.charAt(i) != '\n' && text.charAt(i) != '\r') {
			i++;
			column++;
		}
		String d = text.substring(start, i).stripTrailing();
		out.add(new Token(TokenKind.DIRECTIVE, d, line, startColumn));
	}

	private void word() {
		int start = i;
		int startColumn = column;
		while (i < text.length() && isWordPart(text.charAt(i))) {
			i++;
			column++;
		}
		String w = text.substring(start, i);
		if (Keywords.isKeyword(w, version) || this.extensionWords.contains(w)) {
			out.add(new Token(TokenKind.KEYWORD, w, line, startColumn));
		} else if (Keywords.isReserved(w, version)) {
			throw new GlslSyntaxException(line, startColumn, "reserved word: '" + w + "'");
		} else {
			out.add(new Token(TokenKind.IDENT, w, line, startColumn));
		}
	}

	private void number() {
		int start = i;
		int startColumn = column;
		char c = text.charAt(i);
		// Hexadecimal integer.
		if (c == '0' && i + 1 < text.length() && (text.charAt(i + 1) == 'x' || text.charAt(i + 1) == 'X')) {
			i += 2;
			column += 2;
			while (i < text.length() && isHexDigit(text.charAt(i))) {
				i++;
				column++;
			}
			if (i == start + 2) {
				throw errorAt(startColumn, "malformed hexadecimal literal");
			}
			TokenKind kind = TokenKind.INT;
			if (i < text.length() && (text.charAt(i) == 'u' || text.charAt(i) == 'U')) {
				i++;
				column++;
				kind = TokenKind.UINT;
			}
			out.add(new Token(kind, text.substring(start, i), line, startColumn));
			return;
		}
		boolean isFloat = false;
		while (i < text.length() && isAsciiDigit(text.charAt(i))) {
			i++;
			column++;
		}
		// Fractional part.
		if (i < text.length() && text.charAt(i) == '.') {
			isFloat = true;
			i++;
			column++;
			while (i < text.length() && isAsciiDigit(text.charAt(i))) {
				i++;
				column++;
			}
		}
		// Exponent part.
		if (i < text.length() && (text.charAt(i) == 'e' || text.charAt(i) == 'E')) {
			int save = i;
			int saveColumn = column;
			i++;
			column++;
			if (i < text.length() && (text.charAt(i) == '+' || text.charAt(i) == '-')) {
				i++;
				column++;
			}
			if (i >= text.length() || !isAsciiDigit(text.charAt(i))) {
				throw errorAt(saveColumn, "malformed exponent in float literal");
			}
			isFloat = true;
			while (i < text.length() && isAsciiDigit(text.charAt(i))) {
				i++;
				column++;
			}
		}
		String all = text.substring(start, i);
		if (isFloat) {
			if (i < text.length()) {
				char s = text.charAt(i);
				if (s == 'f' || s == 'F') {
					i++;
					column++;
					out.add(new Token(TokenKind.FLOAT, all + s, line, startColumn));
					return;
				}
				if ((s == 'l' || s == 'L') && i + 1 < text.length()
						&& (text.charAt(i + 1) == 'f' || text.charAt(i + 1) == 'F')) {
					char f = text.charAt(i + 1);
					i += 2;
					column += 2;
					out.add(new Token(TokenKind.DOUBLE, all + s + f, line, startColumn));
					return;
				}
			}
			out.add(new Token(TokenKind.FLOAT, all, line, startColumn));
			return;
		}
		// Plain integer, decimal or octal.
		if (all.length() > 1 && all.charAt(0) == '0') {
			for (int k = 1; k < all.length(); k++) {
				if (all.charAt(k) < '0' || all.charAt(k) > '7') {
					throw errorAt(startColumn, "invalid octal digit in '" + all + "'");
				}
			}
		}
		if (i < text.length() && (text.charAt(i) == 'u' || text.charAt(i) == 'U')) {
			char u = text.charAt(i);
			i++;
			column++;
			out.add(new Token(TokenKind.UINT, all + u, line, startColumn));
			return;
		}
		out.add(new Token(TokenKind.INT, all, line, startColumn));
	}

	/** The operator kinds by symbol length (index 1 to 3), in declaration order: the maximal munch's candidates. */
	private static final TokenKind[][] OPERATORS = operatorsByLength();

	private static TokenKind[][] operatorsByLength() {
		List<List<TokenKind>> byLength = new ArrayList<>();
		for (int len = 0; len <= 3; len++) {
			byLength.add(new ArrayList<>());
		}
		for (TokenKind k : TokenKind.values()) {
			int len = k.symbol().length();
			if (len >= 1 && len <= 3) {
				byLength.get(len).add(k);
			}
		}
		TokenKind[][] out = new TokenKind[4][];
		for (int len = 0; len <= 3; len++) {
			out[len] = byLength.get(len).toArray(TokenKind[]::new);
		}
		return out;
	}

	private void operator() {
		// Maximal munch: try three characters, then two, then one. The symbols compare in place (no substring per try; E9,
		// load time), and the token keeps the kind's own symbol text, equal to the source's.
		for (int len = 3; len >= 1; len--) {
			if (i + len > text.length()) {
				continue;
			}
			for (TokenKind k : OPERATORS[len]) {
				if (text.startsWith(k.symbol(), i)) {
					out.add(new Token(k, k.symbol(), line, column));
					i += len;
					column += len;
					return;
				}
			}
		}
		throw error("unexpected character '" + text.charAt(i) + "'");
	}
	/** GLSL digits are ASCII only: Unicode digits are not part of any literal. */
	private static boolean isAsciiDigit(char c) {
		return c >= '0' && c <= '9';
	}

	private static boolean isWordStart(char c) {
		return c == '_' || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
	}

	private static boolean isWordPart(char c) {
		return isWordStart(c) || (c >= '0' && c <= '9');
	}

	private static boolean isHexDigit(char c) {
		return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
	}

	private GlslSyntaxException error(String message) {
		return errorAt(column, message);
	}

	private GlslSyntaxException errorAt(int atColumn, String message) {
		return new GlslSyntaxException(line, atColumn, message);
	}
}
