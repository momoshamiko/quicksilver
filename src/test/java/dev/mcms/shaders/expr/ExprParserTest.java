// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.expr;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Grammar and precedence of the expression language (OptiFine doc shaders.properties, "Operators"). */
class ExprParserTest {
	private Expr.Binary binary(Expr e) {
		return assertInstanceOf(Expr.Binary.class, e);
	}

	private Expr.Unary unary(Expr e) {
		return assertInstanceOf(Expr.Unary.class, e);
	}

	private Expr.Name name(Expr e) {
		return assertInstanceOf(Expr.Name.class, e);
	}

	@Test
	void multiplicationBindsTighterThanAddition() {
		Expr.Binary e = binary(ExprParser.parse("1 + 2 * 3"));
		assertEquals("+", e.op());
		assertEquals(1.0, ((Expr.Num) e.left()).value());
		Expr.Binary r = binary(e.right());
		assertEquals("*", r.op());
		assertEquals(2.0, ((Expr.Num) r.left()).value());
		assertEquals(3.0, ((Expr.Num) r.right()).value());
	}

	@Test
	void parenthesesOverridePrecedence() {
		Expr.Binary e = binary(ExprParser.parse("(1 + 2) * 3"));
		assertEquals("*", e.op());
		Expr.Binary l = binary(e.left());
		assertEquals("+", l.op());
	}

	@Test
	void sameLevelOperatorsAssociateLeft() {
		Expr.Binary e = binary(ExprParser.parse("10 - 4 - 3"));
		assertEquals("-", e.op());
		assertEquals("-", binary(e.left()).op());
		Expr.Binary d = binary(ExprParser.parse("100 / 10 / 5"));
		assertEquals("/", d.op());
		assertEquals("/", binary(d.left()).op());
		Expr.Binary m = binary(ExprParser.parse("2 * 3 % 4"));
		assertEquals("%", m.op());
		assertEquals("*", binary(m.left()).op());
	}

	@Test
	void comparisonBindsTighterThanEquality() {
		Expr.Binary e = binary(ExprParser.parse("a < b == c"));
		assertEquals("==", e.op());
		assertEquals("<", binary(e.left()).op());
	}

	@Test
	void andBindsTighterThanOr() {
		Expr.Binary e = binary(ExprParser.parse("a || b && c"));
		assertEquals("||", e.op());
		assertEquals("a", name(e.left()).name());
		assertEquals("&&", binary(e.right()).op());
	}

	@Test
	void unaryBindsTighterThanMultiplication() {
		Expr.Binary e = binary(ExprParser.parse("-a * !b"));
		assertEquals("*", e.op());
		assertEquals("-", unary(e.left()).op());
		assertEquals("!", unary(e.right()).op());
		Expr.Unary u = unary(ExprParser.parse("!!a"));
		assertEquals("!", u.op());
		assertEquals("!", unary(u.operand()).op());
		Expr.Unary neg = unary(ExprParser.parse("-3"));
		assertEquals("-", neg.op());
		assertTrue(((Expr.Num) neg.operand()).integral());
	}

	@Test
	void numberLiterals() {
		assertTrue(((Expr.Num) ExprParser.parse("2")).integral());
		assertEquals(2.0, ((Expr.Num) ExprParser.parse("2")).value());
		assertEquals(2.5, ((Expr.Num) ExprParser.parse("2.5")).value());
		assertEquals(0.5, ((Expr.Num) ExprParser.parse(".5")).value());
		assertEquals(2.0, ((Expr.Num) ExprParser.parse("2.")).value());
		assertEquals(1000.0, ((Expr.Num) ExprParser.parse("1e3")).value());
		assertEquals(0.25, ((Expr.Num) ExprParser.parse("2.5e-1")).value());
		assertEquals(false, ((Expr.Num) ExprParser.parse("1e3")).integral());
	}

	@Test
	void booleansAndNames() {
		assertEquals(true, ((Expr.Bool) ExprParser.parse("true")).value());
		assertEquals(false, ((Expr.Bool) ExprParser.parse("false")).value());
		assertEquals("frameTimeCounter", name(ExprParser.parse("frameTimeCounter")).name());
	}

	@Test
	void syntaxErrorsNameTextAndColumn() {
		ExprError e = assertThrows(ExprError.class, () -> ExprParser.parse("1 +"));
		assertEquals(4, e.column());
		assertTrue(e.getMessage().contains("1 +"));
		assertEquals(1, assertThrows(ExprError.class, () -> ExprParser.parse("")).column());
		ExprError bad = assertThrows(ExprError.class, () -> ExprParser.parse("(1 + 2"));
		assertEquals(7, bad.column());
		assertThrows(ExprError.class, () -> ExprParser.parse("min(1 2)"));
		assertThrows(ExprError.class, () -> ExprParser.parse("1 $ 2"));
		ExprError lone = assertThrows(ExprError.class, () -> ExprParser.parse("a = 5"));
		assertTrue(lone.getMessage().contains("'='"));
		assertThrows(ExprError.class, () -> ExprParser.parse("cameraPosition."));
		assertThrows(ExprError.class, () -> ExprParser.parse("1.5e"));
	}

	@Test
	void callsWithAndWithoutArguments() {
		Expr.Call c = assertInstanceOf(Expr.Call.class, ExprParser.parse("min(1, 2, 3)"));
		assertEquals("min", c.function());
		assertEquals(3, c.args().size());
		Expr.Call r = assertInstanceOf(Expr.Call.class, ExprParser.parse("random()"));
		assertEquals(0, r.args().size());
	}

	@Test
	void binaryPositionIsItsFirstToken() {
		// Expr.java documents every node's position as its first token, so a binary node sits on its left operand.
		assertEquals(0, ExprParser.parse("a || b && c").pos());
		Expr.Binary nested = binary(ExprParser.parse("1 + 2 * 3"));
		assertEquals(0, nested.pos());
		assertEquals(4, binary(nested.right()).pos());
		Expr.Binary spaced = binary(ExprParser.parse("  sunAngle  +  1.0"));
		assertEquals(2, spaced.pos());
	}

}
