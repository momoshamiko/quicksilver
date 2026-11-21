// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.mcms.shaders.expr.ExprType;

/** Resolution, rules, profiles, options and directives on a small pack written to a temporary folder. */
class SyntheticPackTest {
	@TempDir
	Path dir;

	private void file(String rel, String text) throws IOException {
		Path p = dir.resolve("shaders").resolve(rel);
		Files.createDirectories(p.getParent());
		Files.writeString(p, text);
	}

	private PackModel load(Properties overrides) throws IOException {
		file("shaders.properties", String.join("\n",
			"program.composite1.enabled=FANCY",
			"program.world0/composite2.enabled=FANCY",
			"program.world-1/composite.enabled=false",
			"program.composite1.enabled=FANCY && IS_IRIS",
			"program.composite3.enabled=false",
			"profile.LOW=FANCY:false !program.world0/gbuffers_hand !program.composite4",
			"uniform.float.level=frameTime * 2.0",
			"variable.float.half=level / 2.0",
			"uniform.float.bad=entityId + 1",
			""));
		file("world0/composite.fsh", String.join("\n",
			"#version 330 compatibility",
			"// #define FANCY //fancy look",
			"/* DRAWBUFFERS:03 */",
			"#ifdef FANCY",
			"const int colortex3Format = RGBA16F;",
			"#endif",
			"void main() {}",
			""));
		file("world0/composite1.fsh", "/* DRAWBUFFERS:0 */\nvoid main() {}\n");
		file("world0/composite2.fsh", "#error broken on purpose\nvoid main() {}\n");
		file("world0/gbuffers_hand.fsh", "/* DRAWBUFFERS:0 */\nvoid main() {}\n");
		file("world0/clrwl_gbuffers.fsh", "void main() {}\n");
		file("world-1/composite.fsh", "/* DRAWBUFFERS:0 */\nvoid main() {}\n");
		file("composite3.fsh", "/* DRAWBUFFERS:0 */\nvoid main() {}\n");
		file("composite4.fsh", "/* DRAWBUFFERS:0 */\nvoid main() {}\n");
		file("world0/composite3.fsh", "/* DRAWBUFFERS:0 */\nvoid main() {}\n");
		file("world0/composite4.fsh", "/* DRAWBUFFERS:0 */\nvoid main() {}\n");
		try (PackSource pack = PackSource.open(dir)) {
			return PackModel.load(pack, StandardMacros.engine(), "en_us", overrides);
		}
	}

	private static ResolvedProgram program(DimensionPrograms d, String name) {
		return d.programs().stream().filter(p -> p.name().equals(name)).findFirst()
			.orElseThrow(() -> new AssertionError(name + " is not in " + d.label()));
	}

	private static DimensionPrograms dimension(PackModel m, String label) {
		return m.dimensions().stream().filter(d -> d.label().equals(label)).findFirst()
			.orElseThrow(() -> new AssertionError(label + " row missing"));
	}

	@Test
	void fallbackServesAProgramWithoutFiles() throws IOException {
		Properties on = new Properties();
		on.setProperty("FANCY", "true");
		ResolvedProgram water = program(dimension(load(on), "Overworld"), "gbuffers_hand_water");
		assertEquals(ResolvedProgram.Source.FALLBACK, water.source());
		assertEquals("gbuffers_hand", water.servedBy().orElseThrow());
	}

	@Test
	void ruleSwitchesOffOnlyTheDimensionItNames() throws IOException {
		PackModel m = load(new Properties());
		ResolvedProgram overworld = program(dimension(m, "Overworld"), "composite");
		ResolvedProgram nether = program(dimension(m, "Nether"), "composite");
		assertEquals(ResolvedProgram.Source.DIRECT, overworld.source());
		assertEquals(ResolvedProgram.Source.DISABLED, nether.source());
		assertEquals("rule program.world-1/composite.enabled", nether.disabledBy().orElseThrow());
		assertTrue(nether.stages().isEmpty(), "a disabled program is not preprocessed");
	}

	@Test
	void disabledProgramWithAnErrorIsNotPreprocessed() throws IOException {
		PackModel m = load(new Properties());
		ResolvedProgram c2 = program(dimension(m, "Overworld"), "composite2");
		assertEquals(ResolvedProgram.Source.DISABLED, c2.source());
		assertTrue(c2.errors().isEmpty(), "the #error sits in a program the rule switches off");
	}

	@Test
	void enabledProgramWithAnErrorIsReported() throws IOException {
		Properties on = new Properties();
		on.setProperty("FANCY", "true");
		PackModel m = load(on);
		ResolvedProgram c2 = program(dimension(m, "Overworld"), "composite2");
		assertEquals(ResolvedProgram.Source.DIRECT, c2.source());
		assertFalse(c2.errors().isEmpty());
		assertTrue(c2.errors().get(0).contains("broken on purpose"), c2.errors().get(0));
		assertTrue(c2.directives().isEmpty(), "a program with errors has no directives");
	}

	@Test
	void profileDisablesAProgramAndItsFallbackDoesNotServe() throws IOException {
		PackModel m = load(new Properties());
		assertEquals("LOW", m.currentProfile().orElseThrow().name());
		DimensionPrograms d = dimension(m, "Overworld");
		ResolvedProgram hand = program(d, "gbuffers_hand");
		assertEquals(ResolvedProgram.Source.DISABLED, hand.source());
		assertEquals("profile LOW", hand.disabledBy().orElseThrow());
		assertTrue(d.programs().stream().noneMatch(p -> p.name().equals("gbuffers_hand_water") && p.runs()),
			"a fallback that is switched off does not serve");
	}

	@Test
	void rulesAndProfilesWithoutAFolderSwitchOffOnlyTheBaseFoldersPrograms() throws IOException {
		// Iris 1.11.7, measured with the mcms-progenable test pack: program.composite3.enabled=false and a current profile's
		// !program.composite4 leave world0's programs running; the End, which the base folder serves here, loses both.
		PackModel m = load(new Properties());
		DimensionPrograms overworld = dimension(m, "Overworld");
		DimensionPrograms end = dimension(m, "End");
		assertEquals(ResolvedProgram.Source.DIRECT, program(overworld, "composite3").source());
		assertEquals(ResolvedProgram.Source.DIRECT, program(overworld, "composite4").source());
		assertEquals(ResolvedProgram.Source.DIRECT, program(overworld, "composite1").source(),
			"program.composite1.enabled is false (FANCY off) but names no folder");
		assertEquals("rule program.composite3.enabled", program(end, "composite3").disabledBy().orElseThrow());
		assertEquals("profile LOW", program(end, "composite4").disabledBy().orElseThrow());
	}

	@Test
	void optionValueChangesTheTextTheDirectivesRead() throws IOException {
		PackModel off = load(new Properties());
		ProgramDirectives offD = program(dimension(off, "Overworld"), "composite").directives().orElseThrow();
		assertTrue(offD.colorFormats().isEmpty(), "the #ifdef FANCY branch is inactive");
		Properties on = new Properties();
		on.setProperty("FANCY", "true");
		PackModel m = load(on);
		ProgramDirectives onD = program(dimension(m, "Overworld"), "composite").directives().orElseThrow();
		assertEquals("RGBA16F", onD.colorFormats().get(3));
		assertEquals(List.of(0, 3), onD.writes());
	}

	@Test
	void ruleUsesOptionsAndStandardMacros() throws IOException {
		PackModel off = load(new Properties());
		assertTrue(off.programRules().stream().anyMatch(r -> r.program().equals("composite1") && r.value().orElse(true) == false),
			"FANCY off makes the rule false");
		Properties on = new Properties();
		on.setProperty("FANCY", "true");
		PackModel m = load(on);
		assertTrue(m.programRules().stream().filter(r -> r.program().equals("composite1")).anyMatch(r -> r.value().orElse(false)),
			"FANCY on and IS_IRIS make the rule true");
	}

	@Test
	void customValuesCompileInFileOrderAndDynamicNamesFail() throws IOException {
		PackModel m = load(new Properties());
		PackModel.CustomValue level = m.customValues().stream().filter(c -> c.name().equals("level")).findFirst().orElseThrow();
		assertEquals(Optional.of(ExprType.FLOAT), level.type());
		assertTrue(level.compiled().isPresent());
		PackModel.CustomValue half = m.customValues().stream().filter(c -> c.name().equals("half")).findFirst().orElseThrow();
		assertTrue(half.error().isEmpty(), "a variable may use an earlier uniform: " + half.error());
		PackModel.CustomValue bad = m.customValues().stream().filter(c -> c.name().equals("bad")).findFirst().orElseThrow();
		assertTrue(bad.error().isPresent(), "entityId is dynamic and not usable as a parameter");
	}

	@Test
	void unrecognisedFilesAreListedByName() throws IOException {
		PackModel m = load(new Properties());
		assertEquals(List.of("clrwl_gbuffers.fsh"), dimension(m, "Overworld").unrecognised());
		assertTrue(m.unrecognisedByFolder().get("world0").contains("clrwl_gbuffers.fsh"));
	}

	@Test
	void theBaseFolderIsResolvedButNoWorldDimensionUsesIt() throws IOException {
		file("gbuffers_basic.fsh", "/* DRAWBUFFERS:0 */\nvoid main() {}\n");
		PackModel m = load(new Properties());
		assertEquals(Optional.of(""), m.rootFolder().folder());
		assertEquals(ResolvedProgram.Source.DIRECT, program(m.rootFolder(), "gbuffers_basic").source());
		assertTrue(dimension(m, "Overworld").programs().stream().noneMatch(p -> p.name().equals("gbuffers_basic")),
			"worldN folders exist, so the base folder is not used");
	}
}
