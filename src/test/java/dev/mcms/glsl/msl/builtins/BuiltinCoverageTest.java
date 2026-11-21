// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.msl.builtins;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage of the built-in table against GLSL 4.60 chapter 8 (W2-C, ADR 0006): every function name of the
 * specification's chapter 8 (plus the compatibility names of 8.9.5 and the extension names packs still use) appears
 * in the catalog, every catalog signature is mapped or carries a reason, every mapped expression's helpers exist,
 * and the semantics the table decides produce the MSL shape the GPU suite then compiles and measures.
 */
final class BuiltinCoverageTest {
	private final BuiltinTable table = BuiltinTable.create();
	private final BuiltinCatalog catalog = BuiltinCatalog.of(table);

	/** The function names of GLSL 4.60 chapter 8 sections 8.1-8.19, transcribed from the specification. */
	private static final List<String> CHAPTER_8 = List.of(
		// 8.1
		"radians", "degrees", "sin", "cos", "asin", "acos", "atan", "sinh", "cosh", "asinh", "acosh", "atanh",
		// 8.2
		"pow", "exp", "log", "exp2", "log2", "sqrt", "inversesqrt",
		// 8.3
		"abs", "sign", "floor", "trunc", "round", "roundEven", "ceil", "fract", "mod", "modf", "min", "max",
		"clamp", "mix", "step", "smoothstep", "isnan", "isinf", "floatBitsToInt", "floatBitsToUint",
		"intBitsToFloat", "uintBitsToFloat", "fma", "frexp", "ldexp",
		// 8.4
		"packUnorm2x16", "packSnorm2x16", "packUnorm4x8", "packSnorm4x8", "unpackUnorm2x16", "unpackSnorm2x16",
		"unpackUnorm4x8", "unpackSnorm4x8", "packHalf2x16", "unpackHalf2x16", "packDouble2x32", "unpackDouble2x32",
		// 8.5
		"length", "distance", "dot", "cross", "normalize", "ftransform", "faceforward", "reflect", "refract",
		// 8.6
		"matrixCompMult", "outerProduct", "transpose", "determinant", "inverse",
		// 8.7
		"lessThan", "lessThanEqual", "greaterThan", "greaterThanEqual", "equal", "notEqual", "any", "all", "not",
		// 8.8
		"uaddCarry", "usubBorrow", "umulExtended", "imulExtended", "bitfieldExtract", "bitfieldInsert",
		"bitfieldReverse", "bitCount", "findLSB", "findMSB",
		// 8.9 (queries, lookups, gradients, gather)
		"texture", "textureProj", "textureLod", "textureOffset", "texelFetch", "texelFetchOffset",
		"textureProjOffset", "textureLodOffset", "textureProjLod", "textureProjLodOffset", "textureGrad",
		"textureGradOffset", "textureProjGrad", "textureProjGradOffset", "textureGather", "textureGatherOffset",
		"textureGatherOffsets", "textureSize", "textureQueryLod", "textureQueryLevels", "textureSamples",
		// 8.9.5 compatibility names
		"texture1D", "texture1DProj", "texture1DLod", "texture1DProjLod", "texture2D", "texture2DProj",
		"texture2DLod", "texture2DProjLod", "texture3D", "texture3DProj", "texture3DLod", "texture3DProjLod",
		"textureCube", "textureCubeLod", "shadow1D", "shadow2D", "shadow1DProj", "shadow2DProj", "shadow1DLod",
		"shadow2DLod", "shadow1DProjLod", "shadow2DProjLod",
		// extension names packs still use (EXT_gpu_shader4, ARB_shader_texture_gather era)
		"texelFetch2D", "texture2DGradARB", "texture2DGradEXT",
		// 8.10
		"atomicCounterIncrement", "atomicCounterDecrement", "atomicCounter", "atomicCounterAdd",
		"atomicCounterSubtract", "atomicCounterMin", "atomicCounterMax", "atomicCounterAnd", "atomicCounterOr",
		"atomicCounterXor", "atomicCounterExchange", "atomicCounterCompSwap",
		// 8.11
		"atomicAdd", "atomicMin", "atomicMax", "atomicAnd", "atomicOr", "atomicXor", "atomicExchange",
		"atomicCompSwap",
		// 8.12
		"imageSize", "imageSamples", "imageLoad", "imageStore", "imageAtomicAdd", "imageAtomicMin",
		"imageAtomicMax", "imageAtomicAnd", "imageAtomicOr", "imageAtomicXor", "imageAtomicExchange",
		"imageAtomicCompSwap",
		// 8.13
		"EmitVertex", "EndPrimitive",
		// 8.14
		"dFdx", "dFdy", "fwidth", "dFdxFine", "dFdyFine", "fwidthFine", "dFdxCoarse", "dFdyCoarse", "fwidthCoarse",
		"interpolateAtCentroid", "interpolateAtSample", "interpolateAtOffset",
		// 8.15
		"noise1", "noise2", "noise3", "noise4",
		// 8.16, 8.17
		"barrier", "memoryBarrier", "memoryBarrierAtomicCounter", "memoryBarrierBuffer", "memoryBarrierShared",
		"memoryBarrierImage", "groupMemoryBarrier",
		// 8.18, 8.19
		"subpassLoad", "subpassLoadMS", "anyInvocation", "allInvocations", "allInvocationsEqual");

	@Test
	void everyChapter8FunctionIsInTheCatalog() {
		Set<String> names = new HashSet<>();
		for (BuiltinCatalog.Signature s : catalog.signatures()) names.add(s.name());
		for (String name : CHAPTER_8)
			assertTrue(names.contains(name), "chapter 8 function missing from the catalog: " + name);
		// and nothing beyond chapter 8 plus the extension aliases
		for (String name : names)
			assertTrue(CHAPTER_8.contains(name), "catalog lists a non-chapter-8 function: " + name);
	}

	@Test
	void everySignatureIsMappedOrCarriesAReason() {
		int mapped = 0;
		for (BuiltinCatalog.Signature s : catalog.signatures()) {
			if (s.supported()) {
				mapped++;
				assertFalse(s.reason() != null, "mapped signature has a reason: " + s);
			} else {
				assertTrue(s.reason() != null && s.reason().length() > 20,
					"unsupported without a usable reason: " + s);
			}
		}
		assertTrue(mapped > 500, "expected a substantial mapped set, got " + mapped);
		assertTrue(catalog.signatures().size() > 700, "expected the full chapter 8 enumeration, got "
			+ catalog.signatures().size());
	}

	@Test
	void mappedExpressionsOnlyNameKnownHelpers() {
		for (BuiltinCatalog.Signature s : catalog.signatures()) {
			Mapping mapping = map(s);
			if (mapping instanceof Mapping.Mapped m)
				for (String helper : m.helpers())
					assertTrue(Prelude.helpers().contains(helper), "unknown helper " + helper + " in " + s);
		}
	}

	@Test
	void unsupportedVerdictsAreStable() {
		for (BuiltinCatalog.Signature s : catalog.signatures()) {
			Mapping mapping = map(s);
			if (s.supported()) assertTrue(mapping instanceof Mapping.Mapped, "catalog says mapped: " + s);
			else assertTrue(mapping instanceof Mapping.Unsupported, "catalog says unsupported: " + s);
		}
	}

	@Test
	void modIsTheFlooredQuotientHelper() {
		Mapping vec = map(call("mod", "vec3", List.of("vec3", "float"), List.of("x", "y")));
		assertEquals("qs::mod(x, y)", ((Mapping.Mapped) vec).msl());
		assertEquals(List.of("mod"), ((Mapping.Mapped) vec).helpers());
		Mapping same = map(call("mod", "vec4", List.of("vec4", "vec4"), List.of("x", "y")));
		assertEquals("qs::mod(x, y)", ((Mapping.Mapped) same).msl());
	}


	@Test
	void atanTwoArgumentsIsAtan2() {
		Mapping two = map(call("atan", "float", List.of("float", "float"), List.of("y", "x")));
		assertEquals("atan2(y, x)", ((Mapping.Mapped) two).msl());
		Mapping one = map(call("atan", "vec2", List.of("vec2"), List.of("v")));
		assertEquals("atan(v)", ((Mapping.Mapped) one).msl());
	}

	@Test
	void inversesqrtIsRsqrtAndRoundEvenIsRint() {
		assertEquals("rsqrt(v)", ((Mapping.Mapped) map(call("inversesqrt", "float", List.of("float"),
			List.of("v")))).msl());
		assertEquals("rint(v)", ((Mapping.Mapped) map(call("roundEven", "vec2", List.of("vec2"),
			List.of("v")))).msl());
	}

	@Test
	void broadcastFormsKeepThePlainMslCall() {
		// MSL broadcasts the scalar (measured); no helper, no splat in the emitted text.
		assertEquals("min(v, s)", ((Mapping.Mapped) map(call("min", "vec3", List.of("vec3", "float"),
			List.of("v", "s")))).msl());
		assertEquals("clamp(v, lo, hi)", ((Mapping.Mapped) map(call("clamp", "vec4", List.of("vec4", "float",
			"float"), List.of("v", "lo", "hi")))).msl());
		assertEquals("step(e, v)", ((Mapping.Mapped) map(call("step", "vec3", List.of("float", "vec3"),
			List.of("e", "v")))).msl());
		assertEquals("smoothstep(a, b, v)", ((Mapping.Mapped) map(call("smoothstep", "vec2", List.of("float",
			"float", "vec2"), List.of("a", "b", "v")))).msl());
	}

	@Test
	void mixWithBooleanVectorSelects() {
		Mapping sel = map(call("mix", "vec4", List.of("vec4", "vec4", "bvec4"), List.of("x", "y", "b")));
		assertEquals("select(x, y, b)", ((Mapping.Mapped) sel).msl());
		Mapping lin = map(call("mix", "vec4", List.of("vec4", "vec4", "float"), List.of("x", "y", "a")));
		assertEquals("mix(x, y, a)", ((Mapping.Mapped) lin).msl());
	}

	@Test
	void relationalFunctionsAreOperators() {
		assertEquals("(a < b)", ((Mapping.Mapped) map(call("lessThan", "bvec3", List.of("vec3", "vec3"),
			List.of("a", "b")))).msl());
		assertEquals("(a != b)", ((Mapping.Mapped) map(call("notEqual", "bvec2", List.of("ivec2", "ivec2"),
			List.of("a", "b")))).msl());
		assertEquals("(!(a))", ((Mapping.Mapped) map(call("not", "bvec3", List.of("bvec3"),
			List.of("a")))).msl());
	}

	@Test
	void integerSignGoesThroughFloatSign() {
		assertEquals("int(sign(float(i)))", ((Mapping.Mapped) map(call("sign", "int", List.of("int"),
			List.of("i")))).msl());
		assertEquals("int3(sign(float3(v)))", ((Mapping.Mapped) map(call("sign", "ivec3", List.of("ivec3"),
			List.of("v")))).msl());
		assertEquals("sign(f)", ((Mapping.Mapped) map(call("sign", "float", List.of("float"),
			List.of("f")))).msl());
	}

	@Test
	void matrixHelpersAreNamed() {
		assertEquals("qs::outerProduct(c, r)", ((Mapping.Mapped) map(call("outerProduct", "mat3x2",
			List.of("vec2", "vec3"), List.of("c", "r")))).msl());
		assertEquals("qs::inverse(m)", ((Mapping.Mapped) map(call("inverse", "mat4", List.of("mat4"),
			List.of("m")))).msl());
		assertEquals("qs::matrixCompMult(a, b)", ((Mapping.Mapped) map(call("matrixCompMult", "mat3",
			List.of("mat3", "mat3"), List.of("a", "b")))).msl());
	}

	@Test
	void packingMapsToMslPackFunctions() {
		assertEquals("pack_float_to_unorm4x8(v)", ((Mapping.Mapped) map(call("packUnorm4x8", "uint",
			List.of("vec4"), List.of("v")))).msl());
		assertEquals("unpack_snorm2x16_to_float(p)", ((Mapping.Mapped) map(call("unpackSnorm2x16", "vec2",
			List.of("uint"), List.of("p")))).msl());
		assertEquals("as_type<uint>(half2(v))", ((Mapping.Mapped) map(call("packHalf2x16", "uint",
			List.of("vec2"), List.of("v")))).msl());
		assertTrue(map(call("packDouble2x32", "double", List.of("uvec2"), List.of("v")))
			instanceof Mapping.Unsupported);
	}

	@Test
	void integerFunctionsKeepGlslEdgeCases() {
		assertEquals("qs::findLSB(v)", ((Mapping.Mapped) map(call("findLSB", "int", List.of("uint"),
			List.of("v")))).msl());
		assertEquals("qs::findMSB(v)", ((Mapping.Mapped) map(call("findMSB", "ivec2", List.of("ivec2"),
			List.of("v")))).msl());
		assertEquals("extract_bits(v, uint(o), uint(b))", ((Mapping.Mapped) map(call("bitfieldExtract", "int",
			List.of("int", "int", "int"), List.of("v", "o", "b")))).msl());
		assertEquals("int4(popcount(v))", ((Mapping.Mapped) map(call("bitCount", "ivec4", List.of("uvec4"),
			List.of("v")))).msl());
		Mapping carry = map(call("uaddCarry", "uint", List.of("uint", "uint", "uint"), List.of("x", "y", "c")));
		assertEquals("qs::uaddCarry(x, y, c)", ((Mapping.Mapped) carry).msl());
	}

	@Test
	void derivativesUseMetalsDerivativesWithNoSignChange() {
		assertEquals("dfdx(p)", ((Mapping.Mapped) map(call("dFdx", "float", List.of("float"),
			List.of("p")))).msl());
		assertEquals("dfdy(p)", ((Mapping.Mapped) map(call("dFdy", "vec3", List.of("vec3"),
			List.of("p")))).msl());
		assertEquals("fwidth(p)", ((Mapping.Mapped) map(call("fwidthFine", "vec2", List.of("vec2"),
			List.of("p")))).msl());
	}

	@Test
	void derivativesAreFragmentOnlyWithTheComputeExtensionReason() {
		BuiltinCall dfdx = call("dFdx", "float", List.of("float"), List.of("p"));
		assertTrue(table.map(dfdx, BuiltinTable.Stage.FRAGMENT) instanceof Mapping.Mapped);
		Mapping compute = table.map(dfdx, BuiltinTable.Stage.COMPUTE);
		assertTrue(compute instanceof Mapping.Unsupported u && u.reason().contains("GL_KHR_compute_shader_derivatives"),
			compute.toString());
		assertTrue(table.map(call("fwidth", "vec3", List.of("vec3"), List.of("p")), BuiltinTable.Stage.VERTEX)
			instanceof Mapping.Unsupported);
	}

	@Test
	void barriersMapToThreadgroupBarrierFlags() {
		// barrier() orders the group's shared memory, and the image and buffer writes before it (a shared array above 32 KB
		// lives in a device buffer, so device memory is ordered too).
		assertEquals("threadgroup_barrier(mem_flags::mem_device | mem_flags::mem_texture | mem_flags::mem_threadgroup)",
			((Mapping.Mapped) table.map(call("barrier", "void", List.of(), List.of()), BuiltinTable.Stage.COMPUTE))
				.msl());
		assertEquals("threadgroup_barrier(mem_flags::mem_device | mem_flags::mem_texture | mem_flags::mem_threadgroup)",
			((Mapping.Mapped) map(call("memoryBarrier", "void", List.of(), List.of()))).msl());
		assertEquals("threadgroup_barrier(mem_flags::mem_texture)",
			((Mapping.Mapped) map(call("memoryBarrierImage", "void", List.of(), List.of()))).msl());
	}

	@Test
	void atomicsAreRelaxedWithAddressOf() {
		assertEquals("atomic_fetch_add_explicit(&(m), d, memory_order_relaxed)",
			((Mapping.Mapped) map(call("atomicAdd", "uint", List.of("uint", "uint"), List.of("m", "d")))).msl());
		assertEquals("qs::atomicCompSwap(&(m), c, d)", ((Mapping.Mapped) map(call("atomicCompSwap", "int",
			List.of("int", "int", "int"), List.of("m", "c", "d")))).msl());
	}

	@Test
	void noiseIsZero() {
		assertEquals("float3(0.0)", ((Mapping.Mapped) map(call("noise3", "vec3", List.of("float"),
			List.of("x")))).msl());
	}

	// --- texture call shapes ---

	@Test
	void textureCallsBuildMslMembers() {
		BuiltinCall.Resource res = new BuiltinCall.Resource("tex", "smp");
		assertEquals("tex.sample(smp, uv)",
			msl(new BuiltinCall("texture", List.of("sampler2D", "vec2"), "vec4", List.of("tex", "uv"), res)));
		assertEquals("tex.sample(smp, uv, level(lod))",
			msl(new BuiltinCall("textureLod", List.of("sampler2D", "vec2", "float"), "vec4",
				List.of("tex", "uv", "lod"), res)));
		assertEquals("tex.sample(smp, uv, bias(b))",
			msl(new BuiltinCall("texture", List.of("sampler2D", "vec2", "float"), "vec4",
				List.of("tex", "uv", "b"), res)));
		assertEquals("tex.sample(smp, uv, level(lod), int2(o))",
			msl(new BuiltinCall("textureLodOffset", List.of("sampler2D", "vec2", "float", "ivec2"), "vec4",
				List.of("tex", "uv", "lod", "o"), res)));
		assertEquals("tex.sample(smp, uv, gradient2d(dx, dy))",
			msl(new BuiltinCall("textureGrad", List.of("sampler2D", "vec2", "vec2", "vec2"), "vec4",
				List.of("tex", "uv", "dx", "dy"), res)));
		// array layer: floor(layer + 0.5) in unsigned, before the options
		assertEquals("tex.sample(smp, p.xy, uint(floor(p.z + 0.5f)), level(lod))",
			msl(new BuiltinCall("textureLod", List.of("sampler2DArray", "vec3", "float"), "vec4",
				List.of("tex", "p", "lod"), res)));
		// shadow: reference after the coordinates
		assertEquals("tex.sample_compare(smp, p.xy, p.z)",
			msl(new BuiltinCall("texture", List.of("sampler2DShadow", "vec3"), "float",
				List.of("tex", "p"), res)));
		assertEquals("tex.sample_compare(smp, p.xy, uint(floor(p.z + 0.5f)), p.w)",
			msl(new BuiltinCall("texture", List.of("sampler2DArrayShadow", "vec4"), "float",
				List.of("tex", "p"), res)));
		// projection divides, including the shadow reference
		assertEquals("tex.sample(smp, (p.xy / p.w))",
			msl(new BuiltinCall("textureProj", List.of("sampler2D", "vec4"), "vec4",
				List.of("tex", "p"), res)));
		assertEquals("tex.sample_compare(smp, (p.xy / p.w), (p.z / p.w))",
			msl(new BuiltinCall("textureProj", List.of("sampler2DShadow", "vec4"), "float",
				List.of("tex", "p"), res)));
		// texel fetch converts to unsigned coordinates, layer before lod
		assertEquals("tex.read(uint2(p), uint(lod))",
			msl(new BuiltinCall("texelFetch", List.of("sampler2D", "ivec2", "int"), "vec4",
				List.of("tex", "p", "lod"), res)));
		assertEquals("tex.read(uint2(p.xy), uint(p.z), uint(lod))",
			msl(new BuiltinCall("texelFetch", List.of("sampler2DArray", "ivec3", "int"), "vec4",
				List.of("tex", "p", "lod"), res)));
		// gather with component, offset in the offset slot
		assertEquals("tex.gather(smp, p, int2(0), component(int(k)))",
			msl(new BuiltinCall("textureGather", List.of("sampler2D", "vec2", "int"), "vec4",
				List.of("tex", "p", "k"), res)));
		assertEquals("tex.gather(smp, p, int2(o))",
			msl(new BuiltinCall("textureGatherOffset", List.of("sampler2D", "vec2", "ivec2"), "vec4",
				List.of("tex", "p", "o"), res)));
		assertEquals("tex.gather_compare(smp, p.xy, r)",
			msl(new BuiltinCall("textureGather", List.of("sampler2DShadow", "vec2", "float"), "vec4",
				List.of("tex", "p", "r"), res)));
		// size and levels helpers
		assertEquals("qs::textureSize(tex, lod)",
			msl(new BuiltinCall("textureSize", List.of("sampler3D", "int"), "ivec3",
				List.of("tex", "lod"), res)));
		assertEquals("int(tex.get_num_mip_levels())",
			msl(new BuiltinCall("textureQueryLevels", List.of("sampler2D"), "int", List.of("tex"), res)));
	}

	@Test
	void legacyNamesKeepTheirShapes() {
		BuiltinCall.Resource res = new BuiltinCall.Resource("tex", "smp");
		assertEquals("tex.sample(smp, uv)",
			msl(new BuiltinCall("texture2D", List.of("sampler2D", "vec2"), "vec4", List.of("tex", "uv"), res)));
		assertEquals("tex.sample(smp, uv, level(lod))",
			msl(new BuiltinCall("texture2DLod", List.of("sampler2D", "vec2", "float"), "vec4",
				List.of("tex", "uv", "lod"), res)));
		assertEquals("tex.sample(smp, uv, gradient2d(dx, dy))",
			msl(new BuiltinCall("texture2DGradARB", List.of("sampler2D", "vec2", "vec2", "vec2"), "vec4",
				List.of("tex", "uv", "dx", "dy"), res)));
		assertEquals("tex.read(uint2(p), uint(lod))",
			msl(new BuiltinCall("texelFetch2D", List.of("sampler2D", "ivec2", "int"), "vec4",
				List.of("tex", "p", "lod"), res)));
		// texelFetch on a shadow sampler (Bliss 2.1.2, E7): the stored depth without a comparison, as GL reads a depth texture
		assertEquals("float4(tex.read(uint2(p), uint(lod)), 0.0, 0.0, 1.0)",
			msl(new BuiltinCall("texelFetch2D", List.of("sampler2DShadow", "ivec2", "int"), "vec4",
				List.of("tex", "p", "lod"), res)));
		// shadow2D returns vec4 with the comparison splatted (compatibility GLSL)
		assertEquals("float4(tex.sample_compare(smp, c.xy, c.z))",
			msl(new BuiltinCall("shadow2D", List.of("sampler2DShadow", "vec3"), "vec4",
				List.of("tex", "c"), res)));
		assertEquals("float4(tex.sample_compare(smp, (c.xy / c.w), (c.z / c.w)))",
			msl(new BuiltinCall("shadow2DProj", List.of("sampler2DShadow", "vec4"), "vec4",
				List.of("tex", "c"), res)));
		assertEquals("tex.sample(smp, dir)",
			msl(new BuiltinCall("textureCube", List.of("samplerCube", "vec3"), "vec4",
				List.of("tex", "dir"), res)));
	}

	@Test
	void oneDimensionalLookupsUseTheHeightOneTexture() {
		BuiltinCall.Resource res = new BuiltinCall.Resource("tex", "smp");
		assertEquals("tex.sample(smp, float2(c, 0.5f), level(lod))",
			msl(new BuiltinCall("textureLod", List.of("sampler1D", "float", "float"), "vec4",
				List.of("tex", "c", "lod"), res)));
		assertEquals("tex.sample(smp, float2(p.x, 0.5f), uint(floor(p.y + 0.5f)), level(lod))",
			msl(new BuiltinCall("textureLod", List.of("sampler1DArray", "vec2", "float"), "vec4",
				List.of("tex", "p", "lod"), res)));
		assertEquals("tex.sample(smp, float2((p.x / p.y), 0.5f))",
			msl(new BuiltinCall("textureProj", List.of("sampler1D", "vec2"), "vec4", List.of("tex", "p"), res)));
		assertEquals("tex.sample(smp, float2((p.x / p.w), 0.5f))",
			msl(new BuiltinCall("textureProj", List.of("sampler1D", "vec4"), "vec4", List.of("tex", "p"), res)));
		assertEquals("tex.sample(smp, float2(c, 0.5f), gradient2d(float2(dx, 0.0f), float2(dy, 0.0f)))",
			msl(new BuiltinCall("textureGrad", List.of("sampler1D", "float", "float", "float"), "vec4",
				List.of("tex", "c", "dx", "dy"), res)));
		assertEquals("tex.sample(smp, float2(c, 0.5f), int2(o, 0))",
			msl(new BuiltinCall("textureOffset", List.of("sampler1D", "float", "int"), "vec4",
				List.of("tex", "c", "o"), res)));
		assertEquals("tex.sample_compare(smp, float2(p.x, 0.5f), p.z)",
			msl(new BuiltinCall("texture", List.of("sampler1DShadow", "vec3"), "float", List.of("tex", "p"), res)));
		assertEquals("tex.sample_compare(smp, float2((p.x / p.w), 0.5f), (p.z / p.w))",
			msl(new BuiltinCall("textureProj", List.of("sampler1DShadow", "vec4"), "float", List.of("tex", "p"), res)));
		assertEquals("float4(tex.sample_compare(smp, float2(c.x, 0.5f), c.z))",
			msl(new BuiltinCall("shadow1D", List.of("sampler1DShadow", "vec3"), "vec4", List.of("tex", "c"), res)));
		assertEquals("tex.sample_compare(smp, float2(p.x, 0.5f), uint(floor(p.y + 0.5f)), p.z)",
			msl(new BuiltinCall("texture", List.of("sampler1DArrayShadow", "vec3"), "float", List.of("tex", "p"), res)));
		assertEquals("tex.read(uint2(uint(p), 0u), uint(lod))",
			msl(new BuiltinCall("texelFetch", List.of("sampler1D", "int", "int"), "vec4", List.of("tex", "p", "lod"),
				res)));
		assertEquals("tex.read(uint2(uint(p.x), 0u), uint(p.y), uint(lod))",
			msl(new BuiltinCall("texelFetch", List.of("sampler1DArray", "ivec2", "int"), "vec4",
				List.of("tex", "p", "lod"), res)));
		// texelFetchOffset(P, lod, offset): the offset moves x only, and the 2D array layer is never offset
		assertEquals("tex.read(uint2(uint((p + o)), 0u), uint(lod))",
			msl(new BuiltinCall("texelFetchOffset", List.of("sampler1D", "int", "int", "int"), "vec4",
				List.of("tex", "p", "lod", "o"), res)));
		assertEquals("tex.read(uint2((p.xy + o)), uint(p.z), uint(lod))",
			msl(new BuiltinCall("texelFetchOffset", List.of("sampler2DArray", "ivec3", "int", "ivec2"), "vec4",
				List.of("tex", "p", "lod", "o"), res)));
		assertEquals("tex.read(uint2(uint((p.x + o)), 0u), uint(p.y), uint(lod))",
			msl(new BuiltinCall("texelFetchOffset", List.of("sampler1DArray", "ivec2", "int", "int"), "vec4",
				List.of("tex", "p", "lod", "o"), res)));
		assertEquals("int(tex.get_width(uint(lod)))",
			msl(new BuiltinCall("textureSize", List.of("sampler1D", "int"), "int", List.of("tex", "lod"), res)));
		assertEquals("int2(int(tex.get_width(uint(lod))), int(tex.get_array_size()))",
			msl(new BuiltinCall("textureSize", List.of("sampler1DArray", "int"), "ivec2", List.of("tex", "lod"), res)));
		assertTrue(table.map(new BuiltinCall("textureGather", List.of("sampler1D", "float"), "vec4",
			List.of("tex", "c"), res)) instanceof Mapping.Unsupported);
	}

	@Test
	void oneDimensionalImagesUseTheHeightOneTexture() {
		BuiltinCall.Resource res = new BuiltinCall.Resource("img", null);
		assertEquals("img.read(uint2(uint(p), 0u))",
			msl(new BuiltinCall("imageLoad", List.of("image1D(rgba16f)", "int"), "vec4", List.of("img", "p"), res)));
		assertEquals("img.write(d, uint2(uint(p.x), 0u), uint(p.y))",
			msl(new BuiltinCall("imageStore", List.of("image1DArray(rgba8)", "ivec2", "vec4"), "void",
				List.of("img", "p", "d"), res)));
		assertEquals("int(img.get_width())",
			msl(new BuiltinCall("imageSize", List.of("image1D(rgba16f)"), "int", List.of("img"), res)));
		assertEquals("int2(int(img.get_width()), int(img.get_array_size()))",
			msl(new BuiltinCall("imageSize", List.of("image1DArray(rgba16f)"), "ivec2", List.of("img"), res)));
		assertEquals("img.atomic_fetch_add(uint2(uint(p), 0u), uint4(d)).x",
			msl(new BuiltinCall("imageAtomicAdd", List.of("uimage1D(r32ui)", "int", "uint"), "uint",
				List.of("img", "p", "d"), res)));
	}

	@Test
	void textureGatherOffsetsIsFourSingleOffsetGathers() {
		BuiltinCall.Resource res = new BuiltinCall.Resource("tex", "smp");
		assertEquals("float4(tex.gather(smp, p, (o)[0]).w, tex.gather(smp, p, (o)[1]).w, "
			+ "tex.gather(smp, p, (o)[2]).w, tex.gather(smp, p, (o)[3]).w)",
			msl(new BuiltinCall("textureGatherOffsets", List.of("sampler2D", "vec2", "ivec2[4]"), "vec4",
				List.of("tex", "p", "o"), res)));
		assertEquals("int4(tex.gather(smp, p.xy, uint(floor(p.z + 0.5f)), (o)[0], component(int(k))).w, "
			+ "tex.gather(smp, p.xy, uint(floor(p.z + 0.5f)), (o)[1], component(int(k))).w, "
			+ "tex.gather(smp, p.xy, uint(floor(p.z + 0.5f)), (o)[2], component(int(k))).w, "
			+ "tex.gather(smp, p.xy, uint(floor(p.z + 0.5f)), (o)[3], component(int(k))).w)",
			msl(new BuiltinCall("textureGatherOffsets", List.of("sampler2DArray", "vec3", "ivec2[4]", "int"), "ivec4",
				List.of("tex", "p", "o", "k"), res)));
		assertEquals("float4(tex.gather_compare(smp, p, r, (o)[0]).w, tex.gather_compare(smp, p, r, (o)[1]).w, "
			+ "tex.gather_compare(smp, p, r, (o)[2]).w, tex.gather_compare(smp, p, r, (o)[3]).w)",
			msl(new BuiltinCall("textureGatherOffsets", List.of("sampler2DShadow", "vec2", "float", "ivec2[4]"),
				"vec4", List.of("tex", "p", "r", "o"), res)));
		assertTrue(table.map(new BuiltinCall("textureGatherOffsets", List.of("samplerCube", "vec3", "ivec2[4]"),
			"vec4", List.of("tex", "p", "o"), res)) instanceof Mapping.Unsupported);
	}

	@Test
	void texelBuffersAreIndexedAndSizedByWidth() {
		BuiltinCall.Resource res = new BuiltinCall.Resource("buf", null);
		assertEquals("buf.read(uint(p))",
			msl(new BuiltinCall("texelFetch", List.of("samplerBuffer", "int"), "vec4", List.of("buf", "p"), res)));
		assertEquals("int(buf.get_width())",
			msl(new BuiltinCall("textureSize", List.of("usamplerBuffer"), "int", List.of("buf"), res)));
		assertEquals("buf.read(uint(p))",
			msl(new BuiltinCall("imageLoad", List.of("imageBuffer(rgba32f)", "int"), "vec4", List.of("buf", "p"), res)));
		assertEquals("buf.write(d, uint(p))",
			msl(new BuiltinCall("imageStore", List.of("imageBuffer(rgba32f)", "int", "vec4"), "void",
				List.of("buf", "p", "d"), res)));
		assertEquals("int(buf.get_width())",
			msl(new BuiltinCall("imageSize", List.of("uimageBuffer(r32ui)"), "int", List.of("buf"), res)));
		assertTrue(table.map(new BuiltinCall("texture", List.of("samplerBuffer", "float"), "vec4",
			List.of("buf", "p"), res)) instanceof Mapping.Unsupported);
		assertEquals("buf.atomic_fetch_add(uint(p), uint4(d)).x",
			msl(new BuiltinCall("imageAtomicAdd", List.of("uimageBuffer(r32ui)", "int", "uint"), "uint",
				List.of("buf", "p", "d"), res)));
	}

	@Test
	void multisampleImagesAreRefused() {
		BuiltinCall.Resource res = new BuiltinCall.Resource("img", null);
		assertTrue(table.map(new BuiltinCall("imageLoad", List.of("image2DMS(rgba8)", "ivec2", "int"), "vec4",
			List.of("img", "p", "s"), res)) instanceof Mapping.Unsupported);
		assertTrue(table.map(new BuiltinCall("imageStore", List.of("image2DMS(rgba8)", "ivec2", "int", "vec4"), "void",
			List.of("img", "p", "s", "d"), res)) instanceof Mapping.Unsupported);
	}

	@Test
	void cubeImagesUseTheFaceAndLayerOfTheThirdCoordinate() {
		BuiltinCall.Resource res = new BuiltinCall.Resource("img", null);
		assertEquals("img.read(uint2(p.xy), uint(p.z))",
			msl(new BuiltinCall("imageLoad", List.of("imageCube(rgba8)", "ivec3"), "vec4",
				List.of("img", "p"), res)));
		assertEquals("img.write(d, uint2(p.xy), uint(p.z))",
			msl(new BuiltinCall("imageStore", List.of("imageCube(rgba8)", "ivec3", "vec4"), "void",
				List.of("img", "p", "d"), res)));
		assertEquals("img.read(uint2(p.xy), uint(p.z) % 6u, uint(p.z) / 6u)",
			msl(new BuiltinCall("imageLoad", List.of("uimageCubeArray(r32ui)", "ivec3"), "uvec4",
				List.of("img", "p"), res)));
		assertEquals("img.write(d, uint2(p.xy), uint(p.z) % 6u, uint(p.z) / 6u)",
			msl(new BuiltinCall("imageStore", List.of("imageCubeArray(rgba8)", "ivec3", "vec4"), "void",
				List.of("img", "p", "d"), res)));
		assertEquals("qs::imageSize(img)",
			msl(new BuiltinCall("imageSize", List.of("imageCubeArray(rgba8)"), "ivec3", List.of("img"), res)));
		assertTrue(table.map(new BuiltinCall("imageAtomicAdd", List.of("uimageCube(r32ui)", "ivec3", "uint"), "uint",
			List.of("img", "p", "d"), res)) instanceof Mapping.Unsupported);
	}

	@Test
	void imageCallsConvertCoordinates() {
		BuiltinCall.Resource res = new BuiltinCall.Resource("img", null);
		assertEquals("img.read(uint2(p))",
			msl(new BuiltinCall("imageLoad", List.of("image2D(rgba16f)", "ivec2"), "vec4",
				List.of("img", "p"), res)));
		assertEquals("img.write(d, uint2(p))",
			msl(new BuiltinCall("imageStore", List.of("uimage2D(r32ui)", "ivec2", "uvec4"), "void",
				List.of("img", "p", "d"), res)));
		assertEquals("img.read(uint3(p))",
			msl(new BuiltinCall("imageLoad", List.of("image3D(r32ui)", "ivec3"), "uvec4",
				List.of("img", "p"), res)));
		assertEquals("qs::imageSize(img)",
			msl(new BuiltinCall("imageSize", List.of("image2D(rgba16f)"), "ivec2", List.of("img"), res)));
		assertEquals("img.atomic_fetch_add(uint2(p), uint4(d)).x",
			msl(new BuiltinCall("imageAtomicAdd", List.of("uimage2D(r32ui)", "ivec2", "uint"), "uint",
				List.of("img", "p", "d"), res)));
		// r32f atomics do not exist on Metal, whatever the GLSL type says
		assertTrue(table.map(new BuiltinCall("imageAtomicExchange", List.of("image2D(r32f)", "ivec2", "float"),
			"float", List.of("img", "p", "d"), res)) instanceof Mapping.Unsupported);
	}

	@Test
	void doublesAreRefusedWithTheReason() {
		Mapping m = table.map(BuiltinCall.of("sin", "double", "double", "x"));
		assertTrue(m instanceof Mapping.Unsupported u && u.reason().contains("double"), m.toString());
	}

	// --- overload tables of GLSL 4.60 8.9 and 8.12 (the specification's tables, not the generator's guesses) ---

	@Test
	void catalogListsNoOverloadsTheSpecificationDoesNotHave() {
		assertFalse(has("texture", "float", "sampler2DShadow", "vec2"));
		assertFalse(has("texture", "float", "samplerCubeShadow", "vec3"));
		assertFalse(has("texture", "float", "sampler1DShadow", "float"));
		assertFalse(has("texture", "float", "sampler2DArrayShadow", "vec3"));
		assertFalse(has("textureProj", "vec4", "sampler2DArray", "vec3"));
		assertFalse(has("textureProj", "vec4", "sampler2DArray", "vec4"));
		assertFalse(has("textureProj", "float", "sampler1DShadow", "vec3"));
		assertFalse(has("textureOffset", "vec4", "sampler1D", "float", "ivec2"));
		assertFalse(has("texelFetchOffset", "vec4", "sampler1D", "int", "int", "ivec2"));
		assertFalse(has("texelFetch", "float", "sampler2DShadow", "ivec2", "int"));
		assertFalse(has("imageSize", "ivec3", "image2D"));
		assertFalse(has("imageAtomicCompSwap", "uint", "iimage2D", "ivec2", "uint", "uint"));
		assertFalse(has("imageAtomicCompSwap", "int", "uimage2D", "ivec2", "int", "int"));
		assertFalse(has("imageAtomicAdd", "uint", "iimage2D", "ivec2", "uint"));
	}

	@Test
	void catalogListsTheSpecificationOverloads() {
		assertTrue(has("texture", "float", "samplerCubeShadow", "vec4"));
		assertTrue(has("texture", "float", "sampler2DArrayShadow", "vec4"));
		assertTrue(has("texture", "float", "samplerCubeArrayShadow", "vec4", "float"));
		assertTrue(has("textureOffset", "vec4", "sampler1D", "float", "int"));
		assertTrue(has("textureOffset", "vec4", "sampler1DArray", "vec2", "int"));
		assertTrue(has("textureProj", "vec4", "sampler1D", "vec2"));
		assertTrue(has("textureProj", "float", "sampler2DShadow", "vec4"));
		assertTrue(has("textureQueryLod", "vec2", "sampler2D", "vec2"));
		assertTrue(has("textureGather", "vec4", "sampler2DShadow", "vec2", "float"));
		assertTrue(has("imageSize", "ivec2", "image2D"));
		assertTrue(has("imageSize", "ivec3", "imageCubeArray"));
		assertTrue(has("imageAtomicCompSwap", "int", "iimage2D", "ivec2", "int", "int"));
		assertTrue(has("imageAtomicCompSwap", "uint", "uimage2D", "ivec2", "uint", "uint"));
	}

	@Test
	void textureQueryLodIsFragmentOnlyAndUsesTheMetalQueries() {
		BuiltinCall q = call("textureQueryLod", "vec2", List.of("sampler2D", "vec2"), List.of("t", "p"));
		assertEquals("float2(t.calculate_clamped_lod(smp, p), t.calculate_unclamped_lod(smp, p))", msl(q));
		assertTrue(table.map(q, BuiltinTable.Stage.COMPUTE) instanceof Mapping.Unsupported u
			&& u.reason().contains("fragment"));
		assertTrue(table.map(q, BuiltinTable.Stage.VERTEX) instanceof Mapping.Unsupported);
		assertEquals("float2(t.calculate_clamped_lod(smp, float2(p, 0.5f)), "
				+ "t.calculate_unclamped_lod(smp, float2(p, 0.5f)))",
			msl(call("textureQueryLod", "vec2", List.of("sampler1D", "float"), List.of("t", "p"))));
	}

	@Test
	void rectangleGradientOffsetsAreMapped() {
		assertEquals("t.sample(smp, p, gradient2d(dx, dy), int2(o))", msl(call("textureGradOffset", "vec4",
			List.of("sampler2DRect", "vec2", "vec2", "vec2", "ivec2"), List.of("t", "p", "dx", "dy", "o"))));
		assertTrue(table.map(call("textureLod", "vec4", List.of("sampler2DRect", "vec2", "float"),
			List.of("t", "p", "l"))) instanceof Mapping.Unsupported);
	}

	@Test
	void multisampleImagesAreRefusedForEveryCall() {
		assertTrue(table.map(call("imageSize", "ivec2", List.of("image2DMS"), List.of("i"))) instanceof Mapping.Unsupported);
		assertTrue(table.map(call("imageSamples", "int", List.of("image2DMS"), List.of("i"))) instanceof Mapping.Unsupported);
	}

	private boolean has(String name, String resultType, String... argTypes) {
		return catalog.signatures().stream().anyMatch(s -> s.name().equals(name)
			&& s.resultType().equals(resultType) && s.argTypes().equals(List.of(argTypes)));
	}

	// --- plumbing ---

	private Mapping map(BuiltinCatalog.Signature s) {
		return table.map(call(s.name(), s.resultType(), s.argTypes(), dummyArgs(s.argTypes())));
	}

	private static List<String> dummyArgs(List<String> argTypes) {
		return argTypes.stream().map(t -> "a").toList();
	}

	private Mapping map(BuiltinCall call) {
		return table.map(call);
	}

	private BuiltinCall call(String name, String resultType, List<String> argTypes, List<String> args) {
		BuiltinCall.Resource resource = null;
		if (!argTypes.isEmpty() && (argTypes.getFirst().contains("sampler") || argTypes.getFirst().contains("image")))
			resource = new BuiltinCall.Resource(args.getFirst(), "smp");
		return new BuiltinCall(name, argTypes, resultType, args, resource);
	}

	private String msl(BuiltinCall call) {
		Mapping mapping = table.map(call);
		if (mapping instanceof Mapping.Mapped m) return m.msl();
		if (mapping instanceof Mapping.Unsupported u) return "UNSUPPORTED: " + u.reason();
		throw new IllegalStateException();
	}
}
