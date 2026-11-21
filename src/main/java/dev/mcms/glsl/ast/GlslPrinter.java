// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.ast;

import java.util.List;

/**
 * Prints any AST node back as valid GLSL. Parentheses are added exactly where precedence needs them (the levels of
 * {@link BinaryOp} and the fixed levels below), literal spelling is preserved, statements keep their structure (no
 * braces are added), and directives print as their lines. Printing a parsed tree and parsing the output therefore
 * yields the same tree. Later stages reuse this printer to emit GLSL for the standard road.
 */
public final class GlslPrinter {
	// Fixed expression levels; binary operator levels (3-13) live in BinaryOp.
	private static final int POSTFIX = 1;
	private static final int UNARY = 2;
	private static final int LOG_OR = 13;
	private static final int TERNARY = 14;
	private static final int ASSIGN = 15;
	private static final int SEQUENCE = 16;

	private GlslPrinter() {
	}

	public static String print(GlslNode node) {
		StringBuilder b = new StringBuilder();
		switch (node) {
			case Unit u -> unit(u, b);
			case Decl d -> {
				decl(d, 0, b);
				if (!(d instanceof FunctionDefinition)) {
					b.append(';');
				}
			}
			case Stmt s -> stmt(s, 0, b);
			case Expr e -> expr(e, SEQUENCE, b);
			case Type t -> type(t, 0, b);
			case Qualifier q -> qualifier(q, 0, b);
			case TypeSpec s -> typeSpec(s, 0, b);
			case GlslNode n -> throw new IllegalArgumentException("cannot print " + n.getClass().getSimpleName());
		}
		return b.toString();
	}

	private static void unit(Unit u, StringBuilder b) {
		if (u.version() != null) {
			b.append("#version ").append(u.version().number());
			if (u.version().profile() != null) {
				b.append(' ').append(u.version().profile());
			}
			b.append('\n');
		}
		// Directives and declarations are emitted in source order (Unit.itemsInOrder), so a rewritten unit keeps
		// extension pragmas where the pack put them.
		boolean previousWasDecl = false;
		for (GlslNode item : u.itemsInOrder()) {
			if (item instanceof Directive d) {
				if (previousWasDecl) {
					b.append('\n'); // a directive must start a line
				}
				switch (d) {
					case ExtensionDirective e -> b.append("#extension ").append(e.name()).append(" : ")
							.append(e.behavior()).append('\n');
					case PragmaDirective p -> b.append("#pragma ").append(p.text()).append('\n');
					case VersionDirective v -> b.append("#version ").append(v.number())
							.append(v.profile() == null ? "" : " " + v.profile()).append('\n');
				}
				previousWasDecl = false;
			} else {
				Decl d = (Decl) item;
				if (previousWasDecl) {
					b.append('\n'); // blank line between declarations
				}
				decl(d, 0, b);
				if (!(d instanceof FunctionDefinition)) {
					b.append(';');
				}
				previousWasDecl = true;
			}
		}
		b.append('\n');
	}

	private static void decl(Decl d, int depth, StringBuilder b) {
		switch (d) {
			case VarDecl v -> {
				type(v.type(), depth, b);
				if (!v.declarators().isEmpty()) {
					b.append(' ');
					joinDeclarators(v.declarators(), b);
				}
			}
			case FunctionPrototype f -> {
				type(f.returnType(), depth, b);
				b.append(' ').append(f.name()).append('(');
				boolean first = true;
				for (Param p : f.params()) {
					if (!first) {
						b.append(", ");
					}
					first = false;
					type(p.type(), depth, b);
					if (p.name() != null) {
						b.append(' ').append(p.name());
						dims(p.dims(), b);
					}
				}
				b.append(')');
			}
			case FunctionDefinition f -> {
				decl(f.proto(), depth, b);
				b.append(' ');
				stmt(f.body(), depth, b);
			}
			case InterfaceBlock k -> {
				for (Qualifier q : k.qualifiers()) {
					qualifier(q, depth, b);
					b.append(' ');
				}
				b.append(k.name()).append(" {\n");
				for (StructMember m : k.members()) {
					b.append(indent(depth + 1));
					member(m, depth + 1, b);
					b.append('\n');
				}
				b.append(indent(depth)).append('}');
				if (k.instance() != null) {
					b.append(' ').append(k.instance());
					dims(k.instanceDims(), b);
				}
			}
			case PrecisionDecl p -> {
				b.append("precision ").append(p.precision().word()).append(' ');
				typeSpec(p.type(), depth, b);
			}
			case QualifierDecl q -> {
				boolean firstQualifier = true;
				for (Qualifier k : q.qualifiers()) {
					if (!firstQualifier) {
						b.append(' ');
					}
					firstQualifier = false;
					qualifier(k, depth, b);
				}
				if (!q.names().isEmpty() && !q.qualifiers().isEmpty()) {
					b.append(' ');
				}
				boolean first = true;
				for (String n : q.names()) {
					if (!first) {
						b.append(", ");
					}
					first = false;
					b.append(n);
				}
			}
		}
	}

	private static void member(StructMember m, int depth, StringBuilder b) {
		type(m.type(), depth, b);
		if (!m.declarators().isEmpty()) {
			b.append(' ');
			joinDeclarators(m.declarators(), b);
		}
		b.append(';');
	}

	private static void joinDeclarators(List<Declarator> ds, StringBuilder b) {
		boolean first = true;
		for (Declarator d : ds) {
			if (!first) {
				b.append(", ");
			}
			first = false;
			b.append(d.name());
			dims(d.dims(), b);
			if (d.initializer() != null) {
				b.append(" = ");
				expr(d.initializer(), ASSIGN, b);
			}
		}
	}

	private static void type(Type t, int depth, StringBuilder b) {
		for (Qualifier q : t.qualifiers()) {
			qualifier(q, depth, b);
			b.append(' ');
		}
		typeSpec(t.spec(), depth, b);
		dims(t.dims(), b);
	}

	private static void typeSpec(TypeSpec s, int depth, StringBuilder b) {
		switch (s) {
			case BuiltinTypeSpec t -> b.append(t.name());
			case NameTypeSpec t -> b.append(t.name());
			case StructDefSpec t -> {
				b.append("struct");
				if (t.name() != null) {
					b.append(' ').append(t.name());
				}
				b.append(" {\n");
				for (StructMember m : t.members()) {
					b.append(indent(depth + 1));
					member(m, depth + 1, b);
					b.append('\n');
				}
				b.append(indent(depth)).append('}');
			}
		}
	}

	private static void qualifier(Qualifier q, int depth, StringBuilder b) {
		switch (q) {
			case StorageQual s -> b.append(s.kind().word());
			case InterpQual s -> b.append(s.kind().word());
			case MemoryQual s -> b.append(s.kind().word());
			case PrecisionQual s -> b.append(s.kind().word());
			case InvariantQual s -> b.append("invariant");
			case PreciseQual s -> b.append("precise");
			case LayoutQual l -> {
				b.append("layout(");
				boolean first = true;
				for (LayoutItem i : l.items()) {
					if (!first) {
						b.append(", ");
					}
					first = false;
					b.append(i.name());
					if (i.value() != null) {
						b.append(" = ");
						expr(i.value(), ASSIGN, b);
					}
				}
				b.append(')');
			}
		}
	}

	private static void dims(List<ArrayDim> ds, StringBuilder b) {
		for (ArrayDim d : ds) {
			b.append('[');
			if (d.length() != null) {
				expr(d.length(), TERNARY, b);
			}
			b.append(']');
		}
	}

	private static void stmt(Stmt s, int depth, StringBuilder b) {
		switch (s) {
			case BlockStmt k -> block(k, depth, b);
			case DeclStmt d -> {
				decl(d.decl(), depth, b);
				b.append(';');
			}
			case ExprStmt e -> {
				expr(e.expr(), SEQUENCE, b);
				b.append(';');
			}
			case IfStmt i -> {
				b.append("if (");
				cond(i.condition(), b);
				b.append(") ");
				// A rebuilt then-branch that ends in an open if (through else chains and loop bodies) would capture our
				// else when re-parsed; the parser never produces it, so it gets braces here.
				if (i.elseStmt() != null && !(i.thenStmt() instanceof BlockStmt) && endsInOpenIf(i.thenStmt())) {
					b.append("{\n").append(indent(depth + 1));
					stmt(i.thenStmt(), depth + 1, b);
					b.append('\n').append(indent(depth)).append('}');
				} else {
					inline(i.thenStmt(), depth, b);
				}
				if (i.elseStmt() != null) {
					b.append(" else ");
					inline(i.elseStmt(), depth, b);
				}
			}
			case ForStmt f -> {
				b.append("for (");
				forInit(f.init(), depth, b);
				b.append(';');
				if (f.condition() != null) {
					b.append(' ');
					cond(f.condition(), b);
				}
				b.append(';');
				if (f.step() != null) {
					b.append(' ');
					expr(f.step(), SEQUENCE, b);
				}
				b.append(") ");
				inline(f.body(), depth, b);
			}
			case WhileStmt w -> {
				b.append("while (");
				cond(w.condition(), b);
				b.append(") ");
				inline(w.body(), depth, b);
			}
			case DoWhileStmt d -> {
				b.append("do ");
				inline(d.body(), depth, b);
				b.append(" while (");
				expr(d.condition(), SEQUENCE, b);
				b.append(");");
			}
			case SwitchStmt s2 -> {
				b.append("switch (");
				expr(s2.head(), SEQUENCE, b);
				b.append(") ");
				inline(s2.body(), depth, b);
			}
			case CaseStmt c -> {
				b.append("case ");
				expr(c.value(), TERNARY, b);
				b.append(':');
			}
			case DefaultStmt d -> b.append("default:");
			case BreakStmt d -> b.append("break;");
			case ContinueStmt d -> b.append("continue;");
			case ReturnStmt r -> {
				b.append("return");
				if (r.value() != null) {
					b.append(' ');
					expr(r.value(), SEQUENCE, b);
				}
				b.append(';');
			}
			case DiscardStmt d -> b.append("discard;");
			case EmptyStmt d -> b.append(';');
		}
	}

	/**
	 * True when printing {@code s} leaves an if without an else at its very end, reached through else branches and loop
	 * bodies. A following {@code else} would bind to that inner if when the text is parsed again.
	 */
	private static boolean endsInOpenIf(Stmt s) {
		return switch (s) {
			case IfStmt i -> i.elseStmt() == null || endsInOpenIf(i.elseStmt());
			case WhileStmt w -> endsInOpenIf(w.body());
			case ForStmt f -> endsInOpenIf(f.body());
			default -> false;
		};
	}

	private static void inline(Stmt s, int depth, StringBuilder b) {
		if (s instanceof BlockStmt k) {
			block(k, depth, b);
		} else {
			stmt(s, depth, b);
		}
	}

	private static void block(BlockStmt k, int depth, StringBuilder b) {
		b.append("{\n");
		for (Stmt s : k.statements()) {
			b.append(indent(depth + 1));
			stmt(s, depth + 1, b);
			b.append('\n');
		}
		b.append(indent(depth)).append('}');
	}

	private static void forInit(Stmt init, int depth, StringBuilder b) {
		switch (init) {
			case EmptyStmt e -> {
			}
			case DeclStmt d -> decl(d.decl(), depth, b);
			case ExprStmt e -> expr(e.expr(), SEQUENCE, b);
			default -> stmt(init, depth, b);
		}
	}

	private static void cond(Cond c, StringBuilder b) {
		switch (c) {
			case ExprCond e -> expr(e.expr(), SEQUENCE, b);
			case DeclCond d -> {
				type(d.decl().type(), 0, b);
				Declarator only = d.decl().declarators().getFirst();
				b.append(' ').append(only.name()).append(" = ");
				expr(only.initializer(), ASSIGN, b);
			}
		}
	}

	private static void expr(Expr e, int context, StringBuilder b) {
		boolean parens = level(e) > context;
		if (parens) {
			b.append('(');
		}
		switch (e) {
			case IntLiteral l -> b.append(l.text());
			case UIntLiteral l -> b.append(l.text());
			case FloatLiteral l -> b.append(l.text());
			case DoubleLiteral l -> b.append(l.text());
			case BoolLiteral l -> b.append(l.value());
			case Ident i -> b.append(i.name());
			case UnaryExpr u -> {
				b.append(u.op().word());
				String operand = printInner(u.operand(), UNARY);
				if (needsSpace(u.op().word(), operand)) {
					b.append(' ');
				}
				b.append(operand);
			}
			case PostfixExpr p -> {
				expr(p.operand(), POSTFIX, b);
				b.append(p.op().word());
			}
			case BinaryExpr x -> {
				expr(x.left(), x.op().level(), b);
				b.append(' ').append(x.op().word()).append(' ');
				expr(x.right(), x.op().level() - 1, b);
			}
			case AssignExpr a -> {
				expr(a.target(), UNARY, b);
				b.append(' ').append(a.op().word()).append(' ');
				expr(a.value(), ASSIGN, b);
			}
			case TernaryExpr t -> {
				expr(t.condition(), LOG_OR, b);
				b.append(" ? ");
				expr(t.thenExpr(), SEQUENCE, b);
				b.append(" : ");
				expr(t.elseExpr(), ASSIGN, b);
			}
			case SequenceExpr s -> {
				boolean first = true;
				for (Expr item : s.items()) {
					if (!first) {
						b.append(", ");
					}
					first = false;
					expr(item, ASSIGN, b);
				}
			}
			case CallExpr c -> {
				switch (c.callee()) {
					case NameCallee n -> b.append(n.name());
					case MethodCallee m -> {
						expr(m.receiver(), POSTFIX, b);
						b.append('.').append(m.name());
					}
					case TypeCallee t -> {
						typeSpec(t.spec(), 0, b);
						dims(t.dims(), b);
					}
				}
				b.append('(');
				boolean first = true;
				for (Expr a : c.args()) {
					if (!first) {
						b.append(", ");
					}
					first = false;
					expr(a, ASSIGN, b);
				}
				b.append(')');
			}
			case IndexExpr x -> {
				expr(x.base(), POSTFIX, b);
				b.append('[');
				expr(x.index(), SEQUENCE, b);
				b.append(']');
			}
			case FieldExpr f -> {
				expr(f.base(), POSTFIX, b);
				b.append('.').append(f.name());
			}
			case InitializerList l -> {
				b.append('{');
				boolean first = true;
				for (Expr item : l.items()) {
					if (!first) {
						b.append(", ");
					}
					first = false;
					expr(item, ASSIGN, b);
				}
				b.append('}');
			}
		}
		if (parens) {
			b.append(')');
		}
	}

	private static String printInner(Expr e, int context) {
		StringBuilder b = new StringBuilder();
		expr(e, context, b);
		return b.toString();
	}

	private static int level(Expr e) {
		return switch (e) {
			case UnaryExpr u -> UNARY;
			case BinaryExpr x -> x.op().level();
			case TernaryExpr t -> TERNARY;
			case AssignExpr a -> ASSIGN;
			case SequenceExpr s -> SEQUENCE;
			default -> POSTFIX;
		};
	}

	/** GLSL has no {@code --} or {@code ++} words: keep two adjacent sign characters from merging into one operator. */
	private static boolean needsSpace(String op, String operand) {
		if (!op.equals("+") && !op.equals("-") && !op.equals("++") && !op.equals("--")) {
			return false;
		}
		return operand.startsWith("+") || operand.startsWith("-");
	}

	private static String indent(int depth) {
		return "\t".repeat(depth);
	}
}
