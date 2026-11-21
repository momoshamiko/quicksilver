// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.preprocess;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

/** {@code #if} expression evaluation: operators, literals, defined, error cases. */
class PreprocessorExpressionTest {
	static List<String> eval(String expr) {
		try {
			return new Preprocessor(Mode.GLSL, (f, n) -> Optional.empty(), Map.of(), null)
					.process(new SourceText("root.fsh", "#if " + expr + "\nyes\n#else\nno\n#endif\n"))
					.text().lines().toList();
		} catch (PreprocessException e) {
			throw new AssertionError(e);
		}
	}
	static boolean truthy(String expr) {
		return eval(expr).contains("yes");
	}

	static PreprocessException fails(String expr) {
		try {
			new Preprocessor(Mode.GLSL, (f, n) -> Optional.empty(), Map.of(), null)
					.process(new SourceText("root.fsh", "#if " + expr + "\nyes\n#endif\n"));
			fail("expected failure for: " + expr);
			throw new AssertionError();
		} catch (PreprocessException e) {
			return e;
		}
	}

	@Test
	void integerLiteralsDecimalOctalHexAndSuffixes() {
		assertTrue(truthy("42 == 42"));
		assertTrue(truthy("052 == 42"));
		assertTrue(truthy("0x2A == 42"));
		assertTrue(truthy("0X2a == 42u"));
		assertTrue(truthy("42L == 42"));
		assertFalse(truthy("0x10 == 15"));
		assertTrue(truthy("0xFF == 255"));
	}

	@Test
	void arithmeticPrecedence() {
		assertTrue(truthy("2 + 3 * 4 == 14"));
		assertTrue(truthy("(2 + 3) * 4 == 20"));
		assertTrue(truthy("10 - 4 - 3 == 3"));
		assertTrue(truthy("20 / 3 == 6"));
		assertTrue(truthy("20 % 3 == 2"));
		assertTrue(truthy("-7 / 2 == -3"));
	}

	@Test
	void unaryOperators() {
		assertTrue(truthy("-5 == 0 - 5"));
		assertTrue(truthy("+5 == 5"));
		assertTrue(truthy("~0 == -1"));
		assertTrue(truthy("!0"));
		assertFalse(truthy("!3"));
		assertTrue(truthy("- - 5 == 5"));
	}

	@Test
	void shiftsAndBitwise() {
		assertTrue(truthy("1 << 4 == 16"));
		assertTrue(truthy("16 >> 2 == 4"));
		assertTrue(truthy("(0xF0 | 0x0F) == 0xFF"));
		assertTrue(truthy("(0xFF & 0x0F) == 0x0F"));
		assertTrue(truthy("(0xFF ^ 0xF0) == 0x0F"));
		assertTrue(truthy("(3 | 4 & 1) == 3"));
	}

	@Test
	void comparisonsAndEquality() {
		assertTrue(truthy("3 < 4"));
		assertTrue(truthy("4 <= 4"));
		assertTrue(truthy("5 > 4"));
		assertTrue(truthy("5 >= 5"));
		assertTrue(truthy("5 == 5"));
		assertTrue(truthy("5 != 4"));
		assertFalse(truthy("5 == 4"));
	}

	@Test
	void logicalOperators() {
		assertTrue(truthy("1 && 2"));
		assertFalse(truthy("1 && 0"));
		assertTrue(truthy("0 || 3"));
		assertFalse(truthy("0 || 0"));
		assertTrue(truthy("0 || 1 && 1"));
	}

	@Test
	void logicalOperatorsShortCircuitPastDivisionByZero() {
		// 0 && (1/0) is 0 and 1 || (1/0) is 1; the dead side never evaluates, so neither errors.
		assertFalse(truthy("0 && 1 / 0"));
		assertTrue(truthy("1 || 1 / 0"));
		assertFalse(truthy("0 || 0 && 1 / 0"));
	}

	@Test
	void ternaryOperator() {
		assertTrue(truthy("(1 ? 10 : 20) == 10"));
		assertTrue(truthy("(0 ? 10 : 20) == 20"));
		assertTrue(truthy("(1 ? 1 : 1 / 0) == 1"));
		assertTrue(truthy("(0 ? 1 / 0 : 2) == 2"));
	}

	@Test
	void definedForms() {
		assertTrue(truthy("defined(__LINE__)"));
		assertTrue(truthy("defined __VERSION__"));
		assertFalse(truthy("defined(B)"));
		assertTrue(truthy("defined(__FILE__) && !defined(B)"));
	}

	@Test
	void identifiersLeftAfterExpansionAreZero() {
		assertTrue(truthy("SOME_NAME == 0"));
		assertFalse(truthy("SOME_NAME"));
		assertTrue(truthy("true == 0"));
	}

	@Test
	void macrosExpandBeforeEvaluation() throws PreprocessException {
		List<String> lines = new Preprocessor(Mode.GLSL, (f, n) -> Optional.empty(), Map.of("N", "6"), null)
				.process(new SourceText("root.fsh", "#define DOUBLE (N * 2)\n#if DOUBLE == 12\nyes\n#else\nno\n#endif\n"))
				.text().lines().toList();
		assertTrue(lines.contains("yes"));
	}

	@Test
	void definedOperandIsNotExpanded() throws PreprocessException {
		// FOO expands to BAR; defined(FOO) asks about the name FOO, not BAR.
		List<String> lines = new Preprocessor(Mode.GLSL, (f, n) -> Optional.empty(), Map.of(), null)
				.process(new SourceText("root.fsh", "#define FOO BAR\n#if defined(FOO) && !defined(BAR)\nyes\n#else\nno\n#endif\n"))
				.text().lines().toList();
		assertTrue(lines.contains("yes"));
	}

	@Test
	void floatValuesFromPackMacrosCompare() throws PreprocessException {
		List<String> lines = new Preprocessor(Mode.GLSL, (f, n) -> Optional.empty(),
				Map.of("MC_RENDER_QUALITY", "1.0", "MC_HAND_DEPTH", "0.125"), null)
				.process(new SourceText("root.fsh", """
						#if MC_RENDER_QUALITY == 1.0
						full
						#else
						reduced
						#endif
						#if MC_HAND_DEPTH < 0.5
						near
						#endif
						"""))
				.text().lines().toList();
		assertEquals(List.of("full", "near"), lines);
	}

	@Test
	void divisionAndModuloByZeroAreErrors() {
		assertTrue(fails("1 / 0").diagnostics().get(0).message().contains("division by zero"));
		assertTrue(fails("5 % 0").diagnostics().get(0).message().contains("modulo by zero"));
		assertTrue(fails("1.0 / 0.0").diagnostics().get(0).message().contains("division by zero"));
	}

	@Test
	void syntaxErrorsAreReported() {
		assertTrue(fails("1 +").diagnostics().get(0).message().contains("#if"));
		assertTrue(fails("(1").diagnostics().get(0).message().contains("#if"));
		assertTrue(fails("\"str\"").diagnostics().get(0).message().contains("#if"));
	}

	@Test
	void missingExpressionIsError() {
		try {
			new Preprocessor(Mode.GLSL, (f, n) -> Optional.empty(), Map.of(), null)
					.process(new SourceText("root.fsh", "#if\n#endif\n"));
			fail("expected failure");
		} catch (PreprocessException e) {
			assertTrue(e.diagnostics().get(0).message().contains("missing expression"));
		}
	}
}
