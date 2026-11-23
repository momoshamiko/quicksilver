// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.glsl450;

import dev.mcms.glsl.ast.ArrayDim;
import dev.mcms.glsl.ast.BlockStmt;
import dev.mcms.glsl.ast.BuiltinTypeSpec;
import dev.mcms.glsl.ast.CallExpr;
import dev.mcms.glsl.ast.Decl;
import dev.mcms.glsl.ast.Declarator;
import dev.mcms.glsl.ast.Expr;
import dev.mcms.glsl.ast.FieldExpr;
import dev.mcms.glsl.ast.FunctionDefinition;
import dev.mcms.glsl.ast.FunctionPrototype;
import dev.mcms.glsl.ast.GlslNode;
import dev.mcms.glsl.ast.GlslPrinter;
import dev.mcms.glsl.ast.Ident;
import dev.mcms.glsl.ast.InitializerList;
import dev.mcms.glsl.ast.InterfaceBlock;
import dev.mcms.glsl.ast.InterpKind;
import dev.mcms.glsl.ast.InterpQual;
import dev.mcms.glsl.ast.LayoutItem;
import dev.mcms.glsl.ast.LayoutQual;
import dev.mcms.glsl.ast.NameCallee;
import dev.mcms.glsl.ast.Param;
import dev.mcms.glsl.ast.Pos;
import dev.mcms.glsl.ast.Qualifier;
import dev.mcms.glsl.ast.StorageKind;
import dev.mcms.glsl.ast.StorageQual;
import dev.mcms.glsl.ast.Trees;
import dev.mcms.glsl.ast.Type;
import dev.mcms.glsl.ast.TypeCallee;
import dev.mcms.glsl.ast.Unit;
import dev.mcms.glsl.ast.VarDecl;
import dev.mcms.glsl.ast.IntLiteral;
import dev.mcms.glsl.glsl450.Glsl450Program.ResourceBinding;
import dev.mcms.glsl.transform.FragmentOutputs;
import dev.mcms.glsl.glsl450.Glsl450Program.StageText;
import dev.mcms.glsl.msl.ProgramRequest;
import dev.mcms.glsl.msl.ProgramRequest.BufferBinding;
import dev.mcms.glsl.msl.ProgramRequest.ColorTarget;
import dev.mcms.glsl.msl.ProgramRequest.ImageBinding;
import dev.mcms.glsl.msl.ProgramRequest.Semantic;
import dev.mcms.glsl.msl.ProgramRequest.TextureBinding;
import dev.mcms.glsl.msl.ProgramRequest.UniformBlock;
import dev.mcms.glsl.msl.ProgramRequest.UniformField;
import dev.mcms.glsl.msl.ProgramRequest.VertexAttribute;
import dev.mcms.glsl.sema.SemaResult;
import dev.mcms.glsl.sema.Stage;
import dev.mcms.glsl.sema.Symbol;
import dev.mcms.glsl.sema.VariableSymbol;
import dev.mcms.glsl.types.ArrayType;
import dev.mcms.glsl.types.GlslType;
import dev.mcms.glsl.types.ImageType;
import dev.mcms.glsl.types.SamplerType;
import dev.mcms.glsl.types.ScalarKind;
import dev.mcms.glsl.types.ScalarType;
import dev.mcms.glsl.types.VectorType;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Prints one stage of a program as Vulkan GLSL 4.50. The pack's own declarations and function bodies go through the AST
 * printer after three rewrites: compatibility built-ins and vertex inputs become {@code qs_} globals filled in the entry
 * (see {@link GlslNames}), legacy texture functions get their 4.50 names, and locals start at zero as the MSL emitter's
 * do. The engine's blocks, the vertex inputs and outputs, and the clip-space epilogue are generated text in the same
 * order as the MSL emitter writes them.
 */
final class StagePrinter {

	private static final Pos AT = new Pos(0, 0);
	private static final int BLOCK_BASE = Glsl450Program.BLOCK_BINDING_BASE;
	private static final Set<StorageKind> REWRITTEN = EnumSet.of(StorageKind.IN, StorageKind.OUT,
		StorageKind.ATTRIBUTE, StorageKind.VARYING, StorageKind.UNIFORM, StorageKind.BUFFER);
	private static final Set<String> NUMBERED = Set.of("location", "set", "binding");

	/** How a pack variable of the stage is printed. */
	private enum Place { DROP, PLAIN, CONST, SHARED, IN, OUT, RESOURCE }

	/** A fragment output's value: the GLSL expression that holds it and its type. */
	private record Source(String expr, GlslType type) {
	}

	private final ProgramRequest request;
	private final Stage stage;
	private final Unit unit;
	private final SemaResult sema;
	private final Map<String, Integer> locations;
	private final Map<String, GlslType> vertexOutputs;
	private final List<String> errors;
	private final List<String> warnings;
	private final Set<String> served = new HashSet<>();
	private final Map<String, Integer> blockSlots = new HashMap<>();
	private final List<ResourceBinding> bindings = new ArrayList<>();
	private final StringBuilder prelude = new StringBuilder();
	private final StringBuilder setup = new StringBuilder();
	private final StringBuilder body = new StringBuilder();
	private final Map<Integer, Source> sources = new TreeMap<>();
	/** The gl_FragColor source, written to every colour target (null when unused); mirrors the emitter's outputFor. */
	private Source colorBroadcast;
	private boolean hasMain;

	/**
	 * @param locations the location of each vertex output's varying (the same numbers the fragment stage reads)
	 * @param vertexOutputs the type of each vertex output's varying, by the same key
	 */
	StagePrinter(ProgramRequest request, Stage stage, Unit unit, SemaResult sema, Map<String, Integer> locations,
			Map<String, GlslType> vertexOutputs, List<String> errors, List<String> warnings) {
		this.request = request;
		this.stage = stage;
		this.unit = unit;
		this.sema = sema;
		this.locations = locations;
		this.vertexOutputs = vertexOutputs;
		this.errors = errors;
		this.warnings = warnings;
		for (UniformBlock ub : request.uniforms().blocks()) {
			for (UniformField f : ub.fields()) {
				served.add(f.name());
			}
		}
		if (fullScreen()) {
			served.addAll(QUAD_MEMBERS);
		}
		for (BufferBinding b : request.buffers()) {
			blockSlots.putIfAbsent(b.name(), b.slot());
		}
	}

	/** The full-screen quad's projection in GLSL (W3-B's contract, plan.md E3): [0,1]^2 to clip space, z = -z, w = 1. */
	private static final String QUAD_PROJECTION = "mat4(vec4(2.0, 0.0, 0.0, 0.0), vec4(0.0, 2.0, 0.0, 0.0), "
		+ "vec4(0.0, 0.0, -1.0, 0.0), vec4(-1.0, -1.0, 0.0, 1.0))";

	/** The lightmap matrix, gl_TextureMatrix[1] for 1.17+ (OptiFine TEXTURE_MATRIX_2): scale 1/256, offset 1/32. */
	private static final String LIGHTMAP_MATRIX = "mat4(vec4(0.00390625, 0.0, 0.0, 0.0), "
		+ "vec4(0.0, 0.00390625, 0.0, 0.0), vec4(0.0, 0.0, 0.00390625, 0.0), "
		+ "vec4(0.03125, 0.03125, 0.03125, 1.0))";

	/** The engine matrices a full-screen program reads as the quad's values. */
	private static final List<String> QUAD_MEMBERS = List.of("projectionMatrix", "modelViewMatrix");

	private boolean fullScreen() {
		return request.vertexLayout() != null && request.vertexLayout().fullScreen();
	}

	/** The engine members a full-screen program reads as the quad's values: its projection and model-view matrices. */
	private boolean quadMember(String name) {
		return fullScreen() && (name.equals("projectionMatrix") || name.equals("modelViewMatrix"));
	}

	/**
	 * The quad's matrices, as globals assigned first: the compatibility copies read them, so this runs before the interface.
	 */
	private void quadMatrices() {
		if (!fullScreen()) {
			return;
		}
		// The quad's matrices exist whether or not the schema declares them (ftransform reads them, and the translator
		// serves them for every full-screen program).
		for (String name : QUAD_MEMBERS) {
			String value = name.equals("projectionMatrix") ? QUAD_PROJECTION : "mat4(1.0)";
			prelude.append("mat4 qs_").append(name).append(";\n");
			setup.append("\tqs_").append(name).append(" = ").append(value).append(";\n");
		}
	}

	StageText print() {
		prelude.append("#version 450\n");
		quadMatrices();
		if (stage == Stage.VERTEX) {
			vertexInterface();
		} else if (stage == Stage.FRAGMENT) {
			fragmentInterface();
		}
		for (UniformBlock ub : request.uniforms().blocks()) {
			engineBlock(ub);
		}
		for (Decl d : unit.decls()) {
			emit(d);
		}
		if (!hasMain) {
			errors.add(stageName() + ": no main function");
		}
		String main = "void main() {\n" + setup + "\tqs_main();\n" + epilogue() + "}\n";
		return new StageText(stage, prelude + "\n" + body + main, bindings);
	}

	private String stageName() {
		return switch (stage) {
			case VERTEX -> "vertex";
			case FRAGMENT -> "fragment";
			case COMPUTE -> "compute";
		};
	}

	// ----- the interface ----------------------------------------------------------------------------------------------

	private void vertexInterface() {
		setup.append("\tgl_Position = vec4(0.0);\n");
		if (fullScreen()) {
			// The full-screen quad (plan.md E3, W3-B's contract): two triangles from the vertex index 0-5, corners (0,0),
			// (1,0), (0,1) and (0,1), (1,0), (1,1), as the MSL emitter draws it. The corner feeds position and texcoord 0
			// (also by their 1.17+ names) before the attributes read it, as the MSL emitter's bindAttribute does.
			setup.append("\tvec2 qs_corner = vec2(float((0x32u >> uint(gl_VertexIndex)) & 1u),"
				+ " float((0x2Cu >> uint(gl_VertexIndex)) & 1u));\n");
		}
		List<VertexAttribute> layout = request.vertexLayout() == null ? List.of()
			: request.vertexLayout().attributes();
		for (int i = 0; i < layout.size(); i++) {
			prelude.append("layout(location = ").append(i).append(") in ")
				.append(GlslNames.attributeType(layout.get(i).format())).append(" qs_a").append(i).append(";\n");
		}
		for (VariableSymbol v : sema.globals()) {
			if (!v.builtin() && vertexInput(v) && !(v.declaration() instanceof InterfaceBlock)) {
				attributeInput(v.name(), v.type(), layout);
			}
		}
		for (VariableSymbol v : sema.usedBuiltins().values()) {
			String n = v.name();
			if (!v.builtin() && v.quals().isUniform()) {
				undeclaredUniform(v);
			} else if (GlslNames.ATTRIBUTES.containsKey(n) && vertexInput(v)) {
				attributeInput(n, v.type(), layout);
			} else if (GlslNames.matrixCore(n) != null) {
				matrix(v);
			} else if (GlslNames.VARYING_OUT.contains(n) && v.quals().isOutput()) {
				varyingOut(v);
			} else if (!GlslNames.KEPT.contains(n) && !GlslNames.INDEX_BUILTINS.contains(n)) {
				privateBuiltin(v);
			}
		}
	}

	/**
	 * An undeclared uniform the pack reads (the 1.17+ names, modelViewMatrix and projectionMatrix): the quad's own
	 * matrices or the engine's blocks serve it, exactly once. An unserved one is an error (compat-unbound), as the MSL
	 * emitter refuses it.
	 */
	private void undeclaredUniform(VariableSymbol v) {
		if (quadMember(v.name()) || engineMember(v)) {
			return; // quadMatrices declares and binds it, or an engine block's member carries it
		}
		errors.add(stageName() + ": compat-unbound: '" + v.name()
			+ "' is an undeclared uniform that no engine block serves for this program");
	}

	/** A vertex input: a global the entry fills from its attribute (or leaves at the value the layout's gaps give). */
	private void attributeInput(String name, GlslType type, List<VertexAttribute> layout) {
		String qs = "qs_" + name;
		prelude.append(GlslNames.declare(type, qs)).append(";\n");
		Semantic semantic = GlslNames.ATTRIBUTES.get(name);
		if (semantic == Semantic.TANGENT && !fullScreen() && findAttribute(layout, Semantic.TANGENT) < 0) {
			int normIdx = findAttribute(layout, Semantic.NORMAL);
			if (normIdx >= 0) {
				String normalExpr = castValue("qs_a" + normIdx, GlslNames.components(layout.get(normIdx).format()),
					VectorType.VEC3);
				setup.append("\t{\n");
				setup.append("\t\tvec3 qs_tn = ").append(normalExpr).append(";\n");
				setup.append("\t\tfloat qs_tn2 = dot(qs_tn, qs_tn);\n");
				setup.append("\t\tvec3 qs_tu = qs_tn2 > 1e-6 ? (qs_tn * inversesqrt(qs_tn2)) : vec3(0.0, 1.0, 0.0);\n");
				setup.append("\t\tvec3 qs_tt = abs(qs_tu.y) < 0.999 ? normalize(cross(vec3(0.0, 1.0, 0.0), qs_tu)) : vec3(1.0, 0.0, 0.0);\n");
				setup.append("\t\t").append(qs).append(" = ").append(castValue("vec4(qs_tt, 1.0)", 4, type)).append(";\n");
				setup.append("\t}\n");
				return;
			}
		}
		setup.append('\t').append(qs).append(" = ")
			.append(semantic == null ? zeroText(type) : attributeValue(semantic, type, layout)).append(";\n");
	}

	private static int findAttribute(List<VertexAttribute> layout, Semantic semantic) {
		int index = -1;
		for (int i = 0; i < layout.size(); i++) {
			if (layout.get(i).semantic() == semantic) {
				index = i;
			}
		}
		return index;
	}

	/** The value of an attribute in the pack's type: converted and padded as the MSL emitter does (w defaults to 1). */
	private String attributeValue(Semantic semantic, GlslType type, List<VertexAttribute> layout) {
		if (fullScreen() && (semantic == Semantic.POSITION || semantic == Semantic.TEXCOORD0)) {
			// The quad carries position and texcoord 0 from its corner; the other attributes keep their GLSL defaults.
			if (type instanceof VectorType v) {
				StringBuilder sb = new StringBuilder(GlslNames.typeName(v)).append("(qs_corner");
				for (int i = 2; i < v.size(); i++) {
					sb.append(i == 3 ? ", 1.0" : ", 0.0");
				}
				return sb.append(')').toString();
			}
			return castValue("vec4(qs_corner, 0.0, 1.0)", 4, type);
		}
		int index = -1;
		for (int i = 0; i < layout.size(); i++) {
			if (layout.get(i).semantic() == semantic) {
				index = i;
			}
		}
		if (index < 0) {
			if (type instanceof VectorType v && v.size() == 4 && semantic == Semantic.POSITION) {
				return "vec4(0.0, 0.0, 0.0, 1.0)";
			}
			if (type instanceof VectorType v && v.size() == 4 && semantic == Semantic.COLOR) {
				return "vec4(1.0)";
			}
			if (semantic == Semantic.TANGENT) {
				return castValue("vec4(1.0, 0.0, 0.0, 1.0)", 4, type);
			}
			return zeroText(type);
		}
		return castValue("qs_a" + index, GlslNames.components(layout.get(index).format()), type);
	}

	/** A value of {@code comps} components cast to the declared type: widened with GL's defaults (w = 1). */
	private static String castValue(String arg, int comps, GlslType type) {
		ScalarKind kind = type.componentKind();
		String typed = comps == 1 ? GlslNames.scalarName(kind) + "(" + arg + ")"
			: GlslNames.prefix(kind) + comps + "(" + arg + ")";
		if (!(type instanceof VectorType v)) {
			return GlslNames.typeName(type) + "(" + typed + ")";
		}
		String target = GlslNames.typeName(v);
		if (comps >= v.size()) {
			return target + "(" + typed + ")";
		}
		StringBuilder sb = new StringBuilder(target).append('(').append(typed);
		for (int i = comps; i < v.size(); i++) {
			sb.append(", ").append(GlslNames.literal(kind, i == 3 ? 1 : 0));
		}
		return sb.append(')').toString();
	}

	/**
	 * A compatibility matrix, with or without the Inverse and Transpose suffixes: copied from the engine block that
	 * serves it, from the quad's own matrices for a full-screen program, or from the fixed texture matrices, as the MSL
	 * emitter's {@code compatMatrix} serves it in every stage. An unserved matrix is an error (compat-unbound), never a
	 * silent zero.
	 */
	private void matrix(VariableSymbol v) {
		String name = v.name();
		String qs = "qs_" + name;
		prelude.append(GlslNames.declare(v.type(), qs)).append(";\n");
		boolean inverse = name.contains("Inverse");
		boolean transpose = name.contains("Transpose");
		if (v.type() instanceof ArrayType at) {
			// gl_TextureMatrix: [0] from the schema, [1] the lightmap matrix, the others the identity.
			setup.append('\t').append(qs).append(" = ").append(zeroText(v.type())).append(";\n");
			int count = at.size() == null ? 8 : at.size();
			for (int i = 0; i < count; i++) {
				String value = compatMatrixValue("gl_TextureMatrix", i);
				if (value != null) {
					assignMatrix(qs + "[" + i + "]", value, inverse, transpose);
				}
			}
			return;
		}
		String value = compatMatrixValue(GlslNames.matrixCore(name), -1);
		if (value == null) {
			setup.append('\t').append(qs).append(" = ").append(zeroText(v.type())).append(";\n");
			return;
		}
		assignMatrix(qs, value, inverse, transpose);
	}

	private void assignMatrix(String target, String value, boolean inverse, boolean transpose) {
		if (inverse) {
			value = "inverse(" + value + ")";
		}
		if (transpose) {
			value = "transpose(" + value + ")";
		}
		setup.append('\t').append(target).append(" = ").append(value).append(";\n");
	}

	/**
	 * The matrix value of a compatibility core: the quad's matrices for a full-screen program (the modelview is the
	 * identity), else the serving engine block's field, and the fixed texture matrices ([1] the lightmap matrix of
	 * OptiFine's TEXTURE_MATRIX_2, scale 1/256 and offset 1/32; [2...] the identity). Null records a compat-unbound
	 * error, as the MSL emitter refuses an unserved matrix.
	 */
	private String compatMatrixValue(String core, int texIndex) {
		String value = switch (core) {
			case "gl_ModelViewMatrix" -> fullScreen() ? "mat4(1.0)" : servedField("modelViewMatrix");
			case "gl_ProjectionMatrix" -> fullScreen() ? QUAD_PROJECTION : servedField("projectionMatrix");
			case "gl_ModelViewProjectionMatrix" -> fullScreen() ? QUAD_PROJECTION + " * mat4(1.0)"
				: product("projectionMatrix", "modelViewMatrix");
			case "gl_NormalMatrix" -> servedField("normalMatrix");
			case "gl_TextureMatrix" -> texIndex == 1 ? LIGHTMAP_MATRIX
				: texIndex >= 2 ? "mat4(1.0)" : servedField("textureMatrix");
			default -> null;
		};
		if (value == null) {
			errors.add(stageName() + ": compat-unbound: '" + core + (texIndex > 0 ? "[" + texIndex + "]" : "")
				+ "' is a compatibility matrix that no engine block serves for this program");
		}
		return value;
	}

	private String product(String left, String right) {
		String a = servedField(left), b = servedField(right);
		return a == null || b == null ? null : "(" + a + " * " + b + ")";
	}

	/** The engine block member that serves a field, or null when no block of the schema has it. */
	private String servedField(String field) {
		return served.contains(field) ? "qs_" + field : null;
	}

	/** A vertex output the compatibility names write: an out varying at its location. */
	private void varyingOut(VariableSymbol v) {
		Integer location = locations.get(v.name());
		if (location == null) {
			errors.add("vertex: no location for " + v.name());
			return;
		}
		prelude.append("layout(location = ").append(location).append(") ")
			.append(GlslNames.isInteger(v.type()) ? "flat " : "").append("out ")
			.append(GlslNames.declare(v.type(), "qs_" + v.name())).append(";\n");
	}

	private void fragmentInterface() {
		for (VariableSymbol v : sema.usedBuiltins().values()) {
			String n = v.name();
			String key = GlslNames.VARYING_IN.get(n);
			if (key != null && fragmentInput(v)) {
				fragmentVarying(v, key);
			} else if (!v.builtin() && v.quals().isUniform()) {
				undeclaredUniform(v);
			} else if (GlslNames.matrixCore(n) != null) {
				// The compatibility matrices are served in the fragment stage too, as the MSL emitter serves them.
				matrix(v);
			} else if (!GlslNames.KEPT.contains(n)) {
				privateBuiltin(v);
			}
		}
		collectSources();
		for (int k = 0; k < request.outputs().size(); k++) {
			ColorTarget t = request.outputs().get(k);
			Source s = sourceFor(k);
			if (s == null) {
				continue;  // nothing writes this listed target: it gets no declaration and no assignment
			}
			// An integer output takes an integer attachment: its type follows the output's kind, as the MSL emitter's
			// Fs_out does.
			ScalarKind kind = s.type().componentKind();
			String type = t.components() == 1 ? GlslNames.scalarName(kind)
				: GlslNames.prefix(kind) + t.components();
			prelude.append("layout(location = ").append(t.attachment()).append(") out ")
				.append(type).append(" qs_c").append(t.attachment()).append(";\n");
		}
	}

	/** A fragment input the compatibility names read: an in varying when the vertex stage writes it, else zero. */
	private void fragmentVarying(VariableSymbol v, String key) {
		GlslType written = vertexOutputs.get(key);
		if (written == null) {
			privateBuiltin(v);
			return;
		}
		if (!written.equals(v.type())) {
			errors.add("fragment: '" + key + "' is " + written + " in the vertex stage and " + v.type()
				+ " in the fragment stage");
			return;
		}
		prelude.append("layout(location = ").append(locations.get(key)).append(") ")
			.append(GlslNames.isInteger(v.type()) ? "flat " : "").append("in ")
			.append(GlslNames.declare(v.type(), "qs_" + v.name())).append(";\n");
	}

	/** A global the pack reads or writes and no interface carries: declared here, zero at the entry. */
	private void privateBuiltin(VariableSymbol v) {
		if (quadMember(v.name()) || engineMember(v)) {
			return; // quadMatrices declares and binds it, or an engine block's member carries it
		}
		String qs = "qs_" + v.name();
		if (v.name().equals("gl_Fog")) {
			// The fixed-function fog record (GLSL 4.50 has no gl_FogParameters): its fields come from the frame block.
			prelude.append("struct qs_FogParameters {\n\tvec4 color;\n\tfloat density;\n\tfloat start;\n\tfloat end;\n"
				+ "\tfloat scale;\n};\n");
			prelude.append("qs_FogParameters ").append(qs).append(";\n");
			setup.append("\t").append(qs).append(" = qs_FogParameters(vec4(qs_fogColor, 1.0), qs_fogDensity,"
				+ " qs_fogStart, qs_fogEnd, 1.0 / (qs_fogEnd - qs_fogStart));\n");
			return;
		}
		prelude.append(GlslNames.declare(v.type(), qs)).append(";\n");
		setup.append('\t').append(qs).append(" = ").append(zeroText(v.type())).append(";\n");
	}

	/** The fragment outputs by location (FragmentOutputs' rule, as the MSL emitter numbers them). */
	private void collectSources() {
		List<VariableSymbol> outs = new ArrayList<>();
		for (VariableSymbol v : sema.globals()) {
			if (v.declaration() instanceof InterfaceBlock || !fragmentOutput(v) || v.builtin()) {
				continue;
			}
			outs.add(v);
		}
		FragmentOutputs.Assignment assigned = FragmentOutputs.assign(outs, request.outputs().size());
		for (FragmentOutputs.Problem p : assigned.problems()) {
			(p.error() ? errors : warnings).add(stageName() + ": " + p.code() + ": " + p.message());
		}
		for (Map.Entry<Integer, FragmentOutputs.Slot> e : assigned.byTarget().entrySet()) {
			FragmentOutputs.Slot s = e.getValue();
			String expr = declared(s.var()) + (s.element() < 0 ? "" : "[" + s.element() + "]");
			sources.put(e.getKey(), new Source(expr, s.type()));
		}
		VariableSymbol color = sema.usedBuiltins().get("gl_FragColor");
		if (color != null) {
			colorBroadcast = new Source("qs_gl_FragColor", color.type());
		}
		VariableSymbol data = sema.usedBuiltins().get("gl_FragData");
		if (data != null) {
			TreeSet<Integer> indices = FragmentOutputs.fragDataIndices(unit);
			ArrayType at = (ArrayType) data.type();
			int count = at.size() == null ? 8 : at.size();
			if (indices == null) {
				indices = new TreeSet<>();
				for (int i = 0; i < count; i++) {
					indices.add(i);
				}
			}
			for (int i : indices) {
				sources.put(i, new Source("qs_gl_FragData[" + i + "]", at.element()));
			}
		}
	}

	/** The source of colour target k: gl_FragColor for every target (GLSL 4.60 section 7.2), else the output at k. */
	private Source sourceFor(int k) {
		return colorBroadcast != null ? colorBroadcast : sources.get(k);
	}

	private void engineBlock(UniformBlock ub) {
		prelude.append("layout(std140, set = 0, binding = ").append(BLOCK_BASE + ub.slot()).append(") uniform ")
			.append(ub.name()).append(" {\n");
		for (UniformField f : ub.fields()) {
			if (quadMember(f.name())) {
				continue;
			}
			prelude.append("\tlayout(offset = ").append(f.offset()).append(") ").append(f.type()).append(' ')
				.append("qs_").append(f.name()).append(";\n");
		}
		prelude.append("};\n");
		bindings.add(new ResourceBinding(ResourceBinding.Kind.BLOCK, BLOCK_BASE + ub.slot(), ub.slot(), -1, null,
			ub.name()));
	}

	// ----- the pack's declarations ------------------------------------------------------------------------------------

	private void emit(Decl d) {
		switch (d) {
			case FunctionDefinition f -> emitFunction(f);
			case VarDecl v -> emitVar(v);
			case InterfaceBlock b -> emitBlock(b);
			case FunctionPrototype p -> body.append(GlslPrinter.print(renamedParams(p))).append('\n');
			default -> body.append(GlslPrinter.print(d)).append('\n');
		}
	}

	private void emitFunction(FunctionDefinition f) {
		FunctionPrototype p = f.proto();
		boolean main = p.name().equals("main");
		hasMain |= main;
		FunctionPrototype proto = renamedParams(main ? new FunctionPrototype(p.pos(), p.returnType(), "qs_main",
			p.namePos(), p.params()) : p);
		BlockStmt block = (BlockStmt) Trees.transform(
			Trees.transformTopDown(Trees.transform(f.body(), this::zeroLocal), this::lowerScalarSwizzle), this::rename);
		body.append(GlslPrinter.print(new FunctionDefinition(f.pos(), proto, block))).append('\n');
	}

	/** Lowers driver-lenient scalar swizzles ({@code f.x}, {@code f.xxx}) into valid GLSL 4.50 expressions. */
	private GlslNode lowerScalarSwizzle(GlslNode n) {
		if (n instanceof FieldExpr f && sema.typeOf(f.base()) instanceof ScalarType s) {
			Expr base = (Expr) lowerScalarSwizzle(f.base());
			if (f.name().length() == 1) {
				return base;
			}
			String vecType = VectorType.of(s.kind(), f.name().length()).toString();
			return new CallExpr(f.pos(), new TypeCallee(f.pos(), new BuiltinTypeSpec(f.pos(), vecType), List.of()),
				List.of(base));
		}
		return n;
	}

	/**
	 * Locals start at zero: the GLSL value of an uninitialized local is undefined, the MSL emitter's is zero. A local takes
	 * its printed name here, because its declarator is rebuilt and the rename pass cannot reach it.
	 */
	private GlslNode zeroLocal(GlslNode n) {
		if (n instanceof Declarator d) {
			VariableSymbol v = sema.variableOf(d);
			if (v != null && !sema.isGlobal(v)) {
				Expr init = d.initializer();
				if (init == null) {
					init = GlslNames.zero(v.type());
				}
				return new Declarator(d.pos(), declared(v), d.dims(), init);
			}
		}
		return n;
	}

	/** The rename pass: built-ins and vertex inputs to their GLSL 4.50 names; legacy texture functions to theirs. */
	private GlslNode rename(GlslNode n) {
		if (n instanceof Ident id) {
			Symbol s = sema.symbolOf(id);
			if (s instanceof VariableSymbol v) {
				String r = renamed(v);
				if (r != null && !r.equals(id.name())) {
					return new Ident(id.pos(), r);
				}
			}
			return n;
		}
		if (n instanceof CallExpr c && c.callee() instanceof NameCallee nc) {
			String legacy = GlslNames.legacyCall(nc.name());
			if (legacy != null) {
				CallExpr call = new CallExpr(c.pos(), new NameCallee(nc.pos(), legacy), c.args());
				// GLSL 4.50 shadow lookups return float; the legacy ones return vec4, so a .z or .r on them must still
				// select. The MSL translator's float4 is the same value.
				return nc.name().startsWith("shadow") ? new CallExpr(c.pos(),
					new TypeCallee(c.pos(), new BuiltinTypeSpec(c.pos(), "vec4"), List.of()), List.<Expr>of(call)) : call;
			}
		}
		return n;
	}

	/**
	 * The printed name of a variable: a built-in under its GLSL 4.50 name, a vertex input or an engine block member under
	 * qs_, and every other user variable under u_, so no user name meets a Metal or C++ keyword or library name. Null when
	 * the variable keeps its own name (a member of a pack interface block).
	 */
	private String renamed(VariableSymbol v) {
		if (v.builtin()) {
			return GlslNames.builtinName(v.name());
		}
		if (v.declaration() instanceof InterfaceBlock) {
			return null;
		}
		if ((vertexInput(v) && sema.isGlobal(v)) || engineMember(v)) {
			return "qs_" + v.name();
		}
		return "u_" + v.name();
	}

	/** An engine block member: a uniform the frame or draw block serves, other than a sampler or an image. */
	private boolean engineMember(VariableSymbol v) {
		return v.quals().isUniform() && served.contains(v.name()) && !(v.type() instanceof SamplerType)
			&& !(v.type() instanceof ImageType);
	}

	/** The printed name of a declared variable: its renamed name, or its own name for a block member. */
	private String declared(VariableSymbol v) {
		String r = renamed(v);
		return r == null ? v.name() : r;
	}

	/** A prototype whose parameters print as the definition's do (user identifiers, see {@link #renamed}). */
	private static FunctionPrototype renamedParams(FunctionPrototype p) {
		List<Param> params = new ArrayList<>();
		for (Param q : p.params()) {
			String name = q.name() == null || q.name().isEmpty() ? q.name() : "u_" + q.name();
			params.add(new Param(q.pos(), q.type(), name, q.namePos(), q.dims()));
		}
		return new FunctionPrototype(p.pos(), p.returnType(), p.name(), p.namePos(), params);
	}

	/** A vertex input: an in or attribute declaration. A legacy varying is an output of the vertex stage. */
	private boolean vertexInput(VariableSymbol s) {
		return stage == Stage.VERTEX && s.quals().isInput() && !s.quals().storage().contains(StorageKind.VARYING);
	}

	/** A fragment input: an in or varying declaration (a legacy varying is an input of the fragment stage). */
	private boolean fragmentInput(VariableSymbol s) {
		return stage == Stage.FRAGMENT && s.quals().isInput() && !s.quals().storage().contains(StorageKind.OUT);
	}

	/** A fragment output: an out declaration of the fragment stage. */
	private boolean fragmentOutput(VariableSymbol s) {
		return stage == Stage.FRAGMENT && s.quals().storage().contains(StorageKind.OUT);
	}

	private Place place(VariableSymbol s) {
		if (vertexInput(s)) {
			return Place.DROP;
		}
		if (s.quals().isUniform()) {
			if (s.type() instanceof SamplerType || s.type() instanceof ImageType) {
				return Place.RESOURCE;
			}
			return served.contains(s.name()) ? Place.DROP : Place.PLAIN;
		}
		if (s.quals().isShared()) {
			return Place.SHARED;
		}
		if (s.quals().isConst()) {
			return Place.CONST;
		}
		if (stage == Stage.VERTEX && s.quals().isOutput()) {
			return Place.OUT;
		}
		if (fragmentInput(s)) {
			return vertexOutputs.containsKey(s.name()) ? Place.IN : Place.PLAIN;
		}
		return Place.PLAIN;
	}

	private void emitVar(VarDecl v) {
		// One declaration per name: a location, a storage and an initializer belong to one name, and a line that declares
		// several names (out vec3 upVec, sunVec) would otherwise give them all the first name's location.
		if (v.declarators().size() > 1) {
			for (Declarator d : v.declarators()) {
				emitVar(new VarDecl(v.pos(), v.type(), List.of(d)));
			}
			return;
		}
		List<Declarator> kept = new ArrayList<>();
		Place place = null;
		for (Declarator d : v.declarators()) {
			VariableSymbol s = sema.variableOf(d);
			if (s == null) {
				errors.add(stageName() + ": '" + d.name() + "' has no symbol");
				return;
			}
			Place p = place(s);
			if (place != null && p != place) {
				errors.add(stageName() + ": '" + d.name() + "' is declared with another storage than its declaration");
				return;
			}
			place = p;
		}
		if (place == null || place == Place.DROP) {
			return;
		}
		for (Declarator d : v.declarators()) {
			VariableSymbol s = sema.variableOf(d);
			Expr init = d.initializer();
			switch (place) {
				case PLAIN -> {
					kept.add(new Declarator(d.pos(), declared(s), d.dims(), null));
					setup.append('\t').append(declared(s)).append(" = ")
						.append(init == null ? zeroText(s.type()) : initText(init, s.type())).append(";\n");
				}
				case CONST -> kept.add(new Declarator(d.pos(), declared(s), d.dims(), d.initializer()));
				case RESOURCE -> {
					kept.add(new Declarator(d.pos(), declared(s), d.dims(), null));
					resource(d.name(), s.type());
				}
				default -> kept.add(new Declarator(d.pos(), declared(s), d.dims(), null));
			}
		}
		if (kept.isEmpty()) {
			return;
		}
		List<Qualifier> quals = new ArrayList<>();
		for (Qualifier q : v.type().qualifiers()) {
			if (q instanceof StorageQual sq && REWRITTEN.contains(sq.kind())) {
				continue;
			}
			if (q instanceof LayoutQual lq) {
				List<LayoutItem> keep = lq.items().stream().filter(i -> !NUMBERED.contains(i.name())).toList();
				if (!keep.isEmpty()) {
					quals.add(new LayoutQual(lq.pos(), keep));
				}
				continue;
			}
			quals.add(q);
		}
		VariableSymbol first = sema.variableOf(v.declarators().get(0));
		switch (place) {
			case IN, OUT -> {
				Integer location = locations.get(first.name());
				if (location == null) {
					errors.add(stageName() + ": no location for '" + first.name() + "'");
					return;
				}
				if (place == Place.IN && vertexOutputs.get(first.name()) != null
						&& !vertexOutputs.get(first.name()).equals(first.type())) {
					errors.add("fragment: '" + first.name() + "' has another type in the vertex stage");
					return;
				}
				quals.add(0, new LayoutQual(AT, List.of(new LayoutItem(AT, "location", GlslNames.intLiteral(location)))));
				if (GlslNames.isInteger(first.type()) && quals.stream().noneMatch(q -> q instanceof InterpQual)) {
					quals.add(new InterpQual(AT, InterpKind.FLAT));
				}
				quals.add(new StorageQual(AT, place == Place.IN ? StorageKind.IN : StorageKind.OUT));
			}
			case RESOURCE -> {
				quals.add(0, new LayoutQual(AT, List.of(new LayoutItem(AT, "set", GlslNames.intLiteral(0)),
					new LayoutItem(AT, "binding", GlslNames.intLiteral(resourceSlot(first.name()))))));
				quals.add(new StorageQual(AT, StorageKind.UNIFORM));
			}
			default -> {
			}
		}
		Type type = new Type(v.type().pos(), quals, v.type().spec(), sizedDims(v));
		body.append(GlslPrinter.print((GlslNode) Trees.transform(
				Trees.transformTopDown(new VarDecl(v.pos(), type, kept), this::lowerScalarSwizzle), this::rename)))
			.append('\n');
	}

	/**
	 * The declared array dimensions of a one-name declaration. An unsized array takes the size its sema type fixed (from the
	 * initializer), because the initializer moves into the setup and a global without a size cannot take the assignment.
	 */
	private List<ArrayDim> sizedDims(VarDecl v) {
		List<ArrayDim> dims = v.type().dims();
		VariableSymbol s = v.declarators().size() == 1 ? sema.variableOf(v.declarators().get(0)) : null;
		if (s == null || dims.size() != 1 || dims.get(0).length() != null || !(s.type() instanceof ArrayType at)
				|| at.size() == null) {
			return dims;
		}
		return List.of(new ArrayDim(AT, GlslNames.intLiteral(at.size())));
	}

	private int resourceSlot(String name) {
		for (TextureBinding t : request.textures()) {
			if (t.name().equals(name)) {
				return t.slot();
			}
		}
		for (ImageBinding i : request.images()) {
			if (i.name().equals(name)) {
				return i.slot();
			}
		}
		return -1;
	}

	private void resource(String name, GlslType type) {
		if (type instanceof ImageType) {
			for (ImageBinding i : request.images()) {
				if (i.name().equals(name)) {
					bindings.add(new ResourceBinding(ResourceBinding.Kind.IMAGE, i.slot(), i.slot(), -1, null, name));
					return;
				}
			}
		} else {
			for (TextureBinding t : request.textures()) {
				if (t.name().equals(name)) {
					bindings.add(new ResourceBinding(ResourceBinding.Kind.TEXTURE, t.slot(), t.slot(), t.samplerSlot(),
						t.sampler(), name));
					return;
				}
			}
		}
		errors.add("'" + name + "' has no texture or image binding in the request");
	}

	private void emitBlock(InterfaceBlock b) {
		boolean uniform = hasStorage(b.qualifiers(), StorageKind.UNIFORM);
		boolean buffer = hasStorage(b.qualifiers(), StorageKind.BUFFER);
		if (!uniform && !buffer) {
			if (!b.name().equals("gl_PerVertex")) {
				errors.add(stageName() + ": interface block '" + b.name() + "' is not supported");
			}
			return;
		}
		Integer slot = blockSlots.get(b.name());
		if (slot == null) {
			errors.add(stageName() + ": block '" + b.name() + "' has no slot in the request");
			return;
		}
		String std = buffer ? "std430" : "std140";
		List<Qualifier> quals = new ArrayList<>();
		for (Qualifier q : b.qualifiers()) {
			if (q instanceof LayoutQual lq) {
				for (LayoutItem item : lq.items()) {
					if (item.name().equals("std140") || item.name().equals("std430")) {
						std = item.name();
					}
				}
				continue;
			}
			quals.add(q);
		}
		quals.add(0, new LayoutQual(AT, List.of(new LayoutItem(AT, std, null),
			new LayoutItem(AT, "set", GlslNames.intLiteral(0)),
			new LayoutItem(AT, "binding", GlslNames.intLiteral(BLOCK_BASE + slot)))));
		body.append(GlslPrinter.print(new InterfaceBlock(b.pos(), quals, b.name(), b.namePos(), b.members(),
			b.instance(), b.instancePos(), b.instanceDims()))).append('\n');
		bindings.add(new ResourceBinding(ResourceBinding.Kind.BLOCK, BLOCK_BASE + slot, slot, -1, null, b.name()));
	}

	private static boolean hasStorage(List<Qualifier> qualifiers, StorageKind kind) {
		for (Qualifier q : qualifiers) {
			if (q instanceof StorageQual sq && sq.kind() == kind) {
				return true;
			}
		}
		return false;
	}

	/** An initializer as the entry's assignment: a braced list becomes the type's constructor. */
	private String initText(Expr init, GlslType type) {
		if (init instanceof InitializerList list) {
			List<String> items = new ArrayList<>();
			for (Expr item : list.items()) {
				if (item instanceof InitializerList) {
					errors.add(stageName() + ": nested initializer lists are not supported");
					return zeroText(type);
				}
				items.add(GlslPrinter.print((GlslNode) Trees.transform(
					Trees.transformTopDown(item, this::lowerScalarSwizzle), this::rename)));
			}
			return GlslNames.typeName(type) + "(" + String.join(", ", items) + ")";
		}
		return "(" + GlslPrinter.print((GlslNode) Trees.transform(
			Trees.transformTopDown(init, this::lowerScalarSwizzle), this::rename)) + ")";
	}

	private String zeroText(GlslType type) {
		Expr z = GlslNames.zero(type);
		if (z == null) {
			errors.add(stageName() + ": no zero value for " + type);
			return "0";
		}
		return GlslPrinter.print(z);
	}

	/** The epilogue: clip space (ADR 0006 section 2), point size, then the alpha test and the colour outputs. */
	private String epilogue() {
		StringBuilder e = new StringBuilder();
		if (stage == Stage.VERTEX) {
			e.append("\tgl_Position.z = (gl_Position.z + gl_Position.w) * 0.5;\n");
			e.append("\tgl_Position.y = -gl_Position.y;\n");
			// The pack's gl_PointSize stands when it writes one; GL's default of 1.0 only when it writes none.
			if (request.drawsPoints() && !sema.usedBuiltins().containsKey("gl_PointSize")) {
				e.append("\tgl_PointSize = 1.0;\n");
			}
		} else if (stage == Stage.FRAGMENT) {
			if (request.alphaTest() != null) {
				e.append(alphaTest(sourceFor(0)));
			}
			for (int k = 0; k < request.outputs().size(); k++) {
				ColorTarget t = request.outputs().get(k);
				Source s = sourceFor(k);
				if (s == null) {
					continue;  // an unwritten target has no declaration (fragmentInterface)
				}
				e.append("\tqs_c").append(t.attachment()).append(" = ").append(pad(s, t.components())).append(";\n");
			}
		}
		return e.toString();
	}

	private String alphaTest(Source out0) {
		ProgramRequest.AlphaTest at = request.alphaTest();
		String op = switch (at.func()) {
			case "ALWAYS" -> null;
			case "NEVER" -> "NEVER";
			case "LESS" -> "<";
			case "LEQUAL" -> "<=";
			case "EQUAL" -> "==";
			case "GEQUAL" -> ">=";
			case "GREATER" -> ">";
			case "NOTEQUAL" -> "!=";
			default -> throw new IllegalArgumentException("alpha test function " + at.func());
		};
		if (op == null) {
			return "";
		}
		if (op.equals("NEVER")) {
			return "\tdiscard;\n";
		}
		if (out0 == null) {
			warnings.add(stageName() + ": alpha-test-target: the alpha test reads target 0, which the program does not"
				+ " write; the test is skipped");
			return "";
		}
		if (!(out0.type() instanceof VectorType v) || v.size() != 4 || out0.type().componentKind() != ScalarKind.FLOAT) {
			warnings.add(stageName() + ": alpha-test-target: the alpha test reads target 0, which is not a float vec4;"
				+ " the test is skipped");
			return "";
		}
		String ref = null;
		for (VariableSymbol sym : sema.globals()) {
			if (sym.name().equals(at.refUniform()) && sym.quals().isUniform()) {
				ref = declared(sym);
			}
		}
		if (ref == null) {
			// The engine's schema serves the reference when the pack does not declare it (alphaTestRef).
			ref = servedField(at.refUniform());
		}
		if (ref == null) {
			errors.add("fragment: alpha-reference: the alpha test's reference '" + at.refUniform()
				+ "' is not declared by the pack, and no engine block serves it");
			return "";
		}
		return "\tif (!(" + out0.expr() + ".w " + op + " " + ref + ")) {\n\t\tdiscard;\n\t}\n";
	}

	/** A colour output padded with (0, 0, 0, 1) or truncated to the attachment's components, as the MSL emitter does. */
	private static String pad(Source s, int components) {
		GlslType t = s.type();
		int have = t instanceof VectorType v ? v.size() : 1;
		String base = GlslNames.scalarName(t.componentKind());
		String x = s.expr();
		if (components == 1) {
			return base + "(" + (have == 1 ? x : "(" + x + ").x") + ")";
		}
		String target = GlslNames.prefix(t.componentKind()) + components;
		if (have == components) {
			return target + "(" + x + ")";
		}
		if (have > components) {
			return target + "((" + x + ")." + "xyzw".substring(0, components) + ")";
		}
		StringBuilder sb = new StringBuilder(target).append('(').append(x);
		for (int i = have; i < components; i++) {
			sb.append(i == 3 ? ", 1.0" : ", 0.0");
		}
		return sb.append(')').toString();
	}
}
