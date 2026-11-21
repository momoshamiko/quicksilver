// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.parse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

import dev.mcms.glsl.GlslSyntaxException;

import dev.mcms.glsl.ast.AssignExpr;
import dev.mcms.glsl.ast.AssignOp;
import dev.mcms.glsl.ast.BinaryExpr;
import dev.mcms.glsl.ast.BinaryOp;
import dev.mcms.glsl.ast.Pos;
import dev.mcms.glsl.ast.PrecisionDecl;
import dev.mcms.glsl.ast.StructDefSpec;
import dev.mcms.glsl.ast.VarDecl;
import dev.mcms.glsl.ast.BoolLiteral;
import dev.mcms.glsl.ast.CallExpr;
import dev.mcms.glsl.ast.DoubleLiteral;
import dev.mcms.glsl.ast.Expr;
import dev.mcms.glsl.ast.Unit;
import dev.mcms.glsl.ast.UIntLiteral;
import dev.mcms.glsl.ast.MethodCallee;
import dev.mcms.glsl.ast.NameCallee;
import dev.mcms.glsl.ast.UnaryExpr;
import dev.mcms.glsl.ast.UnaryOp;
import dev.mcms.glsl.ast.FieldExpr;
import dev.mcms.glsl.ast.FloatLiteral;
import dev.mcms.glsl.ast.Ident;
import dev.mcms.glsl.ast.IndexExpr;
import dev.mcms.glsl.ast.IntLiteral;
import dev.mcms.glsl.ast.NameCallee;
import dev.mcms.glsl.ast.PostfixExpr;
import dev.mcms.glsl.ast.PostfixOp;
import dev.mcms.glsl.ast.SequenceExpr;
import dev.mcms.glsl.ast.TernaryExpr;
import dev.mcms.glsl.ast.TypeCallee;

/** Precedence, associativity, every operator family, calls and constructors, literals and their parsed values. */
class ParserExpressionTest {
	private static BinaryExpr bin(String text) {
		return (BinaryExpr) TestParse.expr(text);
	}

	/** The right child as a binary expression (for precedence assertions). */
	private static BinaryExpr rbin(String text) {
		return (BinaryExpr) bin(text).right();
	}

	/** The left child as a binary expression (for associativity assertions). */
	private static BinaryExpr lbin(String text) {
		return (BinaryExpr) bin(text).left();
	}

	@Test
	void precedenceAcrossAllBinaryLevels() {
		assertEquals(BinaryOp.LOG_OR, bin("a || b ^^ c").op());
		assertEquals(BinaryOp.LOG_XOR, rbin("a || b ^^ c").op());
		assertEquals(BinaryOp.LOG_AND, rbin("a ^^ b && c").op());
		assertEquals(BinaryOp.BIT_OR, rbin("a && b | c").op());
		assertEquals(BinaryOp.BIT_XOR, rbin("a | b ^ c").op());
		assertEquals(BinaryOp.BIT_AND, rbin("a ^ b & c").op());
		assertEquals(BinaryOp.EQ, rbin("a & b == c").op());
		assertEquals(BinaryOp.LE, rbin("a == b <= c").op());
		assertEquals(BinaryOp.SHL, rbin("a < b << c").op());
		assertEquals(BinaryOp.ADD, rbin("a << b + c").op());
		assertEquals(BinaryOp.ADD, bin("a + b * c / d % e").op());
		assertEquals(BinaryOp.MOD, rbin("a + b * c / d % e").op());
	}

	@Test
	void leftAssociativityOfBinaryOperators() {
		BinaryExpr e = bin("a - b - c");
		assertEquals(BinaryOp.SUB, e.op());
		assertEquals(BinaryOp.SUB, lbin("a - b - c").op());
		assertEquals("c", ((Ident) e.right()).name());

		BinaryExpr div = bin("a / b / c");
		assertEquals(BinaryOp.DIV, div.op());
		assertEquals(BinaryOp.DIV, lbin("a / b / c").op());
	}

	@Test
	void parenthesesGroup() {
		BinaryExpr e = bin("(a - b) * c");
		assertEquals(BinaryOp.MUL, e.op());
		assertEquals(BinaryOp.SUB, ((BinaryExpr) e.left()).op());
	}

	@Test
	void unaryOperators() {
		assertEquals(UnaryOp.MINUS, unary("-a").op());
		assertEquals(UnaryOp.PLUS, unary("+a").op());
		assertEquals(UnaryOp.NOT, unary("!a").op());
		assertEquals(UnaryOp.BIT_NOT, unary("~a").op());
		assertEquals(UnaryOp.PRE_INC, unary("++a").op());
		assertEquals(UnaryOp.PRE_DEC, unary("--a").op());
		assertEquals(UnaryOp.MINUS, unary("- -a").op());
		assertEquals(UnaryOp.MINUS, ((UnaryExpr) unary("- -a").operand()).op());
		assertEquals(UnaryOp.NOT, unary("!!a").op());
	}

	private static UnaryExpr unary(String text) {
		return (UnaryExpr) TestParse.expr(text);
	}

	@Test
	void postfixIncrementAndDecrement() {
		PostfixExpr p = (PostfixExpr) TestParse.expr("a++");
		assertEquals(PostfixOp.INC, p.op());
		assertEquals("a", ((Ident) p.operand()).name());
		assertEquals(PostfixOp.DEC, ((PostfixExpr) TestParse.expr("a--")).op());

		BinaryExpr mix = bin("a++ + ++b");
		assertEquals(BinaryOp.ADD, mix.op());
		assertInstanceOf(PostfixExpr.class, mix.left());
		assertInstanceOf(UnaryExpr.class, mix.right());
	}

	@Test
	void assignmentIsRightAssociativeAndHasEveryForm() {
		AssignExpr e = (AssignExpr) TestParse.expr("a = b = c");
		assertEquals(AssignOp.ASSIGN, e.op());
		assertEquals(AssignOp.ASSIGN, ((AssignExpr) e.value()).op());
		assertEquals("a", ((Ident) e.target()).name());

		for (String op : new String[] {"=", "+=", "-=", "*=", "/=", "%=", "<<=", ">>=", "&=", "^=", "|="}) {
			Expr x = TestParse.expr("a " + op + " b");
			AssignExpr a = (AssignExpr) x;
			assertEquals(op, a.op().word());
		}
	}

	@Test
	void ternaryIsRightAssociative() {
		TernaryExpr t = (TernaryExpr) TestParse.expr("a ? b : c ? d : e");
		assertEquals("b", ((Ident) t.thenExpr()).name());
		TernaryExpr inner = (TernaryExpr) t.elseExpr();
		assertEquals("d", ((Ident) inner.thenExpr()).name());

		TernaryExpr grouped = (TernaryExpr) TestParse.expr("(a ? b : c) ? d : e");
		assertInstanceOf(TernaryExpr.class, grouped.condition());
	}

	@Test
	void ternaryMiddleIsAFullExpression() {
		AssignExpr outer = (AssignExpr) TestParse.expr("v = a ? 1, 2 : 3");
		TernaryExpr t = (TernaryExpr) outer.value();
		assertInstanceOf(SequenceExpr.class, t.thenExpr());
		assertEquals(2, ((SequenceExpr) t.thenExpr()).items().size());
	}

	@Test
	void commaSequence() {
		SequenceExpr s = (SequenceExpr) TestParse.expr("(a, b, c)");
		assertEquals(3, s.items().size());

		dev.mcms.glsl.ast.ForStmt f = (dev.mcms.glsl.ast.ForStmt) TestParse.body("{ for (;; i++, j--) ; }")
				.statements().getFirst();
		assertInstanceOf(SequenceExpr.class, f.step());
	}

	@Test
	void functionCalls() {
		CallExpr call = (CallExpr) TestParse.expr("f()");
		assertEquals("f", ((NameCallee) call.callee()).name());
		assertEquals(0, call.args().size());

		CallExpr nested = (CallExpr) TestParse.expr("f(g(x), h(y, z))");
		assertEquals(2, nested.args().size());
		CallExpr inner = (CallExpr) nested.args().get(1);
		assertEquals(2, inner.args().size());
	}

	@Test
	void constructors() {
		CallExpr vec = (CallExpr) TestParse.expr("vec4(vec3(1.0), 1.0)");
		TypeCallee tc = (TypeCallee) vec.callee();
		assertEquals("vec4", ((dev.mcms.glsl.ast.BuiltinTypeSpec) tc.spec()).name());
		assertEquals(0, tc.dims().size());
		CallExpr inner = (CallExpr) vec.args().get(0);
		assertEquals("vec3", ((dev.mcms.glsl.ast.BuiltinTypeSpec) ((TypeCallee) inner.callee()).spec()).name());

		CallExpr fromMatrix = (CallExpr) TestParse.expr("mat3(m)");
		assertEquals("mat3", ((dev.mcms.glsl.ast.BuiltinTypeSpec) ((TypeCallee) fromMatrix.callee()).spec()).name());

		CallExpr struct = (CallExpr) TestParse.expr("S(1.0, 2.0)");
		assertEquals("S", ((NameCallee) struct.callee()).name());
	}

	@Test
	void arrayConstructorsSizedUnsizedAndNested() {
		CallExpr sized = (CallExpr) TestParse.expr("float[3](1.0, 2.0, 3.0)");
		TypeCallee tc = (TypeCallee) sized.callee();
		assertEquals(1, tc.dims().size());
		assertEquals(3L, ((IntLiteral) tc.dims().getFirst().length()).value());
		assertEquals(3, sized.args().size());

		CallExpr unsized = (CallExpr) TestParse.expr("vec3[](a, b, c)");
		assertNull(((TypeCallee) unsized.callee()).dims().getFirst().length());

		CallExpr nested = (CallExpr) TestParse.expr("float[2](float[3](1.0, 2.0, 3.0), float[3](4.0, 5.0, 6.0))");
		assertEquals(2, nested.args().size());
		CallExpr first = (CallExpr) nested.args().get(0);
		assertEquals(3, first.args().size());

		CallExpr named = (CallExpr) TestParse.expr("S[2](a, b)");
		assertInstanceOf(dev.mcms.glsl.ast.NameTypeSpec.class, ((TypeCallee) named.callee()).spec());
	}

	@Test
	void indexingAndFields() {
		IndexExpr idx = (IndexExpr) TestParse.expr("a[0]");
		assertEquals("a", ((Ident) idx.base()).name());

		IndexExpr nested = (IndexExpr) TestParse.expr("m[1][2]");
		assertInstanceOf(IndexExpr.class, nested.base());

		IndexExpr computed = (IndexExpr) TestParse.expr("a[i + 1]");
		assertInstanceOf(BinaryExpr.class, computed.index());

		FieldExpr field = (FieldExpr) TestParse.expr("a.b");
		assertEquals("a", ((Ident) field.base()).name());
		assertEquals("b", field.name());

		FieldExpr chain = (FieldExpr) TestParse.expr("a.b.c");
		assertEquals("b", ((FieldExpr) chain.base()).name());

		FieldExpr swizzle = (FieldExpr) TestParse.expr("v.xy");
		assertEquals("xy", swizzle.name());

		FieldExpr onCall = (FieldExpr) TestParse.expr("f(x).y");
		assertInstanceOf(CallExpr.class, onCall.base());

		FieldExpr onParenthesised = (FieldExpr) TestParse.expr("(a + b).xy");
		assertInstanceOf(BinaryExpr.class, onParenthesised.base());
	}

	@Test
	void constructorExpressionStatementsAreNotDeclarations() {
		dev.mcms.glsl.ast.ExprStmt s = (dev.mcms.glsl.ast.ExprStmt) TestParse.body("{ vec3[](1.0, 2.0, 3.0); }")
				.statements().getFirst();
		assertInstanceOf(CallExpr.class, s.expr());
		dev.mcms.glsl.ast.ExprStmt plain = (dev.mcms.glsl.ast.ExprStmt) TestParse.body("{ vec4(1.0); }")
				.statements().getFirst();
		assertInstanceOf(CallExpr.class, plain.expr());
	}

	@Test
	void integerLiteralValues() {
		assertEquals(31L, ((IntLiteral) TestParse.expr("0x1F")).value());
		assertEquals(255L, ((IntLiteral) TestParse.expr("0XFF")).value());
		assertEquals(8L, ((IntLiteral) TestParse.expr("010")).value());
		assertEquals(42L, ((IntLiteral) TestParse.expr("42")).value());
		assertEquals(42L, ((UIntLiteral) TestParse.expr("42u")).value());
		assertEquals(255L, ((UIntLiteral) TestParse.expr("0xFFu")).value());
		assertEquals("42u", ((UIntLiteral) TestParse.expr("42u")).text());
	}

	@Test
	void floatAndBoolLiteralValues() {
		assertEquals(0.5, ((FloatLiteral) TestParse.expr(".5")).value(), 1e-12);
		assertEquals(1.0, ((FloatLiteral) TestParse.expr("1.")).value(), 1e-12);
		assertEquals(1000.0, ((FloatLiteral) TestParse.expr("1e3")).value(), 1e-12);
		assertEquals(0.015, ((FloatLiteral) TestParse.expr("1.5e-2")).value(), 1e-15);
		assertEquals(1.0, ((FloatLiteral) TestParse.expr("1.0f")).value(), 1e-12);
		assertEquals("1.0f", ((FloatLiteral) TestParse.expr("1.0f")).text());
		assertEquals(2.0, ((DoubleLiteral) TestParse.expr("2.0lf")).value(), 1e-12);
		assertTrue(((BoolLiteral) TestParse.expr("true")).value());
		assertEquals(false, ((BoolLiteral) TestParse.expr("false")).value());
	}

	@Test
	void expressionErrorsNameWhatWasExpected() {
		GlslSyntaxException e1 = assertThrows(GlslSyntaxException.class, () -> TestParse.expr("1 +"));
		assertTrue(e1.getMessage().contains("expected an expression"));
		GlslSyntaxException e2 = assertThrows(GlslSyntaxException.class, () -> TestParse.expr("a."));
		assertTrue(e2.getMessage().contains("a member name"));
		GlslSyntaxException e3 = assertThrows(GlslSyntaxException.class, () -> TestParse.expr("(a"));
		assertTrue(e3.getMessage().contains("')'"));
		GlslSyntaxException e4 = assertThrows(GlslSyntaxException.class, () -> TestParse.expr("f(1,)"));
		assertTrue(e4.getMessage().contains("expected an expression"));
		assertThrows(GlslSyntaxException.class, () -> TestParse.expr("float[3]"));
	}

	@Test
	void indexingAfterAnEarlierBracketedDeclarationStaysAnExpression() {
		// The bracket lookahead must start at the current token, not at the top of the file.
		Unit u = TestParse.unit("""
				#version 120
				const float[3] k = float[3](1.0, 2.0, 3.0);
				void main() {
					gl_FragData[0] = vec4(k[0]);
				}
				""");
		assertEquals(2, u.decls().size());

		Unit second = TestParse.unit("""
				#version 460
				in vec4[3] p;
				void main() {
					x[2] = y;
				}
				""");
		assertEquals(2, second.decls().size());

		Unit third = TestParse.unit("""
				#version 460
				void f() {
					a[0] = 1.0;
				}
				void main() {
					S[2] s;
				}
				""");
		assertEquals(2, third.decls().size());
	}


	@Test
	void identifierArrayDeclarationAndConstructorDeepInAFile() {
		Unit u = TestParse.unit("""
				#version 460
				uniform float noise[4];
				void main() {
					a b[3] = a[3](a(1), a(2), a(3));
				}
				""");
		assertEquals(2, u.decls().size());
	}


	@Test
	void bracketRunsOfSeveralDimensions() {
		Unit u = TestParse.unit("""
				#version 460
				void main() {
					S[2][3] s;
					vec4[2][2] m = vec4[2][2](vec4(0.0), vec4(1.0), vec4(2.0), vec4(3.0));
				}
				""");
		assertEquals(1, u.decls().size());
	}


	@Test
	void lengthMethodCalls() {
		CallExpr n = (CallExpr) TestParse.expr("a.length()");
		MethodCallee method = (MethodCallee) n.callee();
		assertEquals("length", method.name());
		assertEquals("a", ((Ident) method.receiver()).name());
		assertEquals(0, n.args().size());

		BinaryExpr cmp = bin("i < arr.length()");
		assertEquals(BinaryOp.LT, cmp.op());
		assertInstanceOf(MethodCallee.class, ((CallExpr) cmp.right()).callee());

		CallExpr chained = (CallExpr) TestParse.expr("float[3](1.0, 2.0, 3.0).length()");
		MethodCallee onCtor = (MethodCallee) chained.callee();
		assertInstanceOf(TypeCallee.class, ((CallExpr) onCtor.receiver()).callee());

		// chained through a swizzle-free field call: m[0].length() parses
		CallExpr viaIndex = (CallExpr) TestParse.expr("m[0].length()");
		assertInstanceOf(IndexExpr.class, ((MethodCallee) viaIndex.callee()).receiver());
	}

	@Test
	void indexBracketsHoldFullExpressions() {
		Unit u = TestParse.unit("""
				#version 460
				void main() {
					a[i += 1] = 0.0;
					x = a[(i = 1)];
				}
				""");
		String printed = dev.mcms.glsl.ast.GlslPrinter.print(u);
		Unit again = Parser.parse(printed);
		assertTrue(dev.mcms.glsl.ast.Trees.equalsIgnorePos(u, again), printed);

		dev.mcms.glsl.ast.BlockStmt body = ((dev.mcms.glsl.ast.FunctionDefinition) again.decls().getFirst()).body();
		IndexExpr second = (IndexExpr) ((AssignExpr) ((dev.mcms.glsl.ast.ExprStmt) body.statements().get(1)).expr())
				.value();
		assertInstanceOf(AssignExpr.class, second.index());
	}

	@Test
	void integerLiteralsFlagValuesOutsideSigned32BitRange() {
		// exceedsInt32 is informational: GLSL 4.x takes 0xFFFFFFFF and 2147483648 as int bit patterns too.
		assertFalse(((IntLiteral) TestParse.expr("2147483647")).exceedsInt32());
		assertTrue(((IntLiteral) TestParse.expr("2147483648")).exceedsInt32());
		assertTrue(((IntLiteral) TestParse.expr("4294967295")).exceedsInt32());
		assertEquals(4294967295L, ((IntLiteral) TestParse.expr("4294967295")).value());
		assertTrue(((IntLiteral) TestParse.expr("0xFFFFFFFF")).exceedsInt32());
		assertEquals(4294967295L, ((IntLiteral) TestParse.expr("0xFFFFFFFF")).value());
	}

	@Test
	void unsignedLiteralsFlagValuesOutsideUnsigned32BitRange() {
		assertFalse(((UIntLiteral) TestParse.expr("4294967295u")).exceedsUInt32());
		assertTrue(((UIntLiteral) TestParse.expr("4294967296u")).exceedsUInt32());
		assertFalse(((UIntLiteral) TestParse.expr("0xFFFFFFFFu")).exceedsUInt32());
	}

	@Test
	void precisionWordsAreAcceptedAsStatementsWithoutVersion() {
		// 110 sources: highp/mediump/lowp are identifiers, so the statement's shape decides.
		Unit u = TestParse.unit("precision highp float;\nfloat precision = 0.5;\n");
		assertEquals(2, u.decls().size());
		assertTrue(u.decls().get(0) instanceof dev.mcms.glsl.ast.PrecisionDecl);
		assertEquals("precision", ((VarDecl) u.decls().get(1)).declarators().getFirst().name());
	}

	@Test
	void structNameKeepsItsPosition() {
		VarDecl v = TestParse.varDecl("struct Light { vec3 p; } sun;");
		StructDefSpec spec = (StructDefSpec) v.type().spec();
		assertEquals("Light", spec.name());
		assertEquals(new Pos(2, 8), spec.namePos());
	}
}
