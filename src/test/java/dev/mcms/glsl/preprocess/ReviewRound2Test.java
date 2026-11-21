// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.preprocess;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

/** Regression tests for the second review of the W1-A preprocessor. */
class ReviewRound2Test {
	static PreprocessResult run(String text) throws PreprocessException {
		return new Preprocessor(Mode.GLSL, (f, n) -> Optional.empty(), Map.of(), null).process(new SourceText("root.fsh", text));
	}

	static List<String> lines(PreprocessResult r) {
		return r.text().isEmpty() ? List.of() : List.of(r.text().split("\n", -1));
	}

	// A: a // comment in the lookahead must not move code into the comment

	@Test
	void lineCommentBeforeLookaheadParenDoesNotSwallowCode() throws PreprocessException {
		assertEquals(List.of("int a = [1];"), lines(run("#define F(x) [x]\nint a = F // c\n(1);\n")));
	}

	// B: physical lines inside a block comment are kept, even when empty

	@Test
	void emptyLineInsideBlockCommentIsKept() throws PreprocessException {
		assertEquals(List.of("a /* x", "", " y */ b"), lines(run("a /* x\n\n y */ b\n")));
	}

	// C: blank and comment-only lines are skipped when looking for '('

	@Test
	void blankLineBeforeParenStillExpands() throws PreprocessException {
		assertEquals(List.of("int a = [1];"), lines(run("#define F(x) [x]\nint a = F\n\n(1);\n")));
	}

	@Test
	void commentOnlyLineBeforeParenStillExpands() throws PreprocessException {
		assertEquals(List.of("int a = [1];"), lines(run("#define F(x) [x]\nint a = F\n// c\n(1);\n")));
	}

	@Test
	void directiveBetweenNameAndParenStopsLookahead() throws PreprocessException {
		assertEquals(List.of("int a = F", "(1);"), lines(run("#define F(x) [x]\nint a = F\n#define Z 1\n(1);\n")));
	}

	// D: comments in #version and #extension arguments are spaces, not an end

	@Test
	void blockCommentInsideVersionArgumentIsSpace() throws PreprocessException {
		PreprocessResult r = run("#version 330 /*x*/ compatibility\n#if GL_compatibility_profile\nok\n#endif\n");
		assertEquals("330 compatibility", r.version().replaceAll("\\s+", " "));
		assertTrue(lines(r).contains("ok"));
	}

	@Test
	void blockCommentInsideExtensionArgumentIsSpace() throws PreprocessException {
		PreprocessResult r = run("#extension GL_a /*x*/ : require\n");
		assertEquals(List.of("GL_a : require"), r.extensions().stream().map(s -> s.replaceAll("\\s+", " ")).toList());
	}

	// ## with both operands empty

	@Test
	void pasteWithBothOperandsEmptyLeavesNothing() throws PreprocessException {
		assertEquals("int x = [];", lines(run("#define P(a, b) [a##b]\nint x = P(,);\n")).get(0));
	}

	// Step cap: per logical line, not per run

	@Test
	void oneLineThatExceedsTheStepCapIsAnError() {
		StringBuilder src = new StringBuilder("#define Y0 1\n");
		for (int i = 1; i <= 21; i++) src.append("#define Y").append(i).append(" Y").append(i - 1).append(" Y").append(i - 1).append("\n");
		src.append("int v = Y21;\n");
		PreprocessException e = assertThrows(PreprocessException.class, () -> run(src.toString()));
		assertTrue(e.diagnostics().get(0).message().contains("macro expansion"));
	}

	@Test
	void stepsAcrossManyLinesDoNotAccumulate() throws PreprocessException {
		// Each line costs about 2,000 steps; 1,500 lines is about 3 million steps in total, with no error.
		StringBuilder src = new StringBuilder("#define Y0 1\n");
		for (int i = 1; i <= 10; i++) src.append("#define Y").append(i).append(" Y").append(i - 1).append(" Y").append(i - 1).append("\n");
		for (int i = 0; i < 1500; i++) src.append("int v").append(i).append(" = Y10;\n");
		assertEquals(1500, lines(run(src.toString())).size());
	}
}
