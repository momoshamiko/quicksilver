// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.shadow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcms.glsl.Translator;
import dev.mcms.glsl.msl.ProgramRequest;
import dev.mcms.glsl.msl.ProgramTranslation;
import dev.mcms.shaders.pack.PackModel;
import dev.mcms.shaders.pack.PackSource;
import dev.mcms.shaders.pack.ProgramCatalog;
import dev.mcms.shaders.pack.ProgramDirectives;
import dev.mcms.shaders.pack.ResolvedProgram;
import dev.mcms.shaders.pack.StageFile;
import dev.mcms.shaders.pack.StandardMacros;
import dev.mcms.shaders.runtime.ProgramRequests;
import dev.mcms.shaders.runtime.gbuffers.PhaseLayout;
import dev.mcms.shaders.runtime.Targets;
import dev.mcms.shaders.runtime.uniforms.CustomUniforms;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The shadow colour buffer rules (E5 review items 5 and 26) on real pack text: the default write targets of a shadow
 * fragment stage without a DRAWBUFFERS comment, the colour buffer count the names and writes of a pack give, the layout
 * a set builds from them, and the hardware-filtering declaration rewrite with two programs that read one map differently.
 */
class ShadowColourRulesTest {

	private static final String VERTEX_130 = """
		#version 130
		varying vec2 texcoord;
		void main() {
			texcoord = gl_MultiTexCoord0.xy;
			gl_Position = ftransform();
		}
		""";

	/** A fragment-only program the way the pack reader builds it (its directives read from its stage text). */
	private static ResolvedProgram program(String name, String stageText) {
		List<StageFile> stages = List.of(new StageFile('f', name + ".fsh", stageText));
		return new ResolvedProgram(name, new ProgramCatalog.Program(name, ProgramCatalog.Phase.SHADOW, null, false, false,
			false), ResolvedProgram.Source.DIRECT, Optional.empty(), Optional.empty(), stages, List.of(),
			Optional.of(ProgramDirectives.of(stages)));
	}

	private static PackModel load(Path dir, Map<String, String> files) throws IOException {
		Path shaders = Files.createDirectories(dir.resolve("shaders"));
		for (Map.Entry<String, String> file : files.entrySet()) {
			Files.writeString(shaders.resolve(file.getKey()), file.getValue());
		}
		try (PackSource source = PackSource.open(dir)) {
			return PackModel.load(source, StandardMacros.engine(), "en_us", new Properties());
		}
	}

	private static ResolvedProgram named(PackModel model, String name) {
		return model.rootFolder().programs().stream().filter(p -> p.name().equals(name)).findFirst()
			.orElseThrow(() -> new AssertionError("no program " + name));
	}

	private static String fragmentText(ResolvedProgram program) {
		return program.stages().stream().filter(s -> s.stage() == 'f').findFirst()
			.orElseThrow(() -> new AssertionError(program.name() + " has no fragment stage")).text();
	}

	/** Hands out distinct non-zero handles; the layout tests need textures, not pixels. */
	private static Targets.Textures textures() {
		return new Targets.Textures() {
			private long next = 100;

			@Override
			public long create(int format, int width, int height, int mips) {
				return next++;
			}

			@Override
			public void release(long handle) {
			}
		};
	}

	@Test
	void aNarrowedWriteListIsClampedByPositionAndTheFallbackStillFitsTheBuffersASetHolds() {
		ResolvedProgram p = program("shadow", "#version 120\nvoid main() { gl_FragData[0] = vec4(1.0); }\n");
		assertEquals(List.of(0), p.directives().orElseThrow().writes(), "the program writes shadowcolor0 only");
		assertEquals(List.of(0), ShadowTargets.clampWrites(p, 2).directives().orElseThrow().writes());
		ResolvedProgram third = program("shadow", "#version 120\nvoid main() { gl_FragData[2] = vec4(1.0); }\n");
		assertEquals(List.of(0, 1), ShadowTargets.clampWrites(third, 2).directives().orElseThrow().writes(),
			"the list is positional, so the write to index 2 is dropped and 0 and 1 stay listed below it");
		assertEquals(List.of(0, 1, 2), ShadowTargets.clampWrites(third, 3).directives().orElseThrow().writes());
		ResolvedProgram loop = program("shadow", "#version 120\nvoid main() { for (int i = 0; i < 8; i++) gl_FragData[i] = vec4(1.0); }\n");
		assertEquals(List.of(0, 1, 2, 3, 4, 5, 6, 7), loop.directives().orElseThrow().writes(),
			"a non-constant index keeps the documented default (the first eight buffers)");
		assertEquals(List.of(0, 1), ShadowTargets.clampWrites(loop, 2).directives().orElseThrow().writes());
		assertEquals(List.of(0, 1, 2), ShadowTargets.clampWrites(loop, 3).directives().orElseThrow().writes());
	}

	@Test
	void aProgramThatNamesItsTargetsIsNotClamped() {
		ResolvedProgram p = program("shadow", "/* DRAWBUFFERS:03 */\n#version 120\nvoid main() { gl_FragData[0] = vec4(1.0); }\n");
		assertSame(p, ShadowTargets.clampWrites(p, 2), "explicit writes stand as the pack wrote them");
	}

	@Test
	void theColourCountGrowsFromNamedBuffersAndIgnoresImageNames() {
		assertEquals(4, ShadowTargets.colorsNamedBy("uniform sampler2D shadowcolor3;"));
		assertEquals(2, ShadowTargets.colorsNamedBy("texture2D(shadowColor1, uv)"));
		assertEquals(0, ShadowTargets.colorsNamedBy("uniform sampler2D shadowcolor;"));
		assertEquals(0, ShadowTargets.colorsNamedBy("layout (rgba8) uniform image2D shadowcolorimg3;"));
	}

	@Test
	void aPackWithDefaultShadowWritesAndAFourBufferReadBuildsItsShadowColours(@TempDir Path dir) throws Exception {
		PackModel model = load(dir, Map.of(
			"shadow.vsh", "#version 120\nvoid main() { gl_Position = ftransform(); }\n",
			"shadow.fsh", "#version 120\nvoid main() { gl_FragData[0] = vec4(1.0); }\n",
			"composite.vsh", VERTEX_130,
			"composite.fsh", "#version 130\nuniform sampler2D shadowcolor3;\nvarying vec2 texcoord;\n"
				+ "void main() { gl_FragData[0] = texture2D(shadowcolor3, texcoord); }\n"));
		ShadowTargets targets = ShadowTargets.of(model.rootFolder());
		assertEquals(4, targets.colorCount(), "a composite reads shadowcolor3, so four buffers exist");
		TreeSet<Integer> written = new TreeSet<>();
		for (ResolvedProgram program : model.rootFolder().programs()) {
			if (program.name().startsWith("shadow") && program.runs()) {
				written.addAll(ShadowTargets.clampWrites(program, targets.colorCount()).directives().orElseThrow().writes());
			}
		}
		PhaseLayout layout = PhaseLayout.ofShadow(written);
		assertTrue(targets.fits(layout.targets()), "the default writes fit the buffers the set holds");
		assertTrue(targets.create(textures()));
		ShadowPassColours colours = new ShadowPassColours(targets, layout.targets());
		assertEquals(1, colours.colours(new long[8]).length,
			"the default program writes shadowcolor0 only, so the pass attaches one colour buffer (the set still holds four)");
	}

	@Test
	void hardwareFilteringKeepsTheDeclarationOfAProgramThatReadsTheValue(@TempDir Path dir) throws Exception {
		PackModel model = load(dir, Map.of(
			"composite.vsh", VERTEX_130,
			"composite.fsh", "#version 130\nconst bool shadowHardwareFiltering = true;\nuniform sampler2D shadowtex0;\n"
				+ "varying vec2 texcoord;\nvoid main() { gl_FragData[0] = vec4(texture2D(shadowtex0, texcoord).r); }\n",
			"composite1.vsh", VERTEX_130,
			"composite1.fsh", "#version 130\nconst bool shadowHardwareFiltering = true;\nuniform sampler2D shadowtex0;\n"
				+ "varying vec2 texcoord;\nvoid main() { gl_FragData[0] = vec4(texture(shadowtex0, vec3(texcoord, 0.5))); }\n"));
		ShadowTargets targets = ShadowTargets.of(model.rootFolder());
		assertTrue(targets.hardwareDepth(0), "the pack declares hardware filtering on");
		CustomUniforms custom = CustomUniforms.of(model);
		for (String name : List.of("composite", "composite1")) {
			ResolvedProgram program = named(model, name);
			ProgramRequest request = ProgramRequests.fullScreen(program, model, custom,
				model.rootFolder().union().colorFormats(), model.rootFolder().union().mipmaps(), targets, Set.of());
			assertNotNull(request, name + " has a request");
			ProgramTranslation translation = Translator.translate(request);
			assertTrue(translation.ok(), name + " translates after the declaration rewrite: " + translation.diagnostics());
		}
		String reader = fragmentText(named(model, "composite"));
		String compare = fragmentText(named(model, "composite1"));
		assertTrue(targets.rewriteDeclarations(reader).contains("uniform sampler2D shadowtex0;"),
			"the program that reads the value keeps sampler2D");
		assertTrue(targets.rewriteDeclarations(compare).contains("uniform sampler2DShadow shadowtex0;"),
			"the program that only compares becomes sampler2DShadow");
	}
}
