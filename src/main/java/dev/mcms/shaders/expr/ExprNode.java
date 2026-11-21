// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.expr;

/**
 * The compiled expression tree produced by {@link ExprCompiler}. Nodes are immutable after construction except the
 * per-call-site state the documented functions need between frames: {@code smooth} keeps its current value,
 * {@code random} its generator, {@code print} its last log time (OptiFine doc shaders.properties, "Custom uniforms").
 *
 * <p>Evaluation never allocates: scalars are primitives, vectors are written into caller arrays or the shared
 * {@link ExprEval} scratch, and names are already resolved to frame slots.
 */
abstract class ExprNode {
	/**
	 * The smooth() decay rate per fade unit: a fade time F approaches its target exponentially with the time constant
	 * F / 6.67 seconds (the remaining distance is multiplied by exp(-6.67 dt / F)). Measured against Iris 1.11.7 at 60 and at
	 * 120 frames per second (E7, mcms-expr's smooth cells on a 784-block step of cameraPosition.x, the dense and open scenes,
	 * three runs, results/2026-11-16-e7-exprfps60 and -exprfps): fades 100 to 400 fit 6.67 within half a quantisation level,
	 * and the cells were the same at both rates. Uncapped, Iris's smooth decays more slowly and unevenly (the same cells fit
	 * 6.2 at 390 and 4.6 to 5.1 at 530 to 830 frames per second). An inference that fits every probe (plan.md E7, smooth):
	 * Iris's rate is 6.93 (half-lives of F / 10 s) applied to frame times cut to whole milliseconds, which loses 4% at 60
	 * and at 120 frames per second and other amounts at other rates (none at 100, 14% at 144). The engine's smooth does not
	 * depend on the frame rate and keeps Iris's 60 and 120 answer; the bench compares smooth at maxFps 60. The Iris
	 * custom-uniforms page says "fade times are halflife in ticks", which would multiply by exp(-13.9 dt / F); Iris itself is
	 * the reference here.
	 */
	static final float FADE_RATE = 6.67f;

	abstract ExprType type();

	float evalF(ExprEval e) {
		throw new IllegalStateException(type() + " does not evaluate to a float");
	}

	int evalI(ExprEval e) {
		throw new IllegalStateException(type() + " does not evaluate to an int");
	}

	boolean evalB(ExprEval e) {
		throw new IllegalStateException(type() + " does not evaluate to a bool");
	}

	void evalV(ExprEval e, float[] out, int off) {
		throw new IllegalStateException(type() + " does not evaluate to a vector");
	}

	/** How many scratch vectors this node may hold at once; size the {@link ExprEval} with the maximum of a set. */
	int scratch() {
		return 0;
	}

	static int maxScratch(ExprNode... nodes) {
		int m = 0;
		for (ExprNode n : nodes) {
			if (n != null && n.scratch() > m) {
				m = n.scratch();
			}
		}
		return m;
	}

	static int maxScratch(ExprNode[]... nodeSets) {
		int m = 0;
		for (ExprNode[] set : nodeSets) {
			for (ExprNode n : set) {
				if (n != null && n.scratch() > m) {
					m = n.scratch();
				}
			}
		}
		return m;
	}

	// ------------------------------------------------------------------ scalars

	static final class ConstF extends ExprNode {
		final float v;

		ConstF(float v) {
			this.v = v;
		}

		@Override
		ExprType type() {
			return ExprType.FLOAT;
		}

		@Override
		float evalF(ExprEval e) {
			return v;
		}
	}

	static final class ConstI extends ExprNode {
		final int v;

		ConstI(int v) {
			this.v = v;
		}

		@Override
		ExprType type() {
			return ExprType.INT;
		}

		@Override
		int evalI(ExprEval e) {
			return v;
		}
	}

	static final class ConstB extends ExprNode {
		final boolean v;

		ConstB(boolean v) {
			this.v = v;
		}

		@Override
		ExprType type() {
			return ExprType.BOOL;
		}

		@Override
		boolean evalB(ExprEval e) {
			return v;
		}
	}

	static final class LoadF extends ExprNode {
		final int slot;

		LoadF(int slot) {
			this.slot = slot;
		}

		@Override
		ExprType type() {
			return ExprType.FLOAT;
		}

		@Override
		float evalF(ExprEval e) {
			return e.frame.floats[slot];
		}
	}

	static final class LoadI extends ExprNode {
		final int slot;

		LoadI(int slot) {
			this.slot = slot;
		}

		@Override
		ExprType type() {
			return ExprType.INT;
		}

		@Override
		int evalI(ExprEval e) {
			return e.frame.ints[slot];
		}
	}

	static final class LoadB extends ExprNode {
		final int slot;

		LoadB(int slot) {
			this.slot = slot;
		}

		@Override
		ExprType type() {
			return ExprType.BOOL;
		}

		@Override
		boolean evalB(ExprEval e) {
			return e.frame.bools[slot];
		}
	}

	/** One component of a frame vector: {@code cameraPosition.y}. */
	static final class ElemF extends ExprNode {
		final int slot;
		final int comp;

		ElemF(int slot, int comp) {
			this.slot = slot;
			this.comp = comp;
		}

		@Override
		ExprType type() {
			return ExprType.FLOAT;
		}

		@Override
		float evalF(ExprEval e) {
			return e.frame.vecs[slot * 4 + comp];
		}
	}

	/** One element of a frame matrix: {@code name.A.B} is column A, row B, the engine's reading (see ExprFrame.setMat4). */
	static final class MatElemF extends ExprNode {
		final int slot;
		final int index;

		MatElemF(int slot, int index) {
			this.slot = slot;
			this.index = index;
		}

		@Override
		ExprType type() {
			return ExprType.FLOAT;
		}

		@Override
		float evalF(ExprEval e) {
			return e.frame.mats[slot * 16 + index];
		}
	}

	/** Int to float: the conversion the documents need, since they type constants and parameters as floating point. */
	static final class IToF extends ExprNode {
		final ExprNode c;

		IToF(ExprNode c) {
			this.c = c;
		}

		@Override
		ExprType type() {
			return ExprType.FLOAT;
		}

		@Override
		float evalF(ExprEval e) {
			return c.evalI(e);
		}

		@Override
		int scratch() {
			return c.scratch();
		}
	}

	static final class NegF extends ExprNode {
		final ExprNode c;

		NegF(ExprNode c) {
			this.c = c;
		}

		@Override
		ExprType type() {
			return ExprType.FLOAT;
		}

		@Override
		float evalF(ExprEval e) {
			return -c.evalF(e);
		}

		@Override
		int scratch() {
			return c.scratch();
		}
	}

	static final class NegI extends ExprNode {
		final ExprNode c;

		NegI(ExprNode c) {
			this.c = c;
		}

		@Override
		ExprType type() {
			return ExprType.INT;
		}

		@Override
		int evalI(ExprEval e) {
			return -c.evalI(e);
		}
	}

	static final class Not extends ExprNode {
		final ExprNode c;

		Not(ExprNode c) {
			this.c = c;
		}

		@Override
		ExprType type() {
			return ExprType.BOOL;
		}

		@Override
		boolean evalB(ExprEval e) {
			return !c.evalB(e);
		}
	}

	enum Op { ADD, SUB, MUL, DIV, MOD }

	static float apply(Op op, float a, float b) {
		return switch (op) {
			case ADD -> a + b;
			case SUB -> a - b;
			case MUL -> a * b;
			case DIV -> a / b;
			case MOD -> a % b;
		};
	}

	/** Integer operators. "/" is absent on purpose: int / int is float division (BinF), so BinI never holds it. */
	enum IntOp { ADD, SUB, MUL, MOD }

	static int apply(IntOp op, int a, int b) {
		return switch (op) {
			case ADD -> a + b;
			case SUB -> a - b;
			case MUL -> a * b;
			// Remainder by zero gives 0: a bad expression must never throw during a frame.
			case MOD -> b == 0 ? 0 : a % b;
		};
	}

	static Op op(String s) {
		return switch (s) {
			case "+" -> Op.ADD;
			case "-" -> Op.SUB;
			case "*" -> Op.MUL;
			case "/" -> Op.DIV;
			case "%" -> Op.MOD;
			default -> throw new IllegalArgumentException(s);
		};
	}

	static final class BinF extends ExprNode {
		final Op op;
		final ExprNode l;
		final ExprNode r;

		BinF(Op op, ExprNode l, ExprNode r) {
			this.op = op;
			this.l = l;
			this.r = r;
		}

		@Override
		ExprType type() {
			return ExprType.FLOAT;
		}

		@Override
		float evalF(ExprEval e) {
			return apply(op, l.evalF(e), r.evalF(e));
		}
	}

	static final class BinI extends ExprNode {
		final IntOp op;
		final ExprNode l;
		final ExprNode r;

		BinI(IntOp op, ExprNode l, ExprNode r) {
			this.op = op;
			this.l = l;
			this.r = r;
		}

		@Override
		ExprType type() {
			return ExprType.INT;
		}

		@Override
		int evalI(ExprEval e) {
			return apply(op, l.evalI(e), r.evalI(e));
		}
	}

	enum Rel { LT, LE, GT, GE, EQ, NE }

	static boolean test(Rel rel, float a, float b) {
		return switch (rel) {
			case LT -> a < b;
			case LE -> a <= b;
			case GT -> a > b;
			case GE -> a >= b;
			case EQ -> a == b;
			case NE -> a != b;
		};
	}

	static boolean test(Rel rel, int a, int b) {
		return switch (rel) {
			case LT -> a < b;
			case LE -> a <= b;
			case GT -> a > b;
			case GE -> a >= b;
			case EQ -> a == b;
			case NE -> a != b;
		};
	}

	static Rel rel(String s) {
		return switch (s) {
			case "<" -> Rel.LT;
			case "<=" -> Rel.LE;
			case ">" -> Rel.GT;
			case ">=" -> Rel.GE;
			case "==" -> Rel.EQ;
			case "!=" -> Rel.NE;
			default -> throw new IllegalArgumentException(s);
		};
	}

	static final class CmpF extends ExprNode {
		final Rel rel;
		final ExprNode l;
		final ExprNode r;

		CmpF(Rel rel, ExprNode l, ExprNode r) {
			this.rel = rel;
			this.l = l;
			this.r = r;
		}

		@Override
		ExprType type() {
			return ExprType.BOOL;
		}

		@Override
		boolean evalB(ExprEval e) {
			return test(rel, l.evalF(e), r.evalF(e));
		}
	}

	static final class CmpI extends ExprNode {
		final Rel rel;
		final ExprNode l;
		final ExprNode r;

		CmpI(Rel rel, ExprNode l, ExprNode r) {
			this.rel = rel;
			this.l = l;
			this.r = r;
		}

		@Override
		ExprType type() {
			return ExprType.BOOL;
		}

		@Override
		boolean evalB(ExprEval e) {
			return test(rel, l.evalI(e), r.evalI(e));
		}
	}

	static final class CmpB extends ExprNode {
		final boolean eq;
		final ExprNode l;
		final ExprNode r;

		CmpB(boolean eq, ExprNode l, ExprNode r) {
			this.eq = eq;
			this.l = l;
			this.r = r;
		}

		@Override
		ExprType type() {
			return ExprType.BOOL;
		}

		@Override
		boolean evalB(ExprEval e) {
			return eq ? l.evalB(e) == r.evalB(e) : l.evalB(e) != r.evalB(e);
		}
	}

	static final class And extends ExprNode {
		final ExprNode l;
		final ExprNode r;

		And(ExprNode l, ExprNode r) {
			this.l = l;
			this.r = r;
		}

		@Override
		ExprType type() {
			return ExprType.BOOL;
		}

		@Override
		boolean evalB(ExprEval e) {
			return l.evalB(e) && r.evalB(e);
		}
	}

	static final class Or extends ExprNode {
		final ExprNode l;
		final ExprNode r;

		Or(ExprNode l, ExprNode r) {
			this.l = l;
			this.r = r;
		}

		@Override
		ExprType type() {
			return ExprType.BOOL;
		}

		@Override
		boolean evalB(ExprEval e) {
			return l.evalB(e) || r.evalB(e);
		}
	}

	/**
	 * The unary math functions. Real-valued ones return float even for an int argument; ABS, ROUND, SIGNUM, FLOOR and
	 * CEIL keep int for an int argument (engine rule, see {@code ExprCompiler.Checker.SAME_TYPE_UNARY}). UnI is only
	 * built for the latter five.
	 */
	enum F1 {
		SIN, COS, ASIN, ACOS, TAN, ATAN, TORAD, TODEG, EXP, EXP2, EXP10, FRAC, LOG, LOG2, LOG10, SQRT, ABS, ROUND, SIGNUM, FLOOR, CEIL;

		double apply(double x) {
			return switch (this) {
				case SIN -> Math.sin(x);
				case COS -> Math.cos(x);
				case ASIN -> Math.asin(x);
				case ACOS -> Math.acos(x);
				case TAN -> Math.tan(x);
				case ATAN -> Math.atan(x);
				case TORAD -> Math.toRadians(x);
				case TODEG -> Math.toDegrees(x);
				case EXP -> Math.exp(x);
				case EXP2 -> Math.pow(2.0, x);
				case EXP10 -> Math.pow(10.0, x);
				case FRAC -> x - Math.floor(x);
				case LOG -> Math.log(x);
				case LOG2 -> Math.log(x) / Math.log(2.0);
				case LOG10 -> Math.log10(x);
				case SQRT -> Math.sqrt(x);
				case ABS -> Math.abs(x);
				case ROUND -> Math.round(x);
				case SIGNUM -> Math.signum(x);
				case FLOOR -> Math.floor(x);
				case CEIL -> Math.ceil(x);
			};
		}
	}

	static final class UnF extends ExprNode {
		final F1 kind;
		final ExprNode c;

		UnF(F1 kind, ExprNode c) {
			this.kind = kind;
			this.c = c;
		}

		@Override
		ExprType type() {
			return ExprType.FLOAT;
		}

		@Override
		float evalF(ExprEval e) {
			return (float) kind.apply(c.evalF(e));
		}
	}

	static final class UnI extends ExprNode {
		final F1 kind;
		final ExprNode c;

		UnI(F1 kind, ExprNode c) {
			this.kind = kind;
			this.c = c;
		}

		@Override
		ExprType type() {
			return ExprType.INT;
		}

		@Override
		int evalI(ExprEval e) {
			int x = c.evalI(e);
			return switch (kind) {
				case ABS -> Math.abs(x);
				case SIGNUM -> Integer.signum(x);
				case FLOOR, CEIL, ROUND -> x; // an int is already whole
				// The compiler builds UnI only for the five functions above; anything else is a compiler bug.
				default -> throw new IllegalStateException(kind + " has no integer form");
			};
		}
	}

	/** The binary float math functions: pow, fmod, atan2, log(base, value). */
	enum F2 { POW, FMOD, ATAN2, LOGB }

	static final class F2F extends ExprNode {
		final F2 kind;
		final ExprNode l;
		final ExprNode r;

		F2F(F2 kind, ExprNode l, ExprNode r) {
			this.kind = kind;
			this.l = l;
			this.r = r;
		}

		@Override
		ExprType type() {
			return ExprType.FLOAT;
		}

		@Override
		float evalF(ExprEval e) {
			float a = l.evalF(e);
			float b = r.evalF(e);
			return switch (kind) {
				case POW -> (float) Math.pow(a, b);
				// OptiFine doc: fmod(x, y) is "Similar to Math.floorMod()" — the sign follows the divisor.
				// A zero divisor gives 0, not NaN: an expression error must never poison a frame.
				case FMOD -> b == 0f ? 0f : a - b * (float) Math.floor(a / b);
				case ATAN2 -> (float) Math.atan2(a, b);
				case LOGB -> (float) (Math.log(b) / Math.log(a)); // log(base, value)
			};
		}
	}

	/** Int fmod(x, y): the floor modulus, 0 for a zero divisor (the only two-argument function that keeps int). */
	static final class FModI extends ExprNode {
		final ExprNode l;
		final ExprNode r;

		FModI(ExprNode l, ExprNode r) {
			this.l = l;
			this.r = r;
		}

		@Override
		ExprType type() {
			return ExprType.INT;
		}

		@Override
		int evalI(ExprEval e) {
			int a = l.evalI(e);
			int b = r.evalI(e);
			// floorMod throws on a zero divisor; a bad expression must never throw during a frame.
			return b == 0 ? 0 : Math.floorMod(a, b);
		}
	}

	static final class MinMaxF extends ExprNode {
		final ExprNode[] args;
		final boolean min;

		MinMaxF(ExprNode[] args, boolean min) {
			this.args = args;
			this.min = min;
		}

		@Override
		ExprType type() {
			return ExprType.FLOAT;
		}

		@Override
		float evalF(ExprEval e) {
			float v = args[0].evalF(e);
			for (int k = 1; k < args.length; k++) {
				float a = args[k].evalF(e);
				v = min ? Math.min(v, a) : Math.max(v, a);
			}
			return v;
		}
	}

	static final class MinMaxI extends ExprNode {
		final ExprNode[] args;
		final boolean min;

		MinMaxI(ExprNode[] args, boolean min) {
			this.args = args;
			this.min = min;
		}

		@Override
		ExprType type() {
			return ExprType.INT;
		}

		@Override
		int evalI(ExprEval e) {
			int v = args[0].evalI(e);
			for (int k = 1; k < args.length; k++) {
				int a = args[k].evalI(e);
				v = min ? Math.min(v, a) : Math.max(v, a);
			}
			return v;
		}
	}

	static final class ClampF extends ExprNode {
		final ExprNode x;
		final ExprNode lo;
		final ExprNode hi;

		ClampF(ExprNode x, ExprNode lo, ExprNode hi) {
			this.x = x;
			this.lo = lo;
			this.hi = hi;
		}

		@Override
		ExprType type() {
			return ExprType.FLOAT;
		}

		@Override
		float evalF(ExprEval e) {
			return Math.max(lo.evalF(e), Math.min(hi.evalF(e), x.evalF(e)));
		}
	}

	static final class ClampI extends ExprNode {
		final ExprNode x;
		final ExprNode lo;
		final ExprNode hi;

		ClampI(ExprNode x, ExprNode lo, ExprNode hi) {
			this.x = x;
			this.lo = lo;
			this.hi = hi;
		}

		@Override
		ExprType type() {
			return ExprType.INT;
		}

		@Override
		int evalI(ExprEval e) {
			return Math.max(lo.evalI(e), Math.min(hi.evalI(e), x.evalI(e)));
		}
	}

	/** Iris: edge(k, x) is "0 if x < k, otherwise 1" (GLSL step). */
	static final class EdgeF extends ExprNode {
		final ExprNode k;
		final ExprNode x;

		EdgeF(ExprNode k, ExprNode x) {
			this.k = k;
			this.x = x;
		}

		@Override
		ExprType type() {
			return ExprType.FLOAT;
		}

		@Override
		float evalF(ExprEval e) {
			return x.evalF(e) < k.evalF(e) ? 0f : 1f;
		}
	}

	static final class EdgeI extends ExprNode {
		final ExprNode k;
		final ExprNode x;

		EdgeI(ExprNode k, ExprNode x) {
			this.k = k;
			this.x = x;
		}

		@Override
		ExprType type() {
			return ExprType.INT;
		}

		@Override
		int evalI(ExprEval e) {
			return x.evalI(e) < k.evalI(e) ? 0 : 1;
		}
	}

	/** OptiFine doc: lerp(k, x, y), "Linear interpolation between X and Y" — k comes first. */
	static final class LerpF extends ExprNode {
		final ExprNode k;
		final ExprNode x;
		final ExprNode y;

		LerpF(ExprNode k, ExprNode x, ExprNode y) {
			this.k = k;
			this.x = x;
			this.y = y;
		}

		@Override
		ExprType type() {
			return ExprType.FLOAT;
		}

		@Override
		float evalF(ExprEval e) {
			float from = x.evalF(e);
			return from + (y.evalF(e) - from) * k.evalF(e);
		}
	}

	/** Iris: mix(x, y, a), the factor last, same value as lerp(a, x, y). */
	static final class MixF extends ExprNode {
		final ExprNode x;
		final ExprNode y;
		final ExprNode a;

		MixF(ExprNode x, ExprNode y, ExprNode a) {
			this.x = x;
			this.y = y;
			this.a = a;
		}

		@Override
		ExprType type() {
			return ExprType.FLOAT;
		}

		@Override
		float evalF(ExprEval e) {
			float from = x.evalF(e);
			return from + (y.evalF(e) - from) * a.evalF(e);
		}
	}

	/** if/ifb select the first value whose condition holds; later branches are not evaluated. */
	static final class IfF extends ExprNode {
		final ExprNode[] conds;
		final ExprNode[] vals;
		final ExprNode elseV;

		IfF(ExprNode[] conds, ExprNode[] vals, ExprNode elseV) {
			this.conds = conds;
			this.vals = vals;
			this.elseV = elseV;
		}

		@Override
		ExprType type() {
			return ExprType.FLOAT;
		}

		@Override
		float evalF(ExprEval e) {
			for (int k = 0; k < conds.length; k++) {
				if (conds[k].evalB(e)) {
					return vals[k].evalF(e);
				}
			}
			return elseV.evalF(e);
		}

		@Override
		int scratch() {
			return Math.max(maxScratch(conds, vals), elseV.scratch());
		}
	}

	static final class IfI extends ExprNode {
		final ExprNode[] conds;
		final ExprNode[] vals;
		final ExprNode elseV;

		IfI(ExprNode[] conds, ExprNode[] vals, ExprNode elseV) {
			this.conds = conds;
			this.vals = vals;
			this.elseV = elseV;
		}

		@Override
		ExprType type() {
			return ExprType.INT;
		}

		@Override
		int evalI(ExprEval e) {
			for (int k = 0; k < conds.length; k++) {
				if (conds[k].evalB(e)) {
					return vals[k].evalI(e);
				}
			}
			return elseV.evalI(e);
		}

		@Override
		int scratch() {
			return Math.max(maxScratch(conds, vals), elseV.scratch());
		}
	}

	static final class IfB extends ExprNode {
		final ExprNode[] conds;
		final ExprNode[] vals;
		final ExprNode elseV;

		IfB(ExprNode[] conds, ExprNode[] vals, ExprNode elseV) {
			this.conds = conds;
			this.vals = vals;
			this.elseV = elseV;
		}

		@Override
		ExprType type() {
			return ExprType.BOOL;
		}

		@Override
		boolean evalB(ExprEval e) {
			for (int k = 0; k < conds.length; k++) {
				if (conds[k].evalB(e)) {
					return vals[k].evalB(e);
				}
			}
			return elseV.evalB(e);
		}

		@Override
		int scratch() {
			return Math.max(maxScratch(conds, vals), elseV.scratch());
		}
	}

	static final class IfV extends ExprNode {
		final ExprNode[] conds;
		final ExprNode[] vals;
		final ExprNode elseV;
		final int n;
		final ExprType t;

		IfV(ExprNode[] conds, ExprNode[] vals, ExprNode elseV, int n, ExprType t) {
			this.conds = conds;
			this.vals = vals;
			this.elseV = elseV;
			this.n = n;
			this.t = t;
		}

		@Override
		ExprType type() {
			return t;
		}

		@Override
		void evalV(ExprEval e, float[] out, int off) {
			for (int k = 0; k < conds.length; k++) {
				if (conds[k].evalB(e)) {
					vals[k].evalV(e, out, off);
					return;
				}
			}
			elseV.evalV(e, out, off);
		}

		@Override
		int scratch() {
			return Math.max(maxScratch(conds, vals), elseV.scratch());
		}
	}

	/** in(x, a, b, ...): true when x equals one of the candidates (OptiFine doc, Boolean functions). */
	static final class InF extends ExprNode {
		final ExprNode x;
		final ExprNode[] vals;

		InF(ExprNode x, ExprNode[] vals) {
			this.x = x;
			this.vals = vals;
		}

		@Override
		ExprType type() {
			return ExprType.BOOL;
		}

		@Override
		boolean evalB(ExprEval e) {
			float v = x.evalF(e);
			for (ExprNode val : vals) {
				if (v == val.evalF(e)) {
					return true;
				}
			}
			return false;
		}
	}

	static final class InI extends ExprNode {
		final ExprNode x;
		final ExprNode[] vals;

		InI(ExprNode x, ExprNode[] vals) {
			this.x = x;
			this.vals = vals;
		}

		@Override
		ExprType type() {
			return ExprType.BOOL;
		}

		@Override
		boolean evalB(ExprEval e) {
			int v = x.evalI(e);
			for (ExprNode val : vals) {
				if (v == val.evalI(e)) {
					return true;
				}
			}
			return false;
		}
	}

	/** Iris: between(a, min, max) is "true if a is between min and max inclusive". */
	static final class BetweenF extends ExprNode {
		final ExprNode x;
		final ExprNode lo;
		final ExprNode hi;

		BetweenF(ExprNode x, ExprNode lo, ExprNode hi) {
			this.x = x;
			this.lo = lo;
			this.hi = hi;
		}

		@Override
		ExprType type() {
			return ExprType.BOOL;
		}

		@Override
		boolean evalB(ExprEval e) {
			float v = x.evalF(e);
			return v >= lo.evalF(e) && v <= hi.evalF(e);
		}
	}

	static final class BetweenI extends ExprNode {
		final ExprNode x;
		final ExprNode lo;
		final ExprNode hi;

		BetweenI(ExprNode x, ExprNode lo, ExprNode hi) {
			this.x = x;
			this.lo = lo;
			this.hi = hi;
		}

		@Override
		ExprType type() {
			return ExprType.BOOL;
		}

		@Override
		boolean evalB(ExprEval e) {
			int v = x.evalI(e);
			return v >= lo.evalI(e) && v <= hi.evalI(e);
		}
	}

	/** OptiFine doc: equals(a, b, epsilon), "Compare two float values with error margin". */
	static final class EqualsF extends ExprNode {
		final ExprNode l;
		final ExprNode r;
		final ExprNode eps;

		EqualsF(ExprNode l, ExprNode r, ExprNode eps) {
			this.l = l;
			this.r = r;
			this.eps = eps;
		}


		@Override
		ExprType type() {
			return ExprType.BOOL;
		}

		@Override
		boolean evalB(ExprEval e) {
			return Math.abs(l.evalF(e) - r.evalF(e)) <= eps.evalF(e);
		}
	}

	/**
	 * smooth([id,] value, [fadeUp[, fadeDown]]): exponential approach with the time constant fade / {@link #FADE_RATE}
	 * seconds (Iris's measured timing), each fade defaulting to 1. fadeUp applies while the value rises, fadeDown while it
	 * falls. State is per call site; ids are documented unique per smoother.
	 */
	static final class SmoothF extends ExprNode {
		final int id;
		final ExprNode val;
		final ExprNode up;
		final ExprNode down;
		float cur;
		boolean started;

		SmoothF(int id, ExprNode val, ExprNode up, ExprNode down) {
			this.id = id;
			this.val = val;
			this.up = up;
			this.down = down;
		}

		@Override
		ExprType type() {
			return ExprType.FLOAT;
		}

		@Override
		float evalF(ExprEval e) {
			float target = val.evalF(e);
			if (!started) {
				started = true;
				cur = target;
				return cur;
			}
			// A non-finite current value (a bad target earlier in the run) must not stick forever.
			if (!isFinite(cur)) {
				cur = target;
				return cur;
			}
			float dt = e.deltaTime;
			if (dt <= 0f || target == cur) {
				return cur;
			}
			float fade = target > cur ? up.evalF(e) : down.evalF(e);
			if (fade <= 0f) {
				cur = target;
				return cur;
			}
			cur = target + (cur - target) * (float) Math.exp(-FADE_RATE * dt / fade);
			return cur;
		}

		static boolean isFinite(float v) {
			return v == v && v != Float.POSITIVE_INFINITY && v != Float.NEGATIVE_INFINITY;
		}
	}

	/** random() / random(min, max): a new value every frame (Iris); state (the generator) is per call site. */
	static final class RandomF extends ExprNode {
		final ExprNode min;
		final ExprNode max;
		final java.util.Random rnd;

		RandomF(ExprNode min, ExprNode max, java.util.Random rnd) {
			this.min = min;
			this.max = max;
			this.rnd = rnd;
		}

		@Override
		ExprType type() {
			return ExprType.FLOAT;
		}

		@Override
		float evalF(ExprEval e) {
			if (min == null) {
				return rnd.nextFloat();
			}
			float lo = min.evalF(e);
			float hi = max.evalF(e);
			return lo + (hi - lo) * rnd.nextFloat();
		}
	}

	/** randomInt() / randomInt(min, max): between min (inclusive) and max (exclusive). */
	static final class RandomI extends ExprNode {
		final ExprNode min;
		final ExprNode max;
		final java.util.Random rnd;

		RandomI(ExprNode min, ExprNode max, java.util.Random rnd) {
			this.min = min;
			this.max = max;
			this.rnd = rnd;
		}

		@Override
		ExprType type() {
			return ExprType.INT;
		}

		@Override
		int evalI(ExprEval e) {
			if (min == null) {
				return rnd.nextInt();
			}
			int lo = min.type() == ExprType.INT ? min.evalI(e) : (int) min.evalF(e);
			int hi = max.type() == ExprType.INT ? max.evalI(e) : (int) max.evalF(e);
			// The range is measured in longs: the full int range is (max - min + 1) values and must not overflow
			// nextInt, and an empty or single-value range returns min instead of throwing.
			long range = (long) hi - lo;
			return range <= 0 ? lo : lo + (int) rnd.nextLong(range);
		}
	}

	// ------------------------------------------------------------------ print (at most once per second, W1-D)

	static final class PrintF extends ExprNode {
		final int id;
		final int everyN;
		final ExprNode c;
		float last = Float.NEGATIVE_INFINITY;

		PrintF(int id, int everyN, ExprNode c) {
			this.id = id;
			this.everyN = everyN;
			this.c = c;
		}

		@Override
		ExprType type() {
			return ExprType.FLOAT;
		}

		@Override
		float evalF(ExprEval e) {
			float v = c.evalF(e);
			if (e.print != null && e.timeSeconds - last >= 1.0f) {
				last = e.timeSeconds;
				e.print.print(id, everyN, Float.toString(v));
			}
			return v;
		}

		@Override
		int scratch() {
			return c.scratch();
		}
	}

	static final class PrintI extends ExprNode {
		final int id;
		final int everyN;
		final ExprNode c;
		float last = Float.NEGATIVE_INFINITY;

		PrintI(int id, int everyN, ExprNode c) {
			this.id = id;
			this.everyN = everyN;
			this.c = c;
		}

		@Override
		ExprType type() {
			return ExprType.INT;
		}

		@Override
		int evalI(ExprEval e) {
			int v = c.evalI(e);
			if (e.print != null && e.timeSeconds - last >= 1.0f) {
				last = e.timeSeconds;
				e.print.print(id, everyN, Integer.toString(v));
			}
			return v;
		}
	}

	static final class PrintB extends ExprNode {
		final int id;
		final int everyN;
		final ExprNode c;
		float last = Float.NEGATIVE_INFINITY;

		PrintB(int id, int everyN, ExprNode c) {
			this.id = id;
			this.everyN = everyN;
			this.c = c;
		}

		@Override
		ExprType type() {
			return ExprType.BOOL;
		}

		@Override
		boolean evalB(ExprEval e) {
			boolean v = c.evalB(e);
			if (e.print != null && e.timeSeconds - last >= 1.0f) {
				last = e.timeSeconds;
				e.print.print(id, everyN, Boolean.toString(v));
			}
			return v;
		}
	}

	static final class PrintV extends ExprNode {
		final int id;
		final int everyN;
		final ExprNode c;
		final int n;
		final ExprType t;
		float last = Float.NEGATIVE_INFINITY;

		PrintV(int id, int everyN, ExprNode c, int n, ExprType t) {
			this.id = id;
			this.everyN = everyN;
			this.c = c;
			this.n = n;
			this.t = t;
		}

		@Override
		ExprType type() {
			return t;
		}

		@Override
		void evalV(ExprEval e, float[] out, int off) {
			c.evalV(e, out, off);
			if (e.print != null && e.timeSeconds - last >= 1.0f) {
				last = e.timeSeconds;
				StringBuilder b = new StringBuilder(n * 8);
				for (int k = 0; k < n; k++) {
					if (k > 0) {
						b.append(' ');
					}
					b.append(out[off + k]);
				}
				e.print.print(id, everyN, b.toString());
			}
		}

		@Override
		int scratch() {
			return c.scratch();
		}
	}

	// ------------------------------------------------------------------ vectors

	static final class ConstV extends ExprNode {
		final ExprType t;
		final int n;
		final float x;
		final float y;
		final float z;
		final float w;

		ConstV(ExprType t, float x, float y, float z, float w) {
			this.t = t;
			this.n = t.components();
			this.x = x;
			this.y = y;
			this.z = z;
			this.w = w;
		}

		@Override
		ExprType type() {
			return t;
		}

		@Override
		void evalV(ExprEval e, float[] out, int off) {
			out[off] = x;
			if (n > 1) {
				out[off + 1] = y;
			}
			if (n > 2) {
				out[off + 2] = z;
			}
			if (n > 3) {
				out[off + 3] = w;
			}
		}
	}

	static final class LoadV extends ExprNode {
		final int slot;
		final int n;
		final ExprType t;

		LoadV(int slot, ExprType t) {
			this.slot = slot;
			this.n = t.components();
			this.t = t;
		}

		@Override
		ExprType type() {
			return t;
		}

		@Override
		void evalV(ExprEval e, float[] out, int off) {
			System.arraycopy(e.frame.vecs, slot * 4, out, off, n);
		}
	}

	static final class VecCtor extends ExprNode {
		final int n;
		final ExprType t;
		final ExprNode[] parts;

		VecCtor(int n, ExprNode[] parts) {
			this.n = n;
			this.t = switch (n) {
				case 2 -> ExprType.VEC2;
				case 3 -> ExprType.VEC3;
				default -> ExprType.VEC4;
			};
			this.parts = parts;
		}

		@Override
		ExprType type() {
			return t;
		}

		@Override
		void evalV(ExprEval e, float[] out, int off) {
			for (int k = 0; k < n; k++) {
				out[off + k] = parts[k].evalF(e);
			}
		}

		@Override
		int scratch() {
			return maxScratch(parts);
		}
	}

	static final class NegV extends ExprNode {
		final ExprNode c;
		final int n;
		final ExprType t;

		NegV(ExprNode c, ExprType t) {
			this.c = c;
			this.n = t.components();
			this.t = t;
		}

		@Override
		ExprType type() {
			return t;
		}

		@Override
		void evalV(ExprEval e, float[] out, int off) {
			c.evalV(e, out, off);
			for (int k = 0; k < n; k++) {
				out[off + k] = -out[off + k];
			}
		}

		@Override
		int scratch() {
			return c.scratch();
		}
	}

	/** Component-wise vector arithmetic with scalar broadcast (Iris: the operators work on "float/int/vector"). */
	static final class BinV extends ExprNode {
		final Op op;
		final ExprNode a;
		final ExprNode b;
		final boolean av;
		final boolean bv;
		final int n;
		final ExprType t;

		BinV(Op op, ExprNode a, ExprNode b, boolean av, boolean bv, ExprType t) {
			this.op = op;
			this.a = a;
			this.b = b;
			this.av = av;
			this.bv = bv;
			this.n = t.components();
			this.t = t;
		}

		@Override
		ExprType type() {
			return t;
		}

		@Override
		void evalV(ExprEval e, float[] out, int off) {
			float sa = 0f;
			float sb = 0f;
			int tb = -1;
			if (av) {
				a.evalV(e, out, off);
			} else {
				sa = a.evalF(e);
			}
			if (bv) {
				tb = e.vecPush();
				b.evalV(e, e.vecScratch, tb);
			} else {
				sb = b.evalF(e);
			}
			for (int k = 0; k < n; k++) {
				float x = av ? out[off + k] : sa;
				float y = bv ? e.vecScratch[tb + k] : sb;
				out[off + k] = apply(op, x, y);
			}
			if (bv) {
				e.vecPop();
			}
		}

		@Override
		int scratch() {
			// A vector right operand is pushed before it evaluates, so its own scratch nests under that push.
			return Math.max(a.scratch(), b.scratch() + (bv ? 1 : 0));
		}
	}

	/** abs, floor and ceil on vectors (Iris lists exactly these as vector-capable besides min, max, clamp, smooth). */
	static final class UnV extends ExprNode {
		final F1 kind;
		final ExprNode c;
		final int n;
		final ExprType t;

		UnV(F1 kind, ExprNode c, ExprType t) {
			this.kind = kind;
			this.c = c;
			this.n = t.components();
			this.t = t;
		}

		@Override
		ExprType type() {
			return t;
		}

		@Override
		void evalV(ExprEval e, float[] out, int off) {
			c.evalV(e, out, off);
			for (int k = 0; k < n; k++) {
				out[off + k] = (float) kind.apply(out[off + k]);
			}
		}

		@Override
		int scratch() {
			return c.scratch();
		}
	}

	static final class MinMaxV extends ExprNode {
		final ExprNode[] args;
		final boolean[] isVec;
		final boolean min;
		final int n;
		final ExprType t;

		MinMaxV(ExprNode[] args, boolean[] isVec, boolean min, ExprType t) {
			this.args = args;
			this.isVec = isVec;
			this.min = min;
			this.n = t.components();
			this.t = t;
		}

		@Override
		ExprType type() {
			return t;
		}

		@Override
		void evalV(ExprEval e, float[] out, int off) {
			boolean have = false;
			for (int k = 0; k < args.length; k++) {
				if (isVec[k]) {
					if (!have) {
						args[k].evalV(e, out, off);
						have = true;
						continue;
					}
					int t = e.vecPush();
					args[k].evalV(e, e.vecScratch, t);
					for (int c = 0; c < n; c++) {
						float v = e.vecScratch[t + c];
						out[off + c] = min ? Math.min(out[off + c], v) : Math.max(out[off + c], v);
					}
					e.vecPop();
				} else {
					float s = args[k].evalF(e);
					if (!have) {
						for (int c = 0; c < n; c++) {
							out[off + c] = s;
						}
						have = true;
					} else {
						for (int c = 0; c < n; c++) {
							out[off + c] = min ? Math.min(out[off + c], s) : Math.max(out[off + c], s);
						}
					}
				}
			}
		}

		@Override
		int scratch() {
			return maxScratch(args) + 1;
		}
	}

	static final class ClampV extends ExprNode {
		final ExprNode x;
		final ExprNode lo;
		final ExprNode hi;
		final boolean lov;
		final boolean hiv;
		final int n;
		final ExprType t;

		ClampV(ExprNode x, ExprNode lo, ExprNode hi, boolean lov, boolean hiv, ExprType t) {
			this.x = x;
			this.lo = lo;
			this.hi = hi;
			this.lov = lov;
			this.hiv = hiv;
			this.n = t.components();
			this.t = t;
		}

		@Override
		ExprType type() {
			return t;
		}

		@Override
		void evalV(ExprEval e, float[] out, int off) {
			x.evalV(e, out, off);
			float sl = 0f;
			float sh = 0f;
			int tl = -1;
			int th = -1;
			if (lov) {
				tl = e.vecPush();
				lo.evalV(e, e.vecScratch, tl);
			} else {
				sl = lo.evalF(e);
			}
			if (hiv) {
				th = e.vecPush();
				hi.evalV(e, e.vecScratch, th);
			} else {
				sh = hi.evalF(e);
			}
			for (int k = 0; k < n; k++) {
				float l = lov ? e.vecScratch[tl + k] : sl;
				float h = hiv ? e.vecScratch[th + k] : sh;
				out[off + k] = Math.max(l, Math.min(h, out[off + k]));
			}
			if (hiv) {
				e.vecPop();
			}
			if (lov) {
				e.vecPop();
			}
		}

		@Override
		int scratch() {
			return maxScratch(x, lo, hi) + (lov ? 1 : 0) + (hiv ? 1 : 0);
		}
	}

	static final class SmoothV extends ExprNode {
		final int id;
		final ExprNode val;
		final ExprNode up;
		final ExprNode down;
		final int n;
		final ExprType t;
		final float[] cur = new float[4];
		boolean started;

		SmoothV(int id, ExprNode val, ExprNode up, ExprNode down, ExprType t) {
			this.id = id;
			this.val = val;
			this.up = up;
			this.down = down;
			this.n = t.components();
			this.t = t;
		}

		@Override
		ExprType type() {
			return t;
		}

		@Override
		void evalV(ExprEval e, float[] out, int off) {
			int t = e.vecPush();
			val.evalV(e, e.vecScratch, t);
			if (!started) {
				started = true;
				for (int k = 0; k < n; k++) {
					cur[k] = e.vecScratch[t + k];
				}
			} else if (e.deltaTime > 0f) {
				for (int k = 0; k < n; k++) {
					float target = e.vecScratch[t + k];
					if (!SmoothF.isFinite(cur[k])) { // like the scalar case: recover instead of sticking
						cur[k] = target;
						continue;
					}
					if (target == cur[k]) {
						continue;
					}
					float fade = target > cur[k] ? up.evalF(e) : down.evalF(e);
					cur[k] = fade <= 0f ? target : target + (cur[k] - target) * (float) Math.exp(-FADE_RATE * e.deltaTime / fade);
				}
			}
			e.vecPop();
			System.arraycopy(cur, 0, out, off, n);
		}

		@Override
		int scratch() {
			return val.scratch() + 1;
		}
	}
}
