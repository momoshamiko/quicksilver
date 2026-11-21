// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.shadow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The shadow frame's failure branch (E5 review item 12): a step that throws drops the frame for the set, detaches once, and
 * the next frame runs nothing. The pass still closes on a failed draw (the sequence's finally blocks).
 */
class ShadowFrameDriverTest {

	private static final ShadowPassSequence.Content ALL = new ShadowPassSequence.Content(true, true, true, true, true);

	/** Records each shadow step's name in order; one step can throw an {@link Error}, as a missing Sodium member does. */
	static final class Recorder implements ShadowFrame.Ops {
		final List<String> steps = new ArrayList<>();
		String failAt;

		private void step(String name) {
			this.steps.add(name);
			if (name.equals(this.failAt)) {
				throw new NoSuchMethodError("failed at " + name);
			}
		}

		@Override public void submitEntities(boolean entities, boolean blockEntities, boolean player) { step("submitEntities"); }
		@Override public void prepareEntities() { step("prepareEntities"); }
		@Override public void clearTargets() { step("clearTargets"); }
		@Override public void openPass() { step("openPass"); }
		@Override public void bindShadowView() { step("bindShadowView"); }
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

	@Test
	void aCleanFrameRunsEveryStepInOrderAndStaysActive() {
		Recorder ops = new Recorder();
		int[] detached = {0};
		ShadowFrameDriver driver = new ShadowFrameDriver(new ShadowFrame(ops), ALL, () -> detached[0]++);

		driver.run();

		assertTrue(driver.active());
		assertEquals(0, detached[0]);
		assertEquals(List.of("submitEntities", "prepareEntities", "clearCachedBatches", "clearTargets", "openPass",
			"bindShadowView", "terrainSolidAndCutout", "entitiesSolid", "copyShadowtex1", "terrainTranslucent",
			"entitiesTranslucent", "closePass", "closePrepared", "clearCachedBatches", "shadowcomp"), ops.steps);
	}

	@Test
	void aThrowingStepDropsTheFrameDetachesOnceAndTheNextFrameRunsNothing() {
		Recorder ops = new Recorder();
		ops.failAt = "openPass";
		int[] detached = {0};
		ShadowFrameDriver driver = new ShadowFrameDriver(new ShadowFrame(ops), ALL, () -> detached[0]++);

		driver.run();

		assertFalse(driver.active(), "the frame must be dropped after the failed step");
		assertEquals(1, detached[0], "the light's lists detach once");
		int stepsAfterFailure = ops.steps.size();
		driver.run();
		driver.run();
		assertEquals(stepsAfterFailure, ops.steps.size(), "a dropped frame runs no step");
		assertEquals(1, detached[0], "the detach does not run again");
	}

	@Test
	void aFailedDrawStillClosesThePassAndThePreparedFrame() {
		Recorder ops = new Recorder();
		ops.failAt = "terrainSolidAndCutout";
		ShadowFrameDriver driver = new ShadowFrameDriver(new ShadowFrame(ops), ALL, () -> { });

		driver.run();

		assertEquals(List.of("submitEntities", "prepareEntities", "clearCachedBatches", "clearTargets", "openPass",
			"bindShadowView", "terrainSolidAndCutout", "closePass", "closePrepared", "clearCachedBatches"), ops.steps);
		assertFalse(driver.active());
	}

	@Test
	void dropRunsTheDetachOnceEvenWhenCalledTwice() {
		int[] detached = {0};
		ShadowFrameDriver driver = new ShadowFrameDriver(new ShadowFrame(new Recorder()), ALL, () -> detached[0]++);

		driver.drop("the shadow pass's open", new IllegalStateException("engine-side failure before the frame ran"));
		driver.drop("the entity list", new IllegalStateException("a second failure in the same frame"));

		assertFalse(driver.active());
		assertEquals(1, detached[0]);
	}
}
