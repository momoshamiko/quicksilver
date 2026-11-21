// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.pack;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Every corpus pack gives each vanilla dimension a shader folder (the root or a folder of its own). The base folder serves a
 * dimension that has no folder of its own (OptiFine, shaders.properties dimension section; Iris, dimension.properties page),
 * so no vanilla dimension can have none. Runs with {@code -Pcorpus=<folder with the zips>}.
 */
@Tag("corpus")
class CorpusFoldersTest {
	@Test
	void everyVanillaDimensionGetsAFolder() throws IOException {
		List<String> failures = new ArrayList<>();
		for (Path zip : CorpusTest.corpusZips()) {
			String name = zip.getFileName().toString();
			try (PackSource pack = PackSource.open(zip)) {
				PackModel m = PackModel.load(pack, StandardMacros.engine(), "en_us", new Properties());
				for (Dimension d : Dimension.values()) {
					DimensionPrograms row = m.dimensions().stream()
						.filter(r -> r.dimension().equals(Optional.of(d))).findFirst().orElseThrow();
					if (row.folder().isEmpty()) failures.add(name + " " + d + ": no folder serves it (" + row.reason() + ")");
				}
			}
		}
		assertTrue(failures.isEmpty(), failures.size() + " dimensions without a folder:\n" + String.join("\n", failures));
	}
}
