package dev.mcms.metal.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.device.DeviceFeatures;
import com.mojang.renderpearl.api.device.DeviceInfo;
import net.caffeinemc.mods.sodium.client.gpu.device.backend.DrawBackend;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Seam part 4 (spec v0.2 §5), from the M0c probe (bench/probe/mod .../DrawBackendMixin.java): Sodium 0.9.3-alpha.1's
 * {@code DrawBackend.chooseBackend()} answers OPENGL for every device that is not a {@code VulkanDevice}. On this mod's
 * Metal device it answers the Vulkan draw path instead, chosen by the same feature flags Sodium reads for Vulkan. Its
 * batches use Vulkan only for struct layouts and draw through renderpearl's {@code RenderPass.multiDrawIndexed} /
 * {@code drawIndexedIndirect}, which the Metal backend implements.
 */
@Mixin(DrawBackend.class)
abstract class SodiumDrawBackendMixin {
	@Inject(method = "chooseBackend", at = @At("HEAD"), cancellable = true)
	private static void mcmsMetal$vulkanPathOnMetal(CallbackInfoReturnable<DrawBackend> cir) {
		DeviceInfo info = RenderSystem.getDevice().getDeviceInfo();
		if (!"Metal".equals(info.backendName())) {
			return;
		}
		DeviceFeatures features = info.features();
		DrawBackend chosen = features.multiDrawDirectInterleaved() ? DrawBackend.VK_MULTIDRAW
			: features.multiDrawIndirect() ? DrawBackend.VK_INDIRECT : null;
		System.out.println("[mcms-metal] Sodium draw backend on Metal: " + (chosen == null ? "unchanged (no multi-draw feature)" : chosen));
		if (chosen != null) {
			cir.setReturnValue(chosen);
		}
	}
}
