// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.pack;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Every corpus pack loads at its defaults, and every program the pack runs (directly or through a fallback) preprocesses
 * without an error. A program that its rules switch off is not preprocessed, so its errors do not count (the rules are
 * evaluated, not excused). Also: every {@code program.*.enabled} rule and every custom uniform or variable compiles, and every
 * properties file (shaders.properties, block, item and entity) preprocesses. Runs with {@code -Pcorpus=<folder with the zips>}.
 */
@Tag("corpus")
class CorpusTest {
	@Test
	void everyRunProgramPreprocessesAtDefaults() throws IOException {
		List<Path> zips = corpusZips();
		List<String> failures = new ArrayList<>();
		int programs = 0;
		int disabled = 0;
		for (Path zip : zips) {
			try (PackSource pack = PackSource.open(zip)) {
				long t0 = System.nanoTime();
				PackModel model = PackModel.load(pack, StandardMacros.engine(), "en_us", new Properties());
				String name = zip.getFileName().toString();
				for (DimensionPrograms d : model.dimensions()) {
					for (ResolvedProgram p : d.programs()) {
						if (p.source() == ResolvedProgram.Source.DISABLED) {
							disabled++;
							if (!p.stages().isEmpty() || !p.errors().isEmpty()) {
								failures.add(name + " " + d.label() + " " + p.name() + ": a disabled program was preprocessed");
							}
						}
						if (!p.runs()) continue;
						programs++;
						if (!p.errors().isEmpty()) {
							failures.add(name + " " + d.label() + " " + p.name() + ": " + p.errors().get(0));
						}
					}
				}
				for (PackModel.ProgramRule r : model.programRules()) {
					r.error().ifPresent(e -> failures.add(name + " program." + r.key() + ".enabled: " + e));
				}
				for (PackModel.CustomValue c : model.customValues()) {
					c.error().ifPresent(e -> failures.add(name + " " + c.typeName() + "." + c.name() + ": " + e));
				}
				for (String p : model.problems()) {
					if (p.contains(": error: ")) failures.add(name + ": " + p);
				}
				System.out.println("[corpus] " + name + ": " + model.dimensions().size() + " dimensions, "
					+ model.programRules().size() + " rules, " + model.customValues().size() + " custom values, "
					+ (System.nanoTime() - t0) / 1_000_000 + " ms");
			}
		}
		System.out.println("[corpus] programs run: " + programs + ", switched off by rules: " + disabled);
		assertTrue(failures.isEmpty(), failures.size() + " corpus failures:\n" + String.join("\n", failures));
	}

	/** The corpus folder's zips, sorted; skipped when the property is not set. */
	static List<Path> corpusZips() throws IOException {
		String dir = System.getProperty("mcms.test.corpus");
		Assumptions.assumeTrue(dir != null, "no corpus folder given");
		Path folder = Path.of(dir);
		Assumptions.assumeTrue(Files.isDirectory(folder), dir + " is not a folder");
		try (Stream<Path> s = Files.list(folder)) {
			List<Path> zips = s.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".zip")).sorted().toList();
			Assumptions.assumeFalse(zips.isEmpty(), "no pack zips in " + dir);
			return zips;
		}
	}
}
