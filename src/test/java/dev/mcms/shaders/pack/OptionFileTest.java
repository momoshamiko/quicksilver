// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The values file writer: only the changed values, sorted, the file's unknown names kept, written atomically, read back by OptionValues. */
class OptionFileTest {
	@TempDir
	Path dir;

	private OptionCatalog catalog() throws IOException {
		Path shaders = dir.resolve("pack/shaders");
		Files.createDirectories(shaders);
		Files.writeString(shaders.resolve("composite.fsh"), String.join("\n",
			"#define LEVEL 2 // [1 3 4]",
			"#define FLAG //tip",
			"// #define OFF_BY_DEFAULT",
			"#ifdef FLAG",
			"#endif",
			"#ifdef OFF_BY_DEFAULT",
			"#endif",
			"void main() {}",
			""));
		try (PackSource pack = PackSource.open(dir.resolve("pack"))) {
			return OptionDiscovery.scan(pack, List.of(""));
		}
	}

	@Test
	void rendersOnlyTheValuesThatDifferFromTheDefaultsSortedByName() throws IOException {
		OptionCatalog catalog = catalog();
		Map<String, String> defaults = OptionValues.defaults(catalog).values();
		Map<String, String> chosen = new LinkedHashMap<>(defaults);
		chosen.put("LEVEL", "3");
		chosen.put("FLAG", "false");
		chosen.put("GONE", "1");
		assertEquals("FLAG=false\nLEVEL=3\n", OptionFile.render(defaults, chosen, Map.of()));
	}

	@Test
	void escapesTheCharactersPropertiesReadSpecially() throws IOException {
		String text = OptionFile.render(Map.of("A", "1"), Map.of("A", "x y=z:#"), Map.of());
		Properties p = new Properties();
		p.load(new StringReader(text));
		assertEquals("x y=z:#", p.getProperty("A"));
	}

	@Test
	void writesAtomicallyAndReadsBackWithTheSameValues() throws IOException {
		OptionCatalog catalog = catalog();
		Map<String, String> defaults = OptionValues.defaults(catalog).values();
		Map<String, String> chosen = new LinkedHashMap<>(defaults);
		chosen.put("LEVEL", "4");
		chosen.put("OFF_BY_DEFAULT", "true");
		Path folder = dir.resolve("shaderpacks");
		Path file = OptionValues.fileFor(folder, "Pack.zip");

		OptionFile.write(file, defaults, chosen, Map.of());

		try (Stream<Path> s = Files.list(folder)) {
			assertEquals(List.of("Pack.zip.txt"), s.map(p -> p.getFileName().toString()).toList(),
				"no temporary file is left next to the values file");
		}
		assertEquals("LEVEL=4\nOFF_BY_DEFAULT=true\n", Files.readString(file));
		assertEquals("rw-r--r--", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(file)),
			"readable by everyone, as the game's own files (a temporary file starts owner-only)");
		OptionValues back = OptionValues.of(catalog, OptionValues.readFile(file));
		assertEquals(OptionValues.defaults(catalog).with(catalog, Map.of("LEVEL", "4", "OFF_BY_DEFAULT", "true")).values(),
			back.values());
		assertEquals(List.of(), back.problems());
	}

	@Test
	void rewritingWithTheDefaultsLeavesNoChangedLines() throws IOException {
		OptionCatalog catalog = catalog();
		Map<String, String> defaults = OptionValues.defaults(catalog).values();
		Path file = dir.resolve("shaderpacks/Pack.zip.txt");
		OptionFile.write(file, defaults, Map.of("LEVEL", "1"), Map.of());
		OptionFile.write(file, defaults, defaults, Map.of());
		assertEquals("", Files.readString(file));
		assertFalse(OptionValues.readFile(file).stringPropertyNames().iterator().hasNext());
		assertEquals(OptionValues.defaults(catalog).values(), OptionValues.of(catalog, OptionValues.readFile(file)).values());
	}

	@Test
	void namesThePackDoesNotDeclareSurviveAnApply() throws IOException {
		// A pack update that drops an option, or another loader's names, must not cost the player the saved values: the file's
		// unknown names come back verbatim after the declared ones, and the next read reports them as unknown again.
		OptionCatalog catalog = catalog();
		Map<String, String> defaults = OptionValues.defaults(catalog).values();
		Path file = dir.resolve("shaderpacks/Pack.zip.txt");
		Files.createDirectories(file.getParent());
		Files.writeString(file, "LEVEL=3\nOLD_OPTION=7\nOther\\ Name=a b\n");
		OptionValues loaded = OptionValues.of(catalog, OptionValues.readFile(file));
		assertEquals(Map.of("OLD_OPTION", "7", "Other Name", "a b"), loaded.unknown());

		Map<String, String> chosen = new LinkedHashMap<>(loaded.values());
		chosen.put("LEVEL", "4");
		OptionFile.write(file, defaults, chosen, loaded.unknown());

		assertEquals("LEVEL=4\nOLD_OPTION=7\nOther\\ Name=a\\ b\n", Files.readString(file));
		OptionValues back = OptionValues.of(catalog, OptionValues.readFile(file));
		assertEquals("4", back.values().get("LEVEL"));
		assertEquals(loaded.unknown(), back.unknown());
	}
}
