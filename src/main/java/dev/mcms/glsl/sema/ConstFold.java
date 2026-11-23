// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.sema;

import dev.mcms.glsl.ast.BinaryOp;
import dev.mcms.glsl.ast.UnaryOp;
import dev.mcms.glsl.types.GlslType;
import dev.mcms.glsl.types.MatrixType;
import dev.mcms.glsl.types.ScalarKind;
import dev.mcms.glsl.types.ScalarType;
import dev.mcms.glsl.types.VectorType;

import java.util.List;

/**
 * Constant folding with GLSL semantics. Integers wrap to 32 bits; unsigned division and remainder work on bit
 * patterns; signed division truncates toward zero. Float modulus is {@code x - y * floor(x / y)} (GLSL section
 * 8.3). Division or remainder by a zero constant does not fold (the value is undefined in GLSL). Operands must
 * already be converted to the operator's common kind by the caller.
 */
final class ConstFold {
	private ConstFold() {
	}

	static ConstValue unary(UnaryOp op, ConstValue v) {
		if (op == UnaryOp.PRE_INC || op == UnaryOp.PRE_DEC) {
			return null;
		}
		return mapLeaves(v, leaf -> scalarUnary(op, leaf));
	}

	private static ConstValue scalarUnary(UnaryOp op, ConstValue v) {
		return switch (op) {
			case PLUS -> v;
			case MINUS -> switch (v.kind()) {
				case INT -> ConstValue.ofInt(-v.intValue());
				case UINT -> ConstValue.ofUint(-v.uintValue());
				case FLOAT -> ConstValue.ofFloat(-v.floatValue());
				case DOUBLE -> ConstValue.ofDouble(-v.floatValue());
				case BOOL -> null;
			};
			case NOT -> ConstValue.of(!v.booleanValue());
			case BIT_NOT -> v.kind() == ScalarKind.UINT ? ConstValue.ofUint(~v.uintValue())
					: ConstValue.ofInt(~v.intValue());
			case PRE_INC, PRE_DEC -> null;
		};
	}

	/** Binary operators on two constants already converted to the common kind. {@code result} is the
	 * expression's type; the rebuilt composite uses it. */
	static ConstValue binary(BinaryOp op, ConstValue a, ConstValue b, GlslType result) {
		if (a.isScalar() && b.isScalar()) {
			return scalarBinary(op, a, b);
		}
		if (a.isScalar() == b.isScalar() && !(a.type() instanceof MatrixType) && !(b.type() instanceof MatrixType)
				&& a.type().equals(b.type())) {
			return componentWise(op, a.flatten(), b.flatten(), result);
		}
		if (a.isScalar() || b.isScalar()) {
			ConstValue[] left = a.isScalar() ? repeat(a, b.flatten().length) : a.flatten();
			ConstValue[] right = b.isScalar() ? repeat(b, a.flatten().length) : b.flatten();
			return componentWise(op, left, right, result);
		}
		return op == BinaryOp.MUL ? matrixProduct(a, b, result) : null;
	}

	private static ConstValue[] repeat(ConstValue v, int n) {
		ConstValue[] out = new ConstValue[n];
		java.util.Arrays.fill(out, v);
		return out;
	}

	/**
	 * The linear-algebra product of two constant float operands, one of them a matrix (GLSL 4.60 section 5.10): matrix by
	 * vector, vector by matrix (the vector is a row there), matrix by matrix. Leaves are column-major.
	 */
	private static ConstValue matrixProduct(ConstValue a, ConstValue b, GlslType result) {
		if (a.kind() != ScalarKind.FLOAT || b.kind() != ScalarKind.FLOAT) {
			return null;
		}
		int[] left = shape(a, true);
		int[] right = shape(b, false);
		if (left[1] != right[0]) {
			return null;
		}
		double[] x = values(a);
		double[] y = values(b);
		int rows = left[0];
		int cols = right[1];
		double[] out = new double[rows * cols];
		for (int k = 0; k < cols; k++) {
			for (int i = 0; i < rows; i++) {
				double s = 0.0;
				for (int j = 0; j < left[1]; j++) {
					s += x[j * rows + i] * y[k * right[0] + j];
				}
				out[k * rows + i] = s;
			}
		}
		return rebuild(result, floats(out));
	}

	/** Rows and columns of an operand: a matrix as it is; a vector as a row when it is on the left, else a column. */
	private static int[] shape(ConstValue v, boolean left) {
		if (v.type() instanceof MatrixType m) {
			return new int[] {m.rows(), m.cols()};
		}
		int n = values(v).length;
		return left ? new int[] {1, n} : new int[] {n, 1};
	}

	private static ConstValue componentWise(BinaryOp op, ConstValue[] left, ConstValue[] right, GlslType result) {
		ConstValue[] out = new ConstValue[left.length];
		for (int i = 0; i < out.length; i++) {
			ConstValue r = scalarBinary(op, left[i], right[i]);
			if (r == null) {
				return null;
			}
			out[i] = r;
		}
		return rebuild(result, out);
	}

	/** Rebuilds a composite of {@code type} from its scalar leaves in order. */
	static ConstValue rebuild(GlslType type, ConstValue[] leaves) {
		if (type instanceof ScalarType) {
			return leaves[0];
		}
		if (type instanceof VectorType) {
			return ConstValue.vector(type, leaves);
		}
		if (type instanceof MatrixType m) {
			ConstValue[] cols = new ConstValue[m.cols()];
			for (int c = 0; c < m.cols(); c++) {
				ConstValue[] col = new ConstValue[m.rows()];
				System.arraycopy(leaves, c * m.rows(), col, 0, m.rows());
				cols[c] = ConstValue.vector(m.columnType(), col);
			}
			return ConstValue.matrix(m, cols);
		}
		return null;
	}

	private static ConstValue mapLeaves(ConstValue v, java.util.function.UnaryOperator<ConstValue> f) {
		ConstValue[] leaves = v.flatten();
		ConstValue[] out = new ConstValue[leaves.length];
		for (int i = 0; i < out.length; i++) {
			out[i] = f.apply(leaves[i]);
			if (out[i] == null) {
				return null;
			}
		}
		return rebuild(v.type(), out);
	}

	private static ConstValue scalarBinary(BinaryOp op, ConstValue a, ConstValue b) {
		return switch (a.kind()) {
			case BOOL -> boolBinary(op, a.booleanValue(), b.booleanValue());
			case INT -> intBinary(op, a.intValue(), b.intValue(), false);
			case UINT -> intBinary(op, a.uintValue(), b.uintValue(), true);
			case FLOAT, DOUBLE -> floatBinary(op, a.floatValue(), b.floatValue(), a.kind());
		};
	}

	private static ConstValue boolBinary(BinaryOp op, boolean x, boolean y) {
		return switch (op) {
			case LOG_AND -> ConstValue.of(x && y);
			case LOG_OR -> ConstValue.of(x || y);
			case LOG_XOR -> ConstValue.of(x ^ y);
			case EQ -> ConstValue.of(x == y);
			case NE -> ConstValue.of(x != y);
			default -> null;
		};
	}

	private static ConstValue floatBinary(BinaryOp op, double x, double y, ScalarKind kind) {
		return switch (op) {
			case ADD -> floatOf(kind, x + y);
			case SUB -> floatOf(kind, x - y);
			case MUL -> floatOf(kind, x * y);
			case DIV -> floatOf(kind, x / y);
			case MOD -> floatOf(kind, x - y * Math.floor(x / y));
			case LT -> ConstValue.of(x < y);
			case GT -> ConstValue.of(x > y);
			case LE -> ConstValue.of(x <= y);
			case GE -> ConstValue.of(x >= y);
			case EQ -> ConstValue.of(x == y);
			case NE -> ConstValue.of(x != y);
			default -> null;
		};
	}

	private static ConstValue floatOf(ScalarKind kind, double v) {
		return kind == ScalarKind.FLOAT ? ConstValue.ofFloat(v) : ConstValue.ofDouble(v);
	}

	private static ConstValue intBinary(BinaryOp op, long x, long y, boolean unsigned) {
		if (unsigned) {
			return switch (op) {
				case ADD -> ConstValue.ofUint(x + y);
				case SUB -> ConstValue.ofUint(x - y);
				case MUL -> ConstValue.ofUint(x * y);
				case DIV -> y == 0 ? null : ConstValue.ofUint(Long.divideUnsigned(x, y));
				case MOD -> y == 0 ? null : ConstValue.ofUint(Long.remainderUnsigned(x, y));
				case SHL -> ConstValue.ofUint(x << (y & 31));
				case SHR -> ConstValue.ofUint(x >>> (y & 31));
				case BIT_AND -> ConstValue.ofUint(x & y);
				case BIT_OR -> ConstValue.ofUint(x | y);
				case BIT_XOR -> ConstValue.ofUint(x ^ y);
				case LT -> ConstValue.of(Long.compareUnsigned(x, y) < 0);
				case GT -> ConstValue.of(Long.compareUnsigned(x, y) > 0);
				case LE -> ConstValue.of(Long.compareUnsigned(x, y) <= 0);
				case GE -> ConstValue.of(Long.compareUnsigned(x, y) >= 0);
				case EQ -> ConstValue.of(x == y);
				case NE -> ConstValue.of(x != y);
				default -> null;
			};
		}
		return switch (op) {
			case ADD -> ConstValue.ofInt(x + y);
			case SUB -> ConstValue.ofInt(x - y);
			case MUL -> ConstValue.ofInt(x * y);
			case DIV -> y == 0 ? null : ConstValue.ofInt(x / y);
			case MOD -> y == 0 ? null : ConstValue.ofInt(x % y);
			case SHL -> ConstValue.ofInt(x << (y & 31));
			case SHR -> ConstValue.ofInt(x >> (y & 31));
			case BIT_AND -> ConstValue.ofInt(x & y);
			case BIT_OR -> ConstValue.ofInt(x | y);
			case BIT_XOR -> ConstValue.ofInt(x ^ y);
			case LT -> ConstValue.of(x < y);
			case GT -> ConstValue.of(x > y);
			case LE -> ConstValue.of(x <= y);
			case GE -> ConstValue.of(x >= y);
			case EQ -> ConstValue.of(x == y);
			case NE -> ConstValue.of(x != y);
			default -> null;
		};
	}

	/** {@code base[index]} for arrays, vectors and matrices (columns). Null when out of range. */
	static ConstValue index(ConstValue base, long index) {
		ConstValue[] parts = base.parts();
		return index >= 0 && index < parts.length ? parts[(int) index] : null;
	}

	static ConstValue swizzle(ConstValue base, int[] components, GlslType result) {
		ConstValue[] parts = base.isScalar() ? new ConstValue[] {base} : base.parts();
		if (result instanceof ScalarType) {
			return parts[components[0]];
		}
		ConstValue[] out = new ConstValue[components.length];
		for (int i = 0; i < components.length; i++) {
			out[i] = parts[components[i]];
		}
		return ConstValue.vector(result, out);
	}

	static ConstValue matrixDiagonal(MatrixType target, ConstValue scalar) {
		ConstValue s = scalar.convert(target.component());
		ConstValue zero = ConstValue.scalarOf(target.component(), 0.0);
		ConstValue[] leaves = new ConstValue[target.cols() * target.rows()];
		for (int c = 0; c < target.cols(); c++) {
			for (int r = 0; r < target.rows(); r++) {
				leaves[c * target.rows() + r] = c == r ? s : zero;
			}
		}
		return rebuild(target, leaves);
	}

	/** A matrix constructed from a matrix: shared top-left components, identity elsewhere (GLSL 4.60 5.4.2). */
	static ConstValue matrixFromMatrix(MatrixType target, ConstValue source) {
		MatrixType from = (MatrixType) source.type();
		ConstValue[] leaves = new ConstValue[target.cols() * target.rows()];
		for (int c = 0; c < target.cols(); c++) {
			for (int r = 0; r < target.rows(); r++) {
				ConstValue v;
				if (c < from.cols() && r < from.rows()) {
					v = source.parts()[c].parts()[r];
				} else {
					v = ConstValue.scalarOf(target.component(), c == r ? 1.0 : 0.0);
				}
				leaves[c * target.rows() + r] = v.convert(target.component());
			}
		}
		return rebuild(target, leaves);
	}

	/** Built-in functions folded for constant arguments (component-wise; scalars and vectors of one kind). */
	static ConstValue builtin(String name, List<ConstValue> args, GlslType result) {
		if (args.isEmpty()) {
			return null;
		}
		ConstValue vector = nonComponentBuiltin(name, args, result);
		if (vector != null || NON_COMPONENT.contains(name)) {
			return vector;
		}
		ScalarKind kind = args.getFirst().kind();
		for (ConstValue a : args) {
			if (a.kind() != kind || a.type() instanceof MatrixType) {
				return null;
			}
		}
		int size = result instanceof VectorType v ? v.size() : 1;
		ConstValue[][] leaves = new ConstValue[args.size()][];
		for (int a = 0; a < args.size(); a++) {
			leaves[a] = args.get(a).isScalar() ? repeat(args.get(a), size) : args.get(a).flatten();
			if (leaves[a].length != size) {
				return null;
			}
		}
		ConstValue[] out = new ConstValue[size];
		for (int i = 0; i < size; i++) {
			ConstValue[] parts = new ConstValue[args.size()];
			for (int a = 0; a < args.size(); a++) {
				parts[a] = leaves[a][i];
			}
			ConstValue r = scalarBuiltin(name, parts, kind);
			if (r == null) {
				return null;
			}
			out[i] = r;
		}
		return rebuild(result, out);
	}

	private static ConstValue scalarBuiltin(String name, ConstValue[] p, ScalarKind kind) {
		if (kind == ScalarKind.INT || kind == ScalarKind.UINT) {
			boolean u = kind == ScalarKind.UINT;
			long x = p[0].integerValue();
			return switch (name) {
				case "abs" -> u ? p[0] : ConstValue.ofInt(Math.abs(x));
				case "sign" -> u ? ConstValue.ofUint(x == 0 ? 0 : 1) : ConstValue.ofInt(Long.signum(x));
				case "min" -> u ? ConstValue.ofUint(Math.min(x, p[1].uintValue()))
						: ConstValue.ofInt(Math.min(x, p[1].intValue()));
				case "max" -> u ? ConstValue.ofUint(Math.max(x, p[1].uintValue()))
						: ConstValue.ofInt(Math.max(x, p[1].intValue()));
				case "clamp" -> u ? ConstValue.ofUint(Math.max(Math.min(x, p[2].uintValue()), p[1].uintValue()))
						: ConstValue.ofInt(Math.max(Math.min(x, p[2].intValue()), p[1].intValue()));
				default -> null;
			};
		}
		if (kind == ScalarKind.BOOL) {
			return null;
		}
		double x = p[0].floatValue();
		double y = p.length > 1 ? p[1].floatValue() : 0.0;
		double z = p.length > 2 ? p[2].floatValue() : 0.0;
		double r = switch (name) {
			case "abs" -> Math.abs(x);
			case "sign" -> Math.signum(x);
			case "floor" -> Math.floor(x);
			case "ceil" -> Math.ceil(x);
			case "trunc" -> x < 0 ? Math.ceil(x) : Math.floor(x);
			case "fract" -> x - Math.floor(x);
			case "round" -> Math.floor(x + 0.5);
			case "roundEven" -> Math.rint(x);
			case "min" -> Math.min(x, y);
			case "max" -> Math.max(x, y);
			case "clamp" -> Math.max(Math.min(x, z), y);
			case "mod" -> x - y * Math.floor(x / y);
			case "step" -> y < x ? 0.0 : 1.0;
			case "mix" -> x * (1.0 - z) + y * z;
			case "smoothstep" -> {
				double t = Math.max(0.0, Math.min(1.0, (z - x) / (y - x)));
				yield t * t * (3.0 - 2.0 * t);
			}
			case "fma" -> x * y + z;
			case "sqrt" -> Math.sqrt(x);
			case "inversesqrt" -> 1.0 / Math.sqrt(x);
			case "pow" -> Math.pow(x, y);
			case "exp" -> Math.exp(x);
			case "exp2" -> Math.pow(2.0, x);
			case "log" -> Math.log(x);
			case "log2" -> Math.log(x) / Math.log(2.0);
			case "sin" -> Math.sin(x);
			case "cos" -> Math.cos(x);
			case "tan" -> Math.tan(x);
			case "asin" -> Math.asin(x);
			case "acos" -> Math.acos(x);
			case "atan" -> p.length > 1 ? Math.atan2(x, y) : Math.atan(x);
			case "radians" -> Math.toRadians(x);
			case "degrees" -> Math.toDegrees(x);
			default -> Double.NaN;
		};
		if (Double.isNaN(r) && !name.equals("sqrt") && !name.equals("pow") && !name.equals("exp2")) {
			return null;
		}
		return floatOf(kind, r);
	}

	/** Built-ins that are not component-wise: they are folded by {@link #nonComponentBuiltin} or not at all. */
	private static final java.util.Set<String> NON_COMPONENT = java.util.Set.of("dot", "length", "distance",
			"normalize", "cross");

	/** dot, length, distance, normalize and cross of constant float vectors, in double, rounded to float. */
	private static ConstValue nonComponentBuiltin(String name, List<ConstValue> args, GlslType result) {
		if (!NON_COMPONENT.contains(name) || args.isEmpty() || args.getFirst().kind() != ScalarKind.FLOAT) {
			return null;
		}
		for (ConstValue a : args) {
			if (a.kind() != ScalarKind.FLOAT || a.type() instanceof MatrixType) {
				return null;
			}
		}
		double[] a = values(args.getFirst());
		double[] b = args.size() > 1 ? values(args.get(1)) : new double[0];
		switch (name) {
			case "dot" -> {
				if (args.size() != 2 || a.length != b.length) {
					return null;
				}
				double s = 0.0;
				for (int i = 0; i < a.length; i++) {
					s += a[i] * b[i];
				}
				return floatOf(ScalarKind.FLOAT, s);
			}
			case "length" -> {
				return floatOf(ScalarKind.FLOAT, Math.sqrt(sumSquares(a)));
			}
			case "distance" -> {
				if (args.size() != 2 || a.length != b.length) {
					return null;
				}
				double[] d = new double[a.length];
				for (int i = 0; i < a.length; i++) {
					d[i] = a[i] - b[i];
				}
				return floatOf(ScalarKind.FLOAT, Math.sqrt(sumSquares(d)));
			}
			case "normalize" -> {
				double len = Math.sqrt(sumSquares(a));
				if (len == 0.0) {
					return null;
				}
				double[] n = new double[a.length];
				for (int i = 0; i < a.length; i++) {
					n[i] = a[i] / len;
				}
				return rebuild(result, floats(n));
			}
			case "cross" -> {
				if (args.size() != 2 || a.length != 3 || b.length != 3) {
					return null;
				}
				double[] c = {a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
				return rebuild(result, floats(c));
			}
			default -> {
				return null;
			}
		}
	}

	private static double sumSquares(double[] v) {
		double s = 0.0;
		for (double d : v) {
			s += d * d;
		}
		return s;
	}

	private static double[] values(ConstValue v) {
		ConstValue[] parts = v.isScalar() ? new ConstValue[] {v} : v.flatten();
		double[] out = new double[parts.length];
		for (int i = 0; i < parts.length; i++) {
			out[i] = parts[i].floatValue();
		}
		return out;
	}

	private static ConstValue[] floats(double[] v) {
		ConstValue[] out = new ConstValue[v.length];
		for (int i = 0; i < v.length; i++) {
			out[i] = floatOf(ScalarKind.FLOAT, v[i]);
		}
		return out;
	}
}
