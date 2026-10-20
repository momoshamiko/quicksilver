/*
 * Part of MC Metal Shaders (mcms-metal), 2026-10-12 (M4). The numbering below is Vitrail 0.12.0-beta's own
 * (common/src/mc26.3/java/dev/vitrail/render/ComputeShader.java, compile, lines 91-114), Copyright the Vitrail authors,
 * LGPL-3.0-only; this file is therefore LGPL-3.0-only (see mod/mcms-metal/README.md, licences).
 */
package dev.mcms.metal.mixin;

import com.mojang.renderpearl.backend.api.SpvModule;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import dev.mcms.metal.MetalStorage;
import dev.mcms.metal.vitrail.ComputeSpirv;
import dev.vitrail.render.ComputeShader;
import java.util.ArrayList;
import java.util.List;
import org.lwjgl.util.spvc.Spvc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Seam part 2a (spec §5), compute: Vitrail's ComputeShader numbers a compute module's descriptors into one set and creates a Vulkan
 * shader module from it. On Metal (McmsMetalCompute passes no Vulkan device) the numbering runs unchanged and the numbered SPIR-V is
 * kept for MetalCompute to translate, in place of the Vulkan module; the module handle answered is 0, which nothing reads on Metal.
 */
@Mixin(ComputeShader.class)
abstract class VitrailComputeShaderMixin {
	@Inject(method = "compile", at = @At("HEAD"), cancellable = true)
	private static void mcmsMetal$numberForMetal(VulkanDevice vulkan, SpvModule module, CallbackInfoReturnable<ComputeShader.Compiled> cir)
			throws Exception {
		if (vulkan != null) {
			return;
		}

		// No device at all (the Metal device closed between the dispatch's check and this build): no module, as when shaderc refuses one.
		if (!MetalStorage.active()) {
			cir.setReturnValue(null);
			return;
		}

		SpvModule.Reflection reflection = module.reflect();
		List<ComputeShader.Binding> bindings = new ArrayList<>();
		int binding = 0;
		for (int type : new int[] {Spvc.SPVC_RESOURCE_TYPE_UNIFORM_BUFFER, Spvc.SPVC_RESOURCE_TYPE_STORAGE_BUFFER,
				Spvc.SPVC_RESOURCE_TYPE_STORAGE_IMAGE, Spvc.SPVC_RESOURCE_TYPE_SAMPLED_IMAGE}) {
			boolean buffer = type == Spvc.SPVC_RESOURCE_TYPE_UNIFORM_BUFFER || type == Spvc.SPVC_RESOURCE_TYPE_STORAGE_BUFFER;
			for (SpvModule.Reflection.Descriptor descriptor : reflection.descriptors(type)) {
				descriptor.descriptorSetIndex(0);
				descriptor.binding(binding++);
				bindings.add(new ComputeShader.Binding(buffer, descriptor.name()));
			}
		}

		for (int type : new int[] {Spvc.SPVC_RESOURCE_TYPE_SEPARATE_IMAGE, Spvc.SPVC_RESOURCE_TYPE_SEPARATE_SAMPLERS}) {
			if (!reflection.descriptors(type).isEmpty()) {
				throw new IllegalStateException("a separate image or sampler, which the engine binds on no road, is declared by "
						+ reflection.descriptors(type).getFirst().name());
			}
		}

		ComputeSpirv.keep(module.spv());
		cir.setReturnValue(new ComputeShader.Compiled(0L, List.copyOf(bindings)));
	}
}
