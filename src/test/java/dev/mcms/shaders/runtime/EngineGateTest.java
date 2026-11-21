// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import dev.mcms.shaders.pack.DimensionPrograms;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The engine's settings gate and generation counter without a device or a game: the frame-start hook decides with the
 * settings and the backend only, and a background load a newer request replaced never reaches the frame. The arm the
 * game itself fills (the Metal device) is covered by the game runs; here every hook runs against an empty JVM, which is
 * exactly the "not our backend" arm.
 */
class EngineGateTest {

	@BeforeEach
	void noMetalBackend() {
		// GPU test classes in the same JVM may have made a RenderSystem device (RenderSystem.initRenderer); this test's
		// premise is a JVM with no Metal backend, so the device check is pinned to "none" here.
		Engine.get().setDeviceReadyForTest(() -> false);
	}

	@AfterEach
	void cleanUp() {
		System.clearProperty("mcms.shaders");
		System.clearProperty("mcms.shaders.pack");
		Engine.get().shutdown();
		Engine.get().restoreDeviceReadyForTest();
	}

	@Test
	void theEngineStaysOffWhenShadersAreOffOrNoPackIsSelected() {
		System.clearProperty("mcms.shaders");
		System.clearProperty("mcms.shaders.pack");
		// The file defaults: off, no pack. frameStart must return without touching its other arguments (null here).
		Engine.get().frameStart(null, null, 0f);
		assertEquals(Engine.State.OFF, Engine.get().state());

		System.setProperty("mcms.shaders", "on");
		Engine.get().frameStart(null, null, 0f);  // a pack is still missing
		assertEquals(Engine.State.OFF, Engine.get().state());
	}

	@Test
	void theEngineStaysOffWithoutTheMetalBackendEvenWithEverythingSwitchedOn() {
		System.setProperty("mcms.shaders", "on");
		System.setProperty("mcms.shaders.pack", "some-pack");
		// No game, no RenderSystem device in this JVM: backend() is null, the engine stays off and never loads.
		Engine.get().frameStart(null, null, 0f);
		assertEquals(Engine.State.OFF, Engine.get().state());
		assertNull(Engine.get().model());
	}

	@Test
	void aSupersededLoadIsDroppedAndOnlyTheNewestRequestReachesTheFrame() {
		Engine engine = Engine.get();
		DimensionPrograms programs = new DimensionPrograms("shaders/ root", Optional.empty(), Optional.of(""), "",
			List.of(), List.of());
		DimensionSet first = new DimensionSet(programs, List.of(), 0, 0, 0, 0, 0, 0, List.of());
		DimensionSet second = new DimensionSet(programs, List.of(), 0, 0, 0, 0, 0, 0, List.of());
		long requestA = engine.beginRequest();   // a pack load starts
		engine.complete(requestA, first);
		assertSame(first, engine.pendingSet());
		long requestB = engine.beginRequest();   // the player switches the pack; the first load is superseded
		engine.complete(requestA, second);       // the first load finishes late
		assertSame(first, engine.pendingSet());  // dropped: its set never reaches the frame
		DimensionSet late = new DimensionSet(programs, List.of(), 0, 0, 0, 0, 0, 0, List.of());
		engine.complete(requestB, late);
		assertSame(late, engine.pendingSet());   // the newest request's set waits for the next frame
	}

	@Test
	void turningTheEngineOffDropsALoadStillRunning() {
		Engine engine = Engine.get();
		long request = engine.beginRequest();
		DimensionSet set = new DimensionSet(new DimensionPrograms("shaders/ root", Optional.empty(), Optional.of(""),
			"", List.of(), List.of()), List.of(), 0, 0, 0, 0, 0, 0, List.of());
		engine.shutdown();                       // the engine off is itself a new request
		engine.complete(request, set);           // the load finishes after the engine turned off
		assertNull(engine.pendingSet());
	}

	@Test
	void otherDimensionsLandInTheReadyStoreAndASupersededOneNeverReplacesThem() {
		Engine engine = Engine.get();
		engine.foregroundFolder = "shaders/overworld";
		DimensionSet nether = setOf("shaders/nether");
		long request = engine.beginRequest();
		engine.complete(request, nether);        // a background load of the other dimension finished
		assertSame(nether, engine.readySet("shaders/nether"));
		assertNull(engine.pendingSet());         // only the foreground's set waits for the frame
		engine.beginRequest();                   // a pack switch supersedes every older load
		DimensionSet lateNether = setOf("shaders/nether");
		engine.complete(request, lateNether);    // the superseded load finishes late
		assertNull(engine.readySet("shaders/nether"));  // a stored set of a superseded request is never handed out (N3)
	}

	@Test
	void theForegroundLoadWaitsForTheFrameEvenWhileOtherFoldersAreReady() {
		Engine engine = Engine.get();
		engine.foregroundFolder = "shaders/overworld";
		long request = engine.beginRequest();
		DimensionSet overworld = setOf("shaders/overworld");
		engine.complete(request, overworld);
		assertSame(overworld, engine.pendingSet());
		assertNull(engine.readySet("shaders/overworld"));
	}

	@Test
	void turningTheEngineOffReleasesTheReadyStore() {
		Engine engine = Engine.get();
		engine.foregroundFolder = "shaders/overworld";
		long request = engine.beginRequest();
		engine.complete(request, setOf("shaders/nether"));
		engine.shutdown();
		assertNull(engine.readySet("shaders/nether"));
		assertNull(engine.pendingSet());
	}

	@Test
	void aPendingSetSupersededBetweenCompletionAndTheFrameIsReleasedNotInstalled() {
		Engine engine = Engine.get();
		long request = engine.beginRequest();
		engine.complete(request, setOf("shaders/overworld"));
		engine.beginRequest();                   // the check-then-set race: a newer request after completion
		engine.takePendingSet(null);             // the frame: this JVM has no backend at all
		assertNull(engine.pendingSet());
		assertNull(engine.activeSet());
	}

	@Test
	void aPendingSetWithoutADeviceIsReleasedAndTheEngineFailsLoudly() {
		Engine engine = Engine.get();
		long request = engine.beginRequest();
		engine.complete(request, setOf("shaders/overworld"));
		engine.takePendingSet(null);             // no backend in this JVM: pre-fix this returned silently
		assertNull(engine.activeSet());
		assertEquals(Engine.State.FAILED, engine.state());  // and the set's pipelines leaked unseen
	}

	private static DimensionSet setOf(String folder) {
		return new DimensionSet(new DimensionPrograms(folder, Optional.empty(), Optional.of(folder), "", List.of(),
			List.of()), List.of(), 0, 0, 0, 0, 0, 0, List.of());
	}
}
