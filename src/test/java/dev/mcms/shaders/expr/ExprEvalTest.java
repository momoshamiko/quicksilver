// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.expr;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Evaluation semantics: every operator and function against the documented behavior. */
class ExprEvalTest {
	private final TestScope scope = TestScope.standard();
	private final ExprEval eval = new ExprEval(scope.frame, 8);

	// Slots of the standard scope, for the tests that change a frame value.
	private static final int SUN = 1;
	private static final int TEMPERATURE = 2;
	private static final int BIOME = 4;

	@BeforeEach
	void fillFrame() {
		scope.frame.setFloat(0, 12.5f);            // frameTimeCounter
		scope.frame.setFloat(SUN, 0.25f);          // sunAngle
		scope.frame.setFloat(TEMPERATURE, 0.7f);   // temperature
		scope.frame.setFloat(3, 0.4f);             // rainfall
		scope.frame.setInt(BIOME, 45);             // biome = BIOME_FOREST
		scope.frame.setInt(5, 276);                // heldItemId
		scope.frame.setInt(6, 6000);               // worldTime
		scope.frame.setBool(7, true);              // is_sprinting
		scope.frame.setBool(8, false);             // is_hurt
		scope.frame.setVec3(9, 10f, 20f, 30f);     // cameraPosition
		scope.frame.setVec3(10, 0.1f, 0.2f, 0.3f); // skyColor
		scope.frame.setVec2(11, 120f, 80f);        // eyeBrightnessSmooth
		float[] mv = new float[16];
		mv[1] = 0.5f;                              // gbufferModelView[0][1]
		scope.frame.setMat4(12, mv);
		float[] nm = new float[9];
		nm[8] = 0.75f;                             // normalMatrix[2][2]
		scope.frame.setMat3(13, nm);
		eval.deltaTime = 0f;
		eval.timeSeconds = 0f;
		eval.print = null;
	}

	private float f(String text) {
		// uniform.float.<name>: the declared type is FLOAT; int expressions convert (the documented rule).
		return ExprCompiler.compile(text, scope, ExprType.FLOAT).evalF(eval);
	}

	private int i(String text) {
		return ExprCompiler.compile(text, scope).evalI(eval);
	}

	private boolean b(String text) {
		return ExprCompiler.compile(text, scope).evalB(eval);
	}

	private float[] v(String text) {
		float[] out = new float[4];
		ExprCompiler.compile(text, scope).evalV(eval, out, 0);
		return out;
	}

	// ------------------------------------------------------------------ operators and precedence

	@Test
	void arithmeticPrecedence() {
		assertEquals(7f, f("1 + 2 * 3"), 1e-6f);
		assertEquals(9f, f("(1 + 2) * 3"), 1e-6f);
		assertEquals(3f, f("10 - 4 - 3"), 1e-6f);
		assertEquals(2f, f("100 / 10 / 5"), 1e-6f);
		assertEquals(0.5f, f("1.0 / 2.0"), 1e-6f);
	}

	@Test
	void integerArithmeticStaysExact() {
		// "/" on two ints is float division: OptiFine's doc types every constant and parameter as a floating point
		// number, so a pack writing 7 / 2 means 3.5. "%" keeps exact int remainder; fmod is the floor modulus.
		assertEquals(ExprType.FLOAT, ExprCompiler.compile("7 / 2", scope).type());
		assertEquals(3.5f, f("7 / 2"), 1e-6f);
		assertEquals(2.5f, f("5 / 2.0"), 1e-6f);
		assertEquals(1, i("7 % 3"));
		// % is the remainder (sign follows the dividend); fmod is the floor modulus (see fmodMatchesFloorMod).
		assertEquals(-1, i("-7 % 3"));
		assertEquals(2, i("fmod(-7, 3)"));
		assertEquals(1.5f, f("7.5 % 2"), 1e-6f);
		assertEquals(-0.5f, f("-0.5 % 2"), 1e-6f);
		// Large ids compare exactly, not through float.
		scope.frame.setInt(5, 1500000);
		assertTrue(b("heldItemId == 1500000"));
		scope.frame.setInt(5, 276);
	}

	@Test
	void divisionByZeroNeverThrows() {
		// Int and float division follow IEEE rules (infinity, no exception); remainder-style operations give 0.
		assertEquals(Float.POSITIVE_INFINITY, f("1 / 0"));
		assertEquals(Float.POSITIVE_INFINITY, f("1.0 / 0.0"));
		assertEquals(0, i("1 % 0"));
		assertEquals(0, i("fmod(7, 0)"));
		assertEquals(0f, f("fmod(1.0, 0.0)"), 1e-6f);
	}

	@Test
	void realFunctionsReturnFloatForIntArguments() {
		// OptiFine's doc: constants are "floating point number" and the parameters are float, so the real-valued
		// functions return float even for int arguments instead of truncating the result to int.
		assertEquals(ExprType.FLOAT, ExprCompiler.compile("sin(1)", scope).type());
		assertEquals((float) Math.sin(1), f("sin(1)"), 1e-6f);
		assertEquals((float) Math.cos(1), f("cos(1)"), 1e-6f);
		assertEquals((float) Math.sqrt(2), f("sqrt(2)"), 1e-6f);
		assertEquals(8f, f("pow(2, 3)"), 1e-6f);
		assertEquals(3f, f("log(2, 8)"), 1e-6f);
		assertEquals(0f, f("frac(7)"), 1e-6f);
		assertEquals((float) Math.toRadians(90), f("torad(90)"), 1e-6f);
		// The exact-for-integers functions keep the int type.
		assertEquals(ExprType.INT, ExprCompiler.compile("abs(-2)", scope).type());
		assertEquals(ExprType.INT, ExprCompiler.compile("min(1, 2)", scope).type());
		assertEquals(ExprType.INT, ExprCompiler.compile("fmod(7, 2)", scope).type());
	}

	@Test
	void intLiteralsBeyondTheIntRangeAreFloats() {
		// Measured in the corpus: packs write very large integer literals as constants; they become floats.
		CompiledExpr big = ExprCompiler.compile("1000000000000000000", scope);
		assertEquals(ExprType.FLOAT, big.type());
		assertEquals(1e18f, big.evalF(eval), 1e9f);
		assertEquals(ExprType.INT, ExprCompiler.compile("2147483647", scope).type());
	}

	@Test
	void comparisons() {
		assertTrue(b("3 < 4"));
		assertFalse(b("4 < 3"));
		assertTrue(b("3 <= 3"));
		assertTrue(b("5 >= 5"));
		assertTrue(b("4 > 3"));
		assertFalse(b("3 > 4"));
		assertTrue(b("2 == 2.0")); // int converts to float for a mixed comparison
		assertTrue(b("2 != 3"));
		assertTrue(b("true == true"));
		assertTrue(b("true != false"));
		assertFalse(b("sunAngle == 0.5"));
	}

	@Test
	void booleanOperatorsAndUnaryMinusAndNot() {
		assertFalse(b("true && false"));
		assertTrue(b("false || true"));
		assertTrue(b("is_sprinting && !is_hurt"));
		assertFalse(b("!is_sprinting"));
		assertEquals(-2, i("-2"));
		assertEquals(2, i("- -2"));
		assertEquals(-0.5f, f("-0.5"), 1e-6f);
		assertEquals(1, i("-biome + 46"));
	}

	@Test
	void programEnabledSwitchOverBooleanOptions() {
		// OptiFine doc: "program.<program>.enabled=<expression>", for example "(BLOOM || SSAO) && !GODRAYS".
		TestScope s = new TestScope();
		s.constantBool("BLOOM", true);
		s.constantBool("SSAO", false);
		s.constantBool("GODRAYS", false);
		ExprEval e = new ExprEval(s.frame, 1);
		assertTrue(ExprCompiler.compile("(BLOOM || SSAO) && !GODRAYS", s).evalB(e));
		assertTrue(ExprCompiler.compile("BLOOM && !SSAO", s).evalB(e));
		TestScope rays = new TestScope();
		rays.constantBool("BLOOM", true);
		rays.constantBool("SSAO", false);
		rays.constantBool("GODRAYS", true);
		assertFalse(ExprCompiler.compile("(BLOOM || SSAO) && !GODRAYS", rays).evalB(new ExprEval(rays.frame, 1)));
		assertFalse(ExprCompiler.compile("SSAO && BLOOM", rays).evalB(new ExprEval(rays.frame, 1)));
	}

	// ------------------------------------------------------------------ scalar functions

	@Test
	void trigonometryAndAngleConversion() {
		assertEquals(0f, f("sin(0.0)"), 1e-6f);
		assertEquals(1f, f("cos(0.0)"), 1e-6f);
		assertEquals(1f, f("sin(pi / 2)"), 1e-6f);
		assertEquals(1f, f("tan(pi / 4)"), 1e-5f);
		assertEquals(Math.PI / 2, f("asin(1.0)"), 1e-6f);
		assertEquals(0f, f("acos(1.0)"), 1e-6f);
		assertEquals(Math.PI / 4, f("atan(1.0)"), 1e-6f);
		assertEquals(Math.PI / 4, f("atan2(1.0, 1.0)"), 1e-6f);
		assertEquals(Math.PI / 4, f("atan(1.0, 1.0)"), 1e-6f); // Iris alias
		assertEquals(Math.PI, f("torad(180.0)"), 1e-6f);
		assertEquals(Math.PI, f("radians(180.0)"), 1e-6f);
		assertEquals(180f, f("todeg(pi)"), 1e-4f);
		assertEquals(180f, f("degrees(pi)"), 1e-4f);
	}

	@Test
	void minMaxClampVariadicAndTyped() {
		assertEquals(1f, f("min(3.0, 1.0, 2.0)"), 1e-6f);
		assertEquals(4.5f, f("max(3.0, 1.0, 4.5)"), 1e-6f);
		assertEquals(1, i("min(3, 1, 2)"));
		assertEquals(5, i("max(3, 1, 5)"));
		assertEquals(ExprType.INT, ExprCompiler.compile("min(1, 2)", scope).type());
		assertEquals(0.5f, f("clamp(0.5, 0.0, 1.0)"), 1e-6f);
		assertEquals(1f, f("clamp(5.0, 0.0, 1.0)"), 1e-6f);
		assertEquals(0f, f("clamp(-0.5, 0.0, 1.0)"), 1e-6f);
		assertEquals(2, i("clamp(9, 1, 2)"));
	}

	@Test
	void scalarMathFunctions() {
		assertEquals(2.5f, f("abs(-2.5)"), 1e-6f);
		assertEquals(2, i("abs(-2)"));
		assertEquals(2f, f("floor(2.7)"), 1e-6f);
		assertEquals(-3f, f("floor(-2.3)"), 1e-6f);
		assertEquals(3f, f("ceil(2.1)"), 1e-6f);
		assertEquals(-2f, f("ceil(-2.5)"), 1e-6f);
		assertEquals(1f, f("exp(0.0)"), 1e-6f);
		assertEquals(8f, f("exp2(3.0)"), 1e-5f);
		assertEquals(100f, f("exp10(2.0)"), 1e-4f);
		assertEquals(0.75f, f("frac(2.75)"), 1e-6f);
		assertEquals(0.75f, f("frac(-0.25)"), 1e-6f); // x - floor(x)
		assertEquals(0f, f("log(1.0)"), 1e-6f);
		assertEquals(3f, f("log2(8.0)"), 1e-6f);
		assertEquals(3f, f("log10(1000.0)"), 1e-5f);
		assertEquals(3f, f("log(2.0, 8.0)"), 1e-6f);
		assertEquals(3f, f("log(2, 8)"), 1e-6f); // int arguments, float result
		assertEquals(1024f, f("pow(2.0, 10.0)"), 1e-4f);
		assertEquals(1024f, f("pow(2, 10)"), 1e-4f);
		assertEquals(3f, f("sqrt(9.0)"), 1e-6f);
		assertEquals(3f, f("round(2.5)"), 1e-6f);
		assertEquals(2f, f("round(2.4)"), 1e-6f);
		assertEquals(-1f, f("signum(-2.5)"), 1e-6f);
		assertEquals(0f, f("signum(0.0)"), 1e-6f);
		assertEquals(1, i("signum(5)"));
		assertEquals(-1f, f("sign(-1.0)"), 1e-6f);
	}

	@Test
	void fmodMatchesFloorMod() {
		// OptiFine doc: fmod(x, y) — "Similar to Math.floorMod()".
		assertEquals(2f, f("fmod(-7.0, 3.0)"), 1e-6f);
		assertEquals(2, i("fmod(-7, 3)"));
		assertEquals(1.5f, f("fmod(7.5, 2.0)"), 1e-6f);
	}

	@Test
	void lerpMixAndEdge() {
		assertEquals(12.5f, f("lerp(0.25, 10.0, 20.0)"), 1e-6f); // k first (OptiFine doc)
		assertEquals(12.5f, f("mix(10.0, 20.0, 0.25)"), 1e-6f);  // a last (Iris)
		assertEquals(0f, f("edge(0.5, 0.25)"), 1e-6f);
		assertEquals(1f, f("edge(0.5, 0.75)"), 1e-6f);
		assertEquals(1, i("edge(2, 5)"));
	}

	@Test
	void ifSelectsTheFirstMatchingBranch() {
		assertEquals(1f, f("if(sunAngle < 0.5, 1.0, 2.0)"), 1e-6f);
		assertEquals(2f, f("if(sunAngle > 0.5, 1.0, 2.0)"), 1e-6f);
		assertEquals(2f, f("if(false, 1.0, true, 2.0, 3.0)"), 1e-6f);
		assertEquals(3f, f("if(false, 1.0, false, 2.0, 3.0)"), 1e-6f);
		assertEquals(1, i("if(is_sprinting, 1, 0)"));
		assertFalse(b("ifb(is_sprinting, false, true)")); // the first value is selected: false
	}

	@Test
	void ifSkipsTheBranchesItDoesNotTake() {
		// The skipped print never runs: observable through the sink.
		List<String> logged = new ArrayList<>();
		eval.print = (id, n, value) -> logged.add(value);
		ExprCompiler.compile("ifb(false, print(1, 1, true), false)", scope).evalB(eval);
		assertEquals(0, logged.size());
		ExprCompiler.compile("ifb(true, print(1, 1, true), false)", scope).evalB(eval);
		assertEquals(1, logged.size());
	}

	@Test
	void booleanFunctions() {
		// Iris: between is inclusive.
		assertTrue(b("between(1.0, 0.0, 1.0)"));
		assertFalse(b("between(1.5, 0.0, 1.0)"));
		assertTrue(b("between(biome, 40, 50)"));
		assertTrue(b("equals(0.1 + 0.2, 0.3, 0.0001)"));
		assertFalse(b("equals(0.1, 0.2, 0.0001)"));
		assertTrue(b("in(biome, BIOME_RIVER, BIOME_FOREST)"));
		assertFalse(b("in(biome, BIOME_RIVER)"));
		assertTrue(b("in(2, 1, 2.0, 3)"));
	}

	@Test
	void rainSnowRuleFromTheDocumentation() {
		// OptiFine doc: rain/snow is rendered when biome_precipitation != PPT_NONE; rain if temperature >= 0.15.
		TestScope s = new TestScope();
		s.bind("biome_precipitation", ExprType.INT); // slot 0
		s.bind("temperature", ExprType.FLOAT);       // slot 1
		s.bind("precipIsOn", ExprType.BOOL);         // slot 2: an earlier variable of the pack
		s.constantInt("PPT_NONE", 0);
		s.done();
		ExprEval e = new ExprEval(s.frame, 1);
		CompiledExpr precip = ExprCompiler.compile("biome_precipitation != PPT_NONE", s);
		CompiledExpr rain = ExprCompiler.compile("precipIsOn && temperature >= 0.15", s);
		s.frame.setInt(0, 0);
		assertFalse(precip.evalB(e));
		s.frame.setInt(0, 1);
		assertTrue(precip.evalB(e));
		s.frame.setBool(2, true);
		s.frame.setFloat(1, 0.1f);
		assertFalse(rain.evalB(e)); // cold: snow
		s.frame.setFloat(1, 0.5f);
		assertTrue(rain.evalB(e)); // warm: rain
	}

	// ------------------------------------------------------------------ vectors

	@Test
	void vectorConstructorsConvertIntParts() {
		assertEquals(2, ExprCompiler.compile("vec2(1, 2)", scope).type().components());
		org.junit.jupiter.api.Assertions.assertArrayEquals(new float[] {1f, 2f, 0f, 0f}, v("vec2(1, 2)"), 1e-6f);
		org.junit.jupiter.api.Assertions.assertArrayEquals(new float[] {1f, 2f, 3.5f, 0f}, v("vec3(1, 2, 3.5)"), 1e-6f);
		org.junit.jupiter.api.Assertions.assertArrayEquals(new float[] {1f, 2f, 3f, 4f}, v("vec4(1, 2, 3, 4)"), 1e-6f);
	}

	@Test
	void vectorArithmeticWithScalarBroadcast() {
		org.junit.jupiter.api.Assertions.assertArrayEquals(new float[] {5f, 7f, 9f, 0f}, v("vec3(1, 2, 3) + vec3(4, 5, 6)"), 1e-6f);
		org.junit.jupiter.api.Assertions.assertArrayEquals(new float[] {-3f, -3f, -3f, 0f}, v("vec3(1, 2, 3) - vec3(4, 5, 6)"), 1e-6f);
		org.junit.jupiter.api.Assertions.assertArrayEquals(new float[] {2f, 4f, 0f, 0f}, v("vec2(1, 2) * 2.0"), 1e-6f);
		org.junit.jupiter.api.Assertions.assertArrayEquals(new float[] {2f, 4f, 0f, 0f}, v("2.0 * vec2(1, 2)"), 1e-6f);
		org.junit.jupiter.api.Assertions.assertArrayEquals(new float[] {2f, 2f, 0f, 0f}, v("vec2(4, 6) / vec2(2, 3)"), 1e-6f);
		org.junit.jupiter.api.Assertions.assertArrayEquals(new float[] {0f, 1f, 0f, 0f}, v("vec2(1, 2) - 1"), 1e-6f);
		org.junit.jupiter.api.Assertions.assertArrayEquals(new float[] {1f, 1f, 0f, 0f}, v("vec2(5, 7) % 2"), 1e-6f);
		org.junit.jupiter.api.Assertions.assertArrayEquals(new float[] {-1f, 2f, 0f, 0f}, v("-vec2(1, -2)"), 1e-6f);
	}

	@Test
	void vectorFunctions() {
		org.junit.jupiter.api.Assertions.assertArrayEquals(new float[] {1f, 2f, 0f, 0f}, v("abs(vec2(-1, 2))"), 1e-6f);
		org.junit.jupiter.api.Assertions.assertArrayEquals(new float[] {1f, -2f, 0f, 0f}, v("floor(vec2(1.5, -1.5))"), 1e-6f);
		org.junit.jupiter.api.Assertions.assertArrayEquals(new float[] {2f, -1f, 0f, 0f}, v("ceil(vec2(1.5, -1.5))"), 1e-6f);
		org.junit.jupiter.api.Assertions.assertArrayEquals(new float[] {1f, 2f, 0f, 0f}, v("min(vec2(1, 5), vec2(3, 2))"), 1e-6f);
		org.junit.jupiter.api.Assertions.assertArrayEquals(new float[] {3f, 5f, 0f, 0f}, v("max(vec2(1, 5), 3.0)"), 1e-6f);
		org.junit.jupiter.api.Assertions.assertArrayEquals(new float[] {0.5f, 0.5f, 0f, 0f}, v("min(0.5, vec2(1, 5))"), 1e-6f);
		org.junit.jupiter.api.Assertions.assertArrayEquals(new float[] {0f, 1f, 0f, 0f}, v("clamp(vec2(-1.0, 2.0), 0.0, 1.0)"), 1e-6f);
		org.junit.jupiter.api.Assertions.assertArrayEquals(new float[] {0.6f, 0.7f, 0f, 0f}, v("clamp(vec2(0.5, 0.7), vec2(0.6, 0.6), 1.0)"), 1e-6f);
		org.junit.jupiter.api.Assertions.assertArrayEquals(new float[] {10f, 20f, 30f, 0f}, v("cameraPosition"), 1e-6f);
		org.junit.jupiter.api.Assertions.assertArrayEquals(new float[] {10.25f, 20.25f, 30.25f, 0f}, v("cameraPosition + 0.25"), 1e-6f);
	}

	@Test
	void elementAccessOnFrameValues() {
		assertEquals(20f, f("cameraPosition.y"), 1e-6f);
		assertEquals(0.3f, f("skyColor.b"), 1e-6f);
		assertEquals(20f, f("cameraPosition.t"), 1e-6f); // stpq spelling (Iris)
		assertEquals(30f, f("cameraPosition.2"), 1e-6f); // numeric spelling (Iris)
		assertEquals(120f, f("eyeBrightnessSmooth.x"), 1e-6f);
	}

	@Test
	void matrixElementsAreColumnMajorLikeGlsl() {
		// The engine's reading: name.A.B is GLSL M[A][B], column A and row B. The docs word it "row and column index" (and
		// Iris calls .0.3 the 1st row 4th column); the corpus evidence for column first is in ExprFrame.setMat4.
		// The frame stores the matrix column-major, so .0.1 is the element at index 0*4+1.
		float[] m = new float[16];
		for (int k = 0; k < 16; k++) {
			m[k] = k * 0.1f; // all sixteen elements distinct: a non-symmetric matrix
		}
		scope.frame.setMat4(12, m);
		assertEquals(0.1f, f("gbufferModelView.0.1"), 1e-6f); // column 0, row 1
		assertEquals(0.4f, f("gbufferModelView.1.0"), 1e-6f); // column 1, row 0
		assertEquals(1.5f, f("gbufferModelView.3.3"), 1e-6f);
		float[] n = new float[9];
		for (int k = 0; k < 9; k++) {
			n[k] = k * 0.01f;
		}
		scope.frame.setMat3(13, n);
		assertEquals(0.02f, f("normalMatrix.0.2"), 1e-6f); // column 0, row 2
		assertEquals(0.08f, f("normalMatrix.2.2"), 1e-6f); // column 2, row 2
	}

	@Test
	void constantVectorsWriteOnlyTheirComponents() {
		float[] out = {9f, 9f, 9f, 9f};
		ExprCompiler.compile("vec3(1, 2, 3)", scope).evalV(eval, out, 0);
		org.junit.jupiter.api.Assertions.assertArrayEquals(new float[] {1f, 2f, 3f, 9f}, out, 1e-6f);
		ExprCompiler.compile("vec2(4, 5)", scope).evalV(eval, out, 0);
		// A vec2 writes x and y only: z keeps the 3 that the vec3 wrote before it.
		org.junit.jupiter.api.Assertions.assertArrayEquals(new float[] {4f, 5f, 3f, 9f}, out, 1e-6f);
	}

	@Test
	void nestedVectorExpressionsUseTheCompiledScratchSize() {
		// Scalar-left nesting is the case that needs one scratch vector per level for the right operand.
		CompiledExpr ex = ExprCompiler.compile("1.0 - (1.0 - (1.0 - vec3(0.1, 0.2, 0.3)))", scope);
		ExprEval sized = new ExprEval(scope.frame, ex.vectorScratch());
		float[] out = new float[4];
		ex.evalV(sized, out, 0);
		org.junit.jupiter.api.Assertions.assertArrayEquals(new float[] {0.9f, 0.8f, 0.7f, 0f}, out, 1e-6f);
		assertEquals(3, ex.vectorScratch());
		// An undersized evaluator fails loudly instead of allocating per frame.
		ExprEval undersized = new ExprEval(scope.frame, ex.vectorScratch() - 1);
		assertThrows(IllegalStateException.class, () -> ex.evalV(undersized, out, 0));
	}

	// ------------------------------------------------------------------ stateful functions

	@Test
	void smoothDecaysWithTheTimeConstantIrisShowsAtCappedFrameRates() {
		// Iris 1.11.7 measured at 60 and 120 frames per second (E7, mcms-expr): the remaining distance is multiplied by
		// exp(-6.67 dt / fade); both fades default to 1, so 0.15 s is about one time constant.
		CompiledExpr ex = ExprCompiler.compile("smooth(1, sunAngle, 1)", scope);
		eval.deltaTime = 0.15f;
		scope.frame.setFloat(SUN, 0f);
		assertEquals(0f, ex.evalF(eval), 1e-6f); // first frame initializes
		scope.frame.setFloat(SUN, 1f);
		assertEquals(1f - Math.exp(-6.67 * 0.15), ex.evalF(eval), 1e-4f);
		assertEquals(1f - Math.exp(-6.67 * 0.30), ex.evalF(eval), 1e-4f);
		assertEquals(1f - Math.exp(-6.67 * 0.45), ex.evalF(eval), 1e-4f);
	}

	@Test
	void smoothFadeUpAndFadeDownDiffer() {
		// fadeUp 1 (0.15 s), fadeDown 2000 (300 s): after one second of rising, exp(-6.67) of the distance remains; after
		// one second of falling, exp(-6.67 / 2000) of the value remains.
		CompiledExpr ex = ExprCompiler.compile("smooth(2, sunAngle, 1, 2000)", scope);
		eval.deltaTime = 1f;
		scope.frame.setFloat(SUN, 0f);
		ex.evalF(eval);
		scope.frame.setFloat(SUN, 1f);
		float risen = ex.evalF(eval);
		assertEquals(1f - Math.exp(-6.67), risen, 1e-5f);
		scope.frame.setFloat(SUN, 0f);
		assertEquals(risen * Math.exp(-6.67 / 2000), ex.evalF(eval), 1e-5f);
	}
	@Test
	void undersizedEvaluatorIsRejectedAtLoad() {
		CompiledExpr ex = ExprCompiler.compile("1.0 - (1.0 - (1.0 - vec3(0.1, 0.2, 0.3)))", scope);
		ExprEval small = new ExprEval(scope.frame, ex.vectorScratch() - 1);
		assertThrows(IllegalArgumentException.class, () -> ex.requireScratch(small));
		ex.requireScratch(new ExprEval(scope.frame, ex.vectorScratch()));
	}

	@Test
	void singleFadeLeavesTheOtherAtOne() {
		// smooth(v, 2000): fadeUp 2000, fadeDown defaults to 1, not to fadeUp.
		CompiledExpr ex = ExprCompiler.compile("smooth(sunAngle, 2000)", scope);
		eval.deltaTime = 1f;
		scope.frame.setFloat(SUN, 1f);
		ex.evalF(eval);
		scope.frame.setFloat(SUN, 0f);
		// Falling with fade 1 for 1 s: exp(-6.67) of the value remains.
		assertEquals(Math.exp(-6.67), ex.evalF(eval), 1e-6);
	}

	@Test
	void smoothZeroFadeJumpsInstantly() {
		// OptiFine doc example shape: smooth(2, if(is_hurt, 1.3, 0), 0, 10): fadeUp 0 (instant), fadeDown 10 (1.5 s).
		CompiledExpr ex = ExprCompiler.compile("smooth(2, if(is_hurt, 1.3, 0), 0, 10)", scope);
		eval.deltaTime = 1f;
		assertEquals(0f, ex.evalF(eval), 1e-6f);
		scope.frame.setBool(8, true);
		assertEquals(1.3f, ex.evalF(eval), 1e-4f); // fadeUp 0: instant
		scope.frame.setBool(8, false);
		assertEquals(1.3f * (float) Math.exp(-0.667), ex.evalF(eval), 1e-4f); // 1 s of a 1.5 s time constant
	}

	@Test
	void smoothVectorsTrackComponentsIndependently() {
		CompiledExpr ex = ExprCompiler.compile("smooth(3, vec2(sunAngle, temperature), 1, 2000)", scope);
		eval.deltaTime = 1f;
		float[] out = new float[4];
		ex.evalV(eval, out, 0); // initialize at 0.25, 0.7
		scope.frame.setFloat(SUN, 1f);
		scope.frame.setFloat(TEMPERATURE, 0f);
		ex.evalV(eval, out, 0);
		assertEquals(1f - 0.75 * Math.exp(-6.67), out[0], 1e-5f); // rising from 0.25 with fade 1 for a second
		assertEquals(0.7 * Math.exp(-6.67 / 2000), out[1], 1e-5f); // falling with fade 2000
	}

	@Test
	void smoothResetsWhenItsValueIsNotFinite() {
		CompiledExpr ex = ExprCompiler.compile("smooth(1, sunAngle, 100000)", scope);
		eval.deltaTime = 1f;
		scope.frame.setFloat(SUN, Float.NaN);
		assertTrue(Float.isNaN(ex.evalF(eval))); // the target itself was not finite
		scope.frame.setFloat(SUN, 1f);
		assertEquals(1f, ex.evalF(eval), 1e-6f); // a non-finite current value recovers to the target
		scope.frame.setFloat(SUN, Float.POSITIVE_INFINITY);
		ex.evalF(eval);
		scope.frame.setFloat(SUN, 0.5f);
		assertEquals(0.5f, ex.evalF(eval), 1e-6f);
	}

	@Test
	void randomRegeneratesEachFrameAndSitesDiffer() {
		CompiledExpr ex = ExprCompiler.compile("random()", scope);
		float v1 = ex.evalF(eval);
		float v2 = ex.evalF(eval);
		assertTrue(v1 >= 0f && v1 < 1f);
		assertTrue(v2 >= 0f && v2 < 1f);
		assertTrue(v1 != v2, "random() is generated each frame");
		// Identical texts must not share one sequence: the first values of ten fresh compiles spread across [0, 1).
		float lo = Float.MAX_VALUE;
		float hi = -Float.MAX_VALUE;
		for (int k = 0; k < 10; k++) {
			float first = ExprCompiler.compile("random()", scope).evalF(eval);
			lo = Math.min(lo, first);
			hi = Math.max(hi, first);
		}
		assertTrue(hi - lo > 0.1f, "identical texts produced clustered sequences: spread " + (hi - lo));
		CompiledExpr ranged = ExprCompiler.compile("random(2.0, 5.0)", scope);
		for (int k = 0; k < 100; k++) {
			float r = ranged.evalF(eval);
			assertTrue(r >= 2f && r < 5f);
		}
		CompiledExpr ints = ExprCompiler.compile("randomInt(1, 5)", scope);
		for (int k = 0; k < 100; k++) {
			int r = ints.evalI(eval);
			assertTrue(r >= 1 && r < 5);
		}
	}

	@Test
	void randomIntOverTheFullIntRangeDoesNotOverflow() {
		CompiledExpr ints = ExprCompiler.compile("randomInt(-2147483648, 2147483647)", scope);
		for (int k = 0; k < 100; k++) {
			int r = ints.evalI(eval);
			assertTrue(r >= Integer.MIN_VALUE && r < Integer.MAX_VALUE);
		}
	}


	@Test
	void printLogsAtMostOncePerSecondAndReturnsTheValue() {
		List<String> logged = new ArrayList<>();
		eval.print = (id, n, value) -> logged.add(id + "/" + n + "/" + value);
		CompiledExpr ex = ExprCompiler.compile("print(3, 60, sunAngle)", scope);
		assertEquals(0.25f, ex.evalF(eval), 1e-6f); // prints at t=0
		eval.timeSeconds = 0.5f;
		ex.evalF(eval);
		eval.timeSeconds = 0.9f;
		ex.evalF(eval);
		assertEquals(1, logged.size());
		eval.timeSeconds = 1.0f;
		ex.evalF(eval);
		assertEquals(2, logged.size());
		assertEquals("3/60/0.25", logged.get(0));
		CompiledExpr intPrint = ExprCompiler.compile("print(4, 60, biome)", scope);
		intPrint.evalI(eval);
		assertEquals("4/60/45", logged.get(2));
		CompiledExpr vecPrint = ExprCompiler.compile("print(5, 60, cameraPosition)", scope);
		vecPrint.evalV(eval, new float[4], 0);
		assertEquals("5/60/10.0 20.0 30.0", logged.get(3));
	}

	// ------------------------------------------------------------------ the documented example, file order

	@Test
	void optifineExampleUniformSetRunsInFileOrder() {
		// The "Example" block of the OptiFine doc's Custom uniforms section, with the runtime's role played by the test:
		// each earlier definition becomes a slot the later expressions read.
		scope.frame.ensureSlots(30);
		// ensureSlots reallocates the arrays: set the frame values this example reads again.
		scope.frame.setInt(BIOME, 45);
		scope.frame.setInt(5, 276);
		scope.frame.setBool(8, false);
		int isBiomeDark = 20;
		int valBiomeDark = 21;
		int valHurtDark = 22;
		int valSwordDark = 23;
		int screenDark = 24;
		ExprScope chain = name -> switch (name) {
			case "biome", "is_hurt", "heldItemId", "BIOME_RIVER", "BIOME_FOREST" -> scope.resolve(name);
			case "isBiomeDark" -> new ExprBinding.Frame(isBiomeDark, ExprType.BOOL);
			case "valBiomeDark" -> new ExprBinding.Frame(valBiomeDark, ExprType.FLOAT);
			case "valHurtDark" -> new ExprBinding.Frame(valHurtDark, ExprType.FLOAT);
			case "valSwordDark" -> new ExprBinding.Frame(valSwordDark, ExprType.FLOAT);
			case "screenDark" -> new ExprBinding.Frame(screenDark, ExprType.FLOAT);
			default -> null;
		};
		CompiledExpr isBiome = ExprCompiler.compile("in(biome, BIOME_RIVER, BIOME_FOREST)", chain, ExprType.BOOL);
		CompiledExpr smoothBiome = ExprCompiler.compile("smooth(1, if(isBiomeDark, 1, 0), 5)", chain, ExprType.FLOAT);
		CompiledExpr smoothHurt = ExprCompiler.compile("smooth(2, if(is_hurt, 1.3, 0), 0, 10)", chain, ExprType.FLOAT);
		CompiledExpr dark = ExprCompiler.compile("max(valBiomeDark, valHurtDark, valSwordDark)", chain, ExprType.FLOAT);
		CompiledExpr dark3 = ExprCompiler.compile("vec3(screenDark, heldItemId, biome)", chain, ExprType.VEC3);
		eval.deltaTime = 1f;
		float[] out = new float[4];
		for (int frame = 0; frame < 3; frame++) {
			scope.frame.setBool(isBiomeDark, isBiome.evalB(eval));
			scope.frame.setFloat(valBiomeDark, smoothBiome.evalF(eval));
			scope.frame.setFloat(valHurtDark, smoothHurt.evalF(eval));
			scope.frame.setFloat(valSwordDark, 0.5f);
			scope.frame.setFloat(screenDark, dark.evalF(eval));
			dark3.evalV(eval, out, 0);
			assertEquals(1f, out[0], 1e-5f, "screenDark, frame " + frame);
			assertEquals(276f, out[1], 1e-5f);
			assertEquals(45f, out[2], 1e-5f);
		}
		// The biome leaves the dark set: isBiomeDark turns false, the smooth value decays.
		scope.frame.setInt(BIOME, 1);
		scope.frame.setBool(isBiomeDark, isBiome.evalB(eval));
		assertFalse(scope.frame.getBool(isBiomeDark));
		// smooth(id, value, 5) gives only the rise: the fall takes the default fade of 1. A tenth of a second of it keeps
		// exp(-0.667) of the value.
		eval.deltaTime = 0.1f;
		float falling = smoothBiome.evalF(eval);
		assertEquals((float) Math.exp(-0.667), falling, 1e-4f, "a tenth of a second of the default fall, was " + falling);
	}
}
