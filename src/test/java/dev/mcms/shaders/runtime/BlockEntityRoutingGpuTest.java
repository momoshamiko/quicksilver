// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import dev.mcms.shaders.metal.HeadlessGpu;
import dev.mcms.shaders.runtime.gbuffers.GbufferMapping;
import dev.mcms.shaders.runtime.gbuffers.GbufferPhase;
import dev.mcms.shaders.runtime.gbuffers.GbufferPlans;
import dev.mcms.shaders.runtime.gbuffers.PassKind;
import dev.mcms.shaders.pack.PackModel;
import dev.mcms.shaders.pack.PackSource;
import dev.mcms.shaders.pack.StandardMacros;
import dev.mcms.shaders.runtime.uniforms.CustomUniforms;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The E4 routing rules on real game pipelines (plan.md E4, the pipeline-mapping bullet): the {@code mcms-classes} pack
 * (it defines every gbuffers program) builds plans over the game's own pipeline objects, so the test checks the whole
 * road — the pipeline's real blend state (its colour target states), the mapping's two rules and the build — not the
 * table alone (that is GbufferMappingTest). The block-entity domain is what the split draws in: a block-entity
 * pipeline that resolved a game variant there would draw the vanilla picture, which the engine must not show.
 */
@Tag("gpu")
class BlockEntityRoutingGpuTest {

	private static final int RGBA8 = 70;
	private static PackModel model;
	private static CustomUniforms custom;

	@BeforeAll
	static void loadPack() throws Exception {
		Path dir = findPack();
		Properties options = new Properties();
		try (PackSource source = PackSource.open(dir)) {
			model = PackModel.load(source, StandardMacros.engine(), "en_us", options);
		}
		assertTrue(model.problems().isEmpty(), "the test pack must load clean: " + model.problems());
		custom = CustomUniforms.of(model);
	}

	private static Path findPack() {
		Path dir = Path.of(System.getProperty("user.dir"));
		for (int i = 0; i < 6; i++) {
			Path candidate = dir.resolve(Path.of("bench", "test-packs", "mcms-classes"));
			if (Files.isDirectory(candidate)) {
				return candidate;
			}
			dir = dir.getParent();
		}
		throw new IllegalStateException("bench/test-packs/mcms-classes not found above " + System.getProperty("user.dir"));
	}

	private static GbufferPlans plans(HeadlessGpu gpu) {
		long ctx = gpu.context();
		DimensionSet set = DimensionSet.load(ctx, "mcms-classes", model, model.rootFolder(), custom, RGBA8,
			model.rootFolder().union().colorFormats(), Engine.gamePipelines(), Engine.COPIES_NOT_COMPILED);
		assertTrue(set.absent() == 0, "the pack's programs must all run: " + set.notes());
		GbufferPlans plans = set.plans();
		assertNotNull(plans, "the pack's gbuffers plans must build: " + set.notes());
		return plans;
	}

	private static RenderPipeline pipeline(String path) {
		for (RenderPipeline game : Engine.gamePipelines()) {
			if (game.getLocation().toString().equals(path)) {
				return game;
			}
		}
		throw new IllegalStateException("the game lists no pipeline " + path);
	}

	/** Whether the game pipeline's colour target states carry a blend function (rule 1's fact, from the game). */
	private static boolean blends(RenderPipeline game) {
		return game.getColorTargetStates().stream().anyMatch(state -> state.blendFunction().isPresent());
	}

	/** Asserts the plan's (kind, phase, domain) variant is the pack program named {@code program}. */
	private static void assertProgram(GbufferPlans plans, String pipelinePath, GbufferPhase phase,
			GbufferMapping.Domain domain, String program, StringBuilder failures) {
		GbufferPlans.Plan plan = plans.planOf(pipelinePath);
		if (plan == null) {
			failures.append(pipelinePath).append(": no plan\n");
			return;
		}
		GbufferPlans.Variant variant = plan.variant(PassKind.GBUFFERS, phase, domain);
		if (!(variant instanceof GbufferPlans.PackVariant pv) || !pv.program().equals(program)) {
			failures.append(pipelinePath).append(' ').append(phase).append(' ').append(domain).append(": ")
				.append(variant == null ? "no variant"
					: variant instanceof GbufferPlans.PackVariant pv2 ? "pack program " + pv2.program()
					: "game variant: " + plan.reason(PassKind.GBUFFERS, phase, domain))
				.append(", wanted gbuffers_").append(program).append('\n');
		}
	}

	@Test
	void theGamePipelinesBlendStateFeedsRuleOneAndTheRulesPickThePrograms() {
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			GbufferPlans plans = plans(gpu);
			StringBuilder failures = new StringBuilder();

			// Rule 1 needs the real blend facts; the game's pipelines carry them (RenderPipelines javap, 2026-11-14).
			assertTrue(blends(pipeline("minecraft:pipeline/entity_shadow")), "entity_shadow must blend (TRANSLUCENT)");
			assertTrue(blends(pipeline("minecraft:pipeline/text")), "text must blend (TRANSLUCENT)");
			assertTrue(!blends(pipeline("minecraft:pipeline/entity_cutout")), "entity_cutout must not blend (DEFAULT)");
			assertTrue(!blends(pipeline("minecraft:pipeline/text_grayscale_see_through")),
				"text_grayscale_see_through must not blend (DEFAULT)");

			// Rule 1: the blending entity pipelines draw through gbuffers_entities_translucent (the entity domain).
			for (String location : new String[] {"entity_shadow", "text", "text_see_through"}) {
				assertProgram(plans, "minecraft:pipeline/" + location, GbufferPhase.TRANSLUCENT, GbufferMapping.Domain.ENTITY,
					"gbuffers_entities_translucent", failures);
			}
			// The entity shadows draw in the translucent phase only; the non-blending entity pipelines keep entities.
			assertProgram(plans, "minecraft:pipeline/entity_cutout", GbufferPhase.OPAQUE, GbufferMapping.Domain.ENTITY,
				"gbuffers_entities", failures);

			// Rule 2: the block-entity domain routes the entity pipelines' geometry through the block programs.
			// (entity_cutout_no_cull has no pipeline the game lists — GameLocationCoverageTest's list — so it is not
			// checked here; GbufferMappingTest covers its rows.)
			for (String location : new String[] {"entity_cutout", "entity_solid", "item_cutout", "text"}) {
				assertProgram(plans, "minecraft:pipeline/" + location, GbufferPhase.OPAQUE,
					GbufferMapping.Domain.BLOCK_ENTITY, "gbuffers_block", failures);
			}
			// The banner's pattern layers blend, so they take gbuffers_block_translucent there.
			assertProgram(plans, "minecraft:pipeline/banner_pattern", GbufferPhase.OPAQUE,
				GbufferMapping.Domain.BLOCK_ENTITY, "gbuffers_block_translucent", failures);
			assertProgram(plans, "minecraft:pipeline/translucent_block", GbufferPhase.TRANSLUCENT,
				GbufferMapping.Domain.BLOCK_ENTITY, "gbuffers_block_translucent", failures);
			// The beacon beams keep their documented program in both domains.
			assertProgram(plans, "minecraft:pipeline/beacon_beam_opaque", GbufferPhase.OPAQUE,
				GbufferMapping.Domain.BLOCK_ENTITY, "gbuffers_beaconbeam", failures);
			assertTrue(failures.isEmpty(), failures.toString());
		}
	}

	@Test
	void everyMappedPipelineDrawsThroughAPackProgramInTheBlockEntityDomainToo() {
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			GbufferPlans plans = plans(gpu);
			StringBuilder failures = new StringBuilder();
			int checked = 0;
			for (RenderPipeline game : Engine.gamePipelines()) {
				String location = GbufferMapping.locationOf(game.getLocation().getPath());
				if (GbufferMapping.table().get(location) == null) {
					continue;  // no documented row: a game variant by design (GameLocationCoverageTest lists them)
				}
				GbufferPlans.Plan plan = plans.planOf(game.getLocation().toString());
				if (plan == null) {
					failures.append(game.getLocation()).append(": no plan\n");
					continue;
				}
				for (GbufferPhase phase : GbufferMapping.phases(PassKind.GBUFFERS, location)) {
					checked++;
					GbufferPlans.Variant variant = plan.variant(PassKind.GBUFFERS, phase,
						GbufferMapping.Domain.BLOCK_ENTITY);
					if (!(variant instanceof GbufferPlans.PackVariant pv) || pv.program().isEmpty()) {
						failures.append(game.getLocation()).append(' ').append(phase).append(" BLOCK_ENTITY: ")
							.append(variant == null ? "no variant"
								: "game variant: " + plan.reason(PassKind.GBUFFERS, phase, GbufferMapping.Domain.BLOCK_ENTITY))
							.append('\n');
					}
				}
			}
			assertTrue(checked > 0, "some world pipelines must be checked");
			assertTrue(failures.isEmpty(), "block-entity pipelines that do not draw through a pack program:\n" + failures);
		}
	}
}
