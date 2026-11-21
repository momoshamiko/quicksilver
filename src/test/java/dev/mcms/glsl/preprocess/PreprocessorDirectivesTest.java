// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.preprocess;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

/** Directive behavior of the GLSL preprocessor, from the GLSL specification's preprocessing chapter. */
class PreprocessorDirectivesTest {
	static PreprocessResult glsl(String text) {
		return glsl(text, Map.of());
	}

	static PreprocessResult glsl(String text, Map<String, String> predefined) {
		try {
			return new Preprocessor(Mode.GLSL, (f, n) -> Optional.empty(), predefined, null).process(new SourceText("root.fsh", text));
		} catch (PreprocessException e) {
			throw new AssertionError(e);
		}
	}

	static List<String> lines(PreprocessResult r) {
		return r.text().isEmpty() ? List.of() : List.of(r.text().split("\n", -1));
	}

	static PreprocessException fails(String text) {
		try {
			new Preprocessor(Mode.GLSL, (f, n) -> Optional.empty(), Map.of(), null).process(new SourceText("root.fsh", text));
			fail("expected PreprocessException");
			throw new AssertionError();
		} catch (PreprocessException e) {
			return e;
		}
	}

	@Test
	void objectAndFunctionDefineExpand() {
		PreprocessResult r = glsl("""
				#define COUNT 3
				#define ADD(a, b) a + b
				int c = COUNT;
				int d = ADD(1, 2);
				""");
		assertEquals(List.of("int c = 3;", "int d = 1 + 2;"), lines(r));
	}

	@Test
	void defineWithEmptyArgumentListAndNestedParentheses() {
		PreprocessResult r = glsl("""
				#define SEVEN() 7
				#define FIRST(x) x
				int a = SEVEN();
				vec2 b = FIRST((1, 2));
				""");
		assertEquals(List.of("int a = 7;", "vec2 b = (1, 2);"), lines(r));
	}

	@Test
	void parenthesizedBodyIsObjectLikeWhenSpaced() {
		// A '(' separated from the name by whitespace does not make the macro function-like (C rule).
		PreprocessResult r = glsl("""
				#define V (x)
				int a = V;
				""");
		assertEquals(List.of("int a = (x);"), lines(r));
	}

	@Test
	void undefRemovesMacro() {
		PreprocessResult r = glsl("""
				#define G 1
				#undef G
				int a = G;
				""");
		assertEquals(List.of("int a = G;"), lines(r));
	}

	@Test
	void undefPredefinedThenRedefine() {
		PreprocessResult r = glsl("""
				#undef MAC
				int a = MAC;
				#define MAC 5
				int b = MAC;
				""", Map.of("MAC", "2"));
		assertEquals(List.of("int a = MAC;", "int b = 5;"), lines(r));
	}

	@Test
	void ifdefAndIfndef() {
		PreprocessResult r = glsl("""
				#ifdef A
				one
				#endif
				#ifndef A
				two
				#endif
				#ifdef B
				three
				#endif
				""", Map.of("B", "1"));
		assertEquals(List.of("two", "three"), lines(r));
	}

	@Test
	void ifElifElseEndifChain() {
		PreprocessResult r = glsl("""
				#if X == 2
				first
				#elif X == 4
				second
				#else
				third
				#endif
				""", Map.of("X", "4"));
		assertEquals(List.of("second"), lines(r));
	}

	@Test
	void takenElifExpressionIsNotEvaluated() {
		// X == 2 is true, so the #elif condition (division by zero) must never run.
		PreprocessResult r = glsl("""
				#if X == 2
				ok
				#elif 1 / 0
				bad
				#endif
				""", Map.of("X", "2"));
		assertEquals(List.of("ok"), lines(r));
	}

	@Test
	void inactiveBranchesAreIgnoredEntirely() {
		PreprocessResult r = glsl("""
				#if 0
				#define HIDDEN 1
				#error should not fire
				garbage that does not lex ))) ***
				#elif 0
				also hidden
				#endif
				#define HIDDEN 2
				int a = HIDDEN;
				""");
		assertEquals(List.of("int a = 2;"), lines(r));
		assertTrue(r.warnings().isEmpty());
	}

	@Test
	void nestedConditionals() {
		PreprocessResult r = glsl("""
				#if 1
				#if 0
				no
				#else
				yes
				#endif
				#endif
				""");
		assertEquals(List.of("yes"), lines(r));
	}

	@Test
	void unterminatedConditionalIsError() {
		PreprocessException e = fails("""
				#if 1
				int a = 1;
				""");
		assertEquals(1, e.diagnostics().size());
		assertEquals("root.fsh", e.diagnostics().get(0).path());
		assertEquals(1, e.diagnostics().get(0).line());
		assertTrue(e.diagnostics().get(0).message().contains("unterminated"));
	}

	@Test
	void strayElseIsAnErrorAndStrayEndifAWarning() {
		assertTrue(fails("x\n#else\n").diagnostics().get(0).message().contains("#else without #if"));
		// Photon 1.4's TAAU pass ends with a stray #endif: the code around it is kept, and a conditional after it still works.
		PreprocessResult r = glsl("int a;\n#endif\n#ifdef NOPE\nint b;\n#endif\nint c;\n", Map.of());
		assertEquals(List.of("int a;", "int c;"), lines(r));
		assertEquals(1, r.warnings().size());
		assertTrue(r.warnings().get(0).message().contains("#endif without #if"));
		assertEquals(2, r.warnings().get(0).line());
	}

	@Test
	void duplicateElseAndElifAfterElseAreErrors() {
		assertTrue(fails("#if 1\n#else\n#else\n#endif\n").diagnostics().get(0).message().contains("duplicate #else"));
		assertTrue(fails("#if 1\n#else\n#elif 1\n#endif\n").diagnostics().get(0).message().contains("#elif after #else"));
	}

	@Test
	void errorDirectiveFiresWithMessageAndPosition() {
		PreprocessException e = fails("int a;\n#error pack requires OptiFine\n");
		Diagnostic d = e.diagnostics().get(0);
		assertEquals("root.fsh", d.path());
		assertEquals(2, d.line());
		assertTrue(d.message().contains("pack requires OptiFine"));
	}

	@Test
	void errorDirectiveTakesRestOfLine() {
		PreprocessException e = fails("#error custom message here // not a comment problem\n");
		assertTrue(e.diagnostics().get(0).message().contains("custom message here"));
	}

	@Test
	void lineDirectiveIsDroppedAndRenumbered() {
		PreprocessResult r = glsl("""
				int a;
				#line 100
				int L = __LINE__;
				""");
		assertEquals(List.of("int a;", "int L = 100;"), lines(r));
	}

	@Test
	void versionRecordedAndKept() {
		PreprocessResult r = glsl("#version 400 compatibility\nint a;\n");
		assertEquals("400 compatibility", r.version());
		assertEquals(List.of("#version 400 compatibility", "int a;"), lines(r));
		assertEquals(2, r.lineMap().size());
		assertEquals(new LineOrigin("root.fsh", 1), r.lineMap().get(0));
	}

	@Test
	void versionBuiltinsFollowDirective() {
		PreprocessResult r = glsl("""
				#version 430
				int v = __VERSION__;
				int c = GL_core_profile;
				int p = GL_compatibility_profile;
				""");
		assertEquals("430", r.version());
		assertEquals(List.of("#version 430", "int v = 430;", "int c = 1;", "int p = GL_compatibility_profile;"), lines(r));
	}

	@Test
	void defaultVersionIs110Compatibility() {
		PreprocessResult r = glsl("int v = __VERSION__;\nint c = GL_compatibility_profile;\n");
		assertNull(r.version());
		assertEquals(List.of("int v = 110;", "int c = 1;"), lines(r));
	}

	@Test
	void versionBelow150IsCompatibility() {
		PreprocessResult r = glsl("#version 130\nint p = GL_compatibility_profile;\n");
		assertEquals(List.of("#version 130", "int p = 1;"), lines(r));
	}

	@Test
	void extensionAndPragmaKeptInOutput() {
		PreprocessResult r = glsl("""
				#extension GL_ARB_shader_texture_lod : require
				#pragma optimize(off)
				int a;
				""");
		assertEquals(List.of("#extension GL_ARB_shader_texture_lod : require", "#pragma optimize(off)", "int a;"), lines(r));
		assertEquals(List.of("GL_ARB_shader_texture_lod : require"), r.extensions());
	}

	@Test
	void extensionInInactiveBranchNotRecorded() {
		PreprocessResult r = glsl("#if 0\n#extension GL_ARB_x : require\n#endif\n");
		assertEquals("", r.text());
		assertEquals(List.of(), r.extensions());
	}

	@Test
	void unknownDirectiveDroppedWithWarning() {
		PreprocessResult r = glsl("#foo bar\nint a;\n");
		assertEquals(List.of("int a;"), lines(r));
		assertEquals(1, r.warnings().size());
		assertTrue(r.warnings().get(0).message().contains("#foo"));
	}

	@Test
	void unknownDirectiveInInactiveCodeIsSilent() {
		PreprocessResult r = glsl("#if 0\n#foo\n#endif\nint a;\n");
		assertEquals(List.of("int a;"), lines(r));
		assertTrue(r.warnings().isEmpty());
	}

	@Test
	void nullDirectiveDropped() {
		PreprocessResult r = glsl("#\nint a;\n");
		assertEquals(List.of("int a;"), lines(r));
		assertTrue(r.warnings().isEmpty());
	}

	@Test
	void lineMarkerDirectiveDropped() {
		PreprocessResult r = glsl("# 42 \"generated.glsl\"\nint a;\n");
		assertEquals(List.of("int a;"), lines(r));
	}

	@Test
	void lineAndFileBuiltins() {
		PreprocessResult r = glsl("""
				int a = __LINE__;
				int b = __LINE__;
				int f = __FILE__;
				""");
		assertEquals(List.of("int a = 1;", "int b = 2;", "int f = 0;"), lines(r));
	}

	@Test
	void lineBuiltinAfterContinuationKeepsFirstLine() {
		PreprocessResult r = glsl("""
				int a = __LINE__, \\
				b = __LINE__;
				""");
		assertEquals(List.of("int a = 1, b = 1;"), lines(r));
	}

	@Test
	void objectMacrosAtEndContainsFileDefinesOnly() {
		PreprocessResult r = glsl("""
				#define KEEP 1
				#define FN(x) x
				#define GONE 2
				#undef GONE
				int a;
				""", Map.of("PRE", "9"));
		assertEquals(Map.of("KEEP", "1"), r.objectMacrosAtEnd());
	}

	@Test
	void objectMacrosAtEndHoldsFinalReplacements() {
		PreprocessResult r = glsl("""
				#define TWICE 1
				#define TWICE 2
				""");
		assertEquals(Map.of("TWICE", "2"), r.objectMacrosAtEnd());
	}

	@Test
	void commentsAreStrippedFromMacroBodies() {
		PreprocessResult r = glsl("""
				#define OPT 5 // [1 5] tooltip text
				#define V 2 /* inline */ + 3
				int a = OPT;
				int b = V;
				""");
		assertEquals(Map.of("OPT", "5", "V", "2 + 3"), r.objectMacrosAtEnd());
		assertEquals(List.of("int a = 5;", "int b = 2 + 3;"), lines(r));
	}
}
