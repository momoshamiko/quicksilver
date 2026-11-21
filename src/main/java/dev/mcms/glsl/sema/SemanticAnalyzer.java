// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.sema;

import dev.mcms.glsl.ast.ArrayDim;
import dev.mcms.glsl.ast.AssignExpr;
import dev.mcms.glsl.ast.AssignOp;
import dev.mcms.glsl.ast.BinaryExpr;
import dev.mcms.glsl.ast.BinaryOp;
import dev.mcms.glsl.ast.BlockStmt;
import dev.mcms.glsl.ast.BoolLiteral;
import dev.mcms.glsl.ast.BreakStmt;
import dev.mcms.glsl.ast.BuiltinTypeSpec;
import dev.mcms.glsl.ast.CallExpr;
import dev.mcms.glsl.ast.CaseStmt;
import dev.mcms.glsl.ast.Callee;
import dev.mcms.glsl.ast.Cond;
import dev.mcms.glsl.ast.ContinueStmt;
import dev.mcms.glsl.ast.Decl;
import dev.mcms.glsl.ast.DeclCond;
import dev.mcms.glsl.ast.DeclStmt;
import dev.mcms.glsl.ast.Declarator;
import dev.mcms.glsl.ast.DefaultStmt;
import dev.mcms.glsl.ast.DiscardStmt;
import dev.mcms.glsl.ast.DoWhileStmt;
import dev.mcms.glsl.ast.DoubleLiteral;
import dev.mcms.glsl.ast.EmptyStmt;
import dev.mcms.glsl.ast.Expr;
import dev.mcms.glsl.ast.ExprCond;
import dev.mcms.glsl.ast.ExprStmt;
import dev.mcms.glsl.ast.ExtensionDirective;
import dev.mcms.glsl.ast.FieldExpr;
import dev.mcms.glsl.ast.FloatLiteral;
import dev.mcms.glsl.ast.ForStmt;
import dev.mcms.glsl.ast.FunctionDefinition;
import dev.mcms.glsl.ast.FunctionPrototype;
import dev.mcms.glsl.ast.GlslNode;
import dev.mcms.glsl.ast.Ident;
import dev.mcms.glsl.ast.IfStmt;
import dev.mcms.glsl.ast.IndexExpr;
import dev.mcms.glsl.ast.InitializerList;
import dev.mcms.glsl.ast.IntLiteral;
import dev.mcms.glsl.ast.InterfaceBlock;
import dev.mcms.glsl.ast.InterpQual;
import dev.mcms.glsl.ast.LayoutItem;
import dev.mcms.glsl.ast.LayoutQual;
import dev.mcms.glsl.ast.MemoryQual;
import dev.mcms.glsl.ast.MethodCallee;
import dev.mcms.glsl.ast.NameCallee;
import dev.mcms.glsl.ast.NameTypeSpec;
import dev.mcms.glsl.ast.Param;
import dev.mcms.glsl.ast.Pos;
import dev.mcms.glsl.ast.PostfixExpr;
import dev.mcms.glsl.ast.PrecisionDecl;
import dev.mcms.glsl.ast.PrecisionQual;
import dev.mcms.glsl.ast.PreciseQual;
import dev.mcms.glsl.ast.Qualifier;
import dev.mcms.glsl.ast.QualifierDecl;
import dev.mcms.glsl.ast.ReturnStmt;
import dev.mcms.glsl.ast.SequenceExpr;
import dev.mcms.glsl.ast.StorageKind;
import dev.mcms.glsl.ast.StorageQual;
import dev.mcms.glsl.ast.StructDefSpec;
import dev.mcms.glsl.ast.StructMember;
import dev.mcms.glsl.ast.Stmt;
import dev.mcms.glsl.ast.SwitchStmt;
import dev.mcms.glsl.ast.TernaryExpr;
import dev.mcms.glsl.ast.Type;
import dev.mcms.glsl.ast.TypeCallee;
import dev.mcms.glsl.ast.TypeSpec;
import dev.mcms.glsl.ast.UIntLiteral;
import dev.mcms.glsl.ast.UnaryExpr;
import dev.mcms.glsl.ast.UnaryOp;
import dev.mcms.glsl.ast.Unit;
import dev.mcms.glsl.ast.VarDecl;
import dev.mcms.glsl.ast.WhileStmt;
import dev.mcms.glsl.ast.MemoryKind;
import dev.mcms.glsl.ast.InterpKind;
import dev.mcms.glsl.ast.PrecisionKind;
import dev.mcms.glsl.ast.PragmaDirective;
import dev.mcms.glsl.ast.VersionDirective;
import dev.mcms.glsl.ast.TypeSpec;
import dev.mcms.glsl.ast.PostfixOp;
import dev.mcms.glsl.ast.FloatLiteral;
import dev.mcms.glsl.types.ArrayType;
import dev.mcms.glsl.types.AtomicCounterType;
import dev.mcms.glsl.types.ErrorType;
import dev.mcms.glsl.types.GlslType;
import dev.mcms.glsl.types.ImageFormat;
import dev.mcms.glsl.types.ImageType;
import dev.mcms.glsl.types.MatrixType;
import dev.mcms.glsl.types.ScalarKind;
import dev.mcms.glsl.types.ScalarType;
import dev.mcms.glsl.types.StructField;
import dev.mcms.glsl.types.StructType;
import dev.mcms.glsl.types.Types;
import dev.mcms.glsl.types.VectorType;
import dev.mcms.glsl.types.VoidType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The semantic analysis pass. It resolves names against scopes and the built-in tables, types every expression,
 * resolves every call to one overload, records every implicit conversion where it happens, folds constants, and
 * checks l-values, swizzles, qualifiers and stage rules. The rules are the GLSL specifications' (1.10 to 4.60)
 * for the unit's version; accepted driver leniencies are warnings ({@code SemaOptions.lenient}).
 */
public final class SemanticAnalyzer {

	/** The typed view of one expression. */
	record ExprInfo(GlslType type, boolean lvalue, ConstValue value) {
		static final ExprInfo ERROR = new ExprInfo(ErrorType.ERROR, false, null);
	}

	/** One overload candidate: a user function or a built-in signature, with its parameters. */
	private record Candidate(List<FunctionOverload.Parameter> params, GlslType result, FunctionOverload user,
			BuiltinFunction builtin) {
	}

	private final SemaOptions options;
	private final Stage stage;
	private final int version;
	private final boolean compatibility;
	private final Map<String, String> extensions = new HashMap<>();
	private final List<SemaDiagnostic> diagnostics = new ArrayList<>();
	private final Scope globals = new Scope(null);
	private Scope scope = globals;
	private final IdentityHashMap<Expr, ExprInfo> exprs = new IdentityHashMap<>();
	private final IdentityHashMap<CallExpr, CallResolution> calls = new IdentityHashMap<>();
	private final IdentityHashMap<Expr, Symbol> identSymbols = new IdentityHashMap<>();
	private final IdentityHashMap<Object, Object> declarations = new IdentityHashMap<>();
	private final IdentityHashMap<VariableSymbol, ConstValue> constValues = new IdentityHashMap<>();
	/** The variables some statement writes: assigned, incremented or passed to an out or inout parameter. */
	private final Set<VariableSymbol> written = Collections.newSetFromMap(new IdentityHashMap<>());
	private final List<Conversion> conversions = new ArrayList<>();
	private final List<VariableSymbol> globalVars = new ArrayList<>();
	private final List<FunctionOverload> functions = new ArrayList<>();
	private final Map<String, VariableSymbol> usedBuiltins = new LinkedHashMap<>();
	private final List<StructType> structs = new ArrayList<>();
	private final Map<String, ConstValue> localSize = new LinkedHashMap<>();
	private final Set<String> reported = new HashSet<>();
	private int nextStructId = 1;
	private GlslType returnType = VoidType.VOID;
	private boolean sawReturn;
	private FunctionOverload current;
	/** The user calls each function makes, keyed by signature, for the call-cycle check (GLSL forbids recursion). */
	private final Map<String, List<CallEdge>> callEdges = new HashMap<>();

	private record CallEdge(String callee, Pos pos) {
	}
	private int loopDepth;
	private int switchDepth;

	private SemanticAnalyzer(SemaOptions options, Unit unit) {
		this.options = options;
		this.stage = options.stage();
		int number = unit.version() == null ? 110 : unit.version().number();
		String declared = unit.version() == null ? null : unit.version().profile();
		this.version = number;
		this.compatibility = declared != null ? declared.equals("compatibility") : number < 150;
	}

	/** Analyzes one translation unit for {@code options.stage()}. */
	public static SemaResult analyze(Unit unit, SemaOptions options) {
		SemanticAnalyzer a = new SemanticAnalyzer(options, unit);
		for (GlslNode item : unit.itemsInOrder()) {
			switch (item) {
				case ExtensionDirective e -> a.extensions.put(e.name(), e.behavior());
				case Decl d -> a.globalDecl(d);
				case VersionDirective v -> {
				}
				case PragmaDirective p -> {
				}
				default -> {
				}
			}
		}
		a.resolveUserCalls();
		a.checkRecursion();
		a.checkMain(unit.pos());
		return a.result();
	}

	// --- diagnostics ---

	private void error(Pos pos, String code, String message) {
		diagnostics.add(SemaDiagnostic.error(pos, code, message));
	}

	private void warning(Pos pos, String code, String message) {
		diagnostics.add(SemaDiagnostic.warning(pos, code, message));
	}

	/** A driver leniency: a warning when accepted, an error in strict mode. */
	private void leniency(Pos pos, String code, String message) {
		if (options.lenient()) {
			warning(pos, code, message);
		} else {
			error(pos, code, message);
		}
	}

	private boolean extensionEnabled(String name) {
		String behavior = extensions.get(name);
		return behavior != null && !behavior.equals("disable");
	}

	private void checkMain(Pos pos) {
		Symbol s = globals.resolve("main");
		if (!(s instanceof FunctionGroupSymbol g)) {
			error(pos, "no-main", "no function main() in this program");
			return;
		}
		boolean ok = false;
		for (FunctionOverload o : g.overloads()) {
			if (o.params().isEmpty() && o.returnType().isVoid()) {
				ok = true;
				if (!o.defined()) {
					error(o.pos(), "no-main", "main() is declared but not defined");
				}
			}
		}
		if (!ok) {
			error(g.pos(), "main-signature", "main() must be declared as 'void main()'");
		}
	}

	// --- global and local declarations ---

	private void globalDecl(Decl d) {
		switch (d) {
			case FunctionPrototype p -> declareFunction(p, null);
			case FunctionDefinition f -> declareFunction(f.proto(), f.body());
			case VarDecl v -> varDecl(v, true);
			case InterfaceBlock b -> interfaceBlock(b);
			case PrecisionDecl p -> {
			}
			case QualifierDecl q -> qualifierDecl(q);
		}
	}

	private void localDecl(Decl d) {
		switch (d) {
			case VarDecl v -> varDecl(v, false);
			case PrecisionDecl p -> {
			}
			case FunctionPrototype p -> error(p.pos(), "nested-function",
					"functions cannot be declared inside another function");
			case FunctionDefinition f -> error(f.pos(), "nested-function",
					"functions cannot be defined inside another function");
			case InterfaceBlock b -> error(b.pos(), "block-local",
					"interface blocks are only allowed at global scope");
			case QualifierDecl q -> error(q.pos(), "qualifier-local",
					"qualifier-only declarations are only allowed at global scope");
		}
	}

	private void qualifierDecl(QualifierDecl q) {
		for (Qualifier qual : q.qualifiers()) {
			if (qual instanceof LayoutQual layout) {
				layoutItems(layout.items(), true);
			}
		}
		for (int i = 0; i < q.names().size(); i++) {
			String name = q.names().get(i);
			Pos pos = q.namePositions().get(i);
			if (globals.resolve(name) == null && !BuiltinVariables.isBuiltinName(name)) {
				error(pos, "unknown-identifier", "no declaration named '" + name + "' to qualify");
			}
		}
	}

	/** Folds layout values; a bare name maps to {@link Boolean#TRUE}. */
	private Map<String, Object> layoutItems(List<LayoutItem> items, boolean global) {
		Map<String, Object> values = new LinkedHashMap<>();
		for (LayoutItem item : items) {
			if (item.value() == null) {
				values.put(item.name(), Boolean.TRUE);
				continue;
			}
			ExprInfo info = expr(item.value());
			ConstValue value = info.value();
			if (info.type().isError()) {
				values.put(item.name(), Boolean.TRUE);
			} else if (value == null) {
				error(item.value().pos(), "layout-not-constant",
						"layout value '" + item.name() + "' must be a constant expression");
				values.put(item.name(), Boolean.TRUE);
			} else if (!value.isScalar() || !Types.isInteger(value.type())) {
				error(item.value().pos(), "layout-not-integer", "layout value '" + item.name() + "' must be an integer");
				values.put(item.name(), Boolean.TRUE);
			} else {
				values.put(item.name(), value);
				if (global && item.name().startsWith("local_size_")) {
					localSize.put(item.name(), value);
				}
			}
		}
		return values;
	}

	private TypeQuals quals(List<Qualifier> qualifiers, boolean global) {
		TypeQuals.Builder b = new TypeQuals.Builder();
		for (Qualifier q : qualifiers) {
			switch (q) {
				case StorageQual s -> {
					b.storage(s.kind());
					if ((s.kind() == StorageKind.ATTRIBUTE || s.kind() == StorageKind.VARYING)
							&& version >= 140 && !compatibility) {
						leniency(s.pos(), "legacy-storage",
								s.kind().word() + " is removed from GLSL core profiles; use in/out");
					}
				}
				case InterpQual i -> {
					if (version < 130) {
						leniency(i.pos(), "interp-qualifier", "interpolation qualifiers need GLSL 1.30; drivers accept"
								+ " them earlier");
					}
					b.interpolation(i.kind());
				}
				case MemoryQual m -> b.memory(m.kind());
				case PrecisionQual p -> b.precision(p.kind());
				case dev.mcms.glsl.ast.InvariantQual i -> b.invariant(true);
				case PreciseQual p -> b.precise(true);
				case LayoutQual l -> layoutItems(l.items(), global).forEach(b::layout);
			}
		}
		return b.build();
	}

	/** True when the variable may not be written by the program (GLSL 4.60 section 4.3). */
	private boolean readOnly(TypeQuals q, GlslType type) {
		if (q.isConst() || q.storage().contains(StorageKind.IN) || q.storage().contains(StorageKind.ATTRIBUTE)) {
			return true;
		}
		if (q.storage().contains(StorageKind.UNIFORM) && !(type instanceof ImageType)) {
			return true;
		}
		if (q.storage().contains(StorageKind.VARYING)) {
			return stage == Stage.FRAGMENT;
		}
		return q.memory().contains(MemoryKind.READONLY);
	}

	private void declare(String name, Symbol symbol, Pos pos) {
		if (scope.declare(symbol) != null) {
			error(pos, "redeclaration", "'" + name + "' is already declared in this scope");
		}
	}

	private void varDecl(VarDecl decl, boolean global) {
		if (decl.declarators().isEmpty()) {
			if (decl.type().spec() instanceof StructDefSpec def) {
				structDef(def, global);
			} else {
				error(decl.pos(), "declaration", "declaration declares nothing");
			}
			return;
		}
		GlslType base = baseType(decl.type().spec(), decl.pos(), global);
		TypeQuals quals = quals(decl.type().qualifiers(), global);
		for (Declarator d : decl.declarators()) {
			GlslType t = dims(base, decl.type().dims(), d.dims());
			ConstValue value = null;
			if (d.initializer() instanceof InitializerList list) {
				if (t instanceof ArrayType a && a.size() == null) {
					t = new ArrayType(a.element(), list.items().size());
				}
				value = initializerList(list, t);
			} else if (d.initializer() != null) {
				ExprInfo info = expr(d.initializer());
				if (t instanceof ArrayType a && a.size() == null && info.type() instanceof ArrayType from) {
					t = from;
				}
				if (!info.type().isError()) {
					if (!assignable(info.type(), t)) {
						error(d.pos(), "initializer-type",
								"cannot initialize '" + t + "' from '" + info.type() + "'");
					} else {
						recordIfDifferent(d.initializer(), info.type(), t, Conversion.Site.INITIALIZER);
						value = info.value() == null ? null : info.value().implicitTo(t);
					}
				}
			}
			if (t instanceof ArrayType a && a.size() == null && d.initializer() == null && !quals.isBuffer()
					&& !BuiltinVariables.isBuiltinName(d.name())) {
				error(d.pos(), "array-size-missing", "array '" + d.name() + "' needs a size or an initializer");
			}
			if (d.initializer() != null && (quals.isUniform() || quals.isInput() || quals.isOutput()
					|| quals.isBuffer()) && !quals.isConst()) {
				leniency(d.pos(), "interface-initializer", "'" + d.name()
						+ "' is an interface variable; drivers ignore its initializer and the game sets its value");
			}
			if (quals.isConst() && d.initializer() == null) {
				error(d.pos(), "const-initializer", "const '" + d.name() + "' must be initialized");
			} else if (quals.isConst() && value == null && !(d.initializer() instanceof InitializerList)
					&& !t.isError() && d.initializer() != null && !exprs.get(d.initializer()).type().isError()) {
				leniency(d.pos(), "const-nonconstant", "const '" + d.name()
						+ "' has a run-time initializer; drivers accept it and treat it as a variable");
			}
			VariableSymbol symbol = new VariableSymbol(d.name(), t, quals, false, readOnly(quals, t), d.pos(), d);
			declare(d.name(), symbol, d.pos());
			declarations.put(d, symbol);
			if (global) {
				globalVars.add(symbol);
			}
			if (quals.isConst() && value != null) {
				constValues.put(symbol, value);
			}
		}
	}

	private void interfaceBlock(InterfaceBlock b) {
		TypeQuals quals = quals(b.qualifiers(), true);
		boolean perVertex = "gl_PerVertex".equals(b.name());
		List<StructField> fields = new ArrayList<>();
		for (int m = 0; m < b.members().size(); m++) {
			StructMember member = b.members().get(m);
			GlslType base = baseType(member.type().spec(), member.pos(), true);
			for (Declarator d : member.declarators()) {
				GlslType t = dims(base, member.type().dims(), d.dims());
				boolean unsized = t instanceof ArrayType a && a.size() == null;
				if (unsized && !quals.isBuffer() && !perVertex) {
					error(d.pos(), "block-unsized-member", "an unsized array member needs a buffer block");
				}
				if (unsized && (m != b.members().size() - 1 || !d.equals(member.declarators().getLast()))) {
					error(d.pos(), "block-unsized-member", "only the last member of a block can be unsized");
				}
				fields.add(new StructField(d.name(), t));
			}
		}
		StructType struct = new StructType(b.name(), fields, nextStructId++);
		structs.add(struct);
		declarations.put(b, struct);
		if (b.instance() != null) {
			GlslType t = dims(struct, b.instanceDims(), List.of());
			VariableSymbol symbol = new VariableSymbol(b.instance(), t, quals, false, readOnly(quals, t),
					b.instancePos(), b);
			declare(b.instance(), symbol, b.instancePos());
			globalVars.add(symbol);
		} else if (!perVertex) {
			for (StructField f : fields) {
				VariableSymbol symbol = new VariableSymbol(f.name(), f.type(), quals, false,
						readOnly(quals, f.type()), b.pos(), b);
				declare(f.name(), symbol, b.pos());
				globalVars.add(symbol);
			}
		}
	}

	private StructType structDef(StructDefSpec def, boolean global) {
		List<StructField> fields = new ArrayList<>();
		for (StructMember m : def.members()) {
			GlslType base = baseType(m.type().spec(), m.pos(), global);
			for (Declarator d : m.declarators()) {
				GlslType t = dims(base, m.type().dims(), d.dims());
				if (t instanceof ArrayType a && a.size() == null) {
					error(d.pos(), "unsized-struct-member",
							"a struct member cannot be an unsized array (only the last member of a buffer block)");
				}
				fields.add(new StructField(d.name(), t));
			}
		}
		StructType struct = new StructType(def.name(), fields, nextStructId++);
		structs.add(struct);
		declarations.put(def, struct);
		if (def.name() != null) {
			declare(def.name(), new StructSymbol(def.name(), struct, def.pos(), def), def.pos());
		}
		return struct;
	}

	/** The base type of a specifier; struct definitions declare their name in the current scope. */
	private GlslType baseType(TypeSpec spec, Pos pos, boolean global) {
		return switch (spec) {
			case StructDefSpec def -> structDef(def, global);
			case BuiltinTypeSpec b -> {
				GlslType t = Types.fromWord(b.name());
				yield t == null ? typeError(pos, "unknown-type", "'" + b.name() + "' is not a type") : t;
			}
			case NameTypeSpec n -> namedType(n, pos);
		};
	}

	private GlslType namedType(NameTypeSpec n, Pos pos) {
		Symbol s = scope.resolve(n.name());
		if (s instanceof StructSymbol st) {
			return st.struct();
		}
		if (s != null) {
			return typeError(pos, "not-a-type", "'" + n.name() + "' is not a type");
		}
		GlslType t = Types.fromWord(n.name());
		if (t != null) {
			boolean needsExtension = t instanceof ImageType || t instanceof AtomicCounterType;
			boolean enabled = extensionEnabled("GL_ARB_shader_image_load_store")
					|| extensionEnabled("GL_EXT_shader_image_load_store")
					|| extensionEnabled("GL_ARB_shader_atomic_counters");
			if (!needsExtension || enabled) {
				return t;
			}
			leniency(pos, "extension-type", "type '" + n.name() + "' needs an image or atomic counter extension");
			return t;
		}
		for (BuiltinVariables.Entry e : BuiltinVariables.byName(n.name())) {
			if (e.type() instanceof StructType st && e.kind() == null) {
				return st;
			}
		}
		return typeError(pos, "unknown-type", "unknown type '" + n.name() + "'");
	}

	private GlslType typeError(Pos pos, String code, String message) {
		error(pos, code, message);
		return ErrorType.ERROR;
	}

	/**
	 * Applies array dimensions to a base type. A dimension list is written outermost first ({@code a[2][3]} is an
	 * array of 2 arrays of 3), so the last dimension wraps the base first. Declarator dimensions are outside the
	 * type's own dimensions ({@code float[2] a[3]} is an array of 3 arrays of 2).
	 */
	private GlslType dims(GlslType base, List<ArrayDim> typeDims, List<ArrayDim> declDims) {
		return wrap(wrap(base, typeDims), declDims);
	}

	private GlslType wrap(GlslType base, List<ArrayDim> dims) {
		GlslType t = base;
		for (int i = dims.size() - 1; i >= 0; i--) {
			t = new ArrayType(t, arraySize(dims.get(i)));
		}
		return t;
	}

	private Integer arraySize(ArrayDim dim) {
		if (dim.length() == null) {
			return null;
		}
		ExprInfo info = expr(dim.length());
		if (info.type().isError()) {
			return 1;
		}
		ConstValue v = info.value();
		if (v == null) {
			error(dim.length().pos(), "array-size-constant", "array size must be a constant expression");
			return 1;
		}
		if (!v.isScalar() || !Types.isInteger(v.type())) {
			error(dim.length().pos(), "array-size-integer", "array size must be an integer");
			return 1;
		}
		if (v.integerValue() <= 0) {
			error(dim.length().pos(), "array-size-positive", "array size must be positive");
			return 1;
		}
		return (int) v.integerValue();
	}

	private void declareFunction(FunctionPrototype proto, BlockStmt body) {
		GlslType ret = dims(baseType(proto.returnType().spec(), proto.pos(), true), proto.returnType().dims(),
				List.of());
		if (ret instanceof ArrayType) {
			error(proto.pos(), "array-return", "functions cannot return arrays");
		}
		if (ret.containsOpaque()) {
			error(proto.pos(), "opaque-return", "functions cannot return opaque types");
		}
		Scope paramScope = new Scope(globals);
		List<FunctionOverload.Parameter> params = new ArrayList<>();
		List<VariableSymbol> paramSymbols = new ArrayList<>();
		for (Param p : proto.params()) {
			GlslType base = baseType(p.type().spec(), p.pos(), false);
			GlslType t = dims(base, p.type().dims(), p.dims());
			StorageKind direction = StorageKind.IN;
			boolean isConst = false;
			for (Qualifier q : p.type().qualifiers()) {
				if (q instanceof StorageQual s) {
					if (s.kind() == StorageKind.OUT || s.kind() == StorageKind.INOUT) {
						direction = s.kind();
					}
					if (s.kind() == StorageKind.CONST) {
						isConst = true;
					}
				}
			}
			if (t.containsOpaque() && direction != StorageKind.IN) {
				error(p.pos(), "opaque-out", "opaque types can only be in parameters");
			}
			params.add(new FunctionOverload.Parameter(p.name(), t, direction, isConst));
			if (p.name() != null) {
				TypeQuals.Builder qb = new TypeQuals.Builder();
				qb.storage(direction);
				VariableSymbol symbol = new VariableSymbol(p.name(), t, qb.build(), false,
						isConst, p.namePos() != null ? p.namePos() : p.pos(), p);
				if (paramScope.declare(symbol) != null) {
					error(p.pos(), "redeclaration", "parameter '" + p.name() + "' is already declared");
				}
				declarations.put(p, symbol);
				paramSymbols.add(symbol);
			}
		}
		FunctionOverload overload = new FunctionOverload(proto.name(), ret, params, body != null, false,
				proto.namePos() != null ? proto.namePos() : proto.pos(), proto);
		overload = registerFunction(proto, overload, body != null);
		if (body == null) {
			return;
		}
		Scope savedScope = scope;
		GlslType savedReturn = returnType;
		boolean savedSaw = sawReturn;
		int savedLoops = loopDepth;
		int savedSwitch = switchDepth;
		FunctionOverload savedCurrent = current;
		scope = paramScope;
		returnType = ret;
		sawReturn = false;
		loopDepth = 0;
		switchDepth = 0;
		current = overload;
		block(body);
		if (!ret.isVoid() && !sawReturn) {
			error(proto.pos(), "no-return", "function '" + proto.name() + "' must return a value");
		}
		scope = savedScope;
		returnType = savedReturn;
		sawReturn = savedSaw;
		loopDepth = savedLoops;
		switchDepth = savedSwitch;
		current = savedCurrent;
	}

	/**
	 * Registers a function. A prototype and its definition, or a repeated prototype, are one overload: a definition
	 * completes the prototype's overload, so calls and recursion see one function (GLSL 4.60 section 6.1: multiple
	 * declarations, one definition).
	 */
	private FunctionOverload registerFunction(FunctionPrototype proto, FunctionOverload overload, boolean defined) {
		Symbol existing = globals.resolve(proto.name());
		if (existing == null) {
			globals.declare(new FunctionGroupSymbol(proto.name(), List.of(overload),
					proto.namePos() != null ? proto.namePos() : proto.pos()));
			functions.add(overload);
			declarations.put(proto, overload);
			return overload;
		}
		if (!(existing instanceof FunctionGroupSymbol g)) {
			error(proto.pos(), "redeclaration", "'" + proto.name() + "' is already declared as a variable or type");
			return overload;
		}
		FunctionOverload same = null;
		for (FunctionOverload o : g.overloads()) {
			if (sameParams(o, overload)) {
				same = o;
				if (!o.returnType().equals(overload.returnType())) {
					error(proto.pos(), "redeclaration", "'" + proto.name()
							+ "' is declared with a different return type");
				}
				if (o.defined() && defined) {
					error(proto.pos(), "redefinition", "'" + proto.name() + "' is defined twice");
				}
				break;
			}
		}
		if (same == null) {
			globals.replace(g.add(overload));
			functions.add(overload);
			declarations.put(proto, overload);
			return overload;
		}
		// the merged overload is the definition's: its declaration and position are the definition's prototype
		FunctionOverload merged = same.defined() || !defined ? same
				: new FunctionOverload(same.name(), same.returnType(), overload.params(), true, false, overload.pos(),
						overload.declaration());
		if (merged != same) {
			List<FunctionOverload> all = new ArrayList<>();
			for (FunctionOverload o : g.overloads()) {
				all.add(o == same ? merged : o);
			}
			globals.replace(new FunctionGroupSymbol(g.name(), all, g.pos()));
			int at = functions.indexOf(same);
			if (at >= 0) {
				functions.set(at, merged);
			}
			// every earlier prototype of this function now names the merged overload, not the replaced one
			FunctionOverload replaced = same;
			declarations.replaceAll((node, value) -> value == replaced ? merged : value);
		}
		declarations.put(proto, merged);
		return merged;
	}

	private boolean sameParams(FunctionOverload a, FunctionOverload b) {
		if (a.params().size() != b.params().size()) {
			return false;
		}
		for (int i = 0; i < a.params().size(); i++) {
			if (!a.params().get(i).type().equals(b.params().get(i).type())) {
				return false;
			}
		}
		return true;
	}

	// --- statements ---

	private void block(BlockStmt block) {
		scope = new Scope(scope);
		for (Stmt s : block.statements()) {
			stmt(s);
		}
		scope = scope.parent();
	}

	private void stmt(Stmt s) {
		switch (s) {
			case BlockStmt b -> block(b);
			case DeclStmt d -> localDecl(d.decl());
			case ExprStmt e -> expr(e.expr());
			case IfStmt i -> {
				condition(i.condition());
				stmt(i.thenStmt());
				if (i.elseStmt() != null) {
					stmt(i.elseStmt());
				}
			}
			case WhileStmt w -> {
				scope = new Scope(scope);
				condition(w.condition());
				loopDepth++;
				stmt(w.body());
				loopDepth--;
				scope = scope.parent();
			}
			case DoWhileStmt d -> {
				loopDepth++;
				stmt(d.body());
				loopDepth--;
				requireBool(expr(d.condition()), d.condition().pos(), "do-while condition");
			}
			case ForStmt f -> {
				scope = new Scope(scope);
				stmt(f.init());
				if (f.condition() != null) {
					condition(f.condition());
				}
				if (f.step() != null) {
					expr(f.step());
				}
				loopDepth++;
				stmt(f.body());
				loopDepth--;
				scope = scope.parent();
			}
			case SwitchStmt sw -> switchStmt(sw);
			case CaseStmt c -> error(c.pos(), "case-outside-switch", "case label outside a switch");
			case DefaultStmt d -> error(d.pos(), "case-outside-switch", "default label outside a switch");
			case BreakStmt b -> {
				if (loopDepth == 0 && switchDepth == 0) {
					error(b.pos(), "break-outside", "break is not inside a loop or switch");
				}
			}
			case ContinueStmt c -> {
				if (loopDepth == 0) {
					error(c.pos(), "continue-outside", "continue is not inside a loop");
				}
			}
			case ReturnStmt r -> returnStmt(r);
			case DiscardStmt d -> {
				if (stage != Stage.FRAGMENT) {
					error(d.pos(), "discard-stage", "discard is only allowed in fragment shaders");
				}
			}
			case EmptyStmt e -> {
			}
		}
	}

	private void switchStmt(SwitchStmt sw) {
		ExprInfo head = expr(sw.head());
		if (!head.type().isError() && !head.type().equals(ScalarType.INT) && !head.type().equals(ScalarType.UINT)) {
			error(sw.head().pos(), "switch-type", "switch needs an int or uint expression");
		}
		switchDepth++;
		scope = new Scope(scope);
		Set<Long> labels = new HashSet<>();
		boolean defaultSeen = false;
		List<Stmt> items = sw.body() instanceof BlockStmt b ? b.statements() : List.of(sw.body());
		for (Stmt item : items) {
			if (item instanceof CaseStmt c) {
				ExprInfo value = expr(c.value());
				if (value.type().isError()) {
					continue;
				}
				if (value.value() == null) {
					error(c.value().pos(), "case-constant", "case label must be a constant expression");
				} else if (!value.type().equals(head.type()) && !head.type().isError()) {
					if (Conversions.converts(value.type(), head.type())) {
						recordIfDifferent(c.value(), value.type(), head.type(), Conversion.Site.OPERAND);
					} else {
						error(c.value().pos(), "case-type", "case label type " + value.type()
								+ " does not match the switch type " + head.type());
					}
				}
				if (value.value() != null && value.value().isIntegral() && !labels.add(value.value().integerValue())) {
					error(c.value().pos(), "case-duplicate", "duplicate case label");
				}
			} else if (item instanceof DefaultStmt d) {
				if (defaultSeen) {
					error(d.pos(), "default-duplicate", "duplicate default label");
				}
				defaultSeen = true;
			} else {
				stmt(item);
			}
		}
		scope = scope.parent();
		switchDepth--;
	}

	private void returnStmt(ReturnStmt r) {
		sawReturn = true;
		if (r.value() == null) {
			if (!returnType.isVoid()) {
				error(r.pos(), "return-value", "this function must return a " + returnType);
			}
			return;
		}
		if (returnType.isVoid()) {
			error(r.pos(), "return-void", "a void function cannot return a value");
			expr(r.value());
			return;
		}
		ExprInfo info = expr(r.value());
		if (info.type().isError()) {
			return;
		}
		if (!assignable(info.type(), returnType)) {
			error(r.pos(), "return-type", "cannot return '" + info.type() + "' from a function returning "
					+ returnType);
		} else {
			recordIfDifferent(r.value(), info.type(), returnType, Conversion.Site.RETURN);
		}
	}

	private void condition(Cond c) {
		switch (c) {
			case ExprCond e -> requireBool(expr(e.expr()), e.expr().pos(), "condition");
			case DeclCond d -> {
				varDecl(d.decl(), false);
				Declarator only = d.decl().declarators().getFirst();
				if (declarations.get(only) instanceof VariableSymbol v && !v.type().equals(ScalarType.BOOL)) {
					error(only.pos(), "condition-type", "a declared condition must be bool");
				}
			}
		}
	}

	private void requireBool(ExprInfo info, Pos pos, String what) {
		if (!info.type().isError() && !info.type().equals(ScalarType.BOOL)) {
			error(pos, "condition-type", "the " + what + " must be a bool scalar, not " + info.type());
		}
	}

	// --- expressions ---

	private ExprInfo expr(Expr e) {
		ExprInfo info = switch (e) {
			case IntLiteral l -> {
				if (l.value() > 0xFFFFFFFFL) {
					error(l.pos(), "literal-range", "integer literal " + l.text() + " needs more than 32 bits");
				}
				yield info(e, ScalarType.INT, false, ConstValue.ofInt((int) l.value()));
			}
			case UIntLiteral l -> {
				if (l.exceedsUInt32()) {
					error(l.pos(), "literal-range", "integer literal " + l.text() + " needs more than 32 bits");
				}
				yield info(e, ScalarType.UINT, false, ConstValue.ofUint(l.value()));
			}
			case FloatLiteral l -> info(e, ScalarType.FLOAT, false, ConstValue.ofFloat(l.value()));
			case DoubleLiteral l -> info(e, ScalarType.DOUBLE, false, ConstValue.ofDouble(l.value()));
			case BoolLiteral l -> info(e, ScalarType.BOOL, false, ConstValue.of(l.value()));
			case Ident i -> ident(i);
			case UnaryExpr u -> unary(u);
			case PostfixExpr p -> postfix(p);
			case BinaryExpr b -> binary(b);
			case AssignExpr a -> assign(a);
			case TernaryExpr t -> ternary(t);
			case SequenceExpr s -> sequence(s);
			case CallExpr c -> call(c);
			case IndexExpr i -> index(i);
			case FieldExpr f -> field(f);
			case InitializerList l -> {
				error(l.pos(), "initializer-list-position",
						"a brace initializer is only allowed as the initializer of a declaration");
				yield ExprInfo.ERROR;
			}
		};
		exprs.put(e, info);
		return info;
	}

	private ExprInfo info(Expr e, GlslType type, boolean lvalue, ConstValue value) {
		return new ExprInfo(type, lvalue, value);
	}

	private ExprInfo ident(Ident id) {
		Symbol s = scope.resolve(id.name());
		if (s instanceof VariableSymbol v) {
			identSymbols.put(id, v);
			return info(id, v.type(), !v.readOnly(), constValues.get(v));
		}
		if (s != null) {
			error(id.pos(), "not-a-value", "'" + id.name() + "' is a "
					+ (s instanceof StructSymbol ? "type" : "function") + ", not a value");
			return ExprInfo.ERROR;
		}
		BuiltinVariables.Entry entry = builtinVariable(id);
		if (entry == null) {
			return ExprInfo.ERROR;
		}
		VariableSymbol symbol = builtinSymbol(entry);
		identSymbols.put(id, symbol);
		usedBuiltins.putIfAbsent(id.name(), symbol);
		return info(id, entry.type(), !entry.kind().readOnly, entry.value());
	}

	/** Chooses the built-in variable entry for this stage and version, or reports why none applies. */
	private BuiltinVariables.Entry builtinVariable(Ident id) {
		List<BuiltinVariables.Entry> entries = BuiltinVariables.byName(id.name()).stream()
				.filter(e -> e.kind() != null).toList();
		if (entries.isEmpty()) {
			BuiltinVariables.Entry game = BuiltinVariables.undeclaredGameName(id.name());
			if (game == null) {
				error(id.pos(), "unknown-identifier", "unknown identifier '" + id.name() + "'");
				return null;
			}
			leniency(id.pos(), "undeclared-game-name", "'" + id.name()
					+ "' is provided by the game and not declared in this program");
			entries = List.of(game);
		}
		List<BuiltinVariables.Entry> here = entries.stream()
				.filter(e -> e.stage() == null || e.stage() == stage).toList();
		if (here.isEmpty()) {
			error(id.pos(), "builtin-stage", "'" + id.name() + "' is not available in the " + stage.word() + " stage");
			return null;
		}
		for (BuiltinVariables.Entry e : here) {
			if (e.since() <= version) {
				if (e.compat() && !compatibility) {
					leniency(id.pos(), "compat-builtin", "'" + id.name()
							+ "' is a compatibility profile built-in; this unit uses the core profile");
				}
				return e;
			}
		}
		// drivers accept a later version's built-in in an older unit (gl_VertexID in a 1.20 unit)
		BuiltinVariables.Entry later = here.stream()
				.min(java.util.Comparator.comparingInt(BuiltinVariables.Entry::since)).orElseThrow();
		leniency(id.pos(), "later-builtin", "'" + id.name() + "' is a GLSL " + later.since()
				+ " built-in; drivers accept it in a GLSL " + version + " unit");
		return later;
	}

	private VariableSymbol builtinSymbol(BuiltinVariables.Entry entry) {
		TypeQuals.Builder b = new TypeQuals.Builder();
		b.storage(switch (entry.kind()) {
			case INPUT -> StorageKind.IN;
			case OUTPUT -> StorageKind.OUT;
			case UNIFORM -> StorageKind.UNIFORM;
			case CONST -> StorageKind.CONST;
		});
		return new VariableSymbol(entry.name(), entry.type(), b.build(), true,
				entry.kind() != BuiltinVariables.VarKind.OUTPUT, null, null);
	}

	private ExprInfo unary(UnaryExpr u) {
		ExprInfo operand = expr(u.operand());
		GlslType t = operand.type();
		if (t.isError()) {
			return ExprInfo.ERROR;
		}
		switch (u.op()) {
			case PLUS, MINUS -> {
				if (!Types.isNumeric(t)) {
					return typeFail(u.pos(), "operator " + word(u.op()) + " needs a numeric operand");
				}
				return info(u, t, false, operand.value() == null ? null : ConstFold.unary(u.op(), operand.value()));
			}
			case NOT -> {
				if (!t.equals(ScalarType.BOOL)) {
					return typeFail(u.pos(), "operator ! needs a bool scalar");
				}
				return info(u, t, false, operand.value() == null ? null : ConstFold.unary(u.op(), operand.value()));
			}
			case BIT_NOT -> {
				if (!Types.isInteger(t)) {
					return typeFail(u.pos(), "operator ~ needs an integer operand");
				}
				return info(u, t, false, operand.value() == null ? null : ConstFold.unary(u.op(), operand.value()));
			}
			case PRE_INC, PRE_DEC -> {
				writable(u.operand(), operand, "operator " + word(u.op()));
				if (!Types.isNumeric(t)) {
					return typeFail(u.pos(), "operator " + word(u.op()) + " needs a numeric l-value");
				}
				return info(u, t, false, null);
			}
		}
		return ExprInfo.ERROR;
	}

	private ExprInfo postfix(PostfixExpr p) {
		ExprInfo operand = expr(p.operand());
		if (operand.type().isError()) {
			return ExprInfo.ERROR;
		}
		writable(p.operand(), operand, "operator " + (p.op() == PostfixOp.INC ? "++" : "--"));
		if (!Types.isNumeric(operand.type())) {
			return typeFail(p.pos(), "postfix operator needs a numeric l-value");
		}
		return info(p, operand.type(), false, null);
	}

	private void writable(Expr target, ExprInfo info, String what) {
		if (!info.type().isError() && !info.lvalue()) {
			error(target.pos(), "not-lvalue", what + " needs a writable l-value");
		}
		Expr root = target;
		while (root instanceof IndexExpr || root instanceof FieldExpr) {
			root = root instanceof IndexExpr ie ? ie.base() : ((FieldExpr) root).base();
		}
		if (identSymbols.get(root) instanceof VariableSymbol v) {
			written.add(v);
		}
	}

	private ExprInfo typeFail(Pos pos, String message) {
		error(pos, "operand-type", message);
		return ExprInfo.ERROR;
	}

	private ExprInfo constructorFail(Pos pos, String message) {
		error(pos, "constructor-args", message);
		return ExprInfo.ERROR;
	}

	private static String word(UnaryOp op) {
		return op.word();
	}

	private ExprInfo binary(BinaryExpr b) {
		ExprInfo left = expr(b.left());
		ExprInfo right = expr(b.right());
		if (left.type().isError() || right.type().isError()) {
			return ExprInfo.ERROR;
		}
		return switch (b.op()) {
			case ADD, SUB, MUL, DIV -> arithmetic(b, left, right, false);
			case MOD, BIT_AND, BIT_OR, BIT_XOR -> arithmetic(b, left, right, true);
			case SHL, SHR -> shift(b, left, right);
			case LT, GT, LE, GE -> relational(b, left, right);
			case EQ, NE -> equality(b, left, right);
			case LOG_AND, LOG_OR, LOG_XOR -> logical(b, left, right);
		};
	}

	/** The scalar kind both operands convert to, or null when no implicit conversion matches them. */
	private ScalarKind commonKind(ScalarKind a, ScalarKind b) {
		if (a == b) {
			return a;
		}
		// the least common type: the lowest target both operands convert to (int and float are float, not double)
		for (ScalarKind target : new ScalarKind[] {ScalarKind.UINT, ScalarKind.FLOAT, ScalarKind.DOUBLE}) {
			boolean fitsA = a == target || Conversions.converts(ScalarType.of(a), ScalarType.of(target));
			boolean fitsB = b == target || Conversions.converts(ScalarType.of(b), ScalarType.of(target));
			if (fitsA && fitsB) {
				return target;
			}
		}
		return null;
	}

	private ExprInfo arithmetic(BinaryExpr b, ExprInfo left, ExprInfo right, boolean integerOnly) {
		GlslType lt = left.type();
		GlslType rt = right.type();
		if (b.op() == BinaryOp.MUL && (lt instanceof MatrixType || rt instanceof MatrixType)
				&& !(lt instanceof ScalarType) && !(rt instanceof ScalarType)) {
			return matrixProduct(b, left, right);
		}
		if (!isArithmeticOperand(lt, integerOnly) || !isArithmeticOperand(rt, integerOnly)) {
			return typeFail(b.pos(), "operator " + b.op().word() + (integerOnly ? " needs integer operands"
					: " needs numeric operands") + ", got " + lt + " and " + rt);
		}
		ScalarKind kind = commonKind(lt.componentKind(), rt.componentKind());
		if (kind == null) {
			return typeFail(b.pos(), "no implicit conversion makes " + lt + " and " + rt + " match");
		}
		GlslType lShape = shapeOf(lt);
		GlslType rShape = shapeOf(rt);
		if (!lShape.getClass().equals(rShape.getClass()) && lt instanceof ScalarType == false
				&& rt instanceof ScalarType == false) {
			return typeFail(b.pos(), "operand shapes " + lt + " and " + rt + " do not match");
		}
		GlslType result;
		if (lt instanceof ScalarType && rt instanceof ScalarType) {
			result = ScalarType.of(kind);
		} else if (lt instanceof ScalarType) {
			result = Types.withKind(rt, kind);
		} else if (rt instanceof ScalarType) {
			result = Types.withKind(lt, kind);
		} else {
			if (!lt.equals(rt) && !(sameShape(lt, rt))) {
				return typeFail(b.pos(), "operand shapes " + lt + " and " + rt + " do not match");
			}
			result = Types.withKind(lt, kind);
		}
		GlslType targetL = Types.withKind(lt, kind);
		GlslType targetR = Types.withKind(rt, kind);
		recordIfDifferent(b.left(), lt, targetL, Conversion.Site.OPERAND, lt instanceof ScalarType && !(result instanceof ScalarType));
		recordIfDifferent(b.right(), rt, targetR, Conversion.Site.OPERAND, rt instanceof ScalarType && !(result instanceof ScalarType));
		ConstValue value = null;
		if (left.value() != null && right.value() != null) {
			value = ConstFold.binary(b.op(), left.value().implicitTo(targetL), right.value().implicitTo(targetR),
					result);
		}
		return info(b, result, false, value);
	}

	private boolean isArithmeticOperand(GlslType t, boolean integerOnly) {
		if (integerOnly) {
			return Types.isInteger(t) && Types.isScalarOrVector(t);
		}
		return Types.isNumeric(t);
	}

	/** Scalar, vector or matrix shape marker used to compare operand shapes. */
	private static GlslType shapeOf(GlslType t) {
		return t;
	}

	private static boolean sameShape(GlslType a, GlslType b) {
		if (a instanceof VectorType va && b instanceof VectorType vb) {
			return va.size() == vb.size();
		}
		if (a instanceof MatrixType ma && b instanceof MatrixType mb) {
			return ma.cols() == mb.cols() && ma.rows() == mb.rows();
		}
		return false;
	}

	private ExprInfo matrixProduct(BinaryExpr b, ExprInfo left, ExprInfo right) {
		GlslType lt = left.type();
		GlslType rt = right.type();
		ScalarKind kind = commonKind(lt.componentKind(), rt.componentKind());
		if (kind == null || !Types.isNumeric(lt) || !Types.isNumeric(rt)) {
			return typeFail(b.pos(), "matrix product needs float or double operands");
		}
		GlslType lTarget = Types.withKind(lt, kind);
		GlslType rTarget = Types.withKind(rt, kind);
		recordIfDifferent(b.left(), lt, lTarget, Conversion.Site.OPERAND, false);
		recordIfDifferent(b.right(), rt, rTarget, Conversion.Site.OPERAND, false);
		GlslType result;
		if (lt instanceof MatrixType lm && rt instanceof MatrixType rm) {
			if (lm.cols() != rm.rows()) {
				return typeFail(b.pos(), "cannot multiply " + lt + " by " + rt);
			}
			result = MatrixType.of(kind, rm.cols(), lm.rows());
		} else if (lt instanceof MatrixType lm && rt instanceof VectorType rv) {
			if (lm.cols() != rv.size()) {
				return typeFail(b.pos(), "cannot multiply " + lt + " by " + rt);
			}
			result = VectorType.of(kind, lm.rows());
		} else if (lt instanceof VectorType lv && rt instanceof MatrixType rm) {
			if (lv.size() != rm.rows()) {
				return typeFail(b.pos(), "cannot multiply " + lt + " by " + rt);
			}
			result = VectorType.of(kind, rm.cols());
		} else {
			return typeFail(b.pos(), "cannot multiply " + lt + " by " + rt);
		}
		ConstValue value = null;
		if (left.value() != null && right.value() != null) {
			value = ConstFold.binary(BinaryOp.MUL, left.value().implicitTo(lTarget),
					right.value().implicitTo(rTarget), result);
		}
		return info(b, result, false, value);
	}

	private ExprInfo shift(BinaryExpr b, ExprInfo left, ExprInfo right) {
		GlslType lt = left.type();
		GlslType rt = right.type();
		if (!Types.isInteger(lt) || !Types.isInteger(rt) || !Types.isScalarOrVector(lt)
				|| !Types.isScalarOrVector(rt)) {
			return typeFail(b.pos(), "shift needs integer operands");
		}
		if (lt instanceof ScalarType && rt instanceof VectorType) {
			return typeFail(b.pos(), "a scalar cannot be shifted by a vector");
		}
		if (lt instanceof VectorType lv && rt instanceof VectorType rv && lv.size() != rv.size()) {
			return typeFail(b.pos(), "shift operands have different vector sizes");
		}
		ConstValue value = null;
		if (left.value() != null && right.value() != null && lt instanceof ScalarType && rt instanceof ScalarType) {
			value = ConstFold.binary(b.op(), left.value(), right.value(), lt);
		}
		return info(b, lt, false, value);
	}

	private ExprInfo relational(BinaryExpr b, ExprInfo left, ExprInfo right) {
		GlslType lt = left.type();
		GlslType rt = right.type();
		if (!(lt instanceof ScalarType) || !(rt instanceof ScalarType) || !Types.isNumeric(lt)
				|| !Types.isNumeric(rt)) {
			return typeFail(b.pos(), "operator " + b.op().word() + " needs numeric scalars (lessThan and friends"
					+ " compare vectors)");
		}
		ScalarKind kind = commonKind(lt.componentKind(), rt.componentKind());
		if (kind == null) {
			return typeFail(b.pos(), "no implicit conversion makes " + lt + " and " + rt + " match");
		}
		GlslType lTarget = ScalarType.of(kind);
		recordIfDifferent(b.left(), lt, lTarget, Conversion.Site.OPERAND, false);
		recordIfDifferent(b.right(), rt, lTarget, Conversion.Site.OPERAND, false);
		ConstValue value = left.value() != null && right.value() != null
				? ConstFold.binary(b.op(), left.value().implicitTo(lTarget), right.value().implicitTo(lTarget),
						ScalarType.BOOL)
				: null;
		return info(b, ScalarType.BOOL, false, value);
	}

	private ExprInfo equality(BinaryExpr b, ExprInfo left, ExprInfo right) {
		GlslType lt = left.type();
		GlslType rt = right.type();
		if (lt.containsOpaque() || rt.containsOpaque()) {
			return typeFail(b.pos(), "opaque types cannot be compared");
		}
		GlslType common;
		if (lt.equals(rt)) {
			common = lt;
		} else if (Conversions.converts(lt, rt)) {
			common = rt;
			recordIfDifferent(b.left(), lt, rt, Conversion.Site.OPERAND, false);
		} else if (Conversions.converts(rt, lt)) {
			common = lt;
			recordIfDifferent(b.right(), rt, lt, Conversion.Site.OPERAND, false);
		} else {
			return typeFail(b.pos(), "cannot compare " + lt + " with " + rt);
		}
		ConstValue value = null;
		if (common instanceof ScalarType && left.value() != null && right.value() != null) {
			value = ConstFold.binary(b.op(), left.value().implicitTo(common), right.value().implicitTo(common),
					ScalarType.BOOL);
		}
		return info(b, ScalarType.BOOL, false, value);
	}

	private ExprInfo logical(BinaryExpr b, ExprInfo left, ExprInfo right) {
		if (!left.type().equals(ScalarType.BOOL) || !right.type().equals(ScalarType.BOOL)) {
			return typeFail(b.pos(), "operator " + b.op().word() + " needs two bool scalars");
		}
		ConstValue value = left.value() != null && right.value() != null
				? ConstFold.binary(b.op(), left.value(), right.value(), ScalarType.BOOL)
				: null;
		return info(b, ScalarType.BOOL, false, value);
	}

	private ExprInfo assign(AssignExpr a) {
		ExprInfo target = expr(a.target());
		ExprInfo value = expr(a.value());
		if (target.type().isError() || value.type().isError()) {
			return ExprInfo.ERROR;
		}
		writable(a.target(), target, "assignment");
		GlslType tt = target.type();
		if (a.op() == AssignOp.ASSIGN) {
			if (!assignable(value.type(), tt)) {
				return typeFail(a.pos(), "cannot assign " + value.type() + " to " + tt);
			}
			recordIfDifferent(a.value(), value.type(), tt, Conversion.Site.ASSIGNMENT);
			return info(a, tt, false, null);
		}
		BinaryOp op = switch (a.op()) {
			case ADD -> BinaryOp.ADD;
			case SUB -> BinaryOp.SUB;
			case MUL -> BinaryOp.MUL;
			case DIV -> BinaryOp.DIV;
			case MOD -> BinaryOp.MOD;
			case SHL -> BinaryOp.SHL;
			case SHR -> BinaryOp.SHR;
			case AND -> BinaryOp.BIT_AND;
			case XOR -> BinaryOp.BIT_XOR;
			case OR -> BinaryOp.BIT_OR;
			case ASSIGN -> throw new IllegalStateException("plain assignment handled above");
		};
		// the operation types exactly as the binary operator would; its result must assign back to the target
		BinaryExpr synthetic = new BinaryExpr(a.pos(), op, a.target(), a.value());
		ExprInfo result = switch (op) {
			case SHL, SHR -> shift(synthetic, target, value);
			case MOD, BIT_AND, BIT_OR, BIT_XOR -> arithmetic(synthetic, target, value, true);
			default -> arithmetic(synthetic, target, value, false);
		};
		if (result.type().isError()) {
			return ExprInfo.ERROR;
		}
		if (!result.type().equals(tt)) {
			if (!Conversions.converts(result.type(), tt)) {
				return typeFail(a.pos(), "cannot assign " + result.type() + " to " + tt);
			}
			recordIfDifferent(a, result.type(), tt, Conversion.Site.ASSIGNMENT);
		}
		return info(a, tt, false, null);
	}

	/** Assignment compatibility: equal types, or an implicit conversion (GLSL 4.60 section 4.1.10). */
	private boolean assignable(GlslType from, GlslType to) {
		return from.isError() || to.isError() || from.equals(to) || Conversions.converts(from, to);
	}

	private ExprInfo ternary(TernaryExpr t) {
		ExprInfo cond = expr(t.condition());
		ExprInfo a = expr(t.thenExpr());
		ExprInfo b = expr(t.elseExpr());
		requireBool(cond, t.condition().pos(), "ternary condition");
		if (a.type().isError() || b.type().isError()) {
			return ExprInfo.ERROR;
		}
		GlslType at = a.type();
		GlslType bt = b.type();
		if (at.containsOpaque() || bt.containsOpaque()) {
			return typeFail(t.pos(), "ternary branches cannot have opaque types");
		}
		GlslType result;
		if (at.equals(bt)) {
			result = at;
		} else if (Conversions.converts(at, bt)) {
			result = bt;
			recordIfDifferent(t.thenExpr(), at, bt, Conversion.Site.OPERAND);
		} else if (Conversions.converts(bt, at)) {
			result = at;
			recordIfDifferent(t.elseExpr(), bt, at, Conversion.Site.OPERAND);
		} else {
			return typeFail(t.pos(), "ternary branches have types " + at + " and " + bt);
		}
		ConstValue value = null;
		if (cond.value() != null && a.value() != null && b.value() != null) {
			ConstValue chosen = cond.value().booleanValue() ? a.value() : b.value();
			value = chosen.implicitTo(result);
		}
		return info(t, result, false, value);
	}

	private ExprInfo sequence(SequenceExpr s) {
		ExprInfo last = ExprInfo.ERROR;
		boolean allConst = !s.items().isEmpty();
		for (Expr item : s.items()) {
			last = expr(item);
			if (last.value() == null) {
				allConst = false;
			}
		}
		return info(s, last.type(), false, allConst ? last.value() : null);
	}

	private ExprInfo index(IndexExpr i) {
		ExprInfo base = expr(i.base());
		ExprInfo index = expr(i.index());
		if (base.type().isError()) {
			return ExprInfo.ERROR;
		}
		if (!index.type().isError() && !index.type().equals(ScalarType.INT) && !index.type().equals(ScalarType.UINT)) {
			error(i.index().pos(), "index-type", "an index must be an int or uint scalar");
		}
		GlslType t = base.type();
		int bound;
		GlslType element;
		switch (t) {
			case ArrayType a -> {
				element = a.element();
				bound = a.size() == null ? -1 : a.size();
			}
			case VectorType v -> {
				element = ScalarType.of(v.component());
				bound = v.size();
			}
			case MatrixType m -> {
				element = m.columnType();
				bound = m.cols();
			}
			default -> {
				error(i.base().pos(), "index-type", "cannot index " + t);
				return ExprInfo.ERROR;
			}
		}
		ConstValue value = null;
		if (index.value() != null && index.value().isIntegral()) {
			long at = index.value().integerValue();
			if (bound >= 0 && (at < 0 || at >= bound)) {
				error(i.index().pos(), "index-out-of-bounds", "index " + at + " is out of bounds for " + t);
			}
			if (base.value() != null) {
				value = ConstFold.index(base.value(), at);
			}
		}
		return info(i, element, base.lvalue(), value);
	}

	private ExprInfo field(FieldExpr f) {
		ExprInfo base = expr(f.base());
		GlslType t = base.type();
		if (t.isError()) {
			return ExprInfo.ERROR;
		}
		if (t instanceof StructType s) {
			int at = -1;
			for (int k = 0; k < s.fields().size(); k++) {
				if (s.fields().get(k).name().equals(f.name())) {
					at = k;
				}
			}
			if (at < 0) {
				return typeFail(f.pos(), "struct " + s + " has no member '" + f.name() + "'");
			}
			ConstValue value = base.value() == null ? null : base.value().parts()[at];
			return info(f, s.fields().get(at).type(), base.lvalue(), value);
		}
		if (t instanceof VectorType v) {
			int[] components = swizzle(f, v.size());
			if (components == null) {
				return ExprInfo.ERROR;
			}
			GlslType result = components.length == 1 ? ScalarType.of(v.component())
					: VectorType.of(v.component(), components.length);
			boolean repeats = Arrays.stream(components).distinct().count() != components.length;
			if (repeats && base.lvalue()) {
				// a swizzle that repeats a component is a read-only value
				return info(f, result, false, base.value() == null ? null
						: ConstFold.swizzle(base.value(), components, result));
			}
			return info(f, result, base.lvalue(), base.value() == null ? null
					: ConstFold.swizzle(base.value(), components, result));
		}
		return typeFail(f.pos(), "cannot select '" + f.name() + "' on " + t);
	}

	private static final String[] SWIZZLE_SETS = {"xyzw", "rgba", "stpq"};

	/** Component indices of a swizzle, or null after a diagnostic. */
	private int[] swizzle(FieldExpr f, int size) {
		String name = f.name();
		if (name.length() > 4) {
			error(f.namePos(), "swizzle-length", "a swizzle has at most 4 components");
			return null;
		}
		String set = null;
		for (String candidate : SWIZZLE_SETS) {
			if (name.chars().allMatch(c -> candidate.indexOf(c) >= 0)) {
				set = candidate;
				break;
			}
		}
		if (set == null) {
			error(f.namePos(), "swizzle-mixed-sets", "'" + name + "' mixes the xyzw, rgba and stpq component sets");
			return null;
		}
		int[] out = new int[name.length()];
		for (int k = 0; k < name.length(); k++) {
			out[k] = set.indexOf(name.charAt(k));
			if (out[k] >= size) {
				error(f.namePos(), "swizzle-range", "component '" + name.charAt(k)
						+ "' is out of range for a vector of size " + size);
				return null;
			}
		}
		return out;
	}

	private void recordIfDifferent(Expr e, GlslType from, GlslType to, Conversion.Site site) {
		recordIfDifferent(e, from, to, site, false);
	}

	private void recordIfDifferent(Expr e, GlslType from, GlslType to, Conversion.Site site, boolean broadcast) {
		if (!from.isError() && !to.isError() && !from.equals(to)) {
			conversions.add(new Conversion(e, from, to, site, broadcast));
		}
	}

	// --- calls ---

	private ExprInfo call(CallExpr c) {
		Callee callee = c.callee();
		return switch (callee) {
			case TypeCallee t -> constructor(c, t);
			case MethodCallee m -> lengthMethod(c, m);
			case NameCallee n -> {
				Symbol s = scope.resolve(n.name());
				if (s instanceof StructSymbol st) {
					yield structConstructor(c, st.struct());
				}
				if (s == null && Types.fromWord(n.name()) != null) {
					// a type name the parser reads as a call (uint in a GLSL 1.20 unit) is a constructor
					int since = typeSince(n.name());
					if (since > version) {
						leniency(c.pos(), "later-builtin", "'" + n.name() + "' is a GLSL " + since
								+ " type; drivers accept its constructor in a GLSL " + version + " unit");
					}
					yield constructor(c, new TypeCallee(c.pos(), new BuiltinTypeSpec(c.pos(), n.name()), List.of()));
				}
				yield functionCall(c, n.name());
			}
		};
	}

	private ExprInfo lengthMethod(CallExpr c, MethodCallee m) {
		ExprInfo receiver = expr(m.receiver());
		for (Expr arg : c.args()) {
			expr(arg);
		}
		if (receiver.type().isError()) {
			return ExprInfo.ERROR;
		}
		if (!m.name().equals("length") || !c.args().isEmpty()) {
			return typeFail(c.pos(), "only .length() with no arguments is a method of this language");
		}
		if (!(receiver.type() instanceof ArrayType a)) {
			return typeFail(c.pos(), ".length() needs an array, not " + receiver.type());
		}
		ConstValue value = a.size() == null ? null : ConstValue.ofInt(a.size());
		calls.put(c, new CallResolution(c, CallResolution.Kind.ARRAY_LENGTH, "length", null, null,
				List.of(a), List.of(), ScalarType.INT));
		return info(c, ScalarType.INT, false, value);
	}

	private ExprInfo functionCall(CallExpr c, String name) {
		List<ExprInfo> args = new ArrayList<>();
		for (Expr a : c.args()) {
			args.add(expr(a));
		}
		if (args.stream().anyMatch(a -> a.type().isError())) {
			return ExprInfo.ERROR;
		}
		Symbol s = scope.resolve(name);
		List<Candidate> candidates;
		if (s instanceof FunctionGroupSymbol group) {
			candidates = new ArrayList<>();
			for (FunctionOverload o : group.overloads()) {
				candidates.add(new Candidate(o.params(), o.returnType(), o, null));
			}
		} else if (s != null && !(s instanceof VariableSymbol)) {
			return typeFail(c.pos(), "'" + name + "' is a type, not a function");
		} else {
			if (s instanceof VariableSymbol && !BuiltinFunctions.isBuiltinName(name)) {
				return typeFail(c.pos(), "'" + name + "' is a variable, not a function");
			}
			if (s instanceof VariableSymbol) {
				leniency(c.pos(), "variable-hides-builtin", "'" + name
						+ "' names a variable and a built-in function; calling the built-in function");
			}
			candidates = new ArrayList<>();
			for (BuiltinFunction f : BuiltinFunctions.byName(name)) {
				if (f.available(version) && f.availableIn(stage)) {
					candidates.add(new Candidate(toParams(f), f.result(), null, f));
				}
			}
			// lenient mode (drivers): the overloads of later versions join the candidates, so the normal resolution
			// (exact matches first, then conversions) decides. The later-builtin warning below names a later winner.
			if (options.lenient()) {
				for (BuiltinFunction f : BuiltinFunctions.byName(name)) {
					if (!f.available(version) && f.availableIn(stage)) {
						candidates.add(new Candidate(toParams(f), f.result(), null, f));
					}
				}
			}
		}
		if (name.equals("main")) {
			return typeFail(c.pos(), "main() cannot be called");
		}
		Candidate chosen = resolve(c, name, candidates, args);
		if (chosen != null && chosen.builtin() != null && chosen.builtin().since() > version) {
			leniency(c.pos(), "later-builtin", "'" + name + "' is a GLSL " + chosen.builtin().since()
					+ " built-in; drivers accept it in a GLSL " + version + " unit");
		}
		if (chosen == null) {
			if (candidates.isEmpty() && !(s instanceof FunctionGroupSymbol)) {
				return typeFail(c.pos(), "unknown function '" + name + "' for GLSL " + version + " " + stage.word()
						+ " stage");
			}
			if (!errorReportedAt(c.pos())) {
				error(c.pos(), "no-overload", "no overload of '" + name + "' matches ("
						+ args.stream().map(a -> a.type().toString()).reduce((x, y) -> x + ", " + y).orElse("")
						+ ")");
			}
			return ExprInfo.ERROR;
		}
		if (chosen.user() != null && current != null) {
			callEdges.computeIfAbsent(signatureKey(current), k -> new ArrayList<>())
					.add(new CallEdge(signatureKey(chosen.user()), c.pos()));
		}
		if (chosen.user() != null && chosen.user() == current) {
			error(c.pos(), "recursion", "function '" + name + "' calls itself (recursion is not allowed)");
		}
		if (chosen.builtin() != null) {
			BuiltinFunction f = chosen.builtin();
			if (f.extension() != null && !extensionEnabled(f.extension())) {
				leniency(c.pos(), "extension-function", "'" + name + "' comes from " + f.extension()
						+ ", which this unit does not enable");
			}
			if (f.compat() && !compatibility) {
				leniency(c.pos(), "compat-function", "'" + name
						+ "' is a compatibility profile function; this unit uses the core profile");
			}
		}
		List<GlslType> argTypes = new ArrayList<>();
		List<GlslType> paramTypes = new ArrayList<>();
		ConstValue[] argValues = new ConstValue[args.size()];
		for (int i = 0; i < args.size(); i++) {
			FunctionOverload.Parameter param = chosen.params().get(i);
			ExprInfo arg = args.get(i);
			Expr argExpr = c.args().get(i);
			argTypes.add(arg.type());
			paramTypes.add(param.type());
			if (param.writes()) {
				writable(argExpr, arg, "argument " + (i + 1) + " of '" + name + "'");
				recordIfDifferent(argExpr, param.type(), arg.type(), Conversion.Site.ARGUMENT);
			} else {
				recordIfDifferent(argExpr, arg.type(), param.type(), Conversion.Site.ARGUMENT);
			}
			if (chosen.builtin() != null && chosen.builtin().params().get(i).constant() && arg.value() == null) {
				error(argExpr.pos(), "argument-not-constant",
						"argument " + (i + 1) + " of '" + name + "' must be a constant expression");
			}
			argValues[i] = arg.value() == null ? null : arg.value().implicitTo(param.type());
		}
		ConstValue value = null;
		if (chosen.builtin() != null && Arrays.stream(argValues).allMatch(v -> v != null) && foldable(name)) {
			value = ConstFold.builtin(name, Arrays.asList(argValues), chosen.result());
		}
		calls.put(c, new CallResolution(c,
				chosen.user() != null ? CallResolution.Kind.USER_FUNCTION : CallResolution.Kind.BUILTIN_FUNCTION,
				name, chosen.user(), chosen.builtin(), argTypes, paramTypes, chosen.result()));
		return info(c, chosen.result(), false, value);
	}

	/** A user function's identity for the call graph: its name and parameter types (a prototype and its definition agree). */
	private static String signatureKey(FunctionOverload f) {
		return f.name() + f.params().stream().map(p -> p.type().toString()).toList();
	}

	/**
	 * Every user call names the function's final overload (the one that carries the definition, when there is one), and
	 * a call to a function that is declared but never defined is an error: the program cannot link.
	 */
	private void resolveUserCalls() {
		Map<String, FunctionOverload> finalBySignature = new HashMap<>();
		for (FunctionOverload f : functions) {
			finalBySignature.put(signatureKey(f), f);
		}
		for (Map.Entry<CallExpr, CallResolution> e : calls.entrySet()) {
			CallResolution r = e.getValue();
			if (r.kind() != CallResolution.Kind.USER_FUNCTION || r.overload() == null) {
				continue;
			}
			FunctionOverload f = finalBySignature.getOrDefault(signatureKey(r.overload()), r.overload());
			if (!f.defined()) {
				error(r.call().pos(), "undefined-function", "function '" + f.name()
						+ "' is declared but never defined in this program");
			}
			if (f != r.overload()) {
				e.setValue(new CallResolution(r.call(), r.kind(), r.name(), f, r.builtin(), r.argTypes(),
						r.paramTypes(), r.resultType()));
			}
		}
	}

 	/** Reports each call that closes a cycle of user functions, directly or through others (GLSL forbids recursion). A
 	 * direct self-call is already reported where it is made. */
	private void checkRecursion() {
		for (Map.Entry<String, List<CallEdge>> caller : callEdges.entrySet()) {
			for (CallEdge e : caller.getValue()) {
				if (!e.callee().equals(caller.getKey()) && reaches(e.callee(), caller.getKey(), new HashSet<>())) {
					error(e.pos(), "recursion", "function " + caller.getKey()
							+ " is part of a call cycle (recursion is not allowed)");
				}
			}
		}
	}

	private boolean reaches(String from, String target, Set<String> seen) {
		if (from.equals(target)) {
			return true;
		}
		if (!seen.add(from)) {
			return false;
		}
		for (CallEdge e : callEdges.getOrDefault(from, List.of())) {
			if (reaches(e.callee(), target, seen)) {
				return true;
			}
		}
		return false;
	}

	/** Built-in calls that may form constant expressions: all but the texture lookups, the noise functions and
	 * {@code ftransform} (GLSL 1.20 to 4.60, section 4.3.3). */
	private static boolean foldable(String name) {
		return !(name.startsWith("texture") || name.startsWith("noise") || name.equals("ftransform")
				|| name.startsWith("shadow") || name.startsWith("textureGather"));
	}

	private boolean errorReportedAt(Pos pos) {
		return diagnostics.stream().anyMatch(d -> d.severity() == SemaDiagnostic.Severity.ERROR
				&& pos.equals(d.pos()) && d.code().startsWith("ambiguous"));
	}

	private List<FunctionOverload.Parameter> toParams(BuiltinFunction f) {
		List<FunctionOverload.Parameter> params = new ArrayList<>();
		for (BuiltinFunction.Param p : f.params()) {
			StorageKind direction = switch (p.direction()) {
				case OUT -> StorageKind.OUT;
				case INOUT -> StorageKind.INOUT;
				case IN -> StorageKind.IN;
			};
			params.add(new FunctionOverload.Parameter(null, p.type(), direction, p.constant()));
		}
		return params;
	}

	/**
	 * Overload resolution (GLSL 4.60 section 6.1): an exact match wins; otherwise the candidates that convert to the
	 * arguments under the conversions of the unit's own version are compared argument by argument. Only when no candidate
	 * matches at that version do the 4.00 conversions apply (the a081cf97 compatibility rule). So below 4.00 f(uint) and
	 * f(float) take f(float) for an int argument, and max(uint, int) at 1.30 keeps max(float, float).
	 */
	private Candidate resolve(CallExpr c, String name, List<Candidate> candidates, List<ExprInfo> args) {
		List<Candidate> exact = new ArrayList<>();
		for (Candidate k : candidates) {
			if (exactMatch(k, args)) {
				exact.add(k);
			}
		}
		exact = oneCopyPerSignature(exact);
		if (!exact.isEmpty()) {
			return exact.getFirst();
		}
		List<Candidate> viable = convertibleMatches(candidates, args, version);
		if (viable.isEmpty()) {
			viable = convertibleMatches(candidates, args, Conversions.COMPATIBILITY_VERSION);
		}
		viable = oneCopyPerSignature(viable);
		if (viable.isEmpty()) {
			return null;
		}
		if (viable.size() == 1) {
			return viable.getFirst();
		}
		Candidate best = null;
		for (Candidate a : viable) {
			boolean beatsAll = true;
			for (Candidate b : viable) {
				if (a != b && !betterThan(a, b, args)) {
					beatsAll = false;
					break;
				}
			}
			if (beatsAll) {
				best = a;
				break;
			}
		}
		if (best == null) {
			error(c.pos(), "ambiguous-call", "call to '" + name + "' is ambiguous: several overloads match");
			return viable.getFirst();
		}
		return best;
	}

	/**
	 * One candidate per signature: a core version and an extension can offer the same function (texture2DLod at 1.30
	 * and GL_ARB_shader_texture_lod). The core entry is kept; with no core entry the first one is kept.
	 */
	private static List<Candidate> oneCopyPerSignature(List<Candidate> candidates) {
		Map<String, Candidate> bySignature = new LinkedHashMap<>();
		for (Candidate k : candidates) {
			String key = k.params().stream().map(p -> p.type() + " " + p.direction()).toList() + " -> " + k.result();
			Candidate kept = bySignature.get(key);
			if (kept == null || usesExtension(kept) && !usesExtension(k)) {
				bySignature.put(key, k);
			}
		}
		return List.copyOf(bySignature.values());
	}

	private static boolean usesExtension(Candidate k) {
		return k.builtin() != null && k.builtin().extension() != null;
	}

	/** Exact: every argument has the parameter's type (an unsized array parameter takes any size of its element type). */
	private static boolean exactMatch(Candidate k, List<ExprInfo> args) {
		if (k.params().size() != args.size()) {
			return false;
		}
		for (int i = 0; i < args.size(); i++) {
			if (!sameOrArray(k.params().get(i).type(), args.get(i).type())) {
				return false;
			}
		}
		return true;
	}

	/** The candidates that match the arguments under the conversions of {@code conversionVersion}. */
	private static List<Candidate> convertibleMatches(List<Candidate> candidates, List<ExprInfo> args,
			int conversionVersion) {
		List<Candidate> viable = new ArrayList<>();
		for (Candidate k : candidates) {
			if (convertible(k, args, conversionVersion)) {
				viable.add(k);
			}
		}
		return viable;
	}

	/** Each argument has the parameter's type or converts to it (out parameters convert the other way, from the parameter
	 * to the argument). */
	private static boolean convertible(Candidate k, List<ExprInfo> args, int conversionVersion) {
		if (k.params().size() != args.size()) {
			return false;
		}
		for (int i = 0; i < args.size(); i++) {
			FunctionOverload.Parameter p = k.params().get(i);
			GlslType want = p.type();
			GlslType got = args.get(i).type();
			if (sameOrArray(want, got)) {
				continue;
			}
			boolean ok = p.writes()
					? Conversions.convertsIn(want, got, conversionVersion)
					: Conversions.convertsIn(got, want, conversionVersion);
			if (!ok) {
				return false;
			}
		}
		return true;
	}

	/** Equal types; an unsized array parameter accepts any size of the same element type. */
	private static boolean sameOrArray(GlslType want, GlslType got) {
		if (want.equals(got)) {
			return true;
		}
		if (want instanceof ArrayType w && got instanceof ArrayType g) {
			return (w.size() == null || w.size().equals(g.size())) && sameOrArray(w.element(), g.element());
		}
		return false;
	}

	/**
	 * Section 6.1.1 (GLSL 4.00 and later), "a function declaration A is considered a better match than B": some argument's
	 * conversion is better in A, and no argument's conversion is better in B. Two conversions are compared by the three
	 * rules of that section only; a pair that none of them covers is unranked, and neither conversion is better.
	 */
	private boolean betterThan(Candidate a, Candidate b, List<ExprInfo> args) {
		boolean betterSomewhere = false;
		for (int i = 0; i < args.size(); i++) {
			int ra = rank(args.get(i).type(), a.params().get(i));
			int rb = rank(args.get(i).type(), b.params().get(i));
			if (argumentBetter(rb, ra)) {
				return false;
			}
			if (argumentBetter(ra, rb)) {
				betterSomewhere = true;
			}
		}
		return betterSomewhere;
	}

	/** The three pairwise rules of section 6.1.1 on the ranks 0 (exact), 1 (float to double), 2 (int or uint to
	 * float), 3 (int or uint to double) and 4 (any other conversion, unranked except against exact). */
	private static boolean argumentBetter(int a, int b) {
		return (a == 0 && b != 0) // rule 1: an exact match is better than any implicit conversion
				|| (a == 1 && b >= 2) // rule 2: float to double is better than any other implicit conversion
				|| (a == 2 && b == 3); // rule 3: int or uint to float is better than int or uint to double
	}

	private static int rank(GlslType got, FunctionOverload.Parameter p) {
		if (sameOrArray(p.type(), got)) {
			return 0;
		}
		ScalarKind from = got.componentKind();
		ScalarKind to = p.type().componentKind();
		if (from == ScalarKind.FLOAT && to == ScalarKind.DOUBLE) {
			return 1;
		}
		if ((from == ScalarKind.INT || from == ScalarKind.UINT) && to == ScalarKind.FLOAT) {
			return 2;
		}
		if ((from == ScalarKind.INT || from == ScalarKind.UINT) && to == ScalarKind.DOUBLE) {
			return 3;
		}
		return 4;
	}

	/** The GLSL version that introduced a type name: uint and its vectors need 1.30, double and its vectors 4.00. */
	private static int typeSince(String word) {
		if (word.equals("double") || word.startsWith("dvec")) {
			return 400;
		}
		if (word.equals("uint") || word.startsWith("uvec")) {
			return 130;
		}
		return 110;
	}

	// --- constructors ---

	private ExprInfo constructor(CallExpr c, TypeCallee callee) {
		List<ExprInfo> args = new ArrayList<>();
		for (Expr a : c.args()) {
			args.add(expr(a));
		}
		GlslType base = baseType(callee.spec(), callee.pos(), false);
		if (base.isError() || args.stream().anyMatch(a -> a.type().isError())) {
			return ExprInfo.ERROR;
		}
		if (base instanceof VoidType) {
			return typeFail(c.pos(), "void has no constructor");
		}
		GlslType target = wrap(base, callee.dims());
		if (target instanceof ArrayType a) {
			return arrayConstructor(c, a, args);
		}
		if (target.containsOpaque()) {
			return typeFail(c.pos(), "opaque types have no constructor");
		}
		if (target instanceof ScalarType s) {
			return scalarConstructor(c, s, args);
		}
		if (target instanceof VectorType v) {
			return vectorConstructor(c, v, args);
		}
		if (target instanceof MatrixType m) {
			return matrixConstructor(c, m, args);
		}
		if (target instanceof StructType s) {
			return structConstructor(c, s);
		}
		return typeFail(c.pos(), "no constructor for " + target);
	}

	private ExprInfo scalarConstructor(CallExpr c, ScalarType target, List<ExprInfo> args) {
		if (args.size() != 1) {
			return typeFail(c.pos(), target + " takes exactly one argument");
		}
		ExprInfo arg = args.getFirst();
		ConstValue value = arg.value() == null ? null : arg.value().flatten()[0].convert(target.kind());
		calls.put(c, new CallResolution(c, CallResolution.Kind.TYPE_CONSTRUCTOR, target.toString(), null, null,
				List.of(arg.type()), List.of(target), target));
		return info(c, target, false, value);
	}

	/** Consumes components left to right; an argument that starts after the last needed component is an error
	 * (GLSL 4.60 section 5.4.2). */
	private ExprInfo vectorConstructor(CallExpr c, VectorType target, List<ExprInfo> args) {
		List<GlslType> argTypes = new ArrayList<>();
		List<GlslType> paramTypes = new ArrayList<>();
		for (ExprInfo a : args) {
			argTypes.add(a.type());
			paramTypes.add(ScalarType.of(target.component()));
		}
		calls.put(c, new CallResolution(c, CallResolution.Kind.TYPE_CONSTRUCTOR, target.toString(), null, null,
				argTypes, paramTypes, target));
		if (args.size() == 1 && args.getFirst().type() instanceof ScalarType) {
			ConstValue v = args.getFirst().value() == null ? null
					: args.getFirst().value().convert(target.component());
			return info(c, target, false, v == null ? null : ConstValue.vector(target, repeat(v, target.size())));
		}
		if (args.size() == 1 && args.getFirst().type() instanceof MatrixType) {
			// a matrix supplies its components column by column
			if (args.getFirst().type() instanceof MatrixType m && m.cols() * m.rows() < target.size()) {
				return typeFail(c.pos(), target + " needs " + target.size() + " components");
			}
		}
		return collect(c, target, args, target.size(), target.component(), (leaves) ->
				ConstValue.vector(target, leaves));
	}

	private ExprInfo matrixConstructor(CallExpr c, MatrixType target, List<ExprInfo> args) {
		List<GlslType> argTypes = new ArrayList<>();
		List<GlslType> paramTypes = new ArrayList<>();
		for (ExprInfo a : args) {
			argTypes.add(a.type());
			paramTypes.add(ScalarType.of(target.component()));
		}
		calls.put(c, new CallResolution(c, CallResolution.Kind.TYPE_CONSTRUCTOR, target.toString(), null, null,
				argTypes, paramTypes, target));
		if (args.size() == 1 && args.getFirst().type() instanceof ScalarType) {
			ExprInfo only = args.getFirst();
			return info(c, target, false, only.value() == null ? null
					: ConstFold.matrixDiagonal(target, only.value()));
		}
		if (args.size() == 1 && args.getFirst().type() instanceof MatrixType) {
			ExprInfo only = args.getFirst();
			return info(c, target, false, only.value() == null ? null
					: ConstFold.matrixFromMatrix(target, only.value()));
		}
		if (args.stream().anyMatch(a -> a.type() instanceof MatrixType)) {
			return typeFail(c.pos(), "a matrix argument must be the only argument of a matrix constructor");
		}
		return collect(c, target, args, target.cols() * target.rows(), target.component(), leaves ->
				ConstFold.rebuild(target, leaves));
	}

	/** Shared rule for vector and matrix constructors: arguments fill the components in order. */
	private ExprInfo collect(CallExpr c, GlslType target, List<ExprInfo> args, int needed, ScalarKind kind,
			java.util.function.Function<ConstValue[], ConstValue> build) {
		int total = 0;
		for (ExprInfo a : args) {
			if (total >= needed) {
				return constructorFail(c.pos(), "extra argument of type " + a.type()
						+ " after the last used component of " + target);
			}
			total += componentsOf(a.type());
		}
		if (total < needed) {
			return constructorFail(c.pos(), target + " needs " + needed + " components, got " + total);
		}
		ConstValue value = null;
		if (args.stream().allMatch(a -> a.value() != null)) {
			List<ConstValue> leaves = new ArrayList<>();
			for (ExprInfo a : args) {
				for (ConstValue leaf : a.value().flatten()) {
					leaves.add(leaf.convert(kind));
				}
			}
			value = build.apply(leaves.subList(0, needed).toArray(new ConstValue[0]));
		}
		return info(c, target, false, value);
	}

	private static int componentsOf(GlslType t) {
		return switch (t) {
			case VectorType v -> v.size();
			case MatrixType m -> m.cols() * m.rows();
			default -> 1;
		};
	}

	private static ConstValue[] repeat(ConstValue v, int n) {
		ConstValue[] out = new ConstValue[n];
		Arrays.fill(out, v);
		return out;
	}

	private ExprInfo arrayConstructor(CallExpr c, ArrayType target, List<ExprInfo> args) {
		if (version < 120) {
			leniency(c.pos(), "array-constructor", "array constructors need GLSL 1.20");
		}
		int count = args.size();
		if (target.size() != null && target.size() != count) {
			return constructorFail(c.pos(), "array of " + target.size() + " cannot be built from " + count
					+ " arguments");
		}
		GlslType arrayType = new ArrayType(target.element(), target.size() == null ? count : target.size());
		List<GlslType> argTypes = new ArrayList<>();
		List<GlslType> paramTypes = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			ExprInfo arg = args.get(i);
			argTypes.add(arg.type());
			paramTypes.add(target.element());
			if (!assignable(arg.type(), target.element())) {
				return typeFail(c.args().get(i).pos(), "cannot build " + target.element() + " from " + arg.type());
			}
			recordIfDifferent(c.args().get(i), arg.type(), target.element(), Conversion.Site.ARGUMENT);
		}
		calls.put(c, new CallResolution(c, CallResolution.Kind.ARRAY_CONSTRUCTOR, arrayType.toString(), null, null,
				argTypes, paramTypes, arrayType));
		ConstValue value = null;
		if (args.stream().allMatch(a -> a.value() != null)) {
			ConstValue[] elements = new ConstValue[count];
			for (int i = 0; i < count; i++) {
				elements[i] = args.get(i).value().implicitTo(target.element());
			}
			value = ConstValue.array(arrayType, elements);
		}
		return info(c, arrayType, false, value);
	}

	private ExprInfo structConstructor(CallExpr c, StructType struct) {
		List<ExprInfo> args = new ArrayList<>();
		for (Expr a : c.args()) {
			args.add(expr(a));
		}
		if (args.size() != struct.fields().size()) {
			return typeFail(c.pos(), struct + " takes " + struct.fields().size() + " arguments, got " + args.size());
		}
		List<GlslType> argTypes = new ArrayList<>();
		List<GlslType> paramTypes = new ArrayList<>();
		ConstValue[] values = new ConstValue[args.size()];
		boolean constant = true;
		for (int i = 0; i < args.size(); i++) {
			GlslType field = struct.fields().get(i).type();
			ExprInfo arg = args.get(i);
			argTypes.add(arg.type());
			paramTypes.add(field);
			if (!assignable(arg.type(), field)) {
				error(c.args().get(i).pos(), "constructor-args", "field '" + struct.fields().get(i).name()
						+ "' is " + field + ", got " + arg.type());
				return ExprInfo.ERROR;
			}
			recordIfDifferent(c.args().get(i), arg.type(), field, Conversion.Site.ARGUMENT);
			if (arg.value() == null) {
				constant = false;
			} else {
				values[i] = arg.value().implicitTo(field);
			}
		}
		calls.put(c, new CallResolution(c, CallResolution.Kind.STRUCT_CONSTRUCTOR, struct.toString(), null, null,
				argTypes, paramTypes, struct));
		return info(c, struct, false, constant ? ConstValue.struct(struct, values) : null);
	}

	/**
	 * A brace initializer (GLSL 4.20): its items fill the components, columns, elements or fields of the declared type,
	 * in order, and must match the count exactly (the array size is already fixed by the caller when unsized).
	 * Returns the folded {@link ConstValue} when every item is a constant expression, else null.
	 */
	private ConstValue initializerList(InitializerList list, GlslType type) {
		if (version < 420) {
			leniency(list.pos(), "initializer-list", "brace-enclosed initializer lists need GLSL 4.20");
		}
		List<Expr> items = list.items();
		ConstValue folded = null;
		switch (type) {
			case ArrayType a -> {
				if (a.size() != null && a.size() != items.size()) {
					error(list.pos(), "initializer-count", "array of " + a.size() + " needs " + a.size()
							+ " initializers, got " + items.size());
				} else {
					ConstValue[] els = new ConstValue[items.size()];
					boolean allConst = true;
					for (int i = 0; i < items.size(); i++) {
						ConstValue v = initializerItem(items.get(i), a.element());
						if (v == null) {
							allConst = false;
						} else {
							els[i] = v;
						}
					}
					if (allConst) {
						folded = ConstValue.array(a, els);
					}
				}
			}
			case VectorType v -> {
				ConstValue[] parts = countAndItems(list, items, v.size(), ScalarType.of(v.component()), v.toString());
				if (parts != null) {
					folded = ConstValue.vector(v, parts);
				}
			}
			case MatrixType m -> {
				ConstValue[] cols = countAndItems(list, items, m.cols(), m.columnType(), m.toString());
				if (cols != null) {
					folded = ConstValue.matrix(m, cols);
				}
			}
			case StructType s -> {
				if (s.fields().size() != items.size()) {
					error(list.pos(), "initializer-count", s + " needs " + s.fields().size()
							+ " initializers, got " + items.size());
				} else {
					ConstValue[] fields = new ConstValue[items.size()];
					boolean allConst = true;
					for (int i = 0; i < items.size(); i++) {
						ConstValue v = initializerItem(items.get(i), s.fields().get(i).type());
						if (v == null) {
							allConst = false;
						} else {
							fields[i] = v;
						}
					}
					if (allConst) {
						folded = ConstValue.struct(s, fields);
					}
				}
			}
			default -> error(list.pos(), "initializer-list-type",
					"a brace initializer needs an array, vector, matrix or struct, not " + type);
		}
		exprs.put(list, new ExprInfo(type, false, folded));
		return folded;
	}

	private ConstValue[] countAndItems(InitializerList list, List<Expr> items, int count, GlslType itemType, String name) {
		if (items.size() != count) {
			error(list.pos(), "initializer-count", name + " needs " + count + " initializers, got " + items.size());
			return null;
		}
		ConstValue[] vals = new ConstValue[count];
		boolean allConst = true;
		for (int i = 0; i < items.size(); i++) {
			ConstValue v = initializerItem(items.get(i), itemType);
			if (v == null) {
				allConst = false;
			} else {
				vals[i] = v;
			}
		}
		return allConst ? vals : null;
	}

	private ConstValue initializerItem(Expr item, GlslType type) {
		if (item instanceof InitializerList nested) {
			return initializerList(nested, type);
		}
		ExprInfo info = expr(item);
		if (info.type().isError()) {
			return null;
		}
		if (!assignable(info.type(), type)) {
			error(item.pos(), "initializer-type", "cannot initialize '" + type + "' from '" + info.type() + "'");
			return null;
		}
		recordIfDifferent(item, info.type(), type, Conversion.Site.INITIALIZER);
		return info.value() == null ? null : info.value().implicitTo(type);
	}

	// --- result ---

	private SemaResult result() {
		List<SemaDiagnostic> mapped = new ArrayList<>(diagnostics.size());
		for (SemaDiagnostic d : diagnostics) {
			int line = d.pos() == null ? 0 : d.pos().line();
			SourceLine origin = line >= 1 && line <= options.lineMap().size() ? options.lineMap().get(line - 1) : null;
			mapped.add(origin == null ? d : d.withOrigin(origin));
		}
		return new SemaResult(version, compatibility ? "compatibility" : "core", stage, mapped, exprs, calls,
				identSymbols, declarations, conversions, globalVars, functions, usedBuiltins, structs, localSize, written);
	}
}
