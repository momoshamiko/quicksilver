/*
 * Derived from mcopt v0.3.0-alpha.2 (https://github.com/noahdunnagan/mcopt), metal/src/main/java/mcopt/metal/MetalRenderPass.java.
 * Copyright Noah Dunnagan, Apache License 2.0. Changed by MC Metal Shaders, 2026-10-11: package renamed to dev.mcms.metal; cut the
 * MetalTerrain hooks (the pulled-twin recording and its flush/split, the cloud culling in drawIndexed, the push-constant tracking
 * and the index/vertex-buffer state only its splits needed restored); TexelViews' A/B switch removed with its active flag. 2026-10-12
 * (M4): a texture's sampler goes to the slot the pipeline numbered for each stage (MetalPipeline.samplerSlots); the swap of a pack's
 * storage images and buffers for the frontend's placeholders (MetalHooks.storage), added with it, went at E10 with the Vitrail
 * integration that served it. 2026-11-17 (E9, ADR 0008): the game's scissor rects scale from the pass's own attachment size to a
 * smaller redirect's (constructor factors, scaleLow, scaleHigh, scissorRaw).
 */
package dev.mcms.metal;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.api.RenderPassBackend;
import com.mojang.renderpearl.util.TextureViewAndSampler;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryUtil;

/**
 * Uniforms are bound lazily at the next draw, and only when the slot's content actually changed: the frontend re-sends
 * every uniform on each pipeline switch, but Metal's argument slots survive pipeline changes, so most of that is a no-op.
 */
final class MetalRenderPass implements RenderPassBackend {
	private static final int MAX_UNIFORMS = MetalConst.PUSH_CONSTANTS_INDEX;

	private final MetalEncoder encoder;
	private final long enc;
	private final int areaX, areaY, areaWidth, areaHeight;
	private @Nullable MetalPipeline pipeline;
	private final Object[] pending = new Object[MAX_UNIFORMS];
	private final Object[] bound = new Object[MAX_UNIFORMS];
	private int dirtyUpTo;
	/** Metal draw calls issued, multi-draws counted per element, and indices drawn; for the trace. */
	int draws;
	long indices;
	/** Texel buffers need an MTLTexture view per (buffer, range); build each once per pass. */
	private final Map<GpuBufferSlice, Long> texelViews = new HashMap<>();
	/** The frontend's index buffer, restored after a split of the pass. */
	private long indexHandle;
	private int indexInt;
	/** What the frontend set in the encoder besides uniforms, to put back after the pass is split (lazy clears). */
	private final int[] scissor = new int[4];
	private final @Nullable GpuBufferSlice[] vertexBuffers = new GpuBufferSlice[4];
	private byte @Nullable [] pushedConstants;

	private final boolean hasDepth;
	/** Shaderpack runtime: draws this pass's pipelines with its own (see MetalHooks); null on the backend's own path. */
	private final @Nullable PassDelegate delegate;
	/**
	 * The depth attachment and the size of the open encoder: a split records the ones it opened on (MetalEncoder.splitTo),
	 * so a later group switch reopens on them. Depth 0 for a pass without one.
	 */
	long depth;
	int targetWidth, targetHeight;
	/** With a delegate: the current pipeline's uniform slots (index -> slot, -1 unbound); null drops its draws. */
	private int @Nullable [] slots;

	/**
	 * The factors from the pass's own attachment size to the targets it draws into (ADR 0008's render scale: a redirected
	 * world pass draws into smaller targets than the game's main target): the game's scissor rects scale by them. 1 and 1 for
	 * every pass drawing into its own attachments, and for a redirect at the same size.
	 */
	private final float scissorScaleX, scissorScaleY;

	MetalRenderPass(MetalEncoder encoder, boolean hasDepth, int x, int y, int width, int height, @Nullable PassDelegate delegate,
			long depth, int targetWidth, int targetHeight, float scissorScaleX, float scissorScaleY) {
		this.encoder = encoder;
		this.enc = encoder.enc;
		this.hasDepth = hasDepth;
		this.delegate = delegate;
		this.depth = depth;
		this.targetWidth = targetWidth;
		this.targetHeight = targetHeight;
		this.areaX = x;
		this.areaY = y;
		this.areaWidth = width;
		this.areaHeight = height;
		this.scissorScaleX = scissorScaleX;
		this.scissorScaleY = scissorScaleY;
		this.scissorRaw(x, y, width, height);
	}

	/** The low edge of a box edge scaled by s (rounded down, so the scaled box covers the scaled pixels it touches). */
	static int scaleLow(int v, float s) {
		return s == 1f ? v : Math.max(0, (int) Math.floor(v * (double) s));
	}

	/** The high edge of a box edge scaled by s (rounded up), clamped to [0, max]. */
	static int scaleHigh(int v, float s, int max) {
		int scaled = s == 1f ? v : (int) Math.ceil(v * (double) s);
		return Math.max(0, Math.min(max, scaled));
	}

	@Override
	public void pushDebugGroup(Supplier<String> label) {
	}

	@Override
	public void popDebugGroup() {
	}

	@Override
	public void setPipeline(BackendRenderPipeline pipeline) {
		MetalPipeline p = (MetalPipeline) pipeline;
		if (this.delegate != null) {
			this.pipeline = p;
			this.slots = this.delegate.setPipeline(this.enc, p, p.uniforms);
			Arrays.fill(this.pending, 0, p.uniforms.size(), null);
			Arrays.fill(this.bound, null);
			this.dirtyUpTo = p.uniforms.size();
			return;
		}
		if (this.encoder.tracing()) this.encoder.trace("  pipeline", p.name, "after draws:", this.draws, "triangles:", this.indices / 3);
		MetalPipeline previous = this.pipeline;
		this.pipeline = p;
		Native.pipeline(this.enc, this.hasDepth ? p.withDepth : p.withoutDepth, p.depthState, p.cull ? 1 : 0, p.wireframe ? 1 : 0, p.depthBiasConstant, p.depthBiasSlope, p.primitive);
		// A texture keeps its slot across pipelines, but its sampler's slot is the pipeline's own (MetalPipeline.samplerSlots): where the
		// new pipeline puts a uniform's sampler elsewhere, the same value must bind again, at the new slot.
		if (previous != null && previous != p) {
			for (int i = 0; i < this.bound.length; i++) {
				if (this.bound[i] != null && (slot(previous.vertexSamplers, i) != slot(p.vertexSamplers, i) || slot(previous.fragmentSamplers, i) != slot(p.fragmentSamplers, i))) {
					this.bound[i] = null;
				}
			}
		}
		Arrays.fill(this.pending, 0, p.uniforms.size(), null);
		this.dirtyUpTo = p.uniforms.size();
	}

	private static int slot(int[] slots, int index) {
		return index < slots.length ? slots[index] : -1;
	}

	/**
	 * Someone split this pass (ended its render encoder and reopened it on the same attachments, as the lazy-clears split
	 * does, or a group switch moved it): the reopened encoder has no state, so everything the frontend set binds again
	 * (uniforms at the next draw, the rest now). {@code reapply}: the current pipeline's state is set again here; false when
	 * the caller binds its pipeline itself after the split.
	 */
	void restoreAfterSplit(boolean reapply) {
		MetalHooks.EncoderTrace trace = MetalHooks.encoderTrace;
		if (trace != null) trace.restore(this.pipeline == null ? "none" : this.pipeline.name);
		Arrays.fill(this.bound, null);
		for (int i = this.pending.length - 1; i >= 0; i--) {
			if (this.pending[i] != null) {
				this.dirtyUpTo = Math.max(this.dirtyUpTo, i + 1);
				break;
			}
		}
		Native.scissor(this.enc, this.scissor[0], this.scissor[1], this.scissor[2], this.scissor[3]);
		for (int slot = 0; slot < this.vertexBuffers.length; slot++) {
			GpuBufferSlice vb = this.vertexBuffers[slot];
			if (vb != null) Native.vertexBuffer(this.enc, MetalConst.VERTEX_BUFFER_BASE + slot, this.encoder.use(vb.buffer()).handle, vb.offset());
		}
		if (this.indexHandle != 0) Native.index(this.enc, this.indexHandle, this.indexInt);
		if (this.pushedConstants != null && this.pushedConstants.length > 0) {
			try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
				ByteBuffer copy = stack.malloc(this.pushedConstants.length);
				copy.put(this.pushedConstants).flip();
				Native.bytes(this.enc, MetalConst.PUSH_CONSTANTS_INDEX, MemoryUtil.memAddress(copy), this.pushedConstants.length);
			}
		}
		if (reapply) this.reapplyPipeline();
	}

	/** A split opened the pass on these attachments: a later group switch reopens on them (MetalEncoder.splitTo). */
	void splitOn(long depth, int width, int height) {
		this.depth = depth;
		this.targetWidth = width;
		this.targetHeight = height;
	}

	/** The current pipeline's state again (a split ended its render encoder); the game's next draw may come without a setPipeline. */
	private void reapplyPipeline() {
		MetalPipeline p = this.pipeline;
		if (p == null) return;
		if (this.delegate != null) {
			this.slots = this.delegate.setPipeline(this.enc, p, p.uniforms);
			return;
		}
		Native.pipeline(this.enc, this.hasDepth ? p.withDepth : p.withoutDepth, p.depthState, p.cull ? 1 : 0, p.wireframe ? 1 : 0, p.depthBiasConstant, p.depthBiasSlope, p.primitive);
	}

	/** Called by the encoder when the frontend ends the pass. */
	void end() {
		if (this.delegate != null) this.delegate.end(this.enc);
	}

	/** The delegate the pass draws through, or null (a pass the frontend opened with no redirect). */
	@Nullable PassDelegate delegate() {
		return this.delegate;
	}

	/** Whether the pass has a depth attachment. */
	boolean hasDepth() {
		return this.hasDepth;
	}

	/** With a delegate: false when this draw must not be encoded (pipeline not taken over, or the delegate says so). */
	private boolean delegated() {
		return this.delegate == null || this.slots != null && this.delegate.beforeDraw(this.enc);
	}

	/**
	 * Binds one draw's uniforms, then asks the delegate for its per-draw state (2026-11-14, E4Plans): the delegate reads
	 * the draw's game blocks as the uniforms bind, so the uniforms bind first. A pipeline the delegate did not take over
	 * draws nothing. Without a delegate this is {@link #bindUniforms} alone.
	 */
	private boolean prepareDraw(int drawCount) {
		if (this.delegate != null && this.slots == null) return false;
		this.bindUniforms(drawCount);
		return this.delegated();
	}

	@Override
	public void setUniform(int index, @Nullable Object value) {
		this.pending[index] = value;
		this.dirtyUpTo = Math.max(this.dirtyUpTo, index + 1);
	}

	@Override
	public void pushConstants(ByteBuffer value) {
		int len = value.remaining();
		Native.bytes(this.enc, MetalConst.PUSH_CONSTANTS_INDEX, MemoryUtil.memAddress(value), len);
		if (len > 0 && len <= 4096) {
			byte[] saved = new byte[len];
			int pos = value.position();
			value.get(saved);
			value.position(pos);
			this.pushedConstants = saved;
		} else {
			this.pushedConstants = null;
		}
		if (this.delegate != null) this.delegate.pushConstants(value);
	}

	@Override
	public void enableScissor(int x, int y, int width, int height) {
		if (this.scissorScaleX == 1f && this.scissorScaleY == 1f) {
			this.scissorRaw(x, y, width, height);
			return;
		}
		int x0 = scaleLow(x, this.scissorScaleX), y0 = scaleLow(y, this.scissorScaleY);
		int x1 = scaleHigh(x + width, this.scissorScaleX, this.targetWidth);
		int y1 = scaleHigh(y + height, this.scissorScaleY, this.targetHeight);
		this.scissorRaw(Math.min(x0, x1), Math.min(y0, y1), Math.max(0, x1 - x0), Math.max(0, y1 - y0));
	}

	/** Sets the scissor in the targets' pixels (clamped to target bounds), and keeps it to put back after a split of the pass. */
	private void scissorRaw(int x, int y, int width, int height) {
		int tw = Math.max(0, this.targetWidth);
		int th = Math.max(0, this.targetHeight);
		int cx = Math.max(0, Math.min(x, tw));
		int cy = Math.max(0, Math.min(y, th));
		long x1 = Math.min((long) tw, (long) x + Math.max(0, width));
		long y1 = Math.min((long) th, (long) y + Math.max(0, height));
		int cw = (int) Math.max(0L, x1 - cx);
		int ch = (int) Math.max(0L, y1 - cy);
		Native.scissor(this.enc, cx, cy, cw, ch);
		this.scissor[0] = cx;
		this.scissor[1] = cy;
		this.scissor[2] = cw;
		this.scissor[3] = ch;
	}

	@Override
	public void disableScissor() {
		this.scissorRaw(this.areaX, this.areaY, this.areaWidth, this.areaHeight);
	}

	@Override
	public void setVertexBuffer(int slot, @Nullable GpuBufferSlice vertexBuffer) {
		if (vertexBuffer == null) return;
		if (MetalConst.VERTEX_BUFFER_BASE + slot > 30) throw new IllegalArgumentException("Metal backend supports vertex buffer slots 0-3, got " + slot);
		long handle = this.encoder.use(vertexBuffer.buffer()).handle;
		Native.vertexBuffer(this.enc, MetalConst.VERTEX_BUFFER_BASE + slot, handle, vertexBuffer.offset());
		if (this.delegate != null) this.delegate.vertexBuffer(slot, handle, vertexBuffer.offset());
		this.vertexBuffers[slot] = vertexBuffer;
	}

	@Override
	public void setIndexBuffer(GpuBuffer indexBuffer, IndexType indexType) {
		this.indexHandle = this.encoder.use(indexBuffer).handle;
		this.indexInt = indexType == IndexType.INT ? 1 : 0;
		Native.index(this.enc, this.indexHandle, this.indexInt);
		if (this.delegate != null) {
			this.delegate.indexBuffer(this.indexHandle, this.indexInt);  // E4 diagnostics: the draw log names the buffer
		}
	}

	private void bindUniforms(int drawCount) {
		this.draws += drawCount;
		if (this.dirtyUpTo == 0) return;
		if (this.encoder.lazyClears) {
			// a texture still pending its deferred clear is about to be sampled: write the clear first (splits this pass, then everything binds again)
			for (int i = 0; i < this.dirtyUpTo; i++) {
				if (this.pending[i] instanceof TextureViewAndSampler ts && ts.view() instanceof MetalTexture.View v && v.metalTexture().hasPendingClear()) {
					this.encoder.flushClearInPass(v.metalTexture());
				}
			}
		}
		List<BindGroupLayout.UniformDescription> uniforms = Objects.requireNonNull(this.pipeline).uniforms;
		for (int i = 0; i < this.dirtyUpTo && i < uniforms.size(); i++) {
			Object value = this.pending[i];
			if (value == null) throw new IllegalStateException("Missing uniform " + uniforms.get(i).name());
			if (value.equals(this.bound[i])) continue;
			this.bound[i] = value;
			this.bindUniform(uniforms.get(i), i, value);
		}
		this.dirtyUpTo = 0;
	}

	private void bindUniform(BindGroupLayout.UniformDescription uniform, int index, Object value) {
		// 2026-11-14 (E4): the delegate reads the game's blocks (the draw's DynamicTransforms) whatever slot it maps
		// them to, so the notification comes before the slot decision.
		if (this.delegate != null) this.delegate.uniform(this.enc, uniform.name(), value);
		int i = index;
		if (this.delegate != null) {
			if (this.slots == null || index >= this.slots.length || this.slots[index] < 0) return;
			i = this.slots[index];
		}
		switch (uniform.type()) {
			case UNIFORM_BUFFER -> {
				// 2026-11-14 (E4): the delegate reads the game's blocks (the draw's DynamicTransforms) and replaces
				// others (Projection); its slot array says where the rest binds.
				GpuBufferSlice slice = (GpuBufferSlice) value;
				Native.buffer(this.enc, i, this.encoder.use(slice.buffer()).handle, slice.offset());
			}
			case COMBINED_IMAGE_SAMPLER -> {
				TextureViewAndSampler ts = (TextureViewAndSampler) value;
				long texture = ((MetalTexture.View) ts.view()).handle;
				long sampler = ((MetalSampler) ts.sampler()).handle();
				if (this.delegate != null) {
					// The sampler goes to each stage's own slot (the reflection's), and to none where the stage has none: an entity overlay
					// reads its texture by fetch, so both are -1 and only the texture binds (never a sampler at the texture's index).
					Native.textureSlots(this.enc, i, texture, sampler, this.delegate.vertexSamplerSlot(index), this.delegate.fragmentSamplerSlot(index));
					this.delegate.texture(this.enc, i, ts.view());
				} else {
					MetalPipeline p = Objects.requireNonNull(this.pipeline);
					Native.textureSlots(this.enc, i, texture, sampler, p.vertexSamplers[index], p.fragmentSamplers[index]);
				}
				if (this.encoder.encLogOn()) this.encoder.encBind(((MetalTexture.View) ts.view()).metalTexture());
			}
			case TEXEL_BUFFER -> {
				GpuBufferSlice slice = (GpuBufferSlice) value;
				var format = Objects.requireNonNull(uniform.gpuFormat());
				long view = TexelViews.ON ? TexelViews.get(this.encoder, slice, MetalConst.pixelFormat(format), format.blockSize()) // on by default
					: this.texelViews.computeIfAbsent(slice, s -> {
					long handle = Native.textureBuffer(this.encoder.ctx, this.encoder.use(s.buffer()).handle, MetalConst.pixelFormat(format), s.offset(),
						s.length(), format.blockSize());
					this.encoder.releaseLater(handle);
					return handle;
				});
				Native.texture(this.enc, i, view);
			}
		}
	}

	@Override
	public void drawIndexed(int indexCount, int instanceCount, int firstIndex, int vertexOffset, int firstInstance) {
		if (this.delegate != null) this.delegate.drawCall(PassDelegate.DRAW_INDEXED, indexCount, instanceCount, firstIndex, vertexOffset, firstInstance);
		if (!this.prepareDraw(1)) return;
		this.indices += (long) indexCount * instanceCount;
		Native.drawIndexed(this.enc, indexCount, instanceCount, firstIndex, vertexOffset, firstInstance);
	}

	@Override
	public void multiDrawIndexed(IntBuffer drawParameters, int instanceCount, int firstInstance, int drawCount) {
		if (this.delegate != null) this.delegate.multiDrawCall(PassDelegate.MULTI_INDEXED, null, drawParameters, null, drawCount, instanceCount, firstInstance);
		if (!this.prepareDraw(drawCount)) return;
		Native.multiDrawIndexed(this.enc, MemoryUtil.memAddress(drawParameters), drawCount, instanceCount, firstInstance);
	}

	@Override
	public void multiDrawIndexed(PointerBuffer firstIndexOffsets, IntBuffer indexCounts, IntBuffer vertexOffsets, int drawCount) {
		if (this.delegate != null) this.delegate.multiDrawCall(PassDelegate.MULTI_INDEXED_SEPARATE, firstIndexOffsets, indexCounts, vertexOffsets, drawCount, 1, 0);
		if (!this.prepareDraw(drawCount)) return;
		if (this.encoder.tracing()) for (int i = 0; i < drawCount; i++) this.indices += indexCounts.get(indexCounts.position() + i);
		Native.multiDrawIndexedSeparate(this.enc, MemoryUtil.memAddress(firstIndexOffsets), MemoryUtil.memAddress(indexCounts),
			MemoryUtil.memAddress(vertexOffsets), drawCount);
	}

	@Override
	public void drawIndexedIndirect(GpuBufferSlice commands, int drawCount) {
		if (this.delegate != null) this.delegate.drawCall(PassDelegate.INDIRECT_INDEXED, drawCount, 0, 0, 0, 0);
		if (!this.prepareDraw(drawCount)) return;
		Native.drawIndexedIndirect(this.enc, this.encoder.use(commands.buffer()).handle, commands.offset(), drawCount);
	}

	@Override
	public void draw(int vertexCount, int instanceCount, int firstVertex, int firstInstance) {
		if (this.delegate != null) this.delegate.drawCall(PassDelegate.DRAW, vertexCount, instanceCount, firstVertex, 0, firstInstance);
		if (!this.prepareDraw(1)) return;
		this.indices += (long) vertexCount * instanceCount;
		Native.draw(this.enc, vertexCount, instanceCount, firstVertex, firstInstance);
	}

	@Override
	public void multiDraw(IntBuffer drawParameters, int instanceCount, int firstInstance, int drawCount) {
		if (this.delegate != null) this.delegate.multiDrawCall(PassDelegate.MULTI, null, drawParameters, null, drawCount, instanceCount, firstInstance);
		if (!this.prepareDraw(drawCount)) return;
		Native.multiDraw(this.enc, MemoryUtil.memAddress(drawParameters), drawCount, instanceCount, firstInstance);
	}

	@Override
	public void multiDraw(IntBuffer firstVertices, IntBuffer vertexCounts, int drawCount) {
		if (this.delegate != null) this.delegate.multiDrawCall(PassDelegate.MULTI_SEPARATE, null, firstVertices, vertexCounts, drawCount, 1, 0);
		if (!this.prepareDraw(drawCount)) return;
		Native.multiDrawSeparate(this.enc, MemoryUtil.memAddress(firstVertices), MemoryUtil.memAddress(vertexCounts), drawCount);
	}

	@Override
	public void drawIndirect(GpuBufferSlice commands, int drawCount) {
		if (this.delegate != null) this.delegate.drawCall(PassDelegate.INDIRECT, drawCount, 0, 0, 0, 0);
		if (!this.prepareDraw(drawCount)) return;
		Native.drawIndirect(this.enc, this.encoder.use(commands.buffer()).handle, commands.offset(), drawCount);
	}

	@Override
	public void writeTimestamp(GpuQueryPool pool, int index) {
	}
}
