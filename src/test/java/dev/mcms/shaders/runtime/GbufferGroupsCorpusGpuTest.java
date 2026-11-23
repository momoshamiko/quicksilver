// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import dev.mcms.shaders.metal.HeadlessGpu;
import dev.mcms.shaders.pack.DimensionPrograms;
import dev.mcms.shaders.pack.PackModel;
import dev.mcms.shaders.pack.PackSource;
import dev.mcms.shaders.pack.StandardMacros;
import dev.mcms.shaders.runtime.gbuffers.GbufferPhase;
import dev.mcms.shaders.runtime.gbuffers.GbufferPlans;
import dev.mcms.shaders.runtime.gbuffers.VertexLayoutRegistry;
import dev.mcms.shaders.runtime.terrain.TerrainLayout;
import dev.mcms.shaders.runtime.uniforms.CustomUniforms;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The phase split on the corpus packs (2026-11-15): Complementary at its VERY_HIGH and COMPLEMENTARY profiles, and Bliss at
 * its default options, build every dimension's plans with no {@link GbufferPlans.LayoutException}. Complementary's clouds
 * write no target in their active code (they only discard), so with the no-comment write narrowing they no longer split its
 * translucent phase: that phase is one group of at most eight targets. Bliss's water and hand_water share a group apart from
 * block and entities. Each dimension's partition is printed for the three gbuffers phases ({@code [phase-split]}).
 */
@Tag("gpu")
@Tag("corpus")
class GbufferGroupsCorpusGpuTest {

	private static final String COMPLEMENTARY = "ComplementaryReimagined_r5.9.3.zip";
	private static final String BLISS = "Bliss_v2.1.2_(Chocapic13_Shaders_edit).zip";

	@Test
	void complementaryAtVeryHighKeepsTheTranslucentPhaseInOneGroup() throws IOException {
		assertPartitions(COMPLEMENTARY, "profile-veryhigh", true);
	}

	@Test
	void complementaryAtTheComplementaryProfileKeepsTheTranslucentPhaseInOneGroup() throws IOException {
		assertPartitions(COMPLEMENTARY, "profile-complementary", true);
	}

	@Test
	void blissSplitsWaterAndHandWaterApartFromBlockAndEntities() throws IOException {
		assertPartitions(BLISS, null, false);
	}

	@Test
	void nobleAndKappaBuildFullPipelinesWithoutMetalValidationAbortOrShadowMismatch() throws IOException {
		String corpus = System.getProperty("mcms.test.corpus");
		assumeTrue(corpus != null, "no corpus folder given");
		Path noble = Path.of(corpus, "noble.zip");
		Path kappa = Path.of(corpus, "kappa-shader.zip");
		assumeTrue(Files.isRegularFile(noble) && Files.isRegularFile(kappa), "step3 packs not present in " + corpus);
		List<RenderPipeline> pipelines = Engine.gamePipelines();
		VertexLayoutRegistry.register(GbufferRoutingGpuTest.EXTENDED, TerrainLayout.layout(0), GbufferBuildSupport.TERRAIN_FACTS);
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			for (Path pack : List.of(noble, kappa)) {
				PackModel model;
				try (PackSource source = PackSource.open(pack)) {
					model = PackModel.load(source, StandardMacros.engine(), "en_us", new Properties());
				}
				DimensionPrograms overworld = GbufferBuildSupport.overworld(model);
				CustomUniforms custom = CustomUniforms.of(model);
				DimensionSet set = DimensionSet.load(gpu.context(), pack.getFileName().toString(), model, overworld,
					custom, 70, overworld.union().colorFormats(), pipelines, Engine.COPIES_NOT_COMPILED);
				try {
					System.out.println("[step3-gpu] " + pack.getFileName() + " Overworld: passes=" + set.passes().size()
						+ " absent=" + set.absent() + " notes=" + set.notes());
					assertEquals(0, set.absent(), pack.getFileName() + " Overworld had absent passes: " + set.notes());
				} finally {
					set.passes().forEach(p -> dev.mcms.metal.MetalBridge.release(p.pipeline()));
					if (set.plans() != null) {
						set.plans().close();
					}
				}
			}
		} finally {
			VertexLayoutRegistry.unregister(GbufferRoutingGpuTest.EXTENDED);
		}
	}

	/**
	 * Builds every dimension's plans of the pack and checks the translucent split of its Overworld: Complementary's
	 * translucent phase in one group, or Bliss's water and hand_water together and apart from block and entities.
	 */
	private static void assertPartitions(String zip, @Nullable String profile, boolean complementary) throws IOException {
		String corpus = System.getProperty("mcms.test.corpus");
		assumeTrue(corpus != null, "no corpus folder given");
		Path pack = Path.of(corpus, zip);
		assumeTrue(Files.isRegularFile(pack), pack + " is missing");
		Properties options = new Properties();
		if (profile != null) {
			Path optionFile = Path.of(corpus).getParent().getParent().resolve("pack-options").resolve(profile).resolve(zip + ".txt");
			assumeTrue(Files.isRegularFile(optionFile), optionFile + " is missing");
			options.load(Files.newBufferedReader(optionFile));
		}
		PackModel model;
		try (PackSource source = PackSource.open(pack)) {
			model = PackModel.load(source, StandardMacros.engine(), "en_us", options);
		}
		List<RenderPipeline> pipelines = Engine.gamePipelines();
		VertexLayoutRegistry.register(GbufferRoutingGpuTest.EXTENDED, TerrainLayout.layout(0), GbufferBuildSupport.TERRAIN_FACTS);
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			CustomUniforms custom = CustomUniforms.of(model);
			for (DimensionPrograms dimension : model.dimensions()) {
				GbufferPlans plans = GbufferPlans.build(gpu.context(), model, dimension, custom,
					dimension.union().colorFormats(), pipelines, Engine.COPIES_NOT_COMPILED);
				try {
					for (GbufferPhase phase : GbufferPhase.values()) {
						System.out.println("[phase-split] " + zip + " " + (profile == null ? "default" : profile) + " "
							+ dimension.label() + " " + phase + ": " + describe(plans, phase));
					}
					if (dimension == GbufferBuildSupport.overworld(model)) {
						assertOverworldSplit(plans, complementary);
					}
				} finally {
					plans.close();
				}
			}
		} finally {
			VertexLayoutRegistry.unregister(GbufferRoutingGpuTest.EXTENDED);
		}
	}

	private static void assertOverworldSplit(GbufferPlans plans, boolean complementary) {
		Map<String, Integer> byName = plans.programGroups(GbufferPhase.TRANSLUCENT);
		if (complementary) {
			assertEquals(1, plans.groups(GbufferPhase.TRANSLUCENT), "the translucent phase fits one group");
			assertEquals(0, requireGroup(byName, "gbuffers_clouds"), "the clouds are in that group");
			int targets = plans.layout(GbufferPhase.TRANSLUCENT, 0).length;
			assertTrue(targets <= GbufferPlans.MAX_ATTACHMENTS, "the group's " + targets + " targets fit Metal's attachments");
		} else {
			int water = requireGroup(byName, "gbuffers_water");
			assertEquals(water, requireGroup(byName, "gbuffers_hand_water"), "water and hand_water share a group");
			assertNotEquals(water, requireGroup(byName, "gbuffers_block_translucent"), "block is apart from water");
			assertEquals(requireGroup(byName, "gbuffers_block_translucent"),
				requireGroup(byName, "gbuffers_entities_translucent"), "block and entities share a group");
		}
	}

	private static int requireGroup(Map<String, Integer> byName, String program) {
		Integer group = byName.get(program);
		assertNotNull(group, program + " is in no group of the translucent phase");
		return group;
	}

	/** Each group's targets and the programs it holds, for the printed report. */
	private static String describe(GbufferPlans plans, GbufferPhase phase) {
		Map<String, Integer> byName = plans.programGroups(phase);
		StringBuilder text = new StringBuilder();
		for (int group = 0; group < plans.groups(phase); group++) {
			if (group > 0) {
				text.append(" | ");
			}
			text.append("group ").append(group).append(" targets ").append(toList(plans.layout(phase, group)))
				.append(" programs ");
			int current = group;
			List<String> names = byName.entrySet().stream().filter(entry -> entry.getValue() == current)
				.map(Map.Entry::getKey).sorted().toList();
			text.append(names);
		}
		return text.toString();
	}

	private static List<Integer> toList(int[] values) {
		return java.util.Arrays.stream(values).boxed().sorted().toList();
	}
}
