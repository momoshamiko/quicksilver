// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.pack;

import dev.mcms.glsl.GlslSyntaxException;
import dev.mcms.glsl.lex.Lexer;
import dev.mcms.glsl.lex.Token;
import dev.mcms.glsl.lex.TokenKind;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The colour targets a fragment stage writes, read from its active code: the preprocessed text with the comments dropped
 * by the lexer, so a {@code gl_FragData} or an {@code out} inside a comment or an inactive branch counts for nothing. A
 * program with no DRAWBUFFERS or RENDERTARGETS comment writes the targets this returns (plan.md E6-drawbuffers, 2026-11-15).
 *
 * <p>Draw buffer {@code i} is target {@code i}, as the directive programs number them and as the translator maps an
 * output to its listed target (FragmentOutputs). {@code gl_FragData[i]} and an out at {@code layout(location = i)} are
 * draw buffer {@code i}. {@code gl_FragColor} is draw buffer 0: Iris on a core profile writes location 0 only. An out named
 * {@code outColor<n>} is draw buffer {@code n}. An out with neither takes the first free location in declaration order, the
 * rule FragmentOutputs applies, and explicit and named locations are claimed first.
 *
 * <p>The scan gives up, and returns empty, when its answer would be a guess: a {@code gl_FragData} index that is not an
 * integer literal, an index or location of {@link #DRAW_BUFFERS} or more, a location or array length that is not an
 * integer literal, a declaration it does not read, or text the lexer refuses. The caller then keeps the documented
 * default of the first eight buffers.
 */
final class FragmentWriteScan {
	/** The draw buffers a stage can write: Metal's colour attachment limit per pass. */
	static final int DRAW_BUFFERS = 8;

	/** The version the lexer reads a stage with no {@code #version} line at (the GLSL default). */
	private static final int DEFAULT_VERSION = 110;
	private static final Pattern VERSION = Pattern.compile("^\\s*#\\s*version\\s+([0-9]+)", Pattern.MULTILINE);
	private static final Pattern OUT_COLOR = Pattern.compile("outColor([0-9]+)");
	private static final Set<String> PRECISION = Set.of("lowp", "mediump", "highp");

	private FragmentWriteScan() {
	}

	/**
	 * One fragment out declarator: its explicit location, its {@code outColor} index (each null when absent), and the
	 * number of locations it takes (its array length, 1 for a scalar).
	 */
	private record Out(Integer location, Integer named, int count) {
	}

	/**
	 * The draw buffers the stage writes, ascending. An empty set means the stage writes no target (it only discards, or has
	 * no output). Optional.empty means the scan cannot tell (see the class comment); the caller then keeps the first eight.
	 */
	static Optional<TreeSet<Integer>> of(String text) {
		List<Token> tokens;
		try {
			tokens = new Lexer(text, versionOf(text)).lex();
		} catch (GlslSyntaxException e) {
			return Optional.empty();
		}
		TreeSet<Integer> targets = new TreeSet<>();
		List<Out> outs = new ArrayList<>();
		List<Token> statement = new ArrayList<>();
		int brace = 0;
		int paren = 0;
		for (int i = 0; i < tokens.size(); i++) {
			Token t = tokens.get(i);
			if (t.kind() == TokenKind.DIRECTIVE || t.kind() == TokenKind.EOF) {
				continue;
			}
			if (isWord(t, "gl_FragColor")) {
				targets.add(0);
			} else if (isWord(t, "gl_FragData")) {
				OptionalInt index = literalIndex(tokens, i + 1);
				if (index.isEmpty() || index.getAsInt() >= DRAW_BUFFERS) {
					return Optional.empty();
				}
				targets.add(index.getAsInt());
			}
			if (t.kind() == TokenKind.LBRACE) {
				if (brace == 0) {
					statement.clear();  // a function, a struct or an interface block: no output declaration
				}
				brace++;
			} else if (t.kind() == TokenKind.RBRACE) {
				brace--;
				if (brace == 0) {
					statement.clear();
				}
			} else if (brace == 0) {
				if (t.kind() == TokenKind.SEMICOLON && paren == 0) {
					if (!declaration(statement, outs)) {
						return Optional.empty();
					}
					statement.clear();
				} else {
					if (t.kind() == TokenKind.LPAREN) {
						paren++;
					} else if (t.kind() == TokenKind.RPAREN) {
						paren--;
					}
					statement.add(t);
				}
			}
		}
		return locations(outs, targets) ? Optional.of(targets) : Optional.empty();
	}

	/**
	 * Adds the locations the outs take, explicit first, then named, then the first free run in declaration order. False when
	 * a location falls outside the draw buffers.
	 */
	private static boolean locations(List<Out> outs, TreeSet<Integer> targets) {
		int[] base = new int[outs.size()];
		Arrays.fill(base, -1);
		Set<Integer> claimed = new HashSet<>();
		for (int k = 0; k < outs.size(); k++) {
			Out out = outs.get(k);
			Integer fixed = out.location() != null ? out.location() : out.named();
			if (fixed == null) {
				continue;
			}
			base[k] = fixed;
			for (int j = 0; j < out.count(); j++) {
				claimed.add(fixed + j);
			}
		}
		for (int k = 0; k < outs.size(); k++) {
			if (base[k] >= 0) {
				continue;
			}
			Out out = outs.get(k);
			int first = 0;
			while (first < DRAW_BUFFERS && !free(claimed, first, out.count())) {
				first++;
			}
			if (first + out.count() > DRAW_BUFFERS) {
				return false;
			}
			base[k] = first;
			for (int j = 0; j < out.count(); j++) {
				claimed.add(first + j);
			}
		}
		for (int k = 0; k < outs.size(); k++) {
			for (int j = 0; j < outs.get(k).count(); j++) {
				int target = base[k] + j;
				if (target < 0 || target >= DRAW_BUFFERS) {
					return false;
				}
				targets.add(target);
			}
		}
		return true;
	}

	private static boolean free(Set<Integer> claimed, int first, int count) {
		for (int j = 0; j < count; j++) {
			if (claimed.contains(first + j)) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Reads one top-level statement: if it declares a fragment out (an {@code out} outside any parenthesis, so a parameter
	 * does not count), its declarators are added to {@code outs}. False when the declaration cannot be read.
	 */
	private static boolean declaration(List<Token> stmt, List<Out> outs) {
		int outAt = -1;
		int depth = 0;
		for (int i = 0; i < stmt.size(); i++) {
			Token t = stmt.get(i);
			if (t.kind() == TokenKind.LPAREN) {
				depth++;
			} else if (t.kind() == TokenKind.RPAREN) {
				depth--;
			} else if (depth == 0 && outAt < 0 && isWord(t, "out")) {
				outAt = i;
			}
		}
		if (outAt < 0) {
			return true;
		}
		Integer location = null;
		for (int i = 0; i < outAt; i++) {
			if (!isWord(stmt.get(i), "layout")) {
				continue;
			}
			if (i + 1 >= outAt || stmt.get(i + 1).kind() != TokenKind.LPAREN) {
				return false;
			}
			for (int j = i + 2; j < outAt && stmt.get(j).kind() != TokenKind.RPAREN; j++) {
				if (!isWord(stmt.get(j), "location")) {
					continue;
				}
				if (j + 2 >= outAt || stmt.get(j + 1).kind() != TokenKind.ASSIGN) {
					return false;
				}
				location = integer(stmt.get(j + 2));
				boolean ends = j + 3 < outAt && (stmt.get(j + 3).kind() == TokenKind.COMMA
					|| stmt.get(j + 3).kind() == TokenKind.RPAREN);
				if (location == null || !ends) {
					return false;
				}
				j += 2;
			}
		}
		int at = outAt + 1;
		while (at < stmt.size() && PRECISION.contains(stmt.get(at).text())) {
			at++;
		}
		if (at >= stmt.size()) {
			return false;
		}
		at++;  // the type
		int typeCount = 1;
		if (at < stmt.size() && stmt.get(at).kind() == TokenKind.LBRACKET) {
			if (at + 2 >= stmt.size() || stmt.get(at + 2).kind() != TokenKind.RBRACKET) {
				return false;
			}
			typeCount = arrayLength(stmt.get(at + 1));
			if (typeCount < 0) {
				return false;
			}
			at += 3;
		}
		for (List<Token> declarator : split(stmt.subList(at, stmt.size()))) {
			if (declarator.isEmpty() || declarator.get(0).kind() != TokenKind.IDENT) {
				return false;
			}
			int count = typeCount;
			if (declarator.size() > 1) {
				if (typeCount != 1 || declarator.size() != 4 || declarator.get(1).kind() != TokenKind.LBRACKET
					|| declarator.get(3).kind() != TokenKind.RBRACKET) {
					return false;
				}
				count = arrayLength(declarator.get(2));
				if (count < 0) {
					return false;
				}
			}
			Matcher named = OUT_COLOR.matcher(declarator.get(0).text());
			Integer index = null;
			if (named.matches()) {
				index = integer(declarator.get(0).text().substring("outColor".length()), false);
				if (index == null) {
					return false;
				}
			}
			outs.add(new Out(location, index, count));
		}
		return true;
	}

	/** The comma-separated parts of a declarator list. */
	private static List<List<Token>> split(List<Token> tokens) {
		List<List<Token>> parts = new ArrayList<>();
		List<Token> current = new ArrayList<>();
		for (Token t : tokens) {
			if (t.kind() == TokenKind.COMMA) {
				parts.add(current);
				current = new ArrayList<>();
			} else {
				current.add(t);
			}
		}
		if (!current.isEmpty()) {
			parts.add(current);
		}
		return parts;
	}

	/** The array length in a {@code [n]} bracket's integer token, or -1 when it is not a plain non-negative literal. */
	private static int arrayLength(Token t) {
		Integer n = integer(t);
		return n == null || n < 0 ? -1 : n;
	}

	/** The integer a literal token holds, or null when the token is not an integer literal. */
	private static Integer integer(Token t) {
		if (t.kind() != TokenKind.INT && t.kind() != TokenKind.UINT) {
			return null;
		}
		return integer(t.text(), true);
	}

	/** An integer literal's value (decimal, octal or hex, with an optional u suffix when {@code suffix}); null if malformed. */
	private static Integer integer(String text, boolean suffix) {
		String digits = suffix && (text.endsWith("u") || text.endsWith("U")) ? text.substring(0, text.length() - 1) : text;
		try {
			return Integer.decode(digits);
		} catch (NumberFormatException e) {
			return null;
		}
	}

	/**
	 * The literal index of a {@code [n]} that starts at {@code at} (a {@code gl_FragData} subscript), or empty when the
	 * subscript is not an integer literal.
	 */
	private static OptionalInt literalIndex(List<Token> tokens, int at) {
		if (at + 2 >= tokens.size() || tokens.get(at).kind() != TokenKind.LBRACKET
			|| tokens.get(at + 2).kind() != TokenKind.RBRACKET) {
			return OptionalInt.empty();
		}
		Integer n = integer(tokens.get(at + 1));
		return n == null || n < 0 ? OptionalInt.empty() : OptionalInt.of(n);
	}

	private static boolean isWord(Token t, String word) {
		return (t.kind() == TokenKind.IDENT || t.kind() == TokenKind.KEYWORD) && t.text().equals(word);
	}

	/** The GLSL version of the text's {@code #version} line (110 when there is none). */
	private static int versionOf(String text) {
		Matcher m = VERSION.matcher(text);
		return m.find() ? Integer.parseInt(m.group(1)) : DEFAULT_VERSION;
	}
}
