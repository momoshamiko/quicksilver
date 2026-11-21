// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.sema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcms.glsl.preprocess.LineRewriter;
import dev.mcms.shaders.pack.PackSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CorpusProgramsTest {

	@Test
	void irisProgramNamesRunAndTheGameDoesNotKnowTheOthers() {
		assertEquals("world0/gbuffers_block.fsh",
				CorpusPrograms.programFile("world0/gbuffers_block.fsh").path());
		assertEquals("world0/dh_water.fsh", CorpusPrograms.programFile("world0/dh_water.fsh").path());
		assertEquals("world0/composite14.fsh", CorpusPrograms.programFile("world0/composite14.fsh").path());
		// the run logs: "unrecognised program names: clrwl_gbuffers, gbuffers_voxels, shadow_voxels"
		assertNull(CorpusPrograms.programFile("world-1/clrwl_gbuffers.fsh"));
		assertNull(CorpusPrograms.programFile("world0/gbuffers_voxels.fsh"));
		assertNull(CorpusPrograms.programFile("world0/shadow_voxels.fsh"));
		assertNull(CorpusPrograms.programFile("world0/shadow.glsl"));
	}

	@Test
	void enabledExpressionsUseTheDefinedOptions() {
		Set<String> defined = Set.of("IS_IRIS", "FXAA");
		assertFalse(bool("TRANSLUCENT_ENTITIES && IS_IRIS", defined));
		assertTrue(bool("IS_IRIS", defined));
		assertTrue(bool("FXAA&&!RETRO_FILTER", defined));
		assertFalse(bool("FXAA&&RETRO_FILTER", defined));
		assertTrue(bool("false || (IS_IRIS && !DOF)", defined));
		assertFalse(bool("false", defined));
	}

	@Test
	void aBadExpressionFailsLoudly() {
		assertThrows(IllegalArgumentException.class, () -> bool("A ? B", Set.of()));
	}

	private static boolean bool(String expr, Set<String> defined) {
		return new CorpusPrograms.BoolExpr(expr, defined).evaluate();
	}

	@Test
	void enablementReadsTheIfBlocksOfShadersProperties(@TempDir Path dir) throws IOException {
		Files.createDirectories(dir.resolve("shaders"));
		Files.writeString(dir.resolve("shaders/shaders.properties"), """
				#ifdef FLAG
				program.world0/composite7.enabled = true
				#else
				program.world0/composite7.enabled = false
				#endif
				""");
		Files.createDirectories(dir.resolve("shaders/lib"));
		Files.writeString(dir.resolve("shaders/lib/settings.glsl"), "#define FLAG\n");
		Files.createDirectories(dir.resolve("shaders/world0"));
		Files.writeString(dir.resolve("shaders/world0/composite7.fsh"), "void main() {}\n");
		try (PackSource source = PackSource.open(dir)) {
			CorpusPrograms programs = CorpusPrograms.of(source, null, CorpusMacros.table());
			assertTrue(programs.enabled(CorpusPrograms.programFile("world0/composite7.fsh")));
		}
	}

	@Test
	void enablementFollowsTheOptionsThatChangeTheIfBlock(@TempDir Path dir) throws IOException {
		Files.createDirectories(dir.resolve("shaders"));
		Files.writeString(dir.resolve("shaders/shaders.properties"), """
				#ifdef FLAG
				program.world0/composite7.enabled = true
				#else
				program.world0/composite7.enabled = false
				#endif
				""");
		Files.createDirectories(dir.resolve("shaders/lib"));
		Files.writeString(dir.resolve("shaders/lib/settings.glsl"), "//#define FLAG\n");
		Files.createDirectories(dir.resolve("shaders/world0"));
		Files.writeString(dir.resolve("shaders/world0/composite7.fsh"), "void main() {}\n");
		LineRewriter on = CorpusOptions.rewriter(Map.of("FLAG", "true"));
		try (PackSource source = PackSource.open(dir)) {
			assertFalse(CorpusPrograms.of(source, null, CorpusMacros.table())
					.enabled(CorpusPrograms.programFile("world0/composite7.fsh")));
			assertTrue(CorpusPrograms.of(source, on, CorpusMacros.table())
					.enabled(CorpusPrograms.programFile("world0/composite7.fsh")));
		}
	}

	@Test
	void anIntegerDefineOfZeroMakesAnEqualityConditionTrue(@TempDir Path dir) throws IOException {
		// Complementary's shaders.properties: "#if COLORED_LIGHTING == 0" switches shadowcomp off; the define is 0
		Files.createDirectories(dir.resolve("shaders"));
		Files.writeString(dir.resolve("shaders/shaders.properties"), """
				#if COLORED_LIGHTING == 0
				program.world0/shadowcomp.enabled = false
				#endif
				""");
		Files.createDirectories(dir.resolve("shaders/lib"));
		Files.writeString(dir.resolve("shaders/lib/settings.glsl"), "#define COLORED_LIGHTING 0\n");
		Files.createDirectories(dir.resolve("shaders/world0"));
		Files.writeString(dir.resolve("shaders/world0/shadowcomp.csh"), "void main() {}\n");
		try (PackSource source = PackSource.open(dir)) {
			assertFalse(CorpusPrograms.of(source, null, CorpusMacros.table())
					.enabled(CorpusPrograms.programFile("world0/shadowcomp.csh")));
		}
	}
}
