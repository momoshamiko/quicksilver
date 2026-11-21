// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.msl;

import dev.mcms.glsl.ast.Decl;
import dev.mcms.glsl.ast.Declarator;
import dev.mcms.glsl.ast.Expr;
import dev.mcms.glsl.ast.FunctionDefinition;
import dev.mcms.glsl.ast.Ident;
import dev.mcms.glsl.ast.InterfaceBlock;
import dev.mcms.glsl.ast.LayoutItem;
import dev.mcms.glsl.ast.LayoutQual;
import dev.mcms.glsl.ast.Param;
import dev.mcms.glsl.ast.Pos;
import dev.mcms.glsl.ast.Qualifier;
import dev.mcms.glsl.ast.StorageKind;
import dev.mcms.glsl.ast.StorageQual;
import dev.mcms.glsl.ast.Unit;
import dev.mcms.glsl.msl.ProgramRequest.BufferBinding;
import dev.mcms.glsl.msl.ProgramRequest.ColorTarget;
import dev.mcms.glsl.msl.ProgramRequest.ImageBinding;
import dev.mcms.glsl.msl.ProgramRequest.Semantic;
import dev.mcms.glsl.msl.ProgramRequest.TextureBinding;
import dev.mcms.glsl.msl.ProgramRequest.UniformBlock;
import dev.mcms.glsl.msl.ProgramRequest.UniformField;
import dev.mcms.glsl.msl.ProgramRequest.VertexAttribute;
import dev.mcms.glsl.msl.builtins.BuiltinTable;
import dev.mcms.glsl.sema.FunctionOverload;
import dev.mcms.glsl.sema.SemaResult;
import dev.mcms.glsl.sema.SourceLine;
import dev.mcms.glsl.sema.Stage;
import dev.mcms.glsl.sema.VariableSymbol;
import dev.mcms.glsl.transform.FragmentOutputs;
import dev.mcms.glsl.types.ArrayType;
import dev.mcms.glsl.types.GlslType;
import dev.mcms.glsl.types.ImageType;
import dev.mcms.glsl.types.MatrixType;
import dev.mcms.glsl.types.SamplerType;
import dev.mcms.glsl.types.ScalarKind;
import dev.mcms.glsl.types.ScalarType;
import dev.mcms.glsl.types.StructType;
import dev.mcms.glsl.types.VectorType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Emits one stage (ADR 0006 section 2). The context struct holds the stage's globals, resources and used built-in
 * variables as members; the stage's functions are its methods; {@code qs_bind} copies the entry's inputs (attributes,
 * the rasterizer's values, thread ids) and the engine's uniforms into the built-ins; {@code qs_result} writes the
 * epilogue (clip space, fragment outputs, the alpha test). The entry function builds the context from its arguments
 * and returns the stage's result.
 *
 * <p>Names: a user identifier is {@code u_} plus its GLSL name; a built-in keeps its GLSL name (no MSL name starts with
 * {@code gl_}); the entry's inputs are {@code qs_*}; an interface block field is {@code (*blk_<block>).u_<field>}.
 */
final class StageEmitter {

	/** A varying of the vertex-to-fragment interface: the key both stages share, its type, interpolation and member. */
	record Varying(String key, GlslType type, String interp, String member) {
		String field() {
			return "v_" + key.replace("[", "_").replace("]", "");
		}
	}

	/** A fragment output at its GLSL location: the context member that holds its value and its type. */
	record Output(int location, String member, GlslType type) {
	}

	/** A resource the entry binds: the MSL type of its argument, its slot and the context member it fills. */
	record Resource(String kind, String name, String mslType, int slot, String member, String access) {
	}

	/**
	 * A block the stage uses: its key (the instance name, else the block name), its GLSL block name (the request's binding
	 * name), its context pointer, struct, slot and whether it is a buffer (device) or uniform block.
	 */
	record Block(String key, String name, String pointer, StructType struct, int slot, boolean buffer) {
	}

	private static final Map<String, Semantic> ATTRIBUTES = Map.ofEntries(
			Map.entry("gl_Vertex", Semantic.POSITION), Map.entry("vaPosition", Semantic.POSITION),
			Map.entry("gl_Color", Semantic.COLOR), Map.entry("vaColor", Semantic.COLOR),
			Map.entry("gl_Normal", Semantic.NORMAL), Map.entry("vaNormal", Semantic.NORMAL),
			Map.entry("gl_MultiTexCoord0", Semantic.TEXCOORD0), Map.entry("vaUV0", Semantic.TEXCOORD0),
			Map.entry("vaUV1", Semantic.OVERLAY),
			Map.entry("gl_MultiTexCoord1", Semantic.LIGHTMAP), Map.entry("vaUV2", Semantic.LIGHTMAP),
			Map.entry("gl_MultiTexCoord2", Semantic.TEXCOORD2), Map.entry("dhMaterialId", Semantic.DH_MATERIAL_ID),
			Map.entry("mc_Entity", Semantic.ENTITY), Map.entry("mc_midTexCoord", Semantic.MID_TEXCOORD),
			Map.entry("at_tangent", Semantic.TANGENT), Map.entry("at_midBlock", Semantic.MID_BLOCK),
			Map.entry("at_velocity", Semantic.VELOCITY));

	/** The vertex format names the translator understands: base type, component count, Normalized suffix. */
	private static final Pattern FORMAT =
		Pattern.compile("(float|half|uchar|char|ushort|short|uint|int)([1-4])(Normalized)?");

	/** The uniforms the per-vertex id input serves (ProgramRequest.PerVertexIds; engine-pack-format.md section 10). */
	static final List<String> PER_VERTEX_ID_UNIFORMS = List.of("entityId", "blockEntityId", "currentRenderedItemId");
	/** The served-from name the reflection lists for the per-vertex id uniforms. */
	static final String PER_VERTEX_IDS = "per-vertex ids";
	/** The uniform the entity overlay input serves (ProgramRequest.OverlayColor; engine-pack-format.md section 10). */
	static final String OVERLAY_COLOR_UNIFORM = "entityColor";
	/** The served-from name the reflection lists for the entityColor uniform. */
	static final String OVERLAY_COLOR = "overlay color";

	/** The per-vertex id names this stage serves; the vertex side also holds names only the fragment declared. */
	private final Map<String, GlslType> perVertexIds = new LinkedHashMap<>();

	/** The type this stage declared entityColor with, or null when it did not declare it or serves no overlay. */
	private GlslType overlayColorType;
	/** True once the overlay fetch reads a texture (set when the overlay binding is made; false without UV1). */
	private boolean overlayFetch;


	private final Stage stage;
	private final SemaResult sema;
	private final Unit unit;
	private final ProgramEmitter program;
	private final BuiltinTable table;
	private final List<SourceLine> lineMap;
	private final ProgramRequest request;
	private final String ctxName;
	private final Map<VariableSymbol, String> refs = new HashMap<>();
	private final Map<VariableSymbol, String> refBlock = new HashMap<>();
	private final Set<VariableSymbol> packedRefs = new LinkedHashSet<>();
	private final Set<VariableSymbol> used = new LinkedHashSet<>();
	private final Set<String> usedBlocks = new LinkedHashSet<>();
	/** E9: the texture uniforms a lookup reads at a mip level other than 0 ({@link #markLevelRead}). */
	private final Set<String> levelReads = new LinkedHashSet<>();
	/** The texture uniforms passed to a user function's sampler parameter ({@link #markSamplerPassed}). */
	private final Set<String> passedSamplers = new LinkedHashSet<>();
	/** Whether a lookup through a sampler parameter reads a mip level other than 0. */
	private boolean parameterLevelRead;
	/** Whether an implicit-LOD lookup goes through a sampler parameter ({@link #markImplicitRead}). */
	private boolean parameterImplicitRead;
	private final Map<String, Block> blocks = new LinkedHashMap<>();
	private final Map<String, String> members = new LinkedHashMap<>();
	private final Map<String, VariableSymbol> shared = new LinkedHashMap<>();
	private final Map<String, String> servedFrom = new LinkedHashMap<>();
	/** The engine block that served the last field asked for (see servedField). */
	private String lastServed;
	private final List<String> unknownUniforms = new ArrayList<>();
	private final StringBuilder methods = new StringBuilder();
	private final StringBuilder initGlobals = new StringBuilder();
	private final StringBuilder bind = new StringBuilder();
	private final List<Varying> outVaryings = new ArrayList<>();
	private final List<Varying> inVaryings = new ArrayList<>();
	private final Map<Integer, Output> outputs = new LinkedHashMap<>();
	/** The gl_FragColor the fragment stage uses, written to every colour target (null when unused). */
	private VariableSymbol colorBroadcast;
	private final Map<String, Integer> attributeIndex = new LinkedHashMap<>();
	private final Map<VariableSymbol, String> localNames = new HashMap<>();
	private int tempCount;
	private boolean discards;
	private boolean barrier;

	StageEmitter(Stage stage, SemaResult sema, Unit unit, ProgramEmitter program, BuiltinTable table,
			List<SourceLine> lineMap, ProgramRequest request) {
		this.stage = stage;
		this.sema = sema;
		this.unit = unit;
		this.program = program;
		this.table = table;
		this.lineMap = lineMap;
		this.request = request;
		this.ctxName = switch (stage) {
			case VERTEX -> "Ctx_vs";
			case FRAGMENT -> "Ctx_fs";
			case COMPUTE -> "Ctx_cs";
		};
	}

	// ----- queries used by the body emitter ---------------------------------------------------------------------------

	static String member(String glslName) {
		return "u_" + glslName;
	}

	Stage stage() {
		return stage;
	}

	String ctxName() {
		return ctxName;
	}

	SemaResult sema() {
		return sema;
	}

	List<SourceLine> lineMap() {
		return lineMap;
	}

	/** The C++ spelling of a user function: a method of the stage's context. */
	String functionName(FunctionOverload f) {
		return member(f.name());
	}

	/** The reference text of a variable: a member, a block field, a shared array, a local or a parameter. */
	String variableRef(VariableSymbol v) {
		used.add(v);
		String block = refBlock.get(v);
		if (block != null) {
			usedBlocks.add(block);
		}
		String ref = refs.get(v);
		if (ref != null) {
			return ref;
		}
		if (v.builtin()) {
			return v.name();
		}
		if (sema.isGlobal(v)) {
			return member(v.name());
		}
		// A local or parameter gets a unique name: C++ scopes a declared name from its declarator, so a GLSL initializer
		// that names an outer variable of the same name would read the new one. The l_ prefix is no member's (members
		// are u_ names), so a local never shadows a global of a similar name (foo_0 against foo).
		return localNames.computeIfAbsent(v, k -> "l_" + v.name() + "_" + localNames.size());
	}

	/** A fresh local name for a copy of an argument that MSL cannot bind by reference (see BodyEmitter.userCall). */
	String tempName() {
		return "qs_t" + tempCount++;
	}

	/**
	 * The sampler of a texture argument: the constexpr sampler of a pack texture's state (ProgramEmitter.constexprSampler), or
	 * the game's sampler member named after the texture.
	 */
	String samplerOf(Expr e, Pos pos) {
		if (e instanceof Ident id && sema.symbolOf(id) instanceof VariableSymbol v) {
			String ref = variableRef(v);
			String constexpr = v.quals().isUniform() ? program.constexprSampler(v.name()) : null;
			return constexpr != null ? constexpr : "smp_" + ref;
		}
		error(pos, "texture-argument", "a texture argument must be a variable or a parameter");
		return "smp_error";
	}

	/**
	 * The implicit-LOD sampler of a texture argument: the uniform's (ProgramEmitter.implicitSampler), or the implicit sampler
	 * a sampler parameter carries (its {@code smpi_} argument, which a call passes; E6-fix-F). Null when its lookups take
	 * samplerOf.
	 */
	@Nullable String implicitSamplerOf(Expr e) {
		if (e instanceof Ident id && sema.symbolOf(id) instanceof VariableSymbol v) {
			if (v.quals().isUniform()) {
				return program.implicitSampler(v.name());
			}
			if (v.type() instanceof SamplerType) {
				return "smpi_" + variableRef(v);
			}
		}
		return null;
	}

	/**
	 * ADR 0008: the engine's LOD bias for the implicit-LOD lookups of a texture argument, as an MSL expression, or null. Only in a
	 * fragment stage (Metal's bias option is fragment-only), only for a uniform whose binding asks for it
	 * (ProgramRequest.TextureBinding.lodBias), and only when an engine block serves {@link ProgramRequest#LOD_BIAS_FIELD}; the
	 * stage then binds that block even when the pack reads none of its uniforms. A sampler parameter takes none (ADR 0008's
	 * known limit: the lookup cannot tell which texture the call passed).
	 */
	@Nullable String lodBiasOf(Expr e) {
		if (stage != Stage.FRAGMENT || !(e instanceof Ident id && sema.symbolOf(id) instanceof VariableSymbol v
				&& v.quals().isUniform() && program.lodBiased(v.name()))) {
			return null;
		}
		String block = servingBlock(ProgramRequest.LOD_BIAS_FIELD);
		if (block == null) {
			return null;
		}
		usedBlocks.add(block);
		return "(*blk_" + block + ")." + member(ProgramRequest.LOD_BIAS_FIELD);
	}

	/**
	 * E9: a lookup on this texture reads a mip level other than 0 (BuiltinTable.readsLevels): a uniform is recorded by name;
	 * a sampler parameter (or any other expression) marks every uniform the stage passes to a sampler parameter
	 * ({@link #markSamplerPassed}), since the parameter can be any of them.
	 */
	void markLevelRead(Expr texture) {
		if (texture instanceof Ident id && sema.symbolOf(id) instanceof VariableSymbol v && v.quals().isUniform()) {
			levelReads.add(v.name());
		} else {
			parameterLevelRead = true;
		}
	}

	/** A texture argument of a user call's sampler parameter (see {@link #markLevelRead}). */
	void markSamplerPassed(Expr texture) {
		if (texture instanceof Ident id && sema.symbolOf(id) instanceof VariableSymbol v && v.quals().isUniform()) {
			passedSamplers.add(v.name());
		}
	}

	/**
	 * E9: an implicit-LOD lookup on this texture. On a sampler parameter it takes the parameter's implicit sampler, which a
	 * call fills with the passed uniform's mip-filtered sampler when the uniform has no implicit one (a program that declares
	 * the target's mipmaps), so the lookup reads levels for exactly those uniforms ({@link #readsLevels}). A uniform's own
	 * implicit lookup is decided where it is made (BuiltinTable.readsLevels).
	 */
	void markImplicitRead(Expr texture) {
		if (!(texture instanceof Ident id && sema.symbolOf(id) instanceof VariableSymbol v && v.quals().isUniform())) {
			parameterImplicitRead = true;
		}
	}

	/** Whether this stage reads a mip level other than 0 of the texture uniform (the reflection's ResourceUse.levels). */
	boolean readsLevels(String texture) {
		if (levelReads.contains(texture)) {
			return true;
		}
		return passedSamplers.contains(texture)
			&& (parameterLevelRead || parameterImplicitRead && program.implicitSampler(texture) == null);
	}

	boolean isPackedRef(VariableSymbol v) {
		return packedRefs.contains(v);
	}

	boolean isPackedStruct(StructType s) {
		return program.isBlockStruct(s);
	}

	void requirePrelude(String helper) {
		program.requirePrelude(helper);
	}

	void markDiscard() {
		discards = true;
	}

	void markBarrier() {
		barrier = true;
	}

	BuiltinTable table() {
		return table;
	}

	BuiltinTable.Stage tableStage() {
		return switch (stage) {
			case VERTEX -> BuiltinTable.Stage.VERTEX;
			case FRAGMENT -> BuiltinTable.Stage.FRAGMENT;
			case COMPUTE -> BuiltinTable.Stage.COMPUTE;
		};
	}

	String equalityText(GlslType t, String a, String b) {
		return program.equality(t, a, b);
	}

	void error(Pos pos, String code, String message) {
		program.error(stage, pos, lineMap, code, message);
	}

	void warning(Pos pos, String code, String message) {
		program.warning(stage, pos, lineMap, code, message);
	}

	// ----- building ---------------------------------------------------------------------------------------------------

	/** Registers blocks and globals, emits the functions, the global initializers and the bindings of the inputs. */
	void build(List<VertexAttribute> layout) {
		if (stage == Stage.VERTEX) {
			members.put("gl_Position", "float4 gl_Position{}");
		}
		registerBlocks();
		classifyGlobals();
		emitFunctions();
		collectInterface();
		buildBind(layout);
	}

	private void registerBlocks() {
		for (Decl d : unit.decls()) {
			if (!(d instanceof InterfaceBlock b) || "gl_PerVertex".equals(b.name())) {
				continue;
			}
			boolean buffer = hasStorage(b.qualifiers(), StorageKind.BUFFER);
			boolean uniform = hasStorage(b.qualifiers(), StorageKind.UNIFORM);
			if (!buffer && !uniform) {
				continue;
			}
			StructType st = sema.structOf(b);
			if (st == null) {
				error(b.pos(), "block", "a block the analyzer did not record");
				continue;
			}
			String key = b.instance() != null ? b.instance() : b.name();
			int slot = slotOfBlock(b.name(), buffer);
			String pointer = "blk_" + key;
			blocks.put(key, new Block(key, b.name(), pointer, st, slot, buffer));
			program.registerPackBlock(st, layoutOf(b.qualifiers(), buffer));
			for (VariableSymbol v : sema.globals()) {
				if (v.declaration() != b) {
					continue;
				}
				refBlock.put(v, key);
				if (b.instance() != null) {
					refs.put(v, "(*" + pointer + ")");
				} else {
					refs.put(v, "(*" + pointer + ")." + member(v.name()));
					if (v.type() instanceof VectorType vt && vt.size() == 3) {
						packedRefs.add(v);
					}
				}
			}
		}
		for (UniformBlock ub : request.uniforms().blocks()) {
			StructType st = program.registerEngineBlock(ub);
			blocks.put(ub.name(), new Block(ub.name(), ub.name(), "blk_" + ub.name(), st, ub.slot(), false));
		}
	}

	private int slotOfBlock(String blockName, boolean buffer) {
		for (BufferBinding b : request.buffers()) {
			if (b.name().equals(blockName)) {
				return b.slot();
			}
		}
		error(null, "block-slot", (buffer ? "buffer" : "uniform") + " block '" + blockName
			+ "' has no slot in the request");
		return -1;
	}

	private static boolean hasStorage(List<Qualifier> qualifiers, StorageKind kind) {
		for (Qualifier q : qualifiers) {
			if (q instanceof StorageQual sq && sq.kind() == kind) {
				return true;
			}
		}
		return false;
	}

	static Layouts.Std layoutOf(List<Qualifier> qualifiers, boolean buffer) {
		for (Qualifier q : qualifiers) {
			if (q instanceof LayoutQual lq) {
				for (LayoutItem item : lq.items()) {
					if ("std430".equals(item.name())) {
						return Layouts.Std.STD430;
					}
					if ("std140".equals(item.name())) {
						return Layouts.Std.STD140;
					}
				}
			}
		}
		return buffer ? Layouts.Std.STD430 : Layouts.Std.STD140;
	}

	private void classifyGlobals() {
		for (VariableSymbol v : sema.globals()) {
			if (v.declaration() instanceof InterfaceBlock) {
				continue;
			}
			boolean programConst = v.quals().isConst() && (!(v.declaration() instanceof Declarator d)
				|| d.initializer() == null || sema.constOf(d.initializer()) != null) || readOnlyConstant(v);
			if (programConst) {
				// Program-scope constants are defined once per stage (ProgramEmitter.registerProgramScope).
				refs.put(v, programConstName(v));
				continue;
			}
			if (v.type() instanceof SamplerType || v.type() instanceof ImageType) {
				continue; // bound resources are handled elsewhere
			}
			if (v.quals().isShared()) {
				shared.put(v.name(), v);
				refs.put(v, "(*" + member(v.name()) + ")");
				continue;
			}
			if (v.quals().isUniform()) {
				if (fullScreen() && (v.name().equals("projectionMatrix") || v.name().equals("modelViewMatrix"))) {
					// The quad's matrices hold for the pack's own 1.17+ names too (plan.md E3).
					refs.put(v, v.name().equals("projectionMatrix") ? QUAD_PROJECTION : IDENTITY4);
					servedFrom.put(v.name(), "full-screen quad");
					continue;
				}
				if (request.perVertexIds() != null && PER_VERTEX_ID_UNIFORMS.contains(v.name())) {
					// E4: the per-entity ids come from the per-vertex side buffer, not from an engine block
					// (ProgramRequest.PerVertexIds); the vertex prologue binds them from [[vertex_id]]'s record.
					refs.put(v, member(v.name()));
					members.put(member(v.name()), declare(v.type(), member(v.name())));
					servedFrom.put(v.name(), PER_VERTEX_IDS);
					perVertexIds.put(v.name(), v.type());
					continue;
				}
				if (request.overlayColor() != null && v.name().equals(OVERLAY_COLOR_UNIFORM)) {
					// E4: entityColor comes from the overlay texture fetch, not from an engine block
					// (ProgramRequest.OverlayColor); the vertex prologue binds it from vaUV1's texel.
					refs.put(v, member(v.name()));
					members.put(member(v.name()), declare(v.type(), member(v.name())));
					servedFrom.put(v.name(), OVERLAY_COLOR);
					overlayColorType = v.type();
					continue;
				}
				String served = servingBlock(v.name());
				if (served == null) {
					unknownUniforms.add(v.name());
					members.put(member(v.name()), declare(v.type(), member(v.name())));
					continue;
				}
				refBlock.put(v, served);
				refs.put(v, "(*blk_" + served + ")." + member(v.name()));
				servedFrom.put(v.name(), served);
				if (v.type() instanceof VectorType vt && vt.size() == 3) {
					packedRefs.add(v);
				}
				continue;
			}
			members.put(member(v.name()), declare(v.type(), member(v.name())));
			if (v.declaration() instanceof Declarator d && d.initializer() != null && !v.quals().isInput()
					&& !v.quals().isOutput()) {
				BodyEmitter init = new BodyEmitter(this, sema, table, initGlobals, 2);
				initGlobals.append("\t\t").append(member(v.name())).append(" = ")
					.append(init.initializer(d, v.type())).append(";\n");
			}
		}
	}

	private static String declare(GlslType t, String name) {
		return MslTypes.value(t) + " " + name + "{}";
	}

	/** The engine block that serves a uniform name, or null when no engine block does. */
	private String servingBlock(String name) {
		for (UniformBlock ub : request.uniforms().blocks()) {
			for (UniformField f : ub.fields()) {
				if (f.name().equals(name)) {
					return ub.name();
				}
			}
		}
		return null;
	}

	private void emitFunctions() {
		for (Decl d : unit.decls()) {
			if (!(d instanceof FunctionDefinition def)) {
				continue;
			}
			FunctionOverload overload = sema.functionOf(def.proto());
			if (overload == null) {
				error(def.pos(), "function", "a definition the analyzer did not record");
				continue;
			}
			StringBuilder params = new StringBuilder();
			for (int i = 0; i < def.proto().params().size(); i++) {
				Param p = def.proto().params().get(i);
				VariableSymbol v = sema.variableOf(p);
				FunctionOverload.Parameter fp = overload.params().get(i);
				GlslType paramType = v != null ? v.type() : fp.type();
				if (paramType.isVoid()) {
					continue;
				}
				if (params.length() > 0) {
					params.append(", ");
				}
				String ref = (v != null && p.name() != null) ? variableRef(v) : "_qs_p" + i;
				String type = MslTypes.value(paramType);
				if (fp.writes()) {
					params.append("thread ").append(type).append(" &").append(ref);
				} else {
					params.append(type).append(' ').append(ref);
				}
				if (paramType instanceof SamplerType) {
					params.append(", sampler smp_").append(ref);
					params.append(", sampler smpi_").append(ref);
				}
			}
			String ret = overload.returnType().isVoid() ? "void" : MslTypes.value(overload.returnType());
			StringBuilder text = new StringBuilder();
			new BodyEmitter(this, sema, table, text, 2).stmt(def.body());
			methods.append("\t").append(ret).append(' ').append(functionName(overload)).append('(')
				.append(params).append(") {\n").append(text).append("\t}\n\n");
		}
	}

	// ----- interface: varyings, outputs, attributes --------------------------------------------------------------------

	/** Collects the varyings this stage writes or reads and the fragment outputs (GLSL 4.60 section 4.3.8). */
	private void collectInterface() {
		for (VariableSymbol v : sema.globals()) {
			if (v.declaration() instanceof InterfaceBlock || v.builtin() || v.quals().isUniform()) {
				continue;
			}
			if (stage == Stage.VERTEX && v.quals().isOutput()) {
				addVarying(outVaryings, v, interpOf(v));
			}
			if (stage == Stage.FRAGMENT && v.quals().isInput()) {
				addVarying(inVaryings, v, interpOf(v));
			}
		}
		for (VariableSymbol v : sema.usedBuiltins().values()) {
			if (stage == Stage.VERTEX && v.quals().isOutput() && isVertexVaryingBuiltin(v.name())) {
				addBuiltinVarying(outVaryings, v, v.name());
			}
			if (stage == Stage.FRAGMENT && v.quals().isInput()) {
				switch (v.name()) {
					case "gl_Color" -> addBuiltinVarying(inVaryings, v, "gl_FrontColor");
					case "gl_SecondaryColor" -> addBuiltinVarying(inVaryings, v, "gl_FrontSecondaryColor");
					case "gl_TexCoord", "gl_FogFragCoord" -> addBuiltinVarying(inVaryings, v, v.name());
					default -> {
					}
				}
			}
		}
		if (stage == Stage.FRAGMENT) {
			collectOutputs();
		}
	}

	/**
	 * A user varying. A matrix is one varying per column and an array one per element (Metal has no matrix or array
	 * vertex output), each with its own location.
	 */
	private void addVarying(List<Varying> into, VariableSymbol v, String interp) {
		addVarying(into, v.name(), member(v.name()), v.type(), interp);
	}

	private static void addVarying(List<Varying> into, String key, String member, GlslType type, String interp) {
		if (type instanceof MatrixType m) {
			for (int c = 0; c < m.cols(); c++) {
				into.add(new Varying(key + "[" + c + "]", VectorType.of(m.component(), m.rows()), interp,
					member + "[" + c + "]"));
			}
			return;
		}
		if (type instanceof ArrayType at && at.size() != null) {
			for (int i = 0; i < at.size(); i++) {
				addVarying(into, key + "[" + i + "]", member + "[" + i + "]", at.element(), interp);
			}
			return;
		}
		into.add(new Varying(key, type, interp, member));
	}

	private static boolean isVertexVaryingBuiltin(String name) {
		return switch (name) {
			case "gl_FrontColor", "gl_BackColor", "gl_FrontSecondaryColor", "gl_BackSecondaryColor",
				"gl_FogFragCoord", "gl_TexCoord" -> true;
			default -> false;
		};
	}

	private void addBuiltinVarying(List<Varying> into, VariableSymbol v, String key) {
		if (v.type() instanceof ArrayType at && at.size() != null) {
			for (int i = 0; i < at.size(); i++) {
				into.add(new Varying(key + i, at.element(), interpOf(v), v.name() + "[" + i + "]"));
			}
			return;
		}
		into.add(new Varying(key, v.type(), interpOf(v), v.name()));
	}

	private static String interpOf(VariableSymbol v) {
		if (v.quals().interpolation() != null) {
			return switch (v.quals().interpolation()) {
				case FLAT -> "flat";
				case NOPERSPECTIVE -> "noperspective";
				case SMOOTH -> null;
			};
		}
		return v.quals().storage().contains(StorageKind.CENTROID) ? "centroid" : null;
	}

	/**
	 * Fragment outputs at their GLSL locations (FragmentOutputs): an explicit layout wins, then the outColor<n> name,
	 * then declaration order. Only the gl_FragData indices the source indexes exist. A varying is an input of the
	 * fragment stage and takes no location (GLSL 4.60 section 4.3.8). gl_FragColor is not an indexed output: it goes
	 * to every colour target (GLSL 4.60 section 7.2, see {@link #outputFor}).
	 */
	private void collectOutputs() {
		List<VariableSymbol> outs = new ArrayList<>();
		for (VariableSymbol v : sema.globals()) {
			if (v.declaration() instanceof InterfaceBlock || !v.quals().storage().contains(StorageKind.OUT)
					|| v.builtin()) {
				continue;
			}
			outs.add(v);
		}
		FragmentOutputs.Assignment assigned = FragmentOutputs.assign(outs, request.outputs().size());
		for (FragmentOutputs.Problem p : assigned.problems()) {
			if (p.error()) {
				error(p.var().pos(), p.code(), p.message());
			} else {
				warning(p.var().pos(), p.code(), p.message());
			}
		}
		for (Map.Entry<Integer, FragmentOutputs.Slot> e : assigned.byTarget().entrySet()) {
			FragmentOutputs.Slot s = e.getValue();
			String member = s.element() < 0 ? member(s.var().name())
				: member(s.var().name()) + "[" + s.element() + "]";
			outputs.put(e.getKey(), new Output(e.getKey(), member, s.type()));
		}
		colorBroadcast = sema.usedBuiltins().get("gl_FragColor");
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
				outputs.put(i, new Output(i, "gl_FragData[" + i + "]", at.element()));
			}
		}
	}

	/** The fragment output that colour target k receives: gl_FragColor for every target, otherwise by index. */
	Output outputFor(int k) {
		if (colorBroadcast != null) {
			return new Output(k, "gl_FragColor", colorBroadcast.type());
		}
		return outputs.get(k);
	}

	/** Attribute bindings and built-in inputs, written into {@code qs_bind}. */
	private void buildBind(List<VertexAttribute> layout) {
		for (int i = 0; i < layout.size(); i++) {
			attributeIndex.put(layout.get(i).semantic().name(), i);
		}
		if (stage == Stage.COMPUTE) {
			computeBind();
			return;
		}
		if (stage == Stage.FRAGMENT) {
			for (String[] pair : new String[][] {{"gl_FragCoord", "qs_pos"}, {"gl_FrontFacing", "qs_ff"},
				{"gl_PointCoord", "qs_pc"}}) {
				if (sema.usedBuiltins().containsKey(pair[0])) {
					bind.append("\t\t").append(pair[0]).append(" = ").append(pair[1]).append(";\n");
				}
			}
		}
		if (stage == Stage.VERTEX) {
			if (fullScreen()) {
				// The full-screen quad (plan.md E3): two triangles from the vertex id 0-5, corners (0,0), (1,0), (0,1) and
				// (0,1), (1,0), (1,1), as OptiFine and Iris draw a quad. A pack that scales gl_Position to a part of the screen
				// (Bliss's low-resolution clouds and light passes) then covers exactly that rectangle; the one large triangle
				// before (corners (0,0), (2,0), (0,2)) covered a triangle reaching past it (E7, Bliss's colortex0).
				bind.append("\t\tfloat2 qs_corner = float2(float((0x32u >> qs_vid) & 1u), float((0x2Cu >> qs_vid) & 1u));\n");
			}
			if (clouds()) {
				bind.append(CLOUDS_PULL);
			}
			for (VariableSymbol v : sema.globals()) {
				if (isVertexInput(v) && !v.builtin() && v.declaration() instanceof Declarator) {
					Semantic s = ATTRIBUTES.get(v.name());
					if (s != null) {
						bindAttribute(member(v.name()), v.type(), s, layout, false);
					}
				}
			}
			for (VariableSymbol v : sema.usedBuiltins().values()) {
				if (v.quals().isInput() && ATTRIBUTES.containsKey(v.name())) {
					bindAttribute(v.name(), v.type(), ATTRIBUTES.get(v.name()), layout, v.name().equals("gl_Color"));
				}
				if (v.quals().isInput() && v.name().equals("gl_VertexID")) {
					bind.append("\t\tgl_VertexID = int(qs_vid);\n");
				}
				if (v.quals().isInput() && v.name().equals("gl_InstanceID")) {
					bind.append("\t\tgl_InstanceID = int(qs_iid);\n");
				}
			}
		}
		bindCompatUniforms();
	}

	/** Compute built-ins: the thread ids the entry passes in, and the work-group size from the layout. */
	private void computeBind() {
		for (VariableSymbol v : sema.usedBuiltins().values()) {
			String value = switch (v.name()) {
				case "gl_GlobalInvocationID" -> "qs_gid";
				case "gl_LocalInvocationID" -> "qs_lid";
				case "gl_WorkGroupID" -> "qs_wgid";
				case "gl_NumWorkGroups" -> "qs_ngroups";
				case "gl_LocalInvocationIndex" -> "qs_lidx";
				default -> null;
			};
			if (value != null) {
				bind.append("\t\t").append(v.name()).append(" = ").append(value).append(";\n");
			}
			if (v.name().equals("gl_WorkGroupSize")) {
				bind.append("\t\tgl_WorkGroupSize = uint3(").append(localSizeText()).append(");\n");
			}
		}
	}

	private String localSizeText() {
		String[] names = {"local_size_x", "local_size_y", "local_size_z"};
		List<String> parts = new ArrayList<>();
		for (String n : names) {
			var value = sema.localSize().get(n);
			parts.add(value == null ? "1u" : value.integerValue() + "u");
		}
		return String.join(", ", parts);
	}

	/** The identity matrix (the modelview of a full-screen quad). */
	private static final String IDENTITY4 = "float4x4(float4(1.0f, 0.0f, 0.0f, 0.0f), float4(0.0f, 1.0f, 0.0f, 0.0f), "
		+ "float4(0.0f, 0.0f, 1.0f, 0.0f), float4(0.0f, 0.0f, 0.0f, 1.0f))";
	/** The full-screen quad's projection: [0,1]^2 to clip space (x = 2u - 1, y = 2v - 1), z = -z, w = 1 (plan.md E3). */
	private static final String QUAD_PROJECTION = "float4x4(float4(2.0f, 0.0f, 0.0f, 0.0f), float4(0.0f, 2.0f, 0.0f, 0.0f), "
		+ "float4(0.0f, 0.0f, -1.0f, 0.0f), float4(-1.0f, -1.0f, 0.0f, 1.0f))";
	/** The lightmap matrix, gl_TextureMatrix[1] for 1.17+ (OptiFine shaders.txt): scale 1/256, offset 1/32. */
	private static final String LIGHTMAP_MATRIX = "float4x4(float4(0.00390625f, 0.0f, 0.0f, 0.0f), "
		+ "float4(0.0f, 0.00390625f, 0.0f, 0.0f), float4(0.0f, 0.0f, 0.00390625f, 0.0f), "
		+ "float4(0.03125f, 0.03125f, 0.03125f, 1.0f))";

	/**
	 * The compatibility uniforms this stage uses: the matrices, the texture matrices and gl_NumSamples. Every used
	 * built-in no source serves is a translation error (compat-unbound), never a silent zero (ADR 0006 section 2).
	 */
	private void bindCompatUniforms() {
		for (VariableSymbol v : sema.usedBuiltins().values()) {
			if (!v.builtin() || !v.quals().isUniform()) {
				continue;
			}
			if (sema.globals().stream().anyMatch(g -> g.name().equals(v.name()))) {
				continue; // the pack declares this name: classifyGlobals serves it
			}
			if (!v.name().startsWith("gl_")) {
				// An undeclared 1.17+ uniform (modelViewMatrix, projectionMatrix, ...) is the quad's or the schema's field.
				String value = switch (v.name()) {
					case "modelViewMatrix" -> modelView();
					case "projectionMatrix" -> projection();
					default -> servedField(v.name());
				};
				if (value == null) {
					unbound(v.name());
				} else {
					bind.append("\t\t").append(v.name()).append(" = ").append(value).append(";\n");
				}
				continue;
			}
			if (v.name().equals("gl_NumSamples")) {
				bind.append("\t\tgl_NumSamples = 1;\n");
				continue;
			}
			if (v.name().equals("gl_Fog")) {
				bindFog();
				continue;
			}
			String served = null;
			if (v.type() instanceof ArrayType at) {
				for (int i = 0; i < at.size(); i++) {
					lastServed = null;
					String value = compatMatrix(v.name(), i);
					if (value == null) {
						unbound(v.name());
						break;
					}
					if (lastServed != null) {
						served = lastServed;
					}
					bind.append("\t\t").append(v.name()).append('[').append(i).append("] = ").append(value).append(";\n");
				}
			} else {
				lastServed = null;
				String value = v.name().equals("gl_NormalMatrix") ? servedField("normalMatrix")
					: compatMatrix(v.name(), -1);
				if (value == null) {
					unbound(v.name());
					continue;
				}
				served = lastServed;
				bind.append("\t\t").append(v.name()).append(" = ").append(value).append(";\n");
			}
			servedFrom.put(v.name(), served != null ? served : (fullScreen() ? "full-screen quad" : "constant"));
		}
	}

	/**
	 * A 4x4 compatibility matrix as an MSL value: the base matrix (modelview, projection, their product for
	 * ftransform, or texture matrix {@code texIndex}) with the Inverse and Transpose suffixes applied. Null when no source
	 * serves the base.
	 */
	private String compatMatrix(String name, int texIndex) {
		boolean inverse = name.contains("Inverse");
		boolean transpose = name.contains("Transpose");
		String core = name.replace("InverseTranspose", "").replace("Inverse", "").replace("Transpose", "");
		String value = switch (core) {
			case "gl_ModelViewMatrix" -> modelView();
			case "gl_ProjectionMatrix" -> projection();
			case "gl_ModelViewProjectionMatrix" -> {
				String p = projection();
				String mv = modelView();
				yield p == null || mv == null ? null : "(" + p + " * " + mv + ")";
			}
			case "gl_TextureMatrix" -> textureMatrix(texIndex);
			default -> null;
		};
		if (value == null) {
			return null;
		}
		if (inverse) {
			requirePrelude("inverse");
			value = "qs::inverse(" + value + ")";
		}
		return transpose ? "transpose(" + value + ")" : value;
	}

	private String modelView() {
		return fullScreen() ? IDENTITY4 : servedField("modelViewMatrix");
	}

	private String projection() {
		return fullScreen() ? QUAD_PROJECTION : servedField("projectionMatrix");
	}

	/** gl_TextureMatrix[0] from the schema, [1] the lightmap matrix, the others the identity. */
	private String textureMatrix(int index) {
		return switch (index) {
			case 0 -> servedField("textureMatrix");
			case 1 -> LIGHTMAP_MATRIX;
			default -> IDENTITY4;
		};
	}

	/** The text of an engine block field that serves a name, marking its block used; null when no block has the field. */
	String servedField(String field) {
		for (UniformBlock ub : request.uniforms().blocks()) {
			for (UniformField f : ub.fields()) {
				if (f.name().equals(field)) {
					usedBlocks.add(ub.name());
					lastServed = ub.name();
					return "(*blk_" + ub.name() + ").u_" + field;
				}
			}
		}
		return null;
	}

	boolean fullScreen() {
		return request.vertexLayout() != null && request.vertexLayout().fullScreen();
	}

	boolean clouds() {
		return request.cloudsPrologue() != null;
	}

	/**
	 * The game's clouds vertex pulling (26.3 {@code assets/minecraft/shaders/core/clouds.vsh}), emitted at the head of
	 * {@code qs_bind()}: three R8_SINT texels per quad from the CloudFaces texture buffer (one byte each: cellX >> 1,
	 * cellZ >> 1, the packed direction and flags; the delegate's view is R8_SINT, GbufferDelegate.bindClouds), the
	 * packed bit constants for the face's four corners, the CloudInfo block's CellSize and CloudOffset, the six face
	 * greys (top wins with the flag) and the six face directions for {@code gl_Normal}. The locals feed
	 * {@code bindAttribute} below; the texel reads mirror {@code texelFetch}'s Metal mapping (texture_buffer read;
	 * R8_SINT sign-extends the byte, which the masks below keep harmless).
	 */
	private static final String CLOUDS_PULL = """
			// 2026-11-14 (E4): the game's clouds.vsh vertex pulling (26.3).
			int qs_cv = int(qs_vid) % 4;
			int qs_cbase = (int(qs_vid) / 4) * 3;
			int qs_cdf = qs_cloudfs.read(uint(qs_cbase + 2)).x;
			int qs_ccx = (qs_cloudfs.read(uint(qs_cbase)).x << 1) | ((qs_cdf & 128) >> 7);
			int qs_ccz = (qs_cloudfs.read(uint(qs_cbase + 1)).x << 1) | ((qs_cdf & 64) >> 6);
			int qs_cd = qs_cdf & 7;
			int qs_cvi = qs_cd * 4 + (((qs_cdf & 16) != 0) ? 3 - qs_cv : qs_cv);
			float3 qs_cpos = (float3(float((0xF03CC3 >> qs_cvi) & 1), float((0x6666F0 >> qs_cvi) & 1),
				float((0xC3F066 >> qs_cvi) & 1)) + float3(float(qs_ccx), 0.0f, float(qs_ccz)))
				* *(constant float3*)(qs_cloudinfo + 32L) + *(constant float3*)(qs_cloudinfo + 16L);
			float qs_cgrey = qs_cd == 0 ? 0.7f : (qs_cd == 1 ? 1.0f : (qs_cd < 4 ? 0.8f : 0.9f));
			if ((qs_cdf & 32) != 0) {
				qs_cgrey = 1.0f;
			}
			float4 qs_ccol = *(constant float4*)(qs_cloudinfo);  // the game's face colours carry alpha 1, so CloudColor's alpha passes through
			float4 qs_ccolv = float4(qs_cgrey * qs_ccol.rgb, qs_ccol.a);  // rgb: the face grey scales colour
			float3 qs_cnormal = qs_cd == 0 ? float3(0.0f, -1.0f, 0.0f) : qs_cd == 1 ? float3(0.0f, 1.0f, 0.0f)
				: qs_cd == 2 ? float3(0.0f, 0.0f, -1.0f) : qs_cd == 3 ? float3(0.0f, 0.0f, 1.0f)
				: qs_cd == 4 ? float3(-1.0f, 0.0f, 0.0f) : float3(1.0f, 0.0f, 0.0f);
			""";

	private void unbound(String name) {
		error(null, "compat-unbound", name + " is a compatibility built-in that no engine block serves for this program");
	}

	/**
	 * gl_Fog from the frame block's fog fields (color, density, start, end): the fields the documented fogColor,
	 * fogDensity, fogStart and fogEnd uniforms read, so both serve the same values. scale is 1 / (end - start), as GLSL
	 * defines it. A packed vec3 colour is widened to a vec4 with alpha 1. The reflection reports the block that serves
	 * the fields (the last one read, the frame block for every field).
	 */
	private void bindFog() {
		lastServed = null;
		String color = servedField("fogColor"), density = servedField("fogDensity");
		String start = servedField("fogStart"), end = servedField("fogEnd");
		if (color == null || density == null || start == null || end == null) {
			unbound("gl_Fog");
			return;
		}
		bind.append("\t\tgl_Fog.u_color = float4(float3(").append(color).append("), 1.0f);\n");
		bind.append("\t\tgl_Fog.u_density = ").append(density).append(";\n");
		bind.append("\t\tgl_Fog.u_start = ").append(start).append(";\n");
		bind.append("\t\tgl_Fog.u_end = ").append(end).append(";\n");
		bind.append("\t\tgl_Fog.u_scale = 1.0f / (gl_Fog.u_end - gl_Fog.u_start);\n");
		servedFrom.put("gl_Fog", lastServed);
	}

	/** The program-scope name of a constant global: per stage, so two stages' constants of one name stay apart. */
	String programConstName(VariableSymbol v) {
		return "qs_k_" + ProgramEmitter.stageName(stage) + "_" + v.name();
	}

	/**
	 * A plain global with a constant initializer that no statement writes: the program reads it as a constant, so it is
	 * defined once at program scope like a const (E9). The context struct keeps neither: one dynamic index into a context
	 * array member (Complementary's jitterOffsets[int(framemod8)]) leaves the whole context in memory, which held
	 * Complementary's terrain fragment work at 35 ms a frame against 1 ms (results/2026-11-17-e9-diag-taa).
	 */
	boolean readOnlyConstant(VariableSymbol v) {
		return v.quals().storage().isEmpty() && !v.builtin() && v.declaration() instanceof Declarator d
			&& d.initializer() != null && !sema.isWritten(v) && sema.constOf(d.initializer()) != null;
	}

	/** True for a vertex-stage input: in or attribute (a varying is an output of the vertex stage). */
	static boolean isVertexInput(VariableSymbol v) {
		return v.quals().storage().contains(StorageKind.IN) || v.quals().storage().contains(StorageKind.ATTRIBUTE);
	}

	/** Whether the reflection lists a built-in input as a vertex input: the attribute names the layout serves. */
	static boolean isAttributeName(String name) {
		return ATTRIBUTES.containsKey(name);
	}

	private void bindAttribute(String target, GlslType type, Semantic semantic, List<VertexAttribute> layout,
			boolean compatibilityColour) {
		if (fullScreen()) {
			// The quad carries position and texcoord from its corner; the other attributes keep their GLSL defaults.
			boolean quadCorner = semantic == Semantic.POSITION || semantic == Semantic.TEXCOORD0;
			String value = quadCorner ? castAttribute("float4(qs_corner, 0.0f, 1.0f)", 4, type)
				: defaultAttribute(semantic, type);
			bind.append("\t\t").append(target).append(" = ").append(value).append(";\n");
			return;
		}
		if (clouds()) {
			// 2026-11-14 (E4): the clouds prologue's pulled values serve the geometry names (CLOUDS_PULL computes
			// them just above, in the same function); the texcoords and every other attribute keep their GL defaults,
			// because the game's clouds carry none (ProgramRequest.CloudsPrologue).
			String pulled = switch (semantic) {
				case POSITION -> "float4(qs_cpos, 1.0f)";
				case COLOR -> "qs_ccolv";
				case NORMAL -> "qs_cnormal";
				default -> null;
			};
			if (pulled != null) {
				int comps = semantic == Semantic.NORMAL ? 3 : 4;
				bind.append("\t\t").append(target).append(" = ")
					.append(castAttribute(pulled, comps, type)).append(";\n");
				return;
			}
		}
		String modulator = compatibilityColour ? colorModulator(semantic, type) : null;
		int index = -1;
		for (int i = 0; i < layout.size(); i++) {
			if (layout.get(i).semantic() == semantic) {
				index = i;
			}
		}
		if (index < 0) {
			bind.append("\t\t").append(target).append(" = ").append(modulated(defaultAttribute(semantic, type), modulator))
				.append(";\n");
			return;
		}
		VertexAttribute attribute = layout.get(index);
		String raw;
		int comps;
		if (attribute.decode() != null) {
			// 2026-11-14 (E4): the registry's decode expression produces the semantic's natural value (E4-B's Sodium
			// terrain); the standard fill still applies around it (a vec3 position gains w = 1).
			raw = "(" + attribute.decode() + ")";
			comps = naturalComponents(semantic);
		} else {
			comps = componentsOf(attribute.format());
			raw = MslTypes.scalar(type.componentKind()) + (comps == 1 ? "" : String.valueOf(comps)) + "(qs_a" + index
				+ ")";
		}
		if (semantic == Semantic.POSITION && request.vertexLayout().prologue() != null) {
			// 2026-11-14 (E4, ADR 0007): the position arrives region-relative; the prologue adds the region offset and
			// subtracts u_CameraOffset, each exactly once around the decoded value. The wrap happens before the one
			// cast, so no double constructor appears.
			ProgramRequest.VertexPrologue prologue = request.vertexLayout().prologue();
			StringBuilder wrapped = new StringBuilder("(").append(raw);
			if (prologue.regionSlot() >= 0) {
				wrapped.append(" + *(constant float3*)(qs_region + ").append(prologue.regionOffset()).append("L)");
			}
			if (prologue.cameraOffsetAt() >= 0) {
				wrapped.append(" - *(constant float3*)(qs_globals + ").append(prologue.cameraOffsetAt()).append("L)");
			}
			raw = wrapped.append(")").toString();
		}
		bind.append("\t\t").append(target).append(" = ")
			.append(modulated(castAttribute(raw, comps, type), modulator)).append(";\n");
	}

	/** The natural component count of a semantic's value (a decode expression's result shape). */
	private static int naturalComponents(Semantic semantic) {
		return switch (semantic) {
			case POSITION, NORMAL, ENTITY, VELOCITY -> 3;
			case COLOR, TANGENT, MID_BLOCK -> 4;
			case TEXCOORD0, TEXCOORD2, OVERLAY, LIGHTMAP, MID_TEXCOORD -> 2;
			case DH_MATERIAL_ID, LINE_WIDTH -> 1;
		};
	}

	/** The MSL argument type of an attribute: its Metal format's base type and component count. */
	static String attributeArgType(String format) {
		Matcher m = FORMAT.matcher(format);
		if (!m.matches()) {
			throw new IllegalArgumentException("unknown vertex format " + format);
		}
		String base = m.group(1);
		int n = Integer.parseInt(m.group(2));
		String type = switch (base) {
			case "float", "half" -> "float";
			case "uchar", "ushort", "uint" -> m.group(3) != null ? "float" : "uint";
			case "char", "short", "int" -> m.group(3) != null ? "float" : "int";
			default -> throw new IllegalArgumentException(format);
		};
		// A three-component integer attribute (the DH terrain's ushort3 position) keeps three components. The fault was measured in the
		// game (label 2026-11-16-e7-dh14): a uint4 input over a three-component integer format read the position as zero, while the
		// one-component meta attribute read correctly. Floats keep the padding to four (a float3 attribute reads with w = 1).
		boolean integer = type.equals("uint") || type.equals("int");
		return n == 1 ? type : type + (n == 3 && !integer ? 4 : n);
	}

	private static int componentsOf(String format) {
		Matcher m = FORMAT.matcher(format);
		if (!m.matches()) {
			throw new IllegalArgumentException("unknown vertex format " + format);
		}
		int n = Integer.parseInt(m.group(2));
		// The same rule as attributeArgType: a float three-component attribute is read as four (Metal fills w); an integer one keeps its
		// three components, since a four-component read of a three-component integer input is refused by Metal.
		boolean integer = m.group(3) == null && !m.group(1).equals("float") && !m.group(1).equals("half");
		return n == 3 && !integer ? 4 : n;
	}

	/** The GLSL value of an attribute of {@code comps} components, widened or narrowed to the declared type. */
	private static String castAttribute(String arg, int comps, GlslType type) {
		if (type instanceof VectorType v) {
			String target = MslTypes.vector(v);
			if (comps == v.size()) {
				return target + "(" + arg + ")";
			}
			if (comps > v.size()) {
				return target + "((" + arg + ")." + "xyzw".substring(0, v.size()) + ")";
			}
			// A missing component takes GL's default: y and z are zero and w is one (a float attribute gives (x, 0, 0, 1)).
			StringBuilder fill = new StringBuilder(arg);
			for (int c = comps; c < v.size(); c++) {
				fill.append(c == 3 ? ", 1.0f" : ", 0.0f");
			}
			return target + "(" + fill + ")";
		}
		return MslTypes.value(type) + "(" + arg + ")";
	}

	/**
	 * The GLSL value of an attribute the layout does not provide, typed as the attribute: position w = 1, colour
	 * (1, 1, 1, 1), texture coordinates (0, 0, 0, 1). A scalar or integer attribute is zero.
	 */
	private static String defaultAttribute(Semantic s, GlslType type) {
		if (!(type instanceof VectorType v)) {
			return MslTypes.value(type) + "{}";
		}
		String value = s == Semantic.COLOR ? "float4(1.0f)" : "float4(0.0f, 0.0f, 0.0f, 1.0f)";
		return castAttribute(value, 4, v);
	}

	/**
	 * The draw's colour modulator, which the compatibility built-in {@code gl_Color} multiplies into the vertex colour of a
	 * gbuffers draw: the QsDraw block's {@code colorModulator} (ProgramRequests.DRAW_BLOCK), or null when it does not apply.
	 * <p>Decision: only {@code gl_Color} takes it. The game's fixed-function colour is the vertex colour times ColorModulator
	 * (26.3 assets/minecraft/shaders/core: the entity, particle, position_color, position_tex_color, sky, block and item
	 * fragment shaders multiply it in). The OptiFine doc (OptiFineDoc/doc/shaders.txt, raw.githubusercontent.com/sp614x/
	 * optifine/master) lists {@code in vec4 vaColor} as the colour and {@code uniform vec4 colorModulator} as a separate
	 * uniform (1.17+), and defines no gl_Color for the sky, basic or textured programs. So a core-profile pack's vaColor
	 * stays the raw vertex colour, and the pack multiplies colorModulator itself. The clouds never reach it (their colour is
	 * the prologue's CloudColor, and clouds.fsh reads no ColorModulator).
	 */
	private String colorModulator(Semantic semantic, GlslType type) {
		if (semantic != Semantic.COLOR || request.family() != ProgramRequest.Family.GBUFFERS) {
			return null;
		}
		if (!(type instanceof VectorType v) || v.size() != 4 || v.componentKind() != ScalarKind.FLOAT) {
			return null;
		}
		return servedField("colorModulator");
	}

	/**
	 * The colour with the modulator multiplied in; with no modulator the colour unchanged. The colour is always a vector
	 * constructor call (castAttribute), and it is not parenthesised: clang rejects {@code (float4(float4(a))) * m} with a
	 * stage input {@code a} ("indirection requires pointer operand"), while {@code float4(float4(a)) * m} compiles
	 * (measured on the Metal compiler, 2026-11-14).
	 */
	private static String modulated(String colour, String modulator) {
		return modulator == null ? colour : colour + " * " + modulator;
	}

	private static void addUnused() {
		// reserved for future built-ins
	}

	// ----- assembly ---------------------------------------------------------------------------------------------------

	/** The varyings this stage writes (vertex) or reads (fragment). */
	List<Varying> outVaryings() {
		return outVaryings;
	}

	List<Varying> inVaryings() {
		return inVaryings;
	}

	Map<Integer, Output> outputs() {
		return outputs;
	}

	List<String> unknownUniforms() {
		return unknownUniforms;
	}

	Map<String, String> servedFrom() {
		return servedFrom;
	}

	boolean discards() {
		return discards;
	}

	boolean barrier() {
		return barrier;
	}

	/** The variables the stage refers to, in first-use order. */
	Set<VariableSymbol> usedVariables() {
		return used;
	}

	/** The shared (threadgroup) variables of a compute stage that the stage uses. */
	Map<String, VariableSymbol> sharedVariables() {
		Map<String, VariableSymbol> out = new LinkedHashMap<>();
		for (Map.Entry<String, VariableSymbol> e : shared.entrySet()) {
			if (used.contains(e.getValue())) {
				out.put(e.getKey(), e.getValue());
			}
		}
		return out;
	}

	/** The bytes one group's shared variables take, each aligned to 16 (the layout of a spilled kernel's device buffer). */
	long sharedBytes() {
		long total = 0;
		for (VariableSymbol v : sharedVariables().values()) {
			total += align16(Layouts.mslSize(v.type(), false));
		}
		return total;
	}

	/** Whether the shared variables exceed Metal's 32 KB per group, so they move to a device buffer (E6; plan.md E6). */
	boolean spillsShared() {
		return sharedBytes() > THREADGROUP_LIMIT;
	}

	/** Metal's threadgroup memory per group, in bytes. */
	static final long THREADGROUP_LIMIT = 32768;

	static long align16(long bytes) {
		return (bytes + 15) / 16 * 16;
	}

	/** The blocks this stage binds, in the order they were registered. */
	List<Block> blocksUsed() {
		List<Block> out = new ArrayList<>();
		String flagBlock = perVertexFlagBlock();
		for (Block b : blocks.values()) {
			if (usedBlocks.contains(b.key()) || blockReferenced(b) || b.key().equals(flagBlock)) {
				out.add(b);
			}
		}
		return out;
	}

	private boolean blockReferenced(Block b) {
		for (Map.Entry<VariableSymbol, String> e : refBlock.entrySet()) {
			if (e.getValue().equals(b.key()) && used.contains(e.getKey())) {
				return true;
			}
		}
		return false;
	}

	// ----- per-vertex ids (ProgramRequest.PerVertexIds, plan.md E4) ---------------------------------------------------

	/** The type this stage declared one per-vertex id uniform with, or null when it did not declare it. */
	GlslType perVertexIdType(String name) {
		return perVertexIds.get(name);
	}

	/**
	 * Binds one id name on the vertex side: the buffer read fills the member, and the value reaches the fragment stage
	 * as a flat varying when the fragment reads it. Called for every name either stage declared, so a name only the
	 * fragment declared still reads the buffer here.
	 */
	void perVertexId(String name, GlslType type, boolean toFragment) {
		perVertexIds.putIfAbsent(name, type);
		if (!members.containsKey(member(name))) {
			members.put(member(name), declare(type, member(name)));
		}
		// A draw with no records reads record 0 of the defaults buffer (ProgramRequest.PerVertexIds).
		String index = "qs_vid";
		String flagBlock = perVertexFlagBlock();
		if (flagBlock != null) {
			index = "((*" + blocks.get(flagBlock).pointer() + ")." + member(request.perVertexIds().defaultsMember())
				+ " != 0 ? 0u : qs_vid)";
		}
		String read = "qs_ids[" + index + "].qs_" + name;
		if (!(type instanceof ScalarType scalar && scalar.kind() == ScalarKind.INT)) {
			read = MslTypes.value(type) + "(" + read + ")";
		}
		bind.append("\t\t").append(member(name)).append(" = ").append(read).append(";\n");
		if (toFragment) {
			outVaryings.add(new Varying(name, type, "flat", member(name)));
		}
	}

	/** The fragment side of one id name: it arrives as a flat varying the entry copies into the stage's member. */
	void perVertexIdIn(String name, GlslType type) {
		inVaryings.add(new Varying(name, type, "flat", member(name)));
	}

	/** Whether this stage serves any per-vertex id name (the vertex side decides the entry's buffer argument). */
	boolean servesPerVertexIds() {
		return !perVertexIds.isEmpty();
	}

	/**
	 * The engine block whose defaults flag selects record 0 for a draw with no records ({@link ProgramRequest.PerVertexIds}),
	 * on the vertex stage that serves the ids; null when the request names none or the block is not in the schema.
	 */
	private String perVertexFlagBlock() {
		ProgramRequest.PerVertexIds ids = request.perVertexIds();
		if (stage != Stage.VERTEX || ids == null || ids.defaultsBlock() == null || ids.defaultsMember() == null
				|| !servesPerVertexIds() || !blocks.containsKey(ids.defaultsBlock())) {
			return null;
		}
		return ids.defaultsBlock();
	}

	// ----- entityColor from the overlay (ProgramRequest.OverlayColor, plan.md E4) ------------------------------------

	/** The type this stage declared entityColor with, or null when it did not declare it. */
	GlslType overlayColorType() {
		return overlayColorType;
	}

	/**
	 * Binds the overlay fetch on the vertex side: the texel the overlay attribute's UV1 coordinates select (clamped to the
	 * texture) fills the member with {@code (overlay.rgb, 1 - overlay.a)} while the overlay blends ({@code a < 1}: the red
	 * hurt rows, the white flash), and with {@code (0, 0, 0, 0)} while it does not, as Iris reads a draw with no flash
	 * (measured with mcms-entity, 2026-11-16; the vanilla overlay texel there is opaque white). The value reaches the
	 * fragment stage as a flat varying when the fragment reads it (ProgramRequest.OverlayColor; the vanilla entity.vsh
	 * fetches {@code texelFetch(Sampler1, UV1, 0)}, engine-entity-draws.md section 2). Called when either stage declared
	 * entityColor, so a name only the fragment declared still fetches here.
	 */
	void overlayColor(GlslType type, boolean toFragment) {
		overlayColorType = type;
		String name = member(OVERLAY_COLOR_UNIFORM);
		members.putIfAbsent(name, declare(type, name));
		Integer index = attributeIndex.get(Semantic.OVERLAY.name());
		VertexAttribute attribute = index == null ? null : request.vertexLayout().attributes().get(index);
		String value;
		if (attribute == null) {
			// No overlay attribute in this pipeline's layout: entityColor is (0, 0, 0, 0) — a zero blend factor, so
			// the pack's mix (Iris rendering-uniforms page) leaves the colour unchanged. No texture is fetched.
			overlayFetch = false;
			value = "float4(0.0f)";
		} else {
			overlayFetch = true;
			String source = attribute.decode() != null ? "(" + attribute.decode() + ")" : "qs_a" + index;
			bind.append("\t\tuint2 qs_ovlm = uint2(qs_ovl.get_width() - 1u, qs_ovl.get_height() - 1u);\n");
			bind.append("\t\tfloat4 qs_ovlc = qs_ovl.read(min(uint2(int2(").append(source).append(".xy)), qs_ovlm), 0);\n");
			value = "(qs_ovlc.a < 1.0f ? float4(qs_ovlc.rgb, 1.0f - qs_ovlc.a) : float4(0.0f))";
		}
		if (!(type instanceof VectorType vector) || vector.size() != 4 || vector.componentKind() != ScalarKind.FLOAT) {
			value = MslTypes.value(type) + "(" + value + ")";
		}
		bind.append("\t\t").append(name).append(" = ").append(value).append(";\n");
		if (toFragment) {
			outVaryings.add(new Varying(OVERLAY_COLOR_UNIFORM, type, "flat", name));
		}
	}

	/** The fragment side of entityColor: it arrives as a flat varying the entry copies into the stage's member. */
	void overlayColorIn(GlslType type) {
		overlayColorType = type;
		inVaryings.add(new Varying(OVERLAY_COLOR_UNIFORM, type, "flat", member(OVERLAY_COLOR_UNIFORM)));
	}

	/** Whether this stage serves entityColor from the overlay (the vertex side decides the entry's texture argument). */
	boolean servesOverlayColor() {
		return overlayColorType != null;
	}

	/** Whether the overlay fetch reads a texture (false when the layout carries no overlay attribute). */
	boolean overlayFetch() {
		return overlayFetch;
	}

	private ResourceAccess access;

	/** How this stage reads and writes its images and blocks (see ResourceAccess), from its own uses. */
	ResourceAccess access() {
		if (access == null) {
			access = ResourceAccess.scan(unit, sema);
		}
		return access;
	}

	/** Texture, sampler and image resources the stage uses, with their bindings. */
	List<Resource> resources(List<TextureBinding> textures, List<ImageBinding> images, List<String> errors) {
		List<Resource> out = new ArrayList<>();
		Map<String, Integer> stageSamplers = new LinkedHashMap<>();
		for (VariableSymbol v : used) {
			if (!v.quals().isUniform()) {
				continue; // parameters and locals of sampler type are passed in, not bound
			}
			if (v.type() instanceof SamplerType st) {
				TextureBinding b = null;
				for (TextureBinding t : textures) {
					if (t.name().equals(v.name())) {
						b = t;
					}
				}
				if (b == null) {
					errors.add(v.name());
					continue;
				}
				out.add(new Resource("texture", v.name(), MslTypes.texture(st), b.slot(), member(v.name()), "read"));
				// A texel buffer is a Metal texture_buffer: read by index, no sampler exists for it (TextureCalls).
				if (b.sampler() == null && b.kind() != ProgramRequest.TextureKind.BUFFER) {
					// The game's sampler takes the stage's own next index (0, 1, ... without gaps): a stage declares only the
					// samplers it samples through, and its index is the one the pipeline binds there (Reflection.samplers).
					int index = stageSamplers.computeIfAbsent(v.name(), n -> stageSamplers.size());
					out.add(new Resource("sampler", v.name(), "sampler", index, "smp_" + member(v.name()), "read"));
				}
			}
			if (v.type() instanceof ImageType it) {
				ImageBinding b = null;
				for (ImageBinding i : images) {
					if (i.name().equals(v.name())) {
						b = i;
					}
				}
				if (b == null) {
					errors.add(v.name());
					continue;
				}
				out.add(new Resource("image", v.name(),
					MslTypes.image(it, v.quals().imageFormat(), access().accessOf(v.name(), "read_write")), b.slot(),
					member(v.name()), access().accessOf(v.name(), "read_write")));
			}
		}
		return out;
	}

	/**
	 * The context struct: its members (blocks, resources, globals, built-ins, the entry's inputs), the methods, the
	 * global initializer, {@code qs_bind} and {@code qs_result}.
	 */
	String contextStruct(String resultBody, String resultType, List<Resource> resources) {
		StringBuilder sb = new StringBuilder("struct ").append(ctxName).append(" {\n");
		for (Block b : blocksUsed()) {
			String space = b.buffer() ? "device " : "constant ";
			sb.append('\t').append(space).append(MslTypes.structName(b.struct())).append(" *").append(b.pointer())
				.append(";\n");
		}
		for (Resource r : resources) {
			sb.append('\t').append(r.mslType()).append(' ').append(r.member()).append(";\n");
		}
		for (Map.Entry<String, VariableSymbol> e : sharedVariables().entrySet()) {
			sb.append(spillsShared() ? "\tdevice " : "\tthreadgroup ").append(sharedType(e.getValue())).append(" *")
				.append(member(e.getKey())).append(";\n");
		}
		for (String m : members.values()) {
			sb.append('\t').append(m).append(";\n");
		}
		for (VariableSymbol v : sema.usedBuiltins().values()) {
			if (used.contains(v) && !members.containsKey(v.name()) && !isEntryBuiltin(v.name())) {
				sb.append('\t').append(declare(v.type(), v.name())).append(";\n");
			}
		}
		if (stage == Stage.VERTEX) {
			sb.append("\tuint qs_vid{};\n\tuint qs_iid{};\n");
			if (servesPerVertexIds()) {
				sb.append("\tconst device QsPerVertexIds *qs_ids;\n");
			}
			if (overlayFetch()) {
				sb.append("\ttexture2d<float> qs_ovl{};\n");
			}
			for (int i = 0; i < request.vertexLayout().attributes().size(); i++) {
				VertexAttribute a = request.vertexLayout().attributes().get(i);
				sb.append("\t").append(attributeArgType(a.format())).append(" qs_a").append(i).append("{};\n");
			}
			// 2026-11-14 (E4, ADR 0007): the region and globals blocks the position prologue reads.
			ProgramRequest.VertexPrologue prologue = request.vertexLayout().prologue();
			if (prologue != null) {
				if (prologue.regionSlot() >= 0) {
					sb.append("\tconstant uchar *qs_region{};\n");
				}
				if (prologue.globalsSlot() >= 0) {
					sb.append("\tconstant uchar *qs_globals{};\n");
				}
			}
			// 2026-11-14 (E4): the game's clouds blocks the clouds prologue reads (ProgramRequest.CloudsPrologue).
			if (clouds()) {
				sb.append("\tconstant uchar *qs_cloudinfo{};\n");
				sb.append("\ttexture_buffer<int> qs_cloudfs{};\n");
			}
		}
		if (stage == Stage.FRAGMENT) {
			sb.append("\tfloat4 qs_pos{};\n\tbool qs_ff{};\n\tfloat2 qs_pc{};\n");
		}
		if (stage == Stage.COMPUTE) {
			sb.append("\tuint3 qs_gid{};\n\tuint3 qs_lid{};\n\tuint3 qs_wgid{};\n\tuint3 qs_ngroups{};\n\tuint qs_lidx{};\n");
		}
		sb.append("\n\tvoid init_globals() {\n").append(initGlobals).append("\t}\n\n");
		sb.append(methods);
		sb.append("\tvoid qs_bind() {\n").append(bind).append("\t}\n\n");
		sb.append("\t").append(resultType).append(" qs_result() {\n").append(resultBody).append("\t}\n");
		sb.append("};\n");
		return sb.toString();
	}

	/** The Metal type of a shared variable: atomic when an atomic function uses it (see Atomics). */
	private String sharedType(VariableSymbol v) {
		return program.atomics().isVariable(v) ? Atomics.valueOf(v.type()) : MslTypes.value(v.type());
	}

	/** True when the expression is a place that an atomic function uses (see Atomics). */
	boolean isAtomicRef(Expr e) {
		return program.atomics().isRef(e, sema);
	}

	private boolean isEntryBuiltin(String name) {
		return false;
	}

	/** The entry function of this stage, given the interface struct names and the varyings it copies in. */
	String entryFunction(String resultType, List<Resource> resources, Set<String> matchedKeys,
			List<ColorTarget> targets) {
		StringBuilder args = new StringBuilder();
		StringBuilder body = new StringBuilder();
		String entryName = switch (stage) {
			case VERTEX -> "qs_vs";
			case FRAGMENT -> "qs_fs";
			case COMPUTE -> "qs_cs";
		};
		String kw = switch (stage) {
			case VERTEX -> "vertex";
			case FRAGMENT -> "fragment";
			case COMPUTE -> "kernel";
		};
		switch (stage) {
			case VERTEX -> {
				args.append("Vs_in qs_in [[stage_in]], uint qs_vid [[vertex_id]], uint qs_iid [[instance_id]]");
				List<VertexAttribute> attrs = request.vertexLayout().attributes();
				for (int i = 0; i < attrs.size(); i++) {
					body.append("\tc.qs_a").append(i).append(" = qs_in.qs_a").append(i).append(";\n");
				}
				body.append("\tc.qs_vid = qs_vid;\n\tc.qs_iid = qs_iid;\n");
				// 2026-11-14 (E4, ADR 0007): the prologue's blocks, at the slots the request named.
				ProgramRequest.VertexPrologue prologue = request.vertexLayout().prologue();
				if (prologue != null) {
					if (prologue.regionSlot() >= 0) {
						args.append(", constant uchar* qs_region [[buffer(").append(prologue.regionSlot()).append(")]]");
						body.append("\tc.qs_region = qs_region;\n");
					}
					if (prologue.globalsSlot() >= 0) {
						args.append(", constant uchar* qs_globals [[buffer(").append(prologue.globalsSlot()).append(")]]");
						body.append("\tc.qs_globals = qs_globals;\n");
					}
				}
				// 2026-11-14 (E4): the clouds prologue's blocks, at the slots the request named.
				if (clouds()) {
					args.append(", constant uchar* qs_cloudinfo [[buffer(").append(request.cloudsPrologue().infoSlot())
						.append(")]]");
					args.append(", texture_buffer<int> qs_cloudfs [[texture(").append(request.cloudsPrologue().facesSlot())
						.append(")]]");
					body.append("\tc.qs_cloudinfo = qs_cloudinfo;\n\tc.qs_cloudfs = qs_cloudfs;\n");
				}
			}
			case FRAGMENT -> {
				String stageIn = matchedKeys.isEmpty() ? "" : "Fs_in qs_in [[stage_in]], ";
				args.append(stageIn).append("float4 qs_pos [[position]], bool qs_ff [[front_facing]],"
					+ " float2 qs_pc [[point_coord]]");
				body.append("\tc.qs_pos = qs_pos;\n\tc.qs_ff = qs_ff;\n\tc.qs_pc = qs_pc;\n");
				for (Varying v : inVaryings) {
					if (matchedKeys.contains(v.key())) {
						body.append("\tc.").append(v.member()).append(" = qs_in.").append(v.field()).append(";\n");
					}
				}
			}
			case COMPUTE -> {
				args.append("uint3 qs_gid [[thread_position_in_grid]], uint3 qs_lid [[thread_position_in_threadgroup]],"
					+ " uint3 qs_wgid [[threadgroup_position_in_grid]], uint3 qs_ngroups [[threadgroups_per_grid]],"
					+ " uint qs_lidx [[thread_index_in_threadgroup]]");
				if (spillsShared()) {
					// The spill buffer binds at the one fixed spill slot, which no pack block takes (ProgramRequest).
					args.append(", device uchar *qs_spill [[buffer(").append(ProgramRequest.SHARED_SPILL_SLOT).append(")]]");
				}
				body.append("\tc.qs_gid = qs_gid;\n\tc.qs_lid = qs_lid;\n\tc.qs_wgid = qs_wgid;\n"
					+ "\tc.qs_ngroups = qs_ngroups;\n\tc.qs_lidx = qs_lidx;\n");
			}
		}
		if (stage == Stage.FRAGMENT && !targets.isEmpty()) {
			// outputs are written by qs_result
		}
		for (Resource r : resources) {
			if (args.length() > 0) {
				args.append(", ");
			}
			String argName = "arg_" + r.member();
			switch (r.kind()) {
				case "texture" -> args.append(r.mslType()).append(' ').append(argName).append(" [[texture(")
					.append(r.slot()).append(")]]");
				case "image" -> args.append(r.mslType()).append(' ').append(argName).append(" [[texture(")
					.append(r.slot()).append(")]]");
				case "sampler" -> args.append("sampler ").append(argName).append(" [[sampler(").append(r.slot())
					.append(")]]");
				default -> throw new IllegalStateException("resource kind " + r.kind());
			}
			body.append("\tc.").append(r.member()).append(" = ").append(argName).append(";\n");
		}
		for (Block b : blocksUsed()) {
			if (args.length() > 0) {
				args.append(", ");
			}
			String space = b.buffer() ? "device " : "constant ";
			String argName = "arg_" + b.pointer();
			args.append(space).append(MslTypes.structName(b.struct())).append(" *").append(argName)
				.append(" [[buffer(").append(b.slot()).append(")]]");
			body.append("\tc.").append(b.pointer()).append(" = ").append(argName).append(";\n");
		}
		if (stage == Stage.VERTEX && servesPerVertexIds()) {
			args.append(", ");
			args.append("const device QsPerVertexIds *arg_qsid [[buffer(").append(request.perVertexIds().slot())
				.append(")]]");
			body.append("\tc.qs_ids = arg_qsid;\n");
		}
		if (stage == Stage.VERTEX && overlayFetch()) {
			args.append(", ");
			args.append("texture2d<float> arg_ovl [[texture(").append(request.overlayColor().textureSlot())
				.append(")]]");
			body.append("\tc.qs_ovl = arg_ovl;\n");
		}
		StringBuilder pre = new StringBuilder();
		if (stage == Stage.COMPUTE) {
			if (spillsShared()) {
				pre.append("\tuint qs_grp = qs_wgid.x + qs_wgid.y * qs_ngroups.x + qs_wgid.z * qs_ngroups.x * qs_ngroups.y;\n");
			}
			long offset = 0;
			for (Map.Entry<String, VariableSymbol> e : sharedVariables().entrySet()) {
				VariableSymbol v = e.getValue();
				if (spillsShared()) {
					// Each kernel has one private device buffer (EngineFrame grows it to the dispatch's need, up to its cap). It
					// holds every group's arrays: group g's copy starts at g times the group's bytes, and each variable at its
					// aligned offset within it.
					body.append("\tc.").append(member(e.getKey())).append(" = (device ").append(sharedType(v))
						.append(" *)(qs_spill + qs_grp * ").append(sharedBytes()).append(" + ").append(offset).append(");\n");
					offset += align16(Layouts.mslSize(v.type(), false));
				} else {
					pre.append("\tthreadgroup ").append(sharedType(v)).append(" qs_shared_").append(e.getKey()).append(";\n");
					body.append("\tc.").append(member(e.getKey())).append(" = &qs_shared_").append(e.getKey()).append(";\n");
				}
			}
		}
		StringBuilder fn = new StringBuilder();
		fn.append(kw).append(' ').append(resultType).append(' ').append(entryName).append('(').append(args)
			.append(") {\n");
		fn.append(pre);
		fn.append("\t").append(ctxName).append(" c{};\n");
		fn.append(body);
		fn.append("\tc.qs_bind();\n\tc.init_globals();\n\tc.u_main();\n");
		if (stage == Stage.COMPUTE) {
			fn.append("}\n");
		} else {
			fn.append("\treturn c.qs_result();\n}\n");
		}
		return fn.toString();
	}

	String entryName() {
		return switch (stage) {
			case VERTEX -> "qs_vs";
			case FRAGMENT -> "qs_fs";
			case COMPUTE -> "qs_cs";
		};
	}
}
