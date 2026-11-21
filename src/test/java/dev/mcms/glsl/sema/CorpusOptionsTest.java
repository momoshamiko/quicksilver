// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.sema;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.mcms.glsl.preprocess.LineRewriter;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CorpusOptionsTest {

	private static String apply(Map<String, String> options, String line) {
		return CorpusOptions.rewriter(options).rewrite("lib/common.glsl", 1, line);
	}

	@Test
	void numericOptionReplacesTheDefaultAndKeepsItsComment() {
		assertEquals("    #define CLOUD_STYLE_DEFINE 50 //[-1 0 1 3 50]",
				apply(Map.of("CLOUD_STYLE_DEFINE", "50"), "    #define CLOUD_STYLE_DEFINE -1 //[-1 0 1 3 50]"));
	}

	@Test
	void trueUncommentsAndFalseCommentsOut() {
		assertEquals("#define ATM_COLOR_MULTS", apply(Map.of("ATM_COLOR_MULTS", "true"), "//#define ATM_COLOR_MULTS"));
		assertEquals("//#define DOF", apply(Map.of("DOF", "false"), "#define DOF"));
	}

	@Test
	void numericOptionWithoutDefaultGetsItsValue() {
		assertEquals("#define SHADOW_SMOOTHING 3", apply(Map.of("SHADOW_SMOOTHING", "3"), "#define SHADOW_SMOOTHING"));
	}

	@Test
	void commentOnlyTailIsNotTakenAsAValue() {
		assertEquals("#define NIGHT 2 //[0 1 2]",
				apply(Map.of("NIGHT", "2"), "#define NIGHT //[0 1 2]"));
	}

	@Test
	void unnamedOrUnrelatedLinesAreUnchanged() {
		Map<String, String> options = Map.of("DOF", "false");
		LineRewriter rw = CorpusOptions.rewriter(options);
		assertEquals("#define BLOOM", rw.rewrite("a.glsl", 1, "#define BLOOM"));
		assertEquals("int dof = 1;", rw.rewrite("a.glsl", 2, "int dof = 1;"));
		assertEquals("", rw.rewrite("a.glsl", 3, ""));
	}

	@Test
	void parseSkipsCommentsAndBlankLines() {
		Map<String, String> o = CorpusOptions.parse("#Thu Oct 08\nAMBIENT_MULT=150\n\nDOF=false\nshadowDistance=160.0\n");
		assertEquals("150", o.get("AMBIENT_MULT"));
		assertEquals("false", o.get("DOF"));
		assertEquals("160.0", o.get("shadowDistance"));
		assertEquals(3, o.size());
	}
}
