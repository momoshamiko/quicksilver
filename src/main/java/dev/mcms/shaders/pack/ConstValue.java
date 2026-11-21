// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.pack;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The value of a GLSL {@code const} declaration after a small constant-expression evaluation: the declared type and the
 * components (a scalar has one; booleans are 1 and 0). Used for the configuration constants of engine-pack-format.md
 * section 8, which packs write as literals, vector constructors and simple arithmetic.
 */
public record ConstValue(String type, List<Double> values) {
	public ConstValue {
		values = List.copyOf(values);
	}

	/** Parses a right-hand side of the given declared type; empty when it is not a constant expression this evaluator knows. */
	public static Optional<ConstValue> parse(String type, String expression) {
		try {
			List<Double> v = new Evaluator(expression).evaluateAll();
			if (v.isEmpty()) return Optional.empty();
			return Optional.of(new ConstValue(type, v));
		} catch (IllegalArgumentException e) {
			return Optional.empty();
		}
	}

	public double asDouble() {
		return values.get(0);
	}

	public int asInt() {
		return (int) values.get(0).doubleValue();
	}

	public boolean asBoolean() {
		return values.get(0) != 0.0;
	}

	/** Recursive-descent evaluator: + - * / with unary signs, parentheses, numbers, true/false and constructor calls. */
	private static final class Evaluator {
		private final String s;
		private int pos;

		Evaluator(String s) {
			this.s = s.strip();
		}

		List<Double> evaluateAll() {
			if (s.isEmpty()) throw new IllegalArgumentException("empty");
			List<Double> v = expr();
			skipWs();
			if (pos != s.length()) throw new IllegalArgumentException("trailing text at " + pos);
			return v;
		}

		private List<Double> expr() {
			List<Double> left = term();
			while (true) {
				skipWs();
				if (peek('+')) {
					pos++;
					left = scalar(left, term(), '+');
				} else if (peek('-')) {
					pos++;
					left = scalar(left, term(), '-');
				} else {
					return left;
				}
			}
		}

		private List<Double> term() {
			List<Double> left = unary();
			while (true) {
				skipWs();
				if (peek('*')) {
					pos++;
					left = scalar(left, unary(), '*');
				} else if (peek('/')) {
					pos++;
					left = scalar(left, unary(), '/');
				} else {
					return left;
				}
			}
		}

		private List<Double> unary() {
			skipWs();
			if (peek('-')) {
				pos++;
				List<Double> v = unary();
				List<Double> out = new ArrayList<>(v.size());
				for (double d : v) out.add(-d);
				return out;
			}
			if (peek('+')) {
				pos++;
				return unary();
			}
			return primary();
		}

		private List<Double> primary() {
			skipWs();
			if (pos >= s.length()) throw new IllegalArgumentException("unexpected end");
			char c = s.charAt(pos);
			if (c == '(') {
				pos++;
				List<Double> v = expr();
				expect(')');
				return v;
			}
			if (Character.isDigit(c) || (c == '.' && pos + 1 < s.length() && Character.isDigit(s.charAt(pos + 1)))) {
				return List.of(number());
			}
			if (Character.isLetter(c) || c == '_') {
				String id = identifier();
				skipWs();
				if (peek('(')) {
					pos++;
					return call(id);
				}
				return switch (id) {
					case "true" -> List.of(1.0);
					case "false" -> List.of(0.0);
					default -> throw new IllegalArgumentException("unknown name " + id);
				};
			}
			throw new IllegalArgumentException("unexpected '" + c + "'");
		}

		private List<Double> call(String id) {
			List<Double> args = new ArrayList<>();
			skipWs();
			if (!peek(')')) {
				while (true) {
					args.addAll(expr());
					skipWs();
					if (peek(',')) {
						pos++;
						continue;
					}
					break;
				}
			}
			expect(')');
			return switch (id) {
				case "float", "int", "bool", "double" -> {
					if (args.size() != 1) throw new IllegalArgumentException(id + " takes one argument");
					yield List.of(id.equals("bool") ? (args.get(0) != 0.0 ? 1.0 : 0.0) : args.get(0));
				}
				default -> {
					int n = vectorSize(id);
					if (n == 0) throw new IllegalArgumentException("unknown function " + id);
					if (args.size() == 1) {
						List<Double> out = new ArrayList<>(n);
						for (int i = 0; i < n; i++) out.add(args.get(0));
						yield out;
					}
					if (args.size() != n) throw new IllegalArgumentException(id + " takes " + n + " components");
					yield args;
				}
			};
		}

		private static int vectorSize(String id) {
			String rest = id.startsWith("ivec") ? id.substring(4) : id.startsWith("bvec") ? id.substring(4)
				: id.startsWith("vec") ? id.substring(3) : null;
			if (rest == null) return 0;
			return switch (rest) {
				case "2" -> 2;
				case "3" -> 3;
				case "4" -> 4;
				default -> 0;
			};
		}

		private List<Double> scalar(List<Double> a, List<Double> b, char op) {
			if (a.size() != 1 && b.size() != 1 && a.size() != b.size()) {
				throw new IllegalArgumentException("component count mismatch");
			}
			int n = Math.max(a.size(), b.size());
			List<Double> out = new ArrayList<>(n);
			for (int i = 0; i < n; i++) {
				double x = a.get(a.size() == 1 ? 0 : i);
				double y = b.get(b.size() == 1 ? 0 : i);
				out.add(switch (op) {
					case '+' -> x + y;
					case '-' -> x - y;
					case '*' -> x * y;
					default -> x / y;
				});
			}
			return out;
		}

		private double number() {
			int start = pos;
			while (pos < s.length() && Character.isDigit(s.charAt(pos))) pos++;
			if (pos < s.length() && s.charAt(pos) == '.') {
				pos++;
				while (pos < s.length() && Character.isDigit(s.charAt(pos))) pos++;
			}
			if (pos < s.length() && (s.charAt(pos) == 'e' || s.charAt(pos) == 'E')) {
				int save = pos;
				pos++;
				if (pos < s.length() && (s.charAt(pos) == '+' || s.charAt(pos) == '-')) pos++;
				if (pos < s.length() && Character.isDigit(s.charAt(pos))) {
					while (pos < s.length() && Character.isDigit(s.charAt(pos))) pos++;
				} else {
					pos = save;
				}
			}
			double v = Double.parseDouble(s.substring(start, pos));
			if (pos < s.length() && (s.charAt(pos) == 'f' || s.charAt(pos) == 'F' || s.charAt(pos) == 'u' || s.charAt(pos) == 'U')) {
				pos++;
			}
			return v;
		}

		private String identifier() {
			int start = pos;
			while (pos < s.length() && (Character.isLetterOrDigit(s.charAt(pos)) || s.charAt(pos) == '_')) pos++;
			return s.substring(start, pos);
		}

		private void skipWs() {
			while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++;
		}

		private boolean peek(char c) {
			return pos < s.length() && s.charAt(pos) == c;
		}

		private void expect(char c) {
			skipWs();
			if (!peek(c)) throw new IllegalArgumentException("expected '" + c + "'");
			pos++;
		}
	}
}
