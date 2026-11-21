// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.gbuffers;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import dev.mcms.metal.BackendPassHarness;
import dev.mcms.shaders.metal.HeadlessGpu;
import dev.mcms.shaders.pack.DimensionPrograms;
import dev.mcms.shaders.pack.PackModel;
import dev.mcms.shaders.pack.PackSource;
import dev.mcms.shaders.pack.StandardMacros;
import dev.mcms.shaders.runtime.terrain.TerrainLayout;
import dev.mcms.shaders.runtime.terrain.TerrainVertexType;
import dev.mcms.shaders.runtime.uniforms.CustomUniforms;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The frame head refills the chains the shadow programs' code reads past level 0, and only those (E9 review,
 * {@link GbufferPlans#shadowMipReadTargets}): a shadow program's explicit-level read of colortex1 is in the set, its
 * level-0 read of colortex3 is not, and the opaque terrain program's level read of colortex2 joins the opaque phase's set
 * ({@link GbufferPlans#mipReadTargets}, which also holds the shadow program's) but not the shadow set.
 */
@Tag("gpu")
class ShadowMipReadsGpuTest {

	private static final String VERTEX =
		"#version 120\nvarying vec2 uv;\nvoid main() { uv = gl_MultiTexCoord0.xy; gl_Position = ftransform(); }\n";

	@Test
	void theShadowSetHoldsTheShadowProgramsLevelReadsAlone(@TempDir Path dir) throws Exception {
		// MetalPipeline's cache dir reads the game dir, which no test has: the cache is off before any Metal class initialises.
		System.setProperty("mcms.metal.mslCache", "false");
		if (RenderSystem.tryGetDevice() == null) {
			RenderSystem.initRenderThread();  // the game's pipeline objects need the renderer
			RenderSystem.initRenderer(BackendPassHarness.newDevice());
		}
		Path shaders = Files.createDirectories(dir.resolve("shaders"));
		Files.writeString(shaders.resolve("shadow_solid.vsh"), VERTEX);
		Files.writeString(shaders.resolve("shadow_solid.fsh"), """
			#version 120
			uniform sampler2D colortex1;
			uniform sampler2D colortex3;
			varying vec2 uv;
			/* DRAWBUFFERS:0 */
			void main() { gl_FragData[0] = texture2DLod(colortex1, uv, 2.0) + texture2DLod(colortex3, uv, 0.0); }
			""");
		Files.writeString(shaders.resolve("gbuffers_terrain.vsh"), VERTEX);
		Files.writeString(shaders.resolve("gbuffers_terrain.fsh"), """
			#version 120
			uniform sampler2D colortex2;
			varying vec2 uv;
			/* DRAWBUFFERS:0 */
			void main() { gl_FragData[0] = texture2DLod(colortex2, uv, 2.0); }
			""");
		PackModel model;
		try (PackSource source = PackSource.open(dir)) {
			model = PackModel.load(source, StandardMacros.engine(), "en_us", new Properties());
		}
		DimensionPrograms dimension = model.rootFolder();
		VertexLayoutRegistry.register(TerrainVertexType.INSTANCE.getVertexFormat(), TerrainLayout.layout(0),
			new PrologueFacts(26, 0, 21, 0, -1));
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			GbufferPlans plans = GbufferPlans.build(gpu.context(), model, dimension, CustomUniforms.of(model),
				dimension.union().colorFormats(), terrainPipelines(), copy -> null);
			try {
				assertArrayEquals(new int[] {1}, plans.shadowMipReadTargets(), "the shadow programs' level reads");
				int[] opaque = plans.mipReadTargets(GbufferPhase.OPAQUE);
				assertTrue(Arrays.stream(opaque).anyMatch(t -> t == 1) && Arrays.stream(opaque).anyMatch(t -> t == 2),
					"the opaque phase's level reads hold the shadow program's and the terrain program's: "
						+ Arrays.toString(opaque));
			} finally {
				plans.close();
			}
		} finally {
			VertexLayoutRegistry.unregister(TerrainVertexType.INSTANCE.getVertexFormat());
		}
	}

	/** Sodium's three terrain pipelines (the plans key the shadow and terrain programs to them). */
	private static List<RenderPipeline> terrainPipelines() {
		List<RenderPipeline> pipelines = new ArrayList<>();
		for (String path : List.of("pipeline/solid_terrain", "pipeline/cutout_terrain", "pipeline/translucent_terrain")) {
			pipelines.add(RenderPipeline.builder()
				.withLocation(Identifier.fromNamespaceAndPath("minecraft", path))
				.withVertexShader(Identifier.fromNamespaceAndPath("minecraft", "core/terrain"))
				.withFragmentShader(Identifier.fromNamespaceAndPath("minecraft", "core/terrain"))
				.withCull(true)
				.withDepthStencilState(DepthStencilState.DEFAULT)
				.withPrimitiveTopology(PrimitiveTopology.QUADS)
				.build());
		}
		return pipelines;
	}
}
