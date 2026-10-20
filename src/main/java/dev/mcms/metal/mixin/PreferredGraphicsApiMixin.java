/*
 * Derived from mcopt v0.3.0-alpha.2 (https://github.com/noahdunnagan/mcopt), metal/src/main/java/mcopt/metal/mixin/PreferredGraphicsApiMixin.java.
 * Copyright Noah Dunnagan, Apache License 2.0. Changed by MC Metal Shaders, 2026-10-11: package renamed to dev.mcms.metal.mixin;
 * the opt-out property is now -Dmcms.metal=false. 2026-10-20 (RIL-497): Metal is left out where MetalSupport says it cannot run.
 */
package dev.mcms.metal.mixin;

import com.mojang.renderpearl.api.device.GpuBackend;
import dev.mcms.metal.MetalBackend;
import dev.mcms.metal.MetalSupport;
import net.minecraft.client.PreferredGraphicsApi;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Metal goes first in every backend list; OpenGL and Vulkan stay behind it as fallbacks. -Dmcms.metal=false opts out, and where
 * Metal cannot run (MetalSupport: not macOS 15+ on Apple silicon, or Iris installed) the list stays the game's own.
 */
@Mixin(PreferredGraphicsApi.class)
abstract class PreferredGraphicsApiMixin {
	@Inject(method = "getBackendsToTry", at = @At("RETURN"), cancellable = true)
	private void mcms$metalFirst(CallbackInfoReturnable<GpuBackend[]> cir) {
		if (!Boolean.parseBoolean(System.getProperty("mcms.metal", "true")) || MetalSupport.blocker() != null) return;
		GpuBackend[] fallbacks = cir.getReturnValue();
		GpuBackend[] backends = new GpuBackend[fallbacks.length + 1];
		backends[0] = new MetalBackend();
		System.arraycopy(fallbacks, 0, backends, 1, fallbacks.length);
		cir.setReturnValue(backends);
	}
}
