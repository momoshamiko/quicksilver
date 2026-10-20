/*
 * MC Metal Shaders, Apache License 2.0. Original code (2026-10-12, M4).
 */
package dev.mcms.metal.mixin;

import com.mojang.renderpearl.api.commands.CommandEncoder;
import dev.mcms.metal.MetalStorage;
import dev.vitrail.render.storage.GpuRecording;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Seam part 2a (spec §5): Vitrail's two recording helpers that every storage and compute road goes through. endPass closes the open
 * render pass before a clear, fill or dispatch; destroyLater defers a destruction until the frames still using the object are done,
 * through Vulkan's queue, and on any other device drops it (GpuRecording.java:47-55 at 0.12.0-beta), which on Metal leaked every
 * compute pass's uniform ring at each pack reload. Both go to this backend's encoder here. Applied only when Vitrail is loaded.
 */
@Mixin(GpuRecording.class)
abstract class VitrailGpuRecordingMixin {
	@Inject(method = "endPass", at = @At("HEAD"), cancellable = true)
	private static void mcmsMetal$endPass(CommandEncoder encoder, CallbackInfo ci) {
		if (MetalStorage.active()) {
			ci.cancel();
			MetalStorage.endPass();
		}
	}

	@Inject(method = "destroyLater", at = @At("HEAD"), cancellable = true)
	private static void mcmsMetal$destroyLater(Runnable destruction, CallbackInfo ci) {
		if (MetalStorage.active()) {
			ci.cancel();
			MetalStorage.afterGpu(destruction);
		}
	}
}
