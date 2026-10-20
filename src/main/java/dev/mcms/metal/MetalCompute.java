/*
 * MC Metal Shaders, Apache License 2.0. Original code (2026-10-12, M4).
 */
package dev.mcms.metal;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import org.jspecify.annotations.Nullable;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.spvc.Spv;

import static org.lwjgl.util.spvc.Spvc.*;

/**
 * A shaderpack's compute pass on Metal: its SPIR-V translated by SPIRV-Cross into an MSL kernel, built into a compute pipeline, and
 * dispatched on the frame's compute encoder (mcmetal.m, "M4").
 * <p>
 * The module's descriptors are numbered 0..n-1 in one set by the engine that compiled it (Vitrail's ComputeShader: uniform buffers,
 * storage buffers, storage images, sampled images). Binding i takes buffer slot i and texture slot i, and the samplers are numbered
 * without gaps like a render stage's (MetalPipeline.samplerSlots): a texture the kernel only fetches from, and a depth image, take a
 * constant sampler and no slot.
 */
public final class MetalCompute {
	private MetalCompute() {
	}

	/** One compiled compute pass. Owned by the engine that compiled it, which closes it when its pack is unloaded. */
	public static final class Kernel implements AutoCloseable {
		private long pipeline;
		private final int[] samplerSlots;
		public final int localX, localY, localZ;
		public final int bindings;
		final String name;

		private Kernel(long pipeline, int[] samplerSlots, int localX, int localY, int localZ, String name) {
			this.pipeline = pipeline;
			this.samplerSlots = samplerSlots;
			this.localX = localX;
			this.localY = localY;
			this.localZ = localZ;
			this.bindings = samplerSlots.length;
			this.name = name;
		}

		/** Released once the frames that may still use it have finished on the GPU. */
		@Override
		public void close() {
			if (this.pipeline != 0) {
				MetalStorage.releaseAfterGpu(this.pipeline);
				this.pipeline = 0;
			}
		}
	}

	/** A compute module translated to MSL: the kernel's source and name, its local size, and each binding's sampler slot (-1: none). */
	record Translated(String msl, String entry, int localX, int localY, int localZ, int[] samplerSlots) {
	}

	/**
	 * Translates and builds one compute module whose descriptors are numbered 0..bindings-1 in set 0.
	 *
	 * @throws IllegalStateException with SPIRV-Cross's or Metal's reason, or when the kernel cannot run on this device as written
	 */
	public static Kernel compile(String label, ByteBuffer spirv, int bindings) {
		Translated t = translate(label, spirv, bindings);
		if (MetalPipeline.dumpDir() != null) MetalPipeline.dumpOne(label + ".cs.metal", t.msl());
		long pipeline = build(MetalStorage.device().ctx(), label, t);
		return new Kernel(pipeline, t.samplerSlots(), t.localX(), t.localY(), t.localZ(), label);
	}

	/** SPIRV-Cross's MSL for a compute module, with this backend's slots (MetalPipeline.samplerSlots, compareSamplers, fetchSamplers). */
	static Translated translate(String label, ByteBuffer spirv, int bindings) {
		SpirvResources resources = SpirvResources.read(spirv);
		int[] slots = MetalPipeline.samplerSlots(label, "compute", resources, bindings);
		int model = Spv.SpvExecutionModelGLCompute;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			PointerBuffer out = stack.callocPointer(1);
			MetalPipeline.check(0, spvc_context_create(out), "context");
			long context = out.get(0);
			try {
				IntBuffer words = spirv.duplicate().order(java.nio.ByteOrder.nativeOrder()).asIntBuffer();
				MetalPipeline.check(context, spvc_context_parse_spirv(context, words, words.remaining(), out), "parse");
				long ir = out.get(0);
				MetalPipeline.check(context, spvc_context_create_compiler(context, SPVC_BACKEND_MSL, ir, SPVC_CAPTURE_MODE_TAKE_OWNERSHIP, out), "compiler");
				long compiler = out.get(0);
				MetalPipeline.check(context, spvc_compiler_create_compiler_options(compiler, out), "options");
				long options = out.get(0);
				spvc_compiler_options_set_uint(options, SPVC_COMPILER_OPTION_MSL_VERSION, 30000);
				spvc_compiler_options_set_uint(options, SPVC_COMPILER_OPTION_MSL_PLATFORM, SPVC_MSL_PLATFORM_MACOS);
				spvc_compiler_options_set_bool(options, SPVC_COMPILER_OPTION_MSL_TEXTURE_BUFFER_NATIVE, true);
				MetalPipeline.check(context, spvc_compiler_install_compiler_options(compiler, options), "install options");
				for (int i = 0; i < bindings; i++) MetalPipeline.bind(stack, compiler, model, 0, i, i, slots[i] >= 0 ? slots[i] : i);
				MetalPipeline.compareSamplers(stack, context, compiler);
				MetalPipeline.fetchSamplers(stack, context, compiler, resources, bindings);
				MetalPipeline.check(context, spvc_compiler_compile(compiler, out), "compile");
				String msl = MemoryUtil.memUTF8(out.get(0));
				if (spvc_compiler_msl_needs_buffer_size_buffer(compiler)) {
					throw new IllegalStateException("mcms-metal: compute " + label + " reads the length of a storage buffer's array, which needs a"
						+ " buffer of sizes this backend does not bind");
				}
				return new Translated(msl, spvc_compiler_get_cleansed_entry_point_name(compiler, "main", model),
					Math.max(1, spvc_compiler_get_execution_mode_argument_by_index(compiler, Spv.SpvExecutionModeLocalSize, 0)),
					Math.max(1, spvc_compiler_get_execution_mode_argument_by_index(compiler, Spv.SpvExecutionModeLocalSize, 1)),
					Math.max(1, spvc_compiler_get_execution_mode_argument_by_index(compiler, Spv.SpvExecutionModeLocalSize, 2)), slots);
			} finally {
				spvc_context_destroy(context);
			}
		}
	}

	/** Metal's compute pipeline for a translated kernel, refused when the shader's group is bigger than this kernel may have here. */
	static long build(long ctx, String label, Translated t) {
		ByteBuffer source = MemoryUtil.memUTF8(t.msl());
		try (MemoryStack stack = MemoryStack.stackPush()) {
			long err = stack.nmalloc(1, 4096);
			long maxThreads = stack.ncalloc(4, 1, 4);
			long pipeline = Native.computePipelineNew(ctx, MemoryUtil.memAddress(source), MemoryUtil.memAddress(stack.UTF8(t.entry())), err, 4096, maxThreads);
			if (pipeline == 0) throw new IllegalStateException("mcms-metal: compute " + label + ": " + MemoryUtil.memUTF8(err));
			int most = MemoryUtil.memGetInt(maxThreads);
			if (t.localX() * t.localY() * t.localZ() > most) {
				Native.release(pipeline);
				throw new IllegalStateException("mcms-metal: compute " + label + " asks for " + t.localX() + "x" + t.localY() + "x" + t.localZ()
					+ " threads in a group, past the " + most + " this kernel may have on this GPU");
			}
			return pipeline;
		} finally {
			MemoryUtil.memFree(source);
		}
	}

	/** What one dispatch binds, binding by binding (the kernel's numbering); every binding must be set before dispatch. */
	public static final class Bindings {
		/** Per binding: a GpuBuffer, a GpuTextureView, or a native handle the engine serves (NativeBuffer, NativeTexture). */
		private final Object[] what;
		private final long[] offsets;
		private final @Nullable GpuSampler[] samplers;

		public Bindings(int count) {
			this.what = new Object[count];
			this.offsets = new long[count];
			this.samplers = new GpuSampler[count];
		}

		/** A frontend buffer (a uniform block), from offset. */
		public void buffer(int binding, GpuBuffer buffer, long offset) {
			this.what[binding] = buffer;
			this.offsets[binding] = offset;
		}

		/** A native buffer the engine serves (a pack's storage buffer). */
		public void buffer(int binding, long handle) {
			this.what[binding] = new NativeBuffer(handle);
			this.offsets[binding] = 0;
		}

		/** A frontend texture view, sampled through sampler (null for a storage image). */
		public void texture(int binding, GpuTextureView view, @Nullable GpuSampler sampler) {
			this.what[binding] = view;
			this.samplers[binding] = sampler;
		}

		/** A native texture the engine serves (a pack's storage image), sampled through sampler (null for a storage image). */
		public void texture(int binding, long handle, @Nullable GpuSampler sampler) {
			this.what[binding] = new NativeTexture(handle);
			this.samplers[binding] = sampler;
		}
	}

	private record NativeBuffer(long handle) {
	}

	private record NativeTexture(long handle) {
	}

	/**
	 * Records one dispatch of groupsX x groupsY x groupsZ groups. A frontend texture with a clear still pending gets it written first: a
	 * compute that reads the texture must see it, and one that writes it must not have its writes cleared by the next pass's load.
	 */
	public static void dispatch(Kernel kernel, Bindings bindings, int groupsX, int groupsY, int groupsZ) {
		if (kernel.pipeline == 0) throw new IllegalStateException("compute " + kernel.name + " was closed");
		long enc = MetalStorage.recording();
		MetalEncoder encoder = MetalStorage.device().encoder();
		for (Object o : bindings.what) {
			if (o instanceof GpuTextureView view) encoder.flushClear(MetalTexture.of(view));
		}
		Native.computePipeline(enc, kernel.pipeline);
		for (int i = 0; i < kernel.bindings; i++) {
			Object o = bindings.what[i];
			if (o == null) throw new IllegalStateException("compute " + kernel.name + ": binding " + i + " was not set");
			if (o instanceof GpuBuffer buffer) {
				Native.computeBuffer(enc, i, encoder.use(buffer).handle, bindings.offsets[i]);
				continue;
			}
			if (o instanceof NativeBuffer served) {
				Native.computeBuffer(enc, i, served.handle(), 0);
				continue;
			}
			long texture = o instanceof GpuTextureView view ? ((MetalTexture.View) view).handle : ((NativeTexture) o).handle();
			Native.computeTexture(enc, i, texture);
			GpuSampler sampler = bindings.samplers[i];
			if (sampler != null && kernel.samplerSlots[i] >= 0) Native.computeSampler(enc, kernel.samplerSlots[i], ((MetalSampler) sampler).handle());
		}
		Native.dispatch(enc, groupsX, groupsY, groupsZ, kernel.localX, kernel.localY, kernel.localZ);
	}
}
