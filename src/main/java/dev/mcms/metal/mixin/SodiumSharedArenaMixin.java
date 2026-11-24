// Quicksilver: original code, Apache-2.0.
package dev.mcms.metal.mixin;

import net.caffeinemc.mods.sodium.client.gpu.arena.SharedBufferArena;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * RIL-477: a shared arena dies in {@code deleteShared}, called when it has emptied ({@code ArenaAggregator$DataType.update})
 * and for every shared arena when the aggregator is deleted; it closes the arena's buffer and nothing else. This releases
 * the arena's debug-map texture from the {@code TextureManager} as well (see {@code SodiumBufferArenaAccessor}). Removing
 * one owner ({@code deleteSingleOwner} on a shared arena) leaves the arena alive and its texture registered. Applied only
 * when Sodium is loaded and its release is 0.9.2 or a 0.9.3 prerelease ({@code SodiumAdapter.needsArenaLeakWorkaround}).
 */
@Mixin(SharedBufferArena.class)
abstract class SodiumSharedArenaMixin {
	@Inject(method = "deleteShared", at = @At("TAIL"), require = 0)
	private void mcmsMetal$releaseDebugTexture(CallbackInfo ci) {
		Minecraft.getInstance().getTextureManager().release(((SodiumBufferArenaAccessor) (Object) this).mcmsMetal$textureId());
	}
}
