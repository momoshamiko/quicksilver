// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.msl.builtins;

import java.util.ArrayList;
import java.util.List;

/**
 * Every built-in function signature of GLSL 4.60 chapter 8 (with the compatibility texture names of 8.9.5 and the
 * extension names packs still use), each marked mapped or unsupported with its reason by asking {@link BuiltinTable}
 * with placeholder arguments (W2-C, ADR 0006). This is the coverage list E2 reports and the plain tests check against
 * the specification's function list.
 */
public final class BuiltinCatalog {

	/** One overload: GLSL name, argument types, result type, and the table's verdict on it. */
	public record Signature(String name, List<String> argTypes, String resultType, boolean supported,
							String reason) {
	}

	private final BuiltinTable table;
	private final List<Signature> signatures = new ArrayList<>();

	public static BuiltinCatalog of(BuiltinTable table) {
		BuiltinCatalog catalog = new BuiltinCatalog(table);
		catalog.build();
		return catalog;
	}

	private BuiltinCatalog(BuiltinTable table) {
		this.table = table;
	}

	public List<Signature> signatures() {
		return List.copyOf(signatures);
	}

	private void build() {
		List<String> f = List.of("float", "vec2", "vec3", "vec4");
		List<String> fi = List.of("int", "ivec2", "ivec3", "ivec4");
		List<String> fu = List.of("uint", "uvec2", "uvec3", "uvec4");
		List<String> fb = List.of("bool", "bvec2", "bvec3", "bvec4");
		List<String> fd = List.of("double", "dvec2", "dvec3", "dvec4");

		// 8.1, 8.2: unary genFType (and genDType).
		for (String n : List.of("radians", "degrees", "sin", "cos", "asin", "acos", "sinh", "cosh", "asinh",
			"acosh", "atanh", "exp", "log", "exp2", "log2", "sqrt", "inversesqrt"))
			for (String t : f) sig(n, List.of(t), t);
		for (String n : List.of("radians", "degrees", "sin", "cos", "asin", "acos", "sinh", "cosh", "asinh",
			"acosh", "atanh", "exp", "log", "exp2", "log2", "sqrt", "inversesqrt"))
			for (String t : fd) sig(n, List.of(t), t);
		for (String t : f) sig("atan", List.of(t), t);
		for (String t : f) sig("atan", List.of(t, t), t);
		for (String t : fd) sig("atan", List.of(t), t);
		for (String t : fd) sig("atan", List.of(t, t), t);
		for (String t : f) sig("pow", List.of(t, t), t);
		for (String t : fd) sig("pow", List.of(t, t), t);

		// 8.3 common.
		for (String n : List.of("abs", "sign", "floor", "trunc", "round", "roundEven", "ceil", "fract"))
			for (String t : f) sig(n, List.of(t), t);
		for (String n : List.of("abs", "sign"))
			for (String t : fi) sig(n, List.of(t), t);
		for (String t : fd) sig("abs", List.of(t), t);
		for (String t : fd) sig("sign", List.of(t), t);
		for (String t : List.of("float", "vec2", "vec3", "vec4")) {
			sig("mod", List.of(t, "float"), t);
			sig("mod", List.of(t, t), t);
		}
		for (String t : fd) sig("mod", List.of(t, "double"), t);
		for (String t : fd) sig("mod", List.of(t, t), t);
		for (String t : f) sig("modf", List.of(t, t), t);
		for (String t : fd) sig("modf", List.of(t, t), t);
		for (String t : f) sig("min", List.of(t, t), t);
		for (String t : f) sig("min", List.of(t, "float"), t);
		for (String t : fi) sig("min", List.of(t, t), t);
		for (String t : fi) sig("min", List.of(t, "int"), t);
		for (String t : fu) sig("min", List.of(t, t), t);
		for (String t : fu) sig("min", List.of(t, "uint"), t);
		for (String t : f) sig("max", List.of(t, t), t);
		for (String t : f) sig("max", List.of(t, "float"), t);
		for (String t : fi) sig("max", List.of(t, t), t);
		for (String t : fi) sig("max", List.of(t, "int"), t);
		for (String t : fu) sig("max", List.of(t, t), t);
		for (String t : fu) sig("max", List.of(t, "uint"), t);
		for (String t : f) sig("clamp", List.of(t, t, t), t);
		for (String t : f) sig("clamp", List.of(t, "float", "float"), t);
		for (String t : fi) sig("clamp", List.of(t, t, t), t);
		for (String t : fi) sig("clamp", List.of(t, "int", "int"), t);
		for (String t : fu) sig("clamp", List.of(t, t, t), t);
		for (String t : fu) sig("clamp", List.of(t, "uint", "uint"), t);
		for (String t : f) sig("mix", List.of(t, t, t), t);
		for (String t : f) sig("mix", List.of(t, t, "float"), t);
		for (String t : fd) sig("mix", List.of(t, t, t), t);
		for (String t : List.of("float", "vec2", "vec3", "vec4"))
			sig("mix", List.of(t, t, bvecOf(t)), t);
		for (String t : fi) sig("mix", List.of(t, t, bvecOf(t)), t);
		for (String t : fu) sig("mix", List.of(t, t, bvecOf(t)), t);
		for (String t : fb) sig("mix", List.of(t, t, bvecOf(t)), t);
		for (String t : f) sig("step", List.of(t, t), t);
		for (String t : f) sig("step", List.of("float", t), t);
		for (String t : f) sig("smoothstep", List.of(t, t, t), t);
		for (String t : f) sig("smoothstep", List.of("float", "float", t), t);
		for (String t : f) sig("isnan", List.of(t), bvecOf(t));
		for (String t : f) sig("isinf", List.of(t), bvecOf(t));
		for (String t : f) sig("floatBitsToInt", List.of(t), ivecOf(t));
		for (String t : f) sig("floatBitsToUint", List.of(t), uvecOf(t));
		for (String t : fi) sig("intBitsToFloat", List.of(t), vecOf(t));
		for (String t : fu) sig("uintBitsToFloat", List.of(t), vecOf(t));
		for (String t : f) sig("fma", List.of(t, t, t), t);
		for (String t : f) sig("frexp", List.of(t, ivecOf(t)), t);
		for (String t : f) sig("ldexp", List.of(t, ivecOf(t)), t);

		// 8.4 packing.
		sig("packUnorm2x16", List.of("vec2"), "uint");
		sig("packSnorm2x16", List.of("vec2"), "uint");
		sig("packUnorm4x8", List.of("vec4"), "uint");
		sig("packSnorm4x8", List.of("vec4"), "uint");
		sig("unpackUnorm2x16", List.of("uint"), "vec2");
		sig("unpackSnorm2x16", List.of("uint"), "vec2");
		sig("unpackUnorm4x8", List.of("uint"), "vec4");
		sig("unpackSnorm4x8", List.of("uint"), "vec4");
		sig("packHalf2x16", List.of("vec2"), "uint");
		sig("unpackHalf2x16", List.of("uint"), "vec2");
		sig("packDouble2x32", List.of("uvec2"), "double");
		sig("unpackDouble2x32", List.of("double"), "uvec2");

		// 8.5 geometric.
		for (String t : f) sig("length", List.of(t), "float");
		for (String t : f) sig("distance", List.of(t, t), "float");
		for (String t : f) sig("dot", List.of(t, t), "float");
		sig("cross", List.of("vec3", "vec3"), "vec3");
		for (String t : f) sig("normalize", List.of(t), t);
		for (String t : f) sig("faceforward", List.of(t, t, t), t);
		for (String t : f) sig("reflect", List.of(t, t), t);
		for (String t : f) sig("refract", List.of(t, t, "float"), t);
		for (String t : fd) for (int i = 0; i < 5; i++)
			sig(List.of("length", "distance", "dot", "normalize", "faceforward", "reflect", "refract").get(i),
				i < 2 ? List.of(t, t) : List.of(t), i < 2 ? "double" : t);
		sig("ftransform", List.of(), "vec4");

		// 8.6 matrix.
		for (int c = 2; c <= 4; c++) {
			for (int r = 2; r <= 4; r++) {
				String m = c == r ? "mat" + c : "mat" + c + 'x' + r;
				sig("matrixCompMult", List.of(m, m), m);
				sig("transpose", List.of(m), c == r ? m : "mat" + r + 'x' + c);
				String rv = "vec" + r, cv = "vec" + c;
				sig("outerProduct", List.of(cv, rv), c == r ? m : "mat" + c + 'x' + r);
			}
		}
		for (String m : List.of("mat2", "mat3", "mat4")) {
			sig("determinant", List.of(m), "float");
			sig("inverse", List.of(m), m);
		}

		// 8.7 relational.
		for (String t : List.of("vec2", "vec3", "vec4", "ivec2", "ivec3", "ivec4", "uvec2", "uvec3", "uvec4")) {
			sig("lessThan", List.of(t, t), bvecOf(t));
			sig("lessThanEqual", List.of(t, t), bvecOf(t));
			sig("greaterThan", List.of(t, t), bvecOf(t));
			sig("greaterThanEqual", List.of(t, t), bvecOf(t));
			sig("equal", List.of(t, t), bvecOf(t));
			sig("notEqual", List.of(t, t), bvecOf(t));
		}
		for (String t : fb) {
			sig("equal", List.of(t, t), t.equals("bool") ? "bool" : bvecOf(t));
			sig("notEqual", List.of(t, t), t.equals("bool") ? "bool" : bvecOf(t));
			sig("not", List.of(t), t.equals("bool") ? "bool" : bvecOf(t));
			if (!t.equals("bool")) sig("any", List.of(t), "bool");
			if (!t.equals("bool")) sig("all", List.of(t), "bool");
		}

		// 8.8 integer.
		for (String t : fu) sig("uaddCarry", List.of(t, t, t), t);
		for (String t : fu) sig("usubBorrow", List.of(t, t, t), t);
		for (String t : fu) sig("umulExtended", List.of(t, t, t, t), "void");
		for (String t : fi) sig("imulExtended", List.of(t, t, t, t), "void");
		for (String t : fi) sig("bitfieldExtract", List.of(t, "int", "int"), t);
		for (String t : fu) sig("bitfieldExtract", List.of(t, "int", "int"), t);
		for (String t : fi) sig("bitfieldInsert", List.of(t, t, "int", "int"), t);
		for (String t : fu) sig("bitfieldInsert", List.of(t, t, "int", "int"), t);
		for (String t : fi) sig("bitfieldReverse", List.of(t), t);
		for (String t : fu) sig("bitfieldReverse", List.of(t), t);
		for (String t : fi) sig("bitCount", List.of(t), ivecOf(t));
		for (String t : fu) sig("bitCount", List.of(t), ivecOf(t));
		for (String t : fi) sig("findLSB", List.of(t), ivecOf(t));
		for (String t : fu) sig("findLSB", List.of(t), ivecOf(t));
		for (String t : fi) sig("findMSB", List.of(t), ivecOf(t));
		for (String t : fu) sig("findMSB", List.of(t), ivecOf(t));

		// 8.9 textures (modern and compatibility names) and queries.
		textures();

		// 8.10 atomic counters.
		for (String n : List.of("atomicCounterIncrement", "atomicCounterDecrement", "atomicCounter"))
			sig(n, List.of("atomic_uint"), "uint");
		for (String n : List.of("atomicCounterAdd", "atomicCounterSubtract", "atomicCounterMin",
			"atomicCounterMax", "atomicCounterAnd", "atomicCounterOr", "atomicCounterXor", "atomicCounterExchange"))
			sig(n, List.of("atomic_uint", "uint"), "uint");
		sig("atomicCounterCompSwap", List.of("atomic_uint", "uint", "uint"), "uint");

		// 8.11 atomic memory.
		for (String n : List.of("atomicAdd", "atomicMin", "atomicMax", "atomicAnd", "atomicOr", "atomicXor",
			"atomicExchange")) {
			sig(n, List.of("uint", "uint"), "uint");
			sig(n, List.of("int", "int"), "int");
		}
		sig("atomicCompSwap", List.of("uint", "uint", "uint"), "uint");
		sig("atomicCompSwap", List.of("int", "int", "int"), "int");

		// 8.12 images.
		images();

		// 8.13 geometry.
		sig("EmitVertex", List.of(), "void");
		sig("EndPrimitive", List.of(), "void");

		// 8.14 fragment processing.
		for (String n : List.of("dFdx", "dFdy", "fwidth", "dFdxFine", "dFdyFine", "fwidthFine", "dFdxCoarse",
			"dFdyCoarse", "fwidthCoarse"))
			for (String t : f) sig(n, List.of(t), t);
		for (String t : f) sig("interpolateAtCentroid", List.of(t), t);
		for (String t : f) sig("interpolateAtSample", List.of(t, "int"), t);
		for (String t : f) sig("interpolateAtOffset", List.of(t, "vec2"), t);

		// 8.15 noise.
		sig("noise1", List.of("float"), "float");
		sig("noise2", List.of("float"), "vec2");
		sig("noise3", List.of("float"), "vec3");
		sig("noise4", List.of("float"), "vec4");

		// 8.16, 8.17 control.
		sig("barrier", List.of(), "void");
		for (String n : List.of("memoryBarrier", "memoryBarrierAtomicCounter", "memoryBarrierBuffer",
			"memoryBarrierShared", "memoryBarrierImage", "groupMemoryBarrier"))
			sig(n, List.of(), "void");

		// 8.18 subpass inputs, 8.19 invocation groups.
		sig("subpassLoad", List.of("subpassInput"), "vec4");
		sig("subpassLoad", List.of("subpassInputMS"), "vec4");
		sig("subpassLoadMS", List.of("subpassInputMS"), "vec4");
		for (String n : List.of("anyInvocation", "allInvocations", "allInvocationsEqual"))
			sig(n, List.of("bool"), "bool");
	}

	// --- 8.9 textures: the overloads of GLSL 4.60 8.9.1 to 8.9.5, per sampler type, from the specification's tables ---

	/** Sampler kinds that take the element prefix (i, u) in GLSL: every kind except the shadow kinds. */
	private static final List<String> SAMPLER_KINDS = List.of("1D", "2D", "3D", "Cube", "1DArray", "2DArray",
		"CubeArray", "2DRect", "2DMS", "2DMSArray", "Buffer");

	/** Shadow sampler kinds (no element prefix). */
	private static final List<String> SHADOW_KINDS = List.of("1DShadow", "2DShadow", "CubeShadow", "1DArrayShadow",
		"2DArrayShadow", "CubeArrayShadow", "2DRectShadow");

	private void textures() {
		for (String e : List.of("", "i", "u"))
			for (String k : SAMPLER_KINDS) textureFunctions(e + "sampler" + k, k);
		for (String k : SHADOW_KINDS) textureFunctions("sampler" + k, k);
		legacyTextures();
	}

	/** The overloads of every texture function for one sampler type (kind is the name without element or sampler). */
	private void textureFunctions(String s, String kind) {
		boolean shadow = kind.endsWith("Shadow");
		String element = s.startsWith("i") ? "i" : s.startsWith("u") ? "u" : "";
		String result = shadow ? "float" : element.isEmpty() ? "vec4" : element.equals("i") ? "ivec4" : "uvec4";
		String gather = shadow ? "vec4" : result;
		family("texture", s, textureOverloads(kind), result);
		family("textureProj", s, projOverloads(kind), result);
		family("textureLod", s, lodOverloads(kind), result);
		family("textureOffset", s, offsetOverloads(kind), result);
		family("textureLodOffset", s, lodOffsetOverloads(kind), result);
		family("textureProjOffset", s, projOffsetOverloads(kind), result);
		family("textureProjLod", s, projLodOverloads(kind), result);
		family("textureProjLodOffset", s, projLodOffsetOverloads(kind), result);
		family("textureGrad", s, gradOverloads(kind), result);
		family("textureGradOffset", s, gradOffsetOverloads(kind), result);
		family("textureProjGrad", s, projGradOverloads(kind), result);
		family("textureProjGradOffset", s, projGradOffsetOverloads(kind), result);
		family("textureGather", s, gatherOverloads(kind), gather);
		family("textureGatherOffset", s, gatherOffsetOverloads(kind), gather);
		family("textureGatherOffsets", s, gatherOffsetsOverloads(kind), gather);
		family("texelFetch", s, fetchOverloads(kind), result);
		family("texelFetchOffset", s, fetchOffsetOverloads(kind), result);
		family("textureSize", s, sizeOverloads(kind), sizeResult(kind));
		family("textureQueryLod", s, queryLodOverloads(kind), "vec2");
		if (!kind.startsWith("2DRect") && !kind.startsWith("2DMS") && !kind.equals("Buffer"))
			family("textureQueryLevels", s, ov(), "int");
		if (kind.startsWith("2DMS")) family("textureSamples", s, ov(), "int");
	}

	/** One function of one family: each overload is the argument list after the sampler. */
	private void family(String name, String s, List<List<String>> overloads, String result) {
		for (List<String> after : overloads) {
			List<String> args = new ArrayList<>();
			args.add(s);
			args.addAll(after);
			sig(name, args, result);
		}
	}

	/** GLSL 8.9.2 texture: the coordinate, with the bias where the specification gives one. */
	private static List<List<String>> textureOverloads(String k) {
		return switch (k) {
			case "1D" -> biased("float");
			case "2D" -> biased("vec2");
			case "3D", "Cube" -> biased("vec3");
			case "1DArray" -> biased("vec2");
			case "2DArray" -> biased("vec3");
			case "CubeArray" -> biased("vec4");
			case "2DRect" -> ov("vec2");
			case "1DShadow", "2DShadow", "1DArrayShadow" -> biased("vec3");
			case "CubeShadow" -> biased("vec4");
			case "2DArrayShadow" -> ov("vec4");
			case "2DRectShadow" -> ov("vec3");
			case "CubeArrayShadow" -> ov("vec4", "float");
			default -> List.of();
		};
	}

	/** GLSL 8.9.2 textureProj: 1D and 2D take vec2/vec3 and vec4; 3D and the shadow forms take vec4. */
	private static List<List<String>> projOverloads(String k) {
		return switch (k) {
			case "1D" -> cat(biased("vec2"), biased("vec4"));
			case "2D" -> cat(biased("vec3"), biased("vec4"));
			case "3D", "1DShadow", "2DShadow" -> biased("vec4");
			case "2DRect" -> cat(ov("vec3"), ov("vec4"));
			case "2DRectShadow" -> ov("vec4");
			default -> List.of();
		};
	}

	private static List<List<String>> lodOverloads(String k) {
		return switch (k) {
			case "1D" -> ov("float", "float");
			case "2D", "1DArray" -> ov("vec2", "float");
			case "3D", "Cube", "2DArray", "2DShadow", "1DShadow", "1DArrayShadow" -> ov("vec3", "float");
			case "CubeArray" -> ov("vec4", "float");
			default -> List.of();
		};
	}

	private static List<List<String>> offsetOverloads(String k) {
		return switch (k) {
			case "1D" -> biased("float", "int");
			case "2D" -> biased("vec2", "ivec2");
			case "3D" -> biased("vec3", "ivec3");
			case "1DArray" -> biased("vec2", "int");
			case "2DArray" -> biased("vec3", "ivec2");
			case "2DRect" -> ov("vec2", "ivec2");
			case "1DShadow", "1DArrayShadow" -> biased("vec3", "int");
			case "2DShadow" -> biased("vec3", "ivec2");
			case "2DArrayShadow" -> ov("vec4", "ivec2");
			case "2DRectShadow" -> ov("vec3", "ivec2");
			default -> List.of();
		};
	}

	private static List<List<String>> lodOffsetOverloads(String k) {
		return switch (k) {
			case "1D" -> ov("float", "float", "int");
			case "2D" -> ov("vec2", "float", "ivec2");
			case "3D" -> ov("vec3", "float", "ivec3");
			case "1DArray" -> ov("vec2", "float", "int");
			case "2DArray" -> ov("vec3", "float", "ivec2");
			case "1DShadow", "1DArrayShadow" -> ov("vec3", "float", "int");
			case "2DShadow" -> ov("vec3", "float", "ivec2");
			default -> List.of();
		};
	}

	private static List<List<String>> projOffsetOverloads(String k) {
		return switch (k) {
			case "1D" -> cat(biased("vec2", "int"), biased("vec4", "int"));
			case "2D" -> cat(biased("vec3", "ivec2"), biased("vec4", "ivec2"));
			case "3D" -> biased("vec4", "ivec3");
			case "2DRect" -> cat(ov("vec3", "ivec2"), ov("vec4", "ivec2"));
			case "1DShadow" -> biased("vec4", "int");
			case "2DShadow" -> biased("vec4", "ivec2");
			case "2DRectShadow" -> ov("vec4", "ivec2");
			default -> List.of();
		};
	}

	private static List<List<String>> projLodOverloads(String k) {
		return switch (k) {
			case "1D" -> cat(ov("vec2", "float"), ov("vec4", "float"));
			case "2D" -> cat(ov("vec3", "float"), ov("vec4", "float"));
			case "3D", "1DShadow", "2DShadow" -> ov("vec4", "float");
			default -> List.of();
		};
	}

	private static List<List<String>> projLodOffsetOverloads(String k) {
		return switch (k) {
			case "1D" -> cat(ov("vec2", "float", "int"), ov("vec4", "float", "int"));
			case "2D" -> cat(ov("vec3", "float", "ivec2"), ov("vec4", "float", "ivec2"));
			case "3D" -> ov("vec4", "float", "ivec3");
			case "1DShadow" -> ov("vec4", "float", "int");
			case "2DShadow" -> ov("vec4", "float", "ivec2");
			default -> List.of();
		};
	}

	/** GLSL 8.9.3 explicit gradients: the gradients have the coordinate's dimension, without the array layer. */
	private static List<List<String>> gradOverloads(String k) {
		return switch (k) {
			case "1D" -> ov("float", "float", "float");
			case "2D", "2DRect" -> ov("vec2", "vec2", "vec2");
			case "3D", "Cube" -> ov("vec3", "vec3", "vec3");
			case "1DArray" -> ov("vec2", "float", "float");
			case "2DArray" -> ov("vec3", "vec2", "vec2");
			case "CubeArray" -> ov("vec4", "vec3", "vec3");
			case "1DShadow", "1DArrayShadow" -> ov("vec3", "float", "float");
			case "2DShadow", "2DRectShadow" -> ov("vec3", "vec2", "vec2");
			case "CubeShadow" -> ov("vec4", "vec3", "vec3");
			case "2DArrayShadow" -> ov("vec4", "vec2", "vec2");
			default -> List.of();
		};
	}

	private static List<List<String>> gradOffsetOverloads(String k) {
		return switch (k) {
			case "1D" -> ov("float", "float", "float", "int");
			case "2D", "2DRect" -> ov("vec2", "vec2", "vec2", "ivec2");
			case "3D" -> ov("vec3", "vec3", "vec3", "ivec3");
			case "1DArray" -> ov("vec2", "float", "float", "int");
			case "2DArray" -> ov("vec3", "vec2", "vec2", "ivec2");
			case "1DShadow", "1DArrayShadow" -> ov("vec3", "float", "float", "int");
			case "2DShadow", "2DRectShadow" -> ov("vec3", "vec2", "vec2", "ivec2");
			case "2DArrayShadow" -> ov("vec4", "vec2", "vec2", "ivec2");
			default -> List.of();
		};
	}

	private static List<List<String>> projGradOverloads(String k) {
		return switch (k) {
			case "1D" -> cat(ov("vec2", "float", "float"), ov("vec4", "float", "float"));
			case "2D", "2DRect" -> cat(ov("vec3", "vec2", "vec2"), ov("vec4", "vec2", "vec2"));
			case "3D" -> ov("vec4", "vec3", "vec3");
			case "1DShadow" -> ov("vec4", "float", "float");
			case "2DShadow", "2DRectShadow" -> ov("vec4", "vec2", "vec2");
			default -> List.of();
		};
	}

	private static List<List<String>> projGradOffsetOverloads(String k) {
		return switch (k) {
			case "1D" -> cat(ov("vec2", "float", "float", "int"), ov("vec4", "float", "float", "int"));
			case "2D", "2DRect" -> cat(ov("vec3", "vec2", "vec2", "ivec2"), ov("vec4", "vec2", "vec2", "ivec2"));
			case "3D" -> ov("vec4", "vec3", "vec3", "ivec3");
			case "1DShadow" -> ov("vec4", "float", "float", "int");
			case "2DShadow", "2DRectShadow" -> ov("vec4", "vec2", "vec2", "ivec2");
			default -> List.of();
		};
	}

	/** GLSL 8.9.4 gather: the optional comp selects the component (non-shadow); shadow forms take refZ. */
	private static List<List<String>> gatherOverloads(String k) {
		return switch (k) {
			case "2D", "2DRect" -> cat(ov("vec2"), ov("vec2", "int"));
			case "2DArray", "Cube" -> cat(ov("vec3"), ov("vec3", "int"));
			case "CubeArray" -> cat(ov("vec4"), ov("vec4", "int"));
			case "2DShadow", "2DRectShadow" -> ov("vec2", "float");
			case "2DArrayShadow", "CubeShadow" -> ov("vec3", "float");
			case "CubeArrayShadow" -> ov("vec4", "float");
			default -> List.of();
		};
	}

	private static List<List<String>> gatherOffsetOverloads(String k) {
		return switch (k) {
			case "2D", "2DRect" -> cat(ov("vec2", "ivec2"), ov("vec2", "ivec2", "int"));
			case "2DArray" -> cat(ov("vec3", "ivec2"), ov("vec3", "ivec2", "int"));
			case "2DShadow", "2DRectShadow" -> ov("vec2", "float", "ivec2");
			case "2DArrayShadow" -> ov("vec3", "float", "ivec2");
			default -> List.of();
		};
	}

	private static List<List<String>> gatherOffsetsOverloads(String k) {
		return switch (k) {
			case "2D", "2DRect" -> cat(ov("vec2", "ivec2[4]"), ov("vec2", "ivec2[4]", "int"));
			case "2DArray" -> cat(ov("vec3", "ivec2[4]"), ov("vec3", "ivec2[4]", "int"));
			case "2DShadow", "2DRectShadow" -> ov("vec2", "float", "ivec2[4]");
			case "2DArrayShadow" -> ov("vec3", "float", "ivec2[4]");
			default -> List.of();
		};
	}

	/** GLSL 8.9.1 and 8.9.2 texelFetch: integer coordinates; the array layer is the last component. */
	private static List<List<String>> fetchOverloads(String k) {
		return switch (k) {
			case "1D" -> ov("int", "int");
			case "2D", "2DMS" -> ov("ivec2", "int");
			case "3D" -> ov("ivec3", "int");
			case "2DRect" -> ov("ivec2");
			case "1DArray" -> ov("ivec2", "int");
			case "2DArray", "2DMSArray" -> ov("ivec3", "int");
			case "Buffer" -> ov("int");
			default -> List.of();
		};
	}

	private static List<List<String>> fetchOffsetOverloads(String k) {
		return switch (k) {
			case "1D" -> ov("int", "int", "int");
			case "2D" -> ov("ivec2", "int", "ivec2");
			case "3D" -> ov("ivec3", "int", "ivec3");
			case "2DRect" -> ov("ivec2", "ivec2");
			case "1DArray" -> ov("ivec2", "int", "int");
			case "2DArray" -> ov("ivec3", "int", "ivec2");
			default -> List.of();
		};
	}

	/** GLSL 8.9.1 textureSize: the level, except for the kinds that have one level only. */
	private static List<List<String>> sizeOverloads(String k) {
		return switch (k) {
			case "2DRect", "2DRectShadow", "2DMS", "2DMSArray", "Buffer" -> ov();
			default -> ov("int");
		};
	}

	/** The size type textureSize returns (GLSL 8.9.1): the array layer count is the last component. */
	private static String sizeResult(String k) {
		return switch (k) {
			case "1D", "1DShadow", "Buffer" -> "int";
			case "2D", "2DShadow", "Cube", "CubeShadow", "1DArray", "1DArrayShadow", "2DRect", "2DRectShadow", "2DMS"
				-> "ivec2";
			default -> "ivec3";
		};
	}

	private static List<List<String>> queryLodOverloads(String k) {
		return switch (k) {
			case "1D", "1DArray", "1DShadow", "1DArrayShadow" -> ov("float");
			case "2D", "2DArray", "2DShadow", "2DArrayShadow" -> ov("vec2");
			case "3D", "Cube", "CubeArray", "CubeShadow", "CubeArrayShadow" -> ov("vec3");
			default -> List.of();
		};
	}

	/**
	 * GLSL 4.60 8.9.5 compatibility names and the extension names packs still use (texture2DGradARB and EXT from
	 * ARB_shader_texture_lod, texelFetch2D from EXT_gpu_shader4).
	 */
	private void legacyTextures() {
		family("texture1D", "sampler1D", biased("float"), "vec4");
		family("texture1DProj", "sampler1D", cat(biased("vec2"), biased("vec4")), "vec4");
		family("texture1DLod", "sampler1D", ov("float", "float"), "vec4");
		family("texture1DProjLod", "sampler1D", cat(ov("vec2", "float"), ov("vec4", "float")), "vec4");
		family("texture2D", "sampler2D", biased("vec2"), "vec4");
		family("texture2DProj", "sampler2D", cat(biased("vec3"), biased("vec4")), "vec4");
		family("texture2DLod", "sampler2D", ov("vec2", "float"), "vec4");
		family("texture2DProjLod", "sampler2D", cat(ov("vec3", "float"), ov("vec4", "float")), "vec4");
		family("texture3D", "sampler3D", biased("vec3"), "vec4");
		family("texture3DProj", "sampler3D", biased("vec4"), "vec4");
		family("texture3DLod", "sampler3D", ov("vec3", "float"), "vec4");
		family("texture3DProjLod", "sampler3D", ov("vec4", "float"), "vec4");
		family("textureCube", "samplerCube", biased("vec3"), "vec4");
		family("textureCubeLod", "samplerCube", ov("vec3", "float"), "vec4");
		family("shadow1D", "sampler1DShadow", biased("vec3"), "vec4");
		family("shadow2D", "sampler2DShadow", biased("vec3"), "vec4");
		family("shadow1DProj", "sampler1DShadow", biased("vec4"), "vec4");
		family("shadow2DProj", "sampler2DShadow", biased("vec4"), "vec4");
		family("shadow1DLod", "sampler1DShadow", ov("vec3", "float"), "vec4");
		family("shadow2DLod", "sampler2DShadow", ov("vec3", "float"), "vec4");
		family("shadow1DProjLod", "sampler1DShadow", ov("vec4", "float"), "vec4");
		family("shadow2DProjLod", "sampler2DShadow", ov("vec4", "float"), "vec4");
		family("texture2DGradARB", "sampler2D", ov("vec2", "vec2", "vec2"), "vec4");
		family("texture2DGradEXT", "sampler2D", ov("vec2", "vec2", "vec2"), "vec4");
		family("texelFetch2D", "sampler2D", ov("ivec2", "int"), "vec4");
		// texelFetch on a 2D shadow sampler (no GLSL overload; Bliss 2.1.2 calls it, Iris and Vitrail draw the pack, E7):
		// the stored depth without a comparison, (d, 0, 0, 1) (TextureCalls.fetch).
		family("texelFetch2D", "sampler2DShadow", ov("ivec2", "int"), "vec4");
		family("texelFetch", "sampler2DShadow", ov("ivec2", "int"), "vec4");
	}

	// --- overload lists: a list of argument lists (after the sampler), with the bias forms where GLSL has them ---

	private static List<List<String>> ov(String... types) {
		return List.of(List.of(types));
	}

	/** The plain form and the form with a trailing float bias. */
	private static List<List<String>> biased(String... types) {
		List<String> with = new ArrayList<>(List.of(types));
		with.add("float");
		return List.of(List.of(types), List.copyOf(with));
	}

	@SafeVarargs
	private static List<List<String>> cat(List<List<String>>... parts) {
		List<List<String>> all = new ArrayList<>();
		for (List<List<String>> p : parts) all.addAll(p);
		return all;
	}

	// --- 8.12 images: the overloads of GLSL 4.60 8.12, from the specification's tables ---

	/** Image kinds (the name without the element prefix); the element prefix selects the value and atomic types. */
	private static final List<String> IMAGE_KINDS = List.of("1D", "2D", "3D", "Cube", "1DArray", "2DArray",
		"CubeArray", "2DRect", "2DMS", "2DMSArray", "Buffer");

	private void images() {
		for (String e : List.of("", "i", "u")) {
			for (String k : IMAGE_KINDS) {
				String img = e + "image" + k;
				String vec = e.isEmpty() ? "vec4" : e.equals("i") ? "ivec4" : "uvec4";
				boolean ms = k.startsWith("2DMS");
				List<String> at = ms ? List.of(img, imageCoord(k), "int") : List.of(img, imageCoord(k));
				sig("imageSize", List.of(img), imageSizeResult(k));
				if (ms) sig("imageSamples", List.of(img), "int");
				sig("imageLoad", at, vec);
				sig("imageStore", with(at, vec), "void");
				if (e.isEmpty()) {
					sig("imageAtomicExchange", with(at, "float"), "float");
					continue;
				}
				// Integer images take their own element type only: iimage with int, uimage with uint (8.12).
				String elem = e.equals("i") ? "int" : "uint";
				for (String op : List.of("Add", "Min", "Max", "And", "Or", "Xor", "Exchange"))
					sig("imageAtomic" + op, with(at, elem), elem);
				sig("imageAtomicCompSwap", with(at, elem, elem), elem);
			}
		}
	}

	private static List<String> with(List<String> base, String... more) {
		List<String> all = new ArrayList<>(base);
		all.addAll(List.of(more));
		return all;
	}

	/** The coordinate type of IMAGE_PARAMS for each image kind (GLSL 4.60 8.12). */
	private static String imageCoord(String k) {
		return switch (k) {
			case "1D", "Buffer" -> "int";
			case "2D", "2DRect", "2DMS", "1DArray" -> "ivec2";
			default -> "ivec3";
		};
	}

	/** The imageSize result (GLSL 4.60 8.12): the array layer count is the last component. */
	private static String imageSizeResult(String k) {
		return switch (k) {
			case "1D", "Buffer" -> "int";
			case "2D", "2DRect", "2DMS", "Cube", "1DArray" -> "ivec2";
			default -> "ivec3";
		};
	}

	private static String bvecOf(String t) {
		if (t.equals("float") || t.equals("int") || t.equals("uint") || t.equals("bool")) return "bool";
		return "bvec" + t.charAt(t.length() - 1);
	}

	private static String ivecOf(String t) {
		return vecLike(t, "int", "ivec");
	}

	private static String uvecOf(String t) {
		return vecLike(t, "uint", "uvec");
	}

	/** The same-size vector of another element type: scalars map to the scalar, vectors keep their size. */
	private static String vecLike(String t, String scalar, String prefix) {
		if (t.equals("float") || t.equals("int") || t.equals("uint") || t.equals("bool")) return scalar;
		return prefix + t.charAt(t.length() - 1);
	}

	private static String vecOf(String t) {
		return t.equals("int") ? "float" : t.equals("ivec2") ? "vec2" : t.equals("ivec3") ? "vec3"
			: t.equals("ivec4") ? "vec4" : t.equals("uint") ? "float" : t.equals("uvec2") ? "vec2"
			: t.equals("uvec3") ? "vec3" : t.equals("uvec4") ? "vec4" : t;
	}

	// --- signature plumbing ---

	private void sig(String name, List<String> argTypes, String resultType) {
		List<String> args = new ArrayList<>();
		for (int i = 0; i < argTypes.size(); i++) args.add("p" + i);
		BuiltinCall.Resource resource = null;
		if (!argTypes.isEmpty() && (argTypes.getFirst().contains("sampler") || argTypes.getFirst().contains("image")))
			resource = new BuiltinCall.Resource("p0", "smp");
		Mapping mapping = table.map(new BuiltinCall(name, argTypes, resultType, args, resource));
		if (mapping instanceof Mapping.Mapped m)
			signatures.add(new Signature(name, argTypes, resultType, true, null));
		else if (mapping instanceof Mapping.Unsupported u)
			signatures.add(new Signature(name, argTypes, resultType, false, u.reason()));
		else throw new IllegalStateException(mapping.toString());
	}
}
