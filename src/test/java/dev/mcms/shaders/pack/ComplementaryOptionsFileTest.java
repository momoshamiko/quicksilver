// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The reference options file for Complementary Reimagined (bench/pack-options) loads against the pack, and every key the
 * file holds shows the file's value on the options screen, except a value the pack's list does not hold: that one shows the
 * default and is reported, as OptionValues documents (the file has MOTION_BLURRING_STRENGTH=0, and r5.9.3 lists 0.15 to
 * 2.00). Runs with {@code -Pcorpus=<folder with the zips>}.
 */
@Tag("corpus")
class ComplementaryOptionsFileTest {
	private static final String ZIP = "ComplementaryReimagined_r5.9.3.zip";

	@Test
	void everyKeyOfTheFileShowsItsValueOnTheScreen() throws IOException {
		String dir = System.getProperty("mcms.test.corpus");
		assumeTrue(dir != null, "no corpus folder given");
		Path folder = Path.of(dir);
		assumeTrue(Files.isDirectory(folder), dir + " is not a folder");
		Path zip = folder.resolve(ZIP);
		assumeTrue(Files.isRegularFile(zip), "no " + ZIP + " in " + dir);
		Path optionsFile = folder.toAbsolutePath().getParent().getParent().resolve("pack-options").resolve(ZIP + ".txt");
		assumeTrue(Files.isRegularFile(optionsFile), "no options file " + optionsFile);

		Properties file = OptionValues.readFile(optionsFile);
		List<String> mismatches = new ArrayList<>();
		List<String> outsideTheList = new ArrayList<>();
		try (PackSource pack = PackSource.open(zip)) {
			PackModel model = PackModel.load(pack, StandardMacros.engine(false), "en_us", file);
			OptionValues shown = model.values();
			for (String key : new java.util.TreeSet<>(file.stringPropertyNames())) {
				String want = file.getProperty(key).strip();
				PackOption option = model.options().get(key).orElse(null);
				if (option == null) {
					mismatches.add(key + ": the pack declares no such option");
				} else if (option.ambiguous()) {
					mismatches.add(key + ": ambiguous in the pack, so the screen cannot show it");
				} else if (OptionValues.check(option, want) != null) {
					outsideTheList.add(key + "=" + want);
					if (!option.defaultValue().equals(shown.get(key))) {
						mismatches.add(key + ": outside the list, so the default " + option.defaultValue() + " shows, not "
							+ shown.get(key));
					}
					if (model.problems().stream().noneMatch(p -> p.contains("\"" + key + "\""))) {
						mismatches.add(key + ": outside the list, but the load reports no problem for it");
					}
				} else if (!want.equals(shown.get(key))) {
					mismatches.add(key + ": file " + want + ", screen " + shown.get(key));
				}
			}
			System.out.println("[corpus] options file: " + file.size() + " keys, " + mismatches.size() + " mismatches, "
				+ "outside the list: " + outsideTheList);
		}
		assertTrue(mismatches.isEmpty(), mismatches.size() + " keys differ:\n" + String.join("\n", mismatches));
		assertEquals(List.of("MOTION_BLURRING_STRENGTH=0"), outsideTheList, "the file's values outside the pack's lists");
	}
}
