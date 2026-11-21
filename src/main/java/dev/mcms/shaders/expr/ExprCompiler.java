// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.expr;

import java.util.List;

/**
 * Name resolution, type checking and compilation in one pass. Semantics follow the OptiFine doc file shaders.properties
 * ("Custom uniforms") and the Iris custom-uniforms page (https://shaders.properties), which adds radians, degrees, exp2,
 * exp10, log2, log10, log(base, value), mix, edge, random(min, max), randomInt and vector arithmetic; every non-obvious
 * rule cites its source at the place that implements it.
 *
 * <p>Implicit conversions: int to float (the documented conversion, since OptiFine types constants and parameters as
 * floating point) and a scalar broadcast into the vector operators and the vector-capable functions. A float expression
 * never fills an int declaration: Iris 1.11.7 refuses one ("failed to resolve function divide ... to type int" for
 * {@code uniform.int.x=7 / 2}) and the uniform reads 0 (measured with the mcms-expr pack, plan.md E7).
 */
public final class ExprCompiler {
	private ExprCompiler() {
	}

	/** Parses and compiles against {@code scope}, without a constraint on the result type. */
	public static CompiledExpr compile(String text, ExprScope scope) {
		return compile(ExprParser.parse(text), text, scope, null);
	}

	/**
	 * Parses and compiles, constraining the result to the declared type of the uniform or variable
	 * ({@code uniform.<type>.<name>}). An int expression fills a float declaration (the documented conversion). Anything
	 * else that does not match, a float expression for an int declaration included (Iris refuses it, see the class
	 * comment), is a load-time error that names the expression and column.
	 */
	public static CompiledExpr compile(String text, ExprScope scope, ExprType expected) {
		return compile(ExprParser.parse(text), text, scope, expected);
	}

	public static CompiledExpr compile(Expr expr, String text, ExprScope scope, ExprType expected) {
		ExprNode root = new Checker(text, scope).check(expr);
		if (expected != null) {
			// The declared type decides, as the OptiFine doc does not declare expression types: int fills float (the
			// documented implicit conversion); a float never fills an int declaration (Iris refuses it, measured in E7).
			if (root.type() == ExprType.INT && expected == ExprType.FLOAT) {
				root = new ExprNode.IToF(root);
			} else if (root.type() != expected) {
				throw ExprError.at(text, expr.pos(), "expression has type " + root.type() + ", expected " + expected);
			}
		}
		return new CompiledExpr(root);
	}

	private static final class Checker {
		/**
		 * Unary functions that keep the int type for an int argument: abs, floor, ceil, round, signum. This is an engine
		 * decision, not a doc statement: the Iris custom-uniforms page says "Unless otherwise noted, each function can
		 * take in a float or int and outputs the same type", and names no exception for sin, so applying that to sin
		 * would make sin(int) an int. The OptiFine doc types every constant and parameter as floating point (sin(1) is
		 * 0.84). So the real-valued functions return float, and the functions whose result is exact on integers stay
		 * int. round is in the OptiFine function list; the Iris page does not list it.
		 */
		static final java.util.Set<String> SAME_TYPE_UNARY = java.util.Set.of("abs", "floor", "ceil", "round", "signum", "sign");
		/** Counts compiles so two uniforms with the same expression text seed different random() sequences. */
		private static final java.util.concurrent.atomic.AtomicLong COMPILES = new java.util.concurrent.atomic.AtomicLong();
		final String text;
		final ExprScope scope;
		final long salt = COMPILES.getAndIncrement();

		Checker(String text, ExprScope scope) {
			this.text = text;
			this.scope = scope;
		}

		ExprError err(int pos, String why) {
			return ExprError.at(text, pos, why);
		}

		/**
		 * Mixes a call-site key and a compile counter into one 64-bit seed. java.util.Random's first values are nearly
		 * equal for neighbouring seeds, so the counter goes through the splitmix64 finalizer (Steele, Lea, Flood 2014)
		 * before it seeds a generator. Only the seed is mixed; the generator is still java.util.Random.
		 */
		static long mixSeed(long key, long salt) {
			long z = key ^ (0x5EED_C0FEL << 32) ^ (salt * 0x9E3779B97F4A7C15L);
			z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
			z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
			return z ^ (z >>> 31);
		}

		ExprNode check(Expr e) {
			return switch (e) {
				case Expr.Bool(boolean value, int unusedPos) -> new ExprNode.ConstB(value);
			case Expr.Num(double value, boolean integral, int pos) -> integral && value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE
				? new ExprNode.ConstI((int) value)
				// The OptiFine doc types constants as floating point; very large integer literals (measured in the
				// corpus) stay exact as floats instead of wrapping into the int range.
				: new ExprNode.ConstF((float) value);
				case Expr.Name(String name, int pos) -> name(name, pos);
				case Expr.Access(Expr base, List<String> selectors, int pos) -> access(base, selectors, pos);
				case Expr.Unary(String op, Expr operand, int pos) -> unary(op, operand, pos);
				case Expr.Binary(String op, Expr left, Expr right, int pos) -> binary(op, left, right, pos);
				case Expr.Call(String function, List<Expr> args, int pos) -> call(function, args, pos);
			};
		}

		ExprNode name(String name, int pos) {
			// OptiFine doc, Constants: "pi - 3.1415926" (Math.PI here); true and false are literals of the grammar.
			if (name.equals("pi")) {
				return new ExprNode.ConstF((float) Math.PI);
			}
			ExprBinding b = scope.resolve(name);
			if (b == null) {
				throw err(pos, "unknown name '" + name + "'");
			}
			return switch (b) {
				case ExprBinding.Frame(int slot, ExprType t) -> switch (t) {
					case FLOAT -> new ExprNode.LoadF(slot);
					case INT -> new ExprNode.LoadI(slot);
					case BOOL -> new ExprNode.LoadB(slot);
					case VEC2, VEC3, VEC4 -> new ExprNode.LoadV(slot, t);
					// A matrix name alone has no value; it is read through an element access (column first, see below).
					case MAT3, MAT4 -> throw err(pos,
						"matrix '" + name + "' can only be read through an element access, like " + name + ".0.1");
				};
				case ExprBinding.ConstFloat(float v) -> new ExprNode.ConstF(v);
				case ExprBinding.ConstInt(int v) -> new ExprNode.ConstI(v);
				case ExprBinding.ConstBool(boolean v) -> new ExprNode.ConstB(v);
				case ExprBinding.ConstVec(ExprType t, float x, float y, float z, float w) -> new ExprNode.ConstV(t, x, y, z, w);
			};
		}

		ExprNode access(Expr base, List<String> selectors, int pos) {
			// Iris: "Swizzling is not supported, so only a single element can be pulled ... intermediate values cannot"
			// — element access applies to names only.
			if (!(base instanceof Expr.Name(String name, int namePos))) {
				throw err(pos, "element access (.x, .r, .0, ...) is only allowed on a uniform or variable name");
			}
			ExprBinding b = scope.resolve(name);
			if (b == null) {
				throw err(namePos, "unknown name '" + name + "'");
			}
			return switch (b) {
				case ExprBinding.Frame(int slot, ExprType t) -> {
					if (t.isVector()) {
						if (selectors.size() != 1) {
							throw err(pos, "a vector is read through exactly one element selector, like " + name + ".y");
						}
						// Iris: elements are ".xyzw, .rgba, stpq, and .0123".
						yield new ExprNode.ElemF(slot, component(selectors.get(0), t.components(), name, pos));
					}
					if (t.isMatrix()) {
						// name.A.B reads GLSL M[A][B]: column A, row B. The docs word this as "row and column index" (and
						// Iris's "gbufferModelView.0.3" as "1st row 4th column"); the engine reads column first, on the
						// corpus evidence cited at ExprFrame.setMat4 and engine-pack-format.md section 13.
						if (selectors.size() != 2) {
							throw err(pos, "matrix '" + name + "' is read as " + name + ".<column>.<row>, like " + name + ".0.1");
						}
						int column = matrixIndex(selectors.get(0), name, pos);
						int row = matrixIndex(selectors.get(1), name, pos);
						int cols = t.columns();
						if (column >= cols || row >= cols) {
							throw err(pos, "matrix '" + name + "' has no element ." + selectors.get(0) + "." + selectors.get(1));
						}
						yield new ExprNode.MatElemF(slot, column * cols + row);
					}
					throw err(namePos, "'" + name + "' has no elements to access");
				}
				case ExprBinding.ConstVec(ExprType t, float x, float y, float z, float w) -> {
					if (selectors.size() != 1) {
						throw err(pos, "a vector is read through exactly one element selector");
					}
					int c = component(selectors.get(0), t.components(), "constant", pos);
					yield new ExprNode.ConstF(switch (c) {
						case 0 -> x;
						case 1 -> y;
						case 2 -> z;
						default -> w;
					});
				}
				default -> throw err(namePos, "'" + name + "' has no elements to access");
			};
		}

		int component(String sel, int size, String name, int pos) {
			int c = switch (sel) {
				case "x", "r", "s", "0" -> 0;
				case "y", "g", "t", "1" -> 1;
				case "z", "b", "p", "2" -> 2;
				case "w", "a", "q", "3" -> 3;
				default -> -1;
			};
			if (c < 0 || c >= size) {
				throw err(pos, "unknown or out-of-range element '" + (sel.length() == 1 ? "." + sel : sel) + "' for " + name);
			}
			return c;
		}

		int matrixIndex(String sel, String name, int pos) {
			if (sel.length() != 1 || sel.charAt(0) < '0' || sel.charAt(0) > '9') {
				throw err(pos, "matrix '" + name + "' element indices are single digits");
			}
			return sel.charAt(0) - '0';
		}

		ExprNode unary(String op, Expr operand, int pos) {
			ExprNode n = check(operand);
			if (op.equals("!")) {
				if (n.type() != ExprType.BOOL) {
					throw err(pos, "'!' needs a bool, found " + n.type());
				}
				return new ExprNode.Not(n);
			}
			return switch (n.type()) {
				case FLOAT -> new ExprNode.NegF(n);
				case INT -> new ExprNode.NegI(n);
				case VEC2, VEC3, VEC4 -> new ExprNode.NegV(n, n.type());
				default -> throw err(pos, "'-' needs a number or a vector, found " + n.type());
			};
		}

		ExprNode binary(String op, Expr left, Expr right, int pos) {
			ExprNode l = check(left);
			ExprNode r = check(right);
			switch (op) {
				case "&&", "||":
					if (l.type() != ExprType.BOOL) {
						throw err(left.pos(), "operator '" + op + "' needs a bool on the left, found " + l.type());
					}
					if (r.type() != ExprType.BOOL) {
						throw err(right.pos(), "operator '" + op + "' needs a bool on the right, found " + r.type());
					}
					return op.equals("&&") ? new ExprNode.And(l, r) : new ExprNode.Or(l, r);
				case "==", "!=":
					if (l.type().isNumeric() && r.type().isNumeric()) {
						return compare(l, r, op);
					}
					if (l.type() == ExprType.BOOL && r.type() == ExprType.BOOL) {
						return new ExprNode.CmpB(op.equals("=="), l, r);
					}
					throw err(pos, "operator '" + op + "' needs two numbers or two bools (found " + l.type() + " and " + r.type() + ")");
				case "<", "<=", ">", ">=":
					if (!l.type().isNumeric() || !r.type().isNumeric()) {
						throw err(pos, "comparison '" + op + "' needs two numbers (found " + l.type() + " and " + r.type() + ")");
					}
					return compare(l, r, op);
				default: {
					ExprNode.Op o = ExprNode.op(op);
					if (l.type().isVector() || r.type().isVector()) {
						return binaryVector(o, l, r, left.pos(), right.pos());
					}
					if (!l.type().isNumeric()) {
						throw err(left.pos(), "operator '" + op + "' needs numbers or vectors, found " + l.type());
					}
					if (!r.type().isNumeric()) {
						throw err(right.pos(), "operator '" + op + "' needs numbers or vectors, found " + r.type());
					}
					// "/" on two ints is float division: OptiFine types every value as floating point (7 / 2 is 3.5).
					// "%", "+", "-", "*" stay exact in int when both sides are int.
					if (l.type() == ExprType.INT && r.type() == ExprType.INT && o != ExprNode.Op.DIV) {
						return new ExprNode.BinI(ExprNode.IntOp.valueOf(o.name()), l, r); // "/" is excluded just above
					}
					return new ExprNode.BinF(o, toFloat(l), toFloat(r));
				}
			}
		}

		ExprNode compare(ExprNode l, ExprNode r, String op) {
			ExprNode.Rel rel = ExprNode.rel(op);
			if (l.type() == ExprType.INT && r.type() == ExprType.INT) {
				return new ExprNode.CmpI(rel, l, r);
			}
			return new ExprNode.CmpF(rel, toFloat(l), toFloat(r));
		}

		ExprNode binaryVector(ExprNode.Op op, ExprNode l, ExprNode r, int lPos, int rPos) {
			ExprType t;
			if (l.type().isVector() && r.type().isVector()) {
				if (l.type() != r.type()) {
					throw err(rPos, "vector sizes must match: " + l.type() + " and " + r.type());
				}
				t = l.type();
			} else if (l.type().isVector()) {
				if (!r.type().isNumeric()) {
					throw err(rPos, "a vector combines with a vector or a number, found " + r.type());
				}
				t = l.type();
				r = toFloat(r);
			} else {
				if (!l.type().isNumeric()) {
					throw err(lPos, "a vector combines with a vector or a number, found " + l.type());
				}
				t = r.type();
				l = toFloat(l);
			}
			return new ExprNode.BinV(op, l, r, l.type().isVector(), r.type().isVector(), t);
		}

		ExprNode toFloat(ExprNode n) {
			return n.type() == ExprType.INT ? new ExprNode.IToF(n) : n;
		}

		ExprNode numericArg(Expr e) {
			ExprNode n = check(e);
			if (!n.type().isNumeric()) {
				throw err(e.pos(), "expected a number here, found " + n.type());
			}
			return n;
		}

		/** Converts int nodes to float so all arguments share one type; the caller has checked they are numeric. */
		ExprType unifyScalars(ExprNode[] ns) {
			boolean anyFloat = false;
			for (ExprNode n : ns) {
				if (n.type() == ExprType.FLOAT) {
					anyFloat = true;
				}
			}
			if (!anyFloat) {
				return ExprType.INT;
			}
			for (int k = 0; k < ns.length; k++) {
				if (ns[k].type() == ExprType.INT) {
					ns[k] = new ExprNode.IToF(ns[k]);
				}
			}
			return ExprType.FLOAT;
		}

		ExprNode call(String fn, List<Expr> args, int pos) {
			switch (fn) {
				case "vec2":
					return vecCtor(args, 2, pos);
				case "vec3":
					return vecCtor(args, 3, pos);
				case "vec4":
					return vecCtor(args, 4, pos);
				case "if":
					return ifExpr(args, pos, false);
				case "ifb":
					return ifExpr(args, pos, true);
				case "smooth":
					return smooth(args, pos);
				case "min":
					return minMax(args, pos, true);
				case "max":
					return minMax(args, pos, false);
				case "clamp":
					return clamp(args, pos);
				case "abs", "floor", "ceil":
					return absFloorCeil(fn, args, pos);
				case "lerp":
					return lerp(args, pos);
				case "mix":
					return mix(args, pos);
				case "edge":
					return edge(args, pos);
				case "between":
					return between(args, pos);
				case "equals":
					return equals(args, pos);
				case "in":
					return in(args, pos);
				case "random":
					return random(args, pos, false);
				case "randomInt":
					return random(args, pos, true);
				case "print":
					return print(args, pos);
				default:
					break;
			}
			// Two-argument math, and the two functions that change shape with two arguments.
			switch (fn) {
				case "atan2" -> {
					requireArgs(fn, args, 2, pos);
					return twoMath(ExprNode.F2.ATAN2, args, pos);
				}
				case "pow" -> {
					requireArgs(fn, args, 2, pos);
					return twoMath(ExprNode.F2.POW, args, pos);
				}
				case "fmod" -> {
					requireArgs(fn, args, 2, pos);
					return twoMath(ExprNode.F2.FMOD, args, pos);
				}
				case "atan" -> {
					if (args.size() == 2) { // Iris: "atan(y, x) / atan2(y, x)"
						return twoMath(ExprNode.F2.ATAN2, args, pos);
					}
				}
				case "log" -> {
					if (args.size() == 2) { // Iris: "log(base, value)"
						return twoMath(ExprNode.F2.LOGB, args, pos);
					}
				}
				default -> {
				}
			}
			ExprNode.F1 kind = unaryKind(fn, pos);
			requireArgs(fn, args, 1, pos);
			ExprNode n = numericArg(args.get(0));
			// OptiFine doc: constants and parameters are floating point, so a real-valued function returns float even for
			// an int argument (sin(1) is 0.84, not 0). The exact-integer functions in SAME_TYPE_UNARY keep int (engine rule).
			if (SAME_TYPE_UNARY.contains(fn) && n.type() == ExprType.INT) {
				return new ExprNode.UnI(kind, n);
			}
			return new ExprNode.UnF(kind, toFloat(n));
		}

		void requireArgs(String fn, List<Expr> args, int want, int pos) {
			if (args.size() != want) {
				throw err(pos, fn + " takes " + want + (want == 1 ? " argument" : " arguments") + ", found " + args.size());
			}
		}

		ExprNode.F1 unaryKind(String fn, int pos) {
			return switch (fn) {
				case "sin" -> ExprNode.F1.SIN;
				case "cos" -> ExprNode.F1.COS;
				case "asin" -> ExprNode.F1.ASIN;
				case "acos" -> ExprNode.F1.ACOS;
				case "tan" -> ExprNode.F1.TAN;
				case "atan" -> ExprNode.F1.ATAN;
				case "torad", "radians" -> ExprNode.F1.TORAD;
				case "todeg", "degrees" -> ExprNode.F1.TODEG;
				case "exp" -> ExprNode.F1.EXP;
				case "exp2" -> ExprNode.F1.EXP2;
				case "exp10" -> ExprNode.F1.EXP10;
				case "frac" -> ExprNode.F1.FRAC;
				case "log" -> ExprNode.F1.LOG;
				case "log2" -> ExprNode.F1.LOG2;
				case "log10" -> ExprNode.F1.LOG10;
				case "sqrt" -> ExprNode.F1.SQRT;
				case "abs" -> ExprNode.F1.ABS;
				case "round" -> ExprNode.F1.ROUND;
				case "signum", "sign" -> ExprNode.F1.SIGNUM;
				case "floor" -> ExprNode.F1.FLOOR;
				case "ceil" -> ExprNode.F1.CEIL;
				default -> throw err(pos, "unknown function '" + fn + "'");
			};
		}

		ExprNode twoMath(ExprNode.F2 kind, List<Expr> args, int pos) {
			ExprNode l = numericArg(args.get(0));
			ExprNode r = numericArg(args.get(1));
			// pow, atan2 and log(base, x) are real-valued (float for int arguments); fmod is the one that keeps int.
			if (kind == ExprNode.F2.FMOD && l.type() == ExprType.INT && r.type() == ExprType.INT) {
				return new ExprNode.FModI(l, r);
			}
			return new ExprNode.F2F(kind, toFloat(l), toFloat(r));
		}

		ExprNode vecCtor(List<Expr> args, int n, int pos) {
			if (args.size() != n) {
				throw err(pos, "vec" + n + " takes " + n + " numbers, found " + args.size());
			}
			ExprNode[] parts = new ExprNode[n];
			for (int k = 0; k < n; k++) {
				parts[k] = toFloat(numericArg(args.get(k)));
			}
			return new ExprNode.VecCtor(n, parts);
		}

		ExprNode ifExpr(List<Expr> args, int pos, boolean booleanValues) {
			String fn = booleanValues ? "ifb" : "if";
			if (args.size() < 3 || args.size() % 2 == 0) {
				// OptiFine doc: "if(cond, val, [cond2, val2, ...], val_else)".
				throw err(pos, fn + " takes an odd number of arguments (condition, value, ..., else), found " + args.size());
			}
			int pairs = (args.size() - 1) / 2;
			ExprNode[] conds = new ExprNode[pairs];
			ExprNode[] vals = new ExprNode[pairs + 1]; // last is else
			for (int k = 0; k < pairs; k++) {
				ExprNode c = check(args.get(k * 2));
				if (c.type() != ExprType.BOOL) {
					throw err(args.get(k * 2).pos(), "a condition of " + fn + " must be a bool, found " + c.type());
				}
				conds[k] = c;
				vals[k] = check(args.get(k * 2 + 1));
			}
			vals[pairs] = check(args.get(args.size() - 1));
			if (booleanValues) {
				for (int k = 0; k <= pairs; k++) {
					if (vals[k].type() != ExprType.BOOL) {
						throw err(pos, "ifb selects bool values (if selects numbers and vectors)");
					}
				}
				return new ExprNode.IfB(conds, java.util.Arrays.copyOf(vals, pairs), vals[pairs]);
			}
			// Unify the value types: numbers mix int/float; vectors must match exactly (the documented conversions only).
			ExprType t = vals[0].type();
			for (int k = 1; k <= pairs; k++) {
				ExprType kt = vals[k].type();
				if (kt == ExprType.BOOL) {
					throw err(pos, "if selects numbers or vectors; use ifb for bools");
				}
				if (t.isNumeric() && kt.isNumeric()) {
					if (kt == ExprType.FLOAT) {
						t = ExprType.FLOAT;
					}
				} else if (t != kt) {
					throw err(pos, "the values of if must have one type (found " + t + " and " + kt + ")");
				}
			}
			for (int k = 0; k <= pairs; k++) {
				vals[k] = t == ExprType.FLOAT ? toFloat(vals[k]) : vals[k];
			}
			ExprNode[] branchVals = java.util.Arrays.copyOf(vals, pairs);
			return switch (t) {
				case FLOAT -> new ExprNode.IfF(conds, branchVals, vals[pairs]);
				case INT -> new ExprNode.IfI(conds, branchVals, vals[pairs]);
				default -> new ExprNode.IfV(conds, branchVals, vals[pairs], t.components(), t);
			};
		}

		ExprNode smooth(List<Expr> args, int pos) {
			// OptiFine doc examples: "smooth(1, if(...), 5)" and "smooth(2, if(...), 0, 10)" — the optional id is the
			// leading integer literal; Iris: "smooth(if(...), 2, 0.25)" — without one, all arguments are value and fades.
			int id = -1;
			int valAt = 0;
			if (args.size() >= 2 && args.get(0) instanceof Expr.Num(double v, boolean integral, int unused) && integral) {
				id = (int) v;
				valAt = 1;
			}
			int fades = args.size() - valAt - 1;
			if (fades < 0 || fades > 2) {
				throw err(pos, "smooth takes ([id,] value[, fadeUp[, fadeDown]]), found " + args.size() + " arguments");
			}
			ExprNode val = check(args.get(valAt));
			if (val.type() == ExprType.BOOL) {
				throw err(args.get(valAt).pos(), "the value of smooth must be a number or a vector, found bool");
			}
			// Both docs give the default fade as 1 (Iris: one tick; OptiFine: "Default fade time is 1"). An omitted
			// fade takes that default, not the other fade's value.
			ExprNode up = fades >= 1 ? toFloat(numericArg(args.get(valAt + 1))) : new ExprNode.ConstF(1f);
			ExprNode down = fades == 2 ? toFloat(numericArg(args.get(valAt + 2))) : new ExprNode.ConstF(1f);
			if (val.type() == ExprType.INT) {
				val = toFloat(val);
			}
			return val.type().isVector()
				? new ExprNode.SmoothV(id, val, up, down, val.type())
				: new ExprNode.SmoothF(id, val, up, down);
		}

		ExprNode minMax(List<Expr> args, int pos, boolean min) {
			String fn = min ? "min" : "max";
			if (args.size() < 2) {
				throw err(pos, fn + " takes two or more arguments (OptiFine doc: \"min(x, y ,...)\")");
			}
			ExprNode[] ns = new ExprNode[args.size()];
			ExprType vec = null;
			for (int k = 0; k < args.size(); k++) {
				ns[k] = check(args.get(k));
				if (ns[k].type().isVector()) {
					if (vec == null) {
						vec = ns[k].type();
					} else if (ns[k].type() != vec) {
						throw err(args.get(k).pos(), fn + " vector arguments must have one size (found " + vec + " and " + ns[k].type() + ")");
					}
				} else if (!ns[k].type().isNumeric()) {
					throw err(args.get(k).pos(), fn + " takes numbers or vectors, found " + ns[k].type());
				}
			}
			if (vec != null) {
				boolean[] isVec = new boolean[ns.length];
				for (int k = 0; k < ns.length; k++) {
					isVec[k] = ns[k].type().isVector();
					if (!isVec[k]) {
						ns[k] = toFloat(ns[k]);
					}
				}
				return new ExprNode.MinMaxV(ns, isVec, min, vec);
			}
			ExprType t = unifyScalars(ns);
			return t == ExprType.INT ? new ExprNode.MinMaxI(ns, min) : new ExprNode.MinMaxF(ns, min);
		}

		ExprNode clamp(List<Expr> args, int pos) {
			if (args.size() != 3) {
				throw err(pos, "clamp takes (x, min, max)");
			}
			ExprNode x = check(args.get(0));
			ExprNode lo = check(args.get(1));
			ExprNode hi = check(args.get(2));
			if (x.type().isVector() || lo.type().isVector() || hi.type().isVector()) {
				ExprType t = x.type().isVector() ? x.type() : null;
				if (t == null) {
					throw err(args.get(0).pos(), "the value of clamp cannot be a scalar when the bounds are vectors");
				}
				if (lo.type().isVector() && lo.type() != t || hi.type().isVector() && hi.type() != t) {
					throw err(pos, "clamp vector arguments must have one size");
				}
				if (lo.type() == ExprType.BOOL || hi.type() == ExprType.BOOL) {
					throw err(pos, "clamp takes numbers or vectors");
				}
				if (!lo.type().isVector()) {
					lo = toFloat(lo);
				}
				if (!hi.type().isVector()) {
					hi = toFloat(hi);
				}
				return new ExprNode.ClampV(x, lo, hi, lo.type().isVector(), hi.type().isVector(), t);
			}
			if (!x.type().isNumeric() || !lo.type().isNumeric() || !hi.type().isNumeric()) {
				throw err(pos, "clamp takes numbers or vectors");
			}
			ExprNode[] triple = { x, lo, hi };
			ExprType t = unifyScalars(triple);
			return t == ExprType.INT ? new ExprNode.ClampI(triple[0], triple[1], triple[2])
				: new ExprNode.ClampF(triple[0], triple[1], triple[2]);
		}

		ExprNode absFloorCeil(String fn, List<Expr> args, int pos) {
			if (args.size() != 1) {
				throw err(pos, fn + " takes one argument");
			}
			ExprNode n = check(args.get(0));
			ExprNode.F1 kind = ExprNode.F1.valueOf(fn.toUpperCase(java.util.Locale.ROOT));
			if (n.type().isNumeric()) {
				return n.type() == ExprType.INT ? new ExprNode.UnI(kind, n) : new ExprNode.UnF(kind, n);
			}
			if (n.type().isVector()) {
				// Iris: abs, floor and ceil "can work with vector inputs/output".
				return new ExprNode.UnV(kind, n, n.type());
			}
			throw err(args.get(0).pos(), fn + " takes a number or a vector, found " + n.type());
		}

		ExprNode lerp(List<Expr> args, int pos) {
			if (args.size() != 3) {
				throw err(pos, "lerp takes (k, x, y)");
			}
			return new ExprNode.LerpF(toFloat(numericArg(args.get(0))), toFloat(numericArg(args.get(1))), toFloat(numericArg(args.get(2))));
		}

		ExprNode mix(List<Expr> args, int pos) {
			if (args.size() != 3) {
				throw err(pos, "mix takes (x, y, a)");
			}
			return new ExprNode.MixF(toFloat(numericArg(args.get(0))), toFloat(numericArg(args.get(1))), toFloat(numericArg(args.get(2))));
		}

		ExprNode edge(List<Expr> args, int pos) {
			if (args.size() != 2) {
				throw err(pos, "edge takes (k, x)");
			}
			ExprNode k = numericArg(args.get(0));
			ExprNode x = numericArg(args.get(1));
			ExprNode[] pair = { k, x };
			ExprType t = unifyScalars(pair);
			return t == ExprType.INT ? new ExprNode.EdgeI(pair[0], pair[1]) : new ExprNode.EdgeF(pair[0], pair[1]);
		}

		ExprNode between(List<Expr> args, int pos) {
			if (args.size() != 3) {
				throw err(pos, "between takes (x, min, max)");
			}
			ExprNode x = numericArg(args.get(0));
			ExprNode lo = numericArg(args.get(1));
			ExprNode hi = numericArg(args.get(2));
			ExprNode[] triple = { x, lo, hi };
			ExprType t = unifyScalars(triple);
			return t == ExprType.INT ? new ExprNode.BetweenI(triple[0], triple[1], triple[2])
				: new ExprNode.BetweenF(triple[0], triple[1], triple[2]);
		}

		ExprNode equals(List<Expr> args, int pos) {
			if (args.size() != 3) {
				throw err(pos, "equals takes (a, b, epsilon)");
			}
			ExprNode l = numericArg(args.get(0));
			ExprNode r = numericArg(args.get(1));
			ExprNode eps = toFloat(numericArg(args.get(2)));
			return new ExprNode.EqualsF(toFloat(l), toFloat(r), eps);
		}

		ExprNode in(List<Expr> args, int pos) {
			if (args.size() < 2) {
				throw err(pos, "in takes (x, value, ...)");
			}
			ExprNode[] ns = new ExprNode[args.size()];
			for (int k = 0; k < args.size(); k++) {
				ns[k] = numericArg(args.get(k));
			}
			ExprType t = unifyScalars(ns);
			ExprNode[] vals = java.util.Arrays.copyOfRange(ns, 1, ns.length);
			return t == ExprType.INT ? new ExprNode.InI(ns[0], vals) : new ExprNode.InF(ns[0], vals);
		}

		ExprNode random(List<Expr> args, int pos, boolean ints) {
			String fn = ints ? "randomInt" : "random";
			if (args.size() != 0 && args.size() != 2) {
				throw err(pos, fn + " takes no arguments or (min, max)");
			}
			// The seed mixes the text, the call position and the compile counter: identical expressions in two
			// uniforms get different sequences, and each sequence is stable for the life of the compiled expression.
			java.util.Random rnd = new java.util.Random(mixSeed(text.hashCode() * 31L + pos, salt));
			if (args.size() == 0) {
				return ints ? new ExprNode.RandomI(null, null, rnd) : new ExprNode.RandomF(null, null, rnd);
			}
			ExprNode lo = numericArg(args.get(0));
			ExprNode hi = numericArg(args.get(1));
			return ints ? new ExprNode.RandomI(lo, hi, rnd) : new ExprNode.RandomF(toFloat(lo), toFloat(hi), rnd);
		}

		ExprNode print(List<Expr> args, int pos) {
		if (args.size() != 3
			|| !(args.get(0) instanceof Expr.Num(double idV, boolean idInt, int unused1) && idInt)
			|| !(args.get(1) instanceof Expr.Num(double nV, boolean nInt, int unused2) && nInt)) {
			// OptiFine doc: "print(id, n, x)" — this engine logs a value at most once per second (W1-D).
			throw err(pos, "print takes (id, n, x) with id and n as integer literals");
		}
		int id = (int) idV;
		int everyN = (int) nV;
		ExprNode x = check(args.get(2));
		return switch (x.type()) {
			case INT -> new ExprNode.PrintI(id, everyN, x);
			case BOOL -> new ExprNode.PrintB(id, everyN, x);
			case VEC2, VEC3, VEC4 -> new ExprNode.PrintV(id, everyN, x, x.type().components(), x.type());
			default -> new ExprNode.PrintF(id, everyN, x);
		};
		}
	}
}
