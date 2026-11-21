// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcms.shaders.pack.DimensionPrograms;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The ready store's rules without a device (the review's N3): a stored set belongs to the generation it was loaded
 * for, a newer generation releases it instead of handing it out, and turning the engine off releases what it holds
 * once. The sets are empty (no pipelines), so the releases are plain calls to the counting releaser.
 */
class EngineStoreTest {

	private static DimensionSet setOf(String folder) {
		DimensionPrograms programs = new DimensionPrograms(folder, Optional.empty(), Optional.of(folder), "", List.of(),
			List.of());
		return new DimensionSet(programs, List.of(), 0, 0, 0, 0, 0, 0, List.of());
	}

	@Test
	void aStoredSetOfAnOlderGenerationIsReleasedNotHandedOut() {
		Engine engine = new Engine();
		List<DimensionSet> released = new ArrayList<>();
		engine.setReleaser(released::add);
		engine.foregroundFolder = "";
		DimensionSet nether = setOf("world-1");
		long gen = engine.beginRequest();
		engine.complete(gen, nether);
		assertSame(nether, engine.readySet("world-1"), "a set of the current generation is stored");
		engine.beginRequest();  // a newer request moves the generation; the store is not cleared
		assertNull(engine.readySet("world-1"), "a set of an older generation is never handed out");
		assertEquals(List.of(nether), released, "and it is released, once");
		engine.shutdown();
	}

	@Test
	void turningTheEngineOffReleasesEachStoredSetOnce() {
		Engine engine = new Engine();
		List<DimensionSet> released = new ArrayList<>();
		engine.setReleaser(released::add);
		engine.foregroundFolder = "";
		DimensionSet nether = setOf("world-1");
		DimensionSet end = setOf("world1");
		long gen = engine.beginRequest();
		engine.complete(gen, nether);
		engine.complete(gen, end);
		engine.shutdown();
		assertNull(engine.readySet("world-1"));
		assertNull(engine.readySet("world1"));
		assertEquals(2, released.size(), "each stored set is released once");
		assertTrue(released.contains(nether) && released.contains(end), "both stored sets are released");
	}

	@Test
	void aLateLoadOfAnOlderGenerationLeavesTheMarkerOfANewerRequest() {
		Engine engine = new Engine();
		engine.setReleaser(set -> {});
		engine.foregroundFolder = "";
		long old = engine.beginRequest();
		engine.loading.put("world-1", old);     // the old load of the folder is in flight
		engine.shutdown();                      // turning off clears the markers and moves the generation
		long current = engine.beginRequest();
		engine.loading.put("world-1", current); // a new load of the same folder is in flight
		engine.complete(old, setOf("world-1")); // the old load finishes late
		assertEquals(current, engine.loading.get("world-1"), "the late load removes only its own marker (D3)");
	}
}
