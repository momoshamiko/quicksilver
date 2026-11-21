// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.msl;

import dev.mcms.glsl.ast.Decl;
import dev.mcms.glsl.ast.Declarator;
import dev.mcms.glsl.ast.InvariantQual;
import dev.mcms.glsl.ast.Pos;
import dev.mcms.glsl.ast.QualifierDecl;
import dev.mcms.glsl.ast.Unit;
import dev.mcms.glsl.ast.VarDecl;
import dev.mcms.glsl.msl.ProgramRequest.AlphaTest;
import dev.mcms.glsl.msl.ProgramRequest.ColorTarget;
import dev.mcms.glsl.msl.ProgramRequest.SamplerState;
import dev.mcms.glsl.msl.ProgramRequest.TextureBinding;
import dev.mcms.glsl.msl.ProgramRequest.UniformBlock;
import dev.mcms.glsl.msl.ProgramRequest.UniformField;
import dev.mcms.glsl.msl.ProgramRequest.VertexAttribute;
import dev.mcms.glsl.msl.ProgramTranslation.Diagnostic;
import dev.mcms.glsl.msl.ProgramTranslation.Reflection;
import dev.mcms.glsl.msl.builtins.BuiltinTable;
import dev.mcms.glsl.msl.builtins.Prelude;
import dev.mcms.glsl.sema.SemaResult;
import dev.mcms.glsl.sema.SourceLine;
import dev.mcms.glsl.sema.Stage;
import dev.mcms.glsl.sema.VariableSymbol;
import dev.mcms.glsl.types.ArrayType;
import dev.mcms.glsl.types.GlslType;
import dev.mcms.glsl.types.ImageType;
import dev.mcms.glsl.types.MatrixType;
import dev.mcms.glsl.types.SamplerType;
import dev.mcms.glsl.types.ScalarKind;
import dev.mcms.glsl.types.ScalarType;
import dev.mcms.glsl.types.StructField;
import dev.mcms.glsl.types.StructType;
import dev.mcms.glsl.types.Types;
import dev.mcms.glsl.types.VectorType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Assembles one program's MSL library (ADR 0006 section 2): the prelude helpers, the struct definitions (block structs
 * at their exact byte offsets), the equality helpers, the program-scope constants and constexpr samplers, the interface
 * structs, the stages' context structs and entry functions, and the reflection. Errors are collected: a program with
 * any error has no MSL.
 */
public final class ProgramEmitter {

	/** One analyzed stage handed to the emitter. */
	public record StageInput(Stage stage, Unit unit, SemaResult sema, List<SourceLine> lineMap) {
	}

	private final ProgramRequest request;
	private final BuiltinTable table;
	private final List<Diagnostic> diagnostics = new ArrayList<>();
	private final Set<String> prelude = new LinkedHashSet<>();
	private final Map<String, String> structDefs = new LinkedHashMap<>();
	private final Map<String, Long> structSizes = new HashMap<>();
	private final Map<String, String> equalityNames = new HashMap<>();
	private final List<String> equalityDefs = new ArrayList<>();
	private final Map<String, String> constants = new LinkedHashMap<>();
	private final Map<String, String> samplers = new LinkedHashMap<>();
	/**
	 * The constexpr sampler of each pack texture, by texture name: one constexpr per distinct sampler state, named {@code smpc_<k>}
	 * in first-use order (Metal counts every declared inline sampler toward its limit of 16, so textures that share a state
	 * share its sampler).
	 */
	private final Map<String, String> constexprNames = new HashMap<>();
	/** The constexpr sampler of each pack texture's implicit-LOD lookups, where the binding has one (ProgramRequest.TextureBinding). */
	private final Map<String, String> implicitNames = new HashMap<>();
	/** The textures whose implicit-LOD lookups take the engine's LOD bias (ADR 0008; ProgramRequest.TextureBinding.lodBias). */
	private final java.util.Set<String> lodBiased = new java.util.HashSet<>();
	/** Metal's limit of sampler slots per function: the game's sampler slots plus the constexpr samplers the program declares. */
	static final int SAMPLER_LIMIT = 16;
	private int syntheticId = 1_000_000;

	/**
	 * The per-vertex id record the vertex prologue reads (ProgramRequest.PerVertexIds, plan.md E4): three ints, twelve
	 * bytes a vertex, plain members so the array indexes with a four-byte stride.
	 */
	private static final String IDS_STRUCT = """
		struct QsPerVertexIds {
			int qs_entityId;
			int qs_blockEntityId;
			int qs_currentRenderedItemId;
		};
		""";
	private int helperCount;

	public ProgramEmitter(ProgramRequest request, BuiltinTable table) {
		this.request = request;
		this.table = table;
		Map<SamplerState, String> byState = new HashMap<>();
		for (TextureBinding b : request.textures()) {
			if (b.sampler() != null) {
				constexprNames.put(b.name(), byState.computeIfAbsent(b.sampler(), s -> "smpc_" + byState.size()));
			}
			if (b.implicitSampler() != null) {
				implicitNames.put(b.name(), byState.computeIfAbsent(b.implicitSampler(), s -> "smpc_" + byState.size()));
			}
			if (b.lodBias()) {
				lodBiased.add(b.name());
			}
		}
	}

	BuiltinTable table() {
		return table;
	}

	// ----- services for the stage and body emitters -------------------------------------------------------------------

	void requirePrelude(String helper) {
		prelude.add(helper);
	}

	void error(Stage stage, Pos pos, List<SourceLine> lineMap, String code, String message) {
		diagnostics.add(new Diagnostic(Diagnostic.Severity.ERROR, stageName(stage), originOf(lineMap, pos), code,
			message));
	}

	void warning(Stage stage, Pos pos, List<SourceLine> lineMap, String code, String message) {
		diagnostics.add(new Diagnostic(Diagnostic.Severity.WARNING, stageName(stage), originOf(lineMap, pos), code,
			message));
	}

	public static String stageName(Stage s) {
		return switch (s) {
			case VERTEX -> "vertex";
			case FRAGMENT -> "fragment";
			case COMPUTE -> "compute";
		};
	}

	static SourceLine originOf(List<SourceLine> lineMap, Pos pos) {
		if (pos == null || pos.line() < 1 || pos.line() > lineMap.size()) {
			return null;
		}
		return lineMap.get(pos.line() - 1);
	}

	/** True when the program defines this struct as a block (with its byte layout). */
	boolean isBlockStruct(StructType s) {
		return blockNames.contains(MslTypes.structName(s));
	}

	private final Set<String> blockNames = new LinkedHashSet<>();

	/** The atomic variables and block members of the program (see Atomics). */
	private final Atomics atomics = new Atomics();

	Atomics atomics() {
		return atomics;
	}

	/** Registers a pack block (or a struct inside one) with the byte layout its storage rule gives. */
	void registerPackBlock(StructType st, Layouts.Std std) {
		if (blockNames.contains(MslTypes.structName(st))) {
			return;
		}
		defineBlock(st, Layouts.offsets(st.fields(), std), std);
	}

	/** Registers an engine uniform block at the offsets the request gives; returns its struct type. */
	StructType registerEngineBlock(UniformBlock ub) {
		List<StructField> fields = new ArrayList<>();
		long[] offsets = new long[ub.fields().size()];
		for (int i = 0; i < ub.fields().size(); i++) {
			UniformField f = ub.fields().get(i);
			GlslType t = Types.fromWord(f.type());
			if (t == null) {
				throw new IllegalArgumentException("uniform " + f.name() + " has unknown type " + f.type());
			}
			fields.add(new StructField(f.name(), t));
			offsets[i] = f.offset();
		}
		StructType st = new StructType(ub.name(), fields, syntheticId++);
		if (!blockNames.contains(MslTypes.structName(st))) {
			defineBlock(st, offsets, null);
		}
		return st;
	}

	/**
	 * Emits a block struct: each member at its byte offset (padding fills the gaps), a vec3 member packed. A member
	 * whose Metal layout would differ from the GLSL layout (a bool, an array or matrix stride) is an error.
	 */
	private void defineBlock(StructType st, long[] offsets, Layouts.Std std) {
		String name = MslTypes.structName(st);
		blockNames.add(name);
		StringBuilder sb = new StringBuilder("struct " + name + " {\n");
		long cursor = 0;
		int pad = 0;
		for (int i = 0; i < st.fields().size(); i++) {
			StructField f = st.fields().get(i);
			// A struct inside an array (at any depth) is a block struct too: it is defined with the block's layout.
			GlslType element = f.type();
			while (element instanceof ArrayType a) {
				element = a.element();
			}
			if (element instanceof StructType nested) {
				registerPackBlock(nested, std == null ? Layouts.Std.STD140 : std);
			}
			long off = offsets[i];
			if (off < cursor) {
				blockError(name, f.name(), "offset " + off + " lies inside the previous member");
			}
			if (off > cursor) {
				sb.append("\tuchar _pad").append(pad++).append('[').append(off - cursor).append("];\n");
			}
			boolean packed = f.type() instanceof VectorType v && v.size() == 3;
			sb.append('\t').append(memberDecl(f.type(), packed, f.name(), name)).append(";\n");
			// The member's GLSL size: an array of structs takes the block rule's stride, not Metal's natural one.
			cursor = off + (std == null ? Layouts.mslSize(f.type(), packed) : Layouts.size(f.type(), std));
			if (std != null) {
				checkStride(name, f, std);
			}
		}
		if (std != null) {
			// The struct keeps the GLSL size under the block rule (trailing padding), so an array stride matches.
			long size = Layouts.size(st, std);
			if (size > cursor) {
				sb.append("\tuchar _pad").append(pad).append('[').append(size - cursor).append("];\n");
			}
			structSizes.put(name, size);
		}
		structDefs.put(name, sb.append("};\n").toString());
	}

	private String memberDecl(GlslType t, boolean packed, String field, String block) {
		String member = "u_" + field;
		if (atomics.isField(block, field)) {
			return t instanceof ArrayType at && at.size() == null
				? Atomics.valueOf(at.element()) + " " + member + "[1]" : Atomics.valueOf(t) + " " + member;
		}
		return switch (t) {
			case ArrayType at when at.size() == null -> MslTypes.value(at.element()) + " " + member + "[1]";
			case VectorType v when packed -> MslTypes.packedVector(v) + " " + member;
			// A bool member takes a 4-byte int, as std140 and std430 give it four bytes; C++ converts it where a bool is read.
			case ScalarType s when s.kind() == ScalarKind.BOOL -> "int " + member;
			default -> MslTypes.value(t) + " " + member;
		};
	}

	/** An array or matrix whose GLSL stride differs from Metal's cannot be mirrored (a float array under std140). */
	private void checkStride(String block, StructField f, Layouts.Std std) {
		if (f.type() instanceof ArrayType at && at.size() != null) {
			long glsl = Layouts.arrayStride(at.element(), std);
			long metal = at.element() instanceof StructType st
				? structSizes.getOrDefault(MslTypes.structName(st), -1L)
				: Layouts.roundUp(Layouts.mslSize(at.element(), false), Layouts.mslAlign(at.element()));
			if (glsl != metal) {
				blockError(block, f.name(), "array stride " + glsl + " under " + std + ", Metal's stride " + metal);
			}
		}
		if (f.type() instanceof MatrixType m && std == Layouts.Std.STD140 && m.rows() == 2) {
			blockError(block, f.name(), "a std140 mat" + m.cols() + "x2 has 16-byte columns; Metal's are 8");
		}
	}

	private void blockError(String block, String field, String why) {
		diagnostics.add(new Diagnostic(Diagnostic.Severity.ERROR, "block", null, "block-layout",
			block + "." + field + ": " + why));
	}

	/** The equality of two values of one type, as a boolean MSL expression (GLSL 4.60 section 5.9). */
	String equality(GlslType t, String a, String b) {
		return switch (t) {
			case ScalarType s -> "(" + a + " == " + b + ")";
			case VectorType v -> "all(" + a + " == " + b + ")";
			case MatrixType m -> {
				List<String> cols = new ArrayList<>();
				for (int c = 0; c < m.cols(); c++) {
					cols.add("all(" + a + "[" + c + "] == " + b + "[" + c + "])");
				}
				yield "(" + String.join(" && ", cols) + ")";
			}
			case StructType s -> equalityHelper(s) + "(" + a + ", " + b + ")";
			case ArrayType at -> equalityHelper(at) + "(" + a + ", " + b + ")";
			default -> throw new IllegalArgumentException("no equality for " + t);
		};
	}

	private String equalityHelper(GlslType t) {
		String key = MslTypes.value(t);
		String known = equalityNames.get(key);
		if (known != null) {
			return known;
		}
		String name = "qs_eq" + helperCount++;
		equalityNames.put(key, name);
		String body;
		if (t instanceof StructType s) {
			List<String> conds = new ArrayList<>();
			for (StructField f : s.fields()) {
				conds.add(equality(f.type(), "a.u_" + f.name(), "b.u_" + f.name()));
			}
			body = "\treturn " + (conds.isEmpty() ? "true" : String.join(" && ", conds)) + ";\n";
		} else {
			ArrayType at = (ArrayType) t;
			body = "\tfor (int i = 0; i < " + at.size() + "; i++) {\n\t\tif (!" + equality(at.element(), "a[i]", "b[i]")
				+ ") return false;\n\t}\n\treturn true;\n";
		}
		equalityDefs.add("static bool " + name + "(" + key + " a, " + key + " b) {\n" + body + "}\n");
		return name;
	}

	/**
	 * Defines the struct a type names (through arrays) and, first, the structs its fields name. A block struct is
	 * already defined with its layout and is skipped.
	 */
	private void defineStruct(GlslType t) {
		GlslType base = t;
		while (base instanceof ArrayType at) {
			base = at.element();
		}
		if (!(base instanceof StructType st) || structDefs.containsKey(MslTypes.structName(st))) {
			return;
		}
		for (StructField f : st.fields()) {
			defineStruct(f.type());
		}
		StringBuilder sb = new StringBuilder("struct " + MslTypes.structName(st) + " {\n");
		for (StructField f : st.fields()) {
			if (f.type() instanceof ArrayType at && at.size() == null) {
				diagnostics.add(new Diagnostic(Diagnostic.Severity.ERROR, "program", null, "unsized-member",
					"unsized array '" + f.name() + "' outside a buffer block"));
				continue;
			}
			sb.append('\t').append(MslTypes.value(f.type())).append(" u_").append(f.name()).append(";\n");
		}
		structDefs.put(MslTypes.structName(st), sb.append("};\n").toString());
	}

	// ----- assembly ---------------------------------------------------------------------------------------------------

	/** Assembles the program. {@code inputs} holds one entry per stage the request names. */
	public ProgramTranslation translate(String name, List<StageInput> inputs) {
		long start = System.nanoTime();
		for (StageInput in : inputs) {
			atomics.scan(in.unit(), in.sema());
		}
		Map<Stage, StageEmitter> emitters = new LinkedHashMap<>();
		Map<Stage, Boolean> invariant = new LinkedHashMap<>();
		List<VertexAttribute> layout = request.vertexLayout() == null ? List.of()
			: request.vertexLayout().attributes();
		for (StageInput in : inputs) {
			StageEmitter se = new StageEmitter(in.stage(), in.sema(), in.unit(), this, table, in.lineMap(), request);
			se.build(layout);
			emitters.put(in.stage(), se);
			invariant.put(in.stage(), hasInvariantPosition(in.unit()));
		}
		StageEmitter vs = emitters.get(Stage.VERTEX);
		StageEmitter fs = emitters.get(Stage.FRAGMENT);
		StageEmitter cs = emitters.get(Stage.COMPUTE);
		if (cs != null && (vs != null || fs != null)) {
			error(Stage.COMPUTE, null, List.of(), "stages", "a compute program has one compute stage and no others");
		}
		if (cs == null && (vs == null || fs == null)) {
			error(Stage.VERTEX, null, List.of(), "stages", "a graphics program needs a vertex and a fragment stage");
		}
		if (vs != null && request.vertexLayout() == null) {
			error(Stage.VERTEX, null, List.of(), "layout", "a vertex stage needs a vertex input layout");
		}
		if (request.perVertexIds() != null && (vs == null || request.vertexLayout() == null
				|| request.vertexLayout().fullScreen())) {
			error(Stage.VERTEX, null, List.of(), "per-vertex-ids",
				"the per-vertex id input needs a vertex stage with an attribute layout (not a full-screen or compute"
					+ " program)");
		}
		if (request.perVertexIds() != null && vs != null && !failed()) {
			for (String idName : StageEmitter.PER_VERTEX_ID_UNIFORMS) {
				GlslType vertexType = vs.perVertexIdType(idName);
				GlslType fragmentType = fs == null ? null : fs.perVertexIdType(idName);
				if (vertexType == null && fragmentType == null) {
					continue;
				}
				vs.perVertexId(idName, vertexType != null ? vertexType : fragmentType, fragmentType != null);
				if (fragmentType != null) {
					fs.perVertexIdIn(idName, fragmentType);
				}
			}
			if (vs.servesPerVertexIds()) {
				structDefs.putIfAbsent("QsPerVertexIds", IDS_STRUCT);
			}
		}
		if (request.overlayColor() != null && (vs == null || request.vertexLayout() == null
				|| request.vertexLayout().fullScreen())) {
			error(Stage.VERTEX, null, List.of(), "overlay-color",
				"the entity overlay input needs a vertex stage with an attribute layout (not a full-screen or compute"
					+ " program)");
		}
		if (request.cloudsPrologue() != null && (vs == null || request.vertexLayout() == null
				|| !request.vertexLayout().attributes().isEmpty() || request.vertexLayout().fullScreen()
				|| request.vertexLayout().prologue() != null)) {
			error(Stage.VERTEX, null, List.of(), "clouds-prologue",
				"the clouds prologue needs a vertex stage whose layout has no attributes, is not full-screen and"
					+ " carries no region prologue (the clouds draw binds no vertex format)");
		}
		if (request.overlayColor() != null && vs != null && !failed()) {
			GlslType vertexType = vs.overlayColorType();
			GlslType fragmentType = fs == null ? null : fs.overlayColorType();
			if (vertexType != null || fragmentType != null) {
				vs.overlayColor(vertexType != null ? vertexType : fragmentType, fragmentType != null);
				if (fragmentType != null) {
					fs.overlayColorIn(fragmentType);
				}
			}
		}
		if (failed()) {
			return fail(name, start);
		}
		Map<String, List<StageEmitter.Resource>> resources = new LinkedHashMap<>();
		List<String> missing = new ArrayList<>();
		for (Map.Entry<Stage, StageEmitter> e : emitters.entrySet()) {
			resources.put(stageName(e.getKey()), e.getValue().resources(request.textures(), request.images(), missing));
		}
		for (String m : missing) {
			diagnostics.add(new Diagnostic(Diagnostic.Severity.ERROR, "program", null, "resource-unbound",
				"'" + m + "' has no texture or image binding in the request"));
		}
		for (Map.Entry<Stage, StageEmitter> e : emitters.entrySet()) {
			registerProgramScope(e.getValue());
			for (StageEmitter.Resource r : resources.get(stageName(e.getKey()))) {
				if (r.kind().equals("texture")) {
					for (TextureBinding b : request.textures()) {
						if (b.name().equals(r.name()) && b.sampler() != null) {
							addSampler(b);
							if (b.implicitSampler() != null) {
								addSampler(b.name(), b.implicitSampler(), implicitNames.get(b.name()));
							}
						}
					}
				}
			}
		}
		checkSamplerLimit();
		if (failed()) {
			return fail(name, start);
		}
		// Varyings: the vertex outputs name the locations; a fragment input the vertex never writes reads zero.
		List<StageEmitter.Varying> outs = vs == null ? List.of() : vs.outVaryings();
		Map<String, Integer> location = new TreeMap<>();
		for (StageEmitter.Varying v : outs) {
			location.put(v.key(), 0);
		}
		int next = 0;
		for (String key : new ArrayList<>(location.keySet())) {
			location.put(key, next++);
		}
		List<StageEmitter.Varying> matched = new ArrayList<>();
		if (fs != null) {
			for (StageEmitter.Varying v : fs.inVaryings()) {
				StageEmitter.Varying writer = null;
				for (StageEmitter.Varying o : outs) {
					if (o.key().equals(v.key())) {
						writer = o;
					}
				}
				if (writer == null) {
					continue;
				}
				if (!MslTypes.value(writer.type()).equals(MslTypes.value(v.type()))) {
					error(Stage.FRAGMENT, null, List.of(), "varying-type", "'" + v.key() + "' is " + writer.type()
						+ " in the vertex stage and " + v.type() + " in the fragment stage");
					continue;
				}
				matched.add(v);
			}
		}
		if (failed()) {
			return fail(name, start);
		}
		Set<String> matchedKeys = new LinkedHashSet<>();
		for (StageEmitter.Varying v : matched) {
			matchedKeys.add(v.key());
		}
		String vinText = vs == null ? null : vinStruct();
		String voutText = vs == null ? null : voutStruct(outs, location, invariant.get(Stage.VERTEX));
		String finText = fs == null ? null : finStruct(matched, outs, location);
		boolean depth = fs != null && fs.sema().usedBuiltins().containsKey("gl_FragDepth");
		String foutText = fs == null ? null : foutStruct(depth, fs);
		StringBuilder contexts = new StringBuilder();
		StringBuilder entries = new StringBuilder();
		for (Map.Entry<Stage, StageEmitter> e : emitters.entrySet()) {
			StageEmitter se = e.getValue();
			List<StageEmitter.Resource> res = resources.get(stageName(e.getKey()));
			String resultType;
			String result;
			switch (e.getKey()) {
				case VERTEX -> {
					resultType = "Vs_out";
					result = vertexResult(outs, se, location, se.sema().usedBuiltins().containsKey("gl_PointSize"));
				}
				case FRAGMENT -> {
					resultType = "Fs_out";
					result = fragmentResult(fs, depth);
				}
				default -> {
					resultType = "void";
					result = "";
				}
			}
			contexts.append(se.contextStruct(result, resultType, res)).append('\n');
			entries.append(se.entryFunction(resultType, res, matchedKeys, request.outputs())).append('\n');
		}
		for (StageInput in : inputs) {
			in.sema().structs().forEach(this::defineStruct);
			in.sema().globals().forEach(v -> defineStruct(v.type()));
			in.sema().usedBuiltins().values().forEach(v -> defineStruct(v.type()));
		}
		StringBuilder source = new StringBuilder();
		source.append("#include <metal_stdlib>\nusing namespace metal;\n");
		source.append(Prelude.render(prelude));
		structDefs.values().forEach(source::append);
		equalityDefs.forEach(d -> source.append(d).append('\n'));
		constants.values().forEach(c -> source.append(c).append('\n'));
		samplers.values().forEach(s -> source.append(s).append('\n'));
		for (String s : new String[] {vinText, voutText, finText, foutText}) {
			if (s != null) {
				source.append(s).append('\n');
			}
		}
		source.append(contexts).append(entries);
		if (failed()) {
			return fail(name, start);
		}
		// E9: a full-screen pass whose vertex stage covers the viewport writes every texel (its targets need no load).
		boolean covers = request.family() == ProgramRequest.Family.FULL_SCREEN
			&& inputs.stream().anyMatch(in -> in.stage() == Stage.VERTEX && ViewportCoverage.covers(in.unit()));
		Reflection reflection = reflect(emitters, resources, depth, covers);
		return new ProgramTranslation(name, true, source.toString(), vs == null ? null : "qs_vs",
			fs == null ? null : "qs_fs", cs == null ? null : "qs_cs", reflection, diagnostics,
			System.nanoTime() - start);
	}

	/**
	 * The program-scope constant globals of a stage (a const, or a plain global no statement writes, with a folded
	 * initializer: StageEmitter.readOnlyConstant), each under its stage's name.
	 */
	private void registerProgramScope(StageEmitter se) {
		for (VariableSymbol v : se.sema().globals()) {
			if (!(v.quals().isConst() || se.readOnlyConstant(v)) || !(v.declaration() instanceof Declarator d)
					|| d.initializer() == null) {
				continue;
			}
			var folded = se.sema().constOf(d.initializer());
			if (folded == null) {
				continue;
			}
			String name = se.programConstName(v);
			// The folded value has the initializer's type; GLSL converts it implicitly (ivec2 to vec2), MSL does not convert
			// vectors implicitly, so the value is spelled in the declared type.
			constants.put(name, "constant " + MslTypes.value(v.type()) + " " + name + " = "
				+ BodyEmitter.constText(folded.implicitTo(v.type())) + ";");
		}
	}

	private static boolean hasInvariantPosition(Unit unit) {
		for (Decl d : unit.decls()) {
			if (d instanceof QualifierDecl q && q.qualifiers().stream().anyMatch(x -> x instanceof InvariantQual)
					&& q.names().contains("gl_Position")) {
				return true;
			}
		}
		return false;
	}

	/** The constexpr sampler name a pack texture's binding reads (one per distinct state), or null for a game sampler. */
	String constexprSampler(String textureName) {
		return constexprNames.get(textureName);
	}

	/** The constexpr sampler a pack texture's implicit-LOD lookups read, or null when they take {@link #constexprSampler}. */
	String implicitSampler(String textureName) {
		return implicitNames.get(textureName);
	}

	/** Whether a texture's implicit-LOD lookups take the engine's LOD bias (ADR 0008). */
	boolean lodBiased(String textureName) {
		return lodBiased.contains(textureName);
	}

	/**
	 * A sampler for a pack texture: the constexpr sampler of its state (one declaration per distinct state, shared by the
	 * textures that have it). A pixel sampler (a rectangle texture) takes texel coordinates, which Metal allows only without
	 * mipmaps and with clamp-to-edge addressing.
	 */
	private void addSampler(TextureBinding binding) {
		addSampler(binding.name(), binding.sampler(), constexprNames.get(binding.name()));
	}

	/** Declares the constexpr sampler {@code name} of a texture's state (the implicit-LOD state too; see addSampler). */
	private void addSampler(String texture, SamplerState s, String name) {
		if (s.pixel() && (s.mip() != null || s.wrap() != ProgramRequest.Wrap.CLAMP_TO_EDGE)) {
			diagnostics.add(new Diagnostic(Diagnostic.Severity.ERROR, "program", null, "pixel-sampler",
				"'" + texture + "': a pixel-coordinate sampler has no mipmaps and clamp-to-edge addressing only"));
			return;
		}
		samplers.putIfAbsent(name, "constexpr sampler " + name + "(" + samplerArgs(s) + ");");
	}

	/**
	 * Refuses a program whose sampler states and game sampler slots need more than {@link #SAMPLER_LIMIT} sampler slots: the
	 * constexpr samplers declared (one per distinct state the stages use) plus the distinct game sampler slots the request
	 * names. The count is program-wide, a conservative bound on each stage.
	 */
	private void checkSamplerLimit() {
		Set<Integer> gameSlots = new java.util.TreeSet<>();
		for (TextureBinding b : request.textures()) {
			if (b.sampler() == null && b.kind() != ProgramRequest.TextureKind.BUFFER && b.samplerSlot() >= 0) {
				gameSlots.add(b.samplerSlot());
			}
		}
		int needed = samplers.size() + gameSlots.size();
		if (needed > SAMPLER_LIMIT) {
			diagnostics.add(new Diagnostic(Diagnostic.Severity.ERROR, "program", null, "sampler-limit",
				"the program needs " + needed + " sampler slots (" + samplers.size() + " sampler states and " + gameSlots.size()
					+ " game samplers); Metal allows " + SAMPLER_LIMIT));
		}
	}

	/** The bufferObject index a block binds (the request's BufferBinding), or -1 when the request names no such block. */
	private int bufferObjectOf(String block) {
		for (ProgramRequest.BufferBinding b : request.buffers()) {
			if (b.name().equals(block)) {
				return b.bufferObject();
			}
		}
		return -1;
	}

	private static String samplerArgs(SamplerState s) {
		StringBuilder sb = new StringBuilder();
		if (s.mip() == null) {
			sb.append("filter::").append(word(s.filter()));
		} else {
			sb.append("min_filter::").append(word(s.filter())).append(", mag_filter::").append(word(s.filter()))
				.append(", mip_filter::").append(word(s.mip()));
		}
		sb.append(", address::").append(switch (s.wrap()) {
			case REPEAT -> "repeat";
			case CLAMP_TO_EDGE -> "clamp_to_edge";
			case MIRROR_REPEAT -> "mirrored_repeat";
		});
		if (s.compare()) {
			sb.append(", compare_func::less_equal");
		}
		if (s.pixel()) {
			sb.append(", coord::pixel");
		}
		return sb.toString();
	}

	private static String word(ProgramRequest.Filter f) {
		return f == ProgramRequest.Filter.NEAREST ? "nearest" : "linear";
	}

	private ProgramTranslation fail(String name, long start) {
		return new ProgramTranslation(name, false, null, null, null, null, null, diagnostics,
			System.nanoTime() - start);
	}

	boolean failed() {
		return diagnostics.stream().anyMatch(d -> d.severity() == Diagnostic.Severity.ERROR);
	}

	// ----- interface structs and result bodies ------------------------------------------------------------------------

	/** The vertex stage_in: one member per layout attribute, at its attribute index (Metal reads attributes from it). */
	private String vinStruct() {
		StringBuilder sb = new StringBuilder("struct Vs_in {\n");
		List<VertexAttribute> attrs = request.vertexLayout().attributes();
		for (int i = 0; i < attrs.size(); i++) {
			sb.append('\t').append(StageEmitter.attributeArgType(attrs.get(i).format())).append(" qs_a").append(i)
				.append(" [[attribute(").append(i).append(")]];\n");
		}
		return sb.append("};\n").toString();
	}

	/** The vertex output: clip position, point size when the pipeline draws points, and each varying. */
	private String voutStruct(List<StageEmitter.Varying> outs, Map<String, Integer> location, boolean invariant) {
		StringBuilder sb = new StringBuilder("struct Vs_out {\n");
		sb.append("\tfloat4 gl_Position [[position").append(invariant ? ", invariant" : "").append("]];\n");
		if (request.drawsPoints()) {
			sb.append("\tfloat gl_PointSize [[point_size]];\n");
		}
		for (StageEmitter.Varying v : outs) {
			sb.append('\t').append(MslTypes.value(v.type())).append(' ').append(v.field()).append(" [[user(locn")
				.append(location.get(v.key())).append(")]]").append(interpAttr(v.interp(), v.type())).append(";\n");
		}
		return sb.append("};\n").toString();
	}

	/** The fragment input: the varyings both stages share; the fragment's interpolation wins over the vertex's. */
	private String finStruct(List<StageEmitter.Varying> matched, List<StageEmitter.Varying> outs,
			Map<String, Integer> location) {
		StringBuilder sb = new StringBuilder("struct Fs_in {\n");
		for (StageEmitter.Varying v : matched) {
			String interp = v.interp();
			for (StageEmitter.Varying o : outs) {
				if (o.key().equals(v.key()) && interp == null) {
					interp = o.interp();
				}
			}
			sb.append('\t').append(MslTypes.value(v.type())).append(' ').append(v.field()).append(" [[user(locn")
				.append(location.get(v.key())).append(")]]").append(interpAttr(interp, v.type())).append(";\n");
		}
		return sb.append("};\n").toString();
	}

	/** Integer varyings are flat (Metal does not interpolate integers); floats follow the declared interpolation. */
	private static String interpAttr(String interp, GlslType type) {
		if (type.componentKind() == ScalarKind.INT || type.componentKind() == ScalarKind.UINT) {
			return " [[flat]]";
		}
		if (interp == null) {
			return "";
		}
		return switch (interp) {
			case "flat" -> " [[flat]]";
			case "noperspective" -> " [[center_no_perspective]]";
			case "centroid" -> " [[centroid_perspective]]";
			default -> "";
		};
	}

	/** The fragment output: one attachment per request target; depth only when the shader writes gl_FragDepth. */
	private String foutStruct(boolean depth, StageEmitter fs) {
		StringBuilder sb = new StringBuilder("struct Fs_out {\n");
		for (int k = 0; k < request.outputs().size(); k++) {
			ColorTarget t = request.outputs().get(k);
			// A listed target the fragment stage writes nothing for (a sparse DRAWBUFFERS or RENDERTARGETS set) gets
			// no member: the reflection omits it and the runtime leaves that attachment slot empty (E3, 8b2add0).
			StageEmitter.Output out = fs.outputFor(k);
			if (out == null) {
				continue;
			}
			// An integer output takes an integer attachment: its member has the integer type of the GLSL output.
			String scalar = MslTypes.scalar(out.type().componentKind());
			String type = t.components() == 1 ? scalar : scalar + t.components();
			sb.append('\t').append(type).append(" c").append(t.attachment()).append(" [[color(")
				.append(t.attachment()).append(")]];\n");
		}
		if (depth) {
			sb.append("\tfloat gl_FragDepth [[depth(any)]];\n");
		}
		return sb.append("};\n").toString();
	}

	/**
	 * The vertex epilogue (ADR 0006 section 2): OpenGL depth [-w, w] maps to Metal's [0, w]; y flips as the backend does.
	 * A pipeline that draws points gets the pack's gl_PointSize, the line-width attribute's value (the game's
	 * debug_point.vsh) when the layout carries one and the pack writes none, or GL's default of 1.0.
	 * An integer output takes an integer attachment; the alpha test reads only float outputs. Metal's default winding is
	 * clockwise: the y flip turns GL's counter-clockwise front faces into clockwise ones, and the runtime maps GL
	 * back-face culling to MTLCullModeBack with that winding.
	 */
	private String vertexResult(List<StageEmitter.Varying> outs, StageEmitter se, Map<String, Integer> location,
			boolean pointSizeUsed) {
		StringBuilder sb = new StringBuilder();
		sb.append("\t\tVs_out o{};\n");
		int lineWidth = -1;
		int normal = -1;
		int position = -1;
		if (request.vertexLayout() != null) {
			List<VertexAttribute> attributes = request.vertexLayout().attributes();
			for (int i = 0; i < attributes.size(); i++) {
				if (attributes.get(i).semantic() == ProgramRequest.Semantic.LINE_WIDTH) {
					lineWidth = i;
				} else if (attributes.get(i).semantic() == ProgramRequest.Semantic.NORMAL) {
					normal = i;
				} else if (attributes.get(i).semantic() == ProgramRequest.Semantic.POSITION) {
					position = i;
				}
			}
		}
		sb.append("\t\tfloat4 p = gl_Position;\n");
		if (lineWidth >= 0 && normal >= 0 && position >= 0
				&& request.family() != ProgramRequest.Family.FULL_SCREEN) {
			// 2026-11-14 (E4, plan.md E4's "Line pipelines"): the game's rendertype_lines.vsh widens each line in the
			// vertex stage; a pack's gbuffers_line (or basic) with ftransform() would draw zero-width quads, so the
			// epilogue widens the line the same way after the pack's vertex code: project Position and Position + Normal
			// (view shrunk by 1 - 1/256, as the game's VIEW_SCALE does), find the screen direction, offset by LineWidth
			// / ScreenSize to the vertex index's parity side. The geometry comes from the layout's POSITION and NORMAL
			// attributes, not the served gl_Vertex/gl_Normal members: a pack program need not read either name (the
			// 1.17 names never declare them), and before this every LINE_WIDTH layout whose program ignored gl_Normal
			// failed to compile (game log 2026-11-14-e4-preview2). A layout that carries LINE_WIDTH is a game outline
			// format (no decode, no region prologue), so the raw stage-in values are the served ones.
			String v = attributeSource(position);
			String n = attributeSource(normal);
			// A real gbuffers request serves the frame block's matrices and size; a request without blocks (the tests')
			// falls back to the identity and a 1x1 screen, so the epilogue always emits the widening.
			String mv = servedOr(se, "modelViewMatrix", "float4x4(1.0f)");
			String pj = servedOr(se, "projectionMatrix", "float4x4(1.0f)");
			String screen = "float2(" + servedOr(se, "viewWidth", "1.0f") + ", "
				+ servedOr(se, "viewHeight", "1.0f") + ")";
			sb.append("\t\tfloat3 qs_ln_pos = float3(").append(v).append(").xyz;\n");
			sb.append("\t\tfloat3 qs_ln_view = (").append(mv).append(" * float4(qs_ln_pos, 1.0f)).xyz;\n");
			sb.append("\t\tfloat3 qs_ln_end = (").append(mv).append(" * float4(qs_ln_pos + float3(").append(n)
				.append(").xyz, 1.0f)).xyz;\n");
			sb.append("\t\tqs_ln_view *= 0.99609375f;\n");
			sb.append("\t\tqs_ln_end *= 0.99609375f;\n");
			sb.append("\t\tfloat4 qs_ln_a = ").append(pj).append(" * float4(qs_ln_view, 1.0f);\n");
			sb.append("\t\tfloat4 qs_ln_b = ").append(pj).append(" * float4(qs_ln_end, 1.0f);\n");
			sb.append("\t\tfloat3 qs_ln_n1 = qs_ln_a.xyz / qs_ln_a.w;\n");
			sb.append("\t\tfloat3 qs_ln_n2 = qs_ln_b.xyz / qs_ln_b.w;\n");
			sb.append("\t\tfloat2 qs_ln_dir = normalize((qs_ln_n2.xy - qs_ln_n1.xy) * ").append(screen).append(");\n");
			sb.append("\t\tfloat2 qs_ln_off = float2(-qs_ln_dir.y, qs_ln_dir.x) * qs_a").append(lineWidth)
				.append(" / ").append(screen).append(";\n");
			sb.append("\t\tif (qs_ln_off.x < 0.0f) {\n\t\t\tqs_ln_off = -qs_ln_off;\n\t\t}\n");
			sb.append("\t\tfloat2 qs_ln_side = (qs_vid % 2u == 0u) ? qs_ln_off : -qs_ln_off;\n");
			sb.append("\t\tp = float4((qs_ln_n1 + float3(qs_ln_side, 0.0f)) * qs_ln_a.w, qs_ln_a.w);\n");
		}
		sb.append("\t\tp.z = (p.z + p.w) * 0.5f;\n");
		sb.append("\t\tp.y = -p.y;\n");
		sb.append("\t\to.gl_Position = p;\n");
		if (request.drawsPoints()) {
			// The game's debug_point.vsh writes no widening and takes the point size from the LineWidth attribute;
			// GL's default is 1.0 when the layout carries no width and the program writes none.
			sb.append("\t\to.gl_PointSize = ").append(pointSizeUsed ? "gl_PointSize"
				: lineWidth >= 0 ? "qs_a" + lineWidth : "1.0f").append(";\n");
		}
		for (StageEmitter.Varying v : outs) {
			sb.append("\t\to.").append(v.field()).append(" = ").append(v.member()).append(";\n");
		}
		sb.append("\t\treturn o;\n");
		return sb.toString();
	}


	/** The MSL source of the attribute at {@code index}: its decode expression, or the stage-in value. */
	private String attributeSource(int index) {
		VertexAttribute attribute = request.vertexLayout().attributes().get(index);
		return attribute.decode() != null ? "(" + attribute.decode() + ")" : "qs_a" + index;
	}

	/** The served field's source, or {@code fallback} when the request's stage has no such member. */
	private static String servedOr(StageEmitter se, String field, String fallback) {
		String served = se.servedField(field);
		return served != null ? served : fallback;
	}

	/** The fragment epilogue: the alpha test first, then every target value padded to its component count. */
	private String fragmentResult(StageEmitter fs, boolean depth) {
		StringBuilder sb = new StringBuilder();
		if (request.alphaTest() != null) {
			sb.append(alphaTest(fs, request.alphaTest()));
		}
		sb.append("\t\tFs_out o{};\n");
		for (int k = 0; k < request.outputs().size(); k++) {
			ColorTarget t = request.outputs().get(k);
			StageEmitter.Output out = fs.outputFor(k);
			if (out == null) {
				continue;  // no Fs_out member exists for a target nothing writes (foutStruct)
			}
			sb.append("\t\to.c").append(t.attachment()).append(" = ").append(padValue(out, t.components()))
				.append(";\n");
		}
		if (depth) {
			sb.append("\t\to.gl_FragDepth = gl_FragDepth;\n");
		}
		sb.append("\t\treturn o;\n");
		return sb.toString();
	}

	/**
	 * The compatibility alpha test (engine-pack-format 12 and 15): output 0's alpha against the reference uniform, and a
	 * failing fragment is discarded. The reference is the pack's declaration of that uniform when it has one, else the
	 * engine's schema field (alphaTestRef), which a program need not declare. The fragment stage is marked as discarding.
	 */
	private String alphaTest(StageEmitter fs, AlphaTest at) {
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
			fs.markDiscard();
			return "\t\tdiscard_fragment();\n";
		}
		StageEmitter.Output out0 = fs.outputFor(0);
		if (out0 == null) {
			fs.warning(null, "alpha-test-target", "the alpha test reads target 0, which the program does not write; the"
				+ " test is skipped");
			return "";
		}
		if (!(out0.type() instanceof VectorType v && v.size() == 4)
				|| out0.type().componentKind() != ScalarKind.FLOAT) {
			fs.warning(null, "alpha-test-target", "the alpha test reads target 0, which is not a float vec4; the test"
				+ " is skipped");
			return "";
		}
		String ref = null;
		for (VariableSymbol sym : fs.sema().globals()) {
			if (sym.name().equals(at.refUniform()) && sym.quals().isUniform()) {
				ref = fs.variableRef(sym);
			}
		}
		if (ref == null) {
			ref = fs.servedField(at.refUniform());
		}
		if (ref == null) {
			fs.error(null, "alpha-reference", "the alpha test's reference '" + at.refUniform()
				+ "' is not declared by the pack, and no engine block serves it");
			return "";
		}
		fs.markDiscard();
		return "\t\tif (!(" + out0.member() + ".w " + op + " " + ref + ")) {\n\t\t\tdiscard_fragment();\n\t\t}\n";
	}

	/** An output value padded (zeros, alpha 1) or truncated to the attachment's components. */
	private static String padValue(StageEmitter.Output out, int components) {
		GlslType t = out.type();
		int have = t instanceof VectorType v ? v.size() : 1;
		String base = MslTypes.scalar(t.componentKind());
		String text = out.member();
		if (components == 1) {
			return base + "(" + (have == 1 ? text : "(" + text + ").x") + ")";
		}
		String target = base + components;
		if (have == components) {
			return target + "(" + text + ")";
		}
		if (have > components) {
			return target + "((" + text + ")." + "xyzw".substring(0, components) + ")";
		}
		StringBuilder args = new StringBuilder(text);
		for (int i = have; i < components; i++) {
			args.append(i == 3 ? ", 1.0f" : ", 0.0f");
		}
		return target + "(" + args + ")";
	}

	// ----- reflection -------------------------------------------------------------------------------------------------

	private Reflection reflect(Map<Stage, StageEmitter> emitters, Map<String, List<StageEmitter.Resource>> resources,
			boolean depth, boolean coversViewport) {
		ResourceAccess all = new ResourceAccess();
		for (StageEmitter se : emitters.values()) {
			all.addAll(se.access());
		}
		List<Reflection.UniformUse> uniforms = new ArrayList<>();
		List<Reflection.ResourceUse> textures = new ArrayList<>();
		List<Reflection.ResourceUse> images = new ArrayList<>();
		List<Reflection.ResourceUse> buffers = new ArrayList<>();
		List<String> inputs = new ArrayList<>();
		Set<String> seenUniform = new LinkedHashSet<>();
		Set<String> seenRes = new LinkedHashSet<>();
		boolean discards = false;
		boolean barrier = false;
		boolean fragCoord = false;
		boolean frontFacing = false;
		boolean vertexId = false;
		boolean instanceId = false;
		boolean pointCoord = false;
		int[] workGroup = {1, 1, 1};
		long threadgroupBytes = 0;
		for (Map.Entry<Stage, StageEmitter> e : emitters.entrySet()) {
			StageEmitter se = e.getValue();
			for (VariableSymbol v : se.usedVariables()) {
				if (v.quals().isUniform() && !(v.type() instanceof SamplerType) && !(v.type() instanceof ImageType)
						&& seenUniform.add(v.name())) {
					String served = se.servedFrom().get(v.name());
					uniforms.add(new Reflection.UniformUse(v.name(), v.type().toString(),
						served == null ? Reflection.UniformUse.UNKNOWN : served));
				}
				if (e.getKey() == Stage.VERTEX && !v.builtin() && StageEmitter.isVertexInput(v)) {
					inputs.add(v.name());
				}
			}
			if (e.getKey() == Stage.VERTEX) {
				// The built-in attributes the layout serves (gl_Vertex, gl_Color, vaPosition, ...), once each.
				for (VariableSymbol b : se.sema().usedBuiltins().values()) {
					if (b.quals().isInput() && StageEmitter.isAttributeName(b.name()) && !inputs.contains(b.name())) {
						inputs.add(b.name());
					}
				}
			}
			for (String unknown : se.unknownUniforms()) {
				if (se.usedVariables().stream().anyMatch(v -> v.name().equals(unknown)) && seenUniform.add(unknown)) {
					uniforms.add(new Reflection.UniformUse(unknown, "", Reflection.UniformUse.UNKNOWN));
				}
			}
			for (StageEmitter.Resource r : resources.get(stageName(e.getKey()))) {
				if (!seenRes.add(r.kind() + ":" + r.name())) {
					continue;
				}
				// A texture reads levels when any stage's lookups do (E9): the use is recorded once, from its first stage.
				boolean levels = r.kind().equals("texture")
					&& emitters.values().stream().anyMatch(stage -> stage.readsLevels(r.name()));
				Reflection.ResourceUse use = new Reflection.ResourceUse(r.name(), r.slot(), r.mslType(),
					all.accessOf(r.name(), r.access()), false, -1, levels);
				switch (r.kind()) {
					case "texture" -> textures.add(use);
					case "image" -> images.add(use);
					default -> {
					}
				}
			}
			for (StageEmitter.Block block : se.blocksUsed()) {
				if (seenRes.add("block:" + block.key())) {
					// E6-B: a buffer block is a storage buffer (device memory); it binds its bufferObject (the request's index,
					// found by the GLSL block name: the key is the instance name when the block has one).
					buffers.add(new Reflection.ResourceUse(block.key(), block.slot(),
						MslTypes.structName(block.struct()), all.accessOf(block.struct().name(), "read"), block.buffer(),
						bufferObjectOf(block.name())));
				}
			}
			discards |= se.discards();
			barrier |= se.barrier();
			fragCoord |= se.sema().usedBuiltins().containsKey("gl_FragCoord");
			frontFacing |= se.sema().usedBuiltins().containsKey("gl_FrontFacing");
			pointCoord |= se.sema().usedBuiltins().containsKey("gl_PointCoord");
			vertexId |= se.sema().usedBuiltins().containsKey("gl_VertexID");
			instanceId |= se.sema().usedBuiltins().containsKey("gl_InstanceID");
			if (e.getKey() == Stage.COMPUTE) {
				workGroup = localSize(se.sema());
				threadgroupBytes = se.sharedBytes();  // aligned per variable, the layout a spilled kernel's buffer uses
			}
		}
		List<Reflection.OutputUse> outputs = new ArrayList<>();
		StageEmitter fs = emitters.get(Stage.FRAGMENT);
		if (fs != null) {
			for (int k = 0; k < request.outputs().size(); k++) {
				ColorTarget t = request.outputs().get(k);
				StageEmitter.Output out = fs.outputFor(k);
				if (out != null) {
					outputs.add(new Reflection.OutputUse(out.member(), k, t.attachment(), t.components()));
				}
			}
		}
		List<Integer> feedback = new ArrayList<>();
		for (TextureBinding b : request.textures()) {
			boolean used = resources.values().stream().flatMap(List::stream)
				.anyMatch(r -> r.kind().equals("texture") && r.name().equals(b.name()));
			boolean writtenHere = request.outputs().stream().anyMatch(o -> o.target() == b.target());
			if (used && b.target() >= 0 && writtenHere && !feedback.contains(b.target())) {
				feedback.add(b.target());
			}
		}
		Reflection.Flags flags = new Reflection.Flags(discards, depth, fragCoord, frontFacing, vertexId, instanceId,
			pointCoord, barrier, coversViewport);
		List<Reflection.SamplerUse> samplers = new ArrayList<>();
		for (Map.Entry<Stage, StageEmitter> e : emitters.entrySet()) {
			for (StageEmitter.Resource r : resources.get(stageName(e.getKey()))) {
				if (r.kind().equals("sampler")) {
					samplers.add(new Reflection.SamplerUse(r.name(), e.getKey(), r.slot()));
				}
			}
		}
		return new Reflection(uniforms, textures, images, buffers, inputs, outputs, flags, workGroup,
			(int) threadgroupBytes, feedback, samplers);
	}

	private static int[] localSize(SemaResult sema) {
		int[] size = {1, 1, 1};
		String[] names = {"local_size_x", "local_size_y", "local_size_z"};
		for (int i = 0; i < 3; i++) {
			var value = sema.localSize().get(names[i]);
			if (value != null) {
				size[i] = (int) value.integerValue();
			}
		}
		return size;
	}
}
