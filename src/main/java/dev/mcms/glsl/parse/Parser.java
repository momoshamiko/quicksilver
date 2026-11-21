// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.parse;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.mcms.glsl.GlslSyntaxException;

import dev.mcms.glsl.ast.ArrayDim;
import dev.mcms.glsl.ast.AssignExpr;
import dev.mcms.glsl.ast.AssignOp;
import dev.mcms.glsl.ast.BuiltinTypeSpec;
import dev.mcms.glsl.ast.BinaryExpr;
import dev.mcms.glsl.ast.BinaryOp;
import dev.mcms.glsl.ast.BlockStmt;
import dev.mcms.glsl.ast.BoolLiteral;
import dev.mcms.glsl.ast.BreakStmt;
import dev.mcms.glsl.ast.Callee;
import dev.mcms.glsl.ast.CallExpr;
import dev.mcms.glsl.ast.CaseStmt;
import dev.mcms.glsl.ast.Cond;
import dev.mcms.glsl.ast.ContinueStmt;
import dev.mcms.glsl.ast.Declarator;
import dev.mcms.glsl.ast.DeclCond;
import dev.mcms.glsl.ast.Decl;
import dev.mcms.glsl.ast.DeclStmt;
import dev.mcms.glsl.ast.DefaultStmt;
import dev.mcms.glsl.ast.DiscardStmt;
import dev.mcms.glsl.ast.Directive;
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
import dev.mcms.glsl.ast.Ident;
import dev.mcms.glsl.ast.IfStmt;
import dev.mcms.glsl.ast.IndexExpr;
import dev.mcms.glsl.ast.InitializerList;
import dev.mcms.glsl.ast.IntLiteral;
import dev.mcms.glsl.ast.InterfaceBlock;
import dev.mcms.glsl.ast.InvariantQual;
import dev.mcms.glsl.ast.InterpKind;
import dev.mcms.glsl.ast.InterpQual;
import dev.mcms.glsl.ast.LayoutItem;
import dev.mcms.glsl.ast.LayoutQual;
import dev.mcms.glsl.ast.MemoryKind;
import dev.mcms.glsl.ast.MemoryQual;
import dev.mcms.glsl.ast.MethodCallee;
import dev.mcms.glsl.ast.NameCallee;
import dev.mcms.glsl.ast.NameTypeSpec;
import dev.mcms.glsl.ast.Param;
import dev.mcms.glsl.ast.PragmaDirective;
import dev.mcms.glsl.ast.Pos;
import dev.mcms.glsl.ast.PostfixExpr;
import dev.mcms.glsl.ast.PostfixOp;
import dev.mcms.glsl.ast.PreciseQual;
import dev.mcms.glsl.ast.PrecisionDecl;
import dev.mcms.glsl.ast.PrecisionKind;
import dev.mcms.glsl.ast.PrecisionQual;
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
import dev.mcms.glsl.ast.VersionDirective;
import dev.mcms.glsl.ast.WhileStmt;
import dev.mcms.glsl.lex.Keywords;
import dev.mcms.glsl.lex.Lexer;
import dev.mcms.glsl.lex.Token;
import dev.mcms.glsl.lex.TokenKind;

import static dev.mcms.glsl.lex.TokenKind.AND;
import static dev.mcms.glsl.lex.TokenKind.AND_EQ;
import static dev.mcms.glsl.lex.TokenKind.ASSIGN;
import static dev.mcms.glsl.lex.TokenKind.BANG;
import static dev.mcms.glsl.lex.TokenKind.COLON;
import static dev.mcms.glsl.lex.TokenKind.COMMA;
import static dev.mcms.glsl.lex.TokenKind.DASH;
import static dev.mcms.glsl.lex.TokenKind.DASH_EQ;
import static dev.mcms.glsl.lex.TokenKind.DEC;
import static dev.mcms.glsl.lex.TokenKind.DIRECTIVE;
import static dev.mcms.glsl.lex.TokenKind.DOT;
import static dev.mcms.glsl.lex.TokenKind.EOF;
import static dev.mcms.glsl.lex.TokenKind.EQ;
import static dev.mcms.glsl.lex.TokenKind.FLOAT;
import static dev.mcms.glsl.lex.TokenKind.GE;
import static dev.mcms.glsl.lex.TokenKind.GT;
import static dev.mcms.glsl.lex.TokenKind.IDENT;
import static dev.mcms.glsl.lex.TokenKind.INC;
import static dev.mcms.glsl.lex.TokenKind.INT;
import static dev.mcms.glsl.lex.TokenKind.KEYWORD;
import static dev.mcms.glsl.lex.TokenKind.LBRACE;
import static dev.mcms.glsl.lex.TokenKind.LBRACKET;
import static dev.mcms.glsl.lex.TokenKind.LE;
import static dev.mcms.glsl.lex.TokenKind.LOGAND;
import static dev.mcms.glsl.lex.TokenKind.LOGOR;
import static dev.mcms.glsl.lex.TokenKind.LOGXOR;
import static dev.mcms.glsl.lex.TokenKind.LPAREN;
import static dev.mcms.glsl.lex.TokenKind.LT;
import static dev.mcms.glsl.lex.TokenKind.NE;
import static dev.mcms.glsl.lex.TokenKind.OR;
import static dev.mcms.glsl.lex.TokenKind.OR_EQ;
import static dev.mcms.glsl.lex.TokenKind.PERCENT;
import static dev.mcms.glsl.lex.TokenKind.PLUS;
import static dev.mcms.glsl.lex.TokenKind.PLUS_EQ;
import static dev.mcms.glsl.lex.TokenKind.QUESTION;
import static dev.mcms.glsl.lex.TokenKind.RBRACE;
import static dev.mcms.glsl.lex.TokenKind.RBRACKET;
import static dev.mcms.glsl.lex.TokenKind.RPAREN;
import static dev.mcms.glsl.lex.TokenKind.SEMICOLON;
import static dev.mcms.glsl.lex.TokenKind.SHL;
import static dev.mcms.glsl.lex.TokenKind.SHL_EQ;
import static dev.mcms.glsl.lex.TokenKind.SHR;
import static dev.mcms.glsl.lex.TokenKind.SHR_EQ;
import static dev.mcms.glsl.lex.TokenKind.SLASH;
import static dev.mcms.glsl.lex.TokenKind.SLASH_EQ;
import static dev.mcms.glsl.lex.TokenKind.STAR;
import static dev.mcms.glsl.lex.TokenKind.STAR_EQ;
import static dev.mcms.glsl.lex.TokenKind.TILDE;
import static dev.mcms.glsl.lex.TokenKind.UINT;
import static dev.mcms.glsl.lex.TokenKind.XOR;
import static dev.mcms.glsl.lex.TokenKind.XOR_EQ;

/**
 * Recursive-descent parser for preprocessed GLSL 1.10 to 4.60 (grammar of the GLSL 4.60 specification, section 9, with
 * the older forms packs use: {@code attribute}, {@code varying}, prototypes without parameter names). The GLSL version
 * comes from the {@code #version} line (110 when absent), which selects the keyword set. Struct names and other
 * identifiers both count as type names when they stand where a type stands; whether the name really names a type is
 * semantic analysis's question, not the parser's. Stops at the first error with a {@link GlslSyntaxException} whose
 * message names what was expected.
 */
public final class Parser {
	private static final Pattern VERSION_LINE = Pattern.compile("#version\\s+(\\d+)(?:\\s+(\\w+))?");
	private static final Pattern EXTENSION_LINE = Pattern.compile("#extension\\s+(\\S+)\\s*:\\s*(\\S+)");

	private final List<Token> toks;
	private int i;

	private Parser(List<Token> toks) {
		this.toks = toks;
	}

	/** Parses one shader source (a translation unit). */
	public static Unit parse(String text) {
		VersionInfo info = findVersion(text);
		Parser p = new Parser(new Lexer(text, info.number(), extensionWords(text)).lex());
		return p.unit(info.directive());
	}

	private static final Pattern ENABLED_EXTENSION = Pattern.compile(
		"(?m)^\\s*#\\s*extension\\s+(GL_ARB_\\w+)\\s*:\\s*(?:enable|require)\\b");

	/**
	 * The keywords the enabled extensions of a source add, under any {@code #version} (Keywords.extensionWords). An enabled
	 * extension gives its own words only: GL_ARB_shader_image_load_store the image types and memory qualifiers, and
	 * GL_ARB_shader_storage_buffer_object the word buffer (the test pack mcms-compute declares
	 * {@code layout(r8ui) writeonly uniform uimage2D} under {@code #version 400}). Words the version does not make
	 * keywords stay identifiers, as the extension does not make them keywords.
	 */
	static Set<String> extensionWords(String text) {
		Set<String> words = new HashSet<>();
		Matcher m = ENABLED_EXTENSION.matcher(text);
		while (m.find()) {
			words.addAll(Keywords.extensionWords(m.group(1)));
		}
		return words;
	}

	private record VersionInfo(int number, VersionDirective directive) {
	}

	private static VersionInfo findVersion(String text) {
		// Only the first token decides (E9, load time: this lexed the whole text before the real pass); a bad token later in
		// the text throws in the real pass, at the same place.
		Token t = new Lexer(text, 0).first();
		if (t.kind() != DIRECTIVE) {
			return new VersionInfo(110, null);
		}
		String d = stripComment(t.text());
		if (!d.startsWith("#version")) {
			return new VersionInfo(110, null);
		}
		Matcher m = VERSION_LINE.matcher(d);
		if (!m.matches()) {
			throw new GlslSyntaxException(t.line(), t.column(), "malformed #version directive: '" + d + "'");
		}
		int number;
		try {
			number = Integer.parseInt(m.group(1));
		} catch (NumberFormatException e) {
			throw new GlslSyntaxException(t.line(), t.column(), "#version number out of range: '" + m.group(1) + "'");
		}
		VersionDirective v = new VersionDirective(new Pos(t.line(), t.column()), number, m.group(2));
		return new VersionInfo(v.number(), v);
	}

	/** Cuts a trailing {@code //} comment off a directive line. */
	static String stripComment(String line) {
		int cut = line.indexOf("//");
		return (cut >= 0 ? line.substring(0, cut) : line).strip();
	}

	private Unit unit(VersionDirective version) {
		if (version != null) {
			Token first = peek();
			if (first.kind() == DIRECTIVE) {
				next();
			}
		}
		List<Directive> directives = new ArrayList<>();
		List<Decl> decls = new ArrayList<>();
		Pos start = pos(peek());
		while (!at(EOF)) {
			if (at(DIRECTIVE)) {
				Token t = next();
				String d = stripComment(t.text());
				if (d.startsWith("#version")) {
					throw err(t, "#version must be the first line");
				}
				if (d.startsWith("#extension")) {
					Matcher m = EXTENSION_LINE.matcher(d);
					if (!m.matches()) {
						throw err(t, "malformed #extension directive: '" + d + "'");
					}
					directives.add(new ExtensionDirective(pos(t), m.group(1), m.group(2)));
					continue;
				}
				if (d.startsWith("#pragma")) {
					directives.add(new PragmaDirective(pos(t), d.substring(7).strip()));
					continue;
				}
				throw err(t, "unexpected directive after preprocessing: '" + d + "'");
			}
			if (at(SEMICOLON)) {
				next(); // a lone ';' between external declarations is legal GLSL 4.60
				continue;
			}
			decls.add(declaration());
		}
		return new Unit(start, version, List.copyOf(directives), List.copyOf(decls));
	}

	// --- Declarations ---

	private Decl declaration() {
		Pos p = pos(peek());
		List<Qualifier> quals = qualifiers();
		if (at(SEMICOLON) && !quals.isEmpty()) {
			next();
			return new QualifierDecl(p, quals, List.of(), List.of());
		}
		if (!quals.isEmpty() && (hasInvariant(quals) || hasPrecise(quals)) && at(IDENT)
				&& (peek(1).kind() == SEMICOLON || peek(1).kind() == COMMA)) {
			List<String> names = new ArrayList<>();
			List<Pos> namePositions = new ArrayList<>();
			Token first = next();
			names.add(first.text());
			namePositions.add(pos(first));
			while (at(COMMA)) {
				next();
				Token more = expect(IDENT, "an identifier after ','");
				names.add(more.text());
				namePositions.add(pos(more));
			}
			expect(SEMICOLON, "';'");
			return new QualifierDecl(p, quals, List.copyOf(names), List.copyOf(namePositions));
		}
		if (atPrecisionStatement()) {
			next(); // the word 'precision'
			PrecisionKind kind = precisionWord();
			TypeSpec spec = typeSpec();
			expect(SEMICOLON, "';'");
			return new PrecisionDecl(p, kind, spec);
		}
		if (at(IDENT) && peek(1).kind() == LBRACE) {
			return interfaceBlock(p, quals);
		}
		Type type = new Type(p, quals, typeSpec(), dims());
		if (at(IDENT) && peek(1).kind() == LPAREN) {
			return function(p, type);
		}
		if (at(IDENT)) {
			List<Declarator> ds = declarators();
			expect(SEMICOLON, "';'");
			return new VarDecl(p, type, ds);
		}
		if (type.spec() instanceof StructDefSpec && at(SEMICOLON)) {
			next();
			return new VarDecl(p, type, List.of());
		}
		throw err(peek(), "expected an identifier, found " + show(peek()));
	}

	private boolean hasInvariant(List<Qualifier> quals) {
		for (Qualifier q : quals) {
			if (q instanceof InvariantQual) {
				return true;
			}
		}
		return false;
	}

	private boolean hasPrecise(List<Qualifier> quals) {
		for (Qualifier q : quals) {
			if (q instanceof PreciseQual) {
				return true;
			}
		}
		return false;
	}

	private List<Qualifier> qualifiers() {
		List<Qualifier> q = new ArrayList<>();
		while (peek().kind() == KEYWORD && Keywords.isQualifierWord(peek().text())) {
			Pos p = pos(peek());
			switch (next().text()) {
				case "layout" -> q.add(layout(p));
				case "const" -> q.add(new StorageQual(p, StorageKind.CONST));
				case "attribute" -> q.add(new StorageQual(p, StorageKind.ATTRIBUTE));
				case "varying" -> q.add(new StorageQual(p, StorageKind.VARYING));
				case "uniform" -> q.add(new StorageQual(p, StorageKind.UNIFORM));
				case "buffer" -> q.add(new StorageQual(p, StorageKind.BUFFER));
				case "shared" -> q.add(new StorageQual(p, StorageKind.SHARED));
				case "in" -> q.add(new StorageQual(p, StorageKind.IN));
				case "out" -> q.add(new StorageQual(p, StorageKind.OUT));
				case "inout" -> q.add(new StorageQual(p, StorageKind.INOUT));
				case "centroid" -> q.add(new StorageQual(p, StorageKind.CENTROID));
				case "sample" -> q.add(new StorageQual(p, StorageKind.SAMPLE));
				case "patch" -> q.add(new StorageQual(p, StorageKind.PATCH));
				case "flat" -> q.add(new InterpQual(p, InterpKind.FLAT));
				case "smooth" -> q.add(new InterpQual(p, InterpKind.SMOOTH));
				case "noperspective" -> q.add(new InterpQual(p, InterpKind.NOPERSPECTIVE));
				case "invariant" -> q.add(new InvariantQual(p));
				case "precise" -> q.add(new PreciseQual(p));
				case "coherent" -> q.add(new MemoryQual(p, MemoryKind.COHERENT));
				case "volatile" -> q.add(new MemoryQual(p, MemoryKind.VOLATILE));
				case "restrict" -> q.add(new MemoryQual(p, MemoryKind.RESTRICT));
				case "readonly" -> q.add(new MemoryQual(p, MemoryKind.READONLY));
				case "writeonly" -> q.add(new MemoryQual(p, MemoryKind.WRITEONLY));
				case "highp" -> q.add(new PrecisionQual(p, PrecisionKind.HIGH));
				case "mediump" -> q.add(new PrecisionQual(p, PrecisionKind.MEDIUM));
				case "lowp" -> q.add(new PrecisionQual(p, PrecisionKind.LOW));
				default -> throw new IllegalStateException("unhandled qualifier word");
			}
		}
		return List.copyOf(q);
	}

	private LayoutQual layout(Pos p) {
		expect(LPAREN, "'(' after 'layout'");
		List<LayoutItem> items = new ArrayList<>();
		while (!at(RPAREN)) {
			Token t = peek();
			if (t.kind() != IDENT && t.kind() != KEYWORD) {
				throw err(t, "expected a layout qualifier name, found " + show(t));
			}
			next();
			Expr value = null;
			if (at(ASSIGN)) {
				next();
				value = assignment();
			}
			items.add(new LayoutItem(pos(t), t.text(), value));
			if (at(COMMA)) {
				next();
			} else {
				break;
			}
		}
		expect(RPAREN, "')' to close the layout qualifier");
		return new LayoutQual(p, List.copyOf(items));
	}

	/**
	 * A precision statement starts with the word {@code precision}, then a precision word, then a type. Before GLSL 1.20
	 * these words are plain identifiers, so this shape (not the keyword table) decides in those sources: a 110 source may
	 * still write {@code precision highp float;} (GLSL ES heritage), and {@code float precision = 0.5;} stays a declaration.
	 */
	private boolean atPrecisionStatement() {
		Token t0 = peek();
		if (!(t0.kind() == KEYWORD || t0.kind() == IDENT) || !t0.text().equals("precision")) {
			return false;
		}
		if (!isPrecisionWord(peek(1))) {
			return false;
		}
		Token t2 = peek(2);
		return t2.kind() == IDENT || (t2.kind() == KEYWORD && Keywords.isTypeWord(t2.text()));
	}

	private static boolean isPrecisionWord(Token t) {
		return (t.kind() == KEYWORD || t.kind() == IDENT)
				&& (t.text().equals("highp") || t.text().equals("mediump") || t.text().equals("lowp"));
	}

	private PrecisionKind precisionWord() {
		Token t = peek();
		if (!isPrecisionWord(t)) {
			throw err(t, "expected a precision word (highp, mediump or lowp), found " + show(t));
		}
		next();
		return switch (t.text()) {
			case "highp" -> PrecisionKind.HIGH;
			case "mediump" -> PrecisionKind.MEDIUM;
			default -> PrecisionKind.LOW;
		};
	}

	private TypeSpec typeSpec() {
		Token t = peek();
		if (t.kind() == KEYWORD && t.text().equals("struct")) {
			return structDef();
		}
		if (t.kind() == KEYWORD && Keywords.isTypeWord(t.text())) {
			next();
			return new BuiltinTypeSpec(pos(t), t.text());
		}
		if (t.kind() == IDENT) {
			next();
			return new NameTypeSpec(pos(t), t.text());
		}
		throw err(t, "expected a type, found " + show(t));
	}

	private StructDefSpec structDef() {
		Token s = next();
		Token nameToken = null;
		if (at(IDENT)) {
			nameToken = next();
		}
		String name = nameToken == null ? null : nameToken.text();
		Pos namePos = nameToken == null ? null : pos(nameToken);
		expect(LBRACE, "'{' after the struct name");
		List<StructMember> members = new ArrayList<>();
		while (!at(RBRACE)) {
			if (at(EOF)) {
				throw err(peek(), "expected '}' to close the struct, found end of file");
			}
			members.add(structMember());
		}
		next();
		return new StructDefSpec(pos(s), name, namePos, List.copyOf(members));
	}

	private StructMember structMember() {
		Pos p = pos(peek());
		List<Qualifier> quals = qualifiers();
		Type type = new Type(p, quals, typeSpec(), dims());
		List<Declarator> ds = new ArrayList<>();
		while (true) {
			Token n = expect(IDENT, "a member name");
			List<ArrayDim> md = dims();
			if (at(ASSIGN)) {
				throw err(peek(), "struct and block members cannot have initializers");
			}
			ds.add(new Declarator(pos(n), n.text(), md, null));
			if (at(COMMA)) {
				next();
				continue;
			}
			break;
		}
		expect(SEMICOLON, "';' after the member");
		return new StructMember(p, type, List.copyOf(ds));
	}

	private List<ArrayDim> dims() {
		List<ArrayDim> ds = new ArrayList<>();
		while (at(LBRACKET)) {
			Token lb = next();
			Expr length = null;
			if (!at(RBRACKET)) {
				length = conditional();
			}
			expect(RBRACKET, "']'");
			ds.add(new ArrayDim(pos(lb), length));
		}
		return List.copyOf(ds);
	}

	private List<Declarator> declarators() {
		List<Declarator> ds = new ArrayList<>();
		while (true) {
			Token n = expect(IDENT, "an identifier");
			List<ArrayDim> dd = dims();
			Expr init = null;
			if (at(ASSIGN)) {
				next();
				init = initializer();
			}
			ds.add(new Declarator(pos(n), n.text(), dd, init));
			if (at(COMMA)) {
				next();
				continue;
			}
			return List.copyOf(ds);
		}
	}

	/** A declarator initializer: a brace list (GLSL 4.20) or an assignment expression; list items may nest. */
	private Expr initializer() {
		if (!at(LBRACE)) {
			return assignment();
		}
		Token open = next();
		List<Expr> items = new ArrayList<>();
		while (!at(RBRACE)) {
			if (at(EOF)) {
				throw err(peek(), "expected '}' to close the initializer list, found end of file");
			}
			items.add(initializer());
			if (at(COMMA)) {
				next();
			} else {
				break;
			}
		}
		expect(RBRACE, "'}' to close the initializer list");
		return new InitializerList(pos(open), List.copyOf(items));
	}

	private Decl function(Pos p, Type returnType) {
		Token n = next();
		expect(LPAREN, "'('");
		List<Param> params = new ArrayList<>();
		if (!(atKeyword("void") && peek(1).kind() == RPAREN)) {
			while (!at(RPAREN)) {
				Pos pp = pos(peek());
				List<Qualifier> pq = qualifiers();
				Type pt = new Type(pp, pq, typeSpec(), dims());
				String pname = null;
				Pos pnamePos = null;
				List<ArrayDim> pd = List.of();
				if (at(IDENT)) {
					Token pn = next();
					pname = pn.text();
					pnamePos = pos(pn);
					pd = dims();
				}
				params.add(new Param(pp, pt, pname, pnamePos, pd));
				if (at(COMMA)) {
					next();
				} else {
					break;
				}
			}
		} else {
			next();
		}
		expect(RPAREN, "')' or a parameter");
		FunctionPrototype proto = new FunctionPrototype(p, returnType, n.text(), pos(n), List.copyOf(params));
		if (at(LBRACE)) {
			BlockStmt body = block();
			return new FunctionDefinition(p, proto, body);
		}
		expect(SEMICOLON, "';' after the prototype or '{' for the body");
		return proto;
	}

	private InterfaceBlock interfaceBlock(Pos p, List<Qualifier> quals) {
		Token name = expect(IDENT, "a block name");
		expect(LBRACE, "'{'");
		List<StructMember> members = new ArrayList<>();
		while (!at(RBRACE)) {
			if (at(EOF)) {
				throw err(peek(), "expected '}' to close the block, found end of file");
			}
			members.add(structMember());
		}
		next();
		Token instance = null;
		List<ArrayDim> id = List.of();
		if (at(IDENT)) {
			instance = next();
			id = dims();
		}
		expect(SEMICOLON, "';'");
		return new InterfaceBlock(p, quals, name.text(), pos(name), List.copyOf(members),
				instance == null ? null : instance.text(), instance == null ? null : pos(instance), id);
	}

	// --- Statements ---

	/** Skips {@code #pragma} directives that appear inside function bodies or loops (e.g. {@code #pragma unroll}). */
	private void skipBlockPragmas() {
		while (at(DIRECTIVE) && stripComment(peek().text()).startsWith("#pragma")) {
			next();
		}
	}

	private Stmt statement() {
		skipBlockPragmas();
		Token t = peek();
		if (t.kind() == LBRACE) {
			return block();
		}
		if (t.kind() == SEMICOLON) {
			next();
			return new EmptyStmt(pos(t));
		}
		if (t.kind() == KEYWORD) {
			switch (t.text()) {
				case "if" -> {
					next();
					expect(LPAREN, "'(' after 'if'");
					Cond c = cond();
					expect(RPAREN, "')'");
					Stmt thenStmt = statement();
					Stmt elseStmt = null;
					if (atKeyword("else")) {
						next();
						elseStmt = statement();
					}
					return new IfStmt(pos(t), c, thenStmt, elseStmt);
				}
				case "for" -> {
					next();
					expect(LPAREN, "'(' after 'for'");
					Stmt init;
					if (at(SEMICOLON)) {
						init = new EmptyStmt(pos(next()));
					} else if (declarationStart()) {
						init = new DeclStmt(pos(peek()), declaration());
					} else {
						Pos ip = pos(peek());
						Expr ie = expression();
						expect(SEMICOLON, "';'");
						init = new ExprStmt(ip, ie);
					}
					Cond c = null;
					if (!at(SEMICOLON)) {
						c = cond();
					}
					expect(SEMICOLON, "';'");
					Expr step = null;
					if (!at(RPAREN)) {
						step = expression();
					}
					expect(RPAREN, "')'");
					return new ForStmt(pos(t), init, c, step, statement());
				}
				case "while" -> {
					next();
					expect(LPAREN, "'(' after 'while'");
					Cond c = cond();
					expect(RPAREN, "')'");
					return new WhileStmt(pos(t), c, statement());
				}
				case "do" -> {
					next();
					Stmt body = statement();
					Token w = peek();
					if (!(w.kind() == KEYWORD && w.text().equals("while"))) {
						throw err(w, "expected 'while' after the do body, found " + show(w));
					}
					next();
					expect(LPAREN, "'(' after 'while'");
					Expr c = expression();
					expect(RPAREN, "')'");
					expect(SEMICOLON, "';'");
					return new DoWhileStmt(pos(t), body, c);
				}
				case "switch" -> {
					next();
					expect(LPAREN, "'(' after 'switch'");
					Expr head = expression();
					expect(RPAREN, "')'");
					return new SwitchStmt(pos(t), head, statement());
				}
				case "case" -> {
					next();
					Expr v = conditional();
					expect(COLON, "':' after the case value");
					return new CaseStmt(pos(t), v);
				}
				case "default" -> {
					next();
					expect(COLON, "':' after 'default'");
					return new DefaultStmt(pos(t));
				}
				case "break" -> {
					next();
					expect(SEMICOLON, "';'");
					return new BreakStmt(pos(t));
				}
				case "continue" -> {
					next();
					expect(SEMICOLON, "';'");
					return new ContinueStmt(pos(t));
				}
				case "return" -> {
					next();
					Expr v = null;
					if (!at(SEMICOLON)) {
						v = expression();
					}
					expect(SEMICOLON, "';'");
					return new ReturnStmt(pos(t), v);
				}
				case "discard" -> {
					next();
					expect(SEMICOLON, "';'");
					return new DiscardStmt(pos(t));
				}
				default -> {
				}
			}
		}
		if (constructorExpressionStart()) {
			Pos p = pos(peek());
			Expr e = expression();
			expect(SEMICOLON, "';'");
			return new ExprStmt(p, e);
		}
		if (declarationStart()) {
			return new DeclStmt(pos(peek()), declaration());
		}
		Pos p = pos(peek());
		Expr e = expression();
		expect(SEMICOLON, "';'");
		return new ExprStmt(p, e);
	}

	private BlockStmt block() {
		Token lb = expect(LBRACE, "'{'");
		List<Stmt> ss = new ArrayList<>();
		for (;;) {
			skipBlockPragmas();
			if (at(RBRACE)) {
				break;
			}
			if (at(EOF)) {
				throw err(peek(), "expected '}' to close the block, found end of file");
			}
			ss.add(statement());
		}
		next();
		return new BlockStmt(pos(lb), List.copyOf(ss));
	}

	private Cond cond() {
		if (!constructorExpressionStart() && declarationStart()) {
			Pos p = pos(peek());
			List<Qualifier> quals = qualifiers();
			Type type = new Type(p, quals, typeSpec(), dims());
			Token n = expect(IDENT, "an identifier for the declared condition");
			expect(ASSIGN, "'=' in the condition's declaration");
			Expr init = assignment();
			Declarator d = new Declarator(pos(n), n.text(), List.of(), init);
			return new DeclCond(p, new VarDecl(p, type, List.of(d)));
		}
		Token t = peek();
		return new ExprCond(pos(t), expression());
	}

	// --- Lookahead ---

	/** True when the statement or condition starting here must be a declaration. */
	private boolean declarationStart() {
		Token t = peek();
		if (t.kind() == KEYWORD) {
			return Keywords.isTypeWord(t.text()) || Keywords.isQualifierWord(t.text())
					|| t.text().equals("struct") || t.text().equals("precision");
		}
		if (t.kind() == IDENT) {
			Token t2 = peek(1);
			if (t2.kind() == IDENT) {
				return true;
			}
			if (t2.kind() == LBRACKET) {
				int after = afterBracketGroups(1);
				return after >= 0 && toks.get(after).kind() == IDENT;
			}
		}
		return false;
	}

	/** True when an expression starts here with a constructor on a type word: {@code vec4(...)} or {@code float[3](...)}.
	 * A declaration can never look like this, so this wins over {@link #declarationStart()}. */
	private boolean constructorExpressionStart() {
		Token t = peek();
		if (t.kind() != KEYWORD || !Keywords.isTypeWord(t.text())) {
			return false;
		}
		Token t2 = peek(1);
		if (t2.kind() == LPAREN) {
			return true;
		}
		if (t2.kind() == LBRACKET) {
			int after = afterBracketGroups(1);
			return after >= 0 && toks.get(after).kind() == LPAREN;
		}
		return false;
	}

	/** Absolute index of the first token after the run of {@code [ ... ]} groups starting {@code k} tokens after the
	 * current one (so {@code toks[i + k]} must be '['), or -1 when the brackets do not close. A run like
 * {@code S[2][3]} is scanned as a whole: the index points past the last group. */
	private int afterBracketGroups(int k) {
		int at = i + k;
		int depth = 0;
		while (true) {
			Token t = toks.get(Math.min(at, toks.size() - 1));
			if (t.kind() == EOF) {
				return -1;
			}
			if (t.kind() == LBRACKET) {
				depth++;
			} else if (t.kind() == RBRACKET) {
				depth--;
				if (depth == 0) {
					if (toks.get(Math.min(at + 1, toks.size() - 1)).kind() == LBRACKET) {
						at++; // another dimension follows: keep the run together
						continue;
					}
					return Math.min(at + 1, toks.size() - 1);
				}
				if (depth < 0) {
					return -1;
				}
			}
			at++;
		}
	}

	// --- Expressions ---

	private Expr expression() {
		Token t = peek();
		Expr first = assignment();
		if (!at(COMMA)) {
			return first;
		}
		List<Expr> items = new ArrayList<>();
		items.add(first);
		while (at(COMMA)) {
			next();
			items.add(assignment());
		}
		return new SequenceExpr(pos(t), List.copyOf(items));
	}

	private Expr assignment() {
		Expr e = conditional();
		AssignOp op = assignOp(peek().kind());
		if (op == null) {
			return e;
		}
		next();
		return new AssignExpr(e.pos(), op, e, assignment());
	}

	private static AssignOp assignOp(TokenKind kind) {
		return switch (kind) {
			case ASSIGN -> AssignOp.ASSIGN;
			case PLUS_EQ -> AssignOp.ADD;
			case DASH_EQ -> AssignOp.SUB;
			case STAR_EQ -> AssignOp.MUL;
			case SLASH_EQ -> AssignOp.DIV;
			case PERCENT_EQ -> AssignOp.MOD;
			case SHL_EQ -> AssignOp.SHL;
			case SHR_EQ -> AssignOp.SHR;
			case AND_EQ -> AssignOp.AND;
			case XOR_EQ -> AssignOp.XOR;
			case OR_EQ -> AssignOp.OR;
			default -> null;
		};
	}

	private Expr conditional() {
		Expr c = binary(0);
		if (!at(QUESTION)) {
			return c;
		}
		next();
		Expr thenExpr = expression();
		expect(COLON, "':' in the conditional expression");
		Expr elseExpr = assignment();
		return new TernaryExpr(c.pos(), c, thenExpr, elseExpr);
	}

	/** Binary operator groups from the loosest binding to the tightest (GLSL 4.60 section 5.1); binary(0) is called
	 * first and recurses into tighter levels, so the loosest operators sit at index 0. */
	private static final TokenKind[][] BINARY_LEVELS = {
			{LOGOR},
			{LOGXOR},
			{LOGAND},
			{OR},
			{XOR},
			{AND},
			{EQ, NE},
			{LT, GT, LE, GE},
			{SHL, SHR},
			{PLUS, DASH},
			{STAR, SLASH, PERCENT},
	};

	private Expr binary(int level) {
		if (level >= BINARY_LEVELS.length) {
			return unary();
		}
		Expr left = binary(level + 1);
		while (contains(BINARY_LEVELS[level], peek().kind())) {
			Token op = next();
			Expr right = binary(level + 1);
			left = new BinaryExpr(left.pos(), binaryOp(op.kind()), left, right);
		}
		return left;
	}


	private static boolean contains(TokenKind[] kinds, TokenKind kind) {
		for (TokenKind k : kinds) {
			if (k == kind) {
				return true;
			}
		}
		return false;
	}

	private static BinaryOp binaryOp(TokenKind kind) {
		return switch (kind) {
			case STAR -> BinaryOp.MUL;
			case SLASH -> BinaryOp.DIV;
			case PERCENT -> BinaryOp.MOD;
			case PLUS -> BinaryOp.ADD;
			case DASH -> BinaryOp.SUB;
			case SHL -> BinaryOp.SHL;
			case SHR -> BinaryOp.SHR;
			case LT -> BinaryOp.LT;
			case GT -> BinaryOp.GT;
			case LE -> BinaryOp.LE;
			case GE -> BinaryOp.GE;
			case EQ -> BinaryOp.EQ;
			case NE -> BinaryOp.NE;
			case AND -> BinaryOp.BIT_AND;
			case XOR -> BinaryOp.BIT_XOR;
			case OR -> BinaryOp.BIT_OR;
			case LOGAND -> BinaryOp.LOG_AND;
			case LOGXOR -> BinaryOp.LOG_XOR;
			case LOGOR -> BinaryOp.LOG_OR;
			default -> throw new IllegalArgumentException("not a binary operator: " + kind);
		};
	}

	private Expr unary() {
		Token t = peek();
		switch (t.kind()) {
			case INC -> {
				next();
				return new UnaryExpr(pos(t), UnaryOp.PRE_INC, unary());
			}
			case DEC -> {
				next();
				return new UnaryExpr(pos(t), UnaryOp.PRE_DEC, unary());
			}
			case PLUS -> {
				next();
				return new UnaryExpr(pos(t), UnaryOp.PLUS, unary());
			}
			case DASH -> {
				next();
				return new UnaryExpr(pos(t), UnaryOp.MINUS, unary());
			}
			case BANG -> {
				next();
				return new UnaryExpr(pos(t), UnaryOp.NOT, unary());
			}
			case TILDE -> {
				next();
				return new UnaryExpr(pos(t), UnaryOp.BIT_NOT, unary());
			}
			default -> {
				return postfix();
			}
		}
	}

	private Expr postfix() {
		Expr e = primary();
		while (true) {
			Token t = peek();
			switch (t.kind()) {
				case LPAREN -> {
					Callee callee;
					if (e instanceof Ident id) {
						callee = new NameCallee(e.pos(), id.name());
					} else if (e instanceof FieldExpr f) {
						callee = new MethodCallee(e.pos(), f.base(), f.name());
					} else {
						throw err(t, "only functions, constructors and method calls like a.length() can be called");
					}
					next();
					e = new CallExpr(e.pos(), callee, args());
				}
				case DOT -> {
					next();
					Token n = word("a member name after '.'");
					e = new FieldExpr(e.pos(), e, n.text(), pos(n));
				}
				case LBRACKET -> {
					next();
					Expr index = expression();
					expect(RBRACKET, "']'");
					e = new IndexExpr(e.pos(), e, index);
				}
				case INC -> {
					next();
					e = new PostfixExpr(e.pos(), e, PostfixOp.INC);
				}
				case DEC -> {
					next();
					e = new PostfixExpr(e.pos(), e, PostfixOp.DEC);
				}
				default -> {
					return e;
				}
			}
		}
	}

	private Expr primary() {
		Token t = peek();
		switch (t.kind()) {
			case INT -> {
				next();
				return intLiteral(t, false);
			}
			case UINT -> {
				next();
				return intLiteral(t, true);
			}
			case FLOAT -> {
				next();
				return floatLiteral(t, TokenKind.FLOAT);
			}
			case DOUBLE -> {
				next();
				return floatLiteral(t, TokenKind.DOUBLE);
			}
			case KEYWORD -> {
				if (t.text().equals("true") || t.text().equals("false")) {
					next();
					return new BoolLiteral(pos(t), t.text().equals("true"));
				}
				if (Keywords.isTypeWord(t.text())) {
					next();
					TypeSpec spec = new BuiltinTypeSpec(pos(t), t.text());
					if (at(LBRACKET)) {
						List<ArrayDim> dd = dims();
						expect(LPAREN, "'(' after the array type (array constructor)");
						return new CallExpr(pos(t), new TypeCallee(pos(t), spec, dd), args());
					}
					expect(LPAREN, "'(' — a type word in an expression must construct a value");
					return new CallExpr(pos(t), new TypeCallee(pos(t), spec, List.of()), args());
				}
				throw err(t, "expected an expression, found '" + t.text() + "'");
			}
			case IDENT -> {
				if (peek(1).kind() == LBRACKET) {
					int after = afterBracketGroups(1);
					if (after >= 0 && toks.get(after).kind() == LPAREN) {
						next();
						TypeSpec spec = new NameTypeSpec(pos(t), t.text());
						List<ArrayDim> dd = dims();
						expect(LPAREN, "'(' after the array type (array constructor)");
						return new CallExpr(pos(t), new TypeCallee(pos(t), spec, dd), args());
					}
				}
				next();
				return new Ident(pos(t), t.text());
			}
			case LPAREN -> {
				next();
				Expr e = expression();
				expect(RPAREN, "')'");
				return e;
			}
			default -> throw err(t, "expected an expression, found " + show(t));
		}
	}

	private List<Expr> args() {
		if (at(RPAREN)) {
			next();
			return List.of();
		}
		List<Expr> as = new ArrayList<>();
		while (true) {
			as.add(assignment());
			if (at(COMMA)) {
				next();
				continue;
			}
			break;
		}
		expect(RPAREN, "')' or ',' in the argument list");
		return List.copyOf(as);
	}

	private Expr intLiteral(Token t, boolean unsigned) {
		String s = t.text();
		String digits = s.endsWith("u") || s.endsWith("U") ? s.substring(0, s.length() - 1) : s;
		int radix = 10;
		if (digits.startsWith("0x") || digits.startsWith("0X")) {
			radix = 16;
			digits = digits.substring(2);
		} else if (digits.startsWith("0") && digits.length() > 1) {
			radix = 8;
		}
		long value;
		try {
			value = Long.parseLong(digits, radix);
		} catch (NumberFormatException e) {
			throw err(t, "integer literal out of range: '" + s + "'");
		}
		if (unsigned) {
			return new UIntLiteral(pos(t), s, value, value > 0xFFFF_FFFFL);
		}
		return new IntLiteral(pos(t), s, value, value > Integer.MAX_VALUE);
	}

	private Expr floatLiteral(Token t, TokenKind kind) {
		String s = t.text();
		String digits = s;
		if (kind == TokenKind.DOUBLE) {
			digits = s.substring(0, s.length() - 2);
			return new DoubleLiteral(pos(t), s, Double.parseDouble(digits));
		}
		if (s.charAt(s.length() - 1) == 'f' || s.charAt(s.length() - 1) == 'F') {
			digits = s.substring(0, s.length() - 1);
		}
		return new FloatLiteral(pos(t), s, Double.parseDouble(digits));
	}

	// --- Token helpers ---

	private Token peek() {
		return toks.get(i);
	}

	private Token peek(int k) {
		return toks.get(Math.min(i + k, toks.size() - 1));
	}

	private Token next() {
		Token t = toks.get(i);
		if (t.kind() != EOF) {
			i++;
		}
		return t;
	}

	private boolean at(TokenKind kind) {
		return peek().kind() == kind;
	}

	private boolean atKeyword(String word) {
		return peek().kind() == KEYWORD && peek().text().equals(word);
	}

	private Token expect(TokenKind kind, String what) {
		if (!at(kind)) {
			throw err(peek(), "expected " + what + ", found " + show(peek()));
		}
		return next();
	}

	private Token word(String what) {
		Token t = peek();
		if (t.kind() != IDENT && t.kind() != KEYWORD) {
			throw err(t, "expected " + what + ", found " + show(t));
		}
		return next();
	}

	private static Pos pos(Token t) {
		return new Pos(t.line(), t.column());
	}

	private static String show(Token t) {
		return t.kind() == EOF ? "end of file" : "'" + t.text() + "'";
	}

	private GlslSyntaxException err(Token t, String message) {
		return new GlslSyntaxException(t.line(), t.column(), message);
	}
}
