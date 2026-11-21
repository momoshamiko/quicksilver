// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.expr;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Name resolution and type checking: the documented conversions and the errors that name text and column. */
class ExprCompilerTest {
	private final TestScope scope = TestScope.standard();

	private ExprError fails(String text) {
		return assertThrows(ExprError.class, () -> ExprCompiler.compile(text, scope));
	}

	@Test
	void piIsAFloatConstant() {
		assertEquals(ExprType.FLOAT, ExprCompiler.compile("pi", scope).type());
	}

	@Test
	void intToFloatIsTheOnlyImplicitScalarConversion() {
		assertEquals(ExprType.INT, ExprCompiler.compile("biome", scope).type());
		assertEquals(ExprType.INT, ExprCompiler.compile("biome + heldItemId", scope).type());
		assertEquals(ExprType.FLOAT, ExprCompiler.compile("biome + 0.5", scope).type());
		assertEquals(ExprType.FLOAT, ExprCompiler.compile("sunAngle + 1", scope).type());
	}

	@Test
	void declaredTypeConvertsIntToFloatAndRefusesFloatToInt() {
		// uniform.float.<name> = <int expr>: the int converts to float.
		CompiledExpr toFloat = ExprCompiler.compile("3", scope, ExprType.FLOAT);
		assertEquals(ExprType.FLOAT, toFloat.type());
		assertEquals(3f, toFloat.evalF(new ExprEval(scope.frame, 1)), 1e-6f);
		// uniform.int.<name> = <float expr>: refused, as Iris refuses it (uniform.int.x=7 / 2 reads 0 on Iris 1.11.7,
		// measured with the mcms-expr pack, E7): the uniform is dropped at load and reads zero.
		ExprError toInt = fails2("7 / 2", ExprType.INT);
		assertTrue(toInt.getMessage().contains("expected INT"));
		assertEquals(3, ExprCompiler.compile("7 % 4", scope, ExprType.INT).evalI(new ExprEval(scope.frame, 1)));
		ExprError vec = fails2("vec2(1.0, 2.0)", ExprType.VEC3);
		assertTrue(vec.getMessage().contains("expected VEC3"));
		ExprError bool = fails2("true", ExprType.FLOAT);
		assertTrue(bool.getMessage().contains("expected FLOAT"));
	}

	private ExprError fails2(String text, ExprType expected) {
		return assertThrows(ExprError.class, () -> ExprCompiler.compile(text, scope, expected));
	}

	@Test
	void unknownNameNamesTheExpressionAndColumn() {
		ExprError e = fails("1 + fooQ");
		assertEquals(5, e.column());
		assertTrue(e.getMessage().contains("unknown name 'fooQ'"));
		assertTrue(e.getMessage().contains("1 + fooQ"));
	}

	@Test
	void unknownFunctionNamesTheCall() {
		ExprError e = fails("frobnicate(1)");
		assertTrue(e.getMessage().contains("unknown function 'frobnicate'"));
	}

	@Test
	void booleanOperatorsNeedBooleans() {
		assertTrue(fails("1 && true").getMessage().contains("bool"));
		assertTrue(fails("!sunAngle").getMessage().contains("'!'"));
		assertTrue(fails("is_sprinting + true").getMessage().contains("numbers or vectors"));
	}

	@Test
	void comparisonsNeedTwoNumbersOrTwoBools() {
		assertTrue(fails("true == 1").getMessage().contains("two numbers or two bools"));
		assertTrue(fails("sunAngle < is_sprinting").getMessage().contains("two numbers"));
		assertEquals(ExprType.BOOL, ExprCompiler.compile("true != false", scope).type());
	}

	@Test
	void vectorSizesMustMatch() {
		ExprError e = fails("vec2(1.0, 2.0) + vec3(1.0, 2.0, 3.0)");
		assertTrue(e.getMessage().contains("sizes must match"));
		assertTrue(fails("vec2(true, 1.0)").getMessage().contains("expected a number"));
		assertTrue(fails("vec3(1.0, 2.0)").getMessage().contains("vec3 takes 3"));
	}

	@Test
	void elementAccessAppliesToNamesOnly() {
		// Iris: "intermediate values cannot" be accessed with an element selector.
		ExprError e = fails("min(vec2(0.0, 1.0), vec2(1.0, 0.0)).x");
		assertTrue(e.getMessage().contains("only allowed on a uniform or variable name"));
	}

	@Test
	void vectorElementSelectorsAndTheirRanges() {
		assertEquals(ExprType.FLOAT, ExprCompiler.compile("cameraPosition.y", scope).type());
		ExprError e = fails("cameraPosition.x.y");
		assertTrue(e.getMessage().contains("exactly one element"));
		ExprError unknown = fails("cameraPosition.xz");
		assertTrue(unknown.getMessage().contains("xz"));
		ExprError range = fails("eyeBrightnessSmooth.z");
		assertTrue(range.getMessage().contains("out-of-range"));
		// Iris: ".xyzw, .rgba, stpq, and .0123".
		assertEquals(ExprType.FLOAT, ExprCompiler.compile("skyColor.g", scope).type());
		assertEquals(ExprType.FLOAT, ExprCompiler.compile("skyColor.t", scope).type());
		assertEquals(ExprType.FLOAT, ExprCompiler.compile("skyColor.2", scope).type());
		ExprError scalar = fails("sunAngle.x");
		assertTrue(scalar.getMessage().contains("no elements"));
	}

	@Test
	void matrixElementAccessNeedsRowAndColumn() {
		assertEquals(ExprType.FLOAT, ExprCompiler.compile("gbufferModelView.0.1", scope).type());
		assertEquals(ExprType.FLOAT, ExprCompiler.compile("normalMatrix.2.2", scope).type());
		ExprError bare = fails("gbufferModelView");
		assertTrue(bare.getMessage().contains("element access"));
		ExprError one = fails("gbufferModelView.0");
		assertTrue(one.getMessage().contains(".<column>.<row>"));
		ExprError out = fails("gbufferModelView.0.4");
		assertTrue(out.getMessage().contains("no element"));
	}

	@Test
	void ifAndIfbShape() {
		assertEquals(ExprType.FLOAT, ExprCompiler.compile("if(sunAngle < 0.5, 1.0, 2.0)", scope).type());
		assertEquals(ExprType.INT, ExprCompiler.compile("if(true, 1, 0)", scope).type());
		assertEquals(ExprType.VEC3, ExprCompiler.compile("if(true, vec3(1, 2, 3), vec3(4, 5, 6))", scope).type());
		assertEquals(ExprType.BOOL, ExprCompiler.compile("ifb(is_sprinting, true, false)", scope).type());
		assertTrue(fails("if(true, 1)").getMessage().contains("odd number"));
		assertTrue(fails("ifb(true, 1, 0)").getMessage().contains("ifb selects bool"));
		assertTrue(fails("if(true, true, false)").getMessage().contains("ifb"));
		assertTrue(fails("if(true, 1.0, vec2(1.0, 2.0))").getMessage().contains("one type"));
	}

	@Test
	void smoothShapesFromTheDocumentation() {
		// OptiFine examples: smooth(1, if(...), 5), smooth(2, ..., 0, 10); Iris: smooth(value, 2, 0.25).
		assertEquals(ExprType.FLOAT, ExprCompiler.compile("smooth(1, sunAngle, 5)", scope).type());
		assertEquals(ExprType.FLOAT, ExprCompiler.compile("smooth(2, sunAngle, 0, 10)", scope).type());
		assertEquals(ExprType.FLOAT, ExprCompiler.compile("smooth(sunAngle)", scope).type());
		assertEquals(ExprType.FLOAT, ExprCompiler.compile("smooth(sunAngle, 2, 0.25)", scope).type());
		assertEquals(ExprType.VEC2, ExprCompiler.compile("smooth(eyeBrightnessSmooth, 1, 2)", scope).type());
		assertTrue(fails("smooth(1, is_sprinting)").getMessage().contains("number or a vector"));
		assertTrue(fails("smooth(1, sunAngle, 1, 2, 3)").getMessage().contains("fadeDown"));
	}

	@Test
	void arityErrorsNameTheFunction() {
		assertTrue(fails("min(1)").getMessage().contains("min"));
		assertTrue(fails("clamp(1, 2)").getMessage().contains("clamp"));
		assertTrue(fails("sin(1, 2)").getMessage().contains("sin"));
		assertTrue(fails("pow(1)").getMessage().contains("pow"));
		assertTrue(fails("in(biome)").getMessage().contains("in"));
		assertTrue(fails("print(1, 2)").getMessage().contains("print"));
		assertTrue(fails("print(biome, 2, sunAngle)").getMessage().contains("integer literals"));
	}
}
