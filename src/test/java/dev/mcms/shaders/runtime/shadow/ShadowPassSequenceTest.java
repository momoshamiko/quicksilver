// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.shadow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The shadow pass's order (plan.md E5, "The pass"): submit and prepare the shadow entities, open the redirected pass,
 * terrain solid and cutout, the entities' solid draws, the {@code shadowtex1} copy, translucent terrain, the entities'
 * translucent draws, close the pass, close the shadow prepared frame, clear the cached batches, run {@code shadowcomp}.
 * The content switches drop their steps (the Ordering page's switches), and whatever a step throws, the pass and the
 * prepared frame still close.
 */
class ShadowPassSequenceTest {

	/** Records each step's name, in order, and what the submit step was given; one step can be made to throw. */
	private static final class Recorder implements ShadowPassSequence.Steps {
		final List<String> log = new ArrayList<>();
		String failAt;
		boolean submittedEntityList;
		boolean submittedBlockEntities;

		private void step(String name) {
			this.log.add(name);
			if (name.equals(this.failAt)) {
				throw new IllegalStateException("draw failed at " + name);
			}
		}

		@Override public void submitEntities(boolean entityList, boolean blockEntities, boolean player) {
			this.submittedEntityList = entityList;
			this.submittedBlockEntities = blockEntities;
			step("submitEntities");
		}
		@Override public void prepareEntities() { step("prepareEntities"); }
		@Override public void clearTargets() { step("clearTargets"); }
		@Override public void openPass() { step("openPass"); }
		@Override public void terrainSolidAndCutout() { step("terrainSolidAndCutout"); }
		@Override public void entitiesSolid() { step("entitiesSolid"); }
		@Override public void copyShadowtex1() { step("copyShadowtex1"); }
		@Override public void terrainTranslucent() { step("terrainTranslucent"); }
		@Override public void entitiesTranslucent() { step("entitiesTranslucent"); }
		@Override public void closePass() { step("closePass"); }
		@Override public void closePrepared() { step("closePrepared"); }
		@Override public void clearCachedBatches() { step("clearCachedBatches"); }
		@Override public void shadowcomp() { step("shadowcomp"); }
	}

	private static final ShadowPassSequence.Content ALL = new ShadowPassSequence.Content(true, true, true, true, true);

	@Test
	void runsTheDocumentedOrder() {
		Recorder r = new Recorder();
		ShadowPassSequence.run(r, ALL);
		assertEquals(List.of("submitEntities", "prepareEntities", "clearCachedBatches", "clearTargets", "openPass",
			"terrainSolidAndCutout", "entitiesSolid", "copyShadowtex1", "terrainTranslucent", "entitiesTranslucent",
			"closePass", "closePrepared", "clearCachedBatches", "shadowcomp"), r.log);
	}

	@Test
	void theTargetsClearJustBeforeEachPassOpens() {
		// Each frame clears the shadow targets right before its pass opens, so no caster of an earlier frame survives.
		for (int frame = 0; frame < 2; frame++) {
			Recorder r = new Recorder();
			ShadowPassSequence.run(r, ALL);
			assertEquals(r.log.indexOf("openPass") - 1, r.log.indexOf("clearTargets"), "frame " + frame);
		}
	}

	@Test
	void theContentSwitchesDropTheirSteps() {
		Recorder r = new Recorder();
		ShadowPassSequence.run(r, new ShadowPassSequence.Content(false, false, false, false, false));
		// The submit, the copy and the closes always run: they are the pass's bookkeeping, not content.
		assertEquals(List.of("submitEntities", "prepareEntities", "clearCachedBatches", "clearTargets", "openPass",
			"copyShadowtex1", "closePass", "closePrepared", "clearCachedBatches", "shadowcomp"), r.log);
	}

	@Test
	void theTerrainSwitchOnlyDropsTerrain() {
		Recorder r = new Recorder();
		ShadowPassSequence.run(r, new ShadowPassSequence.Content(false, true, true, true, true));
		assertFalse(r.log.contains("terrainSolidAndCutout"));
		assertTrue(r.log.contains("terrainTranslucent"));
		assertTrue(r.log.contains("entitiesSolid"));
	}

	@Test
	void contentFromThePackBooleans() {
		ShadowPassSequence.Content c = ShadowPassSequence.Content.of(Map.of("shadowTerrain", false,
			"shadowPlayer", false));
		assertFalse(c.terrain());
		assertTrue(c.translucent());
		assertTrue(c.entities());
		assertTrue(c.blockEntities());
		assertFalse(c.player());
	}

	@Test
	void blockEntitiesKeepTheirDrawsWhenEntitiesAreOff() {
		// shadowBlockEntities has its own switch: shadowEntities=false does not remove the block entities (Ordering page).
		Recorder r = new Recorder();
		ShadowPassSequence.run(r, ShadowPassSequence.Content.of(Map.of("shadowEntities", false, "shadowPlayer", false)));
		assertTrue(r.log.contains("entitiesSolid"));
		assertTrue(r.log.contains("entitiesTranslucent"));
		assertTrue(r.submittedBlockEntities);
		assertFalse(r.submittedEntityList);
	}

	@Test
	void theEntityDrawsStopWhenBothEntitySwitchesAreOff() {
		Recorder r = new Recorder();
		ShadowPassSequence.run(r, ShadowPassSequence.Content.of(Map.of("shadowEntities", false,
			"shadowBlockEntities", false, "shadowPlayer", false)));
		assertFalse(r.log.contains("entitiesSolid"));
		assertFalse(r.log.contains("entitiesTranslucent"));
		assertFalse(r.submittedEntityList);
		assertFalse(r.submittedBlockEntities);
	}

	@Test
	void withEntitiesOffThePlayerAloneIsExtractedAndDrawn() {
		// shadowPlayer on, shadowEntities off: the player and its vehicle only (the list); the entity draws still run.
		Recorder r = new Recorder();
		ShadowPassSequence.run(r, ShadowPassSequence.Content.of(Map.of("shadowEntities", false, "shadowBlockEntities", false,
			"shadowPlayer", true)));
		assertTrue(r.submittedEntityList);
		assertFalse(r.submittedBlockEntities);
		assertTrue(r.log.contains("entitiesSolid"));
		assertTrue(r.log.contains("entitiesTranslucent"));
	}

	@Test
	void shadowPlayerIsForcedOnWhileShadowEntitiesIsOn() {
		// The Ordering page: shadowPlayer "is forced on if shadowEntities is enabled".
		ShadowPassSequence.Content c = ShadowPassSequence.Content.of(Map.of("shadowPlayer", false));
		assertFalse(c.player());
		assertTrue(c.entityList());
	}

	@Test
	void theEntityListIsEmptyWhenEntitiesAndPlayerAreOff() {
		ShadowPassSequence.Content c = ShadowPassSequence.Content.of(Map.of("shadowEntities", false, "shadowPlayer", false));
		assertFalse(c.entityList());
		assertTrue(c.entityDraws(), "block entities still draw (their own switch)");
	}

	@Test
	void aThrowingDrawStillClosesThePassAndThePreparedFrame() {
		Recorder r = new Recorder();
		r.failAt = "entitiesTranslucent";
		assertThrows(IllegalStateException.class, () -> ShadowPassSequence.run(r, ALL));
		assertEquals(List.of("closePass", "closePrepared", "clearCachedBatches"),
			r.log.subList(r.log.size() - 3, r.log.size()));
		assertFalse(r.log.contains("shadowcomp"));
	}

	@Test
	void aFailedEntitySubmitOpensNothingAndStillCloses() {
		Recorder r = new Recorder();
		r.failAt = "submitEntities";
		assertThrows(IllegalStateException.class, () -> ShadowPassSequence.run(r, ALL));
		assertEquals(List.of("submitEntities", "closePrepared", "clearCachedBatches"), r.log);
	}
}
