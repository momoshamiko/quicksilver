// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcms.shaders.metal.HeadlessGpu;
import dev.mcms.shaders.pack.DimensionPrograms;
import dev.mcms.shaders.pack.PackModel;
import dev.mcms.shaders.pack.PackSource;
import dev.mcms.shaders.pack.StandardMacros;
import dev.mcms.shaders.runtime.uniforms.CustomUniforms;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The engine's install and set switching on the headless device, through the engine's own paths (not a hand-fed
 * store): an install starts the other folders' loads (plan.md E3); a switch away keeps the outgoing set in the store;
 * a switch back is at once and hands the set out once; turning off releases every set exactly once. The releases are
 * counted by the engine's releaser seam, and the production release still frees the pipelines.
 */
@Tag("gpu")
class EngineSwitchGpuTest {

	private static final int W = 64, H = 64;
	private static final int RGBA8 = 70;
	private static final String NETHER_FOLDER = "world-1";
	private static final String OVERWORLD = "minecraft:overworld";
	private static final String VSH = """
		#version 130
		varying vec2 texcoord;
		void main() {
			texcoord = gl_MultiTexCoord0.xy;
			gl_Position = ftransform();
		}
		""";

	/** A pack with a root composite and a legacy nether folder's composite (each its own program set). */
	private static PackModel writePack(Path dir) throws Exception {
		Path shaders = Files.createDirectories(dir.resolve("shaders"));
		Path nether = Files.createDirectories(shaders.resolve(NETHER_FOLDER));
		for (Path folder : new Path[] {shaders, nether}) {
			Files.writeString(folder.resolve("composite.vsh"), VSH);
			Files.writeString(folder.resolve("composite.fsh"), """
				#version 130
				/* DRAWBUFFERS:0 */
				void main() {
					gl_FragColor = vec4(0.0, 1.0, 0.0, 1.0);
				}
				""");
		}
		try (PackSource source = PackSource.open(dir)) {
			return PackModel.load(source, StandardMacros.engine(), "en_us", new Properties());
		}
	}

	private static String folderKey(DimensionPrograms programs) {
		return programs.folder().orElse("");
	}

	private static int count(Map<DimensionSet, Integer> releases, DimensionSet set) {
		return releases.getOrDefault(set, 0);
	}

	private static void waitUntil(BooleanSupplier condition, int seconds) throws InterruptedException {
		long deadline = System.nanoTime() + seconds * 1_000_000_000L;
		while (!condition.getAsBoolean()) {
			if (System.nanoTime() > deadline) {
				throw new AssertionError("timed out after " + seconds + " s waiting for the background loads");
			}
			Thread.sleep(20);
		}
	}

	@Test
	void anInstallLoadsTheOtherFoldersAndASwitchKeepsAndReturnsTheSetsOnce(@TempDir Path dir) throws Exception {
		PackModel model = writePack(dir);
		DimensionPrograms nether = model.dimensions().stream()
			.filter(p -> p.folder().equals(Optional.of(NETHER_FOLDER))).findFirst().orElseThrow();
		String netherId = nether.dimension().orElseThrow().id;
		Map<DimensionSet, Integer> releases = Collections.synchronizedMap(new IdentityHashMap<>());
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			long ctx = gpu.context();
			Engine engine = new Engine();
			engine.setReleaser(set -> {
				releases.merge(set, 1, Integer::sum);
				Engine.releaseSet(set);  // the production release: frees the set's pipelines
			});
			engine.prepareLoad(model);
			DimensionSet root = DimensionSet.load(ctx, "switch", model, model.rootFolder(), CustomUniforms.of(model),
				RGBA8, model.rootFolder().union().colorFormats(), Engine.gamePipelines(), Engine.COPIES_NOT_COMPILED);
			Engine.Surface surface = new Engine.Surface(ctx, W, H, RGBA8);
			DimensionSet netherSet = null;
			try {
				engine.install(root, surface);
				assertEquals(Engine.State.ACTIVE, engine.state(), "the root set installs");
				// The install submits the other folder's load; the finished set waits in the store (plan.md E3).
				waitUntil(() -> engine.readySet(folderKey(nether)) != null, 60);
				netherSet = engine.readySet(folderKey(nether));
				assertNotNull(netherSet, "the install starts the other folders' loads");

				// Root -> nether: the nether set is handed out at once; the root set is kept, not released.
				engine.dimensionChanged(netherId, surface);
				assertSame(netherSet, engine.activeSet(), "the switch installs the stored nether set");
				assertEquals(0, count(releases, root), "the outgoing root set is kept, not released");
				assertSame(root, engine.readySet(folderKey(model.rootFolder())), "the root set is stored");

				// Nether -> root: at once again (no load), and the nether set is kept.
				engine.dimensionChanged(OVERWORLD, surface);
				assertEquals(Engine.State.ACTIVE, engine.state(), "the switch back does not reload");
				assertSame(root, engine.activeSet(), "the root set comes back");
				assertSame(netherSet, engine.readySet(folderKey(nether)), "the nether set is stored again");
				assertEquals(0, count(releases, root), "no set was released on the way");
				assertEquals(0, count(releases, netherSet), "no set was released on the way");
			} finally {
				engine.shutdown();
			}
			// Turning off releases each set exactly once: the active one and the stored one.
			assertEquals(1, count(releases, root), "the root set is released once");
			assertEquals(1, count(releases, netherSet), "the nether set is released once");
			assertTrue(releases.size() == 2, "no other set was released: " + releases.size());
		}
	}

	@Test
	void aSetWhoseGpuInstallFailsIsReleasedNotLeaked(@TempDir Path dir) throws Exception {
		PackModel model = writePack(dir);
		Map<DimensionSet, Integer> releases = Collections.synchronizedMap(new IdentityHashMap<>());
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			long ctx = gpu.context();
			Engine engine = new Engine();
			engine.setReleaser(set -> {
				releases.merge(set, 1, Integer::sum);
				Engine.releaseSet(set);
			});
			engine.prepareLoad(model);
			DimensionSet root = DimensionSet.load(ctx, "install-fail", model, model.rootFolder(),
				CustomUniforms.of(model), RGBA8, model.rootFolder().union().colorFormats(), Engine.gamePipelines(),
				Engine.COPIES_NOT_COMPILED);
			// The device refuses the engine's copy pipeline (the seam stands in for a refusal Metal would report).
			engine.setCopyPipelines((refusedCtx, format) -> 0L);
			engine.install(root, new Engine.Surface(ctx, W, H, RGBA8));
			try {
				assertEquals(Engine.State.FAILED, engine.state(), "a copy pipeline that cannot be made fails the install");
				assertEquals(1, count(releases, root), "the set that never became active is released once (N1)");
			} finally {
				engine.shutdown();
			}
		}
	}

	@Test
	void aSwitchIntoAFolderStillLoadingInstallsOneSetAndReleasesNoDuplicate(@TempDir Path dir) throws Exception {
		PackModel model = writePack(dir);
		DimensionPrograms nether = model.dimensions().stream()
			.filter(p -> p.folder().equals(Optional.of(NETHER_FOLDER))).findFirst().orElseThrow();
		String netherId = nether.dimension().orElseThrow().id;
		Map<DimensionSet, Integer> releases = Collections.synchronizedMap(new IdentityHashMap<>());
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			long ctx = gpu.context();
			Engine engine = new Engine();
			engine.setReleaser(set -> {
				releases.merge(set, 1, Integer::sum);
				Engine.releaseSet(set);
			});
			engine.prepareLoad(model);
			engine.setDeviceReadyForTest(() -> true);  // the headless device stands in for the game's device
			// The loader is held: the nether load the install starts waits behind this, so it is still in flight below.
			java.util.concurrent.CountDownLatch hold = new java.util.concurrent.CountDownLatch(1);
			engine.holdLoaderForTest(hold);
			DimensionSet root = DimensionSet.load(ctx, "d1", model, model.rootFolder(), CustomUniforms.of(model),
				RGBA8, model.rootFolder().union().colorFormats(), Engine.gamePipelines(), Engine.COPIES_NOT_COMPILED);
			Engine.Surface surface = new Engine.Surface(ctx, W, H, RGBA8);
			DimensionSet netherSet = null;
			try {
				engine.install(root, surface);
				assertEquals(Engine.State.ACTIVE, engine.state());
				// The switch comes while the nether load is still in flight: it must wait for that load, not start another.
				engine.dimensionChanged(netherId, surface);
				assertEquals(Engine.State.LOADING, engine.state(), "the switch waits for the load in flight");
				hold.countDown();
				engine.awaitLoaderIdle();
				// Had the switch started a second load, the second set would have replaced the waiting one here.
				assertEquals(0, releases.size(), "no set is released before the install (D1)");
				netherSet = engine.pendingSet();
				assertNotNull(netherSet, "the one load lands for the frame");
				engine.takePendingSet(surface);
				assertSame(netherSet, engine.activeSet(), "the waiting set is installed");
				assertNull(engine.pendingSet());
				// A duplicate of the active folder that lands later is released, never installed over the active set.
				DimensionSet duplicate = DimensionSet.load(ctx, "d1", model, nether, CustomUniforms.of(model), RGBA8,
					nether.union().colorFormats(), Engine.gamePipelines(), Engine.COPIES_NOT_COMPILED);
				engine.complete(engine.currentGeneration(), duplicate);
				engine.takePendingSet(surface);
				assertSame(netherSet, engine.activeSet(), "a duplicate never displaces the active set (D1)");
				assertEquals(1, count(releases, duplicate), "the duplicate is released once");
				assertEquals(0, count(releases, netherSet), "the active set is not released");
			} finally {
				hold.countDown();  // a failed assertion above must not leave the loader held: the shutdown waits for it
				engine.shutdown();
			}
			assertEquals(1, count(releases, root), "the root set is released once, at turn-off");
			assertEquals(1, count(releases, netherSet), "the nether set is released once, at turn-off");
		}
	}

	@Test
	void aQueuedLoadOfAReplacedRequestCompilesNothing(@TempDir Path dir) throws Exception {
		PackModel model = writePack(dir);
		DimensionPrograms nether = model.dimensions().stream()
			.filter(p -> p.folder().equals(Optional.of(NETHER_FOLDER))).findFirst().orElseThrow();
		Map<DimensionSet, Integer> releases = Collections.synchronizedMap(new IdentityHashMap<>());
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			long ctx = gpu.context();
			Engine engine = new Engine();
			engine.setReleaser(set -> {
				releases.merge(set, 1, Integer::sum);
				Engine.releaseSet(set);
			});
			engine.prepareLoad(model);
			engine.setDeviceReadyForTest(() -> true);
			// The loader is held, so the install's nether load waits in the queue behind this task.
			java.util.concurrent.CountDownLatch hold = new java.util.concurrent.CountDownLatch(1);
			engine.holdLoaderForTest(hold);
			DimensionSet root = DimensionSet.load(ctx, "replaced", model, model.rootFolder(), CustomUniforms.of(model),
				RGBA8, model.rootFolder().union().colorFormats(), Engine.gamePipelines(), Engine.COPIES_NOT_COMPILED);
			try {
				engine.install(root, new Engine.Surface(ctx, W, H, RGBA8));
				// A newer request (a pack switch, or the engine turning off) replaces the queued load before its turn.
				engine.beginRequest();
				hold.countDown();
				engine.awaitLoaderIdle();
				assertEquals(0, releases.size(), "a queued load whose request was replaced compiles and releases nothing");
				assertNull(engine.readySet(folderKey(nether)), "no set of the replaced request is stored");
			} finally {
				hold.countDown();
				engine.shutdown();
			}
			assertEquals(1, releases.size(), "only the root set is released, once, at turn-off");
			assertEquals(1, count(releases, root), "the root set is released once, at turn-off");
		}
	}
}
