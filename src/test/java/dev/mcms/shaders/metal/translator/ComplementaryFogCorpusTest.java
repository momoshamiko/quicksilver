// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.metal.translator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.mcms.glsl.Translator;
import dev.mcms.glsl.msl.ProgramTranslation;
import dev.mcms.glsl.msl.ProgramTranslation.Reflection.UniformUse;
import dev.mcms.shaders.metal.translator.CorpusTranslatorTest.Program;
import dev.mcms.shaders.pack.PackModel;
import dev.mcms.shaders.pack.StandardMacros;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Complementary r5.9.3's deferred1 reads gl_Fog (plan.md E4): the translator serves it from the frame block, so the
 * program no longer lists gl_Fog among its unknown uniforms (the engine logs those as read as zero).
 */
@Tag("corpus")
final class ComplementaryFogCorpusTest {
	private static final String PACK = "ComplementaryReimagined_r5.9.3.zip";

	@Test
	void deferred1ServesGlFogFromTheFrameBlock() throws IOException {
		Path zip = corpusFile();
		PackModel model = CorpusTranslatorTest.loadModel(zip, StandardMacros.engine(), new Properties());
		var programs = CorpusTranslatorTest.programsOf(model);
		Program program = programs.stream().filter(p -> p.name().endsWith("deferred1")).findFirst()
			.orElseThrow(() -> new AssertionError("no deferred1 program in " + PACK + ": "
				+ programs.stream().map(Program::name).toList()));
		ProgramTranslation t = Translator.translate(CorpusTranslatorTest.requestFor(program, model));
		UniformUse fog = t.reflection().uniforms().stream().filter(u -> u.name().equals("gl_Fog")).findFirst()
			.orElseThrow(() -> new AssertionError("deferred1 no longer reads gl_Fog; the test has nothing to prove"));
		assertEquals("QsFrame", fog.servedBy(), "gl_Fog must come from the frame block");
		assertTrue(t.reflection().uniforms().stream().noneMatch(u -> u.name().equals("gl_Fog")
			&& u.servedBy().equals(UniformUse.UNKNOWN)), t.reflection().uniforms().toString());
	}

	private static Path corpusFile() {
		String dir = System.getProperty("mcms.test.corpus");
		assumeTrue(dir != null, "no corpus folder given");
		Path zip = Path.of(dir).resolve(PACK);
		assumeTrue(Files.isRegularFile(zip), "no " + PACK + " in the corpus folder");
		return zip;
	}
}
