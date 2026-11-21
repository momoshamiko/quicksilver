// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import dev.mcms.shaders.metal.HeadlessGpu;
import dev.mcms.shaders.pack.PackModel;
import dev.mcms.shaders.pack.PackSource;
import dev.mcms.shaders.pack.StandardMacros;
import dev.mcms.shaders.runtime.gbuffers.GbufferMapping;
import dev.mcms.shaders.runtime.gbuffers.GbufferPhase;
import dev.mcms.shaders.runtime.gbuffers.GbufferPlans;
import dev.mcms.shaders.runtime.gbuffers.PassKind;
import dev.mcms.shaders.runtime.gbuffers.PrologueFacts;
import dev.mcms.shaders.runtime.gbuffers.VertexLayoutRegistry;
import dev.mcms.shaders.runtime.terrain.TerrainLayout;
import dev.mcms.shaders.runtime.terrain.TerrainVertexType;
import dev.mcms.shaders.runtime.uniforms.CustomUniforms;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Every game pipeline a documented world row maps draws through a pack program in every phase the row names, on the
 * {@code mcms-classes} pack (it defines the gbuffers programs). A world pipeline that resolves to a game variant instead
 * draws the game's own shader into colortex0, which is the vanilla picture the engine must not show (plan.md E4).
 */
@Tag("gpu")
class GbufferCoverageGpuTest {

	private static final int RGBA8 = 70;
	private static PackModel model;
	private static CustomUniforms custom;

	/**
	 * The registered extended terrain format Sodium's passes draw with (E4-B's registration; the same shape
	 * {@code TerrainEngine.registerLayout} installs in the game): a zero offset compiles the same request.
	 */
	private static final VertexFormat EXTENDED = TerrainVertexType.INSTANCE.getVertexFormat();

	private static final PrologueFacts FACTS = new PrologueFacts(26, 0, 21, 0, -1);

	/**
	 * Locations that still draw through the game's variant, with the cause (open; the test fails when one starts to draw
	 * through a pack program, so the entry is removed then). Empty since the clouds prologue: clouds, flat_clouds and
	 * the OIT transmittance variant draw through the pack's gbuffers_clouds (CloudsLayout, plan.md E4's clouds bullet).
	 */
	private static final Set<String> KNOWN_OPEN = Set.of();

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

	@Test
	void everyWorldPipelineDrawsThroughAPackProgramInEveryPhaseItsRowNames() {
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			long ctx = gpu.context();
			DimensionSet set = DimensionSet.load(ctx, "mcms-classes", model, model.rootFolder(), custom, RGBA8,
				model.rootFolder().union().colorFormats(), Engine.gamePipelines(), Engine.COPIES_NOT_COMPILED);
			assertTrue(set.absent() == 0, "the pack's programs must all run: " + set.notes());
			GbufferPlans plans = set.plans();
			assertNotNull(plans, "the pack's gbuffers plans must build: " + set.notes());
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
					GbufferPlans.Variant variant = plan.variant(PassKind.GBUFFERS, phase);
					boolean pack = variant instanceof GbufferPlans.PackVariant pv && !pv.program().isEmpty();
					if (KNOWN_OPEN.contains(location)) {
						// Open (see KNOWN_OPEN): the variant stays the game's until the cause is fixed.
						if (pack) {
							failures.append(game.getLocation()).append(' ').append(phase)
								.append(": now draws through a pack program; remove it from KNOWN_OPEN\n");
						}
					} else if (!pack) {
						failures.append(game.getLocation()).append(' ').append(phase).append(": ")
							.append(variant == null ? "no variant" : "game variant: " + plan.reason(PassKind.GBUFFERS, phase))
							.append('\n');
					}
				}
			}
			assertTrue(checked > 0, "some world pipelines must be checked");
			assertTrue(failures.isEmpty(), "world pipelines that do not draw through a pack program:\n" + failures);
		}
	}

	@Test
	void sodiumsTerrainPipelinesAndUnmappedWorldPipelinesArePlanned() {
		VertexLayoutRegistry.register(EXTENDED, TerrainLayout.layout(0), FACTS);
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			long ctx = gpu.context();
			DimensionSet set = DimensionSet.load(ctx, "mcms-classes", model, model.rootFolder(), custom, RGBA8,
				model.rootFolder().union().colorFormats(), Engine.gamePipelines(), Engine.COPIES_NOT_COMPILED);
			assertTrue(set.absent() == 0, "the pack's programs must all run: " + set.notes());
			GbufferPlans plans = set.plans();
			assertNotNull(plans, "the pack's gbuffers plans must build: " + set.notes());
			StringBuilder failures = new StringBuilder();
			for (String pass : List.of("solid_terrain", "cutout_terrain", "translucent_terrain")) {
				GbufferPlans.Plan plan = plans.planOf("sodium:pipeline/" + pass);
				if (plan == null) {
					failures.append("sodium:pipeline/").append(pass).append(": no plan\n");
					continue;
				}
				GbufferPhase phase = pass.equals("translucent_terrain") ? GbufferPhase.TRANSLUCENT : GbufferPhase.OPAQUE;
				GbufferPlans.Variant variant = plan.variant(PassKind.GBUFFERS, phase);
				if (!(variant instanceof GbufferPlans.PackVariant pv) || pv.program().isEmpty()) {
					failures.append("sodium:pipeline/").append(pass).append(' ').append(phase).append(": ")
						.append(variant == null ? "no variant" : "game variant").append(" (")
						.append(plan.reason(PassKind.GBUFFERS, phase)).append(")\n");
				}
				// The game's own pipeline of the same path keeps its own plan (a different vertex format).
				if (plans.planOf("minecraft:pipeline/" + pass) == null) {
					failures.append("minecraft:pipeline/").append(pass).append(": no plan\n");
				}
			}
			// An unmapped location's game variant must exist in both phases it can draw in (item_cutout dropped
			// translucent-phase draws with a WARN and would drop solid-phase ones silently).
			GbufferPlans.Plan items = plans.planOf("minecraft:pipeline/item_cutout");
			if (items == null) {
				failures.append("minecraft:pipeline/item_cutout: no plan\n");
			} else {
				for (GbufferPhase phase : new GbufferPhase[] {GbufferPhase.OPAQUE, GbufferPhase.TRANSLUCENT}) {
					if (items.variant(PassKind.GBUFFERS, phase) == null) {
						failures.append("minecraft:pipeline/item_cutout ").append(phase).append(": no variant\n");
					}
				}
			}
			assertTrue(failures.isEmpty(), failures.toString());
		} finally {
			VertexLayoutRegistry.unregister(EXTENDED);
		}
	}
}
