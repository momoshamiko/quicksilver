// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.sema;

import dev.mcms.glsl.types.ArrayType;
import dev.mcms.glsl.types.GlslType;
import dev.mcms.glsl.types.MatrixType;
import dev.mcms.glsl.types.ScalarKind;
import dev.mcms.glsl.types.ScalarType;
import dev.mcms.glsl.types.StructField;
import dev.mcms.glsl.types.StructType;
import dev.mcms.glsl.types.Types;
import dev.mcms.glsl.types.VectorType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The built-in variables per stage, version and profile, from the GLSL specifications: sections 7.1 (vertex,
 * fragment, compute special variables), 7.2 (compatibility vertex inputs), 7.3 (built-in constants), 7.4
 * (built-in uniform state) of 4.60 and the same sections of 1.10 to 4.30 for the versions each name appeared;
 * the compatibility entries ({@code gl_Vertex}, {@code gl_FragData}, the matrices, the light and fog
 * structures) are the ones shader packs draw on. Struct identities are unique (ids start high so analyzer
 * structs never collide). Constant entries carry the specification's minimum value, so array sizes like
 * {@code gl_TexCoord[gl_MaxTextureCoords]} fold.
 */
public final class BuiltinVariables {
	private BuiltinVariables() {
	}

	/** How a built-in variable behaves in its stage. */
	public enum VarKind {
		INPUT("in", true),
		OUTPUT("out", false),
		UNIFORM("uniform", true),
		CONST("const", true);

		final String word;
		final boolean readOnly;

		VarKind(String word, boolean readOnly) {
			this.word = word;
			this.readOnly = readOnly;
		}
	}

	public record Entry(String name, GlslType type, VarKind kind, Stage stage, int since, boolean compat,
			ConstValue value) {
	}

	private static final Map<String, List<Entry>> ENTRIES = new HashMap<>();
	private static final Map<String, Entry> UNDECLARED_GAME_NAMES = new HashMap<>();
	private static int id = 1_000_000;

	private static int nextId() {
		return id++;
	}

	static {
		constants();
		vertexCore();
		vertexCompat();
		fragmentCore();
		fragmentCompat();
		compute();
		subgroup();
		compatUniformState();
		undeclaredGameNames();
	}

	/**
	 * Game names a pack may use without declaring them. The corpus uses exactly these four undeclared (Complementary's
	 * gbuffers_basic.glsl reads projectionMatrix, modelViewMatrix, vaPosition and vaNormal, which Iris and Vitrail
	 * provide). The analyzer accepts them with the leniency {@code undeclared-game-name}; every other game name (the
	 * uniforms, samplers and attributes of the format document) must be declared, as the packs do.
	 */
	private static void undeclaredGameNames() {
		UNDECLARED_GAME_NAMES.put("projectionMatrix", new Entry("projectionMatrix", Types.fromWord("mat4"),
				VarKind.UNIFORM, null, 110, false, null));
		UNDECLARED_GAME_NAMES.put("modelViewMatrix", new Entry("modelViewMatrix", Types.fromWord("mat4"),
				VarKind.UNIFORM, null, 110, false, null));
		UNDECLARED_GAME_NAMES.put("vaPosition", new Entry("vaPosition", Types.fromWord("vec3"), VarKind.INPUT,
				Stage.VERTEX, 110, false, null));
		UNDECLARED_GAME_NAMES.put("vaNormal", new Entry("vaNormal", Types.fromWord("vec3"), VarKind.INPUT,
				Stage.VERTEX, 110, false, null));
	}

	/** The undeclared-game-name entry for a name, or null when the game does not license it undeclared. */
	public static Entry undeclaredGameName(String name) {
		return UNDECLARED_GAME_NAMES.get(name);
	}


	/** Every entry of a name, across stages and versions (the analyzer picks by stage, version and profile). */
	public static List<Entry> byName(String name) {
		return ENTRIES.getOrDefault(name, List.of());
	}

	public static boolean isBuiltinName(String name) {
		return ENTRIES.containsKey(name);
	}

	// --- 7.3 constants ---

	private static void constants() {
		// core, since 1.10 (1.10's set), then per version
		c110("gl_MaxVertexAttribs", 16);
		c110("gl_MaxVertexUniformComponents", 1024);
		c110("gl_MaxFragmentUniformComponents", 1024);
		c110("gl_MaxTextureImageUnits", 16);
		c110("gl_MaxVertexTextureImageUnits", 16);
		c110("gl_MaxCombinedTextureImageUnits", 96);
		c110("gl_MaxDrawBuffers", 8);
		c("gl_MaxVaryingComponents", 130, 60);
		c("gl_MaxVaryingVectors", 130, 15);
		c("gl_MaxVertexOutputComponents", 140, 64);
		c("gl_MaxFragmentInputComponents", 140, 128);
		c("gl_MaxVertexUniformVectors", 130, 256);
		c("gl_MaxFragmentUniformVectors", 130, 256);
		c("gl_MinProgramTexelOffset", 330, -8);
		c("gl_MaxProgramTexelOffset", 330, 7);
		c("gl_MaxClipDistances", 130, 8);
		c("gl_MaxCullDistances", 450, 8);
		c("gl_MaxCombinedClipAndCullDistances", 450, 8);
		c("gl_MaxSamples", 130, 4);
		c("gl_MaxImageUnits", 420, 8);
		c("gl_MaxFragmentImageUniforms", 420, 8);
		c("gl_MaxComputeImageUniforms", 420, 8);
		c("gl_MaxCombinedImageUniforms", 420, 48);
		c("gl_MaxVertexAtomicCounters", 420, 0);
		c("gl_MaxFragmentAtomicCounters", 420, 8);
		c("gl_MaxComputeAtomicCounters", 420, 8);
		c("gl_MaxCombinedAtomicCounters", 420, 8);
		c("gl_MaxVertexAtomicCounterBuffers", 420, 0);
		c("gl_MaxFragmentAtomicCounterBuffers", 420, 1);
		c("gl_MaxComputeAtomicCounterBuffers", 420, 8);
		c("gl_MaxCombinedAtomicCounterBuffers", 420, 1);
		c("gl_MaxAtomicCounterBufferSize", 420, 32);
		c("gl_MaxComputeUniformComponents", 430, 1024);
		c("gl_MaxComputeTextureImageUnits", 430, 16);
		c("gl_MaxGeometryInputComponents", 150, 64);
		c("gl_MaxGeometryOutputComponents", 150, 128);
		c("gl_MaxGeometryOutputVertices", 150, 256);
		c("gl_MaxGeometryTotalOutputComponents", 150, 1024);
		c("gl_MaxGeometryTextureImageUnits", 150, 16);
		c("gl_MaxGeometryUniformComponents", 150, 1024);
		c("gl_MaxTextureCoords", 110, 8, true);
		c("gl_MaxTextureUnits", 110, 2, true);
		c("gl_MaxClipPlanes", 110, 8, true);
		c("gl_MaxVaryingFloats", 110, 60, true);
		c("gl_MaxLights", 110, 8, true);
		c("gl_MaxGeometryVaryingComponents", 150, 64, true);
		ivec3("gl_MaxComputeWorkGroupCount", 430, 65535, 65535, 65535);
		ivec3("gl_MaxComputeWorkGroupSize", 430, 1024, 1024, 64);
	}

	private static void c110(String name, long value) {
		c(name, 110, value, false);
	}

	private static void c(String name, int since, long value) {
		c(name, since, value, false);
	}

	private static void c(String name, int since, long value, boolean compat) {
		add(name, ScalarType.INT, VarKind.CONST, null, since, compat, ConstValue.ofInt(value));
	}

	private static void ivec3(String name, int since, long x, long y, long z) {
		add(name, VectorType.IVEC3, VarKind.CONST, null, since, false,
				ConstValue.vector(VectorType.IVEC3, ConstValue.ofInt(x), ConstValue.ofInt(y),
						ConstValue.ofInt(z)));
	}

	// --- 7.1 core per stage ---

	private static void vertexCore() {
		add("gl_Position", VectorType.VEC4, VarKind.OUTPUT, Stage.VERTEX, 110, false, null);
		add("gl_PointSize", ScalarType.FLOAT, VarKind.OUTPUT, Stage.VERTEX, 110, false, null);
		add("gl_VertexID", ScalarType.INT, VarKind.INPUT, Stage.VERTEX, 130, false, null);
		add("gl_InstanceID", ScalarType.INT, VarKind.INPUT, Stage.VERTEX, 140, false, null);
		add("gl_DrawID", ScalarType.INT, VarKind.INPUT, Stage.VERTEX, 460, false, null);
		add("gl_BaseVertex", ScalarType.INT, VarKind.INPUT, Stage.VERTEX, 460, false, null);
		add("gl_BaseInstance", ScalarType.INT, VarKind.INPUT, Stage.VERTEX, 460, false, null);
		add("gl_ClipDistance", new ArrayType(ScalarType.FLOAT, null), VarKind.OUTPUT, Stage.VERTEX, 130, false,
				null);
		add("gl_CullDistance", new ArrayType(ScalarType.FLOAT, null), VarKind.OUTPUT, Stage.VERTEX, 450, false,
				null);
	}

	private static void fragmentCore() {
		add("gl_FragCoord", VectorType.VEC4, VarKind.INPUT, Stage.FRAGMENT, 110, false, null);
		add("gl_FrontFacing", ScalarType.BOOL, VarKind.INPUT, Stage.FRAGMENT, 110, false, null);
		add("gl_PointCoord", VectorType.VEC2, VarKind.INPUT, Stage.FRAGMENT, 110, false, null);
		add("gl_FragDepth", ScalarType.FLOAT, VarKind.OUTPUT, Stage.FRAGMENT, 110, false, null);
		add("gl_PrimitiveID", ScalarType.INT, VarKind.INPUT, Stage.FRAGMENT, 130, false, null);
		add("gl_Layer", ScalarType.INT, VarKind.INPUT, Stage.FRAGMENT, 330, false, null);
		add("gl_ViewportIndex", ScalarType.INT, VarKind.INPUT, Stage.FRAGMENT, 330, false, null);
		add("gl_SampleID", ScalarType.INT, VarKind.INPUT, Stage.FRAGMENT, 400, false, null);
		add("gl_SamplePosition", VectorType.VEC2, VarKind.INPUT, Stage.FRAGMENT, 400, false, null);
		add("gl_SampleMaskIn", new ArrayType(ScalarType.INT, null), VarKind.INPUT, Stage.FRAGMENT, 400, false,
				null);
		add("gl_SampleMask", new ArrayType(ScalarType.INT, null), VarKind.OUTPUT, Stage.FRAGMENT, 400, false,
				null);
		add("gl_HelperInvocation", ScalarType.BOOL, VarKind.INPUT, Stage.FRAGMENT, 400, false, null);
		add("gl_ClipDistance", new ArrayType(ScalarType.FLOAT, null), VarKind.INPUT, Stage.FRAGMENT, 130, false,
				null);
		add("gl_CullDistance", new ArrayType(ScalarType.FLOAT, null), VarKind.INPUT, Stage.FRAGMENT, 450, false,
				null);
	}

	private static void compute() {
		add("gl_NumWorkGroups", VectorType.UVEC3, VarKind.INPUT, Stage.COMPUTE, 430, false, null);
		add("gl_WorkGroupSize", VectorType.UVEC3, VarKind.CONST, Stage.COMPUTE, 430, false, null);
		add("gl_WorkGroupID", VectorType.UVEC3, VarKind.INPUT, Stage.COMPUTE, 430, false, null);
		add("gl_LocalInvocationID", VectorType.UVEC3, VarKind.INPUT, Stage.COMPUTE, 430, false, null);
		add("gl_GlobalInvocationID", VectorType.UVEC3, VarKind.INPUT, Stage.COMPUTE, 430, false, null);
		add("gl_LocalInvocationIndex", ScalarType.UINT, VarKind.INPUT, Stage.COMPUTE, 430, false, null);
	}

	private static void subgroup() {
		add("gl_SubgroupSize", ScalarType.UINT, VarKind.INPUT, Stage.COMPUTE, 460, false, null);
		add("gl_SubgroupInvocationID", ScalarType.UINT, VarKind.INPUT, Stage.COMPUTE, 460, false, null);
		add("gl_SubgroupID", ScalarType.UINT, VarKind.INPUT, Stage.COMPUTE, 460, false, null);
		add("gl_NumSubgroups", ScalarType.UINT, VarKind.INPUT, Stage.COMPUTE, 460, false, null);
	}

	// --- 7.1.7 / 7.2 compatibility variables ---

	private static void vertexCompat() {
		compatIn("gl_Color", VectorType.VEC4, Stage.VERTEX);
		compatIn("gl_SecondaryColor", VectorType.VEC4, Stage.VERTEX);
		compatIn("gl_Normal", VectorType.VEC3, Stage.VERTEX);
		compatIn("gl_Vertex", VectorType.VEC4, Stage.VERTEX);
		for (int i = 0; i <= 7; i++) {
			compatIn("gl_MultiTexCoord" + i, VectorType.VEC4, Stage.VERTEX);
		}
		// Distant Horizons' vertex attribute (engine-pack-format.md 17): packs read it without declaring it, as Iris provides it.
		add("dhMaterialId", ScalarType.INT, VarKind.INPUT, Stage.VERTEX, 110, false, null);
		compatIn("gl_FogCoord", ScalarType.FLOAT, Stage.VERTEX);
		compatOut("gl_ClipVertex", VectorType.VEC4, Stage.VERTEX);
		compatOut("gl_FrontColor", VectorType.VEC4, Stage.VERTEX);
		compatOut("gl_BackColor", VectorType.VEC4, Stage.VERTEX);
		compatOut("gl_FrontSecondaryColor", VectorType.VEC4, Stage.VERTEX);
		compatOut("gl_BackSecondaryColor", VectorType.VEC4, Stage.VERTEX);
		compatOut("gl_TexCoord", new ArrayType(VectorType.VEC4, 8), Stage.VERTEX);
		compatOut("gl_FogFragCoord", ScalarType.FLOAT, Stage.VERTEX);
	}

	private static void fragmentCompat() {
		compatIn("gl_FogFragCoord", ScalarType.FLOAT, Stage.FRAGMENT);
		compatIn("gl_TexCoord", new ArrayType(VectorType.VEC4, 8), Stage.FRAGMENT);
		compatIn("gl_Color", VectorType.VEC4, Stage.FRAGMENT);
		compatIn("gl_SecondaryColor", VectorType.VEC4, Stage.FRAGMENT);
		compatOut("gl_FragColor", VectorType.VEC4, Stage.FRAGMENT);
		compatOut("gl_FragData", new ArrayType(VectorType.VEC4, 8), Stage.FRAGMENT);
	}

	// --- 7.4 built-in uniform state ---

	private static void compatUniformState() {
		StructType depthRangeParams = struct("gl_DepthRangeParameters",
				field("near", ScalarType.FLOAT), field("far", ScalarType.FLOAT),
				field("diff", ScalarType.FLOAT));
		add("gl_DepthRange", depthRangeParams, VarKind.UNIFORM, null, 110, false, null);
		add("gl_NumSamples", ScalarType.INT, VarKind.UNIFORM, null, 400, false, null);
		GlslType mat4 = MatrixType.of(ScalarKind.FLOAT, 4, 4);
		GlslType mat3 = MatrixType.of(ScalarKind.FLOAT, 3, 3);
		GlslType texMat = new ArrayType(mat4, 8);
		compatUniform("gl_ModelViewMatrix", mat4);
		compatUniform("gl_ProjectionMatrix", mat4);
		compatUniform("gl_ModelViewProjectionMatrix", mat4);
		compatUniform("gl_TextureMatrix", texMat);
		compatUniform("gl_NormalMatrix", mat3);
		compatUniform("gl_NormalScale", ScalarType.FLOAT);
		compatUniform("gl_ModelViewMatrixInverse", mat4);
		compatUniform("gl_ProjectionMatrixInverse", mat4);
		compatUniform("gl_ModelViewProjectionMatrixInverse", mat4);
		compatUniform("gl_TextureMatrixInverse", texMat);
		compatUniform("gl_ModelViewMatrixTranspose", mat4);
		compatUniform("gl_ProjectionMatrixTranspose", mat4);
		compatUniform("gl_ModelViewProjectionMatrixTranspose", mat4);
		compatUniform("gl_TextureMatrixTranspose", texMat);
		compatUniform("gl_ModelViewMatrixInverseTranspose", mat4);
		compatUniform("gl_ProjectionMatrixInverseTranspose", mat4);
		compatUniform("gl_ModelViewProjectionMatrixInverseTranspose", mat4);
		compatUniform("gl_TextureMatrixInverseTranspose", texMat);
		compatUniform("gl_ClipPlane", new ArrayType(VectorType.VEC4, 8));
		StructType pointParams = struct("gl_PointParameters",
				field("size", ScalarType.FLOAT), field("sizeMin", ScalarType.FLOAT),
				field("sizeMax", ScalarType.FLOAT), field("fadeThresholdSize", ScalarType.FLOAT),
				field("distanceConstantAttenuation", ScalarType.FLOAT),
				field("distanceLinearAttenuation", ScalarType.FLOAT),
				field("distanceQuadraticAttenuation", ScalarType.FLOAT));
		compatUniform("gl_Point", pointParams);
		StructType material = struct("gl_MaterialParameters",
				field("emission", VectorType.VEC4), field("ambient", VectorType.VEC4),
				field("diffuse", VectorType.VEC4), field("specular", VectorType.VEC4),
				field("shininess", ScalarType.FLOAT));
		compatUniform("gl_FrontMaterial", material);
		compatUniform("gl_BackMaterial", material);
		StructType lightSource = struct("gl_LightSourceParameters",
				field("ambient", VectorType.VEC4), field("diffuse", VectorType.VEC4),
				field("specular", VectorType.VEC4), field("position", VectorType.VEC4),
				field("halfVector", VectorType.VEC4), field("spotDirection", VectorType.VEC3),
				field("spotExponent", ScalarType.FLOAT), field("spotCutoff", ScalarType.FLOAT),
				field("spotCosCutoff", ScalarType.FLOAT),
				field("constantAttenuation", ScalarType.FLOAT),
				field("linearAttenuation", ScalarType.FLOAT),
				field("quadraticAttenuation", ScalarType.FLOAT));
		compatUniform("gl_LightSource", new ArrayType(lightSource, 8));
		StructType lightModel = struct("gl_LightModelParameters",
				field("ambient", VectorType.VEC4));
		compatUniform("gl_LightModel", lightModel);
		StructType lightModelProducts = struct("gl_LightModelProducts",
				field("sceneColor", VectorType.VEC4));
		compatUniform("gl_FrontLightModelProduct", lightModelProducts);
		compatUniform("gl_BackLightModelProduct", lightModelProducts);
		StructType lightProducts = struct("gl_LightProducts",
				field("ambient", VectorType.VEC4), field("diffuse", VectorType.VEC4),
				field("specular", VectorType.VEC4));
		compatUniform("gl_FrontLightProduct", new ArrayType(lightProducts, 8));
		compatUniform("gl_BackLightProduct", new ArrayType(lightProducts, 8));
		compatUniform("gl_TextureEnvColor", new ArrayType(VectorType.VEC4, 2));
		compatUniform("gl_EyePlaneS", new ArrayType(VectorType.VEC4, 8));
		compatUniform("gl_EyePlaneT", new ArrayType(VectorType.VEC4, 8));
		compatUniform("gl_EyePlaneR", new ArrayType(VectorType.VEC4, 8));
		compatUniform("gl_EyePlaneQ", new ArrayType(VectorType.VEC4, 8));
		compatUniform("gl_ObjectPlaneS", new ArrayType(VectorType.VEC4, 8));
		compatUniform("gl_ObjectPlaneT", new ArrayType(VectorType.VEC4, 8));
		compatUniform("gl_ObjectPlaneR", new ArrayType(VectorType.VEC4, 8));
		compatUniform("gl_ObjectPlaneQ", new ArrayType(VectorType.VEC4, 8));
		StructType fog = struct("gl_FogParameters",
				field("color", VectorType.VEC4), field("density", ScalarType.FLOAT),
				field("start", ScalarType.FLOAT), field("end", ScalarType.FLOAT),
				field("scale", ScalarType.FLOAT));
		compatUniform("gl_Fog", fog);
		// the struct names themselves are usable as types in redeclarations
		for (StructType s : new StructType[] {depthRangeParams, pointParams, material, lightSource, lightModel,
				lightModelProducts, lightProducts, fog}) {
			structType(s);
		}
	}

	private static StructType struct(String name, StructField... fields) {
		return new StructType(name, List.of(fields), nextId());
	}

	private static StructField field(String name, GlslType type) {
		return new StructField(name, type);
	}

	private static void structType(StructType s) {
		ENTRIES.computeIfAbsent(s.name(), n -> new ArrayList<>())
				.add(new Entry(s.name(), s, null, null, 110, true, null));
	}

	private static void compatIn(String name, GlslType type, Stage stage) {
		add(name, type, VarKind.INPUT, stage, 110, true, null);
	}

	private static void compatOut(String name, GlslType type, Stage stage) {
		add(name, type, VarKind.OUTPUT, stage, 110, true, null);
	}

	private static void compatUniform(String name, GlslType type) {
		add(name, type, VarKind.UNIFORM, null, 110, true, null);
	}

	private static void add(String name, GlslType type, VarKind kind, Stage stage, int since, boolean compat,
			ConstValue value) {
		ENTRIES.computeIfAbsent(name, n -> new ArrayList<>()).add(
				new Entry(name, type, kind, stage, since, compat, value));
	}
}
