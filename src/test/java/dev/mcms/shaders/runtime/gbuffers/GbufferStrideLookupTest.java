// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.gbuffers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A known pipeline's plan is looked up under the strides of the vertices its draws use. Sodium's terrain pipelines declare
 * their compact 20-byte stride, but once the engine latches its terrain type their meshes are the engine's 44-byte type
 * (the latch's reload rebuilds them), so their plan is the terrain layout's (preview 13). Before the latch, the compact
 * meshes find no plan: the draw is skipped, logged at INFO (an expected skip), and never as a WARN.
 */
class GbufferStrideLookupTest {

	private static final String SODIUM_SOLID = "sodium:pipeline/solid_terrain";
	private static final String GAME_SOLID = "minecraft:pipeline/solid_terrain";

	/** A plan with no variant in any cell: a draw that reaches it is a drop. */
	static GbufferPlans.Plan emptyPlan(String location) {
		return new GbufferPlans.Plan() {
			@Override
			public GbufferPlans.Variant variant(PassKind kind, GbufferPhase phase, GbufferMapping.Domain domain) {
				return null;
			}

			@Override
			public String location() {
				return location;
			}

			@Override
			public String reason(PassKind kind, GbufferPhase phase, GbufferMapping.Domain domain) {
				return "";
			}
		};
	}

	@Test
	void aTwentyByteMeshDoesNotReachTheFortyFourByteLayoutsPlan() {
		GbufferPlans.Plan fortyFour = emptyPlan("solid_terrain");
		GbufferPlans plans = GbufferPlans.forTestLayouts(Map.of(SODIUM_SOLID, Map.of("44", fortyFour)));
		assertNull(plans.planOf(SODIUM_SOLID, new int[] {20}), "a 20-byte mesh must not take the 44-byte plan");
		assertSame(fortyFour, plans.planOf(SODIUM_SOLID, new int[] {44}), "the 44-byte mesh takes its own plan");
	}

	@Test
	void aPipelineWithUnknownStridesTakesItsOnlyLayout() {
		GbufferPlans.Plan fortyFour = emptyPlan("solid_terrain");
		GbufferPlans plans = GbufferPlans.forTestLayouts(Map.of(SODIUM_SOLID, Map.of("44", fortyFour)));
		assertSame(fortyFour, plans.planOf(SODIUM_SOLID, null), "a test that selects by name alone keeps the lone layout");
	}

	@Test
	void aSodiumTerrainPipelineDeclaringTwentyBytesResolvesToTheFortyFourBytePlanWhileTheEngineLatchesItsType() {
		GbufferPlans.Plan fortyFour = emptyPlan("solid_terrain");
		GbufferDelegate delegate = new GbufferDelegate(
			GbufferPlans.forTestLayouts(Map.of(SODIUM_SOLID, Map.of("44", fortyFour))), null);
		assertSame(fortyFour, delegate.resolve(SODIUM_SOLID, new int[] {20}, true).plan,
			"latched: the terrain type's 44-byte meshes take the terrain plan, whatever Sodium declared");
		assertNull(delegate.resolve(SODIUM_SOLID, new int[] {20}, false).plan,
			"before the latch the meshes are compact: the declared stride finds no plan");
	}

	@Test
	void aSodiumTerrainResolutionMovesWithTheLatchAndAGameOneDoesNot() {
		GbufferPlans.Plan fortyFour = emptyPlan("solid_terrain");
		GbufferDelegate delegate = new GbufferDelegate(
			GbufferPlans.forTestLayouts(Map.of(SODIUM_SOLID, Map.of("44", fortyFour), GAME_SOLID, Map.of("", fortyFour))), null);
		GbufferDelegate.Resolved beforeLatch = delegate.resolve(SODIUM_SOLID, new int[] {20}, false);
		assertTrue(beforeLatch.stale(true), "a pipeline resolved before the latch resolves again after it");
		assertFalse(beforeLatch.stale(false));
		assertFalse(delegate.resolve(GAME_SOLID, new int[] {20}, false).stale(true),
			"a game pipeline's strides never depend on the latch");
	}

	@Test
	void aTwentyByteMeshOnTheInstallFrameIsSkippedWithoutAWarning() {
		GbufferPlans plans = GbufferPlans.forTestLayouts(Map.of(SODIUM_SOLID, Map.of("44", emptyPlan("solid_terrain"))));
		GbufferDelegate delegate = new GbufferDelegate(plans, null);
		assertNull(delegate.select(0L, SODIUM_SOLID, null, new int[] {20}, List.of()),
			"the compact mesh draws nothing through the 44-byte plan");
		assertEquals(List.of(), delegate.warnings(), "the skip is expected on the install frame: no WARN");
	}

	@Test
	void aPipelineWithNoPlanAtAllStillWarns() {
		GbufferDelegate delegate = new GbufferDelegate(GbufferPlans.forTestLayouts(Map.of()), null);
		assertNull(delegate.select(0L, "minecraft:pipeline/gui", null, null, List.of()));
		assertEquals(1, delegate.warnings().size(), "a pipeline no plan names is a real drop: one WARN");
	}
}
