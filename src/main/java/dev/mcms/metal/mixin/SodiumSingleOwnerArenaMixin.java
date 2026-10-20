package dev.mcms.metal.mixin;

import net.caffeinemc.mods.sodium.client.gpu.arena.RegionAllocatorHandle;
import net.caffeinemc.mods.sodium.client.gpu.arena.SingleOwnerBufferArena;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * RIL-477: a dedicated (single-owner) arena dies when its one region lets go of it; {@code deleteSingleOwner} closes the
 * arena's buffer and nothing else. This releases the arena's debug-map texture from the {@code TextureManager} as well,
 * which closes its GPU texture and its CPU pixels (see {@code SodiumBufferArenaAccessor}). Applied only when Sodium is loaded.
 */
@Mixin(SingleOwnerBufferArena.class)
abstract class SodiumSingleOwnerArenaMixin {
	@Inject(method = "deleteSingleOwner", at = @At("TAIL"))
	private void mcmsMetal$releaseDebugTexture(RegionAllocatorHandle owner, CallbackInfo ci) {
		Minecraft.getInstance().getTextureManager().release(((SodiumBufferArenaAccessor) (Object) this).mcmsMetal$textureId());
	}
}
