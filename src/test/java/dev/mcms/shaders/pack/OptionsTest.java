// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Option discovery, the values file and the rewriter, on small packs in a temporary folder. */
class OptionsTest {
	@TempDir
	Path dir;

	private void file(String rel, String text) throws IOException {
		Path p = dir.resolve("shaders").resolve(rel);
		Files.createDirectories(p.getParent());
		Files.writeString(p, text);
	}

	private OptionCatalog scan() throws IOException {
		try (PackSource pack = PackSource.open(dir)) {
			return OptionDiscovery.scan(pack, List.of(""));
		}
	}

	@Test
	void booleanCountsWhenATestInTheSameProgramReachesIt() throws IOException {
		file("composite.fsh", "#include \"/lib/opt.glsl\"\n#ifdef TESTED\nvoid main() {}\n#endif\n");
		file("lib/opt.glsl", "// #define TESTED //tip\n#define UNTESTED //tip\n");
		OptionCatalog c = scan();
		PackOption tested = c.get("TESTED").orElseThrow();
		assertEquals(OptionKind.BOOLEAN, tested.kind());
		assertEquals("false", tested.defaultValue(), "a commented define is off by default");
		assertEquals("tip", tested.tooltip());
		assertTrue(c.get("UNTESTED").isEmpty(), "no ifdef or defined test: not an option");
	}

	@Test
	void valueNeedsAListAndGainsItsDefault() throws IOException {
		file("composite.fsh", "#define LEVEL 2 // [1 3 4]\n#define PLAIN 2\nvoid main() {}\n");
		OptionCatalog c = scan();
		PackOption level = c.get("LEVEL").orElseThrow();
		assertEquals(OptionKind.VALUE, level.kind());
		assertEquals(List.of("1", "3", "4", "2"), level.values(), "the default is added when the list lacks it");
		assertTrue(c.get("PLAIN").isEmpty());
	}

	@Test
	void constDocumentedOrListedIsOptionAndAHiddenDocumentedConstHasNoList() throws IOException {
		file("composite.fsh", String.join("\n",
			"const int shadowMapResolution = 1024; // [512 1024 2048]",
			"const int shadowDistance = 64;",
			"const float entityShadowDistanceMul = 0.05; //[0.05 0.10]",
			"const float unlisted = 1.0;",
			"const int trailing = 4; float w[3];",
			"void main() {}", ""));
		OptionCatalog c = scan();
		assertEquals(OptionKind.CONST, c.get("shadowMapResolution").orElseThrow().kind());
		assertTrue(c.get("shadowDistance").orElseThrow().hiddenConst());
		assertTrue(c.get("entityShadowDistanceMul").isPresent(), "a const with a value list is an option");
		assertTrue(c.get("unlisted").isEmpty());
		assertTrue(c.get("trailing").isEmpty(), "brackets in code after the semicolon are not a value list");
	}

	@Test
	void declarationsThatDisagreeOnADefaultAreAmbiguous() throws IOException {
		file("a.fsh", "#ifdef MODE\n#endif\n#define MODE //x\n");
		file("b.fsh", "#ifdef MODE\n#endif\n// #define MODE //x\n");
		OptionCatalog c = scan();
		assertTrue(c.get("MODE").orElseThrow().ambiguous());
		assertFalse(c.warnings().isEmpty());
	}

	@Test
	void valuesFileIsCheckedAgainstTheCatalog() throws IOException {
		file("composite.fsh", "#ifdef ON\n#endif\n// #define ON\n#define LEVEL 2 // [1 2 3]\nvoid main() {}\n");
		OptionCatalog c = scan();
		Properties file = new Properties();
		file.setProperty("LEVEL", "3");
		file.setProperty("ON", "maybe");
		file.setProperty("LEVEL_NOT_LISTED", "9");
		OptionValues v = OptionValues.of(c, file);
		assertEquals("3", v.get("LEVEL"));
		assertEquals("false", v.get("ON"), "a boolean must be true or false: the default stays");
		assertEquals("9", v.unknown().get("LEVEL_NOT_LISTED"), "unknown names are kept");
		assertEquals(2, v.problems().size(), v.problems().toString());
	}

	@Test
	void valueOutsideTheListIsNotApplied() throws IOException {
		file("composite.fsh", "#define LEVEL 2 // [1 2 3]\nvoid main() {}\n");
		OptionCatalog c = scan();
		Properties file = new Properties();
		file.setProperty("LEVEL", "9");
		OptionValues v = OptionValues.of(c, file);
		assertEquals("2", v.get("LEVEL"));
		assertTrue(v.problems().get(0).contains("not in the list"), v.problems().get(0));
	}

	@Test
	void rewriterAppliesTheChosenValuesBeforeThePreprocessorReadsThem() throws IOException {
		file("composite.fsh", String.join("\n",
			"// #define ON //tip",
			"#define LEVEL 2 // [1 2 3]",
			"const int shadowMapResolution = 1024; // [512 1024 2048]",
			"#ifdef ON",
			"const int chosenOn = 1;",
			"#endif",
			"#if LEVEL == 3",
			"const int chosenLevel = 1;",
			"#endif",
			"const int finalResolution = shadowMapResolution;",
			""));
		OptionCatalog c = scan();
		Properties file = new Properties();
		file.setProperty("ON", "true");
		file.setProperty("LEVEL", "3");
		file.setProperty("shadowMapResolution", "2048");
		OptionValues v = OptionValues.of(c, file);
		OptionRewriter rewriter = new OptionRewriter(c, v);
		String out = rewrite(rewriter, "composite.fsh", dir.resolve("shaders/composite.fsh"));
		assertTrue(out.contains("const int chosenOn = 1;"), out);
		assertTrue(out.contains("const int chosenLevel = 1;"), out);
		assertTrue(out.contains("const int shadowMapResolution = 2048;"), out);
	}

	@Test
	void rewriterKeepsACommentGluedToTheValue() throws IOException {
		// Packs write "#define X 0.5// [list]" with no space before the comment (Bliss settings.glsl).
		file("composite.fsh", String.join("\n",
			"#define GLUED 0.5// [0.25 0.5 0.75]",
			"float glued = GLUED;",
			""));
		OptionCatalog c = scan();
		Properties file = new Properties();
		file.setProperty("GLUED", "0.75");
		OptionRewriter rewriter = new OptionRewriter(c, OptionValues.of(c, file));
		String out = rewrite(rewriter, "composite.fsh", dir.resolve("shaders/composite.fsh"));
		assertTrue(out.contains("float glued = 0.75;"), out);
		assertFalse(out.contains("[0.25"), "the value list stays a comment: " + out);
	}

	/** Runs the preprocessor (GLSL mode, standard macros) over one file with the rewriter and returns the active text. */
	static String rewrite(OptionRewriter rewriter, String name, Path onDisk) throws IOException {
		String text = Files.readString(onDisk);
		try {
			return new dev.mcms.glsl.preprocess.Preprocessor(dev.mcms.glsl.preprocess.Mode.GLSL, (from, inc) -> java.util.Optional.empty(),
				StandardMacros.engine().predefined(), rewriter)
				.process(new dev.mcms.glsl.preprocess.SourceText(name, text)).text();
		} catch (dev.mcms.glsl.preprocess.PreprocessException e) {
			throw new AssertionError(e.getMessage(), e);
		}
	}
}
