// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.standardroad;

import dev.mcms.glsl.msl.ProgramRequest.UniformBlock;
import dev.mcms.glsl.msl.ProgramRequest.UniformField;
import dev.mcms.glsl.msl.ProgramRequest.UniformSchema;
import dev.mcms.metal.MetalBridge;
import dev.mcms.shaders.metal.ShaderNative;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

/**
 * Renders one program through two libraries with identical inputs (the GPU side of the E2 differential, ADR 0006 section 4):
 * the same fixed random texture data and uniform bytes, the same full-screen triangle or dispatch, and every output read
 * back. Metal compiles each library with the engine's options (MSL 3.1, fast math, invariance kept), as the translator's
 * MSL is compiled. The harness creates and releases every Metal object it makes.
 *
 * <p>Formats are Metal pixel formats (the ids of {@code MetalConst}). A pack's format name maps to one of them with
 * {@link #ofPackName}; RGB formats are stored with four channels, as Metal stores them.
 */
final class DifferentialGpu implements AutoCloseable {

	/** Raw MTLPixelFormat and MTLTextureType values this harness uses (the ids of MetalConst, Metal.framework headers). */
	static final int R8_UNORM = 10, R16_FLOAT = 25, R32_FLOAT = 55, RG8_UNORM = 30, RG16_UNORM = 60, RG16_FLOAT = 65,
		RGBA8_UNORM = 70, RGBA8_SNORM = 72, RGB10A2_UNORM = 90, RG11B10_FLOAT = 92, RG32_FLOAT = 105,
		RGBA16_UNORM = 110, RGBA16_FLOAT = 115, RGBA32_FLOAT = 125, DEPTH32_FLOAT = 252;
	static final int TYPE_2D = 2, TYPE_3D = 7;
	private static final int USAGE_SHADER_READ = 1, USAGE_RENDER_TARGET = 4, STORAGE_PRIVATE = 2;
	private static final int WRITE_MASK_ALL = 15, TOPOLOGY_TRIANGLE = 3, PRIMITIVE_TRIANGLE = 3;

	/** A texture of the frame: its format, type (2, or 7 for 3D), size and row-major texels. */
	record Tex(int format, int type, int width, int height, int depth, byte[] texels) {
	}

	/** The stages of a pipeline: each stage's library source and entry name (the same source may serve both). */
	record Lib(String vertexSource, String vertexEntry, String fragmentSource, String fragmentEntry) {
	}

	private final long ctx;
	private final long enc;

	DifferentialGpu() {
		ctx = MetalBridge.createHeadlessContext();
		enc = MetalBridge.createHeadlessEncoder(ctx);
	}

	/** The Metal format of a pack's format name (colortexNFormat, OptiFine's names). */
	static int ofPackName(String name) {
		return switch (name) {
			case "RGBA8", "RGB8" -> RGBA8_UNORM;
			case "RGBA8_SNORM", "RGB8_SNORM" -> RGBA8_SNORM;
			case "R8" -> R8_UNORM;
			case "RG8" -> RG8_UNORM;
			case "R16F" -> R16_FLOAT;
			case "RG16" -> RG16_UNORM;
			case "RG16F" -> RG16_FLOAT;
			case "RGBA16" -> RGBA16_UNORM;
			case "RGBA16F", "RGB16F" -> RGBA16_FLOAT;
			case "R32F" -> R32_FLOAT;
			case "RG32F" -> RG32_FLOAT;
			case "RGBA32F" -> RGBA32_FLOAT;
			case "R11F_G11F_B10F" -> RG11B10_FLOAT;
			case "RGB10_A2" -> RGB10A2_UNORM;
			default -> throw new IllegalArgumentException("pack format " + name + " has no Metal format here");
		};
	}

	/** The name of a format in reports. */
	static String formatName(int format) {
		return switch (format) {
			case R8_UNORM -> "R8";
			case RG8_UNORM -> "RG8";
			case R16_FLOAT -> "R16F";
			case RG16_UNORM -> "RG16";
			case RG16_FLOAT -> "RG16F";
			case R32_FLOAT -> "R32F";
			case RG32_FLOAT -> "RG32F";
			case RGBA8_UNORM -> "RGBA8";
			case RGBA8_SNORM -> "RGBA8_SNORM";
			case RGB10A2_UNORM -> "RGB10_A2";
			case RG11B10_FLOAT -> "R11F_G11F_B10F";
			case RGBA16_UNORM -> "RGBA16";
			case RGBA16_FLOAT -> "RGBA16F";
			case RGBA32_FLOAT -> "RGBA32F";
			case DEPTH32_FLOAT -> "Depth32F";
			default -> "format " + format;
		};
	}

	/** Bytes per pixel of the formats this harness reads. */
	static int bytesPerPixel(int format) {
		return switch (format) {
			case R8_UNORM -> 1;
			case RG8_UNORM, R16_FLOAT -> 2;
			case RGBA8_UNORM, RGBA8_SNORM, RGB10A2_UNORM, RG11B10_FLOAT, RG16_UNORM, RG16_FLOAT, R32_FLOAT,
				DEPTH32_FLOAT -> 4;
			case RGBA16_UNORM, RGBA16_FLOAT, RG32_FLOAT, dev.mcms.shaders.metal.HeadlessGpu.RG32_UINT -> 8;
			case RGBA32_FLOAT, dev.mcms.shaders.metal.HeadlessGpu.RGBA32_UINT -> 16;
			default -> throw new IllegalArgumentException("format " + format);
		};
	}

	/** The channels a format stores, which the comparison reads (a one-channel format has one). */
	static int channels(int format) {
		return switch (format) {
			case R8_UNORM, R16_FLOAT, R32_FLOAT, DEPTH32_FLOAT -> 1;
			case RG8_UNORM, RG16_UNORM, RG16_FLOAT, RG32_FLOAT -> 2;
			case RG11B10_FLOAT -> 3;
			default -> 4;
		};
	}

	/** Compiles MSL with the engine's options; throws with Metal's diagnostics when it refuses. */
	private long library(String msl) {
		ShaderNative.Compiled lib = ShaderNative.libraryNew(ctx, msl, ShaderNative.MSL_3_1, ShaderNative.MATH_PACKS, true);
		if (!lib.ok()) {
			throw new IllegalStateException("Metal refused the library: " + lib.error());
		}
		return lib.library();
	}

	/** Renders the full-screen quad (two triangles, six vertices from the vertex ids) into targets of the formats. */
	byte[][] renderFullScreen(Lib lib, int[] formats, int width, int height, Map<Integer, Tex> textures,
			Map<Integer, byte[]> blocks) {
		return renderPrimitives(lib, 6, formats, width, height, textures, blocks);
	}

	/**
	 * Draws {@code vertices} vertices (no vertex buffer: the stages generate their vertices from the vertex ids) into targets
	 * of the formats (width x height), with each texture at its slot and each uniform block at its slot. Returns each
	 * target's pixels, row-major.
	 */
	byte[][] renderPrimitives(Lib lib, int vertices, int[] formats, int width, int height, Map<Integer, Tex> textures,
			Map<Integer, byte[]> blocks) {
		long vlib = library(lib.vertexSource());
		long flib = lib.fragmentSource().equals(lib.vertexSource()) ? vlib : library(lib.fragmentSource());
		List<Long> handles = new ArrayList<>();
		long[] targets = new long[formats.length];
		long pso = 0;
		try {
			pso = MetalBridge.pipelineNew(ctx, vlib, lib.vertexEntry(), flib, lib.fragmentEntry(),
				colorDescriptor(formats));
			if (pso == 0) {
				throw new IllegalStateException("pipeline refused for " + lib.fragmentEntry());
			}
			for (int i = 0; i < formats.length; i++) {
				targets[i] = texture(formats[i], TYPE_2D, width, height, 1, USAGE_RENDER_TARGET | USAGE_SHADER_READ);
			}
			float[][] clears = new float[formats.length][];
			// Cleared to zero: a pixel the triangle does not cover (its corner beyond x + y = 1) must hold one value in both
			// roads, so no pixel reads undefined memory.
			for (int i = 0; i < formats.length; i++) {
				clears[i] = new float[] {0f, 0f, 0f, 0f};
			}
			MetalBridge.renderBegin(enc, targets, clears, 0, false, 0, width, height);
			MetalBridge.renderPipeline(enc, pso, 0, false, false, 0, 0, PRIMITIVE_TRIANGLE);
			for (Map.Entry<Integer, byte[]> b : new TreeMap<>(blocks).entrySet()) {
				MetalBridge.renderBytes(enc, b.getKey(), b.getValue());
			}
			for (Map.Entry<Integer, Tex> t : new TreeMap<>(textures).entrySet()) {
				long handle = upload(t.getValue());
				handles.add(handle);
				MetalBridge.renderTexture(enc, t.getKey(), handle);
			}
			MetalBridge.renderDraw(enc, vertices, 1, 0, 0);
			MetalBridge.endEncoders(enc);
			byte[][] out = new byte[formats.length][];
			for (int i = 0; i < formats.length; i++) {
				out[i] = readback(targets[i], width, height, bytesPerPixel(formats[i]));
			}
			return out;
		} finally {
			handles.forEach(MetalBridge::release);
			for (long t : targets) {
				if (t != 0) {
					MetalBridge.release(t);
				}
			}
			if (pso != 0) {
				MetalBridge.release(pso);
			}
			if (flib != vlib) {
				MetalBridge.release(flib);
			}
			MetalBridge.release(vlib);
		}
	}

	/**
	 * Dispatches a compute kernel over groupsX x groupsY groups of lx x ly x 1 threads, with each block at its buffer slot and
	 * each image at its texture slot. Returns the images' texels in slot order, after the dispatch.
	 */
	byte[][] runCompute(String msl, String entry, int groupsX, int groupsY, int lx, int ly, Map<Integer, Tex> images,
			Map<Integer, byte[]> blocks) {
		long lib = library(msl);
		ShaderNative.ComputePipeline pso = ShaderNative.computePipelineNew(ctx, lib, entry);
		if (!pso.ok()) {
			MetalBridge.release(lib);
			throw new IllegalStateException("compute entry '" + entry + "': " + pso.error());
		}
		List<Long> buffers = new ArrayList<>();
		TreeMap<Integer, Long> handles = new TreeMap<>();
		try {
			MetalBridge.computePipeline(enc, pso.pipeline());
			for (Map.Entry<Integer, byte[]> e : new TreeMap<>(blocks).entrySet()) {
				long buffer = MetalBridge.newBuffer(ctx, Math.max(16, e.getValue().length));
				buffers.add(buffer);
				write(buffer, e.getValue());
				MetalBridge.computeBuffer(enc, e.getKey(), buffer, 0);
			}
			for (Map.Entry<Integer, Tex> e : new TreeMap<>(images).entrySet()) {
				long handle = upload(e.getValue());
				handles.put(e.getKey(), handle);
				MetalBridge.computeTexture(enc, e.getKey(), handle);
			}
			MetalBridge.computeDispatch(enc, groupsX, groupsY, 1, lx, ly, 1);
			MetalBridge.endEncoders(enc);
			byte[][] out = new byte[handles.size()][];
			int i = 0;
			for (Map.Entry<Integer, Long> h : handles.entrySet()) {
				Tex t = images.get(h.getKey());
				out[i++] = readback(h.getValue(), t.width(), t.height(), bytesPerPixel(t.format()));
			}
			if (handles.isEmpty()) {
				MetalBridge.commitAndWait(enc);
			}
			return out;
		} finally {
			buffers.forEach(MetalBridge::release);
			handles.values().forEach(MetalBridge::release);
			MetalBridge.release(pso.pipeline());
			MetalBridge.release(lib);
		}
	}

	/**
	 * The exclusive upper bound of an integer uniform's random values, for the uniforms whose game values are narrower than
	 * [0, 16). Each bound keeps an index in its array, as the game does; a larger value reads past the array (undefined,
	 * and different in the two roads). isEyeInWater takes 0 to 3 in the game: BSL indexes its two-entry denseFogColor
	 * array at isEyeInWater - 2. moonPhase takes 0 to 7: BSL indexes its eight-entry moonPhaseMultiplier at moonPhase
	 * (lib/color/lightColor.glsl). The roads agreed on BSL's composite and deferred1 with moonPhase unbounded while both
	 * kept that table in thread memory, and differed once E9 put never-written tables in the constant address space.
	 */
	static final Map<String, Integer> INT_BOUNDS = Map.of("isEyeInWater", 4, "moonPhase", 8);

	/** Fixed random data for one uniform block of the schema, at the schema's offsets (ints in [0, 16), floats in [0.05, 0.95)). */
	static byte[] uniformBytes(UniformBlock block, long seed) {
		Random rng = new Random(seed);
		int size = 16;
		for (UniformField f : block.fields()) {
			size = Math.max(size, f.offset() + 4 * componentsOf(f.type()));
		}
		ByteBuffer bb = ByteBuffer.allocate(size + 16).order(ByteOrder.LITTLE_ENDIAN);
		for (UniformField f : block.fields()) {
			boolean bool = f.type().equals("bool");
			boolean integer = bool || f.type().startsWith("int") || f.type().startsWith("ivec");
			for (int i = 0; i < componentsOf(f.type()); i++) {
				int at = f.offset() + 4 * i;
				if (bool) {
					bb.putInt(at, rng.nextInt(2));
				} else if (integer) {
					bb.putInt(at, rng.nextInt(INT_BOUNDS.getOrDefault(f.name(), 16)));
				} else {
					bb.putFloat(at, 0.05f + 0.9f * rng.nextFloat());
				}
			}
		}
		return bb.array();
	}

	/**
	 * The frame block's bytes with the frame's size and projection set to the render's, as the game sets them:
	 * viewWidth and viewHeight are the target's size and aspectRatio its ratio (packs read texels at viewWidth - 1 and
	 * viewHeight - 1), near and far are the game's 0.05 and a 32-chunk 512, and gbufferProjection and
	 * gbufferProjectionInverse are the matching perspective pair — a random inverse projection has all-positive entries,
	 * and a depth unmapped through it lands outside [near, far], which compiles ray marches such as BSL's light shafts
	 * to a stop at their first sample (a constant target, no differential evidence).
	 */
	static byte[] frameBytes(UniformSchema schema, int slot, long seed, int width, int height) {
		byte[] out = uniformBytes(schema, slot, seed);
		for (UniformBlock b : schema.blocks()) {
			if (b.slot() != slot) {
				continue;
			}
			ByteBuffer bb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN);
			float near = 0.05f, far = 512.0f;
			float f = (float) (1.0 / Math.tan(Math.toRadians(35.0)));
			float aspect = (float) width / height;
			float a = (far + near) / (near - far), bf = 2 * far * near / (near - far);
			float[] projection = {f / aspect, 0, 0, 0, 0, f, 0, 0, 0, 0, a, -1, 0, 0, bf, 0};
			float[] inverse = {aspect / f, 0, 0, 0, 0, 1 / f, 0, 0, 0, 0, 0, 1 / bf, 0, 0, -1, a / bf};
			// The other matrices the game supplies are valid transforms too: the view matrices are the identity (the
			// camera's frame is the world's), the shadow projection an orthographic box of 128 blocks deep 512, with its
			// exact inverse (2026-11-15; a random matrix has no inverse, so a shadow march through its inverse can divide by
			// w = 0). The composite1 NaN texels of that day came from pow of a negative base, not from these matrices.
			float[] identity = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
			float r = 64f;
			float[] ortho = {1 / r, 0, 0, 0, 0, 1 / r, 0, 0, 0, 0, -2 / (far - near), 0, 0, 0, -(far + near) / (far - near), 1};
			float[] orthoInverse = {r, 0, 0, 0, 0, r, 0, 0, 0, 0, -(far - near) / 2, 0, 0, 0, -(far + near) / 2, 1};
			for (UniformField fld : b.fields()) {
				switch (fld.name()) {
					case "viewWidth" -> bb.putFloat(fld.offset(), width);
					case "viewHeight" -> bb.putFloat(fld.offset(), height);
					case "aspectRatio" -> bb.putFloat(fld.offset(), aspect);
					case "near" -> bb.putFloat(fld.offset(), near);
					case "far" -> bb.putFloat(fld.offset(), far);
					case "gbufferProjection" -> putMatrix(bb, fld.offset(), projection);
					case "gbufferProjectionInverse" -> putMatrix(bb, fld.offset(), inverse);
					case "gbufferModelView", "gbufferModelViewInverse", "gbufferPreviousModelView", "shadowModelView",
						"shadowModelViewInverse" -> putMatrix(bb, fld.offset(), identity);
					case "gbufferPreviousProjection" -> putMatrix(bb, fld.offset(), projection);
					case "shadowProjection" -> putMatrix(bb, fld.offset(), ortho);
					case "shadowProjectionInverse" -> putMatrix(bb, fld.offset(), orthoInverse);
					default -> {
					}
				}
			}
		}
		return out;
	}

	/** Writes a std140 column-major 4x4 matrix at an offset. */
	private static void putMatrix(ByteBuffer bb, int offset, float[] columns) {
		for (float v : columns) {
			bb.putFloat(offset, v);
			offset += 4;
		}
	}

	/** The bytes of the uniform block at a slot of the schema. */
	static byte[] uniformBytes(UniformSchema schema, int slot, long seed) {
		for (UniformBlock b : schema.blocks()) {
			if (b.slot() == slot) {
				return uniformBytes(b, seed);
			}
		}
		throw new IllegalArgumentException("no block at slot " + slot);
	}

	/**
	 * Fixed random texels in the format. Every bit pattern is a valid UNORM, SNORM or packed-integer texel, so those formats
	 * take random bytes. Half and single floats take values in [0, 1). R11F_G11F_B10F takes fields with exponents 10 to 20,
	 * so every texel is finite and of ordinary size.
	 */
	static Tex randomTexture(int format, int type, int width, int height, int depth, long seed) {
		Random rng = new Random(seed);
		int bpp = bytesPerPixel(format);
		byte[] texels = new byte[width * height * depth * bpp];
		rng.nextBytes(texels);
		ByteBuffer bb = ByteBuffer.wrap(texels).order(ByteOrder.LITTLE_ENDIAN);
		for (int at = 0; at < texels.length; at += bpp) {
			switch (format) {
				case R16_FLOAT, RG16_FLOAT, RGBA16_FLOAT -> {
					for (int c = 0; c < bpp / 2; c++) {
						bb.putShort(at + 2 * c, Float.floatToFloat16(rng.nextFloat()));
					}
				}
				case R32_FLOAT, RG32_FLOAT, RGBA32_FLOAT, DEPTH32_FLOAT -> {
					for (int c = 0; c < bpp / 4; c++) {
						bb.putFloat(at + 4 * c, rng.nextFloat());
					}
				}
				case RG11B10_FLOAT -> {
					int r = (10 + rng.nextInt(11)) << 6 | rng.nextInt(64);
					int g = (10 + rng.nextInt(11)) << 6 | rng.nextInt(64);
					int b = (10 + rng.nextInt(11)) << 5 | rng.nextInt(32);
					bb.putInt(at, r | g << 11 | b << 22);
				}
				default -> {
				}
			}
		}
		return new Tex(format, type, width, height, depth, texels);
	}

	/**
	 * Decodes one channel of a format to a float (normalized formats to their value, SNORM8 clamped to [-1, 1]). The index
	 * counts channels; for the packed formats it selects the texel's word and the channel within it.
	 */
	static float component(int format, byte[] pixels, int index) {
		ByteBuffer bb = ByteBuffer.wrap(pixels).order(ByteOrder.LITTLE_ENDIAN);
		return switch (format) {
			case R8_UNORM, RG8_UNORM, RGBA8_UNORM -> (pixels[index] & 0xff) / 255.0f;
			case RGBA8_SNORM -> Math.max(pixels[index] / 127.0f, -1.0f);
			case RG16_UNORM, RGBA16_UNORM -> (bb.getShort(2 * index) & 0xffff) / 65535.0f;
			case R16_FLOAT, RG16_FLOAT, RGBA16_FLOAT -> Float.float16ToFloat(bb.getShort(2 * index));
			case RGB10A2_UNORM -> {
				int word = bb.getInt(4 * (index / 4));
				int c = index % 4;
				yield c < 3 ? ((word >>> (10 * c)) & 0x3ff) / 1023.0f : ((word >>> 30) & 3) / 3.0f;
			}
			case RG11B10_FLOAT -> {
				int word = bb.getInt(4 * (index / 3));
				int c = index % 3;
				yield c < 2 ? smallFloat((word >>> (11 * c)) & 0x7ff, 6) : smallFloat(word >>> 22, 5);
			}
			default -> bb.getFloat(4 * index);
		};
	}

	/** An unsigned small float: five exponent bits (bias 15) and the given fraction bits; infinities and NaNs as IEEE. */
	static float smallFloat(int bits, int fractionBits) {
		int e = (bits >>> fractionBits) & 31;
		int m = bits & ((1 << fractionBits) - 1);
		if (e == 31) {
			return m == 0 ? Float.POSITIVE_INFINITY : Float.NaN;
		}
		double frac = m / (double) (1 << fractionBits);
		return e == 0 ? (float) (frac * 0x1p-14) : (float) ((1 + frac) * Math.scalb(1.0, e - 15));
	}

	private long upload(Tex t) {
		long handle = texture(t.format(), t.type(), t.width(), t.height(), t.depth(),
			USAGE_SHADER_READ | ShaderNative.USAGE_SHADER_WRITE);
		long row = (long) t.width() * bytesPerPixel(t.format());
		long image = row * t.height();
		if (!ShaderNative.textureUpload(ctx, handle, t.texels(), row, t.type() == TYPE_3D ? image : 0, 0, 0, 0, 0, 0,
			t.width(), t.height(), t.depth())) {
			throw new IllegalStateException("texture upload failed (format " + t.format() + ")");
		}
		return handle;
	}

	private long texture(int format, int type, int width, int height, int depth, int usage) {
		long handle = ShaderNative.textureNew(ctx, format, type, width, height, depth, 1, usage, STORAGE_PRIVATE);
		if (handle == 0) {
			throw new IllegalStateException("the device rejected the texture (format " + format + ")");
		}
		return handle;
	}

	private byte[] readback(long texture, int width, int height, int bpp) {
		long size = (long) width * height * bpp;
		long buffer = MetalBridge.newBuffer(ctx, size);
		try {
			MetalBridge.readTexture(enc, texture, width, height, bpp, buffer);
			MetalBridge.commitAndWait(enc);
			return MemorySegment.ofAddress(MetalBridge.bufferContents(buffer)).reinterpret(size)
				.toArray(ValueLayout.JAVA_BYTE);
		} finally {
			MetalBridge.release(buffer);
		}
	}

	private void write(long buffer, byte[] bytes) {
		MemorySegment.ofAddress(MetalBridge.bufferContents(buffer)).reinterpret(bytes.length)
			.copyFrom(MemorySegment.ofArray(bytes));
	}

	private static int[] colorDescriptor(int[] formats) {
		int[] d = new int[3 + formats.length * 9 + 2];
		int i = 0;
		d[i++] = 0;
		d[i++] = 0;
		d[i++] = formats.length;
		for (int f : formats) {
			d[i++] = f;
			d[i++] = WRITE_MASK_ALL;
			d[i++] = 0;
			i += 6;
		}
		d[i++] = 0;
		d[i] = TOPOLOGY_TRIANGLE;
		return d;
	}

	private static int componentsOf(String type) {
		return switch (type) {
			case "float", "int", "uint", "bool" -> 1;
			case "vec2", "ivec2", "uvec2" -> 2;
			case "vec3", "ivec3" -> 3;
			case "vec4", "ivec4" -> 4;
			case "mat3" -> 9;
			case "mat4" -> 16;
			default -> throw new IllegalArgumentException("uniform type " + type);
		};
	}

	@Override
	public void close() {
		// Every object is released by the method that created it; the context and encoder are process-wide.
	}
}
