// Quicksilver: original code, Apache-2.0.
package dev.mcms.metal.mixin;

import net.caffeinemc.mods.sodium.client.gpu.arena.BufferArena;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The name under which the {@code BufferArena} constructor of every Sodium build for 26.3 before stable 0.9.3 registers its 200x200 debug-map
 * {@code DynamicTexture} in the game's {@code TextureManager}. Sodium never releases that texture, so each arena leaves
 * one behind (RIL-477, results/2026-10-13-m5-texlog); {@code SodiumSingleOwnerArenaMixin} and
 * {@code SodiumSharedArenaMixin} release it when the arena is deleted. Applied only when Sodium is loaded and its release
 * is 0.9.2 or a 0.9.3 prerelease, the builds checked with javap ({@code SodiumAdapter.needsArenaLeakWorkaround}).
 */
@Mixin(BufferArena.class)
public interface SodiumBufferArenaAccessor {
	@Accessor("textureId")
	Identifier mcmsMetal$textureId();
}
