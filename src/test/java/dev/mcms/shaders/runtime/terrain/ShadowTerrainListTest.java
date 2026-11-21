// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderList;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The main lists stay untouched by the light's build (E5-B2; hooks section 4, item 1), and the light's lists follow the
 * loaded regions. The tests use real Sodium {@link RenderRegion} objects (the constructor takes no arena until it draws)
 * and the real answer of {@link ShadowTerrain#answerRenderList}, which the mixin {@code SodiumEngineRenderRegionMixin} calls
 * on every {@code getRenderList}. The build flag is set through {@link ShadowTerrain#setBuilding} instead of a full
 * traversal: {@code ShadowTerrain.build} needs a GPU render region manager, so the traversal itself is not run here.
 */
class ShadowTerrainListTest {

	@AfterEach
	void reset() {
		ShadowTerrain.setBuilding(false);
		ShadowTerrain.get().detach();
	}

	@Test
	void outsideTheBuildTheCameraListIsAnsweredUnchanged() {
		RenderRegion region = new RenderRegion(0, 0, 0, null);
		ChunkRenderList main = region.getRenderList();

		assertSame(main, ShadowTerrain.answerRenderList(region, main));
		assertNull(ShadowTerrain.get().listOf(region), "no light list is made outside a build");
	}

	@Test
	void duringTheBuildTheLightListIsAnsweredAndTheCameraListIsNotTheAnswer() {
		RenderRegion region = new RenderRegion(0, 0, 0, null);
		ChunkRenderList main = region.getRenderList();

		ShadowTerrain.setBuilding(true);
		ChunkRenderList light = ShadowTerrain.answerRenderList(region, main);
		assertNotSame(main, light, "the build must not answer the camera's list");
		assertSame(light, ShadowTerrain.answerRenderList(region, main), "one light list per region");
		assertSame(region, light.getRegion());

		ShadowTerrain.setBuilding(false);
		assertSame(main, ShadowTerrain.answerRenderList(region, main), "the camera's list answers again after the build");
	}

	@Test
	void eachRegionHasItsOwnLightList() {
		RenderRegion first = new RenderRegion(0, 0, 0, null);
		RenderRegion second = new RenderRegion(1, 0, 0, null);

		ShadowTerrain.setBuilding(true);
		ChunkRenderList firstLight = ShadowTerrain.answerRenderList(first, first.getRenderList());
		ChunkRenderList secondLight = ShadowTerrain.answerRenderList(second, second.getRenderList());

		assertNotSame(firstLight, secondLight);
		assertSame(first, firstLight.getRegion());
		assertSame(second, secondLight.getRegion());
	}

	@Test
	void detachingDropsTheLightLists() {
		RenderRegion region = new RenderRegion(0, 0, 0, null);
		ShadowTerrain.setBuilding(true);
		ShadowTerrain.answerRenderList(region, region.getRenderList());
		ShadowTerrain.setBuilding(false);
		assertNotNull(ShadowTerrain.get().listOf(region), "the build made the region's light list");

		ShadowTerrain.get().detach();
		assertNull(ShadowTerrain.get().listOf(region), "detach drops every light list");
	}

	@Test
	void aNewRegionOwnerDropsEveryLightList() {
		RenderRegion first = new RenderRegion(0, 0, 0, null);
		RenderRegion second = new RenderRegion(1, 0, 0, null);
		Object oldManager = new Object();
		Object newManager = new Object();
		ShadowTerrain light = ShadowTerrain.get();
		light.pruneRegions(oldManager, List.of(first, second));
		answer(first, second);
		assertNotNull(light.listOf(first));

		light.pruneRegions(newManager, List.of(first, second));  // a world reload: the regions are new objects

		assertNull(light.listOf(first), "the old manager's regions leave the map");
		assertNull(light.listOf(second), "the old manager's regions leave the map");
	}

	@Test
	void aRegionUnloadedUnderTheSameOwnerIsDroppedAndTheLoadedOnesKeepTheirLists() {
		RenderRegion first = new RenderRegion(0, 0, 0, null);
		RenderRegion second = new RenderRegion(1, 0, 0, null);
		Object manager = new Object();
		ShadowTerrain light = ShadowTerrain.get();
		light.pruneRegions(manager, List.of(first, second));
		answer(first, second);
		ChunkRenderList keptList = light.listOf(first);

		light.pruneRegions(manager, List.of(first));  // the second region unloaded

		assertNull(light.listOf(second), "an unloaded region's light list is dropped");
		assertSame(keptList, light.listOf(first), "a loaded region keeps its own list");
		assertEquals(1, light.listCount());
	}

	/** The build's answer for each region (the build flag set around it), as the traversal makes it. */
	private static void answer(RenderRegion... regions) {
		ShadowTerrain.setBuilding(true);
		try {
			for (RenderRegion region : regions) {
				ShadowTerrain.answerRenderList(region, region.getRenderList());
			}
		} finally {
			ShadowTerrain.setBuilding(false);
		}
	}
}
