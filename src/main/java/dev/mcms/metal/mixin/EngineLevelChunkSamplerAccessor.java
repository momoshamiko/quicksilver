// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.metal.mixin;

import com.mojang.renderpearl.api.textures.GpuSampler;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The sampler the game draws its chunk layers with ({@code LevelRenderer.chunkLayerSampler}, private; javap of
 * minecraft-merged-deobf-26.3.jar, 2026-11-14): clamp to edge, linear filtering, the full mip chain and the options'
 * anisotropy, made in the main pass ({@code lambda$addMainPass$0}) when it is null or the options ask for a new one. The
 * shadow pass samples the block atlas with it, as the camera's terrain does (E5). Null before the first main pass.
 * Gated in {@code McmsMetalMixinPlugin} like the other engine mixins.
 */
@Mixin(LevelRenderer.class)
public interface EngineLevelChunkSamplerAccessor {
	@Accessor("chunkLayerSampler")
	GpuSampler mcmsMetal$chunkLayerSampler();
}
