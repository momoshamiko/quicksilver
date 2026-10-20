/*
 * MC Metal Shaders, Apache License 2.0. Original code (2026-10-14, RIL-483).
 */
package dev.mcms.metal.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.seibel.distanthorizons.common.wrappers.minecraft.MinecraftRenderWrapper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Distant Horizons 3.3.4 on this backend (RIL-483): {@code MinecraftRenderWrapper.getMcRenderingApi()} answers VULKAN for
 * the backend name "Vulkan", OPEN_GL for every other name, and keeps the first answer. On Metal, OPEN_GL sends DH's
 * {@code MixinLightTexture.render} to a cast of the light map's texture to the OpenGL backend's {@code GlTexture}, which
 * throws on a {@code MetalTexture} as soon as a world draws ({@code results/2026-10-14-pack-on-metal}, run 1). Here the
 * name "Metal" counts as Vulkan too, so DH takes the paths it takes on Vulkan, none of which touches an OpenGL object: the
 * light map reaches DH as a renderpearl {@code GpuTexture} ({@code setLightmapGpuTexture}), and its Blaze3D engine, the
 * default on 26.3, draws through renderpearl, which this backend implements ({@code results/2026-10-14-dh-metal}). DH's
 * OpenGL engine (renderingEngine = OPEN_GL in its config) stops at DH's own check that the engine's API matches the
 * game's, as it does on Vulkan.
 */
@Mixin(MinecraftRenderWrapper.class)
abstract class DistantHorizonsRenderApiMixin {
	@WrapOperation(method = "getMcRenderingApi", at = @At(value = "INVOKE", target = "Ljava/lang/String;equalsIgnoreCase(Ljava/lang/String;)Z"))
	private boolean mcmsMetal$metalCountsAsVulkan(String backendName, String vulkan, Operation<Boolean> original) {
		return original.call(backendName, vulkan) || "Metal".equals(backendName);
	}
}
