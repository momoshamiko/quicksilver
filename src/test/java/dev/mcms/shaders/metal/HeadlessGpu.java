// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.metal;

import dev.mcms.metal.MetalBridge;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;

/**
 * The headless GPU test device (ADR 0006, W1-C): compiles MSL, runs compute kernels and renders full-screen passes on the
 * real Metal device with no game and no window, through the backend's headless context. Every call releases every GPU object
 * it created before returning, on failure too. The translator's GPU tests reuse this device; expected values are computed
 * in Java.
 *
 * <p>All instances share one static context and encoder (creating a context allocates the backend's built-in library,
 * present pipeline, queue and ~786 KB index buffer; tests make many short-lived instances).
 */
public final class HeadlessGpu implements AutoCloseable {
	/** Raw MTLPixelFormat values (Metal.framework Headers/MTLPixelFormat.h), float and integer formats. */
	public static final int R8_SINT = 14;
	public static final int R32_UINT = 53, R32_SINT = 54, R32_FLOAT = 55, RGBA8_UNORM = 70, RGBA8_UINT = 73,
		RG32_UINT = 103, RG32_FLOAT = 105, RGBA16_FLOAT = 115, RGBA32_UINT = 123, RGBA32_FLOAT = 125,
		DEPTH32_FLOAT = 252;

	/** A compile: Metal's diagnostics when it failed, the library's entry-point names when it succeeded. */
	public record CompileResult(boolean ok, @Nullable String diagnostics, List<String> functions) {
	}

	/**
	 * A texture created from bytes: format is a raw MTLPixelFormat (Depth32Float binds as depth2d), type a raw MTLTextureType
	 * (2 = 2D, 3 = 2D array with depth as the layer count, 7 = 3D, 9 = texture buffer with width texels), texels row-major,
	 * z-major images for 3D.
	 */
	public record InputTexture(int format, int type, int width, int height, int depth, byte[] texels) {
		public static InputTexture tex2d(int format, int width, int height, byte[] texels) {
			return new InputTexture(format, 2, width, height, 1, texels);
		}

		public static InputTexture tex3d(int format, int width, int height, int depth, byte[] texels) {
			return new InputTexture(format, 7, width, height, depth, texels);
		}

		/** A texture buffer: count texels of format over a copy of texels (MSL texture_buffer). */
		public static InputTexture texelBuffer(int format, int count, byte[] texels) {
			return new InputTexture(format, ShaderNative.TYPE_BUFFER, count, 1, 1, texels);
		}
	}

	/** The raw MTL values this device sets up itself (Metal.framework headers); only what it needs. */
	private static final int USAGE_SHADER_READ = 1, USAGE_RENDER_TARGET = 4;
	private static final int STORAGE_PRIVATE = 2;
	private static final int WRITE_MASK_ALL = 15;
	private static final int PRIMITIVE_TRIANGLE = 3;  // MTLPrimitiveType
	private static final int TOPOLOGY_TRIANGLE = 3;   // MTLPrimitiveTopologyClass

	private static long ctx, enc;  // shared: one headless device per test process

	private int languageVersion = ShaderNative.MSL_3_1, mathMode = ShaderNative.MATH_PACKS;
	private boolean preserveInvariance = true;

	/** Overrides the compile options this device compiles with (defaults: the engine's, see ShaderNative's class comment). */
	public HeadlessGpu with(int languageVersion, int mathMode, boolean preserveInvariance) {
		this.languageVersion = languageVersion;
		this.mathMode = mathMode;
		this.preserveInvariance = preserveInvariance;
		return this;
	}

	/** Compiles with the engine's options (MSL 3.1, fast math, invariance preserved: ShaderNative's class comment). */
	public CompileResult compile(String msl) {
		return compile(msl, languageVersion, mathMode, preserveInvariance);
	}

	public CompileResult compile(String msl, int languageVersion, int mathMode, boolean preserveInvariance) {
		ensure();
		ShaderNative.Compiled lib = ShaderNative.libraryNew(ctx, msl, languageVersion, mathMode, preserveInvariance);
		if (!lib.ok()) return new CompileResult(false, lib.error(), List.of());
		List<String> names = ShaderNative.functionNames(lib.library());
		MetalBridge.release(lib.library());
		return new CompileResult(true, null, names);
	}

	/** The device's context, for tests that need ShaderNative helpers without their own device methods. */
	public long context() {
		ensure();
		return ctx;
	}

	/** The shared headless encoder handle, for tests that encode with the backend's own render calls. */
	public long encoder() {
		ensure();
		return enc;
	}

	/**
	 * Compiles a kernel, binds the input buffers' bytes and then the output buffers in order at buffer slots 0..n, dispatches
	 * gx * gy * gz threadgroups of lx * ly * lz threads (the backend's dispatchThreadgroups) and returns the outputs' bytes.
	 * Textures (bound at texture slots 0..n) may be null.
	 */
	public byte[][] runKernel(String msl, String entry, @Nullable InputTexture[] textures, byte[][] inputs,
		int[] outputSizes, int gx, int gy, int gz, int lx, int ly, int lz) {
		ensure();
		InputTexture[] tex = textures == null ? new InputTexture[0] : textures;
		long[] handles = new long[tex.length];
		try {
			for (int i = 0; i < tex.length; i++) handles[i] = uploadTexture(tex[i]);
			return runKernelOn(msl, entry, handles, inputs, outputSizes, gx, gy, gz, lx, ly, lz);
		} finally {
			for (long handle : handles) if (handle != 0) MetalBridge.release(handle);
		}
	}

	/**
	 * Like runKernel, but binds textures the caller created (handles from this device's texture creation, for example
	 * ShaderNative.textureBufferNew) at texture slots 0..n. The caller releases them.
	 */
	public byte[][] runKernelOn(String msl, String entry, long[] textures, byte[][] inputs, int[] outputSizes, int gx,
		int gy, int gz, int lx, int ly, int lz) {
		ensure();
		ShaderNative.Compiled lib = ShaderNative.libraryNew(ctx, msl, languageVersion, mathMode, preserveInvariance);
		if (!lib.ok()) throw new IllegalStateException("kernel failed to compile: " + lib.error());
		try {
			ShaderNative.ComputePipeline pso = ShaderNative.computePipelineNew(ctx, lib.library(), entry);
			if (!pso.ok()) throw new IllegalStateException("kernel entry '" + entry + "': " + pso.error());
			long[] buffers = new long[inputs.length + outputSizes.length];
			try {
				for (int i = 0; i < inputs.length; i++) {
					buffers[i] = MetalBridge.newBuffer(ctx, Math.max(16, inputs[i].length));
					write(buffers[i], inputs[i]);
				}
				for (int i = 0; i < outputSizes.length; i++)
					buffers[inputs.length + i] = MetalBridge.newBuffer(ctx, Math.max(16, outputSizes[i]));
				MetalBridge.computePipeline(enc, pso.pipeline());
				for (int i = 0; i < textures.length; i++) MetalBridge.computeTexture(enc, i, textures[i]);
				for (int i = 0; i < buffers.length; i++) MetalBridge.computeBuffer(enc, i, buffers[i], 0);
				MetalBridge.computeDispatch(enc, gx, gy, gz, lx, ly, lz);
				MetalBridge.commitAndWait(enc);
				byte[][] out = new byte[outputSizes.length][];
				for (int i = 0; i < outputSizes.length; i++) out[i] = read(buffers[inputs.length + i], outputSizes[i]);
				return out;
			} finally {
				for (long buffer : buffers) if (buffer != 0) MetalBridge.release(buffer);
				MetalBridge.release(pso.pipeline());
			}
		} finally {
			MetalBridge.release(lib.library());
		}
	}

	/**
	 * Like runKernel without textures, but each buffer binds at its own index: {@code slots[i]} for the i-th input, then the
	 * outputs in order. The indices may be sparse (a storage block at binding 1, the spill buffer at its own slot), which the
	 * sequential runKernel cannot express.
	 */
	public byte[][] runKernelAt(String msl, String entry, int[] slots, byte[][] inputs, int[] outputSizes, int gx, int gy,
		int gz, int lx, int ly, int lz) {
		ensure();
		ShaderNative.Compiled lib = ShaderNative.libraryNew(ctx, msl, languageVersion, mathMode, preserveInvariance);
		if (!lib.ok()) throw new IllegalStateException("kernel failed to compile: " + lib.error());
		try {
			ShaderNative.ComputePipeline pso = ShaderNative.computePipelineNew(ctx, lib.library(), entry);
			if (!pso.ok()) throw new IllegalStateException("kernel entry '" + entry + "': " + pso.error());
			long[] buffers = new long[inputs.length + outputSizes.length];
			try {
				for (int i = 0; i < inputs.length; i++) {
					buffers[i] = MetalBridge.newBuffer(ctx, Math.max(16, inputs[i].length));
					write(buffers[i], inputs[i]);
				}
				for (int i = 0; i < outputSizes.length; i++)
					buffers[inputs.length + i] = MetalBridge.newBuffer(ctx, Math.max(16, outputSizes[i]));
				MetalBridge.computePipeline(enc, pso.pipeline());
				for (int i = 0; i < buffers.length; i++) MetalBridge.computeBuffer(enc, slots[i], buffers[i], 0);
				MetalBridge.computeDispatch(enc, gx, gy, gz, lx, ly, lz);
				MetalBridge.commitAndWait(enc);
				byte[][] out = new byte[outputSizes.length][];
				for (int i = 0; i < outputSizes.length; i++) out[i] = read(buffers[inputs.length + i], outputSizes[i]);
				return out;
			} finally {
				for (long buffer : buffers) if (buffer != 0) MetalBridge.release(buffer);
				MetalBridge.release(pso.pipeline());
			}
		} finally {
			MetalBridge.release(lib.library());
		}
	}

	/**
	 * Renders one full-screen triangle (the vertex function receives [[vertex_id]] 0..2 and no vertex buffers) into the
	 * given colour target formats. Input textures are created from their bytes and bound at texture slots 0..n; the uniform
	 * bytes go to buffer slot 0 of both stages, extra stage buffers (storage pointers the functions take) to slots 1..n.
	 * Returns each target's pixels, row-major.
	 */
	public byte[][] renderFullscreen(String msl, String vertexEntry, String fragmentEntry, int[] targetFormats, int width,
		int height, @Nullable InputTexture[] inputs, byte[] uniforms, byte @Nullable [][] stageBuffers) {
		return renderFullscreenCleared(msl, vertexEntry, fragmentEntry, targetFormats, width, height, inputs, uniforms,
			stageBuffers, null);
	}

	/**
	 * Like renderFullscreen, but every colour target is cleared to clearRgba first (null: no clear, the values of a
	 * pixel the program leaves untouched, for example one it discards, are whatever the target held). Discard tests need
	 * the clear value to tell a discarded pixel from a written one.
	 */
	public byte[][] renderFullscreenCleared(String msl, String vertexEntry, String fragmentEntry, int[] targetFormats,
		int width, int height, @Nullable InputTexture[] inputs, byte[] uniforms, byte @Nullable [][] stageBuffers,
		float @Nullable [] clearRgba) {
		ensure();  // the device must exist before the textures are uploaded
		InputTexture[] tex = inputs == null ? new InputTexture[0] : inputs;
		long[] handles = new long[tex.length];
		try {
			for (int i = 0; i < tex.length; i++) handles[i] = uploadTexture(tex[i]);
			return drawFullscreen(msl, vertexEntry, fragmentEntry, targetFormats, width, height, handles, uniforms,
				stageBuffers, clearRgba, null);
		} finally {
			for (long handle : handles) if (handle != 0) MetalBridge.release(handle);
		}
	}

	/**
	 * Like renderFullscreen, but binds textures the caller created (ShaderNative's or this device's handles, for example
	 * mipmapped or cube textures) at texture slots 0..n. The caller releases them.
	 */
	public byte[][] renderFullscreenOn(String msl, String vertexEntry, String fragmentEntry, int[] targetFormats, int width,
		int height, long[] textures, byte[] uniforms, byte @Nullable [][] stageBuffers) {
		return drawFullscreen(msl, vertexEntry, fragmentEntry, targetFormats, width, height, textures, uniforms,
			stageBuffers, null, null);
	}

	/**
	 * Like renderFullscreenCleared with no textures, but attachment k writes with writeMasks[k] (renderpearl's RGBA bits: 15 all,
	 * 0 none). Metal checks a fragment output's type against its attachment's format even when the mask is 0, so a float output
	 * on an integer attachment is refused whatever the mask.
	 */
	public byte[][] renderFullscreenWithWriteMasks(String msl, String vertexEntry, String fragmentEntry, int[] targetFormats,
		int[] writeMasks, int width, int height, float[] clearRgba) {
		return drawFullscreen(msl, vertexEntry, fragmentEntry, targetFormats, width, height, new long[0], new byte[16], null,
			clearRgba, writeMasks);
	}

	private byte[][] drawFullscreen(String msl, String vertexEntry, String fragmentEntry, int[] targetFormats, int width,
		int height, long[] textures, byte[] uniforms, byte @Nullable [][] stageBuffers, float @Nullable [] clearRgba,
		int @Nullable [] writeMasks) {
		ensure();
		ShaderNative.Compiled lib = ShaderNative.libraryNew(ctx, msl, languageVersion, mathMode, preserveInvariance);
		if (!lib.ok()) throw new IllegalStateException("render source failed to compile: " + lib.error());
		try {
			long pso = MetalBridge.pipelineNew(ctx, lib.library(), vertexEntry, lib.library(), fragmentEntry,
				colorDescriptor(targetFormats, writeMasks));
			long[] targets = new long[targetFormats.length];
			byte[][] stages = stageBuffers == null ? new byte[0][] : stageBuffers;
			long[] stageHandles = new long[stages.length];
			try {
				for (int i = 0; i < targets.length; i++)
					targets[i] = checkedTexture(targetFormats[i], 2, width, height, 1, USAGE_RENDER_TARGET | USAGE_SHADER_READ);
				float[][] clears = new float[targetFormats.length][];
				Arrays.fill(clears, MetalBridge.DONT_CARE);  // the triangle covers every pixel
				if (clearRgba != null) Arrays.fill(clears, clearRgba);
				MetalBridge.renderBegin(enc, targets, clears, 0, false, 0, width, height);
				MetalBridge.renderPipeline(enc, pso, 0, false, false, 0, 0, PRIMITIVE_TRIANGLE);
				MetalBridge.renderBytes(enc, 0, uniforms);
				for (int i = 0; i < stages.length; i++) {
					stageHandles[i] = MetalBridge.newBuffer(ctx, Math.max(16, stages[i].length));
					write(stageHandles[i], stages[i]);
					MetalBridge.renderBuffer(enc, i + 1, stageHandles[i], 0);
				}
				for (int i = 0; i < textures.length; i++) MetalBridge.renderTexture(enc, i, textures[i]);
				MetalBridge.renderDraw(enc, 6, 1, 0, 0);  // the translated full-screen quad (six vertices); a 3-corner shader overdraws
				MetalBridge.endEncoders(enc);
				byte[][] pixels = new byte[targetFormats.length][];
				for (int i = 0; i < targets.length; i++)
					pixels[i] = readback(targets[i], width, height, bytesPerPixel(targetFormats[i]));
				return pixels;
			} finally {
				for (long handle : stageHandles) if (handle != 0) MetalBridge.release(handle);
				for (long target : targets) if (target != 0) MetalBridge.release(target);
				MetalBridge.release(pso);
			}
		} finally {
			MetalBridge.release(lib.library());
		}
	}

	/**
	 * Renders the same full-screen triangle into a Depth32Float target with no colour attachments (a depth-only pipeline;
	 * fragmentEntry may be null) and returns the depth value per pixel. Compiles with the options set by {@link #with}.
	 */
	public float[] renderDepthOnly(String msl, String vertexEntry, @Nullable String fragmentEntry, int width, int height,
		byte[] uniforms) {
		ensure();
		ShaderNative.Compiled lib = ShaderNative.libraryNew(ctx, msl, languageVersion, mathMode, preserveInvariance);
		if (!lib.ok()) throw new IllegalStateException("depth source failed to compile: " + lib.error());
		try {
			// Descriptor: no vertex buffers, no attributes, no colours, Depth32Float, triangle topology.
			int[] descriptor = {0, 0, 0, DEPTH32_FLOAT, TOPOLOGY_TRIANGLE};
			long pso = MetalBridge.pipelineNew(ctx, lib.library(), vertexEntry, lib.library(),
				fragmentEntry == null ? "" : fragmentEntry, descriptor);
			long depth = checkedTexture(DEPTH32_FLOAT, 2, width, height, 1, USAGE_RENDER_TARGET | USAGE_SHADER_READ);
			// Without a depth state Metal compares Always and writes nothing: bind an always-write state.
			long depthState = MetalBridge.depthStateNew(ctx, 7 /* MTLCompareFunctionAlways */, true);
			try {
				MetalBridge.renderBegin(enc, new long[0], new float[0][], depth, true, 1.0f, width, height);
				MetalBridge.renderPipeline(enc, pso, depthState, false, false, 0, 0, PRIMITIVE_TRIANGLE);
				MetalBridge.renderBytes(enc, 0, uniforms);
				MetalBridge.renderDraw(enc, 6, 1, 0, 0);
				MetalBridge.endEncoders(enc);
				byte[] bytes = readback(depth, width, height, 4);
				float[] out = new float[width * height];
				ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(out);
				return out;
			} finally {
				MetalBridge.release(depthState);
				MetalBridge.release(depth);
				MetalBridge.release(pso);
			}
		} finally {
			MetalBridge.release(lib.library());
		}
	}

	/** MTLVertexFormatFloat2 (Metal.framework Headers/MTLVertexDescriptor.h), the helper below draws positions with it. */
	public static final int VERTEX_FORMAT_FLOAT2 = 29;

	/**
	 * Renders one indexed draw into one colour target: float2 positions from a vertex buffer at vertex buffer 0
	 * (attribute 0, the given stride), an extra vertex-stage buffer (a per-vertex side buffer) at idSlot, uint16
	 * indices read from firstIndex on, with the draw's baseVertex. Returns the target's pixels, row-major. The
	 * per-entity id GPU tests use this to prove the side buffer is read at [[vertex_id]] (index plus base vertex).
	 */
	public byte[] drawIndexed(String msl, String vertexEntry, String fragmentEntry, int targetFormat, int width,
			int height, byte[] vertices, int stride, byte[] idBytes, int idSlot, short[] indices, int indexCount,
			int firstIndex, int baseVertex) {
		ensure();
		ShaderNative.Compiled lib = ShaderNative.libraryNew(ctx, msl, languageVersion, mathMode, preserveInvariance);
		if (!lib.ok()) throw new IllegalStateException("render source failed to compile: " + lib.error());
		try {
			int[] descriptor = {1, 0, stride, 0, 1, 0, 0, 0, VERTEX_FORMAT_FLOAT2, 1, targetFormat, WRITE_MASK_ALL,
				0, 0, 0, 0, 0, 0, 0, 0, 0, 3};
			long pso = MetalBridge.pipelineNew(ctx, lib.library(), vertexEntry, lib.library(), fragmentEntry,
				descriptor);
			long target = 0, vertexBuffer = 0, idBuffer = 0, indexBuffer = 0;
			try {
				target = checkedTexture(targetFormat, 2, width, height, 1, USAGE_RENDER_TARGET | USAGE_SHADER_READ);
				vertexBuffer = MetalBridge.newBuffer(ctx, Math.max(16, vertices.length));
				write(vertexBuffer, vertices);
				idBuffer = MetalBridge.newBuffer(ctx, Math.max(16, idBytes.length));
				write(idBuffer, idBytes);
				byte[] indexBytes = new byte[indices.length * 2];
				java.nio.ByteBuffer.wrap(indexBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(indices);
				indexBuffer = MetalBridge.newBuffer(ctx, Math.max(16, indexBytes.length));
				write(indexBuffer, indexBytes);
				MetalBridge.renderBegin(enc, new long[] {target}, new float[][] {MetalBridge.DONT_CARE}, 0, false, 0,
					width, height);
				MetalBridge.renderPipeline(enc, pso, 0, false, false, 0, 0, PRIMITIVE_TRIANGLE);
				MetalBridge.vertexBuffer(enc, 0, vertexBuffer, 0);
				MetalBridge.vertexBuffer(enc, idSlot, idBuffer, 0);
				MetalBridge.indexBuffer(enc, indexBuffer, false);
				MetalBridge.drawIndexed(enc, indexCount, 1, firstIndex, baseVertex, 0);
				MetalBridge.endEncoders(enc);
				return readback(target, width, height, bytesPerPixel(targetFormat));
			} finally {
				if (target != 0) MetalBridge.release(target);
				if (vertexBuffer != 0) MetalBridge.release(vertexBuffer);
				if (idBuffer != 0) MetalBridge.release(idBuffer);
				if (indexBuffer != 0) MetalBridge.release(indexBuffer);
				MetalBridge.release(pso);
			}
		} finally {
			MetalBridge.release(lib.library());
		}
	}

	/** MTLVertexFormatShort2 (Metal.framework Headers/MTLVertexDescriptor.h), the overlay attribute below. */
	public static final int VERTEX_FORMAT_SHORT2 = 16;

	/**
	 * Renders one indexed draw with two vertex attributes — float2 positions (attribute 0, offset 0) and a raw
	 * {@code short2} at attribute 1 with the given offset, both from the vertex buffer at index 0 with the given
	 * stride — and one texture bound at textureSlot. The entity overlay GPU test uses this to prove the prologue
	 * fetches the texel UV1 selects (ProgramRequest.OverlayColor).
	 */
	public byte[] drawIndexedTextured(String msl, String vertexEntry, String fragmentEntry, int targetFormat,
			int width, int height, byte[] vertices, int stride, int overlayOffset, InputTexture overlay, int textureSlot,
			short[] indices, int indexCount, int firstIndex, int baseVertex) {
		ensure();
		ShaderNative.Compiled lib = ShaderNative.libraryNew(ctx, msl, languageVersion, mathMode, preserveInvariance);
		if (!lib.ok()) throw new IllegalStateException("render source failed to compile: " + lib.error());
		try {
			int[] descriptor = {1, 0, stride, 0, 2, 0, 0, 0, VERTEX_FORMAT_FLOAT2, 1, 0, overlayOffset,
				VERTEX_FORMAT_SHORT2, 1, targetFormat, WRITE_MASK_ALL, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 3};
			long pso = MetalBridge.pipelineNew(ctx, lib.library(), vertexEntry, lib.library(), fragmentEntry,
				descriptor);
			long target = 0, vertexBuffer = 0, indexBuffer = 0, texture = 0;
			try {
				target = checkedTexture(targetFormat, 2, width, height, 1, USAGE_RENDER_TARGET | USAGE_SHADER_READ);
				vertexBuffer = MetalBridge.newBuffer(ctx, Math.max(16, vertices.length));
				write(vertexBuffer, vertices);
				byte[] indexBytes = new byte[indices.length * 2];
				java.nio.ByteBuffer.wrap(indexBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(indices);
				indexBuffer = MetalBridge.newBuffer(ctx, Math.max(16, indexBytes.length));
				write(indexBuffer, indexBytes);
				texture = uploadTexture(overlay);
				MetalBridge.renderBegin(enc, new long[] {target}, new float[][] {MetalBridge.DONT_CARE}, 0, false, 0,
					width, height);
				MetalBridge.renderPipeline(enc, pso, 0, false, false, 0, 0, PRIMITIVE_TRIANGLE);
				MetalBridge.vertexBuffer(enc, 0, vertexBuffer, 0);
				MetalBridge.renderTexture(enc, textureSlot, texture);
				MetalBridge.indexBuffer(enc, indexBuffer, false);
				MetalBridge.drawIndexed(enc, indexCount, 1, firstIndex, baseVertex, 0);
				MetalBridge.endEncoders(enc);
				return readback(target, width, height, bytesPerPixel(targetFormat));
			} finally {
				if (target != 0) MetalBridge.release(target);
				if (vertexBuffer != 0) MetalBridge.release(vertexBuffer);
				if (indexBuffer != 0) MetalBridge.release(indexBuffer);
				if (texture != 0) MetalBridge.release(texture);
				MetalBridge.release(pso);
			}
		} finally {
			MetalBridge.release(lib.library());
		}
	}

	/** Reads a box of texels out of a texture the caller holds (any dimension, any slice), synchronously. */
	public byte[] readTexture(long texture, int slice, int mip, int width, int height, int depth, int bytesPerPixel) {
		ensure();
		byte[] out = new byte[width * height * depth * bytesPerPixel];
		if (!ShaderNative.textureRead(ctx, texture, slice, mip, 0, 0, 0, width, height, depth, out,
			(long) width * bytesPerPixel, (long) width * height * bytesPerPixel))
			throw new IllegalStateException("texture read failed");
		return out;
	}

	public static int bytesPerPixel(int format) {
		return switch (format) {
			case R32_UINT, R32_SINT, R32_FLOAT, DEPTH32_FLOAT, RGBA8_UNORM, RGBA8_UINT -> 4;
			case RG32_UINT, RG32_FLOAT, RGBA16_FLOAT -> 8;
			case RGBA32_UINT, RGBA32_FLOAT -> 16;
			default -> throw new IllegalArgumentException("no bytesPerPixel for pixel format " + format);
		};
	}

	private long uploadTexture(InputTexture texture) {
		if (texture.type() == ShaderNative.TYPE_BUFFER) {
			long buffer = ShaderNative.textureBufferNew(ctx, texture.format(), texture.width(), texture.texels(),
				USAGE_SHADER_READ | ShaderNative.USAGE_SHADER_WRITE);
			if (buffer == 0) throw new IllegalStateException("the device rejected the texture buffer (format "
				+ texture.format() + ", " + texture.width() + " texels)");
			return buffer;
		}
		int bpp = bytesPerPixel(texture.format());
		long handle = checkedTexture(texture.format(), texture.type(), texture.width(), texture.height(), texture.depth(),
			USAGE_SHADER_READ | ShaderNative.USAGE_SHADER_WRITE);
		long row = (long) texture.width() * bpp, image = row * texture.height();
		if (texture.texels() != null && !ShaderNative.textureUpload(ctx, handle, texture.texels(), row,
			texture.type() == 7 ? image : 0, 0, 0, 0, 0, 0, texture.width(), texture.height(), texture.depth()))
			throw new IllegalStateException("texture upload failed (format " + texture.format() + ")");
		return handle;
	}

	private long checkedTexture(int format, int type, int width, int height, int depth, int usage) {
		long handle = ShaderNative.textureNew(ctx, format, type, width, height, depth, 1, usage, STORAGE_PRIVATE);
		if (handle == 0)
			throw new IllegalStateException("the device rejected the texture (format " + format + ", type " + type + ")");
		return handle;
	}

	/**
	 * The int descriptor mc_pipeline_new parses: no vertex buffers or attributes, the target formats, no depth, triangle. writeMasks
	 * (renderpearl's RGBA bits, 15 all and 0 none) gives each attachment its mask; null writes every attachment.
	 */
	private static int[] colorDescriptor(int[] targetFormats, int @Nullable [] writeMasks) {
		int[] descriptor = new int[3 + targetFormats.length * 9 + 2];
		int i = 0;
		descriptor[i++] = 0;  // vertexBufferCount
		descriptor[i++] = 0;  // attributeCount
		descriptor[i++] = targetFormats.length;
		for (int attachment = 0; attachment < targetFormats.length; attachment++) {
			descriptor[i++] = targetFormats[attachment];
			descriptor[i++] = writeMasks == null ? WRITE_MASK_ALL : writeMasks[attachment];
			descriptor[i++] = 0;  // blending off
			i += 6;  // the blend factors and operations stay zero
		}
		descriptor[i++] = 0;  // depthPixelFormat: none
		descriptor[i++] = TOPOLOGY_TRIANGLE;
		return descriptor;
	}

	private byte[] readback(long texture, int width, int height, int bytesPerPixel) {
		long buffer = 0;
		try {
			buffer = MetalBridge.newBuffer(ctx, (long) width * height * bytesPerPixel);
			MetalBridge.readTexture(enc, texture, width, height, bytesPerPixel, buffer);
			MetalBridge.commitAndWait(enc);
			return read(buffer, (long) width * height * bytesPerPixel);
		} finally {
			if (buffer != 0) MetalBridge.release(buffer);
		}
	}

	private void write(long buffer, byte[] bytes) {
		MemorySegment.ofAddress(MetalBridge.bufferContents(buffer)).reinterpret(bytes.length)
			.copyFrom(MemorySegment.ofArray(bytes));
	}

	private byte[] read(long buffer, long size) {
		return MemorySegment.ofAddress(MetalBridge.bufferContents(buffer)).reinterpret(size).toArray(JAVA_BYTE);
	}

	private static void ensure() {
		if (ctx == 0) {
			ctx = MetalBridge.createHeadlessContext();
			enc = MetalBridge.createHeadlessEncoder(ctx);
		}
	}

	/**
	 * Nothing to free: the context and its encoder are shared plain C allocations the backend keeps for the process (like
	 * MetalBridge's other headless helpers); every Metal object this device creates is released by the method that made it.
	 */
	@Override
	public void close() {
	}
}
