// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.gbuffers;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import dev.mcms.shaders.runtime.shadow.ShadowTargets;
import dev.mcms.shaders.runtime.terrain.TerrainLayout;
import dev.mcms.shaders.runtime.terrain.TerrainVertexType;
import dev.mcms.shaders.runtime.uniforms.CustomUniforms;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A pack with shadow reads and no shadow program (E5 review item 17): the shadow pass's casters are served by the built-in
 * shadow program, which goes through the translator and Metal like a pack's own and binds a less-equal, depth-writing,
 * {@code shadowcolor0}-only variant of the game's solid terrain pipeline.
 */
@Tag("gpu")
class DefaultShadowProgramGpuTest {

	/** Sodium's solid terrain pipeline's identifier (the plan's key). */
	private static final String SOLID_TERRAIN = "sodium:pipeline/solid_terrain";

	@Test
	void aPackWithoutAShadowProgramGetsTheBuiltInOneOnTheGpu(@TempDir Path dir) throws Exception {
		PackModel model = writePack(dir);
		// MetalPipeline's cache dir reads the game dir, which no test has: the cache is off before any Metal class initialises.
		System.setProperty("mcms.metal.mslCache", "false");
		if (RenderSystem.tryGetDevice() == null) {
			RenderSystem.initRenderThread();  // the game's pipeline objects need the renderer
			RenderSystem.initRenderer(BackendPassHarness.newDevice());
		}
		VertexLayoutRegistry.register(TerrainVertexType.INSTANCE.getVertexFormat(), TerrainLayout.layout(0),
			new PrologueFacts(26, 0, 21, 0, -1));
		DimensionPrograms dimension = model.rootFolder();
		assertTrue(ShadowTargets.of(dimension).used(), "the composite's shadow read makes the set use shadow targets");
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			GbufferPlans plans = GbufferPlans.build(gpu.context(), model, dimension, CustomUniforms.of(model),
				dimension.union().colorFormats(), terrainPipelines(), copy -> null);
			try {
				GbufferPlans.Plan plan = plans.planOf(SOLID_TERRAIN, new int[] {TerrainVertexType.STRIDE});
				assertNotNull(plan, "no plan for " + SOLID_TERRAIN);
				GbufferPlans.Variant variant = plan.variant(PassKind.SHADOW, GbufferPhase.OPAQUE,
					GbufferMapping.Domain.ENTITY);
				assertTrue(variant instanceof GbufferPlans.PackVariant, "the built-in shadow program did not compile: "
					+ plan.reason(PassKind.SHADOW, GbufferPhase.OPAQUE, GbufferMapping.Domain.ENTITY));
				GbufferPlans.PackVariant pack = (GbufferPlans.PackVariant) variant;
				assertTrue(pack.depth(false).description().contains("LESS_THAN_OR_EQUAL"),
					"the shadow pass compares less-equal: " + pack.depth(false).description());
				assertTrue(pack.depth(false).description().contains("write=1"),
					"the shadow pass writes depth: " + pack.depth(false).description());
				assertArrayEquals(new int[] {0}, plans.shadowLayout(), "the built-in program writes shadowcolor0 only");
			} finally {
				plans.close();
			}
		}
	}

	/** A composite that reads {@code shadowtex0} and a pack with no shadow file: the set has shadow reads only. */
	private static PackModel writePack(Path dir) throws Exception {
		Path shaders = Files.createDirectories(dir.resolve("shaders"));
		Files.writeString(shaders.resolve("composite.vsh"), """
			#version 130
			varying vec2 texcoord;
			void main() {
				texcoord = gl_MultiTexCoord0.xy;
				gl_Position = ftransform();
			}
			""");
		Files.writeString(shaders.resolve("composite.fsh"), """
			#version 130
			uniform sampler2D shadowtex0;
			varying vec2 texcoord;
			void main() {
				gl_FragData[0] = vec4(texture2D(shadowtex0, texcoord).r);
			}
			""");
		try (PackSource source = PackSource.open(dir)) {
			return PackModel.load(source, StandardMacros.engine(), "en_us", new Properties());
		}
	}

	/** The game's terrain pipelines by path (the three Sodium builds the plans map from). */
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
