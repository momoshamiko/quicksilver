// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.metal.builtins;

import dev.mcms.glsl.msl.builtins.BuiltinCall;
import dev.mcms.glsl.msl.builtins.BuiltinTable;
import dev.mcms.glsl.msl.builtins.GlslTypes;
import dev.mcms.glsl.msl.builtins.Mapping;
import dev.mcms.glsl.msl.builtins.Prelude;
import dev.mcms.shaders.metal.HeadlessGpu;
import dev.mcms.shaders.metal.ShaderNative;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Runs the built-in table's MSL on the GPU (W2-C, ADR 0006). A case names one GLSL call; the harness builds a compute
 * kernel whose body is the table's own expression for that call, feeds the input arguments as raw 32-bit words, reads
 * the results back as raw words and compares them with a Java reference written from the GLSL 4.60 specification.
 * Floats compare within the case's tolerance (relative, with an absolute floor of the same size); integers and bit
 * results compare exactly. Every case runs under ShaderNative.MATH_PACKS (the engine's mode for pack programs, relaxed
 * since E7) and under MATH_SAFE; {@link #verify} fails a case that is wrong in either mode and reports, by name, the
 * functions whose pack-mode result differs from the precise one.
 *
 * <p>Value conventions: float-valued arguments are {@code float[]} (matrices column-major); int, uint and bool
 * arguments are {@code int[]} (bool as 0 or 1). A reference returns the result first, then each out parameter.
 */
final class BuiltinKernel {

	/** The Java reference: argument values in, result and out-parameter values out (float[] or int[] each). */
	@FunctionalInterface
	interface Reference {
		Object[] apply(Object[] args);
	}

	/**
	 * One call. {@code argTypes} lists every GLSL argument (out parameters included); {@code args} holds the input
	 * values, with null at out-parameter positions. {@code outTypes} lists the result type (unless void) followed by
	 * the out-parameter types, in order.
	 */
	record Case(String function, String resultType, List<String> argTypes, Object[] args, List<String> outTypes,
				Reference reference, float tolerance) {
	}

	/** One case under one math mode: the first mismatch (null when every output matched) and the raw output words. */
	record Outcome(Case the, int mathMode, String mismatch, int[] got) {
	}

	private BuiltinKernel() {
	}

	/** Argument positions the GLSL function writes through (GLSL 4.60 8.3 modf/frexp, 8.8 carry and extended mul). */
	static List<Integer> outPositions(String function) {
		return switch (function) {
			case "frexp", "modf" -> List.of(1);
			case "uaddCarry", "usubBorrow" -> List.of(2);
			case "umulExtended", "imulExtended" -> List.of(2, 3);
			default -> List.of();
		};
	}

	/** The MSL spelling of a GLSL value type. */
	static String mslType(String glsl) {
		GlslTypes.Type t = GlslTypes.parse(glsl);
		return switch (t.kind()) {
			case FLOAT -> "float";
			case INT -> "int";
			case UINT -> "uint";
			case BOOL -> "bool";
			case VECTOR -> GlslTypes.mslVector(t.scalar(), t.components());
			case MATRIX -> GlslTypes.mslMatrix(t.cols(), t.rows());
			default -> throw new IllegalArgumentException("not a value type: " + glsl);
		};
	}

	/** Words per GLSL value (matrices column-major). */
	static int words(String glsl) {
		GlslTypes.Type t = GlslTypes.parse(glsl);
		return switch (t.kind()) {
			case MATRIX -> t.cols() * t.rows();
			case VECTOR -> t.components();
			default -> 1;
		};
	}

	/** The compute kernel for one case: inputs loaded from words, the mapped expression, results and outs stored. */
	static String kernel(Case c, Mapping.Mapped mapped) {
		List<Integer> outs = outPositions(c.function());
		StringBuilder body = new StringBuilder();
		int offset = 0;
		for (int i = 0; i < c.argTypes().size(); i++) {
			String type = c.argTypes().get(i);
			if (outs.contains(i)) {
				body.append('\t').append(mslType(type)).append(" a").append(i).append(" = ").append(mslType(type))
					.append("(0);\n");
				continue;
			}
			body.append('\t').append(mslType(type)).append(" a").append(i).append(" = ").append(load(type, offset))
				.append(";\n");
			offset += words(type);
		}
		StringBuilder emit = new StringBuilder();
		int at = 0;
		if (c.resultType().equals("void")) {
			emit.append('\t').append(mapped.msl()).append(";\n");
		} else {
			emit.append("\tauto r = ").append(mapped.msl()).append(";\n");
			emit.append(store(c.resultType(), "r", at));
			at += words(c.resultType());
		}
		for (int i : outs) {
			emit.append(store(c.argTypes().get(i), "a" + i, at));
			at += words(c.argTypes().get(i));
		}
		Set<String> helpers = new LinkedHashSet<>(mapped.helpers());
		return "#include <metal_stdlib>\nusing namespace metal;\n" + Prelude.render(helpers)
			+ "kernel void k(device const uint *in [[buffer(0)]], device uint *out [[buffer(1)]]) {\n"
			+ body + emit + "}\n";
	}

	private static String load(String glsl, int offset) {
		GlslTypes.Type t = GlslTypes.parse(glsl);
		return switch (t.kind()) {
			case FLOAT -> "as_type<float>(in[" + offset + "])";
			case INT -> "as_type<int>(in[" + offset + "])";
			case UINT -> "in[" + offset + "]";
			case BOOL -> "(in[" + offset + "] != 0u)";
			case VECTOR -> {
				int n = t.components();
				StringBuilder parts = new StringBuilder();
				for (int i = 0; i < n; i++) parts.append(i > 0 ? ", " : "").append("in[").append(offset + i).append(']');
				String uv = "uint" + n;
				if (t.scalar() == GlslTypes.Scalar.BOOL) yield "(" + uv + "(" + parts + ") != 0u)";
				if (t.scalar() == GlslTypes.Scalar.UINT) yield uv + "(" + parts + ")";
				yield "as_type<" + mslType(glsl) + ">(" + uv + "(" + parts + "))";
			}
			case MATRIX -> {
				int rows = t.rows(), cols = t.cols();
				StringBuilder colVecs = new StringBuilder();
				for (int c = 0; c < cols; c++) {
					StringBuilder parts = new StringBuilder();
					for (int r = 0; r < rows; r++)
						parts.append(r > 0 ? ", " : "").append("in[").append(offset + c * rows + r).append(']');
					String col = rows == 1 ? "as_type<float>(" + parts + ")"
						: "as_type<float" + rows + ">(uint" + rows + "(" + parts + "))";
					colVecs.append(c > 0 ? ", " : "").append(col);
				}
				yield mslType(glsl) + "(" + colVecs + ")";
			}
			default -> throw new IllegalArgumentException(glsl);
		};
	}

	static String store(String glsl, String name, int at) {
		GlslTypes.Type t = GlslTypes.parse(glsl);
		StringBuilder sb = new StringBuilder();
		switch (t.kind()) {
			case FLOAT, INT, UINT -> sb.append("\tout[").append(at).append("] = as_type<uint>(").append(name).append(");\n");
			case BOOL -> sb.append("\tout[").append(at).append("] = ").append(name).append(" ? 1u : 0u;\n");
			case VECTOR -> {
				for (int i = 0; i < t.components(); i++) {
					String v = name + "[" + i + "]";
					sb.append("\tout[").append(at + i).append("] = ");
					sb.append(t.scalar() == GlslTypes.Scalar.BOOL ? v + " ? 1u : 0u" : "as_type<uint>(" + v + ")");
					sb.append(";\n");
				}
			}
			case MATRIX -> {
				for (int c = 0; c < t.cols(); c++)
					for (int r = 0; r < t.rows(); r++)
						sb.append("\tout[").append(at + c * t.rows() + r).append("] = as_type<uint>(").append(name)
							.append('[').append(c).append("][").append(r).append("]);\n");
			}
			default -> throw new IllegalArgumentException(glsl);
		}
		return sb.toString();
	}

	/** Raw words of one input value. */
	static int[] toWords(Object value) {
		if (value instanceof float[] f) {
			int[] w = new int[f.length];
			for (int i = 0; i < f.length; i++) w[i] = Float.floatToRawIntBits(f[i]);
			return w;
		}
		return (int[]) value;
	}

	/** Runs one case under one math mode. */
	static Outcome run(Case c, int mathMode) {
		Mapping mapping = TABLE.map(call(c));
		if (!(mapping instanceof Mapping.Mapped mapped))
			throw new IllegalStateException(c.function() + " is unsupported: " + ((Mapping.Unsupported) mapping).reason());
		String msl = kernel(c, mapped);
		List<Integer> outs = outPositions(c.function());
		List<Integer> inputIdx = new ArrayList<>();
		for (int i = 0; i < c.argTypes().size(); i++) if (!outs.contains(i)) inputIdx.add(i);
		List<int[]> inputs = new ArrayList<>();
		int inWords = 0;
		for (int i : inputIdx) {
			int[] w = toWords(c.args()[i]);
			inputs.add(w);
			inWords += w.length;
		}
		int[] flat = new int[inWords];
		int at = 0;
		for (int[] w : inputs) {
			System.arraycopy(w, 0, flat, at, w.length);
			at += w.length;
		}
		int outWords = c.resultType().equals("void") ? 0 : words(c.resultType());
		for (int i : outs) outWords += words(c.argTypes().get(i));
		int[] got = new int[outWords];
		try (HeadlessGpu gpu = new HeadlessGpu().with(ShaderNative.MSL_3_1, mathMode, true)) {
			byte[][] out = gpu.runKernel(msl, "k", null, new byte[][]{bytes(flat)},
				new int[]{Math.max(16, outWords * 4)}, 1, 1, 1, 1, 1, 1);
			ByteBuffer.wrap(out[0]).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(got);
		}
		Object[] expected = c.reference().apply(c.args());
		return new Outcome(c, mathMode, compare(c, expected, got), got);
	}

	/**
	 * Runs every case under both math modes; returns the failures (a mismatch in either mode) and prints the functions
	 * whose pack-mode words differ from the precise ones, with the largest difference in units in the last place.
	 */
	static List<String> verify(List<Case> cases) {
		List<String> failures = new ArrayList<>();
		for (Case c : cases) {
			Outcome packs = run(c, ShaderNative.MATH_PACKS);
			Outcome safe = run(c, ShaderNative.MATH_SAFE);
			if (packs.mismatch() != null) failures.add(describe(c) + " [pack math]: " + packs.mismatch());
			if (safe.mismatch() != null) failures.add(describe(c) + " [precise math]: " + safe.mismatch());
			int ulp = 0, differing = 0;
			for (int i = 0; i < packs.got().length; i++) {
				if (packs.got()[i] == safe.got()[i]) continue;
				differing++;
				ulp = Math.max(ulp, Math.abs(packs.got()[i] - safe.got()[i]));
			}
			if (differing > 0)
				System.out.println("pack math vs precise: " + describe(c) + " differs in " + differing + " word(s), up to "
					+ ulp + " ulp (int bit distance)");
		}
		return failures;
	}

	private static String describe(Case c) {
		return c.function() + c.argTypes();
	}

	private static byte[] bytes(int[] words) {
		ByteBuffer b = ByteBuffer.allocate(Math.max(16, words.length * 4)).order(ByteOrder.LITTLE_ENDIAN);
		for (int w : words) b.putInt(w);
		return b.array();
	}

	private static String compare(Case c, Object[] expected, int[] got) {
		if (expected.length != c.outTypes().size())
			throw new IllegalStateException(c.function() + ": reference returns " + expected.length + " values, expected "
				+ c.outTypes().size());
		int at = 0;
		for (int k = 0; k < expected.length; k++) {
			String type = c.outTypes().get(k);
			int n = words(type);
			if (expected[k] instanceof float[] ef) {
				for (int i = 0; i < n; i++) {
					int g = got[at + i];
					float e = ef[i];
					if (Float.floatToRawIntBits(e) == g) continue;
					float v = Float.intBitsToFloat(g);
					if (Float.isNaN(e) && Float.isNaN(v)) continue;
					if (!(Math.abs(v - e) <= c.tolerance() * Math.max(1f, Math.abs(e))))
						return "output " + k + "[" + i + "]: got " + v + ", expected " + e;
				}
			} else {
				int[] ei = (int[]) expected[k];
				for (int i = 0; i < n; i++)
					if (ei[i] != got[at + i])
						return "output " + k + "[" + i + "]: got " + got[at + i] + ", expected " + ei[i];
			}
			at += n;
		}
		return null;
	}

	/** The GLSL call as the emitter would hand it over (plain calls: no resources). */
	static BuiltinCall call(Case c) {
		List<String> names = new ArrayList<>();
		for (int i = 0; i < c.argTypes().size(); i++) names.add("a" + i);
		return new BuiltinCall(c.function(), c.argTypes(), c.resultType(), names, null);
	}

	/** The table the value tests share (immutable after construction). */
	static final BuiltinTable TABLE = BuiltinTable.create();

	/** Scalars may be written as plain Float or Integer; the harness wraps them as one-element arrays. */
	static Object norm(Object o) {
		if (o instanceof Float f) return new float[]{f};
		if (o instanceof Integer n) return new int[]{n};
		return o;
	}

	/** A plain case: the output is the result alone. */
	static Case fn(String function, String result, List<String> argTypes, Object[] args, Reference ref, float tol) {
		return withOuts(function, result, argTypes, args, List.of(result), ref, tol);
	}

	/** A case with out parameters: outTypes is the result type (unless void) followed by each out parameter's type. */
	static Case withOuts(String function, String result, List<String> argTypes, Object[] args, List<String> outTypes,
						 Reference ref, float tol) {
		Object[] normalized = new Object[args.length];
		for (int i = 0; i < args.length; i++) normalized[i] = norm(args[i]);
		Reference wrapped = a -> {
			Object[] r = ref.apply(a);
			for (int i = 0; i < r.length; i++) r[i] = norm(r[i]);
			return r;
		};
		return new Case(function, result, argTypes, normalized, outTypes, wrapped, tol);
	}

	static List<String> types(String... t) {
		return List.of(t);
	}
}
