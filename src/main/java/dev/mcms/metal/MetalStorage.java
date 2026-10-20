/*
 * MC Metal Shaders, Apache License 2.0. Original code (2026-10-12, M4).
 */
package dev.mcms.metal;

import com.mojang.renderpearl.api.GpuFormat;

/**
 * The backend half of a shaderpack engine's storage seam (spec §5 part 2a): what Vitrail creates and records through Vulkan for a
 * pack's storage images, storage buffers and compute passes, done on this backend's device and in its frame's command buffer. The
 * engine half (which image, when to clear it, what a dispatch binds) stays the engine's: mixin.Vitrail*Mixin and the classes they
 * call. Every recording call needs the frontend's render pass closed, as Vulkan needs it outside a render pass: a native encoder
 * switch inside an open pass would leave the pass without its render encoder, so these calls refuse to run there.
 */
public final class MetalStorage {
	/** Texture types for newImage (MTLTextureType). */
	public static final int SHAPE_1D = 0, SHAPE_2D = 2, SHAPE_3D = 7;
	/** Channel kinds for clearImage. */
	public static final int KIND_FLOAT = 0, KIND_UINT = 1, KIND_SINT = 2;

	private MetalStorage() {
	}

	/** Whether the game draws through this backend (its device exists and is open). */
	public static boolean active() {
		return MetalDevice.current != null;
	}

	static MetalDevice device() {
		MetalDevice device = MetalDevice.current;
		if (device == null) throw new IllegalStateException("mcms-metal: no Metal device");
		return device;
	}

	static long recording() {
		MetalEncoder encoder = device().encoder();
		if (encoder.inRenderPass()) throw new IllegalStateException("mcms-metal: storage work recorded inside an open render pass");
		return encoder.enc;
	}

	/**
	 * A storage image: read and written by shaders, private to the GPU, contents undefined until cleared. otherViews: it will also be
	 * read as other pixel formats (an engine's typed views), which Metal must know when it is created.
	 */
	public static long newImage(GpuFormat format, int shape, int width, int height, int depth, boolean otherViews) {
		int usage = 1 | 2 | (otherViews ? 0x10 : 0);
		long handle = Native.textureNewShaped(device().ctx(), MetalConst.pixelFormat(format), shape, width, height, depth, usage);
		if (handle == 0) throw new IllegalStateException("Metal gave no " + width + "x" + height + "x" + depth + " " + format + " storage image");
		return handle;
	}

	/** The same texels read as another pixel format; the image was made with otherViews. */
	public static long imageView(long image, GpuFormat format) {
		long view = Native.textureViewFormat(image, MetalConst.pixelFormat(format));
		if (view == 0) throw new IllegalStateException("Metal gave no " + format + " view of a storage image");
		return view;
	}

	/** Every texel to zero, in this frame's command buffer (kind: KIND_FLOAT for float and normalized formats, else the integer kind). */
	public static void clearImage(long image, int kind) {
		Native.clearStorage(recording(), image, kind);
	}

	/** Copies a box of texels from src at (sx, sy, sz) to dst at (dx, dy, dz), in this frame's command buffer. */
	public static void copyBox(long src, long dst, int sx, int sy, int sz, int dx, int dy, int dz, int width, int height, int depth) {
		Native.blitTextureBox(recording(), src, dst, sx, sy, sz, dx, dy, dz, width, height, depth);
	}

	/** Ends the open native encoder, so what follows is ordered after what came before (Metal orders across encoders, not in one). */
	public static void endEncoders() {
		Native.endEncoders(recording());
	}

	/** A storage buffer only the GPU reads and writes; contents undefined until zeroed. */
	public static long newBuffer(long bytes) {
		long handle = Native.bufferNewPrivate(device().ctx(), bytes);
		if (handle == 0) throw new IllegalStateException("Metal gave no storage buffer of " + bytes + " bytes");
		return handle;
	}

	/** Zeros bytes of buffer from its start, in this frame's command buffer. */
	public static void zeroBuffer(long buffer, long bytes) {
		Native.fillBuffer(recording(), buffer, 0, bytes, 0);
	}

	/** Releases a handle these calls returned once the frames that may still use it are done; at once if the device is gone. */
	public static void releaseAfterGpu(long handle) {
		MetalDevice device = MetalDevice.current;
		if (device != null) device.encoder().releaseLater(handle);
		else Native.release(handle);
	}

	/** Runs r once the frame being recorded is done on the GPU (an engine's deferred destruction); dropped if the device is gone. */
	public static void afterGpu(Runnable r) {
		MetalDevice device = MetalDevice.current;
		if (device != null) device.encoder().afterGpuFinishes(r);
	}

	/** Ends the frontend's render pass on this backend if one is open (what Vitrail's GpuRecording.endPass does on Vulkan). */
	public static void endPass() {
		MetalEncoder encoder = device().encoder();
		if (encoder.inRenderPass()) encoder.submitRenderPass();
	}

	/**
	 * Whether a shader can write this format here (an engine's "storage image" format question). Apple GPU families 7 and up give
	 * every uncompressed colour format this backend maps the Write capability (Metal feature set tables, pixel format capabilities),
	 * the answer MoltenVK turns into VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT on this Mac; depth and stencil formats have none.
	 */
	public static boolean writable(GpuFormat format) {
		return switch (format) {
			case D16_UNORM, D32_FLOAT, S8_UINT, D32_FLOAT_S8_UINT, D24_UNORM_S8_UINT -> false;
			default -> {
				try {
					MetalConst.pixelFormat(format);
					yield true;
				} catch (IllegalArgumentException unmapped) {
					yield false;
				}
			}
		};
	}
}
