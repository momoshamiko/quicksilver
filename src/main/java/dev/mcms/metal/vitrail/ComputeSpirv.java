/*
 * MC Metal Shaders, Apache License 2.0. Original code (2026-10-12, M4).
 */
package dev.mcms.metal.vitrail;

import java.nio.ByteBuffer;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

/**
 * The renumbered SPIR-V of the compute module Vitrail's ComputeShader.compile just numbered on Metal (mixin.VitrailComputeShaderMixin),
 * kept for McmsMetalCompute to translate: the module itself is closed when ComputeShader.build returns. Per thread, one at a time.
 */
public final class ComputeSpirv {
	private static final ThreadLocal<ByteBuffer> KEPT = new ThreadLocal<>();

	private ComputeSpirv() {
	}

	/** Keeps a copy of spv (native memory; the taker frees it). */
	public static void keep(ByteBuffer spv) {
		discard();
		ByteBuffer copy = MemoryUtil.memAlloc(spv.remaining());
		MemoryUtil.memCopy(spv, copy);
		KEPT.set(copy);
	}

	/** The kept copy, which the caller frees with MemoryUtil.memFree; null if none. */
	public static @Nullable ByteBuffer take() {
		ByteBuffer kept = KEPT.get();
		KEPT.remove();
		return kept;
	}

	public static void discard() {
		ByteBuffer kept = take();
		if (kept != null) MemoryUtil.memFree(kept);
	}
}
