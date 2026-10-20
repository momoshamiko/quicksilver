package dev.mcms.metal.mixin;

import dev.vitrail.HostReport;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Seam part 1 (spec v0.2 §5), from the M0c probe (bench/probe/mod .../HostReportMixin.java): every engine gate in
 * Vitrail 0.12.0-beta asks {@code HostReport.otherBackend()}, which answers true for any backend not named "Vulkan".
 * On this mod's Metal backend it answers false, so the engine runs. Nothing else changes: Vitrail's Vulkan-only
 * helpers still return null off Vulkan (seam part 2 is not in this mod).
 */
@Mixin(HostReport.class)
abstract class VitrailHostReportMixin {
	@Inject(method = "otherBackend", at = @At("HEAD"), cancellable = true)
	private static void mcmsMetal$metalRunsTheEngine(CallbackInfoReturnable<Boolean> cir) {
		if ("Metal".equals(HostReport.backend())) {
			cir.setReturnValue(false);
		}
	}
}
