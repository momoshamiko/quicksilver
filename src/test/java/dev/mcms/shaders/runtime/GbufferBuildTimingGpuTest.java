// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import dev.mcms.shaders.metal.HeadlessGpu;
import dev.mcms.shaders.pack.PackModel;
import dev.mcms.shaders.pack.PackSource;
import dev.mcms.shaders.pack.StandardMacros;
import dev.mcms.shaders.runtime.gbuffers.GbufferPlans;
import dev.mcms.shaders.runtime.gbuffers.VertexLayoutRegistry;
import dev.mcms.shaders.runtime.terrain.TerrainLayout;
import dev.mcms.shaders.runtime.terrain.TerrainVertexType;
import dev.mcms.shaders.runtime.uniforms.CustomUniforms;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Wall time of the gbuffers plan build for Complementary (r5.9.3, the bench's options) on the Overworld, with the game
 * pipelines and Sodium's extended terrain as the engine lists them. Prints one line per run ({@code [gbuffers-timing]});
 * run 0 is the cold build, the later runs repeat it. Needs {@code -Pcorpus=<shaderpacks> -PgpuTests}.
 */
@Tag("gpu")
@Tag("corpus")
class GbufferBuildTimingGpuTest {

	private static final int RUNS = 4;

	@Test
	void complementaryOverworldGbuffersBuildWallTime() throws IOException {
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
		VertexLayoutRegistry.register(GbufferRoutingGpuTest.EXTENDED, TerrainLayout.layout(0), GbufferBuildSupport.TERRAIN_FACTS);
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			CustomUniforms custom = CustomUniforms.of(model);
			List<RenderPipeline> pipelines = Engine.gamePipelines();
			var dimension = GbufferBuildSupport.overworld(model);
			GbufferPlans.Stats first = null;
			for (int run = 0; run < RUNS; run++) {
				long t0 = System.nanoTime();
				GbufferPlans plans = GbufferPlans.build(gpu.context(), model, dimension, custom,
					dimension.union().colorFormats(), pipelines, Engine.COPIES_NOT_COMPILED);
				long wall = System.nanoTime() - t0;
				GbufferPlans.Stats stats = plans.stats();
				plans.close();
				System.out.println(String.format(Locale.ROOT, "[gbuffers-timing] complementary overworld run %d: wall %.1f ms",
					run, wall / 1_000_000.0));
				// The timing means something only if each run builds the real states, and the same ones every time.
				assertTrue(stats != null && stats.pipelines() > 0 && stats.psos() > 0, "run " + run + " built no pipeline states");
				if (first == null) {
					first = stats;
				} else {
					assertEquals(first.pipelines(), stats.pipelines(), "run " + run + " built a different number of states");
					assertEquals(first.psos(), stats.psos(), "run " + run + " made a different number of Metal pipelines");
					assertEquals(first.mslSources(), stats.mslSources(), "run " + run + " translated a different number of sources");
				}
			}
		} finally {
			VertexLayoutRegistry.unregister(GbufferRoutingGpuTest.EXTENDED);
		}
	}
}
