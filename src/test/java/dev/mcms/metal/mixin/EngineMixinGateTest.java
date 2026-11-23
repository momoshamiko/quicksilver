// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.metal.mixin;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The gate of the shader engine's own mixins ({@link McmsMetalMixinPlugin#engineMixinApplies}, plan.md E4): every
 * {@code Engine*} hook applies, and the Sodium hooks only on ADR 0007's allow-list. The plugin asks Sodium's release word;
 * these cases pass the answer in.
 */
class EngineMixinGateTest {

	@Test
	void theEnginesOwnHooksApplyWithOrWithoutSodium() {
		for (String name : new String[] {"EngineLevelClearMixin", "EngineLevelPhaseMixin", "EngineLevelTransparencyMixin",
			"EngineHandGameRendererMixin", "EngineGameRendererMixin", "EngineResourceReloadMixin",
			"EngineSubmitItemFeatureRendererMixin", "EngineSubmitNodeCollectionMixin", "EngineSubmitStagedVertexBufferMixin"}) {
			assertTrue(McmsMetalMixinPlugin.engineMixinApplies(name, null), name + " without Sodium");
			assertTrue(McmsMetalMixinPlugin.engineMixinApplies(name, "0.9.3-beta.5"), name + " with Sodium");
		}
	}

	@Test
	void theSodiumHooksApplyOnlyOnTheAllowList() {
		assertTrue(McmsMetalMixinPlugin.engineMixinApplies("SodiumEngineStartMixin", "0.9.3-beta.1"));
		assertTrue(McmsMetalMixinPlugin.engineMixinApplies("SodiumEngineGlobalUniformsMixin", "0.9.3-beta.5"));
		assertTrue(McmsMetalMixinPlugin.engineMixinApplies("SodiumEngineGlobalUniformsMixin", "0.9.3+mc26.3"));
		assertTrue(McmsMetalMixinPlugin.engineMixinApplies("SodiumEngineStartMixin", "0.9.3-beta.1+mc26.3"),
			"the build part after + does not decide");
		assertFalse(McmsMetalMixinPlugin.engineMixinApplies("SodiumEngineStartMixin", "0.9.3-beta.2"),
			"an unchecked release keeps the engine's Sodium hooks off");
		assertFalse(McmsMetalMixinPlugin.engineMixinApplies("SodiumEngineStartMixin", null),
			"without Sodium there is nothing to hook");
	}

	@Test
	void namesThatAreNotTheEnginesOwnGetFalse() {
		assertFalse(McmsMetalMixinPlugin.engineMixinApplies("SodiumDrawBackendMixin", "0.9.3-beta.1"));
		assertFalse(McmsMetalMixinPlugin.engineMixinApplies("DistantHorizonsShadeMixin", "0.9.3-beta.1"));
	}

	@Test
	void allMixinsStayOffWhenMetalIsUnavailableOrDisabled() {
		for (String name : new String[] {
			"PreferredGraphicsApiMixin", "SodiumDrawBackendMixin", "SodiumSharedArenaMixin",
			"EngineGameRendererMixin", "SodiumEngineStartMixin",
			"DistantHorizonsRenderApiMixin", "DistantHorizonsShadeMixin"
		}) {
			assertFalse(McmsMetalMixinPlugin.mixinApplies(name, "0.9.3-beta.1", "3.3.4", true, true, false),
				name + " must not apply when Metal is blocked or disabled");
		}
	}

	@Test
	void distantHorizonsRenderApiDecoupledFromEngineVersionPin() {
		assertTrue(McmsMetalMixinPlugin.mixinApplies("DistantHorizonsRenderApiMixin", "0.9.3-beta.1", "3.3.5", true, true, true));
		assertFalse(McmsMetalMixinPlugin.mixinApplies("DistantHorizonsRenderApiMixin", "0.9.3-beta.1", null, true, false, true));
		assertTrue(McmsMetalMixinPlugin.mixinApplies("DistantHorizonsShadeMixin", "0.9.3-beta.1", "3.3.4+mc26.3", true, true, true));
		assertFalse(McmsMetalMixinPlugin.mixinApplies("DistantHorizonsShadeMixin", "0.9.3-beta.1", "3.3.5", true, true, true));
	}

	@Test
	void optionalSodiumArenaLeakMixinsAreGatedToCheckedReleases() {
		assertTrue(McmsMetalMixinPlugin.mixinApplies("SodiumSharedArenaMixin", "0.9.3-beta.1", null, true, false, true));
		assertTrue(McmsMetalMixinPlugin.mixinApplies("SodiumSingleOwnerArenaMixin", "0.9.3-beta.5", null, true, false, true));
		assertFalse(McmsMetalMixinPlugin.mixinApplies("SodiumSharedArenaMixin", "0.9.3+mc26.3", null, true, false, true),
			"Sodium 0.9.3 fixed the BufferArena debug texture leak upstream (ADR 0009)");
		assertFalse(McmsMetalMixinPlugin.mixinApplies("SodiumSingleOwnerArenaMixin", "0.9.3", null, true, false, true));
		assertFalse(McmsMetalMixinPlugin.mixinApplies("SodiumBufferArenaAccessor", "0.9.3", null, true, false, true));
		assertFalse(McmsMetalMixinPlugin.mixinApplies("SodiumSharedArenaMixin", "0.9.3-beta.2", null, true, false, true));
		assertFalse(McmsMetalMixinPlugin.mixinApplies("SodiumBufferArenaAccessor", "0.9.4", null, true, false, true));
		assertTrue(McmsMetalMixinPlugin.mixinApplies("SodiumDrawBackendMixin", "0.9.3", null, true, false, true));
		assertTrue(McmsMetalMixinPlugin.mixinApplies("SodiumDrawBackendMixin", "0.9.4", null, true, false, true));
	}
}
