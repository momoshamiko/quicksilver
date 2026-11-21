// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.msl;

import dev.mcms.glsl.ast.*;
import dev.mcms.glsl.msl.builtins.BuiltinCall;
import dev.mcms.glsl.msl.builtins.BuiltinTable;
import dev.mcms.glsl.msl.builtins.Mapping;
import dev.mcms.glsl.sema.BuiltinFunction;
import dev.mcms.glsl.sema.CallResolution;
import dev.mcms.glsl.sema.Conversion;
import dev.mcms.glsl.sema.ConstValue;
import dev.mcms.glsl.sema.FunctionOverload;
import dev.mcms.glsl.sema.SemaResult;
import dev.mcms.glsl.sema.Symbol;
import dev.mcms.glsl.sema.VariableSymbol;
import dev.mcms.glsl.ast.StorageKind;
import dev.mcms.glsl.types.ArrayType;
import dev.mcms.glsl.types.GlslType;
import dev.mcms.glsl.types.ImageFormat;
import dev.mcms.glsl.types.ImageType;
import dev.mcms.glsl.types.MatrixType;
import dev.mcms.glsl.types.SamplerType;
import dev.mcms.glsl.types.ScalarKind;
import dev.mcms.glsl.types.ScalarType;
import dev.mcms.glsl.types.StructField;
import dev.mcms.glsl.types.StructType;
import dev.mcms.glsl.types.VectorType;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;

/**
 * Emits the statements and expressions of one stage's method bodies as MSL (ADR 0006: the MSL emitter). Every
 * implicit conversion the analyzer recorded becomes an explicit constructor call; vector equality becomes
 * {@code all}/{@code any}; float self-comparison becomes {@code isnan} (fast math folds self-compares, measured by
 * W1-C); GLSL constructors MSL lacks are written out component by component; a folded constant is emitted as its
 * literal. Names come from the stage emitter, which owns every identifier's spelling.
 *
 * <p>Known deviation (measured, not fixed): an {@code out} or {@code inout} argument that is a global the callee also
 * reads or writes is passed by reference (a {@code thread T&} into the stage's context), with no copy. GLSL 4.60
 * leaves that aliasing undefined, so the emitter does not promise the value the callee sees.
 */
final class BodyEmitter {

	private final StageEmitter stage;
	private final SemaResult sema;
	private final BuiltinTable table;
	private final IdentityHashMap<Expr, List<Conversion>> conversions = new IdentityHashMap<>();
	private final StringBuilder out;
	private int depth;
	/** Copy-in statements for the statement being emitted, and the write-backs that follow it (see userCall). */
	private final List<String> pre = new ArrayList<>();
	private final List<String> post = new ArrayList<>();
	/** Copied out arguments emitted so far; a loop whose condition or step copies one is written in canonical form. */
	private int copies;
	/** Targets copied into out arguments by the current statement; a second copy of one is refused (see outArgument). */
	private final java.util.Set<String> copiedTargets = new java.util.HashSet<>();

	BodyEmitter(StageEmitter stage, SemaResult sema, BuiltinTable table, StringBuilder out, int depth) {
		this.stage = stage;
		this.sema = sema;
		this.table = table;
		this.out = out;
		this.depth = depth;
		for (Conversion c : sema.conversions()) {
			conversions.computeIfAbsent(c.expr(), k -> new ArrayList<>(1)).add(c);
		}
	}

	// ----- statements -------------------------------------------------------------------------------------------------

	void stmt(Stmt s) {
		switch (s) {
			case BlockStmt b -> {
				line("{");
				depth++;
				for (Stmt inner : b.statements()) {
					stmt(inner);
				}
				depth--;
				line("}");
			}
			case DeclStmt d -> {
				if (d.decl() instanceof VarDecl vd) {
					localDecl(vd);
				}
			}
			case ExprStmt e -> {
				if (e.expr() instanceof AssignExpr a && swizzleStatement(a)) {
					swizzleCompound(a);
				} else {
					line(expr(e.expr()) + ";");
				}
			}
			case IfStmt i -> {
				String condText = condition(i.condition());
				String thenText = block(i.thenStmt());
				line("if (" + condText + ") " + thenText);
				if (i.elseStmt() != null) {
					line("else " + block(i.elseStmt()));
				}
			}
			case ForStmt f -> forLoop(f);
			case WhileStmt w -> whileLoop(w);
			case DoWhileStmt d -> doWhile(d);
			case SwitchStmt sw -> switchStmt(sw);
			case CaseStmt c -> line("case " + constOrExpr(c.value()) + ":");
			case DefaultStmt d -> line("default:");
			case BreakStmt b -> line("break;");
			case ContinueStmt c -> line("continue;");
			case ReturnStmt r -> {
				if (r.value() == null) {
					line("return;");
				} else {
					String v = expr(r.value());
					if (post.isEmpty()) {
						line("return " + v + ";");
					} else {
						line(MslTypes.value(sema.typeOf(r.value())) + " qs_rv = " + v + ";");
						line("return qs_rv;");
					}
				}
			}
			case DiscardStmt d -> {
				stage.markDiscard();
				line("discard_fragment();");
			}
			case EmptyStmt e -> line(";");
		}
	}

	/** A statement as the body of a control statement: always braced, so declarations stay scoped. */
	private String block(Stmt s) {
		if (s instanceof BlockStmt) {
			int mark = out.length();
			stmt(s);
			String text = out.substring(mark).stripLeading();
			out.setLength(mark);
			return text.stripTrailing();
		}
		StringBuilder inner = new StringBuilder();
		BodyEmitter nested = new BodyEmitter(stage, sema, table, inner, 0);
		nested.stmt(s);
		return "{\n" + inner.toString().stripTrailing() + "\n" + "}";
	}

	/**
	 * A condition as the text of a control statement (an expression, or a declaration, which C++ accepts in if and while).
	 * A condition with copied out arguments emits the statements those need first, and is then the temporary that holds
	 * its value, so every write-back happens before the body runs.
	 */
	private String condition(Cond c) {
		return switch (c) {
			case ExprCond e -> settle(stripParens(expr(e.expr())), ScalarType.of(ScalarKind.BOOL));
			case DeclCond d -> {
				VarDecl vd = d.decl();
				Declarator decl = vd.declarators().get(0);
				VariableSymbol v = sema.variableOf(decl);
				String type = MslTypes.value(v.type());
				String init = initializer(decl, v.type());
				if (pre.isEmpty() && post.isEmpty()) {
					yield type + " " + stage.variableRef(v) + " = " + init;
				}
				String name = stage.variableRef(v);
				line(type + " " + name + " = " + init + ";");
				yield name;
			}
		};
	}

	/** Removes one pair of parentheses that encloses the whole text (MSL warns on {@code if ((a == b))}). */
	static String stripParens(String s) {
		if (!s.startsWith("(") || !s.endsWith(")")) {
			return s;
		}
		int depth = 0;
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c == '(') {
				depth++;
			} else if (c == ')') {
				depth--;
				if (depth == 0 && i < s.length() - 1) {
					return s;
				}
			}
		}
		return s.substring(1, s.length() - 1);
	}

	private void whileLoop(WhileStmt w) {
		if (!settles(w.condition())) {
			line("while (" + condition(w.condition()) + ") " + block(w.body()));
			return;
		}
		// A condition with copied out arguments is evaluated on every pass, inside the loop.
		line("while (true) {");
		depth++;
		line("if (!(" + condition(w.condition()) + ")) break;");
		stmt(w.body());
		depth--;
		line("}");
	}

	private void doWhile(DoWhileStmt d) {
		if (!settlesExpr(d.condition())) {
			line("do " + block(d.body()) + " while (" + expr(d.condition()) + ");");
			return;
		}
		// The condition runs after each pass through a flag, so that a continue in the body reaches it too.
		String first = stage.tempName();
		line("bool " + first + " = true;");
		line("while (true) {");
		depth++;
		line("if (!" + first + ") {");
		depth++;
		line("if (!(" + settleExpr(d.condition()) + ")) break;");
		depth--;
		line("}");
		line(first + " = false;");
		stmt(d.body());
		depth--;
		line("}");
	}

	/**
	 * A for loop. When its init, condition or step needs statements of its own (copied out arguments), the loop is
	 * written in the canonical form: the init, then a loop whose step and condition run inside it, so each iteration
	 * re-evaluates them (a continue reaches the step, as in C++).
	 */
	private void forLoop(ForStmt f) {
		boolean canonical = (f.condition() != null && settles(f.condition()))
			|| (f.step() != null && settlesExpr(f.step()))
			|| (f.init() instanceof ExprStmt e && settlesExpr(e.expr()));
		if (canonical) {
			line("{");
			depth++;
			if (f.init() instanceof DeclStmt || f.init() instanceof ExprStmt) {
				stmt(f.init());
			}
			String first = stage.tempName();
			line("bool " + first + " = true;");
			line("while (true) {");
			depth++;
			if (f.step() != null) {
				line("if (!" + first + ") {");
				depth++;
				line(settleExpr(f.step()) + ";");
				depth--;
				line("}");
			}
			line(first + " = false;");
			if (f.condition() != null) {
				line("if (!(" + condition(f.condition()) + ")) break;");
			}
			stmt(f.body());
			depth--;
			line("}");
			depth--;
			line("}");
			return;
		}
		String init = "";
		boolean declared = false;
		if (f.init() instanceof DeclStmt d && d.decl() instanceof VarDecl) {
			line("{");
			depth++;
			declared = true;
			stmt(f.init());
		} else if (f.init() instanceof ExprStmt e) {
			init = expr(e.expr());
		}
		String condText = f.condition() == null ? "" : condition(f.condition());
		String step = f.step() == null ? "" : expr(f.step());
		line("for (" + init + "; " + condText + "; " + step + ") " + block(f.body()));
		if (declared) {
			depth--;
			line("}");
		}
	}

	/** True when a condition needs statements before it: a copied out argument (see condition). */
	private boolean settles(Cond c) {
		BodyEmitter probe = new BodyEmitter(stage, sema, table, new StringBuilder(), 0);
		probe.condition(c);
		return probe.copies > 0;
	}

	private boolean settlesExpr(Expr e) {
		BodyEmitter probe = new BodyEmitter(stage, sema, table, new StringBuilder(), 0);
		probe.expr(e);
		return probe.copies > 0;
	}

	/**
	 * An expression's value after the statements its out arguments need: the value goes to a temporary declared before
	 * the statement, and each write-back follows its call. Without copied out arguments the text is returned unchanged.
	 */
	private String settle(String text, GlslType type) {
		if (pre.isEmpty() && post.isEmpty()) {
			return text;
		}
		String temp = stage.tempName();
		line(MslTypes.value(type) + " " + temp + " = " + text + ";");
		return temp;
	}

	private String settleExpr(Expr e) {
		return settle(expr(e), sema.typeOf(e));
	}

	private void switchStmt(SwitchStmt sw) {
		List<Stmt> items = sw.body() instanceof BlockStmt b ? b.statements() : List.of(sw.body());
		// Declarations directly in the switch body are hoisted above the switch: a case label may not jump over an
		// initialized declaration in C++, and GLSL allows them there.
		line("{");
		depth++;
		for (Stmt s : items) {
			if (s instanceof DeclStmt d && d.decl() instanceof VarDecl vd) {
				for (Declarator decl : vd.declarators()) {
					VariableSymbol v = sema.variableOf(decl);
					line(MslTypes.value(v.type()) + " " + stage.variableRef(v) + "{};");
				}
			}
		}
		line("switch (" + settleExpr(sw.head()) + ") {");
		depth++;
		for (Stmt s : items) {
			if (s instanceof CaseStmt || s instanceof DefaultStmt) {
				stmt(s);
				continue;
			}
			// Each statement is its own block: the out-argument copies it makes are declared there, so a case label
			// never jumps over an initialized copy.
			line("{");
			depth++;
			if (s instanceof DeclStmt d && d.decl() instanceof VarDecl vd) {
				for (Declarator decl : vd.declarators()) {
					if (decl.initializer() != null) {
						VariableSymbol v = sema.variableOf(decl);
						line(stage.variableRef(v) + " = " + initializer(decl, v.type()) + ";");
					}
				}
			} else {
				stmt(s);
			}
			depth--;
			line("}");
		}
		depth--;
		line("}");
		depth--;
		line("}");
	}

	private void localDecl(VarDecl vd) {
		for (Declarator d : vd.declarators()) {
			VariableSymbol v = sema.variableOf(d);
			String name = stage.variableRef(v);
			String type = MslTypes.value(v.type());
			// [[maybe_unused]]: a GLSL local a pack never reads is not an error, and Metal's warnings fill the
			// compiler's 4 KB error buffer before the errors that matter.
			if (d.initializer() == null) {
				line("[[maybe_unused]] " + type + " " + name + "{};");
			} else {
				line("[[maybe_unused]] " + type + " " + name + " = " + initializer(d, v.type()) + ";");
			}
		}
	}

	/** The initializer of a declarator as an expression of the declared type. */
	String initializer(Declarator d, GlslType type) {
		if (d.initializer() instanceof InitializerList list) {
			return initList(list, type);
		}
		return expr(d.initializer());
	}

	private String initList(InitializerList list, GlslType type) {
		List<String> items = new ArrayList<>();
		if (type instanceof ArrayType at) {
			for (Expr item : list.items()) {
				items.add(item instanceof InitializerList nested ? initList(nested, at.element()) : expr(item));
			}
			return MslTypes.value(type) + "{" + String.join(", ", items) + "}";
		}
		if (type instanceof StructType st) {
			for (int i = 0; i < list.items().size(); i++) {
				Expr item = list.items().get(i);
				GlslType ft = st.fields().get(i).type();
				items.add(item instanceof InitializerList nested ? initList(nested, ft) : expr(item));
			}
			return MslTypes.structName(st) + "{" + String.join(", ", items) + "}";
		}
		throw new IllegalStateException("initializer list for " + type);
	}

	private void line(String text) {
		flushPre();
		out.append("\t".repeat(depth)).append(text).append('\n');
		flushPost();
	}

	private void flushPre() {
		for (String s : pre) {
			out.append("\t".repeat(depth)).append(s).append('\n');
		}
		pre.clear();
	}

	private void flushPost() {
		for (String s : post) {
			out.append("\t".repeat(depth)).append(s).append('\n');
		}
		post.clear();
		copiedTargets.clear();
	}

	// ----- expressions ------------------------------------------------------------------------------------------------

	/** An expression as a value: constants folded, packed block members widened, recorded conversions applied. */
	String expr(Expr e) {
		if (!(e instanceof IntLiteral || e instanceof UIntLiteral || e instanceof FloatLiteral
				|| e instanceof DoubleLiteral || e instanceof BoolLiteral)) {
			ConstValue c = sema.constOf(e);
			if (c != null && isPlainValue(c.type())) {
				return applyConversions(e, constText(c));
			}
		}
		String text = raw(e, false);
		if (stage.isAtomicRef(e) && sema.typeOf(e) instanceof ScalarType) {
			text = atomicLoad(text);
		}
		if (isPackedMember(e)) {
			text = MslTypes.vector((VectorType) sema.typeOf(e)) + "(" + text + ")";
		}
		return applyConversions(e, text);
	}

	/** An expression as an assignable place (no widening, no folding): used for targets and out arguments. */
	String lvalue(Expr e) {
		return raw(e, true);
	}

	private String constOrExpr(Expr e) {
		ConstValue c = sema.constOf(e);
		return c != null ? constText(c) : expr(e);
	}

	private boolean isPlainValue(GlslType t) {
		return t instanceof ScalarType || t instanceof VectorType || t instanceof MatrixType;
	}

	private String applyConversions(Expr e, String text) {
		List<Conversion> list = conversions.get(e);
		if (list == null) {
			return text;
		}
		String result = text;
		for (Conversion c : list) {
			if (c.to() instanceof ArrayType || c.to() instanceof StructType || c.to().isError()) {
				continue;
			}
			result = castTo(c.to(), result);
		}
		return result;
	}

	/** An explicit constructor cast of a value to a scalar, vector or matrix type. */
	private String castTo(GlslType to, String text) {
		return switch (to) {
			case ScalarType s -> MslTypes.scalar(s.kind()) + "(" + text + ")";
			case VectorType v -> MslTypes.vector(v) + "(" + text + ")";
			case MatrixType m -> MslTypes.matrix(m) + "(" + text + ")";
			default -> text;
		};
	}

	/**
	 * An argument of a vector constructor, converted to the constructor's component type. Metal's constructors do not
	 * convert between a signed and an unsigned vector argument, so the conversion is explicit (GLSL 4.60 section 5.4.2).
	 */
	private String convertComponents(Expr a, ScalarKind kind) {
		GlslType at = sema.typeOf(a);
		String text = expr(a);
		if (at.componentKind() == null || at.componentKind() == kind) {
			return text;
		}
		GlslType to = at instanceof VectorType v ? VectorType.of(kind, v.size()) : ScalarType.of(kind);
		return castTo(to, text);
	}

	private String raw(Expr e, boolean lv) {
		return switch (e) {
			case IntLiteral i -> intLit(i.value());
			case UIntLiteral u -> u.value() + "u";
			case FloatLiteral f -> floatLit(f.value());
			case DoubleLiteral d -> "double(" + Double.toString(d.value()) + ")";
			case BoolLiteral b -> String.valueOf(b.value());
			case Ident id -> identText(id);
			case UnaryExpr u -> unary(u);
			case PostfixExpr p -> postfix(p);
			case BinaryExpr b -> binary(b);
			case AssignExpr a -> assign(a);
			case TernaryExpr t -> "(" + expr(t.condition()) + " ? " + expr(t.thenExpr()) + " : " + expr(t.elseExpr())
				+ ")";
			case SequenceExpr s -> {
				List<String> items = new ArrayList<>();
				for (Expr item : s.items()) {
					items.add(expr(item));
				}
				yield "(" + String.join(", ", items) + ")";
			}
			case CallExpr c -> call(c);
			case IndexExpr ix -> (lv ? lvalue(ix.base()) : expr(ix.base())) + "[" + expr(ix.index()) + "]";
			case FieldExpr f -> field(f, lv);
			case InitializerList list -> {
				stage.error(list.pos(), "initializer-list", "a brace list is only valid as an initializer");
				yield "{}";
			}
		};
	}

	private String identText(Ident id) {
		Symbol s = sema.symbolOf(id);
		if (s instanceof VariableSymbol v) {
			return stage.variableRef(v);
		}
		stage.error(id.pos(), "unknown-identifier", "'" + id.name() + "' names no variable in the emitter");
		return "0";
	}

	private String unary(UnaryExpr u) {
		return switch (u.op()) {
			case PRE_INC -> stage.isAtomicRef(u.operand()) ? atomicStep(u.operand(), true, true)
				: "(++" + lvalue(u.operand()) + ")";
			case PRE_DEC -> stage.isAtomicRef(u.operand()) ? atomicStep(u.operand(), false, true)
				: "(--" + lvalue(u.operand()) + ")";
			case NOT -> "(!" + expr(u.operand()) + ")";
			case BIT_NOT -> "(~" + expr(u.operand()) + ")";
			case PLUS -> "(+" + expr(u.operand()) + ")";
			case MINUS -> sema.typeOf(u.operand()) instanceof MatrixType ? "(-1.0f * " + expr(u.operand()) + ")"
				: "(-" + expr(u.operand()) + ")";
		};
	}

	private String binary(BinaryExpr b) {
		GlslType lt = sema.typeOf(b.left());
		GlslType rt = sema.typeOf(b.right());
		String l = expr(b.left());
		String r = expr(b.right());
		switch (b.op()) {
			case LOG_XOR:
				return "(" + l + " != " + r + ")";
			case EQ:
			case NE:
				return equality(b, lt, l, r);
			case SHL:
			case SHR:
				if (lt instanceof VectorType v && rt instanceof ScalarType) {
					r = MslTypes.vector(v) + "(" + r + ")";
				}
				return "(" + l + " " + b.op().word() + " " + r + ")";
			default:
				return "(" + l + " " + b.op().word() + " " + r + ")";
		}
	}

	private String equality(BinaryExpr b, GlslType t, String l, String r) {
		boolean eq = b.op() == BinaryOp.EQ;
		if (t instanceof ScalarType s && s.kind() == ScalarKind.FLOAT && sameValue(b.left(), b.right())) {
			return eq ? "(!isnan(" + l + "))" : "isnan(" + l + ")";
		}
		if (t instanceof VectorType v && v.component() == ScalarKind.FLOAT && sameValue(b.left(), b.right())) {
			return eq ? "(!any(isnan(" + l + ")))" : "any(isnan(" + l + "))";
		}
		String text = stage.equalityText(t, l, r);
		return eq ? text : "(!" + text + ")";
	}

	/** True when both sides are the same side-effect-free expression, so {@code x != x} means NaN. */
	private boolean sameValue(Expr a, Expr b) {
		return pure(a) && pure(b) && Trees.equalsIgnorePos(a, b);
	}

	private static boolean pure(Expr e) {
		return switch (e) {
			case Ident i -> true;
			case FieldExpr f -> pure(f.base());
			case IndexExpr i -> pure(i.base()) && (i.index() instanceof IntLiteral || i.index() instanceof Ident);
			default -> false;
		};
	}

	private String assign(AssignExpr a) {
		if (stage.isAtomicRef(a.target()) && sema.typeOf(a.target()) instanceof ScalarType) {
			return atomicAssign(a);
		}
		String target = lvalue(a.target());
		String value = expr(a.value());
		GlslType tt = sema.typeOf(a.target());
		if (a.op() == AssignOp.ASSIGN) {
			return "(" + target + " = " + value + ")";
		}
		String op = a.op().word().substring(0, a.op().word().length() - 1);
		if (isPackedMember(a.target())) {
			String widened = MslTypes.vector((VectorType) tt) + "(" + target + ")";
			return "(" + target + " = " + MslTypes.vector((VectorType) tt) + "(" + widened + " " + op + " " + value + "))";
		}
		if ((op.equals("<<") || op.equals(">>")) && tt instanceof VectorType v
				&& sema.typeOf(a.value()) instanceof ScalarType) {
			value = MslTypes.vector(v) + "(" + value + ")";
		}
		if (needsValueForm(a)) {
			if (pure(a.target())) {
				return "(" + target + " = (" + target + ") " + op + " (" + value + "))";
			}
			stage.error(a.pos(), "compound-assignment", "a compound assignment to a swizzle or a vector times a matrix"
				+ " needs a target without side effects");
			return target;
		}
		return "(" + target + " " + a.op().word() + " " + value + ")";
	}

	/**
	 * Whether a compound assignment must be written as {@code target = target op value}: MSL binds no reference to a
	 * multi-component swizzle, and it has no compound operator for a vector times a matrix.
	 */
	private boolean needsValueForm(AssignExpr a) {
		if (a.target() instanceof FieldExpr f && !(sema.typeOf(f.base()) instanceof StructType) && f.name().length() > 1) {
			return true;
		}
		return a.op().word().equals("*=") && (sema.typeOf(a.target()) instanceof MatrixType
			|| sema.typeOf(a.target()) instanceof VectorType && sema.typeOf(a.value()) instanceof MatrixType);
	}

	/**
	 * A compound assignment to a swizzle of an impure base (an index with a call in it): the statement binds the base
	 * once, so the index is evaluated once.
	 */
	private boolean swizzleStatement(AssignExpr a) {
		return a.op() != AssignOp.ASSIGN && a.target() instanceof FieldExpr f
			&& !(sema.typeOf(f.base()) instanceof StructType) && f.name().length() > 1 && !pure(f.base())
			&& !isPackedMember(a.target());
	}

	/** Emits a block that binds the base by pointer and assigns the swizzle: {@code { auto b = &base; ... }}. */
	private void swizzleCompound(AssignExpr a) {
		FieldExpr f = (FieldExpr) a.target();
		String op = a.op().word().substring(0, a.op().word().length() - 1);
		String value = expr(a.value());
		String swz = swizzle(f.name());
		line("{");
		depth++;
		line("auto qs_base = &(" + lvalue(f.base()) + ");");
		line("qs_base->" + swz + " = (qs_base->" + swz + ") " + op + " (" + value + ");");
		depth--;
		line("}");
	}

	private String field(FieldExpr f, boolean lv) {
		GlslType base = sema.typeOf(f.base());
		if (base instanceof StructType st) {
			return (lv ? lvalue(f.base()) : expr(f.base())) + "." + StageEmitter.member(f.name());
		}
		String swizzle = swizzle(f.name());
		return (lv ? lvalue(f.base()) : expr(f.base())) + "." + swizzle;
	}

	/** GLSL swizzle letters (xyzw, rgba, stpq) as MSL component names. */
	static String swizzle(String name) {
		StringBuilder sb = new StringBuilder(name.length());
		for (char c : name.toCharArray()) {
			sb.append(switch (c) {
				case 'x', 'r', 's' -> 'x';
				case 'y', 'g', 't' -> 'y';
				case 'z', 'b', 'p' -> 'z';
				case 'w', 'a', 'q' -> 'w';
				default -> c;
			});
		}
		return sb.toString();
	}

	/** True when the expression is a block member of type vec3, which Metal stores packed. */
	private boolean isPackedMember(Expr e) {
		if (!(sema.typeOf(e) instanceof VectorType v) || v.size() != 3) {
			return false;
		}
		if (e instanceof Ident id && sema.symbolOf(id) instanceof VariableSymbol vs) {
			return stage.isPackedRef(vs);
		}
		if (e instanceof FieldExpr f && sema.typeOf(f.base()) instanceof StructType st) {
			return stage.isPackedStruct(st);
		}
		return false;
	}

	// ----- calls ------------------------------------------------------------------------------------------------------

	private String call(CallExpr c) {
		CallResolution res = sema.callOf(c);
		if (res == null) {
			stage.error(c.pos(), "unresolved-call", "a call the analyzer did not resolve reached the emitter");
			return "0";
		}
		return switch (res.kind()) {
			case USER_FUNCTION -> userCall(c, res);
			case BUILTIN_FUNCTION -> builtinCall(c, res);
			case TYPE_CONSTRUCTOR -> constructor(c, sema.typeOf(c));
			case STRUCT_CONSTRUCTOR -> {
				List<String> items = new ArrayList<>();
				for (Expr a : c.args()) {
					items.add(expr(a));
				}
				yield MslTypes.value(res.resultType()) + "{" + String.join(", ", items) + "}";
			}
			case ARRAY_CONSTRUCTOR -> {
				List<String> items = new ArrayList<>();
				for (Expr a : c.args()) {
					items.add(expr(a));
				}
				yield MslTypes.value(res.resultType()) + "{" + String.join(", ", items) + "}";
			}
			case ARRAY_LENGTH -> {
				MethodCallee m = (MethodCallee) c.callee();
				yield "int(" + lvalue(m.receiver()) + ".size())";
			}
		};
	}

	/** A relaxed load of an atomic place: every access to an atomic member is relaxed (see Atomics). */
	static String atomicLoad(String place) {
		return "atomic_load_explicit(&(" + place + "), memory_order_relaxed)";
	}

	/** A relaxed store to an atomic place. */
	static String atomicStore(String place, String value) {
		return "atomic_store_explicit(&(" + place + "), " + value + ", memory_order_relaxed)";
	}

	private String postfix(PostfixExpr p) {
		if (stage.isAtomicRef(p.operand())) {
			return atomicStep(p.operand(), p.op().word().equals("++"), false);
		}
		return "(" + lvalue(p.operand()) + p.op().word() + ")";
	}

	/** An increment or decrement of an atomic place: an atomic add or subtract of one, which yields the old value. */
	private String atomicStep(Expr operand, boolean increment, boolean pre) {
		String place = lvalue(operand);
		String one = sema.typeOf(operand) instanceof ScalarType s && s.kind() == ScalarKind.UINT ? "1u" : "1";
		String fn = increment ? "atomic_fetch_add_explicit" : "atomic_fetch_sub_explicit";
		String old = fn + "(&(" + place + "), " + one + ", memory_order_relaxed)";
		if (!pre) {
			return "(" + old + ")";
		}
		return "(" + old + (increment ? " + " : " - ") + one + ")";
	}

	/** An assignment to an atomic place: a relaxed store, or for a compound operator a relaxed load and a store. */
	private String atomicAssign(AssignExpr a) {
		String place = lvalue(a.target());
		String value = expr(a.value());
		if (a.op() == AssignOp.ASSIGN) {
			return atomicStore(place, value);
		}
		String op = a.op().word().substring(0, a.op().word().length() - 1);
		return atomicStore(place, atomicLoad(place) + " " + op + " " + value);
	}

	/** An out or inout argument: a reference when MSL can bind one, else a temporary copied in and back out. */
	private String outArgument(Expr a) {
		if (referenceable(a) && !stage.isAtomicRef(a)) {
			return lvalue(a);
		}
		// MSL cannot bind a vector element, a swizzle or an atomic by reference: copy in before the statement, and
		// write back after it.
		copies++;
		String temp = stage.tempName();
		String target = lvalue(a);
		if (!copiedTargets.add(target)) {
			stage.error(a.pos(), "out-argument-alias", "one statement copies the same variable into two out arguments;"
				+ " split the statement");
		}
		boolean atomic = stage.isAtomicRef(a);
		pre.add(MslTypes.value(sema.typeOf(a)) + " " + temp + " = " + (atomic ? atomicLoad(target) : target) + ";");
		post.add((atomic ? atomicStore(target, temp) : target + " = " + temp) + ";");
		return temp;
	}

	private String userCall(CallExpr c, CallResolution res) {
		FunctionOverload f = res.overload();
		List<String> args = new ArrayList<>();
		for (int i = 0; i < c.args().size(); i++) {
			Expr a = c.args().get(i);
			FunctionOverload.Parameter p = f.params().get(i);
			if (p.writes()) {
				args.add(outArgument(a));
			} else {
				args.add(expr(a));
			}
			if (p.type() instanceof SamplerType) {
				// The sampler, then the implicit-LOD sampler: the argument's own, or the sampler itself when it has none.
				String sampler = stage.samplerOf(a, c.pos());
				String implicit = stage.implicitSamplerOf(a);
				args.add(sampler);
				args.add(implicit != null ? implicit : sampler);
				stage.markSamplerPassed(a);
			}
		}
		return stage.functionName(f) + "(" + String.join(", ", args) + ")";
	}

	/** True when MSL can bind the expression to a reference: a variable, a struct field or an array element of one. */
	private boolean referenceable(Expr e) {
		if (isPackedMember(e)) {
			return false;
		}
		return switch (e) {
			case Ident i -> true;
			case FieldExpr f -> sema.typeOf(f.base()) instanceof StructType && referenceable(f.base());
			case IndexExpr ix -> !(sema.typeOf(ix.base()) instanceof VectorType
				|| sema.typeOf(ix.base()) instanceof MatrixType) && referenceable(ix.base());
			default -> false;
		};
	}

	private String builtinCall(CallExpr c, CallResolution res) {
		BuiltinFunction bf = res.builtin();
		String name = res.name();
		if (name.startsWith("barrier") || name.startsWith("memoryBarrier") || name.equals("groupMemoryBarrier")) {
			stage.markBarrier();
		}
		List<String> argTypes = new ArrayList<>();
		List<String> args = new ArrayList<>();
		BuiltinCall.Resource resource = null;
		for (int i = 0; i < c.args().size(); i++) {
			Expr a = c.args().get(i);
			GlslType pt = res.paramTypes().get(i);
			argTypes.add(MslTypes.glslWord(pt, formatOf(a)));
			if (pt instanceof SamplerType || pt instanceof ImageType) {
				args.add(expr(a));
				if (pt instanceof SamplerType && i == 0) {
					resource = new BuiltinCall.Resource(args.get(0), stage.samplerOf(a, c.pos()), stage.implicitSamplerOf(a),
						stage.lodBiasOf(a));
				}
			} else if (i < bf.paramCount() && bf.direction(i) != StorageKind.IN) {
				args.add(Atomics.isAtomicFunction(name) ? lvalue(a) : outArgument(a));
			} else {
				args.add(expr(a));
			}
		}
		String result = MslTypes.glslWord(res.resultType(), null);
		BuiltinCall call = new BuiltinCall(name, argTypes, result, args, resource);
		Mapping m = table.map(call, stage.tableStage());
		if (m instanceof Mapping.Unsupported u) {
			stage.error(c.pos(), "unsupported-builtin", name + ": " + u.reason());
			return "0";
		}
		Mapping.Mapped mapped = (Mapping.Mapped) m;
		for (String helper : mapped.helpers()) {
			stage.requirePrelude(helper);
		}
		if (resource != null && table.readsLevels(call)) {
			stage.markLevelRead(c.args().get(0));
		} else if (resource != null && table.implicitLookup(call)) {
			stage.markImplicitRead(c.args().get(0));
		}
		if (name.equals("imageStore") && stage.tableStage() == dev.mcms.glsl.msl.builtins.BuiltinTable.Stage.COMPUTE
				&& readWriteImage(c.args().get(0))) {
			// A compute store to an image the kernel also reads is fenced: Metal orders a thread's read-write texture accesses
			// only after texture.fence(), so a thread that reads the image back (its own store, or a neighbour's after
			// barrier()) sees the write. A write-only image has no fence (access::write has none), and needs none.
			return "((" + mapped.msl() + "), " + args.get(0) + ".fence())";
		}
		return "(" + mapped.msl() + ")";
	}

	/**
	 * Whether an image argument may be read back by its stage, so its stores need the fence. A uniform is read-write when
	 * the stage reads it. Any other image is a parameter, whose uses the access scan records under the parameter's name
	 * alone: a store through it reads as write-only there, yet the caller may read the same image back, so it counts as
	 * read-write.
	 */
	private boolean readWriteImage(Expr image) {
		if (!(image instanceof Ident id) || !(sema.symbolOf(id) instanceof VariableSymbol v)) {
			return false;
		}
		return !v.quals().isUniform() || "read_write".equals(stage.access().accessOf(v.name(), "write"));
	}

	/** The image format qualifier of an image argument, so the table can pick its element type. */
	private ImageFormat formatOf(Expr e) {
		if (e instanceof Ident id && sema.symbolOf(id) instanceof VariableSymbol v) {
			return v.quals().imageFormat();
		}
		return null;
	}

	/**
	 * Constructors of scalars, vectors and matrices. MSL lacks some GLSL forms (vector truncation, diagonal and
	 * mixed matrix lists), so each is written out from its components (GLSL 4.60 section 5.4.2).
	 */
	private String constructor(CallExpr c, GlslType target) {
		List<Expr> args = c.args();
		switch (target) {
			case ScalarType s -> {
				return MslTypes.scalar(s.kind()) + "(" + expr(args.get(0)) + ")";
			}
			case VectorType v -> {
				int n = v.size();
				if (args.size() == 1) {
					GlslType at = sema.typeOf(args.get(0));
					if (at instanceof ScalarType) {
						return MslTypes.vector(v) + "(" + expr(args.get(0)) + ")";
					}
					if (at instanceof VectorType av) {
						String a = expr(args.get(0));
						if (av.size() == n) {
							return MslTypes.vector(v) + "(" + a + ")";
						}
						return MslTypes.vector(v) + "((" + a + ")." + "xyzw".substring(0, n) + ")";
					}
				}
				List<String> parts = new ArrayList<>();
				for (Expr a : args) {
					parts.add(convertComponents(a, v.component()));
				}
				return MslTypes.vector(v) + "(" + String.join(", ", parts) + ")";
			}
			case MatrixType m -> {
				return matrixConstructor(m, args);
			}
			default -> {
				stage.error(c.pos(), "constructor", "no constructor for " + target);
				return "0";
			}
		}
	}

	private String matrixConstructor(MatrixType m, List<Expr> args) {
		VectorType col = m.columnType();
		String colType = MslTypes.vector(col);
		List<String> columns = new ArrayList<>();
		if (args.size() == 1 && sema.typeOf(args.get(0)) instanceof ScalarType) {
			String s = expr(args.get(0));
			for (int c = 0; c < m.cols(); c++) {
				List<String> rows = new ArrayList<>();
				for (int r = 0; r < m.rows(); r++) {
					rows.add(c == r ? s : "0.0f");
				}
				columns.add(colType + "(" + String.join(", ", rows) + ")");
			}
		} else if (args.size() == 1 && sema.typeOf(args.get(0)) instanceof MatrixType src) {
			String a = expr(args.get(0));
			for (int c = 0; c < m.cols(); c++) {
				List<String> rows = new ArrayList<>();
				for (int r = 0; r < m.rows(); r++) {
					if (c < src.cols() && r < src.rows()) {
						rows.add("(" + a + ")[" + c + "][" + r + "]");
					} else {
						rows.add(c == r ? "1.0f" : "0.0f");
					}
				}
				columns.add(colType + "(" + String.join(", ", rows) + ")");
			}
		} else {
			List<String> comps = new ArrayList<>();
			for (Expr a : args) {
				GlslType at = sema.typeOf(a);
				String text = expr(a);
				if (at instanceof VectorType av) {
					for (int i = 0; i < av.size(); i++) {
						comps.add("(" + text + ")." + "xyzw".charAt(i));
					}
				} else if (at instanceof MatrixType am) {
					for (int c = 0; c < am.cols(); c++) {
						for (int r = 0; r < am.rows(); r++) {
							comps.add("(" + text + ")[" + c + "][" + r + "]");
						}
					}
				} else {
					comps.add(text);
				}
			}
			for (int c = 0; c < m.cols(); c++) {
				List<String> rows = new ArrayList<>();
				for (int r = 0; r < m.rows(); r++) {
					rows.add(comps.get(c * m.rows() + r));
				}
				columns.add(colType + "(" + String.join(", ", rows) + ")");
			}
		}
		return MslTypes.matrix(m) + "(" + String.join(", ", columns) + ")";
	}

	// ----- constants ---------------------------------------------------------------------------------------------------

	/** The MSL literal of a folded constant (scalar, vector, matrix, array or struct). */
	static String constText(ConstValue c) {
		GlslType t = c.type();
		if (c.isScalar()) {
			return switch (c.kind()) {
				case BOOL -> String.valueOf(c.booleanValue());
				case INT -> intLit(c.intValue());
				case UINT -> c.uintValue() + "u";
				case FLOAT -> floatLit(c.floatValue());
				case DOUBLE -> "double(" + Double.toString(c.floatValue()) + ")";
			};
		}
		List<String> parts = new ArrayList<>();
		for (ConstValue p : c.parts()) {
			parts.add(constText(p));
		}
		return switch (t) {
			case VectorType v -> MslTypes.vector(v) + "(" + String.join(", ", parts) + ")";
			case MatrixType m -> MslTypes.matrix(m) + "(" + String.join(", ", parts) + ")";
			case ArrayType a -> MslTypes.value(a) + "{" + String.join(", ", parts) + "}";
			case StructType s -> MslTypes.structName(s) + "{" + String.join(", ", parts) + "}";
			default -> throw new IllegalStateException("no constant spelling for " + t);
		};
	}

	static String intLit(long v) {
		if (v == Integer.MIN_VALUE) {
			return "(-2147483647 - 1)";
		}
		if (v >= Integer.MIN_VALUE && v <= Integer.MAX_VALUE) {
			return Long.toString(v);
		}
		return "int(" + (v & 0xFFFFFFFFL) + "u)";
	}

	static String floatLit(double v) {
		if (Double.isNaN(v)) {
			return "NAN";
		}
		float f = (float) v;
		if (Float.isInfinite(f)) {
			return f > 0 ? "INFINITY" : "(-INFINITY)";
		}
		return Float.toString(f) + "f";
	}
}
