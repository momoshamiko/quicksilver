package dev.mcms.metal.mixin;

import dev.vitrail.Vitrail;
import dev.vitrail.glsl.SharedMemory;
import dev.vitrail.glsl.VendorExtensions;
import dev.vitrail.pack.model.ProgramStage;
import dev.vitrail.render.BufferBlending;
import dev.vitrail.render.GeometryStage;
import dev.mcms.metal.MetalHooks;
import dev.mcms.metal.vitrail.VitrailStorageResolver;
import dev.vitrail.render.TextureUsage;
import dev.vitrail.render.WideSamplerSets;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.EnumSet;

/**
 * Seam part 2b (spec v0.2 §5): the device answers Vitrail reads back from its Vulkan mixins around Mojang's Vulkan
 * device creation ({@code VulkanBackendMixin.vitrail$deviceFeatures}, Vitrail 0.12.0-beta
 * common/src/mc26.3/java/dev/vitrail/mixin/VulkanBackendMixin.java:194-249). On this backend that wrap never runs, so
 * every answer stays at its unserved default: {@code BufferBlending.served=false} refuses packs requiring
 * {@code PER_BUFFER_BLENDING} and folds per-buffer {@code blend.*} directives into one whole-program function,
 * {@code GeometryStage} is unserved, vendor extensions all count absent, subgroup stages all count present,
 * {@code moltenVk=false} leaves the float packing builtins and oversize {@code shared} declarations alone, and
 * {@code WideSamplerSets} is unserved. Served here at this backend's device creation instead, before any pack is read,
 * with the answer of an Apple M5 Max under native Metal, under one rule: where an answer comes from SPIRV-Cross or from
 * the Apple GPU itself (subgroup stages, the vendor extensions SPIRV-Cross lowers, the float packing rewrite, the
 * threadgroup memory cap), it is the answer MoltenVK gives on this Mac, since the road to Metal runs through the same
 * translator; where an answer would route Vitrail into Vulkan-only machinery it is refused, and the comment at the
 * serve call says so. Applied only when Vitrail is loaded ({@code McmsMetalMixinPlugin}); without Vitrail none of
 * these classes exist.
 * <p>
 * The Vulkan answers this is measured against are the ones MoltenVK gives on this same Mac
 * (results/2026-10-11-m0c-profiles/vk-vitrail-high/run1/game-latest.log:82-84).
 */
@Mixin(targets = "dev.mcms.metal.MetalDevice")
abstract class VitrailDeviceReportMixin {
	@Inject(method = "<init>", at = @At("TAIL"))
	private void mcmsMetal$serveDeviceReport(CallbackInfo ci) {
		// Per-target blending: Metal has always carried one MTLRenderPipelineColorAttachmentDescriptor per colour
		// attachment with its own blend state, and this backend fills each from renderpearl's per-target
		// ColorTargetState (MetalPipeline.describe writes 9 ints a target; mcmetal.m's mc_pipeline_new reads them into
		// pd.colorAttachments[c] one by one). independentBlend is a Vulkan permission Metal needs no equivalent of, so
		// the device's half of PER_BUFFER_BLENDING is yes. Vitrail's own halves (the refusal lift in the pipeline
		// builder, the IRIS_FEATURE_PER_BUFFER_BLENDING define, the per-rank blend directives) all stand on this answer.
		BufferBlending.serve(true);

		// Geometry stage: Metal has no geometry stage at all, on any Apple GPU. Answered false, which on 26.3 only
		// changes the wording of the refusal of a program that ships one (GeometryStage binds none on this game).
		GeometryStage.serve(false);

		// Wide sampler sets: a Vulkan-only road (dropPush runs in Vitrail's VulkanBindGroupLayout/VulkanRenderPipeline
		// mixins, which never run here), and its counts name Vulkan push-descriptor limits this device has no form of.
		// Not MoltenVK, and the counts are unread when it is not, so they stay 0.
		WideSamplerSets.serve(false, 0, 0);

		// Vendor extensions: the three AMD ones MoltenVK exposes on this Mac (the log line above), because what stands
		// behind them there is SPIRV-Cross's MSL lowering of the AMD trinary-minmax, half-float and image-lod
		// instructions, and this backend's road to Metal runs through SPIRV-Cross too (MetalPipeline.translateNow). A
		// pack testing one of those takes the extension here as it does under MoltenVK; every other vendor extension
		// counts absent and the pack takes its own fallback. The enable side has no Vulkan device to enable anything on:
		// it is handed nothing, so nothing routes into Vulkan machinery.
		VendorExtensions.serve(VitrailDeviceReportMixin::mcmsMetal$amdOnMetal, enable -> {
		});

		// Subgroup stages: absent from the vertex, geometry and tessellation-evaluation stages, the same answer MoltenVK
		// gives on this Mac, for the same reason: the road to Metal is SPIRV-Cross here too, which converts simdgroup
		// operations in the fragment and compute stages and refuses a vertex module using a subgroup builtin. Metal
		// simdgroups run in fragment and compute on Apple GPUs; tessellation control is named to match that answer.
		VendorExtensions.serveSubgroupStages(
				EnumSet.of(ProgramStage.FRAGMENT, ProgramStage.COMPUTE, ProgramStage.TESSELLATION_CONTROL));

		// moltenVk=true: the flag names the road whose float packing calls should not reach SPIRV-Cross's own pack
		// builtins, and this is that road - the same translator, run by this mod instead of by MoltenVK. Vitrail's
		// PackBuiltins rewrite puts the calls in plain integer arithmetic in the GLSL, which shaderc compiles the same
		// on any device; the bug the rewrite dodges (a packed word reaching its target holding float bits on an Apple
		// GPU) was SPIRV-Cross's MSL for those builtins, so the builtins are not trusted here either. Its other
		// reader is the translation cache key (VendorExtensions.key()), which then matches MoltenVK's.
		VendorExtensions.serveMoltenVk(true);

		// Shared memory: the flag names "the driver whose Metal kernels cap threadgroup memory", and these kernels are
		// Metal too, so an oversize shared declaration needs the same move into a buffer (PackCompute.Pass.sharedMemory, which
		// McmsMetalCompute calls on this backend since M4).
		SharedMemory.serve(true);

		// M4, the storage seam: a draw binds a pack's storage images and buffers as placeholders under their names, and Vitrail's
		// VulkanRenderPassMixin swaps the real ones in while it pushes the descriptors; MetalRenderPass asks these instead (the Metal
		// textures and buffers VitrailStorageImagesMixin and VitrailStorageBuffersMixin made). A colour target a compute writes as
		// colorimgN is created while TextureUsage says so; this backend then gives it shader-write usage.
		MetalHooks.setStorage(new VitrailStorageResolver(), TextureUsage::storageRequested);

		Vitrail.logger().info(mcmsMetal$served());
	}

	@Unique
	private static String mcmsMetal$served() {
		return "mcms-metal device report on Metal: bufferBlending=true geometryStage=false wideSamplerSets=off"
				+ " vendorExtensions=GL_AMD_shader_trinary_minmax, GL_AMD_gpu_shader_half_float,"
				+ " GL_AMD_shader_image_load_store_lod subgroupsAbsentIn=[VERTEX, GEOMETRY, TESSELLATION_EVALUATION]"
				+ " floatPackingRewritten=true sharedMemoryMovedToBuffer=true";
	}

	@Unique
	private static boolean mcmsMetal$amdOnMetal(String deviceExtension) {
		// VK_AMD_shader_trinary_minmax, VK_AMD_gpu_shader_half_float, VK_AMD_shader_image_load_store_lod: the
		// device extensions MoltenVK reports on this Mac (results/2026-10-11-m0c-profiles/vk-vitrail-high/run1/
		// game-latest.log:82).
		return "VK_AMD_shader_trinary_minmax".equals(deviceExtension)
				|| "VK_AMD_gpu_shader_half_float".equals(deviceExtension)
				|| "VK_AMD_shader_image_load_store_lod".equals(deviceExtension);
	}
}
