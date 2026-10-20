/*
 * MC Metal Shaders, Apache License 2.0. Original code (2026-10-12, M4).
 */
package dev.mcms.metal.mixin;

import com.mojang.renderpearl.api.commands.CommandEncoder;
import dev.mcms.metal.MetalStorage;
import dev.mcms.metal.vitrail.MetalStorageImages;
import dev.vitrail.pack.model.ImageInformation;
import dev.vitrail.render.storage.StorageImages;
import java.util.Collection;
import java.util.List;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Seam part 2a (spec §5), storage images: Vitrail's StorageImages allocates a pack's image. directives through VMA and records their
 * clears and moves into Vulkan's command buffer, and on any other device returns without allocating anything (StorageImages.java:
 * 247-250, 360-363, 478-481 at 0.12.0-beta), so on Metal every program declaring one read garbage or nothing. On this backend each
 * of those roads goes to MetalStorageImages, and the bound views Vitrail's lookup answers are Metal textures, which MetalRenderPass
 * and McmsMetalCompute bind. Vitrail keeps what is its own: the declaration, the movable rule, refusedForGood, the current set.
 * Applied only when Vitrail is loaded.
 */
@Mixin(StorageImages.class)
abstract class VitrailStorageImagesMixin {
	@Shadow
	@Final
	private ImageInformation.Reading declared;

	@Shadow
	private boolean refusedForGood;

	@Unique
	private MetalStorageImages mcmsMetal$images;

	@Shadow
	abstract void install();

	@Shadow
	private boolean movable(ImageInformation image) {
		return false;
	}

	@Unique
	private MetalStorageImages mcmsMetal$images() {
		if (this.mcmsMetal$images == null) this.mcmsMetal$images = new MetalStorageImages(this.declared);
		return this.mcmsMetal$images;
	}

	@Inject(method = "ensure", at = @At("HEAD"), cancellable = true)
	private void mcmsMetal$ensure(int screenWidth, int screenHeight, CallbackInfo ci) {
		if (!MetalStorage.active()) return;
		ci.cancel();
		this.install();
		mcmsMetal$images().ensure(screenWidth, screenHeight, this::movable, () -> this.refusedForGood = true);
	}

	@Inject(method = "lookup", at = @At("HEAD"), cancellable = true)
	private void mcmsMetal$lookup(String name, CallbackInfoReturnable<StorageImages.Bound> cir) {
		if (this.mcmsMetal$images != null) cir.setReturnValue(this.mcmsMetal$images.lookup(name));
	}

	@Inject(method = "clearMarked", at = @At("HEAD"), cancellable = true)
	private void mcmsMetal$clearMarked(CommandEncoder encoder, CallbackInfo ci) {
		if (!MetalStorage.active()) return;
		ci.cancel();
		mcmsMetal$images().clearMarked();
	}

	@Inject(method = "reanchor", at = @At("HEAD"), cancellable = true)
	private void mcmsMetal$reanchor(CommandEncoder encoder, int dx, int dy, int dz, CallbackInfo ci) {
		if (!MetalStorage.active()) return;
		ci.cancel();
		mcmsMetal$images().reanchor(dx, dy, dz);
	}

	@Inject(method = "count", at = @At("HEAD"), cancellable = true)
	private void mcmsMetal$count(CallbackInfoReturnable<Integer> cir) {
		if (this.mcmsMetal$images != null) cir.setReturnValue(this.mcmsMetal$images.count());
	}

	/** Vitrail's own close still runs after this (it resets the current set and its own empty maps). */
	@Inject(method = "close", at = @At("HEAD"))
	private void mcmsMetal$close(CallbackInfo ci) {
		if (this.mcmsMetal$images != null) this.mcmsMetal$images.close();
	}

	@Inject(method = "unusableViews", at = @At("HEAD"), cancellable = true)
	private static void mcmsMetal$unusableViews(Collection<String> names, CallbackInfoReturnable<List<String>> cir) {
		if (MetalStorage.active()) cir.setReturnValue(MetalStorageImages.unusableViews(names));
	}
}
