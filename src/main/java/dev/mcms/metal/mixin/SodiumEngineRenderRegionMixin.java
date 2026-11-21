// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.metal.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.mcms.shaders.runtime.terrain.ShadowTerrain;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderList;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Answers {@code RenderRegion.getRenderList} with the light's list while the light's terrain list is built (E5-B2;
 * hooks section 4, table row 10). Outside the build the region's own list is returned unchanged, so the camera's lists are
 * untouched. The hooked member is {@code getRenderList()Lnet/caffeinemc/mods/sodium/client/render/chunk/lists/ChunkRenderList;},
 * a single {@code aload_0; getfield renderList; areturn} (bytecode 0-4), identical on beta.1 and beta.5 (javap 2026-11-14).
 */
@Mixin(RenderRegion.class)
abstract class SodiumEngineRenderRegionMixin {
	@ModifyReturnValue(method = "getRenderList", at = @At("RETURN"))
	private ChunkRenderList mcmsMetal$lightRenderList(ChunkRenderList main) {
		return ShadowTerrain.answerRenderList((RenderRegion) (Object) this, main);
	}
}
