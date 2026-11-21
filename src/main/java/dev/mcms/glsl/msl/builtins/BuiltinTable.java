// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.msl.builtins;

import dev.mcms.glsl.msl.builtins.TextureCalls.Form;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The table from a resolved GLSL built-in call to an MSL expression (W2-C, ADR 0006). Every function of GLSL 4.60
 * chapter 8 that a fragment, vertex or compute shader can call is mapped or returned unsupported with a reason;
 * {@link BuiltinCatalog} enumerates the signatures and reads this table for the status. The table is data plus small
 * functions: most numeric functions keep their GLSL name in MSL with the same arguments (the {@link #SAME_NAME} set),
 * the rest are renamed one-liners or need a {@link Prelude} helper.
 *
 * <p>Semantics decided here (each cites its source): mod is floored, not C fmod (GLSL 4.60 8.3); atan(y, x) is
 * atan2; round ties are the implementation's choice (GLSL 4.60 8.3), roundEven is rint; mix with a Boolean vector is
 * select; integer sign goes through float sign (MSL has no integer sign); bit field functions map to MSL's
 * extract_bits/insert_bits/reverse_bits/popcount (sign extension checked on the GPU); findLSB/findMSB carry GLSL's
 * -1 cases; dFdy is MSL's dfdy with no sign change: the emitter's epilogue puts OpenGL's window y at the row index, so
 * the derivative runs toward increasing window y as GLSL defines it (GPU test FullscreenContractGpuTest);
 * atomics are relaxed because MSL has no sequential consistency, and the engine's barriers order the memory; noise
 * functions return zero, which GLSL allows.
 *
 * <p>Runtime contract for the engine's texture creation (the emitter declares the textures from
 * {@link GlslTypes#mslTexture}, and the engine creates them to match): the engine creates every 1D texture as a 2D
 * texture of height 1, and every 1D array texture as a 2D array texture of height 1. sampler1D and image1D map to
 * texture2d, sampler1DArray and image1DArray to texture2d_array, and the 1D shadow forms to depth2d and
 * depth2d_array. A 1D coordinate x becomes (x, 0.5) for a normalized lookup and (x, 0) for a texel lookup.
 *
 * <p>Texel buffers (samplerBuffer, imageBuffer) are the one kind the engine must bind as a texture buffer, not as a
 * sampled texture: each is a MSL texture_buffer of the texel format (texture_buffer&lt;T&gt; for samplers,
 * texture_buffer&lt;T, access::read_write&gt; for images; {@link GlslTypes#mslTexture} and {@link GlslTypes#mslImage}).
 * A texel buffer has no sampler and no level: it is read and written by index, and its size is its width. The runtime
 * creates each one as a texture buffer over a buffer that holds its texels.
 *
 * <p>Cube images (imageCube, imageCubeArray) are texturecube and texturecube_array objects with read/write access. The
 * third GLSL coordinate selects the face: imageCube face = z, and imageCubeArray face = z % 6 and layer = z / 6 (GLSL
 * 4.60 8.12). The runtime creates a cube array with the layer count of cubes (not faces), which is what the array size
 * reports.
 *
 * <p>Rectangle textures (sampler2DRect, sampler2DRectShadow, from raw TEXTURE_RECTANGLE custom textures) are 2D textures
 * looked up with texel coordinates: the mappings pass the GLSL coordinate unchanged, so the emitter must give every
 * rectangle sampler a constexpr sampler with coord::pixel, clamp-to-edge addressing and no mip filter (Metal's
 * pixel-coordinate samplers allow no mipmaps and only clamp-to-edge or clamp-to-zero addressing). A normalized sampler
 * would read the wrong texels without any error.
 */
public final class BuiltinTable {

	@FunctionalInterface
	private interface Mapper {
		Mapping map(BuiltinCall call);
	}

	/**
	 * Functions whose MSL form is the same name with the same arguments (vectors included; MSL broadcasts the scalar
	 * beside a vector in min/max/clamp/mix/step/smoothstep, measured).
	 */
	private static final Set<String> SAME_NAME = Set.of(
		"tan", "sin", "cos", "asin", "acos", "sinh", "cosh", "asinh", "acosh", "atanh",
		"pow", "exp", "log", "exp2", "log2", "sqrt", "floor", "trunc", "round", "ceil", "fract",
		"length", "distance", "dot", "cross", "normalize", "reflect", "refract", "faceforward",
		"min", "max", "clamp", "step", "smoothstep", "fma", "ldexp", "frexp", "modf",
		"transpose", "determinant", "any", "all", "isnan", "isinf");

	private final Map<String, Mapper> mappers = new HashMap<>();
	/** The texture lookup form of each texture function name (8.9). */
	private final Map<String, Form> forms = new HashMap<>();

	public static BuiltinTable create() {
		return new BuiltinTable();
	}

	private BuiltinTable() {
		registerFloatUnaries();
		registerCommon();
		registerGeometric();
		registerMatrices();
		registerRelational();
		registerPacking();
		registerInteger();
		registerNoise();
		registerDerivatives();
		registerBarriers();
		registerAtomicMemory();
		registerUnsupportedFamilies();
		registerTextures();
		registerImages();
	}

	/** The stage a call is emitted in; only the derivative functions depend on it (GLSL 4.60 8.14.1). */
	public enum Stage { VERTEX, FRAGMENT, COMPUTE }

	/** The reason derivatives are refused in compute shaders: GLSL forbids them there unless an extension allows it. */
	public static final String COMPUTE_DERIVATIVES = "GLSL forbids derivatives in compute shaders unless the shader enables "
		+ "GL_KHR_compute_shader_derivatives (or GL_NV_compute_shader_derivatives), and the engine enables neither";

	/** The reason derivatives are refused in vertex shaders: GLSL defines them for fragment shaders only (8.14.1). */
	public static final String VERTEX_DERIVATIVES = "GLSL defines derivatives (dFdx, dFdy, fwidth) for fragment shaders only";

	/** Maps one call in a fragment shader; {@link #map(BuiltinCall, Stage)} gives the other stages' verdicts. */
	public Mapping map(BuiltinCall call) {
		return map(call, Stage.FRAGMENT);
	}

	/** Maps one call; a name this table does not know is not a GLSL 4.60 built-in (the translator resolves overloads). */
	public Mapping map(BuiltinCall call, Stage stage) {
		if (isDerivative(call.name()) && stage != Stage.FRAGMENT)
			return new Mapping.Unsupported(stage == Stage.COMPUTE ? COMPUTE_DERIVATIVES : VERTEX_DERIVATIVES);
		if (call.name().equals("textureQueryLod") && stage != Stage.FRAGMENT)
			return new Mapping.Unsupported("textureQueryLod is defined for fragment shaders only (GLSL 4.60 8.9.1)");
		if (call.name().equals("barrier") && stage != Stage.COMPUTE)
			return new Mapping.Unsupported("GLSL barrier() is defined for compute and tessellation control shaders only; "
				+ "the engine has no tessellation stage (GLSL 4.60 8.16)");
		if (stage != Stage.COMPUTE && (call.name().startsWith("memoryBarrier") || call.name().equals("groupMemoryBarrier")))
			// MSL forbids threadgroup_barrier outside compute kernels; GLSL memoryBarrier* in vertex/fragment is an ordering hint.
			return Mapping.Mapped.of("((void)0)");
		if (isDouble(call))
			return new Mapping.Unsupported("no double precision on Apple GPUs (GLSL 4.60 double overload)");
		Mapper mapper = mappers.get(call.name());
		if (mapper == null) {
			if (isSameName(call.name()))
				return Mapping.Mapped.of(call.name() + "(" + String.join(", ", call.args()) + ")");
			return new Mapping.Unsupported("not a built-in of GLSL 4.60 chapter 8: " + call.name());
		}
		return mapper.map(call);
	}

	/**
	 * Whether a texture call can read a mip level other than 0 (E9: a target's mip chain is refilled only before the
	 * programs that read it; {@link TextureCalls#readsLevels}). False for every call that is not a texture lookup.
	 */
	public boolean readsLevels(BuiltinCall call) {
		Form form = forms.get(call.name());
		return form != null && TextureCalls.readsLevels(call, form);
	}

	/** Whether a texture call is an implicit-LOD lookup ({@link TextureCalls#implicitLookup}); false for every other call. */
	public boolean implicitLookup(BuiltinCall call) {
		Form form = forms.get(call.name());
		return form != null && TextureCalls.implicitLookup(form);
	}

	private void register(String name, Mapper mapper) {
		if (mappers.putIfAbsent(name, mapper) != null) throw new IllegalStateException("twice: " + name);
	}

	private static boolean isDouble(BuiltinCall call) {
		if (call.resultType().startsWith("d")) return true;
		for (String t : call.argTypes()) if (t.startsWith("d")) return true;
		return false;
	}

	/** The derivative functions of GLSL 4.60 8.14.1: dFdx, dFdy, fwidth and their Fine and Coarse forms. */
	private static boolean isDerivative(String name) {
		return name.startsWith("dFd") || name.startsWith("fwidth");
	}

	// --- 8.1 angle and trigonometry, 8.2 exponential ---

	private void registerFloatUnaries() {
		register("radians", c -> Mapping.Mapped.of(paren(c.args().get(0) + " * 0.017453292519943295f")));
		register("degrees", c -> Mapping.Mapped.of(paren(c.args().get(0) + " * 57.2957795130823208f")));
		register("atan", c -> c.args().size() == 2
			? Mapping.Mapped.of("atan2(" + c.args().get(0) + ", " + c.args().get(1) + ")")
			: Mapping.Mapped.of("atan(" + c.args().get(0) + ")"));
		register("inversesqrt", c -> Mapping.Mapped.of("rsqrt(" + c.args().get(0) + ")"));
	}

	// --- 8.3 common ---

	private void registerCommon() {
		register("abs", c -> Mapping.Mapped.of((c.type(0).isVectorOf(GlslTypes.Scalar.INT)
			|| c.type(0).kind() == GlslTypes.Kind.INT ? "abs(" : "fabs(") + c.args().get(0) + ")"));
		register("sign", c -> {
			if (c.type(0).kind() == GlslTypes.Kind.FLOAT || c.type(0).isVectorOf(GlslTypes.Scalar.FLOAT))
				return Mapping.Mapped.of("sign(" + c.args().get(0) + ")");
			// MSL sign is float-only; ints convert exactly (zero and sign survive).
			String wide = c.resultType().startsWith("ivec")
				? c.resultType().replace("ivec", "float") : "float";
			return Mapping.Mapped.of(c.resultType().replace("ivec", "int")
				+ "(sign(" + wide + "(" + c.args().get(0) + ")))");
		});
		register("roundEven", c -> Mapping.Mapped.of("rint(" + c.args().get(0) + ")"));
		register("mod", c -> new Mapping.Mapped("qs::mod(" + c.args().get(0) + ", " + c.args().get(1) + ")",
			List.of("mod")));
		register("mix", c -> {
			if (c.argTypes().get(2).startsWith("bvec") || c.argTypes().get(2).equals("bool"))
				return Mapping.Mapped.of("select(" + c.args().get(0) + ", " + c.args().get(1) + ", "
					+ c.args().get(2) + ")");
			return Mapping.Mapped.of("mix(" + String.join(", ", c.args()) + ")");
		});
		register("floatBitsToInt", c -> Mapping.Mapped.of("as_type<" + c.resultType().replace("ivec", "int")
			+ ">(" + c.args().get(0) + ")"));
		register("floatBitsToUint", c -> Mapping.Mapped.of("as_type<" + c.resultType().replace("uvec", "uint")
			+ ">(" + c.args().get(0) + ")"));
		register("intBitsToFloat", c -> Mapping.Mapped.of("as_type<" + mslFloatOf(c.resultType()) + ">("
			+ c.args().get(0) + ")"));
		register("uintBitsToFloat", c -> Mapping.Mapped.of("as_type<" + mslFloatOf(c.resultType()) + ">("
			+ c.args().get(0) + ")"));
	}

	private static String mslFloatOf(String glsl) {
		return glsl.startsWith("vec") ? "float" + glsl.charAt(3) : "float";
	}

	// --- 8.5 geometric ---

	private void registerGeometric() {
		register("ftransform", c -> new Mapping.Unsupported(
			"the engine's vertex prologue supplies ftransform() and the compatibility state (ADR 0006 Decision 2)"));
		// GLSL 4.60 8.5 with genFType = float: the scalar forms (MSL has no scalar overloads of these names).
		register("length", c -> c.argTypes().get(0).equals("float") ? Mapping.Mapped.of("fabs(" + c.args().get(0) + ")")
			: Mapping.Mapped.of("length(" + c.args().get(0) + ")"));
		register("distance", c -> c.argTypes().get(0).equals("float")
			? Mapping.Mapped.of("fabs(" + c.args().get(0) + " - " + c.args().get(1) + ")")
			: Mapping.Mapped.of("distance(" + c.args().get(0) + ", " + c.args().get(1) + ")"));
		register("dot", c -> c.argTypes().get(0).equals("float")
			? Mapping.Mapped.of("(" + c.args().get(0) + " * " + c.args().get(1) + ")")
			: Mapping.Mapped.of("dot(" + c.args().get(0) + ", " + c.args().get(1) + ")"));
		// normalize: x / length(x) with OpenGL's NaN for the zero vector (Prelude's helper; MSL's gives 0 under fast math).
		register("normalize", helper("normalize"));
		register("faceforward", c -> c.argTypes().get(0).equals("float")
			? Mapping.Mapped.of("((" + c.args().get(2) + " * " + c.args().get(1) + ") < 0 ? "
				+ c.args().get(0) + " : -" + c.args().get(0) + ")")
			: Mapping.Mapped.of("faceforward(" + c.args().get(0) + ", " + c.args().get(1) + ", " + c.args().get(2) + ")"));
		// reflect(I, N) = I - 2 dot(N, I) N and refract(I, N, eta) with k = 1 - eta^2 (1 - dot^2), k < 0 gives 0.
		register("reflect", c -> c.argTypes().get(0).equals("float")
			? Mapping.Mapped.of("(" + c.args().get(0) + " - 2.0f * (" + c.args().get(1) + " * " + c.args().get(0) + ") * "
				+ c.args().get(1) + ")")
			: Mapping.Mapped.of("reflect(" + c.args().get(0) + ", " + c.args().get(1) + ")"));
		register("refract", c -> {
			if (!c.argTypes().get(0).equals("float")) return Mapping.Mapped.of("refract(" + c.args().get(0) + ", "
				+ c.args().get(1) + ", " + c.args().get(2) + ")");
			String i = c.args().get(0), n = c.args().get(1), eta = c.args().get(2);
			String d = "(" + n + " * " + i + ")";
			String k = "(1.0f - " + eta + " * " + eta + " * (1.0f - " + d + " * " + d + "))";
			return Mapping.Mapped.of("(" + k + " < 0.0f ? 0.0f : " + eta + " * " + i + " - (" + eta + " * " + d
				+ " + sqrt(" + k + ")) * " + n + ")");
		});
	}

	// --- 8.6 matrix ---

	private void registerMatrices() {
		register("matrixCompMult", c -> new Mapping.Mapped("qs::matrixCompMult(" + c.args().get(0) + ", "
			+ c.args().get(1) + ")", List.of("matrixCompMult")));
		register("outerProduct", c -> new Mapping.Mapped("qs::outerProduct(" + c.args().get(0) + ", "
			+ c.args().get(1) + ")", List.of("outerProduct")));
		register("inverse", c -> new Mapping.Mapped("qs::inverse(" + c.args().get(0) + ")", List.of("inverse")));
	}

	// --- 8.7 vector relational ---

	private void registerRelational() {
		register("lessThan", op("<"));
		register("lessThanEqual", op("<="));
		register("greaterThan", op(">"));
		register("greaterThanEqual", op(">="));
		register("equal", op("=="));
		register("notEqual", op("!="));
		register("not", c -> Mapping.Mapped.of("(!(" + c.args().get(0) + "))"));
	}

	private static Mapper op(String operator) {
		return c -> Mapping.Mapped.of(paren(c.args().get(0) + " " + operator + " " + c.args().get(1)));
	}

	// --- 8.4 packing ---

	private void registerPacking() {
		register("packUnorm2x16", direct("pack_float_to_unorm2x16"));
		register("packSnorm2x16", direct("pack_float_to_snorm2x16"));
		register("packUnorm4x8", direct("pack_float_to_unorm4x8"));
		register("packSnorm4x8", direct("pack_float_to_snorm4x8"));
		register("unpackUnorm2x16", direct("unpack_unorm2x16_to_float"));
		register("unpackSnorm2x16", direct("unpack_snorm2x16_to_float"));
		register("unpackUnorm4x8", direct("unpack_unorm4x8_to_float"));
		register("unpackSnorm4x8", direct("unpack_snorm4x8_to_float"));
		register("packHalf2x16", c -> Mapping.Mapped.of("as_type<uint>(half2(" + c.args().get(0) + "))"));
		register("unpackHalf2x16", c -> Mapping.Mapped.of("float2(as_type<half2>(" + c.args().get(0) + "))"));
		register("packDouble2x32", unsupported("no double precision on Apple GPUs"));
		register("unpackDouble2x32", unsupported("no double precision on Apple GPUs"));
	}

	// --- 8.8 integer ---

	private void registerInteger() {
		register("uaddCarry", helper("uaddCarry"));
		register("usubBorrow", helper("usubBorrow"));
		register("umulExtended", helper("umulExtended"));
		register("imulExtended", helper("imulExtended"));
		register("findLSB", helper("findLSB"));
		register("findMSB", helper("findMSB"));
		register("bitfieldExtract", c -> Mapping.Mapped.of("extract_bits(" + c.args().get(0) + ", uint("
			+ c.args().get(1) + "), uint(" + c.args().get(2) + "))"));
		register("bitfieldInsert", c -> Mapping.Mapped.of("insert_bits(" + c.args().get(0) + ", "
			+ c.args().get(1) + ", uint(" + c.args().get(2) + "), uint(" + c.args().get(3) + "))"));
		register("bitfieldReverse", c -> Mapping.Mapped.of("reverse_bits(" + c.args().get(0) + ")"));
		register("bitCount", c -> Mapping.Mapped.of(c.resultType().replace("ivec", "int")
			+ "(popcount(" + c.args().get(0) + "))"));
	}

	// --- 8.15 noise ---

	private void registerNoise() {
		register("noise1", c -> Mapping.Mapped.of("0.0"));
		register("noise2", c -> Mapping.Mapped.of("float2(0.0)"));
		register("noise3", c -> Mapping.Mapped.of("float3(0.0)"));
		register("noise4", c -> Mapping.Mapped.of("float4(0.0)"));
	}

	// --- 8.14.1 derivatives (fragment only) ---

	private void registerDerivatives() {
		register("dFdx", direct("dfdx"));
		register("dFdxFine", direct("dfdx"));
		register("dFdxCoarse", direct("dfdx"));
		register("dFdy", direct("dfdy"));
		register("dFdyFine", direct("dfdy"));
		register("dFdyCoarse", direct("dfdy"));
		register("fwidth", direct("fwidth"));
		register("fwidthFine", direct("fwidth"));
		register("fwidthCoarse", direct("fwidth"));
		register("interpolateAtCentroid", unsupported("MSL has no interpolation-location controls "
			+ "(interpolateAtCentroid); not observed in the corpus"));
		register("interpolateAtSample", unsupported("needs interpolant-typed inputs from the emitter (MSL "
			+ "interpolant<T,P>); the GLSL interpolateAtSample location control is not in the corpus"));
		register("interpolateAtOffset", unsupported("needs interpolant-typed inputs from the emitter (MSL "
			+ "interpolant<T,P>); the GLSL interpolateAtOffset location control is not in the corpus"));
	}

	// --- 8.16, 8.17 invocation and memory control ---

	private void registerBarriers() {
		// barrier() orders the group's shared memory and, for a shared array moved to device memory (E6), the device writes
		// before it; the image writes too (a compute kernel reads what its group stored into an image).
		register("barrier", c -> Mapping.Mapped.of(
			"threadgroup_barrier(mem_flags::mem_device | mem_flags::mem_texture | mem_flags::mem_threadgroup)"));
		register("memoryBarrier", c -> Mapping.Mapped.of(
			"threadgroup_barrier(mem_flags::mem_device | mem_flags::mem_texture | mem_flags::mem_threadgroup)"));
		register("memoryBarrierBuffer", c -> Mapping.Mapped.of("threadgroup_barrier(mem_flags::mem_device)"));
		register("memoryBarrierAtomicCounter", c -> Mapping.Mapped.of("threadgroup_barrier(mem_flags::mem_device)"));
		// A shared array above 32 KB lives in device memory (E6, the spill buffer), so the shared barrier orders device writes too.
		register("memoryBarrierShared", c -> Mapping.Mapped.of(
			"threadgroup_barrier(mem_flags::mem_device | mem_flags::mem_threadgroup)"));
		register("memoryBarrierImage", c -> Mapping.Mapped.of("threadgroup_barrier(mem_flags::mem_texture)"));
		register("groupMemoryBarrier", c -> Mapping.Mapped.of(
			"threadgroup_barrier(mem_flags::mem_device | mem_flags::mem_texture | mem_flags::mem_threadgroup)"));
	}

	// --- 8.11 atomic memory ---

	private void registerAtomicMemory() {
		register("atomicAdd", atomic("atomic_fetch_add_explicit"));
		register("atomicMin", atomic("atomic_fetch_min_explicit"));
		register("atomicMax", atomic("atomic_fetch_max_explicit"));
		register("atomicAnd", atomic("atomic_fetch_and_explicit"));
		register("atomicOr", atomic("atomic_fetch_or_explicit"));
		register("atomicXor", atomic("atomic_fetch_xor_explicit"));
		register("atomicExchange", atomic("atomic_exchange_explicit"));
		register("atomicCompSwap", c -> new Mapping.Mapped("qs::atomicCompSwap(&(" + c.args().get(0) + "), "
			+ c.args().get(1) + ", " + c.args().get(2) + ")", List.of("atomicCompSwap")));
	}

	// --- families this engine does not serve ---

	private void registerUnsupportedFamilies() {
		String counter = "GLSL atomic counters have no Metal equivalent; packs use SSBO atomics "
			+ "(atomicAdd on a device atomic_uint)";
		for (String n : new String[]{"atomicCounterIncrement", "atomicCounterDecrement", "atomicCounter",
			"atomicCounterAdd", "atomicCounterSubtract", "atomicCounterMin", "atomicCounterMax", "atomicCounterAnd",
			"atomicCounterOr", "atomicCounterXor", "atomicCounterExchange", "atomicCounterCompSwap"})
			register(n, unsupported(counter));
		register("EmitVertex", unsupported("geometry shaders: the engine has no geometry stage (ADR 0006; "
			+ "no corpus pack uses one)"));
		register("EndPrimitive", unsupported("geometry shaders: the engine has no geometry stage (ADR 0006; "
			+ "no corpus pack uses one)"));
		register("subpassLoad", unsupported("Vulkan subpass inputs have no place in this engine"));
		register("subpassLoadMS", unsupported("Vulkan subpass inputs have no place in this engine"));
		String group = "GL invocation groups are not Metal quad or simd groups (anyInvocation); "
			+ "not observed in the corpus";
		register("anyInvocation", unsupported(group));
		register("allInvocations", unsupported(group));
		register("allInvocationsEqual", unsupported(group));
	}

	// --- 8.9 textures ---

	private void registerTextures() {
		form("texture", Form.SAMPLE);
		form("textureProj", Form.SAMPLE_PROJ);
		form("textureLod", Form.SAMPLE_LOD);
		form("textureOffset", Form.SAMPLE_OFFSET);
		form("textureProjOffset", Form.SAMPLE_PROJ_OFFSET);
		form("textureLodOffset", Form.SAMPLE_LOD_OFFSET);
		form("textureProjLod", Form.SAMPLE_PROJ_LOD);
		form("textureProjLodOffset", Form.SAMPLE_PROJ_LOD_OFFSET);
		form("textureGrad", Form.SAMPLE_GRAD);
		form("textureGradOffset", Form.SAMPLE_GRAD_OFFSET);
		form("textureProjGrad", Form.SAMPLE_PROJ_GRAD);
		form("textureProjGradOffset", Form.SAMPLE_PROJ_GRAD_OFFSET);
		form("texelFetch", Form.FETCH);
		form("texelFetchOffset", Form.FETCH_OFFSET);
		form("textureGather", Form.GATHER);
		form("textureGatherOffset", Form.GATHER_OFFSET);
		form("textureGatherOffsets", Form.GATHER_OFFSETS);
		form("textureSize", Form.SIZE);
		form("textureQueryLevels", Form.LEVELS);
		form("textureSamples", Form.SAMPLES);
		// Compatibility names (GLSL 4.60 8.9.5) and the extension names packs still use (texture2DGradARB from
		// ARB_shader_texture_lod, texture2DGradEXT and texelFetch2D from EXT_gpu_shader4).
		for (String d : new String[]{"1D", "2D", "3D", "Cube"}) {
			form("texture" + d, Form.SAMPLE);
			form("texture" + d + "Lod", Form.SAMPLE_LOD);
			if (!d.equals("Cube")) form("texture" + d + "Proj", Form.SAMPLE_PROJ);
			if (!d.equals("Cube")) form("texture" + d + "ProjLod", Form.SAMPLE_PROJ_LOD);
			if (d.equals("1D") || d.equals("2D")) {  // GLSL 4.60 8.9.5 has shadow1D and shadow2D only
				form("shadow" + d, Form.SAMPLE);
				form("shadow" + d + "Lod", Form.SAMPLE_LOD);
				form("shadow" + d + "Proj", Form.SAMPLE_PROJ);
				form("shadow" + d + "ProjLod", Form.SAMPLE_PROJ_LOD);
			}
		}
		form("texture2DGradARB", Form.SAMPLE_GRAD);
		form("texture2DGradEXT", Form.SAMPLE_GRAD);
		form("texelFetch2D", Form.FETCH);
		form("textureQueryLod", Form.QUERY_LOD);
	}

	private void form(String name, Form form) {
		register(name, c -> TextureCalls.map(c, form));
		forms.put(name, form);
	}

	// --- 8.12 images ---

	private void registerImages() {
		for (String n : new String[]{"imageSize", "imageSamples", "imageLoad", "imageStore", "imageAtomicAdd",
			"imageAtomicMin", "imageAtomicMax", "imageAtomicAnd", "imageAtomicOr", "imageAtomicXor",
			"imageAtomicExchange", "imageAtomicCompSwap"})
			register(n, ImageCalls::map);
	}

	// --- mapper factories ---

	private static Mapper direct(String mslName) {
		return c -> Mapping.Mapped.of(mslName + "(" + String.join(", ", c.args()) + ")");
	}


	private static Mapper helper(String name) {
		return c -> new Mapping.Mapped("qs::" + name + "(" + String.join(", ", c.args()) + ")", List.of(name));
	}

	private static Mapper atomic(String mslFunction) {
		return c -> Mapping.Mapped.of(mslFunction + "(&(" + c.args().get(0) + "), " + c.args().get(1)
			+ ", memory_order_relaxed)");
	}

	private static Mapper unsupported(String reason) {
		return c -> new Mapping.Unsupported(reason);
	}

	private static String paren(String text) {
		return "(" + text + ")";
	}

	/** Same-name functions: the MSL call is the GLSL call verbatim. */
	static boolean isSameName(String name) {
		return SAME_NAME.contains(name);
	}
}
