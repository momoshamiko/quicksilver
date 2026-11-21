// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.sema;

import dev.mcms.glsl.types.ArrayType;
import dev.mcms.glsl.types.AtomicCounterType;
import dev.mcms.glsl.types.GlslType;
import dev.mcms.glsl.types.SamplerDim;
import dev.mcms.glsl.types.SamplerType;
import dev.mcms.glsl.types.ScalarKind;
import dev.mcms.glsl.types.ScalarType;
import dev.mcms.glsl.types.VectorType;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The built-in function signatures of GLSL 1.10 to 4.60, written from the GLSL specifications' chapter 8
 * ("Built-In Functions" of each version, 4.60 latest; the 4.60 compatibility section 8.9.5 for the legacy
 * texture names) with each signature's first version, the fragment-only families, and the extensions that
 * introduce functions the core specifications never had ({@code texture2DGradARB} from
 * {@code GL_ARB_shader_texture_lod}, {@code texelFetch2D} from {@code GL_EXT_gpu_shader4}, the subgroup
 * family from {@code GL_KHR_shader_subgroup_*}). Generic families ({@code genType} and the sampler
 * parameterized texture functions) are expanded to concrete signatures once, at class initialization.
 */
public final class BuiltinFunctions {
	private BuiltinFunctions() {
	}

	private static final Map<String, List<BuiltinFunction>> TABLE = new HashMap<>(512);

	/** Every signature of a name (the caller filters by version, stage and extension). */
	public static List<BuiltinFunction> byName(String name) {
		return TABLE.getOrDefault(name, List.of());
	}

	/** True when the name has at least one signature in any version or profile. */
	public static boolean isBuiltinName(String name) {
		return TABLE.containsKey(name);
	}

	// --- helpers ---

	private static final GlslType F = ScalarType.FLOAT;
	private static final GlslType I = ScalarType.INT;
	private static final GlslType U = ScalarType.UINT;
	private static final GlslType D = ScalarType.DOUBLE;
	private static final GlslType B = ScalarType.BOOL;
	private static final GlslType VEC2 = VectorType.VEC2;
	private static final GlslType VEC3 = VectorType.VEC3;
	private static final GlslType VEC4 = VectorType.VEC4;
	private static final GlslType IVEC2 = VectorType.IVEC2;
	private static final GlslType IVEC3 = VectorType.IVEC3;
	private static final GlslType IVEC4 = VectorType.IVEC4;
	private static final GlslType UVEC2 = VectorType.UVEC2;
	private static final GlslType UVEC4 = VectorType.UVEC4;

	private static GlslType fam(ScalarKind kind, int size) {
		return size <= 1 ? ScalarType.of(kind) : VectorType.of(kind, size);
	}

	private static void sig(String name, int since, GlslType result, BuiltinFunction.Param... params) {
		sig(name, since, result, null, false, null, params);
	}

	private static void sig(String name, int since, GlslType result, EnumSet<Stage> stages,
			BuiltinFunction.Param... params) {
		sig(name, since, result, stages, false, null, params);
	}

	private static void sig(String name, int since, GlslType result, EnumSet<Stage> stages, boolean compat,
			String extension, BuiltinFunction.Param... params) {
		BuiltinFunction f = new BuiltinFunction(name, List.of(params), result, since,
				stages == null ? EnumSet.noneOf(Stage.class) : stages, compat, extension);
		List<BuiltinFunction> list = TABLE.computeIfAbsent(name, n -> new ArrayList<>());
		// a family expansion can write one signature twice (max(float, float) from the vector and the scalar forms);
		// the copies must agree on stages, profile and extension, and the earlier version is kept
		for (int i = 0; i < list.size(); i++) {
			BuiltinFunction old = list.get(i);
			if (old.params().equals(f.params()) && old.result().equals(f.result()) && old.stages().equals(f.stages())
					&& old.compat() == f.compat() && Objects.equals(old.extension(), f.extension())) {
				if (f.since() < old.since()) {
					list.set(i, f);
				}
				return;
			}
		}
		list.add(f);
	}

	private static BuiltinFunction.Param in(GlslType t) {
		return BuiltinFunction.Param.in(t);
	}

	private static BuiltinFunction.Param k(GlslType t) {
		return BuiltinFunction.Param.constIn(t);
	}

	private static BuiltinFunction.Param out(GlslType t) {
		return BuiltinFunction.Param.out(t);
	}

	private static BuiltinFunction.Param inout(GlslType t) {
		return BuiltinFunction.Param.inout(t);
	}

	static {
		angleAndTrigonometry();
		exponential();
		common();
		floatClassification();
		geometric();
		matrix();
		vectorRelational();
		integer();
		packing();
		frexpLdexp();
		derivativesAndInterpolation();
		noise();
		barriers();
		atomicCounters();
		atomicMemory();
		imageFunctions();
		subgroups();
		legacyTexture();
		textureFunctions();
	}

	/** Adds {@code name} for every float family member (scalar to vec4); the operands follow the result. */
	private static void fFamily(String name, int since, int args) {
		for (int n = 1; n <= 4; n++) {
			famSig(name, since, ScalarKind.FLOAT, n, args);
		}
	}

	private static void famSig(String name, int since, ScalarKind kind, int size, int args) {
		GlslType t = fam(kind, size);
		sig(name, since, t, repeat(t, args));
	}

	private static BuiltinFunction.Param[] repeat(GlslType t, int count) {
		BuiltinFunction.Param[] params = new BuiltinFunction.Param[count];
		for (int i = 0; i < count; i++) {
			params[i] = in(t);
		}
		return params;
	}

	// --- 8.1 angle and trigonometry ---

	private static void angleAndTrigonometry() {
		fFamily("radians", 110, 1);
		fFamily("degrees", 110, 1);
		fFamily("sin", 110, 1);
		fFamily("cos", 110, 1);
		fFamily("tan", 110, 1);
		fFamily("asin", 110, 1);
		fFamily("acos", 110, 1);
		fFamily("atan", 110, 1);
		fFamily("atan", 110, 2);
		fFamily("sinh", 130, 1);
		fFamily("cosh", 130, 1);
		fFamily("tanh", 130, 1);
		fFamily("asinh", 130, 1);
		fFamily("acosh", 130, 1);
		fFamily("atanh", 130, 1);
	}

	// --- 8.2 exponential ---

	private static void exponential() {
		fFamily("pow", 110, 2);
		fFamily("exp", 110, 1);
		fFamily("log", 110, 1);
		fFamily("exp2", 110, 1);
		fFamily("log2", 110, 1);
		fFamily("sqrt", 110, 1);
		fFamily("inversesqrt", 110, 1);
		for (int n = 1; n <= 4; n++) {
			GlslType t = fam(ScalarKind.DOUBLE, n);
			sig("sqrt", 400, t, in(t));
			sig("inversesqrt", 400, t, in(t));
		}
	}

	// --- 8.3 common ---

	private static void common() {
		family1("abs", 110, ScalarKind.FLOAT);
		family1("abs", 130, ScalarKind.INT);
		family1("abs", 400, ScalarKind.DOUBLE);
		family1("sign", 110, ScalarKind.FLOAT);
		family1("sign", 130, ScalarKind.INT);
		family1("sign", 400, ScalarKind.DOUBLE);
		family1("floor", 110, ScalarKind.FLOAT);
		family1("floor", 400, ScalarKind.DOUBLE);
		family1("trunc", 130, ScalarKind.FLOAT);
		family1("trunc", 400, ScalarKind.DOUBLE);
		family1("round", 130, ScalarKind.FLOAT);
		family1("round", 400, ScalarKind.DOUBLE);
		family1("roundEven", 130, ScalarKind.FLOAT);
		family1("roundEven", 400, ScalarKind.DOUBLE);
		family1("ceil", 110, ScalarKind.FLOAT);
		family1("ceil", 400, ScalarKind.DOUBLE);
		family1("fract", 110, ScalarKind.FLOAT);
		family1("fract", 400, ScalarKind.DOUBLE);
		for (int n = 1; n <= 4; n++) {
			GlslType f = fam(ScalarKind.FLOAT, n);
			sig("mod", 110, f, in(f), in(F));
			sig("mod", 110, f, in(f), in(f));
			sig("modf", 130, f, in(f), out(f));
			GlslType d = fam(ScalarKind.DOUBLE, n);
			sig("mod", 400, d, in(d), in(D));
			sig("mod", 400, d, in(d), in(d));
			sig("modf", 400, d, in(d), out(d));
			minMax("min", n);
			minMax("max", n);
			clamp(n);
			mix(n);
			step(n);
			smoothstep(n);
		}
	}

	private static void family1(String name, int since, ScalarKind kind) {
		for (int n = 1; n <= 4; n++) {
			famSig(name, since, kind, n, 1);
		}
	}

	private static void minMax(String name, int n) {
		GlslType f = fam(ScalarKind.FLOAT, n);
		sig(name, 110, f, in(f), in(f));
		sig(name, 110, f, in(f), in(F));
		GlslType i = fam(ScalarKind.INT, n);
		sig(name, 130, i, in(i), in(i));
		sig(name, 130, i, in(i), in(I));
		GlslType u = fam(ScalarKind.UINT, n);
		sig(name, 130, u, in(u), in(u));
		sig(name, 130, u, in(u), in(U));
		GlslType d = fam(ScalarKind.DOUBLE, n);
		sig(name, 400, d, in(d), in(d));
		sig(name, 400, d, in(d), in(D));
	}

	private static void clamp(int n) {
		GlslType f = fam(ScalarKind.FLOAT, n);
		sig("clamp", 110, f, in(f), in(f), in(f));
		sig("clamp", 110, f, in(f), in(F), in(F));
		GlslType i = fam(ScalarKind.INT, n);
		sig("clamp", 130, i, in(i), in(i), in(i));
		sig("clamp", 130, i, in(i), in(I), in(I));
		GlslType u = fam(ScalarKind.UINT, n);
		sig("clamp", 130, u, in(u), in(u), in(u));
		sig("clamp", 130, u, in(u), in(U), in(U));
		GlslType d = fam(ScalarKind.DOUBLE, n);
		sig("clamp", 400, d, in(d), in(d), in(d));
		sig("clamp", 400, d, in(d), in(D), in(D));
	}

	private static void mix(int n) {
		GlslType f = fam(ScalarKind.FLOAT, n);
		sig("mix", 110, f, in(f), in(f), in(f));
		sig("mix", 110, f, in(f), in(f), in(F));
		// genBType is bool or a bool vector (GLSL 4.60 section 8.3): the selector of a scalar mix is a bool too
		GlslType b = fam(ScalarKind.BOOL, n);
		sig("mix", 130, f, in(f), in(f), in(b));
		GlslType d = fam(ScalarKind.DOUBLE, n);
		sig("mix", 400, d, in(d), in(d), in(d));
		sig("mix", 400, d, in(d), in(d), in(D));
		sig("mix", 400, d, in(d), in(d), in(b));
		GlslType i = fam(ScalarKind.INT, n);
		GlslType u = fam(ScalarKind.UINT, n);
		sig("mix", 450, i, in(i), in(i), in(b));
		sig("mix", 450, u, in(u), in(u), in(b));
		sig("mix", 450, b, in(b), in(b), in(b));
	}

	/** isnan and isinf: the float family (GLSL 1.30, section 8.4) and the double family (4.00), each returning a bool vector. */
	private static void floatClassification() {
		for (int n = 1; n <= 4; n++) {
			GlslType b = fam(ScalarKind.BOOL, n);
			sig("isnan", 130, b, in(fam(ScalarKind.FLOAT, n)));
			sig("isinf", 130, b, in(fam(ScalarKind.FLOAT, n)));
			sig("isnan", 400, b, in(fam(ScalarKind.DOUBLE, n)));
			sig("isinf", 400, b, in(fam(ScalarKind.DOUBLE, n)));
		}
	}

	private static void step(int n) {
		GlslType f = fam(ScalarKind.FLOAT, n);
		sig("step", 110, f, in(f), in(f));
		if (n >= 2) {
			sig("step", 110, f, in(F), in(f));
		}
		GlslType d = fam(ScalarKind.DOUBLE, n);
		sig("step", 400, d, in(d), in(d));
		if (n >= 2) {
			sig("step", 400, d, in(D), in(d));
		}
	}

	private static void smoothstep(int n) {
		GlslType f = fam(ScalarKind.FLOAT, n);
		sig("smoothstep", 110, f, in(f), in(f), in(f));
		if (n >= 2) {
			sig("smoothstep", 110, f, in(F), in(F), in(f));
		}
		GlslType d = fam(ScalarKind.DOUBLE, n);
		sig("smoothstep", 400, d, in(d), in(d), in(d));
		if (n >= 2) {
			sig("smoothstep", 400, d, in(D), in(D), in(d));
		}
	}

	// --- 8.4 geometric ---

	private static void geometric() {
		for (int n = 1; n <= 4; n++) {
			GlslType f = fam(ScalarKind.FLOAT, n);
			sig("length", 110, F, in(f));
			sig("distance", 110, F, in(f), in(f));
			sig("dot", 110, F, in(f), in(f));
			sig("normalize", 110, f, in(f));
			sig("faceforward", 110, f, in(f), in(f), in(f));
			sig("reflect", 110, f, in(f), in(f));
			sig("refract", 110, f, in(f), in(f), in(F));
			GlslType d = fam(ScalarKind.DOUBLE, n);
			sig("length", 400, D, in(d));
			sig("distance", 400, D, in(d), in(d));
			sig("dot", 400, D, in(d), in(d));
			sig("normalize", 400, d, in(d));
			sig("faceforward", 400, d, in(d), in(d), in(d));
			sig("reflect", 400, d, in(d), in(d));
			sig("refract", 400, d, in(d), in(d), in(D));
		}
		sig("cross", 110, VEC3, in(VEC3), in(VEC3));
		sig("cross", 400, VectorType.DVEC3, in(VectorType.DVEC3), in(VectorType.DVEC3));
		sig("ftransform", 110, VEC4, EnumSet.of(Stage.VERTEX), true, null);
	}

	// --- 8.5 matrix ---

	private static void matrix() {
		for (int c = 2; c <= 4; c++) {
			for (int r = 2; r <= 4; r++) {
				GlslType m = dev.mcms.glsl.types.MatrixType.of(ScalarKind.FLOAT, c, r);
				int since = c == r ? 110 : 120;
				sig("matrixCompMult", since, m, in(m), in(m));
				sig("transpose", 120, dev.mcms.glsl.types.MatrixType.of(ScalarKind.FLOAT, r, c), in(m));
				GlslType dm = dev.mcms.glsl.types.MatrixType.of(ScalarKind.DOUBLE, c, r);
				sig("matrixCompMult", 400, dm, in(dm), in(dm));
				sig("transpose", 400, dev.mcms.glsl.types.MatrixType.of(ScalarKind.DOUBLE, r, c), in(dm));
			}
		}
		for (int n = 2; n <= 4; n++) {
			GlslType v = VectorType.of(ScalarKind.FLOAT, n);
			GlslType dv = VectorType.of(ScalarKind.DOUBLE, n);
			for (int k = 2; k <= 4; k++) {
				GlslType w = VectorType.of(ScalarKind.FLOAT, k);
				GlslType dw = VectorType.of(ScalarKind.DOUBLE, k);
				sig("outerProduct", 120, dev.mcms.glsl.types.MatrixType.of(ScalarKind.FLOAT, k, n), in(v), in(w));
				sig("outerProduct", 400, dev.mcms.glsl.types.MatrixType.of(ScalarKind.DOUBLE, k, n), in(dv), in(dw));
			}
			GlslType m = dev.mcms.glsl.types.MatrixType.of(ScalarKind.FLOAT, n, n);
			GlslType dm = dev.mcms.glsl.types.MatrixType.of(ScalarKind.DOUBLE, n, n);
			sig("determinant", 140, F, in(m));
			sig("inverse", 140, m, in(m));
			sig("determinant", 400, D, in(dm));
			sig("inverse", 400, dm, in(dm));
		}
	}

	// --- 8.6 vector relational ---

	private static void vectorRelational() {
		String[] names = {"lessThan", "lessThanEqual", "greaterThan", "greaterThanEqual", "equal", "notEqual"};
		for (String name : names) {
			boolean equality = name.equals("equal") || name.equals("notEqual");
			for (int n = 2; n <= 4; n++) {
				GlslType b = VectorType.of(ScalarKind.BOOL, n);
				sig(name, 110, b, in(VectorType.of(ScalarKind.FLOAT, n)), in(VectorType.of(ScalarKind.FLOAT, n)));
				if (equality) {
					sig(name, 110, b, in(VectorType.of(ScalarKind.BOOL, n)), in(VectorType.of(ScalarKind.BOOL, n)));
				}
				sig(name, 110, b, in(VectorType.of(ScalarKind.INT, n)), in(VectorType.of(ScalarKind.INT, n)));
				sig(name, 130, b, in(VectorType.of(ScalarKind.UINT, n)), in(VectorType.of(ScalarKind.UINT, n)));
				sig(name, 400, b, in(VectorType.of(ScalarKind.DOUBLE, n)), in(VectorType.of(ScalarKind.DOUBLE, n)));
			}
		}
		for (int n = 2; n <= 4; n++) {
			GlslType b = VectorType.of(ScalarKind.BOOL, n);
			sig("any", 110, B, in(b));
			sig("all", 110, B, in(b));
			sig("not", 110, b, in(b));
		}
	}

	// --- 8.7 integer ---

	private static void integer() {
		for (int n = 1; n <= 4; n++) {
			GlslType u = fam(ScalarKind.UINT, n);
			GlslType i = fam(ScalarKind.INT, n);
			sig("uaddCarry", 400, u, in(u), in(u), out(u));
			sig("usubBorrow", 400, u, in(u), in(u), out(u));
			sig("umulExtended", 400, dev.mcms.glsl.types.VoidType.VOID, in(u), in(u), out(u), out(u));
			sig("imulExtended", 400, dev.mcms.glsl.types.VoidType.VOID, in(i), in(i), out(i), out(i));
			sig("bitfieldExtract", 400, i, in(i), in(I), in(I));
			sig("bitfieldExtract", 400, u, in(u), in(I), in(I));
			sig("bitfieldInsert", 400, i, in(i), in(i), in(I), in(I));
			sig("bitfieldInsert", 400, u, in(u), in(u), in(I), in(I));
			sig("bitfieldReverse", 400, i, in(i));
			sig("bitfieldReverse", 400, u, in(u));
			sig("bitCount", 400, i, in(i));
			sig("bitCount", 400, i, in(u));
			sig("findLSB", 400, i, in(i));
			sig("findLSB", 400, i, in(u));
			sig("findMSB", 400, i, in(i));
			sig("findMSB", 400, i, in(u));
		}
	}

	// --- 8.8 packing ---

	private static void packing() {
		sig("packUnorm2x16", 130, U, in(VEC2));
		sig("packSnorm2x16", 130, U, in(VEC2));
		sig("unpackUnorm2x16", 130, VEC2, in(U));
		sig("unpackSnorm2x16", 130, VEC2, in(U));
		sig("packUnorm4x8", 400, U, in(VEC4));
		sig("packSnorm4x8", 400, U, in(VEC4));
		sig("unpackUnorm4x8", 400, VEC4, in(U));
		sig("unpackSnorm4x8", 400, VEC4, in(U));
		sig("packHalf2x16", 400, U, in(VEC2));
		sig("unpackHalf2x16", 400, VEC2, in(U));
		sig("packDouble2x32", 400, D, in(UVEC2));
		sig("unpackDouble2x32", 400, UVEC2, in(D));
		for (int n = 1; n <= 4; n++) {
			GlslType f = fam(ScalarKind.FLOAT, n);
			sig("floatBitsToInt", 330, fam(ScalarKind.INT, n), in(f));
			sig("floatBitsToUint", 330, fam(ScalarKind.UINT, n), in(f));
			sig("intBitsToFloat", 330, f, in(fam(ScalarKind.INT, n)));
			sig("uintBitsToFloat", 330, f, in(fam(ScalarKind.UINT, n)));
		}
	}

	private static void frexpLdexp() {
		for (int n = 1; n <= 4; n++) {
			GlslType f = fam(ScalarKind.FLOAT, n);
			GlslType i = fam(ScalarKind.INT, n);
			GlslType d = fam(ScalarKind.DOUBLE, n);
			sig("frexp", 400, f, in(f), out(i));
			sig("frexp", 400, d, in(d), out(i));
			sig("ldexp", 400, f, in(f), in(i));
			sig("ldexp", 400, d, in(d), in(i));
		}
		sig("fma", 400, F, in(F), in(F), in(F));
		for (int n = 2; n <= 4; n++) {
			GlslType f = VectorType.of(ScalarKind.FLOAT, n);
			GlslType d = VectorType.of(ScalarKind.DOUBLE, n);
			sig("fma", 400, f, in(f), in(f), in(f));
			sig("fma", 400, d, in(d), in(d), in(d));
		}
	}

	// --- derivatives and interpolation (fragment only) ---

	private static void derivativesAndInterpolation() {
		EnumSet<Stage> fragment = EnumSet.of(Stage.FRAGMENT);
		String[] derivatives = {"dFdx", "dFdy", "fwidth", "dFdxFine", "dFdyFine", "fwidthFine", "dFdxCoarse",
				"dFdyCoarse", "fwidthCoarse"};
		int[] sinces = {110, 110, 110, 450, 450, 450, 450, 450, 450};
		for (int d = 0; d < derivatives.length; d++) {
			for (int n = 1; n <= 4; n++) {
				famSigStages(derivatives[d], sinces[d], ScalarKind.FLOAT, n, 1, fragment);
			}
		}
		for (int n = 1; n <= 4; n++) {
			GlslType f = fam(ScalarKind.FLOAT, n);
			sig("interpolateAtCentroid", 400, f, fragment, in(f));
			sig("interpolateAtSample", 400, f, fragment, in(f), in(I));
			sig("interpolateAtOffset", 400, f, fragment, in(f), in(VEC2));
		}
	}

	private static void famSigStages(String name, int since, ScalarKind kind, int size, int args,
			EnumSet<Stage> stages) {
		GlslType t = fam(kind, size);
		sig(name, since, t, stages, repeat(t, args));
	}

	private static void noise() {
		sig("noise1", 110, F, in(F));
		sig("noise2", 110, VEC2, in(VEC2));
		sig("noise3", 110, VEC3, in(VEC3));
		sig("noise4", 110, VEC4, in(VEC4));
	}

	// --- barriers, atomics ---

	private static void barriers() {
		sig("memoryBarrier", 420, dev.mcms.glsl.types.VoidType.VOID);
		sig("memoryBarrierAtomicCounter", 430, dev.mcms.glsl.types.VoidType.VOID);
		sig("memoryBarrierBuffer", 430, dev.mcms.glsl.types.VoidType.VOID);
		sig("memoryBarrierShared", 430, dev.mcms.glsl.types.VoidType.VOID,
				EnumSet.of(Stage.COMPUTE));
		sig("memoryBarrierImage", 430, dev.mcms.glsl.types.VoidType.VOID);
		sig("groupMemoryBarrier", 430, dev.mcms.glsl.types.VoidType.VOID,
				EnumSet.of(Stage.COMPUTE));
		sig("barrier", 430, dev.mcms.glsl.types.VoidType.VOID, EnumSet.of(Stage.COMPUTE));
	}

	private static void atomicCounters() {
		GlslType counter = AtomicCounterType.ATOMIC_UINT;
		sig("atomicCounterIncrement", 420, U, in(counter));
		sig("atomicCounterDecrement", 420, U, in(counter));
		sig("atomicCounter", 420, U, in(counter));
	}

	private static void atomicMemory() {
		String[] names = {"atomicAdd", "atomicMin", "atomicMax", "atomicAnd", "atomicOr", "atomicXor",
				"atomicExchange"};
		for (String name : names) {
			sig(name, 430, U, inout(U), in(U));
			sig(name, 430, I, inout(I), in(I));
		}
		sig("atomicCompSwap", 430, U, inout(U), in(U), in(U));
		sig("atomicCompSwap", 430, I, inout(I), in(I), in(I));
	}

	// --- image functions ---

	private static void imageFunctions() {
		for (ScalarKind texel : new ScalarKind[] {ScalarKind.FLOAT, ScalarKind.INT, ScalarKind.UINT}) {
			for (SamplerDim dim : SamplerDim.values()) {
				ImageSigs.add(dim, texel);
			}
		}
	}

	private static final class ImageSigs {
		private static void add(SamplerDim dim, ScalarKind texel) {
			GlslType image = new dev.mcms.glsl.types.ImageType(dim, texel);
			GlslType gvec4 = VectorType.of(texel, 4);
			GlslType p = switch (dim) {
				case D1 -> I;
				case D2, D2MS, RECT -> IVEC2;
				case D3, CUBE, D2MS_ARRAY -> IVEC3;
				case D1_ARRAY -> IVEC2;
				case D2_ARRAY, CUBE_ARRAY -> IVEC3;
				case BUFFER -> I;
			};
			if (dim == SamplerDim.BUFFER) {
				sig("imageLoad", 420, gvec4, in(image), in(I));
				sig("imageStore", 420, dev.mcms.glsl.types.VoidType.VOID, in(image), in(I), in(gvec4));
			} else if (dim.multisample()) {
				GlslType sample2 = dim == SamplerDim.D2MS ? IVEC2 : IVEC3;
				sig("imageLoad", 420, gvec4, in(image), in(sample2), in(I));
				sig("imageStore", 420, dev.mcms.glsl.types.VoidType.VOID, in(image), in(sample2), in(I),
						in(gvec4));
			} else {
				sig("imageLoad", 420, gvec4, in(image), in(p));
				sig("imageStore", 420, dev.mcms.glsl.types.VoidType.VOID, in(image), in(p), in(gvec4));
			}
			GlslType size = switch (dim) {
				case D1, BUFFER -> I;
				case D2, CUBE, RECT, D1_ARRAY, D2MS -> IVEC2;
				case D3, D2_ARRAY, CUBE_ARRAY, D2MS_ARRAY -> IVEC3;
			};
			sig("imageSize", 430, size, in(image));
			if (dim.multisample()) {
				sig("imageSamples", 450, I, in(image));
			}
			if (texel != ScalarKind.FLOAT) {
				for (String name : new String[] {"imageAtomicAdd", "imageAtomicMin", "imageAtomicMax",
						"imageAtomicAnd", "imageAtomicOr", "imageAtomicXor", "imageAtomicExchange"}) {
					GlslType data = ScalarType.of(texel);
					if (dim.multisample()) {
						GlslType sample2 = dim == SamplerDim.D2MS ? IVEC2 : IVEC3;
						sig(name, 430, data, in(image), in(sample2), in(I), in(data));
					} else {
						sig(name, 430, data, in(image), in(p), in(data));
					}
				}
				if (!dim.multisample()) {
					sig("imageAtomicCompSwap", 430, ScalarType.of(texel), in(image), in(p),
							in(ScalarType.of(texel)), in(ScalarType.of(texel)));
				}
			}
		}
	}

	// --- subgroup operations (GL_KHR_shader_subgroup_*) ---

	private static void subgroups() {
		String arithmetic = "GL_KHR_shader_subgroup_arithmetic";
		String basic = "GL_KHR_shader_subgroup_basic";
		for (String name : new String[] {"subgroupAdd", "subgroupMul", "subgroupMin", "subgroupMax",
				"subgroupAnd", "subgroupOr", "subgroupXor"}) {
			for (int n = 1; n <= 4; n++) {
				sig(name, 460, fam(ScalarKind.FLOAT, n), null, false, arithmetic, in(fam(ScalarKind.FLOAT, n)));
				sig(name, 460, fam(ScalarKind.INT, n), null, false, arithmetic, in(fam(ScalarKind.INT, n)));
				sig(name, 460, fam(ScalarKind.UINT, n), null, false, arithmetic, in(fam(ScalarKind.UINT, n)));
			}
		}
		for (int n = 1; n <= 4; n++) {
			GlslType f = fam(ScalarKind.FLOAT, n);
			GlslType i = fam(ScalarKind.INT, n);
			sig("subgroupBroadcast", 460, f, null, false, basic, in(f), in(I));
			sig("subgroupBroadcast", 460, i, null, false, basic, in(i), in(I));
			sig("subgroupBroadcastFirst", 460, f, null, false, basic, in(f));
			sig("subgroupBroadcastFirst", 460, i, null, false, basic, in(i));
		}
		sig("subgroupElect", 460, B, null, false, basic);
	}

	// --- legacy (compatibility) texture names ---

	/**
	 * The legacy names for every element type of a sampler: a float sampler gives vec4, an isampler ivec4 and a usampler
	 * uvec4, as the modern texture functions do (the engine runs the 4.60 feature set at every version, ADR 0006 E6;
	 * Complementary's gbuffers_terrain samples its own usampler2D with texture2D). The shadow names take float samplers.
	 */
	private static void legacyTexture() {
		for (ScalarKind texel : new ScalarKind[] {ScalarKind.FLOAT, ScalarKind.INT, ScalarKind.UINT}) {
			legacyColorTexture(texel);
		}
		legacyShadowTexture();
	}

	private static void legacyColorTexture(ScalarKind texel) {
		boolean compat = true;
		GlslType res = VectorType.of(texel, 4);
		GlslType s1 = new SamplerType(SamplerDim.D1, false, texel);
		GlslType s2 = new SamplerType(SamplerDim.D2, false, texel);
		GlslType s3 = new SamplerType(SamplerDim.D3, false, texel);
		GlslType sc = new SamplerType(SamplerDim.CUBE, false, texel);
		sig("texture1D", 110, res, null, compat, null, in(s1), in(F));
		sig("texture1D", 110, res, null, compat, null, in(s1), in(F), in(F));
		sig("texture2D", 110, res, null, compat, null, in(s2), in(VEC2));
		sig("texture2D", 110, res, null, compat, null, in(s2), in(VEC2), in(F));
		sig("texture3D", 110, res, null, compat, null, in(s3), in(VEC3));
		sig("texture3D", 110, res, null, compat, null, in(s3), in(VEC3), in(F));
		sig("textureCube", 110, res, null, compat, null, in(sc), in(VEC3));
		sig("textureCube", 110, res, null, compat, null, in(sc), in(VEC3), in(F));
		sig("texture1DProj", 110, res, null, compat, null, in(s1), in(VEC2));
		sig("texture1DProj", 110, res, null, compat, null, in(s1), in(VEC2), in(F));
		sig("texture1DProj", 110, res, null, compat, null, in(s1), in(VEC4));
		sig("texture1DProj", 110, res, null, compat, null, in(s1), in(VEC4), in(F));
		sig("texture2DProj", 110, res, null, compat, null, in(s2), in(VEC3));
		sig("texture2DProj", 110, res, null, compat, null, in(s2), in(VEC3), in(F));
		sig("texture2DProj", 110, res, null, compat, null, in(s2), in(VEC4));
		sig("texture2DProj", 110, res, null, compat, null, in(s2), in(VEC4), in(F));
		sig("texture3DProj", 110, res, null, compat, null, in(s3), in(VEC4));
		sig("texture3DProj", 110, res, null, compat, null, in(s3), in(VEC4), in(F));
		// LOD forms: vertex shaders since 1.10; the fragment stage needs GL_ARB_shader_texture_lod (or 1.30+)
		lod("texture1DLod", s1, F, res);
		lod("texture2DLod", s2, VEC2, res);
		lod("texture3DLod", s3, VEC3, res);
		lod("textureCubeLod", sc, VEC3, res);
		lodProj("texture1DProjLod", s1, VEC2, res);
		lodProj("texture1DProjLod", s1, VEC4, res);
		lodProj("texture2DProjLod", s2, VEC3, res);
		lodProj("texture2DProjLod", s2, VEC4, res);
		lodProj("texture3DProjLod", s3, VEC4, res);
		// gradient forms from GL_ARB_shader_texture_lod (the ARB names end in ARB)
		String ext = "GL_ARB_shader_texture_lod";
		sig("texture1DGradARB", 110, res, null, compat, ext, in(s1), in(F), in(F), in(F));
		sig("texture2DGradARB", 110, res, null, compat, ext, in(s2), in(VEC2), in(VEC2), in(VEC2));
		sig("texture3DGradARB", 110, res, null, compat, ext, in(s3), in(VEC3), in(VEC3), in(VEC3));
		sig("textureCubeGradARB", 110, res, null, compat, ext, in(sc), in(VEC3), in(VEC3), in(VEC3));
		sig("texture2DProjGradARB", 110, res, null, compat, ext, in(s2), in(VEC3), in(VEC2), in(VEC2));
		// texelFetch1D/2D/3D from GL_EXT_gpu_shader4 (the pre-1.30 spelling of texelFetch)
		String ext4 = "GL_EXT_gpu_shader4";
		sig("texelFetch1D", 110, res, null, compat, ext4, in(s1), in(I), in(I));
		sig("texelFetch2D", 110, res, null, compat, ext4, in(s2), in(IVEC2), in(I));
		sig("texelFetch3D", 110, res, null, compat, ext4, in(s3), in(IVEC3), in(I));
	}

	private static void legacyShadowTexture() {
		boolean compat = true;
		GlslType s1s = new SamplerType(SamplerDim.D1, true, ScalarKind.FLOAT);
		GlslType s2s = new SamplerType(SamplerDim.D2, true, ScalarKind.FLOAT);
		sig("shadow1D", 110, VEC4, null, compat, null, in(s1s), in(VEC3));
		sig("shadow2D", 110, VEC4, null, compat, null, in(s2s), in(VEC3));
		sig("shadow1DProj", 110, VEC4, null, compat, null, in(s1s), in(VEC4));
		sig("shadow2DProj", 110, VEC4, null, compat, null, in(s2s), in(VEC4));
		lod("shadow1DLod", s1s, VEC3, VEC4);
		lod("shadow2DLod", s2s, VEC3, VEC4);
		lodProj("shadow1DProjLod", s1s, VEC4, VEC4);
		lodProj("shadow2DProjLod", s2s, VEC4, VEC4);
		sig("shadow2DGradARB", 110, VEC4, null, compat, "GL_ARB_shader_texture_lod", in(s2s), in(VEC3), in(VEC2),
			in(VEC2));
		// texelFetch on a 2D shadow sampler: GLSL declares none, but Bliss 2.1.2 calls texelFetch2D on its sampler2DShadow
		// `shadow` (dimensions/composite.fsh) and Iris and Vitrail both draw the pack (E7, results/*-e7-s2probe). The engine
		// reads the stored depth without a comparison, as core GL reads a depth texture: (d, 0, 0, 1).
		sig("texelFetch2D", 110, VEC4, null, compat, "GL_EXT_gpu_shader4", in(s2s), in(IVEC2), in(I));
		sig("texelFetch", 130, VEC4, null, compat, null, in(s2s), in(IVEC2), in(I));
	}

	private static void lod(String name, GlslType sampler, GlslType coord, GlslType result) {
		EnumSet<Stage> vertex = EnumSet.of(Stage.VERTEX);
		sig(name, 110, result, vertex, true, null, in(sampler), in(coord), in(F));
		String ext = "GL_ARB_shader_texture_lod";
		sig(name, 110, result, null, true, ext, in(sampler), in(coord), in(F));
		sig(name, 130, result, null, true, null, in(sampler), in(coord), in(F));
	}

	private static void lodProj(String name, GlslType sampler, GlslType coord, GlslType result) {
		EnumSet<Stage> vertex = EnumSet.of(Stage.VERTEX);
		sig(name, 110, result, vertex, true, null, in(sampler), in(coord), in(F));
		String ext = "GL_ARB_shader_texture_lod";
		sig(name, 110, result, null, true, ext, in(sampler), in(coord), in(F));
		sig(name, 130, result, null, true, null, in(sampler), in(coord), in(F));
	}

	// --- modern texture functions (1.30+), expanded per sampler kind ---

	private static void textureFunctions() {
		for (ScalarKind texel : new ScalarKind[] {ScalarKind.FLOAT, ScalarKind.INT, ScalarKind.UINT}) {
			for (SamplerDim dim : SamplerDim.values()) {
				if (dim == SamplerDim.BUFFER || dim.multisample()) {
					continue; // no float-coordinate lookups on buffer and multisample samplers
				}
				addLookups(dim, false, texel);
			}
		}
		for (SamplerDim dim : new SamplerDim[] {SamplerDim.D1, SamplerDim.D2, SamplerDim.CUBE, SamplerDim.RECT,
				SamplerDim.D1_ARRAY, SamplerDim.D2_ARRAY, SamplerDim.CUBE_ARRAY}) {
			addLookups(dim, true, ScalarKind.FLOAT);
		}
		for (ScalarKind texel : new ScalarKind[] {ScalarKind.FLOAT, ScalarKind.INT, ScalarKind.UINT}) {
			for (SamplerDim dim : SamplerDim.values()) {
				addFetchAndSize(dim, texel);
			}
		}
		for (SamplerDim dim : new SamplerDim[] {SamplerDim.D1, SamplerDim.D2, SamplerDim.CUBE, SamplerDim.RECT,
				SamplerDim.D1_ARRAY, SamplerDim.D2_ARRAY, SamplerDim.CUBE_ARRAY}) {
			addShadowSize(dim);
		}
		gather();
		query();
	}

	/** The coordinate type of a non-projective lookup: components plus one for arrayed textures. */
	private static GlslType coord(SamplerDim dim) {
		int size = dim.coords() + (dim.arrayed() ? 1 : 0); // the layer of an arrayed texture is one more component
		return size == 1 ? F : VectorType.of(ScalarKind.FLOAT, size);
	}

	/**
	 * The coordinate type of a shadow lookup: components, plus the layer for arrayed samplers, plus the reference
	 * (except cube-array, whose reference is a separate argument). A 1D shadow lookup takes vec3 (the second component
	 * is unused). Specification sections 8.9.1 and 8.9.2.
	 */
	private static GlslType shadowCoord(SamplerDim dim) {
		int size = dim.coords() + (dim.arrayed() ? 1 : 0) + (dim == SamplerDim.CUBE_ARRAY ? 0 : 1);
		return VectorType.of(ScalarKind.FLOAT, dim == SamplerDim.D1 ? Math.max(size, 3) : size);
	}

	private static GlslType offsetType(SamplerDim dim) {
		return switch (dim) {
			case D1, D1_ARRAY -> I;
			case D2, D2_ARRAY, RECT -> IVEC2;
			case D3 -> IVEC3;
			default -> null;
		};
	}

	private static GlslType gradType(SamplerDim dim) {
		int size = dim.coords();
		return size == 1 ? F : VectorType.of(ScalarKind.FLOAT, size);
	}

	private static GlslType fetchCoord(SamplerDim dim) {
		int size = dim.coords() + (dim.arrayed() ? 1 : 0);
		return size == 1 ? I : VectorType.of(ScalarKind.INT, size);
	}

	/**
	 * The textureSize result (4.60 section 8.9): one component per coordinate, cube maps give two, and an array layer
	 * count is the last component of the array forms.
	 */
	private static GlslType sizeType(SamplerDim dim) {
		return switch (dim) {
			case D1, BUFFER -> I;
			case D2, RECT, CUBE, D2MS, D1_ARRAY -> IVEC2;
			case D3, D2_ARRAY, CUBE_ARRAY, D2MS_ARRAY -> IVEC3;
		};
	}

	/** textureSize of a shadow sampler (4.60 section 8.9): the same size types, float samplers only. */
	private static void addShadowSize(SamplerDim dim) {
		GlslType s = new SamplerType(dim, true, ScalarKind.FLOAT);
		int since = dim == SamplerDim.CUBE_ARRAY ? 400 : 130;
		GlslType size = sizeType(dim);
		if (dim == SamplerDim.RECT) {
			sig("textureSize", since, size, in(s));
		} else {
			sig("textureSize", since, size, in(s), in(I));
		}
	}

	/** The projective coordinate types of textureProjOffset for a sampler kind (4.60 section 8.9.2). */
	private static List<GlslType> projOffsetCoords(SamplerDim dim, boolean shadow) {
		if (shadow) {
			return dim == SamplerDim.D1 || dim == SamplerDim.D2 || dim == SamplerDim.RECT ? List.of(VEC4) : List.of();
		}
		return switch (dim) {
			case D1 -> List.of(VEC2, VEC4);
			case D2, RECT -> List.of(VEC3, VEC4);
			case D3 -> List.of(VEC4);
			default -> List.of();
		};
	}

	private static void addLookups(SamplerDim dim, boolean shadow, ScalarKind texel) {
		GlslType s = new SamplerType(dim, shadow, texel);
		GlslType result = shadow ? F : VectorType.of(texel, 4);
		GlslType p = shadow ? shadowCoord(dim) : coord(dim);
		boolean mips = dim != SamplerDim.RECT;
		// bias forms: every mipmap dimension except the 2D-array and cube-array shadow forms; the
		// cube-array shadow form always takes its explicit compare (4.60 specification, section 8.9.2)
		boolean bias = mips && !(shadow && (dim == SamplerDim.D2_ARRAY || dim == SamplerDim.CUBE_ARRAY));
		sig("texture", 130, result, in(s), in(p));
		if (bias) {
			sig("texture", 130, result, in(s), in(p), in(F));
		}
		if (dim == SamplerDim.CUBE_ARRAY && shadow) {
			sig("texture", 400, result, in(s), in(p), in(F));
		}
		// textureProj: 1D and 2D also take the vec4 form; 3D and shadow only vec4; rect without bias
		if (!shadow) {
			switch (dim) {
				case D1 -> {
					sig("textureProj", 130, result, in(s), in(VEC2));
					if (mips) {
						sig("textureProj", 130, result, in(s), in(VEC2), in(F));
					}
					sig("textureProj", 130, result, in(s), in(VEC4));
					if (mips) {
						sig("textureProj", 130, result, in(s), in(VEC4), in(F));
					}
				}
				case D2 -> {
					sig("textureProj", 130, result, in(s), in(VEC3));
					if (mips) {
						sig("textureProj", 130, result, in(s), in(VEC3), in(F));
					}
					sig("textureProj", 130, result, in(s), in(VEC4));
					if (mips) {
						sig("textureProj", 130, result, in(s), in(VEC4), in(F));
					}
				}
				case D3 -> {
					sig("textureProj", 130, result, in(s), in(VEC4));
					sig("textureProj", 130, result, in(s), in(VEC4), in(F));
				}
				case RECT -> {
					sig("textureProj", 130, result, in(s), in(VEC3));
					sig("textureProj", 130, result, in(s), in(VEC4));
				}
				default -> {
				}
			}
		} else if (dim == SamplerDim.D1 || dim == SamplerDim.D2 || dim == SamplerDim.RECT) {
			sig("textureProj", 130, result, in(s), in(VEC4));
			if (mips) {
				sig("textureProj", 130, result, in(s), in(VEC4), in(F));
			}
		}
		// textureLod (no rect, no cube-shadow exceptions; the shadow forms keep the plain coordinate)
		if (mips && !(shadow && (dim == SamplerDim.CUBE || dim == SamplerDim.CUBE_ARRAY))) {
			sig("textureLod", 130, result, in(s), in(p), in(F));
		}
		// textureOffset
		GlslType off = offsetType(dim);
		if (off != null) {
			sig("textureOffset", 130, result, in(s), in(p), k(off));
			if (bias) {
				sig("textureOffset", 130, result, in(s), in(p), k(off), in(F));
			}
			sig("textureLodOffset", 130, result, in(s), in(p), in(F), k(off));
			GlslType g = gradType(dim);
			sig("textureGradOffset", 130, result, in(s), in(p), in(g), in(g), k(off));
			// proj + offset (4.60 section 8.9.2): the projective coordinate forms of each sampler kind
			for (GlslType pj : projOffsetCoords(dim, shadow)) {
				sig("textureProjOffset", 130, result, in(s), in(pj), k(off));
				if (mips) {
					sig("textureProjOffset", 130, result, in(s), in(pj), k(off), in(F));
				}
				if (!shadow && (dim == SamplerDim.D1 || dim == SamplerDim.D2 || dim == SamplerDim.D3)) {
					sig("textureProjLodOffset", 130, result, in(s), in(pj), in(F), k(off));
					sig("textureProjGradOffset", 130, result, in(s), in(pj), in(g), in(g), k(off));
				}
			}
		}
		// textureGrad
		GlslType g = gradType(dim);
		sig("textureGrad", 130, result, in(s), in(p), in(g), in(g));
		// proj plain and lod
		if (!shadow) {
			switch (dim) {
				case D1 -> {
					sig("textureProjLod", 130, result, in(s), in(VEC2), in(F));
					sig("textureProjLod", 130, result, in(s), in(VEC4), in(F));
					sig("textureProjGrad", 130, result, in(s), in(VEC2), in(g), in(g));
					sig("textureProjGrad", 130, result, in(s), in(VEC4), in(g), in(g));
				}
				case D2 -> {
					sig("textureProjLod", 130, result, in(s), in(VEC3), in(F));
					sig("textureProjLod", 130, result, in(s), in(VEC4), in(F));
					sig("textureProjGrad", 130, result, in(s), in(VEC3), in(g), in(g));
					sig("textureProjGrad", 130, result, in(s), in(VEC4), in(g), in(g));
				}
				case D3 -> {
					sig("textureProjLod", 130, result, in(s), in(VEC4), in(F));
					sig("textureProjGrad", 130, result, in(s), in(VEC4), in(g), in(g));
				}
				default -> {
				}
			}
		} else if (dim == SamplerDim.D1 || dim == SamplerDim.D2) {
			sig("textureProjLod", 130, result, in(s), in(VEC4), in(F));
			sig("textureProjGrad", 130, result, in(s), in(VEC4), in(g), in(g));
		}
	}

	private static void addFetchAndSize(SamplerDim dim, ScalarKind texel) {
		GlslType s = new SamplerType(dim, false, texel);
		GlslType result = VectorType.of(texel, 4);
		GlslType p = fetchCoord(dim);
		boolean lod = dim != SamplerDim.BUFFER && dim != SamplerDim.RECT && !dim.multisample();
		if (dim.multisample()) {
			sig("texelFetch", 130, result, in(s), in(p), in(I));
		} else if (lod) {
			sig("texelFetch", 130, result, in(s), in(p), in(I));
		} else {
			sig("texelFetch", 130, result, in(s), in(p));
		}
		GlslType off = offsetType(dim);
		if (off != null && lod) {
			sig("texelFetchOffset", 130, result, in(s), in(p), in(I), k(off));
		}
		GlslType size = sizeType(dim);
		int since = switch (dim) {
			case BUFFER -> 140;
			case D2MS, D2MS_ARRAY -> 150;
			case CUBE_ARRAY -> 400;
			default -> 130;
		};
		if (lod) {
			sig("textureSize", since, size, in(s), in(I));
		} else {
			sig("textureSize", since, size, in(s));
		}
	}

	private static void gather() {
		for (ScalarKind texel : new ScalarKind[] {ScalarKind.FLOAT, ScalarKind.INT, ScalarKind.UINT}) {
			for (SamplerDim dim : new SamplerDim[] {SamplerDim.D2, SamplerDim.D2_ARRAY, SamplerDim.CUBE,
					SamplerDim.CUBE_ARRAY, SamplerDim.RECT}) {
				GlslType s = new SamplerType(dim, false, texel);
				GlslType result = VectorType.of(texel, 4);
				GlslType p = coord(dim);
				sig("textureGather", 400, result, in(s), in(p));
				sig("textureGather", 400, result, in(s), in(p), k(I));
				if (offsetType(dim) == IVEC2) {
					sig("textureGatherOffset", 400, result, in(s), in(p), in(IVEC2));
					sig("textureGatherOffset", 400, result, in(s), in(p), in(IVEC2), k(I));
					sig("textureGatherOffsets", 400, result, in(s), in(p),
							k(new ArrayType(IVEC2, 4)));
					sig("textureGatherOffsets", 400, result, in(s), in(p),
							k(new ArrayType(IVEC2, 4)), k(I));
				}
			}
			for (SamplerDim dim : new SamplerDim[] {SamplerDim.D2, SamplerDim.D2_ARRAY, SamplerDim.CUBE,
					SamplerDim.CUBE_ARRAY, SamplerDim.RECT}) {
				GlslType s = new SamplerType(dim, true, ScalarKind.FLOAT);
				GlslType p = dim == SamplerDim.D2 || dim == SamplerDim.RECT ? VEC2 : VEC3;
				if (dim == SamplerDim.CUBE_ARRAY) {
					p = VEC4;
				}
				sig("textureGather", 400, VEC4, in(s), in(p), in(F));
				if (offsetType(dim) == IVEC2) {
					sig("textureGatherOffset", 400, VEC4, in(s), in(p), in(F), in(IVEC2));
					sig("textureGatherOffsets", 400, VEC4, in(s), in(p), in(F),
							k(new ArrayType(IVEC2, 4)));
				}
			}
		}
	}

	private static void query() {
		sig("textureQueryLod", 400, VEC2, EnumSet.of(Stage.VERTEX, Stage.FRAGMENT), in(
				new SamplerType(SamplerDim.D1, false, ScalarKind.FLOAT)), in(F));
		for (SamplerDim dim : SamplerDim.values()) {
			if (dim == SamplerDim.BUFFER || dim.multisample()) {
				continue;
			}
			GlslType p = coord(dim);
			for (ScalarKind texel : new ScalarKind[] {ScalarKind.FLOAT, ScalarKind.INT, ScalarKind.UINT}) {
				sig("textureQueryLod", 400, VEC2, EnumSet.of(Stage.VERTEX, Stage.FRAGMENT), in(
						new SamplerType(dim, false, texel)), in(p));
			}
			if (canShadow(dim)) {
				sig("textureQueryLod", 400, VEC2, EnumSet.of(Stage.VERTEX, Stage.FRAGMENT), in(
						new SamplerType(dim, true, ScalarKind.FLOAT)), in(p));
			}
			for (ScalarKind texel : new ScalarKind[] {ScalarKind.FLOAT, ScalarKind.INT, ScalarKind.UINT}) {
				sig("textureQueryLevels", 430, I, in(new SamplerType(dim, false, texel)));
			}
			if (canShadow(dim)) {
				sig("textureQueryLevels", 430, I, in(new SamplerType(dim, true, ScalarKind.FLOAT)));
			}
		}
		for (SamplerDim dim : new SamplerDim[] {SamplerDim.D2MS, SamplerDim.D2MS_ARRAY}) {
			for (ScalarKind texel : new ScalarKind[] {ScalarKind.FLOAT, ScalarKind.INT, ScalarKind.UINT}) {
				sig("textureSamples", 450, I, in(new SamplerType(dim, false, texel)));
			}
		}
	}

	private static boolean canShadow(SamplerDim dim) {
		return dim != SamplerDim.BUFFER && !dim.multisample();
	}
}
