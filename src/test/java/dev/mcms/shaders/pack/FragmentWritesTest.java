// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A program with no DRAWBUFFERS or RENDERTARGETS comment writes the targets its active fragment code writes (plan.md
 * E6-drawbuffers, 2026-11-15). The fragment text is the preprocessed stage, so the pack-level test runs the real
 * preprocessor; the others give the stage text directly, which holds no inactive branch.
 */
class FragmentWritesTest {

	private static ProgramDirectives fragment(String text) {
		return ProgramDirectives.of(List.of(new StageFile('f', "p.fsh", text)));
	}

	private static void assertNarrowed(ProgramDirectives d, List<Integer> writes) {
		assertEquals(writes, d.writes());
		assertEquals("default-narrowed", d.writeSource());
	}

	@Test
	void aStageThatOnlyDiscardsWritesNoTarget() {
		assertNarrowed(fragment("void main() {\n\tif (gl_FragCoord.x < 0.5) discard;\n}\n"), List.of());
	}

	@Test
	void fragDataAtZeroWritesTheFirstTarget() {
		assertNarrowed(fragment("void main() { gl_FragData[0] = vec4(1.0); }\n"), List.of(0));
	}

	@Test
	void fragDataAtIndexTwoListsTheTargetsBelowIt() {
		// A write list is read by position (the translator maps gl_FragData[i] to list entry i), so index 2 keeps 0 and 1.
		assertNarrowed(fragment("void main() { gl_FragData[2] = vec4(1.0); }\n"), List.of(0, 1, 2));
	}

	@Test
	void fragColorWritesTheFirstTarget() {
		assertNarrowed(fragment("void main() { gl_FragColor = vec4(1.0); }\n"), List.of(0));
	}

	@Test
	void layoutLocationsWriteTheirLocations() {
		assertNarrowed(fragment("layout(location = 1) out vec4 a;\nlayout(location = 3) out vec4 b;\n"
			+ "void main() { a = vec4(1.0); b = vec4(1.0); }\n"), List.of(0, 1, 2, 3));
	}

	@Test
	void anOutWithoutALocationTakesTheFirstFreeLocation() {
		assertNarrowed(fragment("layout(location = 0) out vec4 a;\nout vec4 b;\n"
			+ "void main() { a = vec4(1.0); b = vec4(1.0); }\n"), List.of(0, 1));
	}

	@Test
	void anOutNamedOutColorWritesItsIndex() {
		assertNarrowed(fragment("out vec4 outColor2;\nvoid main() { outColor2 = vec4(1.0); }\n"), List.of(0, 1, 2));
	}

	@Test
	void aFunctionParameterOutIsNotAnOutput() {
		assertNarrowed(fragment("void fill(out vec4 c) { c = vec4(1.0); }\nvoid main() { vec4 c; fill(c); }\n"), List.of());
	}

	@Test
	void commentsDoNotCount() {
		assertNarrowed(fragment("/* gl_FragData[5] = vec4(0.0); */\n// gl_FragData[6] = vec4(0.0);\n"
			+ "void main() { gl_FragData[0] = vec4(1.0); }\n"), List.of(0));
	}

	@Test
	void aLoopIndexKeepsTheFirstEight() {
		ProgramDirectives d = fragment("void main() { for (int i = 0; i < 3; i++) gl_FragData[i] = vec4(i); }\n");
		assertEquals(List.of(0, 1, 2, 3, 4, 5, 6, 7), d.writes(), "a non-constant index may reach any buffer");
		assertEquals("default", d.writeSource());
	}

	@Test
	void aConstantVariableIndexIsNotALiteralAndKeepsTheFirstEight() {
		ProgramDirectives d = fragment("const int ONE = 1;\nvoid main() { gl_FragData[ONE] = vec4(1.0); }\n");
		assertEquals(List.of(0, 1, 2, 3, 4, 5, 6, 7), d.writes());
		assertEquals("default", d.writeSource());
	}

	@Test
	void anIndexPastTheDrawBuffersKeepsTheFirstEight() {
		ProgramDirectives d = fragment("void main() { gl_FragData[8] = vec4(1.0); }\n");
		assertEquals(List.of(0, 1, 2, 3, 4, 5, 6, 7), d.writes());
		assertEquals("default", d.writeSource());
	}

	@Test
	void aLocationThatIsNotAPlainLiteralKeepsTheFirstEight() {
		ProgramDirectives d = fragment("layout(location = 1 + 1) out vec4 a;\nvoid main() { a = vec4(1.0); }\n");
		assertEquals(List.of(0, 1, 2, 3, 4, 5, 6, 7), d.writes(), "1 + 1 must not be read as location 1");
		assertEquals("default", d.writeSource());
	}

	@Test
	void aDirectiveKeepsItsOwnList() {
		ProgramDirectives d = fragment("/* DRAWBUFFERS:27 */\nvoid main() { gl_FragData[0] = vec4(1.0); }\n");
		assertEquals(List.of(2, 7), d.writes());
		assertEquals("DRAWBUFFERS", d.writeSource());
	}

	@Test
	void theInactiveBranchOfAPackIsNotReadAndTheMacroIndexIs(@TempDir Path dir) throws IOException {
		Path shaders = Files.createDirectories(dir.resolve("shaders"));
		Files.writeString(shaders.resolve("composite.vsh"), "#version 130\nvoid main() { gl_Position = vec4(0.0); }\n");
		Files.writeString(shaders.resolve("composite.fsh"), String.join("\n",
			"#version 130",
			"#define LAYER 2",
			"void dead() {",
			"#ifdef NEVER_SET",
			"\tgl_FragData[3] = vec4(1.0);",
			"#endif",
			"}",
			"void main() {",
			"\tgl_FragData[LAYER] = vec4(1.0);",
			"}",
			""));
		PackModel model;
		try (PackSource source = PackSource.open(dir)) {
			model = PackModel.load(source, StandardMacros.engine(), "en_us", new Properties());
		}
		ProgramDirectives d = model.rootFolder().programs().stream()
			.filter(p -> p.name().equals("composite")).findFirst().orElseThrow().directives().orElseThrow();
		assertNarrowed(d, List.of(0, 1, 2));
	}
}
