// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.preprocess;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

/** Regression tests for the review of the W1-A preprocessor, one group per review item. */
class ReviewFixesTest {
	static PreprocessResult run(String text, Map<String, String> predefined) throws PreprocessException {
		return new Preprocessor(Mode.GLSL, (f, n) -> Optional.empty(), predefined, null).process(new SourceText("root.fsh", text));
	}

	static PreprocessResult run(String text) throws PreprocessException {
		return run(text, Map.of());
	}

	static List<String> lines(PreprocessResult r) {
		return r.text().isEmpty() ? List.of() : List.of(r.text().split("\n", -1));
	}

	static String one(String text) throws PreprocessException {
		List<String> out = lines(run(text));
		assertEquals(1, out.size(), "expected one output line, got " + out);
		return out.get(0);
	}

	// 1. block comments that open or close on a directive line

	@Test
	void commentOpenedOnDefineIsDroppedWithItsTail() throws PreprocessException {
		assertEquals(List.of("float x = 1;"), lines(run("#define FOO 1 /* this is\n   a long comment */\nfloat x = FOO;\n")));
	}

	@Test
	void commentOpenedBeforeDirectiveIsEmittedWhole() throws PreprocessException {
		assertEquals(List.of("/* header", " */", "int y = 2;"), lines(run("/* header\n */ #define Y 2\nint y = Y;\n")));
	}

	// 2. // comments continued by a backslash

	@Test
	void continuedLineCommentStaysAComment() throws PreprocessException {
		List<String> out = lines(run("// note C:\\dir\\\nfloat hidden = 1.0;\nint z;\n"));
		assertEquals(List.of("// note C:\\dirfloat hidden = 1.0;", "int z;"), out);
	}

	@Test
	void continuedPragmaCommentStaysAComment() throws PreprocessException {
		List<String> out = lines(run("#pragma x // c \\\nfloat hidden = 1.0;\nint z;\n"));
		assertEquals(2, out.size());
		assertEquals("int z;", out.get(1));
	}

	// 3. hexadecimal and malformed numbers in #if

	@Test
	void hexWithUnsignedSuffixEvaluates() throws PreprocessException {
		assertEquals("yes", one("#if 0x10u == 16\nyes\n#endif\n"));
		assertEquals("yes", one("#if 0xFFu == 255\nyes\n#endif\n"));
		assertEquals("yes", one("#if 0xFFuL == 255\nyes\n#endif\n"));
	}

	@Test
	void malformedIntegerInIfIsAnError() {
		PreprocessException e = assertThrows(PreprocessException.class, () -> run("#if 1abc\nx\n#endif\n"));
		assertTrue(e.diagnostics().get(0).message().contains("invalid integer constant"));
	}

	@Test
	void floatInIfIsAcceptedWithWarning() throws PreprocessException {
		PreprocessResult r = run("#if 1.0 == 1\nyes\n#endif\n");
		assertEquals(List.of("yes"), lines(r));
		assertTrue(r.warnings().stream().anyMatch(d -> d.severity() == Diagnostic.Severity.WARNING && d.message().contains("floating-point")));
	}

	// 4. only the active GL profile name is defined

	@Test
	void compatibilityVersionDefinesOnlyCompatibilityProfile() throws PreprocessException {
		PreprocessResult r = run("#version 400 compatibility\nint c = GL_compatibility_profile;\nint k = GL_core_profile;\n");
		assertEquals(List.of("#version 400 compatibility", "int c = 1;", "int k = GL_core_profile;"), lines(r));
	}

	@Test
	void coreVersionDefinesOnlyCoreProfile() throws PreprocessException {
		PreprocessResult r = run("#version 430\n#ifdef GL_core_profile\ncore\n#endif\n#ifdef GL_compatibility_profile\ncompat\n#endif\n");
		assertEquals(List.of("#version 430", "core"), lines(r));
	}

	// 5. ## with an empty operand

	@Test
	void pasteWithEmptyLeftArgumentUsesPlacemarker() throws PreprocessException {
		assertEquals("float foo;", one("#define DECL(p, n) float p##n;\nDECL(,foo)\n"));
	}

	@Test
	void pasteWithEmptyRightArgumentKeepsLeft() throws PreprocessException {
		assertEquals("float foo;", one("#define DECL(p, n) float p##n;\nDECL(foo,)\n"));
	}

	// 6. object-like ## is applied

	@Test
	void objectLikePasteIsApplied() throws PreprocessException {
		assertEquals("int c = ab;", one("#define CAT a ## b\nint c = CAT;\n"));
	}

	// 7. version and extension arguments stop at a comment

	@Test
	void versionIgnoresTrailingComment() throws PreprocessException {
		PreprocessResult r = run("#version 330 // not compatibility here\n#if GL_core_profile\ncore\n#endif\n");
		assertEquals("330", r.version());
		assertEquals(List.of("#version 330 // not compatibility here", "core"), lines(r));
	}

	@Test
	void extensionIgnoresTrailingComment() throws PreprocessException {
		PreprocessResult r = run("#extension GL_ARB_x : require // why\n");
		assertEquals(List.of("GL_ARB_x : require"), r.extensions());
	}

	// P3: function-like macro name with its '(' on the next line

	@Test
	void functionLikeNameWithParenOnNextLineExpands() throws PreprocessException {
		assertEquals("int a = (3);", one("#define F(x) (x)\nint a = F\n(3);\n"));
	}

	@Test
	void functionLikeNameWithoutParenOnNextLineStaysAsIs() throws PreprocessException {
		List<String> out = lines(run("#define F(x) (x)\nint a = F\nint b;\n"));
		assertEquals(List.of("int a = F", "int b;"), out);
	}

	// P3: spacing and pp-number rules

	@Test
	void dotAfterIdentifierHasNoSpace() throws PreprocessException {
		assertEquals("int q = a.b;", one("#define X a\nint q = X.b;\n"));
	}

	@Test
	void hexWithEBeforeMinusExpandsFollowingMacro() throws PreprocessException {
		assertEquals("int v = 0xE-3;", one("#define N 3\nint v = 0xE-N;\n"));
	}

	@Test
	void apostropheOutsideCommentDoesNotHideFollowingComment() throws PreprocessException {
		assertEquals(List.of("int a = 1; ' int b;"), lines(run("int a = 1; ' /* c */ int b;\n")).stream()
				.map(s -> s.replace("/* c */ ", "")).toList());
	}

	// P3: the expansion step cap is per logical line

	@Test
	void stepCapIsResetPerLine() throws PreprocessException {
		StringBuilder src = new StringBuilder("#define A(x) x\n");
		for (int i = 0; i < 3000; i++) src.append("int v").append(i).append(" = A(1) + A(2) + A(3) + A(4);\n");
		assertEquals(3000, lines(run(src.toString())).size());
	}

	// P3: properties mode treats '# word' as a comment

	@Test
	void spacedHashIsCommentInPropertiesMode() throws PreprocessException {
		PreprocessResult r = new Preprocessor(Mode.PROPERTIES, (f, n) -> Optional.empty(), Map.of(), null)
				.process(new SourceText("shaders.properties", "# if you want this, edit it\n# else nothing\nkey=1\n#if 1\nkept=2\n#endif\n"));
		assertEquals(List.of("key=1", "kept=2"), lines(r));
	}

	// P3: unterminated conditional reports the innermost opener

	@Test
	void unterminatedReportsInnermostConditional() {
		PreprocessException e = assertThrows(PreprocessException.class, () -> run("#if 1\n#if 1\nx\n"));
		assertEquals(2, e.diagnostics().get(0).line());
	}

	// P3: backslash is a separator in pack paths

	@Test
	void normalizeTurnsBackslashIntoSlash() {
		assertEquals("a/b/c", dev.mcms.shaders.pack.PackPaths.normalize("a\\b\\c"));
	}
}
