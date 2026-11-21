// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.mcms.glsl.Translator;
import dev.mcms.glsl.msl.ProgramRequest;
import dev.mcms.glsl.msl.ProgramRequest.Semantic;
import dev.mcms.glsl.msl.ProgramRequest.VertexAttribute;
import dev.mcms.glsl.msl.ProgramTranslation;
import dev.mcms.shaders.pack.DimensionPrograms;
import dev.mcms.shaders.pack.PackModel;
import dev.mcms.shaders.pack.PackSource;
import dev.mcms.shaders.pack.ResolvedProgram;
import dev.mcms.shaders.pack.StandardMacros;
import dev.mcms.shaders.runtime.gbuffers.GbufferPlans;
import dev.mcms.shaders.runtime.gbuffers.PhaseLayout;
import dev.mcms.shaders.runtime.shadow.ShadowPassColours;
import dev.mcms.shaders.runtime.shadow.ShadowTargets;
import dev.mcms.shaders.runtime.uniforms.CustomUniforms;
import dev.mcms.shaders.runtime.Targets;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Every shadow program the corpus packs run in every dimension translates through {@link ProgramRequests#shadow} (the
 * family {@code SHADOW}, outputs at the shadow layout's attachments with the {@code shadowcolor} formats), and every
 * dimension's shadow colour buffers build from its layout (E5 review item 5: a fragment stage without a DRAWBUFFERS
 * comment takes the buffers the set holds, and no build throws). The packs are test inputs read at run time and never
 * committed: {@code ./gradlew test -Pcorpus=<folder with the pack zips>}.
 */
@Tag("corpus")
class ShadowProgramsCorpusTest {

	/** The vertex layout of the game's entities and blocks (the vanilla format; the shadow programs read the same inputs). */
	private static final ProgramRequest.VertexLayout GEOMETRY = ProgramRequest.VertexLayout.of(
		new VertexAttribute(Semantic.POSITION, "float3", 0, 0),
		new VertexAttribute(Semantic.COLOR, "uchar4Normalized", 0, 12),
		new VertexAttribute(Semantic.TEXCOORD0, "float2", 0, 16),
		new VertexAttribute(Semantic.OVERLAY, "short2", 0, 24),
		new VertexAttribute(Semantic.LIGHTMAP, "short2", 0, 28),
		new VertexAttribute(Semantic.NORMAL, "char4Normalized", 0, 32));

	/** A texture handle source for the layouts: the colour buffers need handles, not pixels. */
	private static final Targets.Textures HANDLES = new Targets.Textures() {
		private long next = 1;

		@Override
		public long create(int format, int width, int height, int mips) {
			return next++;
		}

		@Override
		public void release(long handle) {
		}
	};

	@Test
	void everyCorpusShadowProgramTranslatesAsAShadowRequestAndEveryDimensionBuildsItsColours() throws IOException {
		String corpus = System.getProperty("mcms.test.corpus");
		assumeTrue(corpus != null, "no corpus folder given");
		Path folder = Path.of(corpus);
		List<String> failures = new ArrayList<>();
		int translated = 0;
		try (Stream<Path> zips = Files.list(folder)) {
			for (Path zip : zips.filter(p -> p.getFileName().toString().endsWith(".zip")).sorted().toList()) {
				PackModel model;
				try (PackSource source = PackSource.open(zip)) {
					model = PackModel.load(source, StandardMacros.engine(), "en_us", options(folder, zip));
				}
				CustomUniforms custom = CustomUniforms.of(model);
				List<DimensionPrograms> dimensions = new ArrayList<>(model.dimensions());
				dimensions.add(model.rootFolder());
				for (DimensionPrograms dimension : dimensions) {
					String dimensionWhere = zip.getFileName() + " " + dimension.label();
					ShadowTargets targets = ShadowTargets.of(dimension);
					// The layout and the runnable programs are the engine's own (GbufferPlans), not a copy of its rules.
					PhaseLayout layout = GbufferPlans.shadowLayoutOf(GbufferPlans.runnablePrograms(dimension, targets));
					if (!targets.fits(layout.targets())) {
						failures.add(dimensionWhere + ": the shadow layout names a buffer the set does not hold");
					} else if (targets.create(HANDLES)) {
						try {
							new ShadowPassColours(targets, layout.targets());
						} catch (IllegalArgumentException e) {
							failures.add(dimensionWhere + ": the shadow colours do not build: " + e.getMessage());
						}
					} else {
						failures.add(dimensionWhere + ": the shadow targets could not be created");
					}
					for (ResolvedProgram program : dimension.programs()) {
						if (!isShadowDraw(program)) {
							continue;
						}
						translated++;
						String where = zip.getFileName() + " " + dimension.label() + " " + program.name();
						ProgramRequest request = ProgramRequests.shadow(ShadowTargets.clampWrites(program, targets.colorCount()),
							model, custom, dimension.union().shadowFormats(), GEOMETRY, layout::attachmentOf, false, targets);
						if (request == null) {
							failures.add(where + ": no shadow request (no vertex or fragment stage)");
							continue;
						}
						ProgramTranslation translation = Translator.translate(request);
						if (!translation.ok()) {
							failures.add(where + ": " + translation.diagnostics());
						}
					}
					targets.release();
				}
			}
		}
		assertTrue(translated > 0, "no shadow program ran in the corpus folder " + folder);
		assertEquals(List.of(), failures, "shadow programs or colour buffers that failed");
	}

	/**
	 * A shadow program the shadow pass draws with: {@code shadow} and its documented variants. The {@code shadowcomp}
	 * programs are composite-style passes (their fragment parts run after the shadow pass) and are not in this family.
	 */
	private static boolean isShadowDraw(ResolvedProgram program) {
		return program.name().startsWith("shadow") && !program.name().startsWith("shadowcomp") && program.runs();
	}

	/** The pack's bench options file, as the equivalence test reads it ({@code bench/pack-options/<zip>.txt}). */
	private static Properties options(Path folder, Path zip) throws IOException {
		Properties options = new Properties();
		Path optionFile = folder.getParent().getParent().resolve("pack-options").resolve(zip.getFileName() + ".txt");
		if (Files.isRegularFile(optionFile)) {
			options.load(Files.newBufferedReader(optionFile));
		}
		return options;
	}
}
