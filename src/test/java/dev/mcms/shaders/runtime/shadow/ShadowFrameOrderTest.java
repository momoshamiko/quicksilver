// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.shadow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The shadow frame's real steps ({@link ShadowFrame}) through the real sequence ({@link ShadowPassSequence}), against a
 * recording backend: the order the game would run, the content switches, the cleanup after a failure. No device.
 */
final class ShadowFrameOrderTest {

	private static final ShadowPassSequence.Content ALL = new ShadowPassSequence.Content(true, true, true, true, true);

	/** Records each backend operation in the order it is called. */
	private static final class Recorder implements ShadowFrame.Ops {
		final List<String> calls = new ArrayList<>();
		/** When set, the named operation throws (the failure cases). */
		String failAt;

		private void op(String name) {
			this.calls.add(name);
			if (name.equals(this.failAt)) {
				throw new IllegalStateException("fake " + name);
			}
		}

		@Override public void submitEntities(boolean entities, boolean blockEntities, boolean player) {
			op("submitEntities(" + entities + "," + blockEntities + "," + player + ")");
		}
		@Override public void prepareEntities() { op("prepareEntities"); }
		@Override public void clearTargets() { op("clearTargets"); }
		@Override public void openPass() { op("openPass"); }
		@Override public void bindShadowView() { op("bindShadowView"); }
		@Override public void terrainSolidAndCutout() { op("terrainSolidAndCutout"); }
		@Override public void entitiesSolid() { op("entitiesSolid"); }
		@Override public void copyShadowtex1() { op("copyShadowtex1"); }
		@Override public void terrainTranslucent() { op("terrainTranslucent"); }
		@Override public void entitiesTranslucent() { op("entitiesTranslucent"); }
		@Override public void closePass() { op("closePass"); }
		@Override public void closePrepared() { op("closePrepared"); }
		@Override public void clearCachedBatches() { op("clearCachedBatches"); }
		@Override public void shadowcomp() { op("shadowcomp"); }
	}

	@Test
	void documentedOrderWithEverySwitchOn() {
		Recorder r = new Recorder();
		new ShadowFrame(r).run(ALL);
		assertEquals(List.of(
			"submitEntities(true,true,true)",
			"prepareEntities",
			"clearCachedBatches",
			"clearTargets",
			"openPass",
			"bindShadowView",
			"terrainSolidAndCutout",
			"entitiesSolid",
			"copyShadowtex1",
			"terrainTranslucent",
			"entitiesTranslucent",
			"closePass",
			"closePrepared",
			"clearCachedBatches",
			"shadowcomp"), r.calls);
	}

	@Test
	void theCopyLiesAfterEveryOpaqueCasterAndBeforeTheTranslucentOnes() {
		// plan.md E5: shadowtex1 is copied after the opaque casters (terrain and entities), before the translucents.
		Recorder r = new Recorder();
		new ShadowFrame(r).run(ALL);
		int copy = r.calls.indexOf("copyShadowtex1");
		assertTrue(r.calls.indexOf("terrainSolidAndCutout") < copy);
		assertTrue(r.calls.indexOf("entitiesSolid") < copy);
		assertTrue(copy < r.calls.indexOf("terrainTranslucent"));
		assertTrue(copy < r.calls.indexOf("entitiesTranslucent"));
	}

	@Test
	void contentSwitchesSkipTheirDraws() {
		Recorder r = new Recorder();
		new ShadowFrame(r).run(new ShadowPassSequence.Content(false, false, false, false, false));
		assertEquals(List.of(
			"submitEntities(false,false,false)",
			"prepareEntities",
			"clearCachedBatches",
			"clearTargets",
			"openPass",
			"bindShadowView",
			"copyShadowtex1",
			"closePass",
			"closePrepared",
			"clearCachedBatches",
			"shadowcomp"), r.calls);
	}

	@Test
	void aFailedDrawClosesWhatItOpenedAndSkipsShadowcomp() {
		Recorder r = new Recorder();
		r.failAt = "terrainSolidAndCutout";
		ShadowFrame frame = new ShadowFrame(r);
		assertThrows(IllegalStateException.class, () -> frame.run(ALL));
		assertEquals(List.of(
			"submitEntities(true,true,true)",
			"prepareEntities",
			"clearCachedBatches",
			"clearTargets",
			"openPass",
			"bindShadowView",
			"terrainSolidAndCutout",
			"closePass",
			"closePrepared",
			"clearCachedBatches"), r.calls);
		assertFalse(r.calls.contains("shadowcomp"));
	}

	@Test
	void aFailedSubmitStillClosesThePreparedFrameAndTheBatches() {
		// Nothing was opened yet: the prepared frame and the cached batches still close, no pass does.
		Recorder r = new Recorder();
		r.failAt = "prepareEntities";
		ShadowFrame frame = new ShadowFrame(r);
		assertThrows(IllegalStateException.class, () -> frame.run(ALL));
		assertEquals(List.of(
			"submitEntities(true,true,true)",
			"prepareEntities",
			"closePrepared",
			"clearCachedBatches"), r.calls);
	}
}
