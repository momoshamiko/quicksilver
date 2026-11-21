// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.types;

import java.util.HashMap;
import java.util.Map;

/**
 * The registry of built-in type words (every type word of GLSL 1.10 to 4.60) and helpers over types. The lexer
 * already gates which words are keywords at the unit's version; this class maps a word to its type.
 */
public final class Types {
	private Types() {
	}

	private static final Map<String, GlslType> WORDS = new HashMap<>(512);

	static {
		word("void", VoidType.VOID);
		word("float", ScalarType.FLOAT);
		word("int", ScalarType.INT);
		word("uint", ScalarType.UINT);
		word("bool", ScalarType.BOOL);
		word("double", ScalarType.DOUBLE);
		for (ScalarKind k : ScalarKind.values()) {
			for (int n = 2; n <= 4; n++) {
				word(k.prefix() + "vec" + n, VectorType.of(k, n));
			}
		}
		for (ScalarKind k : new ScalarKind[] {ScalarKind.FLOAT, ScalarKind.DOUBLE}) {
			for (int c = 2; c <= 4; c++) {
				for (int r = 2; r <= 4; r++) {
					MatrixType m = MatrixType.of(k, c, r);
					word(m.toString(), m);
					if (c == r) {
						word(k.prefix() + "mat" + c + "x" + r, m);
					}
				}
			}
		}
		for (SamplerDim dim : SamplerDim.values()) {
			for (ScalarKind texel : new ScalarKind[] {ScalarKind.FLOAT, ScalarKind.INT, ScalarKind.UINT}) {
				SamplerType s = new SamplerType(dim, false, texel);
				word(s.toString(), s);
				ImageType i = new ImageType(dim, texel);
				word(i.toString(), i);
			}
			if (dim != SamplerDim.BUFFER && !dim.multisample()) {
				SamplerType s = new SamplerType(dim, true, ScalarKind.FLOAT);
				word(s.toString(), s);
			}
		}
		word("atomic_uint", AtomicCounterType.ATOMIC_UINT);
	}

	private static void word(String w, GlslType t) {
		if (WORDS.putIfAbsent(w, t) != null) {
			throw new IllegalStateException("duplicate type word " + w);
		}
	}

	/** The type of a built-in type word, or null when the word is no type word. */
	public static GlslType fromWord(String word) {
		return WORDS.get(word);
	}

	/** The element type of an array (the type itself when it is not an array). */
	public static GlslType elementType(GlslType t) {
		return t instanceof ArrayType a ? a.element() : t;
	}

	/** True for scalars and vectors (not matrices, arrays, structs or opaque types). */
	public static boolean isScalarOrVector(GlslType t) {
		return t instanceof ScalarType || t instanceof VectorType;
	}

	/** True when the type is numeric (bool is not numeric). */
	public static boolean isNumeric(GlslType t) {
		return switch (t) {
			case ScalarType s -> s.kind().isNumeric();
			case VectorType v -> v.component().isNumeric();
			case MatrixType m -> true;
			default -> false;
		};
	}

	/** True when the type is integer (scalar or vector of int/uint). */
	public static boolean isInteger(GlslType t) {
		return switch (t) {
			case ScalarType s -> s.kind().isInteger();
			case VectorType v -> v.component().isInteger();
			default -> false;
		};
	}

	/** True for bool scalars and bvec vectors. */
	public static boolean isBoolean(GlslType t) {
		return switch (t) {
			case ScalarType s -> s.kind() == ScalarKind.BOOL;
			case VectorType v -> v.component() == ScalarKind.BOOL;
			default -> false;
		};
	}

	/** The same shape as {@code shape} (scalar or vector size) with {@code kind} components. */
	public static GlslType withKind(GlslType shape, ScalarKind kind) {
		return switch (shape) {
			case ScalarType s -> ScalarType.of(kind);
			case VectorType v -> VectorType.of(kind, v.size());
			case MatrixType m -> MatrixType.of(kind, m.cols(), m.rows());
			default -> shape;
		};
	}
}
