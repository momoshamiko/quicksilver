package dev.mcms.metal.mixin;

import net.caffeinemc.mods.sodium.client.gpu.arena.BufferArena;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The name under which Sodium 0.9.3-alpha.1's {@code BufferArena} constructor registers its 200x200 debug-map
 * {@code DynamicTexture} in the game's {@code TextureManager}. Sodium never releases that texture, so each arena leaves
 * one behind (RIL-477, results/2026-10-13-m5-texlog); {@code SodiumSingleOwnerArenaMixin} and
 * {@code SodiumSharedArenaMixin} release it when the arena is deleted. Applied only when Sodium is loaded.
 */
@Mixin(BufferArena.class)
public interface SodiumBufferArenaAccessor {
	@Accessor("textureId")
	Identifier mcmsMetal$textureId();
}
