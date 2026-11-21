// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.expr;

import java.util.ArrayList;
import java.util.List;

/**
 * Parser of the custom-uniform expression language (OptiFine doc shaders.properties, "Custom uniforms";
 * engine-pack-format.md §13).
 *
 * <p>Precedence, loosest binding first (OptiFine doc "Operators", same order as GLSL):
 * {@code ||}, {@code &&}, {@code == !=}, {@code < <= > >=}, {@code + -}, {@code * / %}, unary {@code ! -}, element
 * access. Element selectors are one letter ({@code .x .y .z .w}, {@code .r .g .b .a}, {@code .s .t .p .q}) or one
 * digit ({@code .0} to {@code .3}); two digit selectors read a matrix element, column first ({@code matrix.0.1} is
 * M[0][1]; see ExprFrame.setMat4 for why the engine reads this way against the docs' "row and column" wording).
 */
public final class ExprParser {
	private static final String[][] LEVELS = {
		{"||"},
		{"&&"},
		{"==", "!="},
		{"<=", "<", ">=", ">"},
		{"+", "-"},
		{"*", "/", "%"},
	};

	private final String text;
	private int i;

	private ExprParser(String text) {
		this.text = text;
	}

	/** Parses one expression; throws {@link ExprError} naming the text and column on a syntax error. */
	public static Expr parse(String text) {
		ExprParser p = new ExprParser(text);
		Expr e = p.binary(0);
		p.ws();
		// Some corpus packs close uniform values with a trailing semicolon (measured identifier-level behavior), so
		// trailing semicolons are accepted and ignored.
		while (p.i < text.length() && text.charAt(p.i) == ';') {
			p.i++;
			p.ws();
		}
		if (p.i < text.length()) {
			throw ExprError.at(text, p.i, "unexpected character '" + text.charAt(p.i) + "'");
		}
		return e;
	}

	private Expr binary(int level) {
		if (level >= LEVELS.length) {
			return unary();
		}
		Expr e = binary(level + 1);
		while (true) {
			ws();
			int at = i;
			String op = null;
			for (String candidate : LEVELS[level]) {
				if (lit(candidate)) {
					op = candidate;
					break;
				}
			}
			if (op == null) {
				if (i < text.length()) {
					checkNoStrayOperator(level, at);
				}
				return e;
			}
			// The node's position is its first token (Expr.java), the left operand's start, so error columns point there.
			e = new Expr.Binary(op, e, binary(level + 1), e.pos());
		}
	}

	/** Reports an operator that does not exist at this level (for example a lone "="), instead of "unexpected character". */
	private void checkNoStrayOperator(int level, int at) {
		char c = text.charAt(i);
		if (c == '=' && level == 2) {
			throw ExprError.at(text, at, "unknown operator '=' (comparison is '==')");
		}
	}

	private Expr unary() {
		ws();
		int at = i;
		if (lit("!")) {
			return new Expr.Unary("!", unary(), at);
		}
		if (lit("-")) {
			return new Expr.Unary("-", unary(), at);
		}
		return postfix();
	}

	private Expr postfix() {
		Expr e = primary();
		List<String> selectors = null;
		while (true) {
			ws();
			if (i >= text.length() || text.charAt(i) != '.') {
				break;
			}
			int dotAt = i;
			i++;
			if (i >= text.length()) {
				throw ExprError.at(text, dotAt, "expected an element selector (.x, .y, .0, ...) after '.'");
			}
			char c = text.charAt(i);
			String selector;
			if (Character.isLetter(c) || c == '_') {
				int s = i;
				i++;
				while (i < text.length() && (Character.isLetterOrDigit(text.charAt(i)) || text.charAt(i) == '_')) {
					i++;
				}
				selector = text.substring(s, i);
			} else if (c >= '0' && c <= '9') {
				// One digit only: ".0.1" is the second selector after a dot, so a digit never begins a number here.
				selector = String.valueOf(c);
				i++;
			} else {
				throw ExprError.at(text, dotAt, "expected an element selector (.x, .y, .0, ...) after '.'");
			}
			if (selectors == null) {
				selectors = new ArrayList<>(2);
			}
			selectors.add(selector);
		}
		if (selectors != null) {
			e = new Expr.Access(e, List.copyOf(selectors), e.pos());
		}
		return e;
	}

	private Expr primary() {
		ws();
		if (i >= text.length()) {
			throw ExprError.at(text, i, "expected a value, found the end of the expression");
		}
		char c = text.charAt(i);
		int at = i;
		if (Character.isDigit(c) || (c == '.' && i + 1 < text.length() && Character.isDigit(text.charAt(i + 1)))) {
			return number();
		}
		if (Character.isLetter(c) || c == '_') {
			String name = ident();
			if (name.equals("true")) {
				return new Expr.Bool(true, at);
			}
			if (name.equals("false")) {
				return new Expr.Bool(false, at);
			}
			ws();
			if (i < text.length() && text.charAt(i) == '(') {
				return call(name, at);
			}
			return new Expr.Name(name, at);
		}
		if (c == '(') {
			i++;
			Expr e = binary(0);
			ws();
			if (i < text.length() && text.charAt(i) == ')') {
				i++;
				return e;
			}
			throw ExprError.at(text, Math.min(i, text.length()), "expected ')'");
		}
		throw ExprError.at(text, at, "expected a value, found '" + c + "'");
	}

	private Expr call(String name, int at) {
		i++; // '('
		List<Expr> args = new ArrayList<>(4);
		ws();
		if (i < text.length() && text.charAt(i) == ')') {
			i++;
			return new Expr.Call(name, List.of(), at);
		}
		while (true) {
			args.add(binary(0));
			ws();
			if (i >= text.length()) {
				throw ExprError.at(text, i, "expected ',' or ')' in the arguments of " + name + "(...)");
			}
			char c = text.charAt(i);
			if (c == ',') {
				i++;
				continue;
			}
			if (c == ')') {
				i++;
				return new Expr.Call(name, List.copyOf(args), at);
			}
			throw ExprError.at(text, i, "expected ',' or ')' in the arguments of " + name + "(...), found '" + c + "'");
		}
	}

	private Expr number() {
		int start = i;
		boolean decimal = false;
		if (text.charAt(i) == '.') {
			decimal = true;
			i++;
		}
		while (i < text.length() && Character.isDigit(text.charAt(i))) {
			i++;
		}
		if (!decimal && i < text.length() && text.charAt(i) == '.') {
			decimal = true;
			i++;
			while (i < text.length() && Character.isDigit(text.charAt(i))) {
				i++;
			}
		}
		if (i < text.length() && (text.charAt(i) == 'e' || text.charAt(i) == 'E')) {
			decimal = true;
			i++;
			if (i < text.length() && (text.charAt(i) == '+' || text.charAt(i) == '-')) {
				i++;
			}
			if (i >= text.length() || !Character.isDigit(text.charAt(i))) {
				throw ExprError.at(text, i, "malformed number: an exponent needs digits");
			}
			while (i < text.length() && Character.isDigit(text.charAt(i))) {
				i++;
			}
		}
		return new Expr.Num(Double.parseDouble(text.substring(start, i)), !decimal, start);
	}

	private String ident() {
		int s = i;
		i++;
		while (i < text.length() && (Character.isLetterOrDigit(text.charAt(i)) || text.charAt(i) == '_')) {
			i++;
		}
		return text.substring(s, i);
	}

	private boolean lit(String s) {
		if (text.startsWith(s, i)) {
			i += s.length();
			return true;
		}
		return false;
	}

	private void ws() {
		while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
			i++;
		}
	}
}
