// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.gbuffers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.mcms.metal.BackendPassHarness;
import dev.mcms.shaders.metal.HeadlessGpu;
import dev.mcms.shaders.pack.DimensionPrograms;
import dev.mcms.shaders.pack.PackModel;
import dev.mcms.shaders.pack.PackSource;
import dev.mcms.shaders.pack.StandardMacros;
import dev.mcms.shaders.runtime.uniforms.CustomUniforms;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * RIL-562 on the game's own pipelines: the per-draw {@code alphaTestRef} of a program the pack gives no {@code alphaTest}
 * is the cut of the game pipeline's own shader (0.5 on {@code cutout_terrain}, where Minecraft 26.3's coverage-scaled mips
 * leave kelp's transparent texels at 0.125; 0.1 on {@code translucent_terrain}; none on {@code solid_terrain}, which names
 * no cutout, as Iris and Vitrail draw it; 0.1 on the particle and text pipelines, whose shaders cut 0.1 without the
 * define), a configured reference reaches the cutout terrain, which runs {@code gbuffers_terrain}'s files as
 * {@code gbuffers_terrain_cutout}, and {@code alphaTest.<program>=off} passes 0, so a program that reads
 * {@code alphaTestRef} and tests itself discards nothing. The pack is one program, written to a temporary folder as
 * {@code gbuffers_terrain} and as {@code gbuffers_basic}, the last fallback of every other gbuffers program.
 */
@Tag("gpu")
class AlphaTestDefaultGpuTest {

	private static final String SOLID = "minecraft:pipeline/solid_terrain";
	private static final String CUTOUT = "minecraft:pipeline/cutout_terrain";
	private static final String TRANSLUCENT = "minecraft:pipeline/translucent_terrain";
	private static final String PARTICLE = "minecraft:pipeline/opaque_particle";
	private static final String TEXT = "minecraft:pipeline/text";

	@Test
	void theReferenceIsTheGameCutoutTheServingProgramsDirectiveOrZeroWhenOff(@TempDir Path dir) throws Exception {
		// MetalPipeline's cache dir reads the game dir, which no test has: the cache is off before any Metal class initialises.
		System.setProperty("mcms.metal.mslCache", "false");
		if (RenderSystem.tryGetDevice() == null) {
			RenderSystem.initRenderThread();  // the game's pipeline objects need the renderer
			RenderSystem.initRenderer(BackendPassHarness.newDevice());
		}
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			try (GbufferPlans plans = plans(gpu, dir.resolve("unset"), "")) {
				assertEquals(0.5f, reference(plans, CUTOUT, GbufferPhase.OPAQUE), "the game cuts its cutout terrain at 0.5");
				assertEquals(0.1f, reference(plans, TRANSLUCENT, GbufferPhase.TRANSLUCENT),
					"the game cuts its translucent terrain at 0.1");
				assertEquals(0f, reference(plans, SOLID, GbufferPhase.OPAQUE), "the game cuts nothing on its solid terrain");
				assertEquals(0.1f, reference(plans, PARTICLE, GbufferPhase.OPAQUE), "the game's particle shader cuts 0.1");
				assertEquals(0.1f, reference(plans, TEXT, GbufferPhase.TRANSLUCENT), "the game's text shader cuts 0.1");
			}
			try (GbufferPlans plans = plans(gpu, dir.resolve("set"), "alphaTest.gbuffers_terrain=GREATER 0.3\n")) {
				assertEquals(0.3f, reference(plans, CUTOUT, GbufferPhase.OPAQUE), "the fallback's directive serves its files");
			}
			try (GbufferPlans plans = plans(gpu, dir.resolve("off"), "alphaTest.gbuffers_terrain=off\n")) {
				assertEquals(0f, reference(plans, CUTOUT, GbufferPhase.OPAQUE), "a switched-off test passes every alpha");
			}
		}
	}

	private static GbufferPlans plans(HeadlessGpu gpu, Path pack, String properties) throws Exception {
		Path shaders = Files.createDirectories(pack.resolve("shaders"));
		Files.writeString(shaders.resolve("shaders.properties"), properties);
		for (String program : new String[] {"gbuffers_terrain", "gbuffers_basic"}) {
			Files.writeString(shaders.resolve(program + ".vsh"), """
				#version 130
				varying vec2 uv;
				void main() {
					gl_Position = ftransform();
					uv = (gl_TextureMatrix[0] * gl_MultiTexCoord0).xy;
				}
				""");
			Files.writeString(shaders.resolve(program + ".fsh"), """
				#version 130
				uniform sampler2D gtexture;
				uniform float alphaTestRef;
				varying vec2 uv;
				/* DRAWBUFFERS:0 */
				void main() {
					vec4 colour = texture2D(gtexture, uv);
					if (colour.a < alphaTestRef) discard;
					gl_FragData[0] = colour;
				}
				""");
		}
		PackModel model;
		try (PackSource source = PackSource.open(pack)) {
			model = PackModel.load(source, StandardMacros.engine(), "en_us", new Properties());
		}
		assertTrue(model.problems().isEmpty(), "the pack must load clean: " + model.problems());
		DimensionPrograms dimension = model.rootFolder();
		return GbufferPlans.build(gpu.context(), model, dimension, CustomUniforms.of(model), dimension.union().colorFormats(),
			GbufferFixtures.gamePipelines(), copy -> null);
	}

	/** The per-draw alpha-test reference of the pipeline's pack variant in one phase. */
	private static float reference(GbufferPlans plans, String pipeline, GbufferPhase phase) {
		GbufferPlans.Variant variant = plans.planOf(pipeline).variant(PassKind.GBUFFERS, phase, GbufferMapping.Domain.ENTITY);
		assertTrue(variant instanceof GbufferPlans.PackVariant, pipeline + " draws no pack program: " + variant);
		return ((GbufferPlans.PackVariant) variant).alphaTestRef();
	}
}
