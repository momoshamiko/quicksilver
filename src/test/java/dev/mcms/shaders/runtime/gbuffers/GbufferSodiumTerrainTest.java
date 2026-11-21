// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.gbuffers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import dev.mcms.shaders.runtime.terrain.TerrainVertexType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;
import org.jspecify.annotations.Nullable;

/**
 * Sodium's terrain pipelines are in no game list (plan.md E4, "Sodium terrain"): Sodium builds them itself, one per
 * terrain pass, named after the pass's game pipeline path under the {@code sodium} namespace — {@code ShaderChunkRenderer
 * .createShader} on both allowed jars (javap 2026-11-14; engine-sodium-terrain.md section 3). The plans must carry them
 * keyed by their full identifier, next to the game's own pipelines of the same paths.
 */
class GbufferSodiumTerrainTest {

	/** The game's three terrain pipelines (paths from ChunkSectionLayer.pipeline(false), the non-multidraw ids). */
	private static List<RenderPipeline> gameTerrain() {
		List<RenderPipeline> game = new ArrayList<>();
		for (String path : List.of("pipeline/solid_terrain", "pipeline/cutout_terrain", "pipeline/translucent_terrain")) {
			game.add(RenderPipeline.builder()
				.withLocation(Identifier.fromNamespaceAndPath("minecraft", path))
				.withVertexShader("core/terrain")
				.withFragmentShader("core/terrain")
				.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
				.withVertexBinding(0, TerrainVertexType.INSTANCE.getVertexFormat())
				.build());
		}
		return game;
	}

	@Test
	void sodiumsPipelinesAreNamedAfterTheGameTerrainPathsUnderTheSodiumNamespace() {
		List<String> names = new ArrayList<>();
		for (RenderPipeline sodium : GbufferPlans.sodiumTerrainPipelines(gameTerrain(),
				TerrainVertexType.INSTANCE.getVertexFormat())) {
			names.add(sodium.getLocation().toString());
		}
		assertEquals(List.of("sodium:pipeline/solid_terrain", "sodium:pipeline/cutout_terrain",
			"sodium:pipeline/translucent_terrain"), names);
	}

	@Test
	void sodiumsPipelinesCarryCreateShadersFacts() {
		VertexFormat extended = TerrainVertexType.INSTANCE.getVertexFormat();
		for (RenderPipeline sodium : GbufferPlans.sodiumTerrainPipelines(gameTerrain(), extended)) {
			assertSame(extended, sodium.getVertexFormatBinding(0), sodium.getLocation() + " binds the extended format");
			assertEquals(PrimitiveTopology.QUADS, sodium.getPrimitiveTopology(), sodium.getLocation().toString());
			assertTrue(sodium.isCull(), sodium.getLocation() + " culls");
			assertEquals(DepthStencilState.DEFAULT, sodium.getDepthStencilState(), sodium.getLocation().toString());
			assertEquals(GpuFormat.RGBA8_UNORM, sodium.getColorTargetStates().get(0).format(), sodium.getLocation().toString());
			assertEquals(20, sodium.pushConstantSize(), sodium.getLocation().toString());
			assertEquals(Identifier.fromNamespaceAndPath("sodium", "blocks/block_layer_opaque"),
				sodium.getShaders().get(ShaderType.VERTEX), sodium.getLocation().toString());
		}
		RenderPipeline translucent = GbufferPlans.sodiumTerrainPipelines(gameTerrain(), extended).get(2);
		assertEquals(BlendFunction.TRANSLUCENT, translucent.getColorTargetStates().get(0).blendFunction().orElseThrow());
		RenderPipeline solid = GbufferPlans.sodiumTerrainPipelines(gameTerrain(), extended).get(0);
		assertTrue(solid.getColorTargetStates().get(0).blendFunction().isEmpty(), "solid terrain blends nothing");
	}

	/** A game pipeline the multidraw backend names ({@code solid_terrain_multidraw}) is not one of Sodium's passes. */
	@Test
	void theMultidrawIdsAreNotSodiumPipelinePaths() {
		RenderPipeline multidraw = RenderPipeline.builder()
			.withLocation(Identifier.fromNamespaceAndPath("minecraft", "pipeline/solid_terrain_multidraw"))
			.withVertexShader("core/terrain")
			.withFragmentShader("core/terrain")
			.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
			.build();
		assertTrue(GbufferPlans.sodiumTerrainPipelines(List.of(multidraw),
			TerrainVertexType.INSTANCE.getVertexFormat()).isEmpty());
	}

	@Test
	void thePlansKeyTheGameAndSodiumPipelinesOfTheSamePathApart() {
		GbufferPlans.Plan sodiumPlan = new PlanStub("solid_terrain");
		GbufferPlans.Plan gamePlan = new PlanStub("solid_terrain");
		GbufferPlans plans = GbufferPlans.forTest(Map.of("sodium:pipeline/solid_terrain", sodiumPlan,
			"minecraft:pipeline/solid_terrain", gamePlan));
		assertSame(sodiumPlan, plans.planOf("sodium:pipeline/solid_terrain"));
		assertSame(gamePlan, plans.planOf("minecraft:pipeline/solid_terrain"));
		assertNull(plans.planOf("sodium:pipeline/cutout_terrain"));
		assertNotNull(plans);
	}

	private record PlanStub(String location) implements GbufferPlans.Plan {
		@Override
		public GbufferPlans.@Nullable Variant variant(PassKind kind, GbufferPhase phase, GbufferMapping.Domain domain) {
			return null;
		}

		@Override
		public String reason(PassKind kind, GbufferPhase phase, GbufferMapping.Domain domain) {
			return "";
		}
		@Override
		public String location() {
			return this.location;
		}
	}
}
