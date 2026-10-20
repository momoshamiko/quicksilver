/*
 * MC Metal Shaders, Apache License 2.0. Original code (2026-10-12, M4).
 */
package dev.mcms.metal.mixin;

import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.mcms.metal.MetalStorage;
import dev.mcms.metal.vitrail.ComputeAccess;
import dev.vitrail.pack.target.TargetSchedule;
import dev.vitrail.render.McmsMetalCompute;
import dev.vitrail.render.PackValues;
import java.util.List;
import java.util.Set;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Seam part 2a (spec §5), compute: Vitrail's PackCompute dispatches the pack's compute passes through Vulkan and returns without a
 * word on any other device (PackCompute.java:419-423, :472-476 at 0.12.0-beta). On this backend both roads (the shadow computes at
 * the head of the frame, the computes chained before a pass or standing alone) go to McmsMetalCompute instead, which records them on
 * Metal. Applied only when Vitrail is loaded.
 */
@Mixin(targets = "dev.vitrail.render.PackCompute")
abstract class VitrailPackComputeMixin implements ComputeAccess.Computes {
	@Shadow
	@Final
	private List<Object> passes;

	@Shadow
	private boolean announced;

	@Shadow
	@Final
	private Set<String> announcedChains;

	@Inject(method = "dispatch", at = @At("HEAD"), cancellable = true)
	private void mcmsMetal$dispatch(PackValues values, @Coerce Object targets, CallbackInfo ci) {
		if (MetalStorage.active()) {
			ci.cancel();
			McmsMetalCompute.dispatch(this, values, targets);
		}
	}

	@Inject(method = "dispatchChain", at = @At("HEAD"), cancellable = true)
	private void mcmsMetal$dispatchChain(List<?> attached, String program, CommandEncoder encoder, GpuDevice device, PackValues values,
			@Coerce Object targets, TargetSchedule.Bound step, GpuTextureView depth, GpuTextureView distant, int width, int height,
			CallbackInfo ci) {
		if (MetalStorage.active()) {
			ci.cancel();
			McmsMetalCompute.dispatchChain(this, attached, program, encoder, values, targets, step, depth, distant, width, height);
		}
	}

	@Override
	public List<?> mcmsMetal$passes() {
		return this.passes;
	}

	@Override
	public boolean mcmsMetal$announced() {
		return this.announced;
	}

	@Override
	public void mcmsMetal$announced(boolean announced) {
		this.announced = announced;
	}

	@Override
	public Set<String> mcmsMetal$announcedChains() {
		return this.announcedChains;
	}
}
