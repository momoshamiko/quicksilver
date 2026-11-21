// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.parse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.mcms.glsl.ast.BlockStmt;
import dev.mcms.glsl.ast.BreakStmt;
import dev.mcms.glsl.ast.CaseStmt;
import dev.mcms.glsl.ast.ContinueStmt;
import dev.mcms.glsl.ast.DeclCond;
import dev.mcms.glsl.ast.DeclStmt;
import dev.mcms.glsl.ast.DefaultStmt;
import dev.mcms.glsl.ast.DiscardStmt;
import dev.mcms.glsl.ast.DoWhileStmt;
import dev.mcms.glsl.ast.EmptyStmt;
import dev.mcms.glsl.ast.ExprCond;
import dev.mcms.glsl.ast.ExprStmt;
import dev.mcms.glsl.ast.ForStmt;
import dev.mcms.glsl.ast.IfStmt;
import dev.mcms.glsl.ast.ReturnStmt;
import dev.mcms.glsl.ast.Stmt;
import dev.mcms.glsl.ast.SwitchStmt;
import dev.mcms.glsl.ast.VarDecl;
import dev.mcms.glsl.ast.WhileStmt;
import dev.mcms.glsl.GlslSyntaxException;

/** Every statement form, including switch fall-through, condition declarations and for-loop shapes. */
class ParserStatementTest {
	private static List<Stmt> stmts(String block) {
		return TestParse.body(block).statements();
	}

	private static Stmt one(String inner) {
		return stmts("{ " + inner + " }").getFirst();
	}

	@Test
	void compoundAndEmptyStatements() {
		assertEquals(3, stmts("{ ; ; ; }").size());
		assertInstanceOf(EmptyStmt.class, one(";"));
	}

	@Test
	void localDeclarationAndLocalStruct() {
		DeclStmt d = (DeclStmt) one("float x = 1.0;");
		assertEquals("x", ((VarDecl) d.decl()).declarators().getFirst().name());

		DeclStmt s = (DeclStmt) one("struct S { int y; } instance;");
		VarDecl v = (VarDecl) s.decl();
		assertEquals("S", ((dev.mcms.glsl.ast.StructDefSpec) v.type().spec()).name());
		assertEquals("instance", v.declarators().getFirst().name());
	}

	@Test
	void ifElseAndDanglingElse() {
		IfStmt plain = (IfStmt) one("if (a) x = 1;");
		assertNull(plain.elseStmt());
		assertInstanceOf(ExprCond.class, plain.condition());

		IfStmt chain = (IfStmt) one("if (a) if (b) x = 1; else x = 2;");
		assertNull(chain.elseStmt());
		IfStmt inner = (IfStmt) chain.thenStmt();
		assertNotNullExpr(inner.elseStmt());

		IfStmt elsed = (IfStmt) one("if (a) x = 1; else if (b) x = 2; else x = 3;");
		assertInstanceOf(IfStmt.class, elsed.elseStmt());
		IfStmt second = (IfStmt) elsed.elseStmt();
		assertInstanceOf(ExprStmt.class, second.elseStmt());
	}

	private static void assertNotNullExpr(Stmt s) {
		assertTrue(s != null, "the else belongs to the inner if");
	}

	@Test
	void ifAndWhileConditionsMayBeDeclarations() {
		IfStmt d = (IfStmt) one("if (bool done = count() > 0) --i;");
		DeclCond c = (DeclCond) d.condition();
		VarDecl v = c.decl();
		assertEquals("done", v.declarators().getFirst().name());

		WhileStmt w = (WhileStmt) one("while (bool more = step()) ++i;");
		assertInstanceOf(DeclCond.class, w.condition());

		WhileStmt plain = (WhileStmt) one("while (i < 4) ++i;");
		assertInstanceOf(ExprCond.class, plain.condition());
	}

	@Test
	void forParts() {
		ForStmt full = (ForStmt) one("for (int i = 0; i < 4; ++i) sum += i;");
		assertInstanceOf(DeclStmt.class, full.init());
		assertInstanceOf(ExprCond.class, full.condition());
		assertTrue(full.step() != null);

		ForStmt noInit = (ForStmt) one("for (; i < 4;) ++i;");
		assertInstanceOf(EmptyStmt.class, noInit.init());

		ForStmt empty = (ForStmt) one("for (;;) break;");
		assertInstanceOf(EmptyStmt.class, empty.init());
		assertNull(empty.condition());
		assertNull(empty.step());

		ForStmt multiInit = (ForStmt) one("for (int i = 0, j = 8; i < j; ++i, --j) sum += i;");
		DeclStmt init = (DeclStmt) multiInit.init();
		assertEquals(2, ((VarDecl) init.decl()).declarators().size());
		assertInstanceOf(dev.mcms.glsl.ast.SequenceExpr.class, multiInit.step());

		ForStmt declCond = (ForStmt) one("for (; bool ok = step2();) ++i;");
		assertInstanceOf(DeclCond.class, declCond.condition());
	}

	@Test
	void doWhile() {
		DoWhileStmt d = (DoWhileStmt) one("do { ++i; } while (i < 8);");
		assertInstanceOf(BlockStmt.class, d.body());
		assertEquals(1, ((BlockStmt) d.body()).statements().size());
	}

	@Test
	void switchPreservesLabelOrderAndFallThrough() {
		SwitchStmt s = (SwitchStmt) one("""
				switch (slot & 3u) {
					case 0u:
						tile += 1.0;
					case 1u:
						tile += 2.0;
						break;
					default:
						break;
				}""");
		assertInstanceOf(BlockStmt.class, s.body());
		List<Stmt> body = ((BlockStmt) s.body()).statements();
		assertEquals(7, body.size());
		CaseStmt c0 = (CaseStmt) body.get(0);
		assertEquals(0L, ((dev.mcms.glsl.ast.UIntLiteral) c0.value()).value());
		assertInstanceOf(ExprStmt.class, body.get(1));
		CaseStmt c1 = (CaseStmt) body.get(2);
		assertEquals(1L, ((dev.mcms.glsl.ast.UIntLiteral) c1.value()).value());
		assertInstanceOf(ExprStmt.class, body.get(3));
		assertInstanceOf(BreakStmt.class, body.get(4));
		assertInstanceOf(DefaultStmt.class, body.get(5));
		assertInstanceOf(BreakStmt.class, body.get(6));
	}

	@Test
	void caseValuesAreExpressions() {
		CaseStmt c = (CaseStmt) one("case 1 + 2: x = 1;");
		assertEquals(dev.mcms.glsl.ast.BinaryOp.ADD, ((dev.mcms.glsl.ast.BinaryExpr) c.value()).op());
	}

	@Test
	void jumpsReturnsAndDiscard() {
		assertInstanceOf(BreakStmt.class, one("break;"));
		assertInstanceOf(ContinueStmt.class, one("continue;"));
		assertInstanceOf(DiscardStmt.class, one("discard;"));
		ReturnStmt bare = (ReturnStmt) one("return;");
		assertNull(bare.value());
		ReturnStmt value = (ReturnStmt) one("return a + b;");
		assertTrue(value.value() != null);
	}

	@Test
	void statementPositions() {
		Stmt s = ((dev.mcms.glsl.ast.FunctionDefinition) Parser.parse("void main() {\n\tx = 1;\n}").decls()
				.getFirst()).body().statements().getFirst();
		assertEquals(2, s.pos().line());
		assertEquals(2, s.pos().column());
	}

	@Test
	void unterminatedBlockThrows() {
		GlslSyntaxException e = assertThrows(GlslSyntaxException.class, () -> TestParse.body("{ x = 1; "));
		assertTrue(e.getMessage().contains("'}'"));
	}

	@Test
	void missingSemicolonNamesTheExpectation() {
		GlslSyntaxException e = assertThrows(GlslSyntaxException.class, () -> one("x = 1 y = 2;"));
		assertTrue(e.getMessage().contains("expected ';'"));
	}
}
