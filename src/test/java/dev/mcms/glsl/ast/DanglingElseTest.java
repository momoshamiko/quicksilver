// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.mcms.glsl.parse.Parser;

/**
 * Rebuilt trees the parser never produces: an if-else whose then-branch ends in an open if (through if/else chains and
 * loop bodies). Printed and re-parsed, the else must stay on the outer if.
 */
class DanglingElseTest {
	private static final Pos P = new Pos(1, 1);

	private static Cond cond() {
		return new ExprCond(P, new Ident(P, "c"));
	}

	private static Stmt x() {
		return new ExprStmt(P, new Ident(P, "x"));
	}

	private static Stmt ifOnly(Stmt then) {
		return new IfStmt(P, cond(), then, null);
	}

	private static Stmt ifElse(Stmt then, Stmt otherwise) {
		return new IfStmt(P, cond(), then, otherwise);
	}

	private static Stmt whileOf(Stmt body) {
		return new WhileStmt(P, cond(), body);
	}

	private static Stmt forOf(Stmt body) {
		return new ForStmt(P, new EmptyStmt(P), null, null, body);
	}

	private static FunctionDefinition functionOf(Stmt s) {
		return new FunctionDefinition(P,
				new FunctionPrototype(P, new Type(P, List.of(), new BuiltinTypeSpec(P, "void"), List.of()), "main", P,
						List.of()),
				new BlockStmt(P, List.of(s)));
	}

	/** Prints {@code s} as the only statement of a function body, then parses it back and returns the statement. */
	private static Stmt roundTrip(Stmt s) {
		FunctionDefinition again = (FunctionDefinition) Parser.parse(GlslPrinter.print(functionOf(s))).decls()
				.getFirst();
		return again.body().statements().getFirst();
	}

	/** The outer if must keep its else after printing: the else must not have moved onto an inner if. */
	private static void assertElseStaysOnOuter(Stmt rebuilt) {
		IfStmt outer = assertInstanceOf(IfStmt.class, roundTrip(rebuilt));
		assertNotNull(outer.elseStmt(), "the else moved to an inner if");
		assertEquals("x", ((Ident) ((ExprStmt) outer.elseStmt()).expr()).name());
	}

	@Test
	void ifElseChainInThenBranch() {
		// if (c) if (c) x; else if (c) x; else x;  -- the then-branch's else ends in an open if, so the last else
		// would bind to it when printed without braces.
		Stmt then = ifElse(x(), ifOnly(x()));
		assertElseStaysOnOuter(ifElse(then, x()));
	}

	@Test
	void whileBodyEndingInOpenIf() {
		// if (c) while (c) if (c) x; else x;
		assertElseStaysOnOuter(ifElse(whileOf(ifOnly(x())), x()));
	}

	@Test
	void forBodyEndingInOpenIf() {
		// if (c) for (;;) if (c) x; else x;
		assertElseStaysOnOuter(ifElse(forOf(ifOnly(x())), x()));
	}

	@Test
	void loopsNestedInsideAChainEndingInOpenIf() {
		// if (c) while (c) for (;;) if (c) x; else if (c) x; else x;  -- open if reached through loops and a chain
		Stmt inner = ifElse(ifOnly(x()), ifOnly(x()));
		assertElseStaysOnOuter(ifElse(whileOf(forOf(inner)), x()));
	}

	@Test
	void thenBranchThatEndsInAClosedStatementIsNotBraced() {
		// if (c) while (c) x; else x;  needs no braces: nothing can capture the else.
		Stmt rebuilt = ifElse(whileOf(x()), x());
		assertTrue(GlslPrinter.print(functionOf(rebuilt)).contains("if (c) while (c) x; else x;"));
		assertElseStaysOnOuter(rebuilt);
	}
}
