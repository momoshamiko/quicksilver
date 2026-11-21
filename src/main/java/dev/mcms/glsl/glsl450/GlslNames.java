// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.glsl450;

import dev.mcms.glsl.ast.ArrayDim;
import dev.mcms.glsl.ast.BuiltinTypeSpec;
import dev.mcms.glsl.ast.CallExpr;
import dev.mcms.glsl.ast.Expr;
import dev.mcms.glsl.ast.IntLiteral;
import dev.mcms.glsl.ast.NameTypeSpec;
import dev.mcms.glsl.ast.Pos;
import dev.mcms.glsl.ast.TypeCallee;
import dev.mcms.glsl.ast.TypeSpec;
import dev.mcms.glsl.msl.ProgramRequest.Semantic;
import dev.mcms.glsl.types.ArrayType;
import dev.mcms.glsl.types.GlslType;
import dev.mcms.glsl.types.MatrixType;
import dev.mcms.glsl.types.ScalarKind;
import dev.mcms.glsl.types.ScalarType;
import dev.mcms.glsl.types.StructField;
import dev.mcms.glsl.types.StructType;
import dev.mcms.glsl.types.VectorType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Names and small builders the stage printer shares: the compatibility built-ins and where each one goes in Vulkan GLSL
 * 4.50, the legacy texture functions, vertex format spellings and zero values. The lowering mirrors the MSL emitter
 * (W3-B's {@code StageEmitter}) so both roads give one program the same inputs and outputs.
 */
final class GlslNames {

	private static final Pos AT = new Pos(0, 0);

	/** The vertex attributes the draw provides, by the compatibility or 1.17+ name the pack reads. */
	static final Map<String, Semantic> ATTRIBUTES = Map.ofEntries(
		Map.entry("gl_Vertex", Semantic.POSITION), Map.entry("vaPosition", Semantic.POSITION),
		Map.entry("gl_Color", Semantic.COLOR), Map.entry("vaColor", Semantic.COLOR),
		Map.entry("gl_Normal", Semantic.NORMAL), Map.entry("vaNormal", Semantic.NORMAL),
		Map.entry("gl_MultiTexCoord0", Semantic.TEXCOORD0), Map.entry("vaUV0", Semantic.TEXCOORD0),
		Map.entry("vaUV1", Semantic.OVERLAY),
		Map.entry("gl_MultiTexCoord1", Semantic.LIGHTMAP), Map.entry("vaUV2", Semantic.LIGHTMAP),
		Map.entry("gl_MultiTexCoord2", Semantic.TEXCOORD2),
		Map.entry("mc_Entity", Semantic.ENTITY), Map.entry("mc_midTexCoord", Semantic.MID_TEXCOORD),
		Map.entry("at_tangent", Semantic.TANGENT), Map.entry("at_midBlock", Semantic.MID_BLOCK),
		Map.entry("at_velocity", Semantic.VELOCITY), Map.entry("dhMaterialId", Semantic.DH_MATERIAL_ID));

	/** The compatibility matrices and the uniform field each one reads from the engine's blocks. */
	static final Map<String, String> MATRICES = Map.of("gl_ModelViewMatrix", "modelViewMatrix",
		"gl_ProjectionMatrix", "projectionMatrix", "gl_NormalMatrix", "normalMatrix", "gl_TextureMatrix",
		"textureMatrix");

	/** Vertex outputs that carry the compatibility varyings, by name. */
	static final Set<String> VARYING_OUT = Set.of("gl_FrontColor", "gl_BackColor", "gl_FrontSecondaryColor",
		"gl_BackSecondaryColor", "gl_FogFragCoord", "gl_TexCoord");

	/** Fragment inputs that carry the compatibility varyings, with the vertex output each one reads. */
	static final Map<String, String> VARYING_IN = Map.of("gl_Color", "gl_FrontColor", "gl_SecondaryColor",
		"gl_FrontSecondaryColor", "gl_TexCoord", "gl_TexCoord", "gl_FogFragCoord", "gl_FogFragCoord");

	/** Built-ins whose GLSL 4.50 spelling differs (Vulkan semantics for the vertex indices). */
	static final Set<String> INDEX_BUILTINS = Set.of("gl_VertexID", "gl_InstanceID");

	/** Core built-ins that keep their names in Vulkan GLSL 4.50; every other {@code gl_} built-in becomes {@code qs_}. */
	static final Set<String> KEPT = Set.of("gl_Position", "gl_PointSize", "gl_ClipDistance", "gl_CullDistance",
		"gl_FragCoord", "gl_FrontFacing", "gl_PointCoord", "gl_FragDepth", "gl_SampleID", "gl_SamplePosition",
		"gl_SampleMaskIn", "gl_SampleMask", "gl_HelperInvocation", "gl_PrimitiveID", "gl_Layer", "gl_ViewportIndex",
		"gl_VertexIndex", "gl_InstanceIndex", "gl_GlobalInvocationID", "gl_LocalInvocationID", "gl_WorkGroupID",
		"gl_NumWorkGroups", "gl_LocalInvocationIndex", "gl_WorkGroupSize", "gl_NumSamples");

	private static final Pattern FORMAT =
		Pattern.compile("(float|half|uchar|char|ushort|short|uint|int)([1-4])(Normalized)?");

	/** Legacy texture functions (GLSL 1.10-1.30 names): texture2D, texture2DProjLod, shadow2DLod, textureCube, ... */
	private static final Pattern LEGACY = Pattern.compile("(texture|shadow)(1D|2D|3D|Cube|Rect)(Proj)?(Lod|Grad)?");

	/**
	 * The extension names packs still use, as W2-C's builtin table maps them: texture2DGradARB from
	 * ARB_shader_texture_lod, texture2DGradEXT and texelFetch2D from EXT_gpu_shader4.
	 */
	private static final Map<String, String> LEGACY_EXTENSIONS = Map.of(
		"texture2DGradARB", "textureGrad", "texture2DGradEXT", "textureGrad", "texelFetch2D", "texelFetch");

	private GlslNames() {
	}

	/** The GLSL 4.50 name of a built-in the pack reads or writes. */
	static String builtinName(String name) {
		if (name.equals("gl_VertexID")) {
			return "gl_VertexIndex";
		}
		if (name.equals("gl_InstanceID")) {
			return "gl_InstanceIndex";
		}
		return KEPT.contains(name) ? name : "qs_" + name;
	}

	/** The GLSL 4.50 function a legacy texture function became, or null when the name is not legacy. */
	static String legacyCall(String name) {
		String extension = LEGACY_EXTENSIONS.get(name);
		if (extension != null) {
			return extension;
		}
		Matcher m = LEGACY.matcher(name);
		if (!m.matches()) {
			return null;
		}
		return "texture" + (m.group(3) != null ? "Proj" : "") + (m.group(4) != null ? m.group(4) : "");
	}

	/** The components an attribute of this Metal format delivers: a three-component attribute is read as four. */
	static int components(String format) {
		Matcher m = FORMAT.matcher(format);
		if (!m.matches()) {
			throw new IllegalArgumentException("unknown vertex format " + format);
		}
		int n = Integer.parseInt(m.group(2));
		return n == 3 ? 4 : n;
	}

	/** The GLSL type of an attribute's input: float for float and normalized formats, else int or uint. */
	static String attributeType(String format) {
		Matcher m = FORMAT.matcher(format);
		if (!m.matches()) {
			throw new IllegalArgumentException("unknown vertex format " + format);
		}
		String base = m.group(1);
		boolean normalized = m.group(3) != null;
		ScalarKind kind = switch (base) {
			case "float", "half" -> ScalarKind.FLOAT;
			case "uchar", "ushort", "uint" -> normalized ? ScalarKind.FLOAT : ScalarKind.UINT;
			case "char", "short", "int" -> normalized ? ScalarKind.FLOAT : ScalarKind.INT;
			default -> throw new IllegalArgumentException(format);
		};
		int comps = components(format);
		return comps == 1 ? scalarName(kind) : prefix(kind) + comps;
	}

	/** The GLSL spelling of a scalar kind. */
	static String scalarName(ScalarKind kind) {
		return kind.name().toLowerCase(java.util.Locale.ROOT);
	}

	/** The prefix of a vector of a scalar kind: vec, ivec, uvec, bvec or dvec. */
	static String prefix(ScalarKind kind) {
		return switch (kind) {
			case FLOAT -> "vec";
			case INT -> "ivec";
			case UINT -> "uvec";
			case BOOL -> "bvec";
			case DOUBLE -> "dvec";
		};
	}

	/** A literal of a scalar kind with the value 0 or 1 (or any small integer). */
	static String literal(ScalarKind kind, int value) {
		return switch (kind) {
			case FLOAT, DOUBLE -> value + ".0";
			case UINT -> value + "u";
			case BOOL -> value == 0 ? "false" : "true";
			case INT -> String.valueOf(value);
		};
	}

	/** True for int and uint components (they are flat varyings: Metal does not interpolate integers). */
	static boolean isInteger(GlslType t) {
		ScalarKind k = t.componentKind();
		return k == ScalarKind.INT || k == ScalarKind.UINT;
	}

	/** The GLSL spelling of a type, with the size of an array (as a type, not a declarator). */
	static String typeName(GlslType t) {
		if (t instanceof ArrayType at) {
			return typeName(at.element()) + "[" + (at.size() == null ? "" : at.size()) + "]";
		}
		return t.toString();
	}

	/** A declaration of a name with the type: the array sizes follow the name, as GLSL writes them. */
	static String declare(GlslType t, String name) {
		StringBuilder dims = new StringBuilder();
		GlslType base = t;
		while (base instanceof ArrayType at) {
			dims.append('[').append(at.size() == null ? "" : at.size()).append(']');
			base = at.element();
		}
		return base + " " + name + dims;
	}

	/** The number of locations a varying of this type takes: one per column of a matrix, else one per array element. */
	static int locations(GlslType t) {
		if (t instanceof MatrixType m) {
			return m.cols();
		}
		return t instanceof ArrayType at && at.size() != null ? at.size() * locations(at.element()) : 1;
	}

	/** A zero value of the type as an expression (constructor calls with zero arguments); null when none exists. */
	static Expr zero(GlslType t) {
		if (t instanceof ArrayType at) {
			if (at.size() == null || at.element() instanceof ArrayType) {
				return null;
			}
			List<Expr> args = new ArrayList<>();
			for (int i = 0; i < at.size(); i++) {
				Expr z = zero(at.element());
				if (z == null) {
					return null;
				}
				args.add(z);
			}
			TypeSpec spec = specOf(at.element());
			return new CallExpr(AT, new TypeCallee(AT, spec, List.of(new ArrayDim(AT, intLiteral(at.size())))), args);
		}
		if (t instanceof StructType st) {
			List<Expr> args = new ArrayList<>();
			for (StructField f : st.fields()) {
				Expr z = zero(f.type());
				if (z == null) {
					return null;
				}
				args.add(z);
			}
			return new CallExpr(AT, new TypeCallee(AT, new NameTypeSpec(AT, st.name()), List.of()), args);
		}
		if (t instanceof ScalarType || t instanceof VectorType || t instanceof MatrixType) {
			return new CallExpr(AT, new TypeCallee(AT, new BuiltinTypeSpec(AT, t.toString()), List.of()),
				List.of(intLiteral(0)));
		}
		return null;
	}

	private static TypeSpec specOf(GlslType t) {
		if (t instanceof StructType st) {
			return new NameTypeSpec(AT, st.name());
		}
		return new BuiltinTypeSpec(AT, t.toString());
	}

	/** An integer literal node. */
	static IntLiteral intLiteral(long value) {
		return new IntLiteral(AT, String.valueOf(value), value, false);
	}

	/**
	 * The core compatibility-matrix name of a used built-in: {@code gl_ModelViewMatrixInverse} has the core
	 * {@code gl_ModelViewMatrix}. Null when the name is not a compatibility matrix (the Inverse and Transpose suffixes
	 * strip in either order, as the MSL emitter's {@code compatMatrix} strips them).
	 */
	static String matrixCore(String name) {
		String core = name.replace("InverseTranspose", "").replace("Inverse", "").replace("Transpose", "");
		if (name.equals(core)) {
			return MATRICES.containsKey(name) || name.equals("gl_ModelViewProjectionMatrix") ? name : null;
		}
		return MATRICES.containsKey(core) || core.equals("gl_ModelViewProjectionMatrix") ? core : null;
	}
}

