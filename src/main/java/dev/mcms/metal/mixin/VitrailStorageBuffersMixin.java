/*
 * MC Metal Shaders, Apache License 2.0. Original code (2026-10-12, M4).
 */
package dev.mcms.metal.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.device.GpuDevice;
import dev.mcms.metal.MetalStorage;
import dev.mcms.metal.vitrail.MetalStorageBuffers;
import dev.vitrail.pack.model.BufferObject;
import dev.vitrail.render.storage.StorageBuffers;
import java.util.Map;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Seam part 2a (spec §5), storage buffers: Vitrail's StorageBuffers allocates a pack's bufferObject. directives through VMA and returns
 * on any other device (StorageBuffers.java:124-128 at 0.12.0-beta). On this backend MetalStorageBuffers allocates and zeroes them, and
 * Vitrail's own binding map is filled with the Metal buffers, so StorageBuffers.bound answers them to MetalRenderPass (graphics) and
 * McmsMetalCompute (compute). The 16-byte placeholder a draw binds under the name stays Vitrail's own. Applied only when Vitrail is
 * loaded.
 */
@Mixin(StorageBuffers.class)
abstract class VitrailStorageBuffersMixin {
	@Shadow
	@Final
	private BufferObject.Reading declared;

	@Shadow
	private Map<Integer, StorageBuffers.Bound> bindings;

	@Unique
	private MetalStorageBuffers mcmsMetal$buffers;

	@Shadow
	abstract void install();

	@Shadow
	private void ensurePlaceholder(GpuDevice device) {
	}

	@Unique
	private MetalStorageBuffers mcmsMetal$buffers() {
		if (this.mcmsMetal$buffers == null) this.mcmsMetal$buffers = new MetalStorageBuffers(this.declared);
		return this.mcmsMetal$buffers;
	}

	@Inject(method = "ensure", at = @At("HEAD"), cancellable = true)
	private void mcmsMetal$ensure(int screenWidth, int screenHeight, CallbackInfo ci) {
		if (!MetalStorage.active()) return;
		ci.cancel();
		this.install();
		if (this.declared.buffers().isEmpty()) return;
		GpuDevice device = RenderSystem.tryGetDevice();
		if (device == null) return;
		this.ensurePlaceholder(device);
		// The map follows the list whatever happens: a refusal halfway through a resize has already released the relative buffers.
		try {
			mcmsMetal$buffers().ensure(screenWidth, screenHeight);
		} finally {
			this.bindings = mcmsMetal$buffers().bindings();
		}
	}

	/** Vitrail's own close still runs after this (current set, placeholder, map). */
	@Inject(method = "close", at = @At("HEAD"))
	private void mcmsMetal$close(CallbackInfo ci) {
		if (this.mcmsMetal$buffers != null) this.mcmsMetal$buffers.close();
	}
}
