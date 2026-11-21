// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * An id-map value takes the object-like macros defined at its line (as a {@code size.buffer} value does), and a macro name
 * left in a value warns once per name. The file is synthetic: it does not copy any pack's text.
 */
class IdMapMacrosTest {

	private static final String BLOCK = String.join("\n",
		"#define tags_stone minecraft:stone minecraft:cobblestone",
		"#define tags_soil dirt",
		"block.1 = tags_stone",
		"block.2 = minecraft:grass_block tags_soil",
		"block.3 = tags_soil",
		"block.4 = tags_sand",
		"block.5 = tags_sand",
		"#define tags_sand minecraft:sand",
		"");

	private static PackModel load(Path dir, String blockText) throws IOException {
		Path shaders = Files.createDirectories(dir.resolve("shaders"));
		Files.writeString(shaders.resolve("block.properties"), blockText);
		try (PackSource source = PackSource.open(dir)) {
			return PackModel.load(source, StandardMacros.engine(), "en_us", new Properties());
		}
	}

	private static List<String> names(IdMapFile file, int id) {
		return file.ids().get(id).stream().map(IdEntry::name).toList();
	}

	@Test
	void aValueTakesTheMacrosDefinedAboveItsLine(@TempDir Path dir) throws IOException {
		IdMapFile blocks = load(dir, BLOCK).blocks();
		assertEquals(List.of("stone", "cobblestone"), names(blocks, 1));
		assertEquals(List.of("grass_block", "dirt"), names(blocks, 2));
		assertEquals(List.of("dirt"), names(blocks, 3));
	}

	@Test
	void aMacroNameLeftInAValueWarnsOnceAndStaysAName(@TempDir Path dir) throws IOException {
		PackModel model = load(dir, BLOCK);
		assertEquals(List.of("tags_sand"), names(model.blocks(), 4), "tags_sand is defined below line 4, so it is not expanded");
		assertEquals(List.of("tags_sand"), names(model.blocks(), 5));
		List<String> warnings = model.warnings().stream().filter(w -> w.contains("tags_sand")).toList();
		assertEquals(1, warnings.size(), model.warnings().toString());
	}

	/** A continued value (BSL's pattern: the value starts on the next line) with a macro on a continued line. */
	private static final String CONTINUED = String.join("\n",
		"#define tags_stone minecraft:stone",
		"block.10000= \\",
		"minecraft:grass minecraft:short_grass \\",
		"tags_stone minecraft:fern",
		"block.10001 = minecraft:dirt \\",
		"  minecraft:coarse_dirt",
		"");

	@Test
	void aContinuedValueJoinsBeforeItIsExpandedAndWarnsOfNothing(@TempDir Path dir) throws IOException {
		PackModel model = load(dir, CONTINUED);
		assertEquals(List.of("grass", "short_grass", "stone", "fern"), names(model.blocks(), 10000),
			"the continued lines are one value, and its macro expands on its continued line");
		assertEquals(List.of("dirt", "coarse_dirt"), names(model.blocks(), 10001));
		List<String> warnings = model.warnings().stream().filter(w -> w.contains("block.properties")).toList();
		assertEquals(List.of(), warnings, "a file with continued values and no unexpandable macro warns of nothing");
	}

	/** Object-like macros whose bodies name other macros defined above the value (Photon's tag pattern). */
	private static final String NESTED = String.join("\n",
		"#define tags_flowers minecraft:poppy tags_small",
		"#define tags_small minecraft:dandelion",
		"block.20001 = tags_flowers grass",
		"");

	@Test
	void aMacroBodyNamingAnotherMacroExpandsAtTheValuesLine(@TempDir Path dir) throws IOException {
		PackModel model = load(dir, NESTED);
		assertEquals(List.of("poppy", "dandelion", "grass"), names(model.blocks(), 20001));
		assertEquals(List.of(), model.warnings().stream().filter(w -> w.contains("block.properties")).toList());
	}

	@Test
	void aFunctionLikeNameWithoutArgumentsStaysInTheValueWithAWarning(@TempDir Path dir) throws IOException {
		PackModel model = load(dir, String.join("\n", "#define tags_fn(x) minecraft:x", "block.30001 = tags_fn", ""));
		assertEquals(List.of("tags_fn"), names(model.blocks(), 30001), "a function-like macro with no arguments does not expand");
		assertTrue(model.warnings().stream().anyMatch(w -> w.contains("tags_fn")), model.warnings().toString());
	}

	/**
	 * Bliss's pattern: #ifdef and #else sit between a value's continued lines, and only the active branch's value loads. The
	 * value goes on after the #endif with a macro line (E6-fix-F): the kept line is expanded like the value's own line.
	 */
	@Test
	void aConditionalInsideAContinuedValueLoadsItsActiveBranch(@TempDir Path dir) throws IOException {
		PackModel model = load(dir, String.join("\n", "#define tags_b minecraft:stone", "#ifdef FLAG",
			"block.5=minecraft:dirt \\", "#else", "block.5=minecraft:sand \\", "#endif", "tags_b \\", "minecraft:gravel", ""));
		assertEquals(List.of("sand", "stone", "gravel"), names(model.blocks(), 5),
			"the macro line after the #endif is expanded as part of the value");
		assertTrue(model.problems().stream().noneMatch(p -> p.contains("conditional")), model.problems().toString());
	}
}
