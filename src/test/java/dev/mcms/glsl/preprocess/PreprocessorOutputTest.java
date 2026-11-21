// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.preprocess;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;

/** Output shape: line map, comment preservation, inactive dropping, properties mode, speed log. */
class PreprocessorOutputTest {
	static PreprocessResult glsl(String text) {
		try {
			return new Preprocessor(Mode.GLSL, (f, n) -> Optional.empty(), Map.of(), null).process(new SourceText("root.fsh", text));
		} catch (PreprocessException e) {
			throw new AssertionError(e);
		}
	}

	static PreprocessResult props(String text, Map<String, String> predefined) {
		try {
			return new Preprocessor(Mode.PROPERTIES, (f, n) -> Optional.empty(), predefined, null).process(new SourceText("shaders.properties", text));
		} catch (PreprocessException e) {
			throw new AssertionError(e);
		}
	}
	static List<String> lines(PreprocessResult r) {
		return r.text().isEmpty() ? List.of() : List.of(r.text().split("\n", -1));
	}

	static PreprocessResult propsExpanding(String text, Map<String, String> predefined, Predicate<String> expandedKeys) {
		try {
			return new Preprocessor(Mode.PROPERTIES, (f, n) -> Optional.empty(), predefined, null, expandedKeys)
				.process(new SourceText("shaders.properties", text));
		} catch (PreprocessException e) {
			throw new AssertionError(e);
		}
	}

	@Test
	void oneOutputLinePerLineMapEntry() {
		PreprocessResult r = glsl("""
				#version 120
				/* multi
				line comment */
				int a = 1; // trailing
				#if 1
				int b = 2;
				#endif
				#define DROPPED
				""");
		assertEquals(r.lineMap().size(), lines(r).size());
		assertEquals(List.of("#version 120", "/* multi", "line comment */", "int a = 1; // trailing", "int b = 2;"), lines(r));
		assertEquals(List.of(new LineOrigin("root.fsh", 1), new LineOrigin("root.fsh", 2), new LineOrigin("root.fsh", 3),
				new LineOrigin("root.fsh", 4), new LineOrigin("root.fsh", 6)), r.lineMap());
	}

	@Test
	void drawbuffersCommentSurvivesAndFollowsConditional() {
		PreprocessResult r = glsl("""
				#define MODE 1
				#if MODE == 0
				/* DRAWBUFFERS:05 */
				#else
				/* DRAWBUFFERS:0257 */
				#endif
				void main() { }
				""");
		assertEquals(List.of("/* DRAWBUFFERS:0257 */", "void main() { }"), lines(r));
	}

	@Test
	void rendertargetsCommentSurvives() {
		PreprocessResult r = glsl("/* RENDERTARGETS: 0,2,11,15 */\n");
		assertEquals(List.of("/* RENDERTARGETS: 0,2,11,15 */"), lines(r));
	}

	@Test
	void directiveInsideCommentIsNotADirective() {
		PreprocessResult r = glsl("""
				/* #if 0
				not a conditional */ kept
				// #endif
				also kept
				""");
		assertEquals(List.of("/* #if 0", "not a conditional */ kept", "// #endif", "also kept"), lines(r));
	}

	@Test
	void blockCommentOpenedInInactiveRegionSwallowsDirective() {
		// Comments run before conditionals: the #endif inside the comment is not a directive, the one after the comment
		// closes the #if, so the file stays balanced and all inactive lines (comment included) are dropped.
		PreprocessResult r = glsl("""
				#if 0
				/* swallows
				#endif
				*/
				#endif
				after
				""");
		assertEquals(List.of("after"), lines(r));
		assertTrue(r.warnings().isEmpty());
	}

	@Test
	void inactiveCommentProducesNoOutputButActiveCodeAfterItDoes() {
		PreprocessResult r = glsl("""
				#if 0
				/* hidden
				still hidden */
				#endif
				after
				""");
		assertEquals(List.of("after"), lines(r));
	}

	@Test
	void blockCommentSplitAcrossSplicedLines() {
		PreprocessResult r = glsl("""
				a /* one \\
				two */ b
				""");
		// The backslash splices the lines, but the comment crossing the physical boundary keeps both lines.
		List<String> out = lines(r);
		assertEquals(2, out.size());
		assertTrue(out.get(0).startsWith("a /* one"));
		assertTrue(out.get(1).startsWith("two */ b"));
		assertEquals(new LineOrigin("root.fsh", 1), r.lineMap().get(0));
		assertEquals(new LineOrigin("root.fsh", 2), r.lineMap().get(1));
	}

	@Test
	void blankActiveLinesAreKept() {
		PreprocessResult r = glsl("a\n\nb\n");
		assertEquals(List.of("a", "", "b"), lines(r));
		assertEquals(new LineOrigin("root.fsh", 2), r.lineMap().get(1));
	}

	@Test
	void lineContinuationJoinsIntoOneOutputLine() {
		PreprocessResult r = glsl("int \\\na = 1;\nint b = 2;\n");
		assertEquals(List.of("int a = 1;", "int b = 2;"), lines(r));
		assertEquals(new LineOrigin("root.fsh", 1), r.lineMap().get(0));
		assertEquals(new LineOrigin("root.fsh", 3), r.lineMap().get(1));
	}

	@Test
	void keptDirectiveWithContinuedCommentKeepsPhysicalLines() {
		PreprocessResult r = glsl("#version 120 /* kept \\\ncomment */\n");
		List<String> out = lines(r);
		assertEquals(2, out.size());
		assertTrue(out.get(0).startsWith("#version 120 /* kept"));
		assertTrue(out.get(1).trim().equals("comment */"));
	}
	@Test
	void versionAndPragmaKeepOriginalSpacing() {
		PreprocessResult r = glsl("#version  330\tcompatibility\n#pragma debug(on)\n");
		assertEquals(List.of("#version  330\tcompatibility", "#pragma debug(on)"), lines(r));
		assertEquals("330\tcompatibility", r.version());
	}

	@Test
	void propertiesModeKeepsTextAndDropsCommentLines() {
		PreprocessResult r = props("""
				# plain comment
				sliders=BLUR_AMOUNT
				profile.LOW=BLUR_AMOUNT:0.5
				#include "nope.properties"
				texture.composite.colortex9=textures/noise.png
				""", Map.of());
		assertEquals(List.of("sliders=BLUR_AMOUNT", "profile.LOW=BLUR_AMOUNT:0.5", "texture.composite.colortex9=textures/noise.png"), lines(r));
		assertEquals(1, r.warnings().size(), "#include warns; #define no longer does");
	}

	@Test
	void propertiesModeConditionalsUsePredefined() {
		PreprocessResult r = props("""
				always=1
				#if MC_VERSION >= 20000
				new.path=block
				#ifdef LEGACY
				legacy.path=block
				#endif
				#else
				old.path=block
				#endif
				""", Map.of("MC_VERSION", "260300"));
		assertEquals(List.of("always=1", "new.path=block"), lines(r));
	}

	@Test
	void propertiesModeKeepsBackslashLineEndings() {
		PreprocessResult r = props("""
				key.1=minecraft:stone \\
				minecraft:dirt
				key.2=plain
				""", Map.of());
		assertEquals(List.of("key.1=minecraft:stone \\", "minecraft:dirt", "key.2=plain"), lines(r));
	}

	@Test
	void propertiesModeConditionalMayEndWithBackslash() {
		PreprocessResult r = props("""
				#if MC_VERSION >= 1 \\
				&& MC_VERSION <= 9999999
				inside=1
				#endif
				""", Map.of("MC_VERSION", "260300"));
		assertEquals(List.of("inside=1"), lines(r));
	}

	@Test
	void propertiesModeUnterminatedConditionalIsError() {
		assertThrows(PreprocessException.class, () -> new Preprocessor(Mode.PROPERTIES, (f, n) -> Optional.empty(), Map.of(), null)
				.process(new SourceText("shaders.properties", "#if 0\nkey=1\n")));
		assertThrows(PreprocessException.class, () -> new Preprocessor(Mode.PROPERTIES, (f, n) -> Optional.empty(), Map.of(), null)
				.process(new SourceText("shaders.properties", "#endif\n")));
	}

	@Test
	void propertiesModeHasNoMacroExpansionInText() {
		PreprocessResult r = props("key=MC_VERSION value\n", Map.of("MC_VERSION", "42"));
		assertEquals(List.of("key=MC_VERSION value"), lines(r));
	}

	@Test
	void expandedKeysTakeTheMacrosDefinedAtTheirLine() {
		// size.buffer values name options and #defines: each line sees the macros above it, and a later #define is not seen
		String text = "#define REFLECTION_RES 0.5\nsize.buffer.colortex1 = REFLECTION_RES REFLECTION_RES\n"
			+ "#define REFLECTION_RES 1.0\nsize.buffer.colortex7 = REFLECTION_RES REFLECTION_RES\n";
		assertEquals(List.of("size.buffer.colortex1=0.5 0.5", "size.buffer.colortex7=1.0 1.0"),
			lines(propsExpanding(text, Map.of(), key -> key.startsWith("size.buffer."))));
	}

	@Test
	void expandedKeysUseTheOptionValuesAndOtherKeysKeepTheirNames() {
		// option values are predefined macros; screen and profile lines keep the names of the options they list
		String text = "screen.MATERIAL=REFLECTION_RES\nprofile.HIGH=REFLECTION_RES\nsize.buffer.colortex1 = REFLECTION_RES 2\n";
		assertEquals(List.of("screen.MATERIAL=REFLECTION_RES", "profile.HIGH=REFLECTION_RES",
				"size.buffer.colortex1=0.5 2"),
			lines(propsExpanding(text, Map.of("REFLECTION_RES", "0.5"), key -> key.startsWith("size.buffer."))));
	}

	@Test
	void continuedExpandedValueIsJoinedExpandedAndWarnsOfNothing() {
		PreprocessResult r = propsExpanding("size.buffer.colortex1 = REFLECTION_RES \\\nREFLECTION_RES\n",
			Map.of("REFLECTION_RES", "0.5"), key -> key.startsWith("size.buffer."));
		assertEquals(List.of("size.buffer.colortex1=0.5 0.5"), lines(r), "the continued lines are one value, expanded at their line");
		assertEquals(List.of(), r.warnings(), "no name is left in the value, so nothing is reported");
	}

	@Test
	void aMacroNameTheValueCannotExpandIsWarnedAndKept() {
		PreprocessResult r = propsExpanding("#define FN(x) x\nsize.buffer.colortex1 = FN\n",
			Map.of(), key -> key.startsWith("size.buffer."));
		assertEquals(List.of("size.buffer.colortex1=FN"), lines(r));
		assertEquals(1, r.warnings().size(), r.warnings().toString());
	}

	@Test
	void twoThousandLinesWithThirtyIncludesRunFast() {
		StringBuilder root = new StringBuilder("#version 400 compatibility\n");
		for (int i = 0; i < 30; i++) root.append("#include \"/lib/part").append(i).append(".glsl\"\n");
		for (int i = 0; i < 1970; i++) root.append("vec4 v").append(i).append(" = vec4(MACRO_").append(i % 30).append(");\n");
		Map<String, String> files = new java.util.HashMap<>();
		files.put("root.fsh", root.toString());
		for (int i = 0; i < 30; i++) {
			StringBuilder lib = new StringBuilder();
			for (int k = 0; k < 70; k++) {
				if (k % 10 == 0) lib.append("#define LIB_").append(i).append("_").append(k).append(" ").append(k).append("\n");
				else if (k % 10 == 5) lib.append("#if LIB_").append(i).append("_0 > 3\n#define MACRO_").append(i).append(" lib").append(i).append("_").append(k).append("(1.0)\n#else\n#define MACRO_").append(i).append(" 0.0\n#endif\n");
				else lib.append("float lib").append(i).append("_").append(k).append("(float x) { return x * ").append(k + 1).append(".0; }\n");
			}
			files.put("lib/part" + i + ".glsl", lib.toString());
		}
		IncludeResolver resolver = (from, name) -> name.startsWith("/") && files.containsKey(name.substring(1))
				? Optional.of(new SourceText(name.substring(1), files.get(name.substring(1))))
				: Optional.empty();
		Preprocessor pp = new Preprocessor(Mode.GLSL, resolver, Map.of(), null);
		long t0 = System.nanoTime();
		PreprocessResult r;
		try {
			r = pp.process(new SourceText("root.fsh", files.get("root.fsh")));
		} catch (PreprocessException e) {
			throw new AssertionError(e);
		}
		long ms = (System.nanoTime() - t0) / 1_000_000;
		int outLines = r.text().isEmpty() ? 0 : (int) r.text().lines().count();
		System.out.println("[speed] 2001-line program, 30 includes: " + outLines + " output lines in " + ms + " ms");
		assertTrue(outLines > 2000, "expected the program body in the output");
		assertTrue(r.files().size() == 31);
	}

	// Properties mode honours #define and #undef in active regions (Complementary's "Special Define Handling").

	@Test
	void propertiesDefineSetsAMacroForTheConditionalsThatFollow() {
		String text = """
				#ifdef MC_OS_MAC
				#define COLORED_LIGHTING 0
				#endif
				#if COLORED_LIGHTING == 0
				off=1
				#else
				on=1
				#endif
				""";
		assertEquals(List.of("on=1"), lines(props(text, Map.of("COLORED_LIGHTING", "192"))));
		assertEquals(List.of("off=1"), lines(props(text, Map.of("COLORED_LIGHTING", "192", "MC_OS_MAC", ""))));
	}

	@Test
	void propertiesDefineReplacesAnOptionMacro() {
		PreprocessResult r = props("""
				#define COLORED_LIGHTING 0
				#if COLORED_LIGHTING == 0
				off=1
				#endif
				""", Map.of("COLORED_LIGHTING", "192"));
		assertEquals(List.of("off=1"), lines(r));
	}

	@Test
	void propertiesUndefRemovesAMacroForTheConditionalsThatFollow() {
		PreprocessResult r = props("""
				#undef SHADOW_MODE
				#ifdef SHADOW_MODE
				kept=1
				#else
				gone=1
				#endif
				""", Map.of("SHADOW_MODE", "1"));
		assertEquals(List.of("gone=1"), lines(r));
	}

	@Test
	void propertiesDefineAndUndefInAnInactiveRegionAreIgnored() {
		PreprocessResult r = props("""
				#ifdef ABSENT
				#define X 1
				#undef PRESENT
				#endif
				#ifdef X
				x=1
				#endif
				#ifdef PRESENT
				present=1
				#endif
				""", Map.of("PRESENT", ""));
		assertEquals(List.of("present=1"), lines(r));
	}

	@Test
	void propertiesDefineIsNotExpandedInText() {
		PreprocessResult r = props("""
				#define LEVEL 2
				level=LEVEL
				""", Map.of());
		assertEquals(List.of("level=LEVEL"), lines(r));
		assertEquals(0, r.warnings().size());
	}
}
