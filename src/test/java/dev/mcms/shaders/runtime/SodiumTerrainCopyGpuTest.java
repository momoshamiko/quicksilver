// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Sodium's terrain pipelines are never copied for an attachment group (2026-11-15). Their shaders come from Sodium's own
 * loader, which the game's pipeline compile cannot reach: in the game, each copy failed with "Couldn't find source for VERTEX
 * shader (sodium:blocks/block_layer_opaque)" (the mcms-targets runs of the E6 evidence). The plans of mcms-targets (six
 * targets) ask the copier for copies of the game's own pipelines, and for none of Sodium's.
 */
@Tag("gpu")
class SodiumTerrainCopyGpuTest {

	@Test
	void theTargetsPacksPlansCopyGamePipelinesButNeverSodiumsTerrain() throws IOException {
		PackModel model;
		try (PackSource source = PackSource.open(findTestPack("mcms-targets"))) {
			model = PackModel.load(source, StandardMacros.engine(), "en_us", new Properties());
		}
		DimensionPrograms dimension = model.rootFolder();
		List<String> copies = new ArrayList<>();
		GbufferPlans.GameCopier recording = copy -> {
			synchronized (copies) {
				copies.add(copy.getLocation().toString());
			}
			return null;
		};
		VertexLayoutRegistry.register(GbufferRoutingGpuTest.EXTENDED, TerrainLayout.layout(0), GbufferBuildSupport.TERRAIN_FACTS);
		try (HeadlessGpu gpu = new HeadlessGpu();
				GbufferPlans plans = GbufferPlans.build(gpu.context(), model, dimension, CustomUniforms.of(model),
					dimension.union().colorFormats(), Engine.gamePipelines(), recording)) {
			assertFalse(copies.isEmpty(), "the plans copy game pipelines for the groups of several targets (the premise)");
			for (String path : List.of("solid_terrain", "cutout_terrain", "translucent_terrain")) {
				assertNotNull(plans.planOf("sodium:pipeline/" + path), "the plans hold Sodium's " + path + " (the premise)");
			}
			List<String> sodium = copies.stream().filter(location -> location.startsWith("sodium:")).toList();
			assertTrue(sodium.isEmpty(), "no copy of Sodium's terrain pipelines: " + sodium);
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
