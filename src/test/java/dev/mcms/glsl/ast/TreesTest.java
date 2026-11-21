// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.mcms.glsl.parse.Parser;

/** The bottom-up rewrite helper beside the position-insensitive equality. */
class TreesTest {
	private static final Pos P = new Pos(1, 1);

	@Test
	void transformRewritesEveryMatchingNodeBottomUp() {
		Unit unit = Parser.parse("""
				#version 460
				uniform float alpha;
				float scale(float v) {
					return v * alpha;
				}
				""");
		Unit renamed = (Unit) Trees.transform(unit, n -> n instanceof Ident i && i.name().equals("alpha")
				? new Ident(i.pos(), "beta")
				: n);
		String printed = GlslPrinter.print(renamed);
		assertTrue(printed.contains("v * beta"), printed);
		assertTrue(!printed.contains("* alpha"), printed);
		// The unchanged declaration keeps its instance (the rewrite touches nothing there).
		assertSame(unit.decls().get(0), ((Unit) Trees.transform(unit, n -> n)).decls().get(0));
	}

	@Test
	void transformWithoutMatchesReturnsTheSameInstance() {
		Unit unit = Parser.parse("#version 460\nuniform float x;");
		assertSame(unit, Trees.transform(unit, n -> n));
	}

	@Test
	void equalsIgnorePosIgnoresNamePositions() {
		Unit a = Parser.parse("#version 460\nuniform Light sun;");
		Unit b = Parser.parse("#version 460\nuniform Light sun;");
		assertTrue(Trees.equalsIgnorePos(a, b));
	}

	private static Stmt exprStmt(String name) {
		return new ExprStmt(P, new Ident(P, name));
	}

	@Test
	void rebuiltListReplacesOneElementAndKeepsTheOthers() {
		BlockStmt block = new BlockStmt(P, List.of(exprStmt("a"), exprStmt("b"), exprStmt("c")));
		BlockStmt rebuilt = (BlockStmt) Trees.transform(block, n -> n instanceof ExprStmt e
				&& ((Ident) e.expr()).name().equals("b") ? exprStmt("B") : n);

		assertEquals(3, rebuilt.statements().size());
		assertSame(block.statements().get(0), rebuilt.statements().get(0));
		assertSame(block.statements().get(2), rebuilt.statements().get(2));
		assertEquals("B", ((Ident) ((ExprStmt) rebuilt.statements().get(1)).expr()).name());
		assertNotSame(block.statements(), rebuilt.statements());
		assertThrows(UnsupportedOperationException.class, () -> rebuilt.statements().add(exprStmt("d")));
	}

	@Test
	void unchangedListKeepsItsInstance() {
		BlockStmt block = new BlockStmt(P, List.of(exprStmt("a"), exprStmt("b")));
		BlockStmt same = (BlockStmt) Trees.transform(block, n -> n);
		assertSame(block.statements(), same.statements());
	}

	@Test
	void rewriterReturningNullIsRejectedNamingTheNode() {
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> Trees.transform(new Ident(P, "x"), n -> null));
		assertTrue(e.getMessage().contains("Ident"), e.getMessage());
	}

	@Test
	void nullInsideAListIsRejectedToo() {
		BlockStmt block = new BlockStmt(P, List.of(exprStmt("a")));
		assertThrows(IllegalArgumentException.class, () -> Trees.transform(block, n -> n instanceof ExprStmt ? null : n));
	}

	@Test
	void replacementOfTheWrongKindIsRejectedNamingTheParent() {
		// A statement slot receives an expression: the IfStmt constructor refuses it, and the message names the parent.
		IfStmt stmt = new IfStmt(P, new ExprCond(P, new Ident(P, "c")), exprStmt("x"), null);
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> Trees.transform(stmt, n -> n instanceof ExprStmt ? new Ident(P, "wrong") : n));
		assertTrue(e.getMessage().contains("IfStmt"), e.getMessage());
	}
}
