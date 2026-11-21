/*
 * Derived from mcopt v0.3.0-alpha.2 (https://github.com/noahdunnagan/mcopt), metal/src/main/java/mcopt/metal/PassDelegate.java.
 * Copyright Noah Dunnagan, Apache License 2.0. Changed by MC Metal Shaders, 2026-10-11: package renamed to dev.mcms.metal; inert in this build (nothing installs a delegate).
 */
package dev.mcms.metal;

import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.lwjgl.PointerBuffer;

/**
 * Takes over a render pass's pipelines for the shaderpack runtime (the shaderpack runtime): the pass's attachments were
 * redirected (see MetalHooks), so the game's pipelines can't draw into it as they are; the delegate binds its own
 * pipeline state for each one and says where the game's uniforms go.
 */
public interface PassDelegate {
	/**
	 * The game set pipeline (a backend pipeline) on the pass whose native encoder is enc. The delegate binds its own
	 * pipeline state and resources, and returns, per entry of uniforms (the pipeline's own layout), the argument-table slot
	 * to bind it at (-1: don't bind); null makes the pass drop this pipeline's draws.
	 */
	int @Nullable [] setPipeline(long enc, Object pipeline, List<BindGroupLayout.UniformDescription> uniforms);

	/** The pass began on the native encoder enc, with or without a depth attachment. */
	default void begin(long enc, boolean hasDepth) {
	}

	/**
	 * The pass began on the native encoder enc (with or without a depth attachment), and {@code splitter} splits the open
	 * pass onto other colour attachments until it ends (2026-11-15: a delegate whose pipelines need another attachment set
	 * than the pass opened with). The default ignores the splitter.
	 */
	default void begin(long enc, boolean hasDepth, PassSplitter splitter) {
		begin(enc, hasDepth);
	}

	/**
	 * The pass continues after another pass was drawn in its place on the same delegate (MetalEncoder.resumePass: Distant
	 * Horizons' water inside the engine's phase hook), which ended the delegate: the delegate takes the pass's state back as
	 * {@link #begin(long, boolean, PassSplitter)} sets it. The default begins again; a delegate that logs its passes overrides
	 * this so the resumed pass is not logged as a new one.
	 */
	default void resume(long enc, boolean hasDepth, PassSplitter splitter) {
		begin(enc, hasDepth, splitter);
	}

	/** Splits the open render pass onto other colour attachments (see {@link #begin(long, boolean, PassSplitter)}). */
	interface PassSplitter {
		/**
		 * Splits the open pass onto {@code colors}, one native colour view per attachment in attachment order. The new
		 * attachments load, the pass keeps its depth attachment and size, and the backend restores the frontend's state (the
		 * delegate's {@link #setPipeline} runs again, so the state it sets must already name the new attachments).
		 */
		void split(long[] colors);
	}

	/** A texture (a GpuTextureView) was bound at slot for the current pipeline, before the draw that uses it. */
	default void texture(long enc, int slot, Object view) {
	}

	/**
	 * 2026-11-14 (E4, ADR 0006): the pass set a uniform buffer (the game's block value, a {@code GpuBufferSlice}) for the
	 * current pipeline, by its uniform name. The engine's delegate reads the draw's {@code DynamicTransforms} here and
	 * substitutes its own {@code Projection}.
	 */
	default void uniform(long enc, String name, Object value) {
	}

	/**
	 * 2026-11-14 (E4, ADR 0006): the pass bound a vertex buffer at slot (its native handle and offset). A delegate whose
	 * pipelines read vertex data at another buffer index rebinds it there before its draws.
	 */
	default void vertexBuffer(int slot, long buffer, long offset) {
	}

	/**
	 * 2026-11-14 (E4 diagnostics): the pass bound an index buffer (its native handle; {@code indexType} 1 for 32-bit
	 * indices, 0 for 16-bit) before its indexed draws. A delegate that does not log ignores it.
	 */
	default void indexBuffer(long buffer, int indexType) {
	}

	/**
	 * The vertex-stage sampler slot the uniform's texture binds its sampler at on the delegate's pipeline (the reflection's
	 * sampler index in that stage), or -1 when the vertex stage does not sample through it. Asked once per texture binding.
	 */
	default int vertexSamplerSlot(int uniformIndex) {
		return -1;
	}

	/** The fragment-stage sampler slot of the uniform's texture, as vertexSamplerSlot; -1 when the fragment stage has none. */
	default int fragmentSamplerSlot(int uniformIndex) {
		return -1;
	}

	/** Kinds of draw call reported to {@link #drawCall} and {@link #multiDrawCall} (2026-11-14, E4 diagnostics). */
	int DRAW_INDEXED = 0, DRAW = 1, MULTI_INDEXED = 2, MULTI_INDEXED_SEPARATE = 3, MULTI = 4, MULTI_SEPARATE = 5,
		INDIRECT_INDEXED = 6, INDIRECT = 7;

	/**
	 * 2026-11-14 (E4 diagnostics): a single draw's arguments, reported before its uniforms bind. A delegate that does not
	 * log ignores them. {@code vertexOffset} is the base vertex of an indexed draw.
	 */
	default void drawCall(int kind, int count, int instances, int first, int vertexOffset, int firstInstance) {
	}

	/**
	 * 2026-11-14 (E4 diagnostics): a multi-draw's arguments, reported before its uniforms bind. The buffers per kind:
	 * {@link #MULTI_INDEXED} triples (first index, index count, base vertex) in {@code a}; {@link #MULTI_INDEXED_SEPARATE}
	 * byte offsets in {@code offsets}, index counts in {@code a}, base vertices in {@code b}; {@link #MULTI} pairs (first
	 * vertex, vertex count) in {@code a}; {@link #MULTI_SEPARATE} first vertices in {@code a}, vertex counts in {@code b}.
	 * Each buffer is read from its position.
	 */
	default void multiDrawCall(int kind, @Nullable PointerBuffer offsets, @Nullable IntBuffer a, @Nullable IntBuffer b,
			int drawCount, int instances, int firstInstance) {
	}

	/**
	 * 2026-11-14 (E4 diagnostics): the frontend pushed constants (bound at the push-constant slot, 26). The bytes are read
	 * before the call returns; a delegate that does not log ignores them.
	 */
	default void pushConstants(ByteBuffer value) {
	}

	/** Right before each draw, for per-draw state. false drops the draw. */
	boolean beforeDraw(long enc);
	/** The pass ended. */
	void end(long enc);
}
