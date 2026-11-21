// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime;

import dev.mcms.metal.MetalBridge;
import dev.mcms.shaders.metal.ShaderNative;
import dev.mcms.shaders.pack.ShaderProperties;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The pack's custom images (plan.md E6; shaders.properties {@code image.<name>=<sampler> <pixelFormat> <internalFormat>
 * <pixelType> <clearEachFrame> <relative> <x> <y> [<z>]}): one storage texture per definition, bound as an image under
 * {@code <name>} and as a sampler under {@code <sampler>}. At most {@link #LIMIT} images; a definition past the limit is
 * logged and not created.
 *
 * <p>The size is {@code x} by {@code y} pixels, or the render size times {@code x} and {@code y} when {@code relative} is
 * set (a factor may be fractional, as the documentation's {@code 0.5} examples are); a {@code z} makes a 3D texture of
 * that depth. A {@code clearEachFrame} image is zeroed at the frame's head by a small compute kernel per texture kind
 * (float, signed or unsigned integer; 2D or 3D), compiled here so the frame path compiles nothing.
 *
 * <p>A resize keeps what does not depend on the render size: {@link #resize} moves the fixed-size textures and the clear
 * kernels into the new object and rebuilds only the relative images. The textures are created on the render thread.
 */
public final class CustomImages {

	/** The most custom images a pack may define (plan.md E6: "custom images ... at most 16"). */
	public static final int LIMIT = 16;

	/** A 3D texture's kind bit (the low byte is the element kind: float, int or uint). */
	private static final int VOLUME = 1 << 8;
	/** The clear kernels: one per element kind for 2D images and one per kind for 3D images. */
	private static final int KERNEL_SLOTS = 6;

	private static final Logger LOG = LoggerFactory.getLogger("Quicksilver");

	/** No images: the set of a pack without {@code image.} keys. */
	public static final CustomImages NONE = new CustomImages(List.of(), new String[0], new String[0], new long[0],
		new int[0], new int[0], new int[0], new boolean[0], new boolean[0], new boolean[0], new int[0],
		new long[KERNEL_SLOTS]);

	private final List<ShaderProperties.ImageBinding> definitions;
	private final String[] imageNames;
	private final String[] samplerNames;
	private final long[] textures;
	private final int[] widths;
	private final int[] heights;
	private final int[] depths;
	private final boolean[] relative;
	private final boolean[] clearEachFrame;
	/** True once a persistent ({@code !clearEachFrame}) image has been zero-initialized on its first frame. */
	private final boolean[] clearedOnce;
	private final int[] kinds;
	/** The clear kernel of each kind slot ({@link #kernelSlot}); 0 where no image of the kind exists. */
	private final long[] clearKernels;

	private CustomImages(List<ShaderProperties.ImageBinding> definitions, String[] imageNames, String[] samplerNames,
			long[] textures, int[] widths, int[] heights, int[] depths, boolean[] relative, boolean[] clearEachFrame,
			boolean[] clearedOnce, int[] kinds, long[] clearKernels) {
		this.definitions = definitions;
		this.imageNames = imageNames;
		this.samplerNames = samplerNames;
		this.textures = textures;
		this.widths = widths;
		this.heights = heights;
		this.depths = depths;
		this.relative = relative;
		this.clearEachFrame = clearEachFrame;
		this.clearedOnce = clearedOnce;
		this.kinds = kinds;
		this.clearKernels = clearKernels;
	}

	/**
	 * Creates the images of a pack at a render size.
	 *
	 * @param ctx         the Metal device context (textures and the clear kernels)
	 * @param definitions the pack's {@code image.} definitions in declaration order
	 * @param mainWidth   the render width (relative sizes)
	 * @param mainHeight  the render height (relative sizes)
	 * @throws RuntimeException a clear kernel the device refuses (the textures made so far are released first)
	 */
	public static CustomImages create(long ctx, List<ShaderProperties.ImageBinding> definitions, int mainWidth,
			int mainHeight) {
		return build(METAL, ctx, definitions, mainWidth, mainHeight, null);
	}

	/**
	 * The images at a new render size (plan.md E6): fixed-size images keep their textures and the clear kernels move to the
	 * result, so only relative images are rebuilt. Call it before this object is released: the release then frees what the
	 * result does not own (the old relative images). On failure this object keeps everything, and the result is not made.
	 */
	public CustomImages resize(long ctx, int mainWidth, int mainHeight) {
		return build(METAL, ctx, this.definitions, mainWidth, mainHeight, this);
	}

	/** The device calls a build makes: Metal in the runtime; the tests substitute one that refuses a kernel (the rollback). */
	interface Device {
		long texture(long ctx, int format, int type, int width, int height, int depth);

		long clearPipeline(long ctx, int kind);
	}

	/** Metal, the runtime's device. */
	static final Device METAL = new Device() {
		@Override
		public long texture(long ctx, int format, int type, int width, int height, int depth) {
			return ShaderNative.textureNew(ctx, format, type, width, height, depth, 1,
				ShaderNative.USAGE_SHADER_READ | ShaderNative.USAGE_SHADER_WRITE, ShaderNative.STORAGE_PRIVATE);
		}

		@Override
		public long clearPipeline(long ctx, int kind) {
			return CustomImages.clearPipeline(ctx, kind);
		}
	};

	/** {@link #resize} on the given device (the package's tests). */
	CustomImages resizeOn(Device device, long ctx, int mainWidth, int mainHeight) {
		return build(device, ctx, this.definitions, mainWidth, mainHeight, this);
	}

	private static CustomImages build(Device device, long ctx, List<ShaderProperties.ImageBinding> definitions, int mainWidth,
			int mainHeight, CustomImages previous) {
		if (definitions.isEmpty()) {
			return NONE;
		}
		int n = Math.min(LIMIT, definitions.size());
		if (definitions.size() > LIMIT) {
			LOG.warn("[mcms-shaders] the pack defines {} custom images; the engine creates {}", definitions.size(), LIMIT);
		}
		String[] imageNames = new String[n];
		String[] samplerNames = new String[n];
		long[] textures = new long[n];
		int[] widths = new int[n];
		int[] heights = new int[n];
		int[] depths = new int[n];
		boolean[] relative = new boolean[n];
		boolean[] clear = new boolean[n];
		boolean[] clearedOnce = new boolean[n];
		int[] kinds = new int[n];
		boolean[] kept = new boolean[n];
		// The kernels are copied, not taken: a failure below leaves the previous object whole (it owns them still).
		long[] kernels = previous == null ? new long[KERNEL_SLOTS] : previous.clearKernels.clone();
		try {
			for (int i = 0; i < n; i++) {
				ShaderProperties.ImageBinding d = definitions.get(i);
				int format = TargetFormats.of(d.internalFormat(), -1).format();
				boolean volume = d.z().isPresent();
				widths[i] = size(d.relative(), mainWidth, d.x());
				heights[i] = size(d.relative(), mainHeight, d.y());
				depths[i] = d.z().orElse(1);
				relative[i] = d.relative();
				kinds[i] = kindOf(d.internalFormat()) | (volume ? VOLUME : 0);
				imageNames[i] = d.name();
				samplerNames[i] = d.sampler();
				clear[i] = d.clearEachFrame();
				long reused = previous == null ? 0L : previous.fixedTexture(i, d.name(), d.relative(), widths[i], heights[i],
					depths[i]);
				if (reused != 0L) {
					textures[i] = reused;
					kept[i] = true;
					clearedOnce[i] = previous.clearedOnce[i];
				} else {
					textures[i] = device.texture(ctx, format, volume ? ShaderNative.TYPE_3D : ShaderNative.TYPE_2D, widths[i],
						heights[i], depths[i]);
					if (textures[i] == 0L) {
						LOG.warn("[mcms-shaders] the device refused custom image {} ({}x{}x{} {})", d.name(), widths[i],
							heights[i], depths[i], d.internalFormat());
					}
				}
				if (textures[i] != 0L && kernels[kernelSlot(kinds[i])] == 0L) {
					kernels[kernelSlot(kinds[i])] = device.clearPipeline(ctx, kinds[i]);
				}
			}
		} catch (RuntimeException refused) {
			// No leak: what this build made is released; what it copied from the previous object stays with that object.
			for (int i = 0; i < n; i++) {
				if (!kept[i] && textures[i] != 0L) {
					MetalBridge.release(textures[i]);
				}
			}
			for (int slot = 0; slot < KERNEL_SLOTS; slot++) {
				if ((previous == null || previous.clearKernels[slot] == 0L) && kernels[slot] != 0L) {
					MetalBridge.release(kernels[slot]);
				}
			}
			throw refused;
		}
		if (previous != null) {
			previous.disown(kept);  // the result owns the kept textures and every kernel now
		}
		return new CustomImages(definitions, imageNames, samplerNames, textures, widths, heights, depths, relative, clear,
			clearedOnce, kinds, kernels);
	}

	/** The texture a fixed-size image of this object keeps for the same definition, or 0 when none applies. */
	private long fixedTexture(int index, String name, boolean isRelative, int width, int height, int depth) {
		if (isRelative || index >= this.imageNames.length || !this.imageNames[index].equals(name) || this.relative[index]
				|| this.textures[index] == 0L || this.widths[index] != width || this.heights[index] != height
				|| this.depths[index] != depth) {
			return 0L;
		}
		return this.textures[index];
	}

	/** Gives up the textures a result took (kept) and the kernels it copied: the result releases them from now on. */
	private void disown(boolean[] kept) {
		for (int i = 0; i < kept.length; i++) {
			if (kept[i]) {
				this.textures[i] = 0L;
			}
		}
		java.util.Arrays.fill(this.clearKernels, 0L);
	}

	/** The size of an axis in pixels: the render size times the factor when relative (at least one pixel), else the value. */
	static int size(boolean relative, int mainSize, double value) {
		return relative ? (int) Math.max(1L, Math.round(mainSize * value)) : (int) value;
	}

	/** The texture kind of an internal format: 0 float, 1 signed integer, 2 unsigned integer. */
	static int kindOf(String internalFormat) {
		String f = internalFormat.trim().toLowerCase(Locale.ROOT);
		if (f.endsWith("ui")) {
			return 2;
		}
		if (f.endsWith("i")) {
			return 1;
		}
		return 0;
	}

	/** The clear kernel slot of a texture kind (element kind, and whether the image is 3D): 0 to {@link #KERNEL_SLOTS} - 1. */
	static int kernelSlot(int kind) {
		return ((kind & VOLUME) != 0 ? 3 : 0) + (kind & 0xff);
	}

	/** Compiles the clear kernel of one kind: a write of zeros over the whole texture, bounds-checked. */
	private static long clearPipeline(long ctx, int kind) {
		String element = switch (kind & 0xff) {
			case 1 -> "int";
			case 2 -> "uint";
			default -> "float";
		};
		String msl;
		if ((kind & VOLUME) != 0) {
			msl = "#include <metal_stdlib>\nusing namespace metal;\n"
				+ "kernel void qs_clear_image(texture3d<" + element + ", access::write> img [[texture(0)]],\n"
				+ "\tuint3 gid [[thread_position_in_grid]]) {\n"
				+ "\tif (gid.x >= img.get_width() || gid.y >= img.get_height() || gid.z >= img.get_depth()) return;\n"
				+ "\timg.write(" + element + "4(0), gid);\n}\n";
		} else {
			msl = "#include <metal_stdlib>\nusing namespace metal;\n"
				+ "kernel void qs_clear_image(texture2d<" + element + ", access::write> img [[texture(0)]],\n"
				+ "\tuint2 gid [[thread_position_in_grid]]) {\n"
				+ "\tif (gid.x >= img.get_width() || gid.y >= img.get_height()) return;\n"
				+ "\timg.write(" + element + "4(0), gid);\n}\n";
		}
		return MetalBridge.computePipelineNew(ctx, msl, "qs_clear_image", null);
	}

	/** The number of custom images. */
	public int count() {
		return this.textures.length;
	}

	/** The index of the image a GLSL name declares, or -1. */
	public int indexOfImage(String name) {
		for (int i = 0; i < this.imageNames.length; i++) {
			if (this.imageNames[i].equals(name)) {
				return i;
			}
		}
		return -1;
	}

	/** The index of the custom image a sampler name reads, or -1. */
	public int indexOfSampler(String name) {
		for (int i = 0; i < this.samplerNames.length; i++) {
			if (this.samplerNames[i].equals(name)) {
				return i;
			}
		}
		return -1;
	}

	/** The texture of custom image {@code index} (0 when the device refused it). */
	public long texture(int index) {
		return this.textures[index];
	}

	/**
	 * Zero-initializes any newly created persistent ({@code !clearEachFrame}) image once before the frame's {@code setup}
	 * and {@code begin} kernels run, so packs never read uninitialized GPU memory. Kept fixed-size images keep their
	 * initialized state across resizes. Allocation-free.
	 */
	public void clearInitial(long enc) {
		for (int i = 0; i < this.textures.length; i++) {
			if (this.clearEachFrame[i] || this.clearedOnce[i]) {
				continue;
			}
			long pipeline = this.clearKernels[kernelSlot(this.kinds[i])];
			if (this.textures[i] == 0L || pipeline == 0L) {
				continue;
			}
			this.clearedOnce[i] = true;
			dispatchClear(enc, i, pipeline);
		}
	}

	/**
	 * Zeroes every {@code clearEachFrame} image on the frame's compute encoder (after {@code setup}/{@code begin}, before
	 * the shadow pass). Allocation-free: the dispatch counts are arithmetic on sizes stored at creation.
	 */
	public void clearEachFrame(long enc) {
		for (int i = 0; i < this.textures.length; i++) {
			long pipeline = this.clearKernels[kernelSlot(this.kinds[i])];
			if (!this.clearEachFrame[i] || this.textures[i] == 0L || pipeline == 0L) {
				continue;
			}
			dispatchClear(enc, i, pipeline);
		}
	}

	private void dispatchClear(long enc, int i, long pipeline) {
		boolean volume = (this.kinds[i] & VOLUME) != 0;
		int wgX = volume ? 4 : 8;
		int wgY = volume ? 4 : 8;
		int wgZ = volume ? 4 : 1;
		MetalBridge.computePipeline(enc, pipeline);
		MetalBridge.computeTexture(enc, 0, this.textures[i]);
		MetalBridge.computeDispatch(enc, (this.widths[i] + wgX - 1) / wgX, (this.heights[i] + wgY - 1) / wgY,
			volume ? (this.depths[i] + wgZ - 1) / wgZ : 1, wgX, wgY, wgZ);
	}

	/** Releases the textures and the clear kernels (the targets that own them are being rebuilt or closed). */
	public void release() {
		for (int i = 0; i < this.textures.length; i++) {
			if (this.textures[i] != 0L) {
				MetalBridge.release(this.textures[i]);
				this.textures[i] = 0L;
			}
		}
		for (int slot = 0; slot < KERNEL_SLOTS; slot++) {
			if (this.clearKernels[slot] != 0L) {
				MetalBridge.release(this.clearKernels[slot]);
				this.clearKernels[slot] = 0L;
			}
		}
	}
}
