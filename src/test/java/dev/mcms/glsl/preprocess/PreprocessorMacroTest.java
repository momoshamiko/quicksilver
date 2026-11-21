// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.preprocess;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

/** Macro expansion: C rescanning rules, stringify, paste, argument handling, token separation. */
class PreprocessorMacroTest {
	static PreprocessResult glsl(String text) {
		try {
			return glslRaw(text);
		} catch (PreprocessException e) {
			throw new AssertionError(e);
		}
	}

	static PreprocessResult glslRaw(String text) throws PreprocessException {
		return new Preprocessor(Mode.GLSL, (f, n) -> Optional.empty(), Map.of(), null).process(new SourceText("root.fsh", text));
	}

	/** Runs text that ends in one active code line; the directive lines produce no output, so it is line 0. */
	static String expanded(String text) {
		PreprocessResult r = glsl(text + "\n");
		List<String> out = r.text().isEmpty() ? List.of() : List.of(r.text().split("\n", -1));
		assertEquals(1, out.size(), "expected a single output line, got: " + out);
		return out.get(0);
	}

	@Test
	void stringification() {
		assertEquals("const char* a = \"hello world\";", expanded("#define STR(x) #x\nconst char* a = STR(hello world);"));
	}

	@Test
	void stringificationKeepsArgumentSpacingAsSingleSpaces() {
		assertEquals("a = \"1 + 2\";", expanded("#define STR(x) #x\na = STR(  1   +  2 );"));
	}

	@Test
	void stringificationEscapesQuotesAndBackslashes() {
		assertEquals("a = \"\\\"x\\\\y\\\"\";", expanded("#define STR(x) #x\na = STR(\"x\\y\");"));
	}

	@Test
	void tokenPasting() {
		assertEquals("int xy = xy;", expanded("#define GLUE(a, b) a##b\nint xy = GLUE(x, y);"));
	}

	@Test
	void tokenPastingWithEmptyArgument() {
		assertEquals("int x = x;", expanded("#define GLUE(a, b) a##b\nint x = GLUE(x,);"));
		assertEquals("int y = y;", expanded("#define GLUE(a, b) a##b\nint y = GLUE(,y);"));
	}

	@Test
	void pasteNextToParameterUsesUnexpandedArgument() {
		// The argument NEXT is a macro, but ## pastes its raw spelling.
		assertEquals("int x = fooNEXT;", expanded("#define GLUE(a, b) a##b\n#define NEXT bar\nint x = GLUE(foo, NEXT);"));
	}

	@Test
	void nonPasteParameterUsesExpandedArgument() {
		assertEquals("int x = bar;", expanded("#define USE(a) a\n#define NEXT bar\nint x = USE(NEXT);"));
	}

	@Test
	void macroNotExpandedInsideItsOwnExpansion() {
		assertEquals("int a = X;", expanded("#define X X\nint a = X;"));
		assertEquals("int a = (4 + X);", expanded("#define X (4 + X)\nint a = X;"));
	}

	@Test
	void mutualRecursionTerminates() {
		assertEquals("int a = F;", expanded("#define F G\n#define G F\nint a = F;"));
	}

	@Test
	void functionLikeNameWithoutParensStaysAsIs() {
		assertEquals("int a = TWICE;", expanded("#define TWICE(x) ((x) + (x))\nint a = TWICE;"));
		assertEquals("int b = TWICE * 2;", expanded("#define TWICE(x) ((x) + (x))\nint b = TWICE * 2;"));
	}

	@Test
	void argumentsAreExpandedBeforeSubstitution() {
		assertEquals("int x = ((5) + 1);", expanded("#define N 5\n#define ADD(a) ((a) + 1)\nint x = ADD(N);"));
	}

	@Test
	void commaInsideNestedParenthesesIsOneArgument() {
		assertEquals("int a = max((1, 2));", expanded("#define CALL(f, x) f(x)\nint a = CALL(max,(1, 2));"));
	}

	@Test
	void commaInsideBracketsIsOneArgument() {
		assertEquals("vec2 v = vec2(1, 2);", expanded("#define FIRST(a) a\nvec2 v = FIRST(vec2(1, 2));"));
	}

	@Test
	void wrongArgumentCountIsAnError() {
		PreprocessException e = assertThrows(PreprocessException.class, () -> glslRaw("#define F(a, b) a\nint x = F(1);\n"));
		assertTrue(e.diagnostics().get(0).message().contains("expects 2"));
	}

	@Test
	void rescanningExpandsResultFurther() {
		assertEquals("int x = 7;", expanded("#define A B\n#define B 7\nint x = A;"));
	}

	@Test
	void expansionDoesNotGlueTokens() {
		assertEquals("int a = 1 + + 2;", expanded("#define P +\nint a = 1 P + 2;"));
	}

	@Test
	void expansionSeparatesIdentifiers() {
		assertEquals("int value x;", expanded("#define NAME value\nint NAME x;"));
	}

	@Test
	void expansionSeparatesNumbersFromIdentifiers() {
		assertEquals("int x = 5 e;", expanded("#define N 5\nint x = N e;"));
		assertEquals("int y = 1 5;", expanded("#define N 5\nint y = 1 N;"));
	}

	@Test
	void expansionSeparatesShifts() {
		assertEquals("int a = 1 << 2;", expanded("#define L <<\nint a = 1 L 2;"));
		assertEquals("int b = 1 < < 2;", expanded("#define LT <\nint b = 1 LT < 2;"));
	}

	@Test
	void commentInsideInvocationActsAsSpace() {
		assertEquals("int x = (1 + 3);", expanded("#define F(a) (a)\nint x = F(1 /* two */ + 3);"));
	}

	@Test
	void commentBetweenNameAndArgumentsStillCalls() {
		// The comment is kept in the output and moves in front of the expansion; the call still happens.
		assertEquals("int x = /* c */ (7);", expanded("#define F(a) (a)\nint x = F /* c */ (7);"));
	}

	@Test
	void invocationSpanningLinesIsOneOutputLine() {
		PreprocessResult r = glsl("""
				#define ADD(a, b) ((a) + (b))
				int x = ADD(1,
				2);
				""");
		List<String> out = r.text().isEmpty() ? List.of() : List.of(r.text().split("\n", -1));
		assertEquals(List.of("int x = ((1) + (2));"), out);
		assertEquals(1, r.lineMap().size());
		assertEquals(new LineOrigin("root.fsh", 2), r.lineMap().get(0));
	}

	@Test
	void directiveInsideInvocationIsAnError() {
		PreprocessException e = assertThrows(PreprocessException.class, () -> glslRaw("""
				#define ADD(a, b) ((a) + (b))
				int x = ADD(1,
				#endif
				2);
				"""));
		assertTrue(e.diagnostics().get(0).message().contains("directive inside macro invocation"));
	}

	@Test
	void unterminatedInvocationIsAnError() {
		PreprocessException e = assertThrows(PreprocessException.class, () -> glslRaw("#define F(a) a\nint x = F(1;\n"));
		assertTrue(e.diagnostics().get(0).message().contains("unterminated invocation"));
	}

	@Test
	void redefinitionReplacesBody() {
		assertEquals("int a = 2;", expanded("#define V 1\n#define V 2\nint a = V;"));
	}

	@Test
	void hidesetsDoNotBlockOtherMacros() {
		assertEquals("float a = clamp(0.5, 0, 1);", expanded("#define APPLY(f, x) f(x)\n#define SAT(v) clamp(v, 0, 1)\nfloat a = APPLY(SAT,0.5);"));
	}
}
