/*
 * MC Metal Shaders, Apache License 2.0. Original code (2026-10-12, M4).
 */
package dev.mcms.metal.mixin;

import com.mojang.renderpearl.api.GpuFormat;
import dev.mcms.metal.MetalStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Seam part 2b (spec §5), one format question: Vitrail asks the Vulkan device what a format can do (GpuFormats.feature) and answers
 * the absent default on any other device. For VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT the default is no, so on Metal no colour target was
 * created writable and a compute writing one as colorimgN failed. On this backend that one bit gets Metal's answer (MetalStorage.
 * writable, the Write capability MoltenVK turns into the same bit on this Mac). The linear-filter and blit bits keep their absent
 * defaults, the answers M1 to M3 measured parity under. Applied only when Vitrail is loaded.
 */
@Mixin(targets = "dev.vitrail.render.GpuFormats")
abstract class VitrailGpuFormatsMixin {
	@Inject(method = "feature", at = @At("HEAD"), cancellable = true)
	private static void mcmsMetal$feature(GpuFormat format, int bit, boolean absent, CallbackInfoReturnable<Boolean> cir) {
		if (bit == 0x2 /* VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT */ && MetalStorage.active()) {
			cir.setReturnValue(MetalStorage.writable(format));
		}
	}
}
