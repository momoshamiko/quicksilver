// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.metal;

import dev.mcms.metal.MetalBridge;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Downcalls into the engine's own native entry points (src/main/native/mcshaders.m, inside libmcmsmetal.dylib), looked up
 * through {@link MetalBridge#nativeLookup()}. Every pointer crosses as a long, like the backend's Native bindings; handles
 * returned here are freed with {@link MetalBridge#release(long)}.
 *
 * <p>The compile options the engine chose for pack programs (mcshaders.m's comment block has the full reasoning): MSL 3.1
 * or newer, {@link #MATH_PACKS} (relaxed math since E7: fast math's optimisations, but infinities and NaNs kept, as
 * OpenGL keeps them on this device), invariance preserved (packs draw the same geometry in two programs and compare
 * depths).
 */
public final class ShaderNative {
	/** Raw MTLLanguageVersion values (Metal.framework Headers/MTLLibrary.h). The engine needs 3.1 or newer. */
	public static final int MSL_3_1 = (3 << 16) + 1, MSL_3_2 = (3 << 16) + 2;

	/** Raw MTLMathMode values; the engine's choice for pack programs is {@link #MATH_PACKS}. */
	public static final int MATH_SAFE = 0, MATH_RELAXED = 1, MATH_FAST = 2;

	/**
	 * The math mode pack programs (render stages and compute kernels) compile with: relaxed (E7, 2026-11-16). Fast math
	 * folded OpenGL's NaN out of Bliss 2.1.2's constant zero-vector normalize in its sky table's vertex stage (a GPU probe:
	 * the folded result was 1.0 under fast math, NaN under relaxed and safe), and Iris draws the NaN (the sky lookup reads
	 * 0); relaxed keeps the optimisations and honours infinities and NaNs (MSL specification, math modes; MathModeGpuTest:
	 * self-compares still fold). The engine's own pipelines keep their fast math. {@code -Dmcms.shaders.mathMode=fast} or
	 * {@code safe} changes it for profiling only (E9 measures relaxed against fast math on a heavy pack, plan.md E7).
	 */
	public static final int MATH_PACKS = mathModeForPacks();

	private static int mathModeForPacks() {
		return switch (System.getProperty("mcms.shaders.mathMode", "relaxed")) {
			case "fast" -> MATH_FAST;
			case "safe" -> MATH_SAFE;
			default -> MATH_RELAXED;
		};
	}

	/** Raw MTLTextureUsage bits (Metal.framework Headers/MTLTexture.h). */
	public static final int USAGE_SHADER_READ = 0x1, USAGE_SHADER_WRITE = 0x2, USAGE_RENDER_TARGET = 0x4,
		USAGE_PIXEL_FORMAT_VIEW = 0x10;

	/** Raw MTLStorageMode values. */
	public static final int STORAGE_SHARED = 0, STORAGE_MANAGED = 1, STORAGE_PRIVATE = 2, STORAGE_MEMORYLESS = 3;

	/** Raw MTLTextureType values. */
	public static final int TYPE_1D = 0, TYPE_1D_ARRAY = 1, TYPE_2D = 2, TYPE_2D_ARRAY = 3, TYPE_3D = 7, TYPE_BUFFER = 9;

	/** The mcs_version() these bindings were written for (mcshaders.m bumps it with any signature change). */
	private static final int BINDINGS_VERSION = 7;
	private static final Linker LINKER = Linker.nativeLinker();
	private static final SymbolLookup LIB = MetalBridge.nativeLookup();

	private static final MethodHandle VERSION = fn("mcs_version", JAVA_INT);
	private static final MethodHandle LIBRARY_NEW = fn("mcs_library_new", JAVA_LONG,
		JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT);
	private static final MethodHandle FUNCTION_NAMES = fn("mcs_function_names", JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_INT);
	private static final MethodHandle COMPUTE_PIPELINE_NEW = fn("mcs_compute_pipeline_new", JAVA_LONG,
		JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_LONG);
	private static final MethodHandle TEXTURE_NEW = fn("mcs_texture_new", JAVA_LONG,
		JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT);
	private static final MethodHandle TEXTURE_BUFFER_NEW = fn("mcs_texture_buffer_new", JAVA_LONG,
		JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_INT);
	private static final MethodHandle RENDER_LABEL = fn("mcs_render_label", null, JAVA_LONG, JAVA_LONG);
	private static final MethodHandle RENDER_VIEWPORT = fn("mcs_render_viewport", null, JAVA_LONG, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE);
	private static final MethodHandle TEXTURE_UPLOAD = fn("mcs_texture_upload", JAVA_INT,
		JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT);
	private static final MethodHandle TEXTURE_READ = fn("mcs_texture_read", JAVA_INT,
		JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG);
	private static final MethodHandle SPATIAL_SCALE = fn("mcs_spatial_scale", JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT);
	private static final MethodHandle SPATIAL_RELEASE = fn("mcs_spatial_release", null);

	static {
		int v = version();
		if (v != BINDINGS_VERSION) {
			throw new IllegalStateException("libmcmsmetal carries mcs_version " + v + ", these bindings are " + BINDINGS_VERSION);
		}
	}

	private ShaderNative() {
	}

	/** A compiled MTLLibrary (handle), or Metal's diagnostic text when the source failed to compile. */
	public record Compiled(long library, String error) {
		public boolean ok() {
			return library != 0;
		}
	}

	/** A compute pipeline from a compiled library, or Metal's diagnostic text. maxThreadsPerThreadgroup is 0 on failure. */
	public record ComputePipeline(long pipeline, String error, int maxThreadsPerThreadgroup) {
		public boolean ok() {
			return pipeline != 0;
		}
	}

	public static int version() {
		try {
			return (int) VERSION.invokeExact();
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	/** Compiles MSL source with explicit options; see the class comment for the engine's defaults. */
	public static Compiled libraryNew(long ctx, String source, int languageVersion, int mathMode, boolean preserveInvariance) {
		try (Arena a = Arena.ofConfined()) {
			MemorySegment src = a.allocateFrom(source, StandardCharsets.UTF_8);
			MemorySegment err = a.allocate(4096);
			long lib = (long) LIBRARY_NEW.invokeExact(ctx, src.address(), err.address(), 4096,
				languageVersion, mathMode, preserveInvariance ? 1 : 0);
			return new Compiled(lib, lib != 0 ? null : utf8(err));
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	/** The library's entry-point names (one per top-level function in the source). */
	public static List<String> functionNames(long library) {
		try (Arena a = Arena.ofConfined()) {
			MemorySegment out = a.allocate(16384);
			int count = (int) FUNCTION_NAMES.invokeExact(library, out.address(), 16384);
			String all = utf8(out);
			List<String> names = new ArrayList<>(Math.max(0, count));
			if (!all.isEmpty()) {
				for (String name : all.split("\n", -1)) {
					if (!name.isEmpty()) names.add(name);
				}
			}
			return names;
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	public static ComputePipeline computePipelineNew(long ctx, long library, String entry) {
		try (Arena a = Arena.ofConfined()) {
			MemorySegment name = a.allocateFrom(entry, StandardCharsets.UTF_8);
			MemorySegment err = a.allocate(4096);
			MemorySegment maxThreads = a.allocate(JAVA_INT);
			long pso = (long) COMPUTE_PIPELINE_NEW.invokeExact(ctx, library, name.address(), err.address(), 4096, maxThreads.address());
			return new ComputePipeline(pso, pso != 0 ? null : utf8(err), maxThreads.get(JAVA_INT, 0));
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	/**
	 * A texture with any shape, usage and storage the engine needs (the backend's own creation calls fix storage or shape).
	 * For the array types depth is the layer count; for 3D it is the depth. Returns 0 when the device rejects the combination.
	 */
	public static long textureNew(long ctx, int format, int type, int width, int height, int depth, int mips, int usage, int storage) {
		try {
			return (long) TEXTURE_NEW.invokeExact(ctx, format, type, width, height, depth, mips, usage, storage);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	/**
	 * A texture buffer (MSL texture_buffer) of width texels holding a copy of bytes, which must hold width texels of format.
	 * Its element is read and written by index only (no sampler, no level). Returns 0 when the device rejects it.
	 */
	public static long textureBufferNew(long ctx, int format, int width, byte[] bytes, int usage) {
		try (Arena a = Arena.ofConfined()) {
			MemorySegment src = a.allocateFrom(JAVA_BYTE, bytes);
			return (long) TEXTURE_BUFFER_NEW.invokeExact(ctx, format, width, src.address(), (long) bytes.length, usage);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	/**
	 * Copies a box of host bytes into a texture synchronously (load-time and test path; the frame uploads through the blit
	 * encoder). The array must hold what the native side reads: bytesPerImage * depth (3D) or bytesPerRow * height bytes.
	 */
	public static boolean textureUpload(long ctx, long texture, byte[] bytes, long bytesPerRow, long bytesPerImage,
		int slice, int mip, int x, int y, int z, int width, int height, int depth) {
		requireBytes(bytes, bytesPerRow, bytesPerImage, height, depth, "upload");
		try (Arena a = Arena.ofConfined()) {
			MemorySegment src = a.allocateFrom(JAVA_BYTE, bytes);
			return (int) TEXTURE_UPLOAD.invokeExact(ctx, texture, src.address(), bytesPerRow, bytesPerImage,
				slice, mip, x, y, z, width, height, depth) != 0;
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	/** Copies a box of texels out of a texture (any dimension, any slice) into out, synchronously; same size rule. */
	public static boolean textureRead(long ctx, long texture, int slice, int mip, int x, int y, int z, int width, int height,
		int depth, byte[] out, long bytesPerRow, long bytesPerImage) {
		requireBytes(out, bytesPerRow, bytesPerImage, height, depth, "read");
		try (Arena a = Arena.ofConfined()) {
			MemorySegment dst = a.allocate((long) out.length);
			int ok = (int) TEXTURE_READ.invokeExact(ctx, texture, slice, mip, x, y, z, width, height, depth,
				dst.address(), bytesPerRow, bytesPerImage);
			if (ok != 0) MemorySegment.ofArray(out).copyFrom(dst);
			return ok != 0;
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	// 2026-11-13 (E3): names the encoder's open render encoder (the engine's full-screen passes) for the encoder log
	// (-Dmcms.metal.encLog), traces and pass reports, the way the backend's own passes are named.

	/** Labels the encoder's currently open render encoder; a no-op when none is open (see mcshaders.m). */
	public static void renderLabel(long enc, String label) {
		try (Arena a = Arena.ofConfined()) {
			RENDER_LABEL.invokeExact(enc, a.allocateFrom(label, StandardCharsets.UTF_8).address());
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	// 2026-11-13 (E3 review): the label-bytes overload for the frame path — the engine keeps each pass's label in
	// native memory once per set (an arena closed with the set), so no arena and no copy per frame.

	/** {@link #renderLabel(long, String)} over label bytes already in native memory (kept by the caller). */
	public static void renderLabel(long enc, MemorySegment label) {
		try {
			RENDER_LABEL.invokeExact(enc, label.address());
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	// 2026-11-13 (E3 review): the open render encoder's viewport (scale.<program> sub-rectangles; the final pass's window).

	/** Sets the open render encoder's viewport to the box x, y, w, h in pixels; a no-op when none is open. */
	public static void renderViewport(long enc, double x, double y, double w, double h) {
		try {
			RENDER_VIEWPORT.invokeExact(enc, x, y, w, h);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	// 2026-11-17 (E9, ADR 0008): MetalFX's spatial scaler for the engine's render scale.

	/** The spatial scaler built a new scaler (1) or reused it (0); the refusals are negative. */
	public static final int SPATIAL_BUILT = 1, SPATIAL_REUSED = 0, SPATIAL_UNSUPPORTED = -1, SPATIAL_NOT_BUILT = -2,
		SPATIAL_INPUT_USAGE = -3, SPATIAL_OUTPUT_USAGE = -4, SPATIAL_OUTPUT_NOT_PRIVATE = -5;

	/**
	 * Upscales {@code in} (the render size) into {@code out} (the window size) with MetalFX's spatial scaler, on the frame's
	 * command buffer after its open encoders end. {@code refuse} answers as a device without MetalFX (the fallback's test).
	 * Returns {@link #SPATIAL_BUILT}, {@link #SPATIAL_REUSED} or a negative refusal code; nothing is encoded on a refusal.
	 */
	public static int spatialScale(long enc, long in, long out, boolean refuse) {
		try {
			return (int) SPATIAL_SCALE.invokeExact(enc, in, out, refuse ? 1 : 0);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	/** Releases the spatial scaler; the next {@link #spatialScale} builds one. */
	public static void spatialRelease() {
		try {
			SPATIAL_RELEASE.invokeExact();
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	/** What a refusal code of {@link #spatialScale} means, for the WARN. */
	public static String spatialRefusal(int code) {
		return switch (code) {
			case SPATIAL_UNSUPPORTED -> "MetalFX does not support this device (or -Dmcms.shaders.upscaler=refuse)";
			case SPATIAL_NOT_BUILT -> "MetalFX did not build a spatial scaler for these sizes and formats";
			case SPATIAL_INPUT_USAGE -> "the render-size image lacks the scaler's colour texture usage";
			case SPATIAL_OUTPUT_USAGE -> "the main colour lacks the scaler's output texture usage";
			case SPATIAL_OUTPUT_NOT_PRIVATE -> "the main colour is not in private storage";
			default -> "refusal code " + code;
		};
	}

	/** The byte count the native side memcpy's for the box (mcshaders.m's own formula); a short array would read past it. */
	private static void requireBytes(byte[] bytes, long bytesPerRow, long bytesPerImage, int height, int depth, String what) {
		long needed = bytesPerImage > 0 ? bytesPerImage * depth : bytesPerRow * height;
		if (bytes.length < needed) {
			throw new IllegalArgumentException("texture " + what + " needs " + needed + " bytes, the array holds " + bytes.length);
		}
	}

	private static MethodHandle fn(String name, MemoryLayout result, MemoryLayout... args) {
		FunctionDescriptor d = result == null ? FunctionDescriptor.ofVoid(args) : FunctionDescriptor.of(result, args);
		return LINKER.downcallHandle(LIB.find(name).orElseThrow(() -> new IllegalStateException("missing native symbol " + name)), d);
	}

	private static String utf8(MemorySegment buffer) {
		long len = buffer.byteSize();
		int n = 0;
		while (n < len && buffer.get(JAVA_BYTE, n) != 0) n++;
		byte[] out = new byte[n];
		MemorySegment.ofArray(out).copyFrom(buffer.asSlice(0, n));
		return new String(out, StandardCharsets.UTF_8);
	}

	private static RuntimeException rethrow(Throwable t) {
		if (t instanceof RuntimeException r) return r;
		if (t instanceof Error e) throw e;
		return new IllegalStateException(t);
	}
}
