// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.metal.translator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.mcms.glsl.Translator;
import dev.mcms.glsl.msl.ProgramTranslation;
import dev.mcms.shaders.metal.HeadlessGpu;
import dev.mcms.shaders.metal.translator.CorpusTranslatorTest.Program;
import dev.mcms.shaders.pack.PackModel;
import dev.mcms.shaders.pack.ShaderProperties;
import dev.mcms.shaders.pack.StandardMacros;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Complementary at the two profiles that enable colored lighting, VERY_HIGH and COMPLEMENTARY (2026-11-15). The engine
 * defines no MC_OS_* macro, so the pack's shadowcomp is switched on and its colored-lighting code is in the programs; the
 * Apple error banner that the pack shows only under MC_OS_MAC must not be in the Overworld's final program. Every program
 * of the profile, in the three dimensions, must translate and compile with Metal, and the BLOCK_EMISSION_ATTRIBUTE sizes
 * of colortex1 and colortex7 must resolve to the half-size reflection buffers.
 *
 * <p>Reads the pack and its bench option file at run time: {@code ./gradlew test -Pcorpus=<folder with the pack zips>}.
 */
@Tag("corpus")
class ComplementaryMacrosCorpusTest {

	private static final String ZIP = "ComplementaryReimagined_r5.9.3.zip";

	/**
	 * The Apple banner's text as the pack's own error file spells it ({@code lib/textRendering/error_apple_act.glsl},
	 * "supported on Apple devices."): one token per glyph. It appears in a preprocessed final only when APPLE_ACT_ERROR is
	 * defined, which needs MC_OS_MAC.
	 */
	private static final String APPLE_BANNER = "_A, _p, _p, _l, _e, _space, _d, _e, _v, _i, _c, _e, _s";

	@Test
	void veryHighProfileRunsTheOverworldShadowcompAndShowsNoAppleBanner() throws IOException {
		checkOverworld("profile-veryhigh");
	}

	@Test
	void complementaryProfileRunsTheOverworldShadowcompAndShowsNoAppleBanner() throws IOException {
		checkOverworld("profile-complementary");
	}

	@Test
	@Tag("gpu")
	void veryHighProfileEveryProgramTranslatesAndCompilesInEveryDimension() throws IOException {
		checkEveryProgram("profile-veryhigh");
	}

	@Test
	@Tag("gpu")
	void complementaryProfileEveryProgramTranslatesAndCompilesInEveryDimension() throws IOException {
		checkEveryProgram("profile-complementary");
	}

	/** The pack at one profile's options; skipped when the corpus or the option file is missing. */
	private static PackModel load(String profile) throws IOException {
		String corpus = System.getProperty("mcms.test.corpus");
		assumeTrue(corpus != null, "no corpus folder given");
		Path folder = Path.of(corpus);
		Path zip = folder.resolve(ZIP);
		assumeTrue(Files.isRegularFile(zip), zip + " is missing");
		Path optionFile = folder.getParent().getParent().resolve("pack-options").resolve(profile).resolve(ZIP + ".txt");
		assumeTrue(Files.isRegularFile(optionFile), optionFile + " is missing");
		Properties options = new Properties();
		options.load(Files.newBufferedReader(optionFile));
		PackModel model = CorpusTranslatorTest.loadModel(zip, StandardMacros.engine(), options);
		assertSizes(model, profile);
		return model;
	}

	/** REFLECTION_RES is 0.5 at both profiles, and the block emission sizes take it through the option's #define. */
	private static void assertSizes(PackModel model, String profile) {
		for (String buffer : List.of("colortex1", "colortex7")) {
			assertEquals(new ShaderProperties.BufferSize(0.5, 0.5, true),
				model.properties().bufferSizes().get(buffer), profile + ": size.buffer." + buffer);
		}
	}

	private static void checkOverworld(String profile) throws IOException {
		PackModel model = load(profile);
		List<Program> programs = CorpusTranslatorTest.programsOf(model);

		Program shadowcomp = overworld(programs, "shadowcomp");
		assertTrue(shadowcomp != null, profile + ": the Overworld's shadowcomp is not a running program (its enabled rule is off)");
		ProgramTranslation translation = Translator.translate(CorpusTranslatorTest.requestFor(shadowcomp, model));
		assertTrue(translation.ok(), profile + ": shadowcomp does not translate: " + translation.diagnostics());

		Program finalProgram = overworld(programs, "final");
		assertTrue(finalProgram != null, profile + ": the Overworld has no final program");
		for (var stage : finalProgram.stages()) {
			assertFalse(stage.text().contains(APPLE_BANNER), profile + ": the final program's " + stage.stage()
				+ " stage shows the Apple colored-lighting banner, so MC_OS_MAC was defined");
		}
	}

	/**
	 * Every program the profile runs, in each dimension, except the dh_ programs (they run only with Distant Horizons on,
	 * and CorpusTranslatorTest's defaults+dh configuration checks them). A program that does not translate or does not
	 * compile with Metal is listed with its verdict. The Overworld, Nether and End folders must all have running programs.
	 */
	private static void checkEveryProgram(String profile) throws IOException {
		PackModel model = load(profile);
		List<Program> programs = CorpusTranslatorTest.programsOf(model);
		for (String folder : List.of("world0/", "world-1/", "world1/")) {
			assertTrue(programs.stream().anyMatch(p -> p.name().startsWith(folder)), profile + ": no program in " + folder);
		}
		List<String> failures = new ArrayList<>();
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			for (Program p : programs) {
				if (p.base().startsWith("dh_")) {
					continue;
				}
				if (p.error() != null) {
					failures.add(p.name() + ": " + p.error());
					continue;
				}
				ProgramTranslation t = Translator.translate(CorpusTranslatorTest.requestFor(p, model));
				if (!t.ok()) {
					failures.add(p.name() + ": does not translate: " + t.diagnostics());
					continue;
				}
				HeadlessGpu.CompileResult r = gpu.compile(t.msl());
				if (!r.ok()) {
					failures.add(p.name() + ": Metal does not compile it: " + r.diagnostics());
				}
			}
		}
		assertTrue(failures.isEmpty(), profile + ": " + failures.size() + " of " + programs.size()
			+ " programs fail:\n" + String.join("\n", failures));
	}

	/** The Overworld's program with this base name, or null when it does not run. */
	private static Program overworld(List<Program> programs, String base) {
		return programs.stream().filter(p -> p.name().startsWith("world0/") && p.base().equals(base)).findFirst().orElse(null);
	}
}
