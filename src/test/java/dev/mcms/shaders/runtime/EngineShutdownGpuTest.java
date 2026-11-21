// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.mcms.metal.MetalBridge;
import dev.mcms.shaders.metal.HeadlessGpu;
import dev.mcms.shaders.pack.DimensionPrograms;
import dev.mcms.shaders.pack.PackModel;
import dev.mcms.shaders.pack.PackSource;
import dev.mcms.shaders.pack.StandardMacros;
import dev.mcms.shaders.runtime.uniforms.CustomUniforms;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The device closes under a background build (plan.md E6, shutdown): the loader's build of another dimension is running
 * Metal compiles when {@link Engine#shutdown} is called. The shutdown must return within a short bound (the build stops
 * before its next Metal call, not after its round), with every Metal object the install and the build created released
 * and nothing installed.
 */
@Tag("gpu")
class EngineShutdownGpuTest {

	private static final int W = 256, H = 256;
	private static final int RGBA8 = 70;
	/** The shutdown's bound in milliseconds: one Metal library compile (about 0.3 s on the reference Mac) plus the wait. */
	private static final long STOP_BOUND_MS = 2_000;

	/** The Metal objects of the kinds the engine makes (pipeline states, libraries, depth states), summed. */
	private static long live() {
		return MetalBridge.trackCount(MetalBridge.TRACK_PIPELINE) + MetalBridge.trackCount(MetalBridge.TRACK_LIBRARY)
			+ MetalBridge.trackCount(MetalBridge.TRACK_DEPTH);
	}

	private static String counts() {
		return "pipeline=" + MetalBridge.trackCount(MetalBridge.TRACK_PIPELINE) + " library="
			+ MetalBridge.trackCount(MetalBridge.TRACK_LIBRARY) + " depth=" + MetalBridge.trackCount(MetalBridge.TRACK_DEPTH);
	}

	private static void waitUntil(BooleanSupplier condition, long seconds, String what) throws InterruptedException {
		long deadline = System.nanoTime() + seconds * 1_000_000_000L;
		while (!condition.getAsBoolean()) {
			if (System.nanoTime() > deadline) {
				throw new AssertionError("timed out after " + seconds + " s waiting for " + what + " (" + counts() + ")");
			}
			Thread.sleep(10);
		}
	}

	@Test
	@Tag("corpus")
	void aShutdownDuringAnotherDimensionsBuildReturnsPromptlyAndReleasesEverything() throws Exception {
		String corpus = System.getProperty("mcms.test.corpus");
		assumeTrue(corpus != null, "no corpus folder given");
		Path zip = Path.of(corpus, "ComplementaryReimagined_r5.9.3.zip");
		assumeTrue(Files.isRegularFile(zip), zip + " is missing");
		// The game's COMPLEMENTARY profile: the heavy case (the measured shutdown error ran with these options).
		Path optionFile = Path.of(corpus).getParent().getParent().resolve("pack-options").resolve("profile-complementary")
			.resolve("ComplementaryReimagined_r5.9.3.zip.txt");
		assumeTrue(Files.isRegularFile(optionFile), optionFile + " is missing");
		Properties options = new Properties();
		options.load(Files.newBufferedReader(optionFile));
		PackModel model;
		try (PackSource source = PackSource.open(zip)) {
			model = PackModel.load(source, StandardMacros.engine(), "en_us", options);
		}
		DimensionPrograms overworld = GbufferBuildSupport.overworld(model);
		Map<Integer, String> formats = overworld.union().colorFormats();
		MetalBridge.trackOn();
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			long ctx = gpu.context();
			Engine engine = new Engine();
			long before = live();
			engine.prepareLoad(model);
			engine.setDeviceReadyForTest(() -> true);  // the headless device stands in for the game's device
			// The frame's own set, built here with no game pipeline (no world: the install needs no game and no takeover, as
			// the takeover tests do). The install then starts the other folders' builds on the loader, with the game's list.
			DimensionSet root = DimensionSet.load(ctx, "shutdown", model, overworld, CustomUniforms.of(model), RGBA8,
				formats, List.of(), Engine.COPIES_NOT_COMPILED);
			engine.install(root, new Engine.Surface(ctx, W, H, RGBA8));
			try {
				assertEquals(Engine.State.ACTIVE, engine.state(), "the overworld set installs");
				long installed = live();
				assertTrue(installed > before, "the install made Metal objects (" + counts() + ")");
				// Wait until the loader is inside another folder's build and has compiled some states of it.
				waitUntil(() -> !engine.loading.isEmpty() && live() > installed + 20, 180,
					"a background build to compile states");
				assertFalse(engine.loading.isEmpty(), "a background build is still in flight at the shutdown");
				System.out.println("[shutdown-test] before shutdown: loading " + engine.loading.keySet() + ", " + counts());

				long t0 = System.nanoTime();
				engine.shutdown();
				long stopMs = (System.nanoTime() - t0) / 1_000_000;
				System.out.println("[shutdown-test] shutdown returned in " + stopMs + " ms; " + counts());

				assertTrue(stopMs < STOP_BOUND_MS, "shutdown waited " + stopMs + " ms for the background build (bound "
					+ STOP_BOUND_MS + " ms)");
				assertTrue(engine.loading.isEmpty(), "no load is marked in flight after the shutdown");
				assertNull(engine.pendingSet(), "nothing waits for the frame after the shutdown");
				assertNull(engine.activeSet(), "nothing is installed after the shutdown");
				assertEquals(Engine.State.OFF, engine.state());
			} finally {
				engine.shutdown();  // idempotent: a failed assertion above still releases everything
			}
			// Every object of the install and of the stopped build is released (the native objects dealloc once the
			// last reference goes; a short wait covers the release that runs on another thread).
			waitUntil(() -> live() == before, 10, "every Metal object to be released");
			assertEquals(before, live(), "the Metal objects after the shutdown equal those before the install (" + counts() + ")");
		}
	}
}
