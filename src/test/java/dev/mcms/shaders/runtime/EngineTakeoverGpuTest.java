// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcms.shaders.metal.HeadlessGpu;
import dev.mcms.shaders.pack.PackModel;
import dev.mcms.shaders.pack.PackSource;
import dev.mcms.shaders.pack.StandardMacros;
import dev.mcms.shaders.runtime.gbuffers.GbufferDelegate;
import dev.mcms.shaders.runtime.uniforms.CustomUniforms;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The takeover's install and release on the headless device, with the {@code mcms-passes} test pack: its world passes
 * have no game pipeline here (none is listed), so its plans serve no world. A set without a world installs nothing of
 * the takeover (E4 review item 1); a failed load releases the set it installed, once (item 5).
 */
@Tag("gpu")
class EngineTakeoverGpuTest {

	private static final int W = 64, H = 48;
	private static final int RGBA8 = 70;
	private static PackModel model;

	@BeforeAll
	static void loadPack() throws Exception {
		Path dir = Path.of(System.getProperty("user.dir"));
		Path pack = null;
		for (int i = 0; i < 6 && pack == null; i++) {
			Path candidate = dir.resolve(Path.of("bench", "test-packs", "mcms-passes"));
			if (Files.isDirectory(candidate)) {
				pack = candidate;
			}
			dir = dir.getParent();
		}
		assertTrue(pack != null, "bench/test-packs/mcms-passes not found above " + System.getProperty("user.dir"));
		Properties options = new Properties();
		options.setProperty("MCMS_HIGH_TARGETS", "true");
		try (PackSource source = PackSource.open(pack)) {
			model = PackModel.load(source, StandardMacros.engine(), "en_us", options);
		}
	}

	/** The set of the pack's root folder, loaded with no game pipelines (so no pack variant serves the world). */
	private static DimensionSet loadRoot(long ctx, String name) {
		return DimensionSet.load(ctx, name, model, model.rootFolder(), CustomUniforms.of(model), RGBA8,
			model.rootFolder().union().colorFormats(), List.of(), Engine.COPIES_NOT_COMPILED);
	}

	@Test
	void aSetWithoutAWorldInstallsNoTakeoverAndTheGameKeepsItsOwnPasses() {
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			long ctx = gpu.context();
			Engine engine = new Engine();
			engine.prepareLoad(model);
			DimensionSet set = loadRoot(ctx, "no-world");
			assertNotNull(set.plans(), "the loader builds the set's plans");
			assertFalse(set.plans().servesWorld(), "no game pipeline is listed, so no pack program serves the world");
			engine.install(set, new Engine.Surface(ctx, W, H, RGBA8));
			try {
				assertEquals(Engine.State.ACTIVE, engine.state(), "the set installs (its passes run)");
				assertNull(GbufferDelegate.active(), "no world: the takeover installs no delegate");
				assertFalse(engine.useClassicTransparency(), "no world: the game answers its own transparency");
				assertFalse(engine.drawsHand(), "no world: the game draws the hand its own way");
			} finally {
				engine.shutdown();
			}
		}
	}

	@Test
	void aFailedLoadReleasesTheInstalledSetOnceAndDoesNotReleaseItAgain() {
		Map<DimensionSet, Integer> releases = new IdentityHashMap<>();
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			long ctx = gpu.context();
			Engine engine = new Engine();
			engine.setReleaser(set -> {
				releases.merge(set, 1, Integer::sum);
				Engine.releaseSet(set);
			});
			engine.prepareLoad(model);
			DimensionSet set = loadRoot(ctx, "failed-load");
			engine.install(set, new Engine.Surface(ctx, W, H, RGBA8));
			assertNotNull(engine.activeSet(), "the set is active before the failure");
			engine.failForTest();
			engine.releaseAfterFailure();
			assertNull(engine.activeSet(), "a failed load leaves no active set (E4 review item 5)");
			assertEquals(Engine.State.FAILED, engine.state());
			assertEquals(1, releases.getOrDefault(set, 0), "released once");
			engine.releaseAfterFailure();
			engine.shutdown();
			assertEquals(1, releases.getOrDefault(set, 0), "a second release path does not release it again");
		}
	}
}
