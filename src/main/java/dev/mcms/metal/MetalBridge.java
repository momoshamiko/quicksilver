/*
 * Derived from mcopt v0.3.0-alpha.2 (https://github.com/noahdunnagan/mcopt), metal/src/main/java/mcopt/metal/MetalBridge.java.
 * Copyright Noah Dunnagan, Apache License 2.0. Changed by MC Metal Shaders, 2026-10-11: package renamed to dev.mcms.metal; dropped the
 * methods only mcopt's removed packages used (waitIdle and library for own, samplerParams for its frame capture, useBuffer for its
 * terrain, reapplyPipeline and restorePass for its far terrain and native shading); generateMipmaps added. 2026-11-13: nativeLookup
 * for the shader engine's own bindings (ADR 0006). 2026-11-13 (W1-C, ADR 0006): public wrappers for the engine added at the end of
 * this file (pipelines, depth state, sampler, textures, render and compute encoding, readback; add-only, nothing above changed).
 * 2026-11-17 (E9, ADR 0008): pendingColorClear (the colour of a texture's deferred clear, for the render-size stand-in). E10: the
 * encoder-object generateMipmaps (Vitrail's mip-chain request) and shaderWritable removed with the Vitrail integration.
 */
package dev.mcms.metal;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.pipeline.BlendFactor;
import com.mojang.renderpearl.api.pipeline.BlendOp;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/**
 * The public window into this package: native handles of the backend's objects and headless helpers, for code outside
 * the backend (a shaderpack runtime). The backend's own classes stay package-private and its hot paths untouched.
 */
public final class MetalBridge {
	private MetalBridge() {
	}

	/** The loaded native image, for the shader engine's own bindings (ADR 0006: src/main/native/mcshaders.m). */
	public static java.lang.foreign.SymbolLookup nativeLookup() {
		return Native.lookup();
	}

	/** Current submit counter of a Metal encoder (or -1 for non-Metal/stub encoders); advances only on {@code submit()}. */
	public static long submitIndex(@Nullable Object encoder) {
		return encoder instanceof MetalEncoder metal ? metal.submitIndex() : -1L;
	}

	/** Headless context created by {@link #createHeadlessContext()}, used as fallback for {@link #currentContext()} in headless tests. */
	private static volatile long headlessContext;

	/** A Metal context without a window, for headless checks (no game, no surface). */
	public static long createHeadlessContext() {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			long name = stack.nmalloc(1, 256), err = stack.nmalloc(1, 1024);
			long ctx = Native.create(name, 256, err, 1024);
			if (ctx == 0) throw new IllegalStateException("Metal unavailable: " + MemoryUtil.memUTF8(err));
			headlessContext = ctx;
			return ctx;
		}
	}

	/** A native encoder for headless checks; commitAndWait submits what was recorded and waits for the GPU. */
	public static long createHeadlessEncoder(long ctx) {
		return Native.encNew(ctx);
	}

	public static void commitAndWait(long enc) {
		long cmd = Native.encCommit(enc);
		Native.cmdWait(cmd);
		Native.release(cmd);
	}

	public static long newTexture(long ctx, GpuFormat format, int width, int height, boolean sampled) {
		return Native.textureNew(ctx, MetalConst.pixelFormat(format), width, height, 1, 1, 4 | (sampled ? 1 : 0), 0);
	}

	public static long newBuffer(long ctx, long size) {
		return Native.bufferNew(ctx, size);
	}

	/**
	 * A buffer only the GPU reads and writes (MTLResourceStorageModePrivate; the compute kernels' spill buffers, E6). The
	 * contents are not CPU-visible, so the caller fills nothing.
	 */
	public static long newBufferPrivate(long ctx, long size) {
		return Native.bufferNewPrivate(ctx, size);
	}

	/** The device's largest buffer in bytes (Metal's maxBufferLength); a larger buffer is refused. */
	public static long maxBufferLength(long ctx) {
		return Native.maxBufferLength(ctx);
	}

	public static long bufferContents(long buffer) {
		return Native.bufferContents(buffer);
	}

	public static void readTexture(long enc, long texture, int width, int height, int bytesPerPixel, long buffer) {
		Native.blitTextureToBuffer(enc, texture, 0, 0, 0, width, height, buffer, 0, width * bytesPerPixel);
	}

	/** Copies w x h texels at (x, y) of texture (mip 0) into buffer at offset, at the frame's current point (a blit). */
	public static void readTextureRegion(long enc, long texture, int x, int y, int w, int h, int bytesPerPixel, long buffer, long offset) {
		Native.blitTextureToBuffer(enc, texture, 0, x, y, w, h, buffer, offset, w * bytesPerPixel);
	}

	/** 2026-11-14 (draw dumps): a texture's width (what 0), height (1) or MTLPixelFormat (2); -1 for none. */
	public static int textureInfo(long texture, int what) {
		return Native.dumpInfo(texture, what);
	}

	/**
	 * 2026-11-14 (draw dumps): the texture's texels as the work so far leaves them, into dst (width x height x bytesPerPixel,
	 * rows tight). An open render pass of the encoder is reopened on its attachments and its state restored, so it goes on.
	 * Returns -1 when the GPU failed the readback, 0 or 1 otherwise (1: a render encoder was reopened).
	 */
	public static int pictureReadback(@Nullable Object encoder, long enc, long texture, int width, int height, int bytesPerPixel,
			boolean depth, long dst) {
		int result = Native.dumpReadback(enc, texture, width, height, bytesPerPixel, depth ? 1 : 0, dst);
		if (result == 1 && encoder instanceof MetalEncoder metal) {
			metal.restoreOpenPass();
		}
		return result;
	}

	public static int pixelFormat(GpuFormat format) {
		return MetalConst.pixelFormat(format);
	}

	/** The format a raw MTLPixelFormat word names (the inverse of {@link #pixelFormat}); null for a word no format has. */
	public static @Nullable GpuFormat gpuFormatOf(int pixelFormat) {
		return GpuFormatWords.ofWord(pixelFormat);
	}

	/** The depth-attachment pipeline state of a backend pipeline object (0 when the object is not a Metal pipeline). */
	public static long depthPipeline(@Nullable Object backendPipeline) {
		return backendPipeline instanceof MetalPipeline p ? p.withDepth : 0;
	}

	/**
	 * 2026-11-14 (E4Plans, ADR 0006): the native pipeline state of a backend pipeline object for a pass with or without a
	 * depth attachment (0 when the object is not a Metal pipeline). A game copy the engine compiled binds through it.
	 */
	public static long pipelineVariant(Object backendPipeline, boolean hasDepth) {
		if (!(backendPipeline instanceof MetalPipeline p)) return 0;
		return hasDepth ? p.withDepth : p.withoutDepth;
	}

	/**
	 * 2026-11-15 (E6, ADR 0006): the vertex stage's sampler slot of uniform {@code index} of a backend pipeline object, -1
	 * when that stage samples nothing there or the object is not a Metal pipeline: the slots its own pass binds
	 * ({@code MetalPipeline.samplerSlots}). A game variant drawn through the engine binds its samplers with them.
	 */
	public static int vertexSamplerSlot(@Nullable Object backendPipeline, int index) {
		return backendPipeline instanceof MetalPipeline p && index < p.vertexSamplers.length ? p.vertexSamplers[index] : -1;
	}

	/** 2026-11-15 (E6, ADR 0006): the fragment stage's slot, as {@link #vertexSamplerSlot}. */
	public static int fragmentSamplerSlot(@Nullable Object backendPipeline, int index) {
		return backendPipeline instanceof MetalPipeline p && index < p.fragmentSamplers.length ? p.fragmentSamplers[index] : -1;
	}

	public static int vertexFormat(GpuFormat format) {
		return MetalConst.vertexFormat(format);
	}

	public static int blendFactor(BlendFactor factor) {
		return MetalConst.blendFactor(factor);
	}

	public static int blendOp(BlendOp op) {
		return MetalConst.blendOp(op);
	}

	/** MTLCompareFunction for op. */
	public static int compare(CompareOp op) {
		return MetalConst.compare(op);
	}

	/** The MTLPrimitiveType the backend draws topology with (5 = triangle fan, emulated). */
	public static int primitive(PrimitiveTopology topology) {
		return MetalConst.primitive(topology);
	}

	public static int topologyClass(PrimitiveTopology topology) {
		return MetalConst.topologyClass(topology);
	}

	public static long textureHandle(GpuTexture texture) {
		return ((MetalTexture) texture).handle;
	}

	public static long viewHandle(GpuTextureView view) {
		return ((MetalTexture.View) view).handle;
	}

	public static long samplerHandle(GpuSampler sampler) {
		return ((MetalSampler) sampler).handle();
	}

	public static long bufferHandle(GpuBuffer buffer) {
		return ((MetalBuffer) buffer).handle;
	}

	/** The native context of the current Metal device (the one the game renders on; headless tests fall back to their headless context). */
	public static long currentContext() {
		MetalDevice device = MetalDevice.current;
		if (device != null) {
			return device.ctx();
		}
		long headless = headlessContext;
		if (headless != 0L) {
			return headless;
		}
		throw new IllegalStateException("no Metal device is current");
	}

	/** A shared buffer of size bytes on ctx as the game's {@link GpuBuffer} (tests feed the delegate's uniform captures). */
	public static GpuBuffer newGpuBuffer(long ctx, long size) {
		return new MetalBuffer(new MetalEncoder(ctx), Native.bufferNew(ctx, size), GpuBuffer.USAGE_UNIFORM, size);
	}

	/** The shared owner of the engine's own GPU buffers in a context, made on first use (see {@link #newGpuBuffer(Object, long, long)}). */
	private static final java.util.Map<Long, MetalEncoder> BUFFER_OWNERS = new java.util.HashMap<>();

	/**
	 * The owner of the engine's GPU buffers in {@code ctx}: one native encoder, shared by every buffer made under it
	 * ({@link #newGpuBuffer(Object, long, long)}). {@link #newGpuBuffer(long, long)} makes an encoder per buffer, which is
	 * never freed; the engine's buffers take this owner instead.
	 */
	public static Object bufferOwner(long ctx) {
		return BUFFER_OWNERS.computeIfAbsent(ctx, MetalEncoder::new);
	}

	/** A uniform GPU buffer of size bytes on {@code ctx}, owned by {@code owner} (from {@link #bufferOwner}). */
	public static GpuBuffer newGpuBuffer(Object owner, long ctx, long size) {
		return new MetalBuffer((MetalEncoder) owner, Native.bufferNew(ctx, size), GpuBuffer.USAGE_UNIFORM, size);
	}

	/**
	 * A texel view of buffer's bytes at offset..length in a Metal pixel format (14 = R8_SINT, one byte per texel, the
	 * game's CloudFaces; 54 = R32_SINT, one int per texel). The view covers length / texelSize texels.
	 */
	public static long texelBufferView(long ctx, long buffer, long offset, long length, int format, int texelSize) {
		return Native.textureBuffer(ctx, buffer, format, offset, length, texelSize);
	}

	/** The backend's encoder behind a frontend command encoder, or null when the game isn't on the Metal backend. */
	public static @Nullable Object encoder(CommandEncoderBackend backend) {
		return backend instanceof MetalEncoder e ? e : null;
	}

	/** CPU address of buffer's current memory (0 for GPU-only storage), for reading what the CPU last wrote into it. */
	public static long bufferAddress(GpuBuffer buffer) {
		return ((MetalBuffer) buffer).address;
	}

	/** Native Enc* of encoder (the object encoder() returned). */
	public static long enc(Object encoder) {
		return ((MetalEncoder) encoder).enc;
	}

	/** Whether encoder is recording the submit -Dmcms.metal.trace names (whose operations and encoder GPU times get printed). */
	public static boolean tracing(Object encoder) {
		return ((MetalEncoder) encoder).tracing();
	}

	public static long ctx(Object encoder) {
		return ((MetalEncoder) encoder).ctx;
	}

	/** A clears entry meaning: don't load this attachment, the pass writes all of it. */
	public static final float[] DONT_CARE = new float[0];

	/**
	 * Opens (or continues) a render encoder on colors (0 = unused slot) and depth, like the backend's own passes: clears[i]
	 * null loads, else clears to {r, g, b, a}. Returns mc_render_begin's result: the mode in the low two bits (0 a new
	 * encoder, 1 or 2 continued), and bit 16 set when the call ended an open encoder first ({@code MetalEncoder.RENDER_ENDED_OPEN}).
	 */
	public static int renderBegin(long enc, long[] colors, float @Nullable [][] clears, long depth, boolean clearDepth, float depthValue, int width, int height) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			long handles = stack.nmalloc(8, Math.max(1, colors.length) * 8);
			long clearData = stack.ncalloc(4, Math.max(1, colors.length) * 5, 4);
			for (int i = 0; i < colors.length; i++) {
				MemoryUtil.memPutAddress(handles + i * 8L, colors[i]);
				float[] c = clears == null ? null : clears[i];
				if (c == DONT_CARE) {
					MemoryUtil.memPutFloat(clearData + i * 20L, 2);
				} else if (c != null) {
					long at = clearData + i * 20L;
					MemoryUtil.memPutFloat(at, 1);
					for (int k = 0; k < 4; k++) MemoryUtil.memPutFloat(at + 4 + k * 4L, c[k]);
				}
			}
			return Native.renderBegin(enc, colors.length, handles, clearData, depth, clearDepth ? 1 : 0, depthValue, width, height);
		}
	}

	public static void blitTextureToTexture(long enc, long src, long dst, int mip, int width, int height) {
		Native.blitTextureToTexture(enc, src, dst, mip, 0, 0, 0, 0, width, height);
	}

	/** Forgets a pending clear of texture: about to be overwritten whole. */
	public static void dropPendingClear(Object encoder, GpuTexture texture) {
		((MetalEncoder) encoder).dropPendingClear((MetalTexture) texture);
	}

	/** Native shading only: move the deferred main-depth clear to its tile-local replacement. */
	public static float takePendingDepthClear(Object encoder, GpuTexture texture) {
		MetalTexture t = (MetalTexture) texture;
		float value = (float) t.pendingDepthClear;
		((MetalEncoder) encoder).dropPendingClear(t);
		return value;
	}

	/** The texture's contents are dead after the open render pass: it isn't stored (store action don't care). */
	public static void discard(long enc, long texture) {
		Native.discard(enc, texture);
	}

	/** A pending (deferred) clear of texture written to memory now, for reading it outside a render pass. */
	public static void flushClear(Object encoder, GpuTexture texture) {
		((MetalEncoder) encoder).flushClear(texture);
	}

	public static void index(long enc, GpuBuffer buffer, boolean intIndices) {
		Native.index(enc, ((MetalBuffer) buffer).handle, intIndices ? 1 : 0);
	}

	public static void drawIndexed(long enc, int indexCount, int instances, int firstIndex, int baseVertex, int firstInstance) {
		Native.drawIndexed(enc, indexCount, instances, firstIndex, baseVertex, firstInstance);
	}

	public static void vertexBuffer(long enc, int index, long buffer, long offset) {
		Native.vertexBuffer(enc, index, buffer, offset);
	}

	/** Binds a backend pipeline's own state (as an undelegated pass would) from a delegate that keeps the pass's attachments. */
	public static void bindOwnPipeline(long enc, Object backendPipeline, boolean hasDepth) {
		MetalPipeline p = (MetalPipeline) backendPipeline;
		Native.pipeline(enc, hasDepth ? p.withDepth : p.withoutDepth, p.depthState, p.cull ? 1 : 0, p.wireframe ? 1 : 0, p.depthBiasConstant, p.depthBiasSlope, p.primitive);
	}

	public static @Nullable String pipelineName(Object backendPipeline) {
		return backendPipeline instanceof MetalPipeline p ? p.name : null;
	}


	/**
	 * The strides of a backend pipeline's vertex bindings (26.3, E4: several game pipelines share one location — the
	 * three {@code pipeline/item_cutout}s differ in their vertex format — so the engine's plan lookup needs the
	 * strides to tell them apart). An empty array when the pipeline binds no vertex buffer.
	 */
	public static int @Nullable [] pipelineStrides(Object backendPipeline) {
		return backendPipeline instanceof MetalPipeline p ? p.vertexStrides : null;
	}

	/** Whether a render pass is open on encoder. */
	public static boolean inRenderPass(Object encoder) {
		return ((MetalEncoder) encoder).inRenderPass();
	}

	public static void release(long handle) {
		Native.release(handle);
	}

	/** The native kind of a render or compute pipeline state, for {@link #trackCount} (mcmetal.m's TK_PIPELINE). */
	public static final int TRACK_PIPELINE = 6;
	/** The native kind of a Metal library (mcmetal.m's TK_LIBRARY). */
	public static final int TRACK_LIBRARY = 7;
	/** The native kind of a depth-stencil state (mcmetal.m's TK_DEPTH). */
	public static final int TRACK_DEPTH = 8;

	/** Turns on the native count of live Metal objects by kind (the tests read it; objects made before this are not counted). */
	public static void trackOn() {
		Native.trackOn();
	}

	/** The live Metal objects of one kind that still exist (their native objects, not our handles); -1 for an unknown kind. */
	public static long trackCount(int kind) {
		return Native.trackCount(kind);
	}

	// ---- 2026-11-13 (W1-C, ADR 0006): the shader engine's window onto the backend's own functions (add-only) ----

	/**
	 * A render pipeline from two compiled libraries and the backend's int descriptor (the layout mc_pipeline_new's comment
	 * documents: vertex buffers, attributes, colour attachments, depth format, topology class). Throws with Metal's text.
	 */
	public static long pipelineNew(long ctx, long vertexLibrary, String vertexEntry, long fragmentLibrary, String fragmentEntry,
		int[] descriptor) {
		try (java.lang.foreign.Arena a = java.lang.foreign.Arena.ofConfined()) {
			long vname = a.allocateFrom(vertexEntry).address();
			long fname = a.allocateFrom(fragmentEntry).address();  // "" names no function: a depth-only pipeline
			long desc = a.allocateFrom(java.lang.foreign.ValueLayout.JAVA_INT, descriptor).address();
			long err = a.allocate(4096).address();
			long pso = Native.pipelineNew(ctx, vertexLibrary, vname, fragmentLibrary, fname, desc, err, 4096);
			if (pso == 0) throw new IllegalStateException("render pipeline failed: " + utf8(err, a));
			return pso;
		}
	}

	/**
	 * A compute pipeline from MSL source through the backend's own options (MSL 3.0, fast math: the game's pipelines'
	 * regime; the engine's own image-clear kernel uses it, pack kernels compile in the pack math mode instead).
	 * maxThreadsOut, when non-null, receives the pipeline's maxTotalThreadsPerThreadgroup. Throws with Metal's text.
	 */
	public static long computePipelineNew(long ctx, String source, String entry, int @Nullable [] maxThreadsOut) {
		try (java.lang.foreign.Arena a = java.lang.foreign.Arena.ofConfined()) {
			long src = a.allocateFrom(source).address();
			long name = a.allocateFrom(entry).address();
			var err = a.allocate(4096);
			var max = a.allocate(java.lang.foreign.ValueLayout.JAVA_INT, 4);
			long pso = Native.computePipelineNew(ctx, src, name, err.address(), 4096, max.address());
			if (pso == 0) throw new IllegalStateException("compute pipeline failed: " + utf8(err.address(), a));
			if (maxThreadsOut != null) maxThreadsOut[0] = max.get(java.lang.foreign.ValueLayout.JAVA_INT, 0);
			return pso;
		}
	}

	/** An MTLDepthStencilState: compare is the raw MTLCompareFunction, write whether the pass writes depth. */
	public static long depthStateNew(long ctx, int compare, boolean write) {
		return Native.depthStateNew(ctx, compare, write ? 1 : 0);
	}

	/** An MTLSamplerState from raw MTL values (address modes, filters, anisotropy, lod clamp). */
	public static long samplerNew(long ctx, int addressU, int addressV, int minFilter, int magFilter, int mipFilter,
		int anisotropy, float maxLod) {
		return Native.samplerNew(ctx, addressU, addressV, minFilter, magFilter, mipFilter, anisotropy, maxLod);
	}

	/** A 2D texture (one mip level) with any usage mask (raw MTLTextureUsage bits), private storage like the game's own. */
	public static long textureNew(long ctx, int format, int width, int height, int usage) {
		return Native.textureNew(ctx, format, width, height, 1, 1, usage, 0);
	}

	/** Binds a render pipeline plus its fixed state (depth state 0 = keep, cull, wireframe, bias, raw MTLPrimitiveType). */
	public static void renderPipeline(long enc, long pipeline, long depthState, boolean cull, boolean wireframe,
		float depthBiasConstant, float depthBiasSlope, int primitive) {
		Native.pipeline(enc, pipeline, depthState, cull ? 1 : 0, wireframe ? 1 : 0, depthBiasConstant, depthBiasSlope, primitive);
	}

	/**
	 * 2026-11-14 (E4, the classes check): {@link #renderPipeline} plus the front-facing winding a pipeline's convention
	 * needs. A translated pack program's epilogue flips y, which mirrors GL's counter-clockwise front faces into the
	 * encoder's clockwise ones (the same rule mcmetal.m's openRender states), so both pack and game binds take
	 * clockwise ({@code packFrontFaces} false): back-face culling then culls GL's back faces, and no bind ever leaves
	 * a foreign winding behind in the pass. The flag stays so the winding is always stated, never inherited.
	 */
	public static void renderPipeline(long enc, long pipeline, long depthState, boolean cull, boolean wireframe,
			float depthBiasConstant, float depthBiasSlope, int primitive, boolean packFrontFaces) {
		Native.pipelineW(enc, pipeline, depthState, cull ? 1 : 0, wireframe ? 1 : 0, depthBiasConstant, depthBiasSlope,
			primitive, packFrontFaces ? 1 : 0);
	}

	// 2026-11-14 (E4): a raw-handle index buffer binding for the engine's indexed draws (the per-vertex id side buffer
	// reads land at [[vertex_id]] only when the draw itself is the game's indexed draw with its base vertex).

	/** Binds an index buffer (raw handle, uint16 when intIndices is false) to the open render encoder. */
	public static void indexBuffer(long enc, long buffer, boolean intIndices) {
		Native.index(enc, buffer, intIndices ? 1 : 0);
	}

	/** Binds a buffer to one index of both stages of the open render encoder. */
	public static void renderBuffer(long enc, int index, long buffer, long offset) {
		Native.buffer(enc, index, buffer, offset);
	}

	/**
	 * Binds a buffer to one index of both stages of the open render encoder and marks it used by the submit being
	 * recorded, so a later write to it waits for the GPU's reads of it (or moves to fresh memory): see
	 * {@link MetalEncoder#writeToBuffer}. Every bind of a buffer the CPU writes later goes through here.
	 */
	public static void renderBuffer(long enc, int index, GpuBuffer buffer, long offset) {
		MetalBuffer metal = (MetalBuffer) buffer;
		metal.markUsed();
		Native.buffer(enc, index, metal.handle, offset);
	}

	/** Small uniform bytes to one index of both stages of the open render encoder (no Metal buffer object). */
	public static void renderBytes(long enc, int index, byte[] bytes) {
		try (java.lang.foreign.Arena a = java.lang.foreign.Arena.ofConfined()) {
			Native.bytes(enc, index, a.allocateFrom(java.lang.foreign.ValueLayout.JAVA_BYTE, bytes).address(), bytes.length);
		}
	}

	/** Binds a texture to one index of both stages of the open render encoder, with no sampler. */
	public static void renderTexture(long enc, int index, long texture) {
		Native.texture(enc, index, texture);
	}

	/**
	 * Binds a texture at its texture index and its sampler at each stage's own sampler slot, both stages of the open render
	 * encoder: vertexSamplerSlot and fragmentSamplerSlot are the slots the program's reflection gives the sampler in each
	 * stage (-1: the stage does not sample through it). The sampler never takes the texture's index.
	 */
	public static void renderTextureSlots(long enc, int index, long texture, long sampler, int vertexSamplerSlot, int fragmentSamplerSlot) {
		Native.textureSlots(enc, index, texture, sampler, vertexSamplerSlot, fragmentSamplerSlot);
	}

	/** drawPrimitives on the open render encoder (the raw MTLPrimitiveType was set with the pipeline). */
	public static void renderDraw(long enc, int vertexCount, int instances, int firstVertex, int firstInstance) {
		Native.draw(enc, vertexCount, instances, firstVertex, firstInstance);
	}

	public static void computePipeline(long enc, long pipeline) {
		Native.computePipeline(enc, pipeline);
	}

	public static void computeBuffer(long enc, int index, long buffer, long offset) {
		Native.computeBuffer(enc, index, buffer, offset);
	}

	public static void computeTexture(long enc, int index, long texture) {
		Native.computeTexture(enc, index, texture);
	}

	public static void computeSampler(long enc, int index, long sampler) {
		Native.computeSampler(enc, index, sampler);
	}

	/** dispatchThreadgroups with an explicit threadgroup size (grid and group given in threads). */
	public static void computeDispatch(long enc, int gx, int gy, int gz, int lx, int ly, int lz) {
		Native.dispatch(enc, gx, gy, gz, lx, ly, lz);
	}

	/** Uploads a 2D box of texels from a buffer through the frame's blit encoder (mcs_texture_upload is the synchronous path). */
	public static void blitBufferToTexture(long enc, long src, long srcOffset, int bytesPerRow, int bytesPerImage, long dst,
		int slice, int mip, int x, int y, int width, int height) {
		Native.blitBufferToTexture(enc, src, srcOffset, bytesPerRow, bytesPerImage, dst, slice, mip, x, y, width, height);
	}

	/** Ends every open encoder of a headless/test encoder, so the next commit holds everything recorded so far. */
	public static void endEncoders(long enc) {
		Native.endEncoders(enc);
	}

	// ---- 2026-11-13 (E3, ADR 0006): the shader engine's own encoders (add-only) ----

	/**
	 * Fills a native texture's mip chain on the frame's blit encoder (the engine's colour targets and shadow colour buffers).
	 * False when the format cannot be reduced on this device.
	 */
	public static boolean generateMipmaps(long enc, long texture) {
		return Native.blitGenerateMipmaps(enc, texture) != 0;
	}

	/**
	 * Uploads one uniform block as this frame's transient GPU memory (the frame's pooled blocks, reused only once the
	 * GPU has finished the frame): a memcpy into shared memory and a slice of a pooled MTLBuffer, lifetime-correct for
	 * per-frame and per-pass writes. Returns the buffer's native handle and the slice's offset in it.
	 */
	public static long[] transientUniform(Object encoder, java.nio.ByteBuffer bytes) {
		// The upload copies from the buffer's native address; a heap buffer has none (address 0: a native crash).
		if (!bytes.isDirect()) throw new IllegalArgumentException("transientUniform needs a direct ByteBuffer");
		MetalEncoder metal = (MetalEncoder) encoder;
		bytes.rewind();
		var slice = metal.transientMemory.uploadGpu(bytes, 256L,
			com.mojang.renderpearl.api.buffers.GpuBuffer.USAGE_UNIFORM, bytes.remaining(), 1L);
		return new long[] {((MetalBuffer) slice.buffer()).handle, slice.offset()};
	}

	// 2026-11-13 (E3 review): the array-out overload writes the slice into a caller-kept holder, so a steady-state
	// frame path allocates no long[] per upload.

	/** {@link #transientUniform(Object, ByteBuffer)} writing its {handle, offset} into {@code out} (kept by the caller). */
	public static void transientUniform(Object encoder, java.nio.ByteBuffer bytes, long[] out) {
		if (!bytes.isDirect()) throw new IllegalArgumentException("transientUniform needs a direct ByteBuffer");
		MetalEncoder metal = (MetalEncoder) encoder;
		bytes.rewind();
		var slice = metal.transientMemory.uploadGpu(bytes, 256L,
			com.mojang.renderpearl.api.buffers.GpuBuffer.USAGE_UNIFORM, bytes.remaining(), 1L);
		out[0] = ((MetalBuffer) slice.buffer()).handle;
		out[1] = slice.offset();
	}

	// ---- 2026-11-14 (E4, ADR 0006): the shader engine's pass split (add-only) ----

	/**
	 * Splits the open render pass of encoder onto other attachments and restores the frontend's state for it (the
	 * engine's phase split inside the game's one "Solid" pass; clears like {@link #renderBegin}: null loads,
	 * {@link #DONT_CARE} discards, else a clear colour). The depth value is read only when {@code clearDepth}.
	 */
	public static void splitRenderPass(Object encoder, long[] colors, float @Nullable [][] clears, long depth,
			boolean clearDepth, float depthValue, int width, int height) {
		((MetalEncoder) encoder).splitRenderPass(colors, clears, depth, clearDepth, depthValue, width, height);
	}

	/** A holder for a suspended pass's state, reused across frames (see {@link #suspendPass}). */
	public static Object newPassSuspension() {
		return new MetalEncoder.PassSuspension();
	}

	/**
	 * Suspends the encoder's open render pass for a pass drawn in its place (Distant Horizons' transparent call in the phase hook): the
	 * state is saved into {@code holder} (from {@link #newPassSuspension}), and {@link #resumePass} puts it back. The frontend's own
	 * in-pass flag is the caller's to suspend.
	 */
	public static void suspendPass(Object encoder, Object holder) {
		((MetalEncoder) encoder).suspendPass((MetalEncoder.PassSuspension) holder);
	}

	/** Restores the open pass {@link #suspendPass} saved; the pass continues on its attachments at the next split. */
	public static void resumePass(Object encoder, Object holder) {
		((MetalEncoder) encoder).resumePass((MetalEncoder.PassSuspension) holder);
	}

	/** The encoder's open pass as text: whether a pass is open, its lazy flag, its attachments and size (DrawLog only). */
	public static String passDescription(Object encoder) {
		return ((MetalEncoder) encoder).passDescription();
	}

	private static String utf8(long address, java.lang.foreign.Arena a) {
		return java.lang.foreign.MemorySegment.ofAddress(address).reinterpret(4096, a, null).getString(0);
	}

	/**
	 * 2026-11-14 (E4Plans, ADR 0006): inline bytes at an index from native memory the caller owns (no allocation per call,
	 * unlike {@link #renderBytes(long, int, byte[])}). Metal copies the bytes at the call.
	 */
	public static void renderBytesAt(long enc, int index, long address, int length) {
		Native.bytes(enc, index, address, length);
	}

	/** The MTLCompareFunction word of a depth compare (2026-11-14, E4Plans: the depth state's argument, MetalPipeline's table). */
	public static int compareWord(CompareOp op) {
		return MetalConst.compare(op);
	}

	/**
	 * 2026-11-17 (E9, ADR 0008): the colour of {@code texture}'s pending (deferred) clear into {@code rgba}; false, and
	 * {@code rgba} untouched, when no colour clear is pending. Reads only: the clear stays pending.
	 */
	public static boolean pendingColorClear(GpuTexture texture, float[] rgba) {
		if (!(texture instanceof MetalTexture t) || t.pendingColorClear == null) {
			return false;
		}
		rgba[0] = t.pendingColorClear.x();
		rgba[1] = t.pendingColorClear.y();
		rgba[2] = t.pendingColorClear.z();
		rgba[3] = t.pendingColorClear.w();
		return true;
	}
}
