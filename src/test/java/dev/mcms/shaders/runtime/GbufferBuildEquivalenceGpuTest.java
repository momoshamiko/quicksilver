// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import dev.mcms.shaders.metal.HeadlessGpu;
import dev.mcms.shaders.pack.DimensionPrograms;
import dev.mcms.shaders.pack.PackModel;
import dev.mcms.shaders.pack.PackSource;
import dev.mcms.shaders.pack.StandardMacros;
import dev.mcms.shaders.runtime.gbuffers.GbufferPlans;
import dev.mcms.shaders.runtime.gbuffers.VertexLayoutRegistry;
import dev.mcms.shaders.runtime.terrain.TerrainLayout;
import dev.mcms.shaders.runtime.uniforms.CustomUniforms;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The parallel pre-build ({@link GbufferPlans#build}) resolves every routing answer exactly as the sequential reference
 * ({@link GbufferPlans#buildSequential}, the states compiled one at a time inside the plan pass). The dumps are compared
 * line by line, so a changed program, slot, depth binding or state presence fails with its line. Also prints the build's
 * summary of both paths ({@code [gbuffers-equiv]}).
 */
@Tag("gpu")
class GbufferBuildEquivalenceGpuTest {

	@Test
	void mcmsClassesPrebuildMatchesTheSequentialBuild() throws IOException {
		Path dir = findTestPack("mcms-classes");
		PackModel model;
		try (PackSource source = PackSource.open(dir)) {
			model = PackModel.load(source, StandardMacros.engine(), "en_us", new Properties());
		}
		compare("mcms-classes", model, model.rootFolder());
	}

	@Test
	@Tag("corpus")
	void complementaryOverworldPrebuildMatchesTheSequentialBuild() throws IOException {
		String corpus = System.getProperty("mcms.test.corpus");
		assumeTrue(corpus != null, "no corpus folder given");
		Path zip = Path.of(corpus, "ComplementaryReimagined_r5.9.3.zip");
		assumeTrue(Files.isRegularFile(zip), zip + " is missing");
		Properties options = new Properties();
		Path optionFile = Path.of(corpus).getParent().getParent().resolve("pack-options")
			.resolve("ComplementaryReimagined_r5.9.3.zip.txt");
		if (Files.isRegularFile(optionFile)) {
			options.load(Files.newBufferedReader(optionFile));
		}
		PackModel model;
		try (PackSource source = PackSource.open(zip)) {
			model = PackModel.load(source, StandardMacros.engine(), "en_us", options);
		}
		compare("complementary-overworld", model, GbufferBuildSupport.overworld(model));
	}

	@Test
	void aBuildCancelledBeforeItsFirstRoundMakesNothingAndAnUncancelledOneStillBuilds() throws IOException {
		Path dir = findTestPack("mcms-classes");
		PackModel model;
		try (PackSource source = PackSource.open(dir)) {
			model = PackModel.load(source, StandardMacros.engine(), "en_us", new Properties());
		}
		VertexLayoutRegistry.register(GbufferRoutingGpuTest.EXTENDED, TerrainLayout.layout(0), GbufferBuildSupport.TERRAIN_FACTS);
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			CustomUniforms custom = CustomUniforms.of(model);
			List<RenderPipeline> pipelines = Engine.gamePipelines();
			DimensionPrograms dimension = model.rootFolder();
			var formats = dimension.union().colorFormats();
			assertThrows(GbufferPlans.BuildCancelled.class, () -> GbufferPlans.build(gpu.context(), model, dimension, custom,
				formats, pipelines, Engine.COPIES_NOT_COMPILED, new GbufferPlans.Options(2, Thread.MIN_PRIORITY, () -> true)));
			try (GbufferPlans plans = GbufferPlans.build(gpu.context(), model, dimension, custom, formats, pipelines,
				Engine.COPIES_NOT_COMPILED, new GbufferPlans.Options(2, Thread.MIN_PRIORITY, () -> false))) {
				assertTrue(plans.stats().pipelines() > 0, "the uncancelled build made its pipeline states");
			}
		} finally {
			VertexLayoutRegistry.unregister(GbufferRoutingGpuTest.EXTENDED);
		}
	}

	private static void compare(String label, PackModel model, DimensionPrograms dimension) {
		VertexLayoutRegistry.register(GbufferRoutingGpuTest.EXTENDED, TerrainLayout.layout(0), GbufferBuildSupport.TERRAIN_FACTS);
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			CustomUniforms custom = CustomUniforms.of(model);
			List<RenderPipeline> pipelines = Engine.gamePipelines();
			GbufferPlans parallel = GbufferPlans.build(gpu.context(), model, dimension, custom,
				dimension.union().colorFormats(), pipelines, Engine.COPIES_NOT_COMPILED);
			GbufferPlans sequential = GbufferPlans.buildSequential(gpu.context(), model, dimension, custom,
				dimension.union().colorFormats(), pipelines, Engine.COPIES_NOT_COMPILED);
			try {
				List<String> a = GbufferRoutingDump.of(parallel, pipelines);
				List<String> b = GbufferRoutingDump.of(sequential, pipelines);
				assertEquals(b.size(), a.size(), label + ": the dumps differ in length");
				for (int i = 0; i < a.size(); i++) {
					assertEquals(b.get(i), a.get(i), label + ": line " + i + " differs");
				}
				assertTrue(parallel.stats().threads() > 1,
					label + ": the parallel build ran on " + parallel.stats().threads() + " worker(s), not a pool");
				GbufferPlans.Stats s = parallel.stats();
				System.out.println("[gbuffers-equiv] " + label + ": " + a.size() + " routing lines equal; parallel "
					+ s.pipelines() + " states, " + s.mslSources() + " MSL sources, " + s.psos() + " Metal pipelines, wall "
					+ s.wallNanos() / 1_000_000 + " ms on " + s.threads() + " workers; sequential wall "
					+ sequential.stats().wallNanos() / 1_000_000 + " ms");
			} finally {
				parallel.close();
				sequential.close();
			}
		} finally {
			VertexLayoutRegistry.unregister(GbufferRoutingGpuTest.EXTENDED);
		}
	}

	private static Path findTestPack(String name) {
		Path dir = Path.of(System.getProperty("user.dir"));
		for (int i = 0; i < 6; i++) {
			Path candidate = dir.resolve(Path.of("bench", "test-packs", name));
			if (Files.isDirectory(candidate)) {
				return candidate;
			}
			dir = dir.getParent();
		}
		throw new IllegalStateException("bench/test-packs/" + name + " not found above " + System.getProperty("user.dir"));
	}
}
