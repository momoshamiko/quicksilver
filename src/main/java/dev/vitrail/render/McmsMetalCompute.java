/*
 * Part of MC Metal Shaders (mcms-metal), 2026-10-12 (M4). Adapted from Vitrail 0.12.0-beta,
 * common/src/main/java/dev/vitrail/render/PackCompute.java (dispatch, dispatchChain, Pass.dispatch, Pass.compile,
 * Pass.pushDescriptors, Pass.colourTarget, samplerFor), Copyright the Vitrail authors, licensed LGPL-3.0-only. This file is
 * therefore LGPL-3.0-only, unlike the rest of mcms-metal (Apache-2.0); see mod/mcms-metal/README.md (Licences) and NOTICE.
 *
 * It sits in Vitrail's package because what it calls is package-private there (ColorTargets, PackPass, TargetSurface). Vitrail's
 * own road records Vulkan commands and returns on any other device; mixin.VitrailPackComputeMixin sends the game here instead when
 * it draws through mcms-metal, and this records the same dispatches, with the same bindings in the same order, on Metal.
 */
package dev.vitrail.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.device.GpuDeviceLossException;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.mcms.metal.MetalCompute;
import dev.mcms.metal.MetalStorage;
import dev.mcms.metal.vitrail.ComputeAccess;
import dev.mcms.metal.vitrail.ComputeSpirv;
import dev.vitrail.Vitrail;
import dev.vitrail.glsl.PackProgram;
import dev.vitrail.glsl.TranslatedUnit;
import dev.vitrail.pack.model.ProgramStage;
import dev.vitrail.pack.model.RenderStage;
import dev.vitrail.pack.model.TargetName;
import dev.vitrail.pack.target.SamplerPlan;
import dev.vitrail.pack.target.TargetSchedule;
import dev.vitrail.render.storage.GpuRecording;
import dev.vitrail.render.storage.StorageBuffers;
import dev.vitrail.render.storage.StorageImages;
import dev.vitrail.uniform.ClipSpace;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MappableRingBuffer;
import org.lwjgl.system.MemoryUtil;

public final class McmsMetalCompute {
	/** PackCompute.COLOUR_IMAGE. */
	private static final Pattern COLOUR_IMAGE = Pattern.compile("\\bcolorimg(\\d+)\\b");
	/** PackCompute.MODULE_CACHE_STAGE (ComputeShader.build takes it; nothing on 26.3 keys anything on it). */
	private static final String MODULE_CACHE_STAGE = "COMPUTE/shaderc-opt2-vulkan1.2";
	/** PackCompute.PUSH_DESCRIPTORS: the warning beside it is Vitrail's, kept so the two backends' logs say the same. */
	private static final int PUSH_DESCRIPTORS = 32;
	/** PackCompute's backend-neutral private helpers, called as they are rather than copied. */
	private static final MethodHandle ENGINE_VIEW, SIZING;

	static {
		try {
			MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(PackCompute.class, MethodHandles.lookup());
			ENGINE_VIEW = lookup.findStatic(PackCompute.class, "engineView", MethodType.methodType(GpuTextureView.class, ColorTargets.class, String.class));
			SIZING = lookup.findStatic(PackCompute.class, "sizing", MethodType.methodType(String.class, PackProgram.Compute.class));
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("mcms-metal: Vitrail's PackCompute is not the 0.12.0-beta this road was written against", e);
		}
	}

	private McmsMetalCompute() {
	}

	/** PackCompute.dispatch: the shadow computes, at the head of the frame. */
	public static void dispatch(Object computes, PackValues values, Object targetsObject) {
		ComputeAccess.Computes compute = (ComputeAccess.Computes) computes;
		ColorTargets targets = (ColorTargets) targetsObject;
		List<?> passes = compute.mcmsMetal$passes();
		if (passes.isEmpty() || !targets.usable()) {
			return;
		}

		GpuDevice device = RenderSystem.tryGetDevice();
		if (device == null) {
			return;
		}

		CommandEncoder encoder = device.createCommandEncoder();
		GeometryHold.flush(() -> "the shadow compute dispatch");
		GpuRecording.endPass(encoder);
		values.convention(ClipSpace.FORWARD);
		values.modelView(null, null);
		values.projection(null);
		values.passColour(null);
		values.renderStage(RenderStage.NONE);
		// Vitrail's afterShadowGeometry barrier: the previous frame's shadow geometry stored into the volumes this reads. Metal orders
		// those stores before these reads by itself (tracked resources, one queue, encoders in submission order).
		Minecraft minecraft = Minecraft.getInstance();
		RenderTarget main = minecraft == null ? null : minecraft.gameRenderer.mainRenderTarget();
		int width = main == null ? 0 : main.width;
		int height = main == null ? 0 : main.height;
		for (Object p : passes) {
			ComputeAccess.Pass pass = (ComputeAccess.Pass) p;
			try {
				dispatch(pass, values, targets, width, height, null, null, null);
			} catch (GpuDeviceLossException e) {
				throw e;
			} catch (RuntimeException e) {
				if (pass.mcmsMetal$failed().add(e.toString())) {
					Vitrail.logger().warn("shadow compute {} failed: {}", pass.mcmsMetal$path(), e.toString());
				}
			}
		}

		// Vitrail's afterCompute barrier (floodfill stores before this frame's gbuffers read them): ordered by Metal likewise.
		if (!compute.mcmsMetal$announced()) {
			compute.mcmsMetal$announced(true);
			Vitrail.logger().info("Dispatched {} shadow compute pass(es) at the head of the frame", passes.size());
		}
	}

	/** PackCompute.dispatchChain: the computes hanging off a full screen pass (or standing alone at a program's moment). */
	public static void dispatchChain(Object computes, List<?> attached, String program, CommandEncoder encoder, PackValues values,
			Object targetsObject, TargetSchedule.Bound step, GpuTextureView depth, GpuTextureView distant, int width, int height) {
		if (attached == null || attached.isEmpty()) {
			return;
		}

		ComputeAccess.Computes compute = (ComputeAccess.Computes) computes;
		ColorTargets targets = (ColorTargets) targetsObject;
		GeometryHold.flush(() -> "the compute dispatch at " + program);
		GpuRecording.endPass(encoder);
		// Vitrail's beforeChainCompute and afterChainCompute barriers (the passes before write what this samples or stores; this stores
		// what the pass after samples): Metal orders render and compute encoders on tracked resources by itself.
		for (Object p : attached) {
			ComputeAccess.Pass pass = (ComputeAccess.Pass) p;
			try {
				dispatch(pass, values, targets, width, height, step, depth, distant);
			} catch (GpuDeviceLossException e) {
				throw e;
			} catch (RuntimeException e) {
				if (pass.mcmsMetal$failed().add(e.toString())) {
					Vitrail.logger().warn("compute {} failed: {}", pass.mcmsMetal$path(), e.toString());
				}
			}
		}

		if (compute.mcmsMetal$announcedChains().add(program)) {
			Vitrail.logger().info("Dispatched {} compute pass(es) at {}", attached.size(), program);
		}
	}

	/** Pass.dispatch. */
	private static void dispatch(ComputeAccess.Pass pass, PackValues values, ColorTargets targets, int width, int height,
			TargetSchedule.Bound step, GpuTextureView depth, GpuTextureView distant) {
		if (!pass.mcmsMetal$compiled()) {
			compile(pass);
		}

		MetalCompute.Kernel kernel = pass.mcmsMetal$kernel();
		if (kernel == null) {
			return;
		}

		pass.mcmsMetal$writeBlock(values);
		MetalCompute.Bindings bindings = bind(pass, kernel, targets, step, depth, distant);
		// The screen and not the shadow map, as Vitrail sizes it (Iris's ShadowCompositeRenderer.java:213-214).
		int[] groups = pass.mcmsMetal$compute().groupsAt(width, height);
		MetalCompute.dispatch(kernel, bindings, groups[0], groups[1], groups[2]);
		MappableRingBuffer block = pass.mcmsMetal$block();
		if (block != null) {
			block.rotate();
		}
	}

	/** Pass.compile: ComputeShader.build numbers the module (mixin.VitrailComputeShaderMixin keeps its SPIR-V), MetalCompute builds it. */
	private static void compile(ComputeAccess.Pass pass) {
		pass.mcmsMetal$compiled(true);
		TranslatedUnit unit = pass.mcmsMetal$compute().loaded().program().stages().get(ProgramStage.COMPUTE);
		if (unit == null) {
			return;
		}

		ComputeShader.Compiled compiled;
		try {
			compiled = ComputeShader.build(null, pass.mcmsMetal$label(), pass.mcmsMetal$sharedMemory(unit.text()), MODULE_CACHE_STAGE,
					pass::mcmsMetal$compileSpirv);
		} catch (GpuDeviceLossException e) {
			ComputeSpirv.discard();
			throw e;
		} catch (Exception e) {
			ComputeSpirv.discard();
			Vitrail.logger().warn("compute {} SPIR-V failed: {}", pass.mcmsMetal$path(), e.toString());
			return;
		}

		ByteBuffer spirv = ComputeSpirv.take();
		if (compiled == null || spirv == null) {
			if (spirv != null) {
				MemoryUtil.memFree(spirv);
			}

			return;
		}

		pass.mcmsMetal$entries(compiled.entries());
		if (compiled.entries().size() > PUSH_DESCRIPTORS) {
			Vitrail.logger().warn("compute {} pushes {} descriptors in one set, past the {} a device commonly allows at once",
					pass.mcmsMetal$path(), compiled.entries().size(), PUSH_DESCRIPTORS);
		}

		try {
			pass.mcmsMetal$kernel(MetalCompute.compile(pass.mcmsMetal$label(), spirv, compiled.entries().size()));
			if (pass.mcmsMetal$sharedBytes() > 0L) {
				pass.mcmsMetal$sharedBuffer(MetalStorage.newBuffer(pass.mcmsMetal$sharedBytes()));
			}
		} catch (GpuDeviceLossException e) {
			throw e;
		} catch (RuntimeException e) {
			MetalCompute.Kernel kernel = pass.mcmsMetal$kernel();
			if (kernel != null) {
				kernel.close();
				pass.mcmsMetal$kernel(null);
			}

			Vitrail.logger().warn("compute {} pipeline failed: {}", pass.mcmsMetal$path(), e.toString());
		} finally {
			MemoryUtil.memFree(spirv);
		}

		if (pass.mcmsMetal$kernel() != null) {
			Vitrail.logger().info("Compiled compute {} ({})", pass.mcmsMetal$path(), sizing(pass.mcmsMetal$compute()));
		}
	}

	/** Pass.pushDescriptors: what each binding takes, in Vitrail's order of questions; Vitrail's "a Vulkan view" reads "a view" here. */
	private static MetalCompute.Bindings bind(ComputeAccess.Pass pass, MetalCompute.Kernel kernel, ColorTargets targets,
			TargetSchedule.Bound step, GpuTextureView depth, GpuTextureView distant) {
		List<ComputeShader.Binding> entries = pass.mcmsMetal$entries();
		MetalCompute.Bindings out = new MetalCompute.Bindings(kernel.bindings);
		SamplerPlan samplers = pass.mcmsMetal$compute().loaded().samplers();
		for (int i = 0; i < entries.size(); i++) {
			ComputeShader.Binding entry = entries.get(i);
			if (entry.buffer()) {
				if (pass.mcmsMetal$servesShared(entry.name())) {
					out.buffer(i, pass.mcmsMetal$sharedBuffer());
					continue;
				}

				if (StorageBuffers.named(entry.name())) {
					StorageBuffers.Bound bound = StorageBuffers.bound(entry.name());
					if (bound == null) {
						throw new IllegalStateException("Missing storage buffer " + entry.name());
					}

					out.buffer(i, bound.buffer());
					continue;
				}

				MappableRingBuffer block = pass.mcmsMetal$block();
				out.buffer(i, block.currentBuffer(), 0L);
				continue;
			}

			StorageImages.Bound bound = StorageImages.bound(entry.name());
			ColorTargets.PackBinding supplied = bound == null ? targets.packTexture(pass.mcmsMetal$textureStage(), entry.name()) : null;
			if (supplied != null && supplied.view() != null) {
				out.texture(i, supplied.view(), PackPass.sampler(supplied.repeat(), supplied.filter(), supplied.mipmaps()));
				continue;
			}

			SamplerPlan.Binding taken = samplers.binding(entry.name());
			if (bound == null && taken.kind() == SamplerPlan.Kind.PACK_TEXTURE && !taken.defaulted() && targets.black() != null) {
				out.texture(i, targets.black(), samplerFor(targets, samplers, entry.name()));
				continue;
			}

			if (bound == null && step != null && colourTarget(out, i, entry.name(), targets, samplers, step, pass.mcmsMetal$program())) {
				continue;
			}

			if (bound == null) {
				GpuTextureView read = switch (SamplerPlan.classify(entry.name())) {
					case DEPTH -> PackPass.depth(entry.name(), targets, depth);
					case DISTANT_DEPTH -> PackPass.distant(entry.name(), targets, distant);
					case CENTER_DEPTH -> PackPass.centerDepth(targets);
					default -> null;
				};
				if (read != null) {
					out.texture(i, read, samplerFor(targets, samplers, entry.name()));
					continue;
				}

				GpuTextureView engine = engineView(targets, entry.name());
				if (engine != null) {
					out.texture(i, engine, samplerFor(targets, samplers, entry.name()));
					continue;
				}

				SamplerPlan.Binding byDefault = samplers.binding(entry.name());
				if (byDefault.defaulted()) {
					String screen = TargetName.canonical(SamplerPlan.DEFAULT_TARGET);
					if (byDefault.kind() == SamplerPlan.Kind.COLORTEX && step != null
							&& colourTarget(out, i, screen, targets, samplers, step, pass.mcmsMetal$program())) {
						continue;
					}

					ColorTargets.PackBinding laid = targets.packTexture(pass.mcmsMetal$textureStage(), screen);
					if (laid != null && laid.view() != null) {
						out.texture(i, laid.view(), PackPass.sampler(laid.repeat(), laid.filter(), laid.mipmaps()));
						continue;
					}

					if (targets.black() != null) {
						out.texture(i, targets.black(), samplerFor(targets, samplers, entry.name()));
						continue;
					}
				}

				throw new IllegalStateException("Missing storage image " + entry.name());
			}

			// The pack's own image: stored into under its image name, sampled under the sampler name on the same image. line.
			out.texture(i, bound.view(), bound.storage() ? null : samplerFor(targets, samplers, entry.name()));
		}

		return out;
	}

	/** Pass.colourTarget. */
	private static boolean colourTarget(MetalCompute.Bindings out, int binding, String name, ColorTargets targets, SamplerPlan samplers,
			TargetSchedule.Bound step, String program) {
		Matcher image = COLOUR_IMAGE.matcher(name);
		if (image.matches()) {
			int index = Integer.parseInt(image.group(1));
			TargetSurface surface = targets.surface(index, step.read(index));
			if (surface == null || !surface.storage() || surface.storageView() == null) {
				throw new IllegalStateException(name + " is not a target created writable from a compute");
			}

			out.texture(binding, surface.storageView(), null);
			return true;
		}

		OptionalInt index = TargetName.index(name);
		if (index.isEmpty()) {
			return false;
		}

		TargetSurface surface = targets.surface(index.getAsInt(), step.read(index.getAsInt()));
		if (surface == null || surface.view() == null) {
			if (targets.black() == null) {
				return false;
			}

			out.texture(binding, targets.black(), samplerFor(targets, samplers, name));
			return true;
		}

		boolean mipmaps = surface.chainWritten() && targets.lodReads(program).contains(index.getAsInt());
		out.texture(binding, surface.view(), PackPass.sampler(false, targets.filter(index.getAsInt()), mipmaps));
		return true;
	}

	/** PackCompute.samplerFor, answering the sampler object where Vitrail answers its Vulkan handle. */
	private static GpuSampler samplerFor(ColorTargets targets, SamplerPlan samplers, String name) {
		boolean noise = "noisetex".equals(name);
		FilterMode filter = noise ? FilterMode.LINEAR : switch (samplers.binding(name).kind()) {
			case SHADOW_COLOUR -> FilterMode.LINEAR;
			case SHADOW_DEPTH -> targets.shadow().depthFilter(samplers.withoutTranslucents(name));
			default -> PackPass.customImageFilter(name);
		};
		boolean mipmaps = !noise && samplers.binding(name).kind() == SamplerPlan.Kind.SHADOW_DEPTH
				&& targets.shadow().depthMipmapped(samplers.withoutTranslucents(name));
		return PackPass.sampler(noise, filter, mipmaps);
	}

	private static GpuTextureView engineView(ColorTargets targets, String name) {
		try {
			return (GpuTextureView) ENGINE_VIEW.invokeExact(targets, name);
		} catch (RuntimeException | Error e) {
			throw e;
		} catch (Throwable e) {
			throw new IllegalStateException(e);
		}
	}

	private static String sizing(PackProgram.Compute compute) {
		try {
			return (String) SIZING.invokeExact(compute);
		} catch (RuntimeException | Error e) {
			throw e;
		} catch (Throwable e) {
			throw new IllegalStateException(e);
		}
	}
}
