// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.frontend.FrontendRenderPipeline;
import dev.mcms.metal.BackendPassHarness;
import dev.mcms.metal.MetalBridge;
import dev.mcms.shaders.metal.HeadlessGpu;
import dev.mcms.shaders.pack.DimensionPrograms;
import dev.mcms.shaders.pack.PackModel;
import dev.mcms.shaders.pack.PackSource;
import dev.mcms.shaders.pack.StandardMacros;
import dev.mcms.shaders.runtime.gbuffers.GbufferMapping;
import dev.mcms.shaders.runtime.gbuffers.GbufferPhase;
import dev.mcms.shaders.runtime.gbuffers.GbufferPlans;
import dev.mcms.shaders.runtime.gbuffers.PassKind;
import dev.mcms.shaders.runtime.gbuffers.VertexLayoutRegistry;
import dev.mcms.shaders.runtime.terrain.TerrainLayout;
import dev.mcms.shaders.runtime.uniforms.CustomUniforms;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * A game-pipeline copy draws with the backend pipeline, not the game cache's frontend record (2026-11-15). The game's
 * pipeline cache returns a {@code FrontendRenderPipeline}, which holds the backend object; the delegate reads the pipeline
 * states from the backend object. Before the fix every copy held the frontend record, so the backend saw no state and each
 * copied pipeline's draws were dropped: mcms-targets (six targets) lost its entities and clouds (the E6 draw log). The
 * copier here returns a frontend record over a backend object, as the game's cache does. The cache owns its copies: once a
 * resource reload closes the old cache, a copy reads null (its draws are dropped), never a released pipeline state.
 */
@Tag("gpu")
class GameVariantCopyBackendGpuTest {

	/** The state handles of the backend objects the copier hands out: only read back, never drawn. */
	private static final long WITH_DEPTH = 0x1100L, WITHOUT_DEPTH = 0x2200L;

	@BeforeAll
	static void headless() {
		// MetalPipeline's cache directory asks Fabric for the game dir, which a headless test has none of.
		System.setProperty("mcms.metal.mslCache", "false");
	}

	@Test
	void everyCopyOfTheTargetsPacksPlansDrawsWithTheBackendObjectsStates() throws IOException {
		withTargetsPlans(plans -> {
			// The game's cache: a frontend record over the backend object it compiled.
			plans.completeCopies(copy -> frontend(copy.getLocation().toString(),
				BackendPassHarness.pipelineWithStates(copy.getLocation().toString(), List.of(), WITH_DEPTH, WITHOUT_DEPTH)));
			List<GbufferPlans.GameVariant> copies = copiesOf(plans);
			for (GbufferPlans.GameVariant game : copies) {
				assertEquals(WITH_DEPTH, MetalBridge.pipelineVariant(game.copy(), true), "a copy binds the backend object's state");
				assertEquals(WITHOUT_DEPTH, MetalBridge.pipelineVariant(game.copy(), false));
			}
		});
	}

	@Test
	void aCopyTheGameClosedReadsNullSoItsDrawsAreDropped() throws IOException {
		withTargetsPlans(plans -> {
			List<FrontendRenderPipeline> records = new ArrayList<>();
			plans.completeCopies(copy -> {
				FrontendRenderPipeline record = frontend(copy.getLocation().toString(), new Closable());
				records.add(record);
				return record;
			});
			List<GbufferPlans.GameVariant> copies = copiesOf(plans);
			for (GbufferPlans.GameVariant game : copies) {
				assertInstanceOf(Closable.class, game.copy(), "an open copy is its backend object");
			}
			records.forEach(FrontendRenderPipeline::close);  // the reload's shader apply closes the old cache
			for (GbufferPlans.GameVariant game : copies) {
				assertNull(game.copy(), "a closed copy is not drawn");
			}
		});
	}

	/** A backend object that only knows whether it is closed (the game's cache closes it). */
	private static final class Closable implements BackendRenderPipeline {
		private boolean closed;

		@Override
		public boolean isClosed() {
			return this.closed;
		}

		@Override
		public void close() {
			this.closed = true;
		}
	}

	private interface PlansCheck {
		void check(GbufferPlans plans);
	}

	/** Builds mcms-targets' plans (six targets: its entities and clouds draw through copies) with the copies pending. */
	private static void withTargetsPlans(PlansCheck check) throws IOException {
		PackModel model;
		try (PackSource source = PackSource.open(findTestPack("mcms-targets"))) {
			model = PackModel.load(source, StandardMacros.engine(), "en_us", new Properties());
		}
		DimensionPrograms dimension = model.rootFolder();
		VertexLayoutRegistry.register(GbufferRoutingGpuTest.EXTENDED, TerrainLayout.layout(0), GbufferBuildSupport.TERRAIN_FACTS);
		try (HeadlessGpu gpu = new HeadlessGpu();
				GbufferPlans plans = GbufferPlans.build(gpu.context(), model, dimension, CustomUniforms.of(model),
					dimension.union().colorFormats(), Engine.gamePipelines(), Engine.COPIES_NOT_COMPILED)) {
			check.check(plans);
		} finally {
			VertexLayoutRegistry.unregister(GbufferRoutingGpuTest.EXTENDED);
		}
	}

	/** The game cache's record over a backend object. */
	private static FrontendRenderPipeline frontend(String name, BackendRenderPipeline backend) {
		return new FrontendRenderPipeline(name, backend, List.of(), new Object2IntOpenHashMap<>(), List.of(), List.of(), true, 0);
	}

	/** The copy variants of entity_cutout and clouds, in every gbuffers phase, domain and group. */
	private static List<GbufferPlans.GameVariant> copiesOf(GbufferPlans plans) {
		List<GbufferPlans.GameVariant> copies = new ArrayList<>();
		for (String name : List.of("minecraft:pipeline/entity_cutout", "minecraft:pipeline/clouds")) {
			GbufferPlans.Plan plan = plans.planOf(name);
			assertNotNull(plan, "the plans hold " + name);
			for (GbufferPhase phase : GbufferPhase.values()) {
				for (GbufferMapping.Domain domain : GbufferMapping.Domain.values()) {
					for (int group = 0; group < plans.groups(phase); group++) {
						GbufferPlans.GameVariant game = plan.gameVariant(PassKind.GBUFFERS, phase, domain, group);
						if (game != null && !game.ownPipeline()) {
							copies.add(game);
						}
					}
				}
			}
		}
		assertFalse(copies.isEmpty(), "entities and clouds draw through copies in mcms-targets' groups (the premise)");
		return copies;
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
