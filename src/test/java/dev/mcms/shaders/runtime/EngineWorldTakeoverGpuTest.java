// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcms.shaders.metal.HeadlessGpu;
import dev.mcms.shaders.pack.PackModel;
import dev.mcms.shaders.pack.PackSource;
import dev.mcms.shaders.pack.StandardMacros;
import dev.mcms.shaders.runtime.gbuffers.GbufferDelegate;
import dev.mcms.shaders.runtime.uniforms.CustomUniforms;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The takeover of a pack whose terrain draws through the game's own pipelines (the {@code mcms-coords} test pack on the
 * preview: no pack program builds for its gbuffers, the terrain's OPAQUE draws resolve to game variants). Such a set
 * serves the world: its passes are redirected, so the engine's depth texture receives the world's depth (the
 * {@code composite} and {@code final} passes read depthtex0). Without the takeover the world's depth never reaches it
 * and every depth read sees the far value (the preview's "far everywhere" cells).
 */
@Tag("gpu")
class EngineWorldTakeoverGpuTest {

	private static final int W = 64, H = 48;
	private static final int RGBA8 = 70;
	private static PackModel model;

	@BeforeAll
	static void loadPack() throws Exception {
		Path dir = Path.of(System.getProperty("user.dir"));
		Path pack = null;
		for (int i = 0; i < 6 && pack == null; i++) {
			Path candidate = dir.resolve(Path.of("bench", "test-packs", "mcms-coords"));
			if (Files.isDirectory(candidate)) {
				pack = candidate;
			}
			dir = dir.getParent();
		}
		assertTrue(pack != null, "bench/test-packs/mcms-coords not found above " + System.getProperty("user.dir"));
		try (PackSource source = PackSource.open(pack)) {
			model = PackModel.load(source, StandardMacros.engine(), "en_us", new Properties());
		}
	}

	@Test
	void aSetWhoseTerrainDrawsThroughGameVariantsTakesTheWorldPassesOver() {
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			long ctx = gpu.context();
			Engine engine = new Engine();
			engine.prepareLoad(model);
			DimensionSet set = load(ctx);
			engine.install(set, new Engine.Surface(ctx, W, H, RGBA8));
			try {
				assertEquals(Engine.State.ACTIVE, engine.state());
				assertTrue(set.plans().servesWorld(),
					"the terrain's OPAQUE draws resolve to game variants (no pack program builds): the world is drawn");
				assertNotNull(GbufferDelegate.active(), "the world passes are taken over, so depthtex0 gets their depth");
				assertTrue(engine.useClassicTransparency(), "the takeover serves the world: classic transparency");
			} finally {
				engine.shutdown();
			}
		}
	}

	/** E4 review item 2: releasing the takeover closes its delegate (the native draw scratch leaked per install). */
	@Test
	void releasingTheTakeoverClosesItsDelegate() {
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			long ctx = gpu.context();
			Engine engine = new Engine();
			engine.prepareLoad(model);
			engine.install(load(ctx), new Engine.Surface(ctx, W, H, RGBA8));
			GbufferDelegate delegate = GbufferDelegate.active();
			assertNotNull(delegate, "a world-serving set installs its delegate");
			engine.shutdown();
			assertTrue(delegate.isClosed(), "the release closes the delegate");
			assertEquals(null, GbufferDelegate.active(), "and uninstalls it");
		}
	}

	private static DimensionSet load(long ctx) {
		return DimensionSet.load(ctx, "coords", model, model.rootFolder(), CustomUniforms.of(model), RGBA8,
			model.rootFolder().union().colorFormats(), Engine.gamePipelines(), Engine.COPIES_NOT_COMPILED);
	}
}
