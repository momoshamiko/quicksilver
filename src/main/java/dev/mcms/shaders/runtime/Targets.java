// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime;

import dev.mcms.metal.MetalBridge;
import dev.mcms.shaders.runtime.gbuffers.DrawLog;
import dev.mcms.shaders.pack.ConstValue;
import dev.mcms.shaders.pack.ProgramDirectives;
import dev.mcms.shaders.pack.ShaderProperties;
import dev.mcms.shaders.pack.TargetUnion;
import dev.mcms.shaders.runtime.uniforms.FrameUniforms;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/**
 * The engine's render targets (ADR 0006 section 3; engine-pack-format.md section 7): {@code colortex0} to
 * {@code colortex15} with their legacy aliases, each two physical Metal textures whose handles swap on a flip and are
 * never copied, plus {@code noisetex} and, since E4, the depth targets: {@code depthtex0} (the live depth the
 * redirected world passes write, cleared to 1.0 once at the frame's clear hook) and the {@code depthtex1}/{@code
 * depthtex2} copies the phase hook takes around the solid hand (documented boundaries, engine-pack-format.md section
 * 7: everything, no translucents, no translucents and no hand; made only when a program reads them). Targets hold
 * OpenGL's orientation in memory (window row y at memory row y, like the backend's main target), so a flip is only a
 * handle swap and a blit copy keeps the picture upright.
 *
 * <p>Sizes come from the main target ({@code size.buffer.*} and fixed sizes honoured), formats from the union of the
 * dimension's programs, clear colours from the {@code <target>ClearColor} constants and the documented defaults
 * (colortex0 the frame's fog colour, refreshed each frame by {@link #refreshFogClear}; colortex1 white; the others
 * black with alpha 0). Clears run once per frame before the
 * first pass on both textures of a target, skipped for {@code <target>Clear=false} (engine-pack-format.md section 7),
 * so a {@code Clear=false} target keeps what an earlier frame wrote. Each texture of a target carries a generation counter that
 * bumps whenever its content changes (a clear or copy into it, an image store, a flip that makes it the read texture after a
 * pass wrote it). A target whose chain is mipped ({@code <target>MipmapEnabled} set by a program) has the read texture's chain
 * refilled right before a reader runs, only when that generation moved since the last fill (engine-pack-format.md section 7;
 * plan.md E3, E6).
 *
 * <p>Textures are created and released on the render thread only (the thread rule of plan.md E3): the background loader
 * compiles libraries and pipelines and touches no texture. {@link Textures} is the seam the headless GPU tests use to
 * run this whole bookkeeping on the test device.
 */
public final class Targets {

	/** Creates and releases native textures; the engine's implementation owns the Metal device's context. */
	public interface Textures {
		/** A 2D texture (mips levels, render target and shader read usage), or 0 when the device refused it. */
		long create(int format, int width, int height, int mips);

		/** Releases a handle this factory returned. */
		default void release(long handle) {
		}
	}

	/** The count of colortex targets (0 to 15; engine-pack-format.md section 7). */
	public static final int COLOR_TARGETS = 16;

	/** The colour targets every set creates even when no program names them (the documented minimum of four; count() is at least this). */
	private static final int MINIMUM_TARGETS = 4;

	/** The count of depth targets: {@code depthtex0} (live) plus the {@code depthtex1}/{@code depthtex2} copies. */
	public static final int DEPTH_TARGETS = 3;

	/** The count of Distant Horizons depth targets: {@code dhDepthTex0} (all DH depth) and {@code dhDepthTex1} (its opaque part). */
	public static final int DISTANT_DEPTH_TARGETS = 2;

	private final Textures textures;
	private final TargetUnion union;
	private final Map<String, ShaderProperties.BufferSize> bufferSizes;
	private final int mainWidth;
	private final int mainHeight;

	private final TargetFormats[] formats = new TargetFormats[COLOR_TARGETS];
	private final boolean[] clear = new boolean[COLOR_TARGETS];
	private final float[][] clearColors = new float[COLOR_TARGETS][];
	private final boolean[] mips = new boolean[COLOR_TARGETS];
	private final long[][] handles = new long[COLOR_TARGETS][2];
	private final int[] width = new int[COLOR_TARGETS];
	private final int[] height = new int[COLOR_TARGETS];
	/** Which of the two textures a pass reads (the other one is the write texture). */
	private final int[] current = new int[COLOR_TARGETS];
	/**
	 * Per target and ping-pong side: bumps whenever that side's content changed (a clear or copy into it, a flip that makes it
	 * the read texture after a pass wrote it, an image store). Drives the mip refill rule: a side's chain is stale when its
	 * generation moved since {@link #mippedAt}.
	 */
	private final long[][] generation = new long[COLOR_TARGETS][2];
	private final long[][] mippedAt = new long[COLOR_TARGETS][2];
	private final int count;
	/** Whether a target gets textures: the documented minimum (the first four) and every target a program uses. */
	private final boolean[] made = new boolean[COLOR_TARGETS];
	/** depthtex0 always; depthtex1/depthtex2 only when some program reads them ({@link #depth}). */
	private final boolean[] depthWanted = new boolean[DEPTH_TARGETS];
	/** The depth textures' handles (0 until created; index 0 is depthtex0, the live depth). */
	private final long[] depths = new long[DEPTH_TARGETS];
	/** The Distant Horizons depth textures (plan.md E7): 0 all DH depth, 1 the opaque DH depth. Made when wanted. */
	private final long[] distantDepths = new long[DISTANT_DEPTH_TARGETS];
	private boolean distantWanted;
	/**
	 * ADR 0008, made only while the render scale is above 1 ({@link #wantUpscale}): the main colour's stand-in at this size
	 * (the main colour's format; {@code final} writes it and the upscale reads it) and the depth of the passes that keep their
	 * own (the game's always-on-top and main depths are the window's size). -1: none wanted.
	 */
	private int upscaleFormat = -1;
	private long upscaleInput, overlayDepth;

	/** colortex0's default clear: the frame's fog colour (rgb, alpha 1), written by {@link #refreshFogClear}. */
	private final float[] fog = {0f, 0f, 0f, 1f};

	/** The pack's custom images (E6): their textures live with the targets and are rebuilt with them. */
	private final CustomImages customs;

	/** The custom images of this size (E6). */
	public CustomImages customImages() {
		return this.customs;
	}


	/** The same as the full constructor with no custom images (every pack before E6, and the tests). */
	public Targets(TargetUnion union, Map<String, ShaderProperties.BufferSize> bufferSizes, Set<Integer> used,
			boolean depth1, boolean depth2, int mainWidth, int mainHeight, Textures textures,
			@Nullable float[] fogColor) {
		this(union, bufferSizes, used, depth1, depth2, mainWidth, mainHeight, textures, fogColor, CustomImages.NONE);
	}
	/**
	 * Lays the targets out for one dimension's program set. Nothing is created yet: {@link #create()} makes the
	 * textures, and {@link #release()} frees them (both render thread).
	 *
	 * @param union       the dimension's buffer settings (formats, clears, clear colours, mip flags)
	 * @param bufferSizes {@code size.buffer.*} of shaders.properties (empty when the pack sets none)
	 * @param used        the colortex indexes the dimension's programs read or write (drives how many exist)
	 * @param depth1      a program of the set reads {@code depthtex1} (ADR 0006 section 3: copies are made only then)
	 * @param depth2      a program of the set reads {@code depthtex2}
	 * @param mainWidth   the main target's width in pixels
	 * @param mainHeight  the main target's height in pixels
	 * @param fogColor   the fog colour (rgb) colortex0 clears to before the first frame's refresh; null: black
	 * @param customs     the pack's custom images at this size (E6); {@link CustomImages#NONE} for a pack without them
	 */
	public Targets(TargetUnion union, Map<String, ShaderProperties.BufferSize> bufferSizes, Set<Integer> used,
			boolean depth1, boolean depth2, int mainWidth, int mainHeight, Textures textures,
			@Nullable float[] fogColor, CustomImages customs) {
		this.customs = customs;
		this.union = union;
		this.bufferSizes = bufferSizes;
		this.mainWidth = mainWidth;
		this.mainHeight = mainHeight;
		if (fogColor != null) {
			System.arraycopy(fogColor, 0, this.fog, 0, 3);
		}
		this.textures = textures;
		this.depthWanted[0] = true;  // the live depth always exists: the redirected world passes write it
		this.depthWanted[1] = depth1;
		this.depthWanted[2] = depth2;
		int max = 3;
		for (int t : used) {
			max = Math.max(max, t);
		}
		this.count = max + 1;
		for (int t = 0; t < this.count; t++) {
			this.made[t] = t < MINIMUM_TARGETS || used.contains(t);
		}
		for (int t = 0; t < this.count; t++) {
			String word = union.colorFormats().get(t);
			this.formats[t] = TargetFormats.of(word, t);
			Boolean clearFlag = union.clears().get(t);
			// The documented defaults: every target clears unless the pack switches it off (engine-pack-format.md 7).
			this.clear[t] = clearFlag == null || clearFlag;
			ConstValue color = union.clearColors().get(t);
			this.clearColors[t] = color != null ? colorVector(color)
				: t == 0 ? this.fog
				: t == 1 ? new float[] {1f, 1f, 1f, 1f} : new float[] {0f, 0f, 0f, 0f};
			Boolean mip = union.mipmaps().get(t);
			this.mips[t] = mip != null && mip;
			ShaderProperties.BufferSize size = sizeOf(t);
			this.width[t] = Math.max(1, size == null ? mainWidth
				: size.relative() ? round(mainWidth * size.width()) : (int) size.width());
			this.height[t] = Math.max(1, size == null ? mainHeight
				: size.relative() ? round(mainHeight * size.height()) : (int) size.height());
			this.current[t] = 0;
			this.generation[t][0] = 1;
			this.generation[t][1] = 1;
			this.mippedAt[t][0] = 0;
			this.mippedAt[t][1] = 0;
		}
	}

	/**
	 * The handle of a depth target ({@code depthtex0} is the live depth the redirected passes write; 1 and 2 the
	 * copies), or 0 when index 1 or 2 was not wanted at construction. All three are the main target's size.
	 */
	public long depth(int index) {
		return this.depths[index];
	}

	/** Whether the depth copy {@code index} (1 or 2) exists — a program of the set reads it. */
	public boolean depthWanted(int index) {
		return this.depthWanted[index];
	}

	/**
	 * Asks for the Distant Horizons depth targets (plan.md E7): {@code dhDepthTex0}, the depth of all DH terrain, and
	 * {@code dhDepthTex1}, its opaque part. Called before {@link #create}; the engine asks while DH runs.
	 */
	public void wantDistantDepth(boolean wanted) {
		this.distantWanted = wanted;
	}

	/** Whether the Distant Horizons depth targets exist (set by {@link #wantDistantDepth}). */
	public boolean distantDepthWanted() {
		return this.distantWanted;
	}

	/**
	 * The handle of a Distant Horizons depth target: 0 is {@code dhDepthTex0} (all DH depth), 1 is {@code dhDepthTex1} (the
	 * opaque DH depth, a copy made before the transparent DH terrain). 0 when none was wanted. Both are the main target's size,
	 * Depth32Float, like the engine's own depth.
	 */
	public long distantDepth(int index) {
		return this.distantDepths[index];
	}

	/**
	 * Asks for the render scale's textures (ADR 0008): the main colour's stand-in in {@code mainFormat} (a raw pixel format, the
	 * main colour's) and the own-depth passes' depth, both at this size. Called before {@link #create}.
	 */
	public void wantUpscale(int mainFormat) {
		this.upscaleFormat = mainFormat;
	}

	/** The main colour's render-size stand-in (ADR 0008), or 0 when the render scale is 1. */
	public long upscaleInput() {
		return this.upscaleInput;
	}

	/**
	 * The depth of the passes that keep their own while the render scale is above 1 (ADR 0008): Depth32Float at this size,
	 * cleared to 0.0 by the frame's clear hook (the game's convention for those passes); 0 when the scale is 1.
	 */
	public long overlayDepth() {
		return this.overlayDepth;
	}

	private static int round(double v) {
		return Math.max(1, (int) Math.round(v));
	}

	private static float[] colorVector(ConstValue value) {
		List<Double> values = value.values();
		float[] out = new float[4];
		for (int i = 0; i < 4; i++) {
			out[i] = i < values.size() ? values.get(i).floatValue() : i == 3 ? 1f : 0f;
		}
		return out;
	}

	private static float[] copy(float[] v) {
		return Arrays.copyOf(v, 4);
	}

	/**
	 * The {@code size.buffer} entry of a colour target: its {@code colortexN} key, else the key of its alias. Null when the
	 * pack sets none, and the target is then the render's size.
	 */
	public static ShaderProperties.@Nullable BufferSize bufferSizeOf(Map<String, ShaderProperties.BufferSize> sizes, int target) {
		ShaderProperties.BufferSize byName = sizes.get("colortex" + target);
		if (byName != null) {
			return byName;
		}
		for (Map.Entry<String, ShaderProperties.BufferSize> e : sizes.entrySet()) {
			if (ProgramDirectives.targetIndex(e.getKey()) == target) {
				return e.getValue();
			}
		}
		return null;
	}

	private ShaderProperties.@Nullable BufferSize sizeOf(int target) {
		return bufferSizeOf(this.bufferSizes, target);
	}

	/** How many colortex targets exist (at least 4, the documented minimum; one more than the largest used index). */
	public int count() {
		return this.count;
	}

	/** The raw MTLPixelFormat of one target. */
	public int format(int target) {
		return this.formats[target].format();
	}

	/** The format record of one target (components, bytes). */
	public TargetFormats formatRecord(int target) {
		return this.formats[target];
	}

	/** The target's size in pixels. */
	public int width(int target) {
		return this.width[target];
	}

	public int height(int target) {
		return this.height[target];
	}

	/** The main target's width (the depth targets' size; the splits' viewport). */
	public int mainWidth() {
		return this.mainWidth;
	}

	/** The main target's height. */
	public int mainHeight() {
		return this.mainHeight;
	}

	/** Whether the target's mip chain is enabled by a program ({@code <target>MipmapEnabled}). */
	public boolean mipmapped(int target) {
		return this.mips[target];
	}

	/** The texture a pass reads (the target's current texture). */
	public long read(int target) {
		return this.handles[target][this.current[target]];
	}

	/** The texture a pass writes (the target's other texture). */
	public long write(int target) {
		return this.handles[target][1 - this.current[target]];
	}

	/**
	 * Swaps a target's handles after a pass wrote it ({@code flip.<program>.<target>} false skips the call). The
	 * pass wrote the write texture; the flip makes it the read texture of the next pass. Never a copy.
	 */
	public void flip(int target) {
		this.current[target] = 1 - this.current[target];
		this.generation[target][this.current[target]]++;  // the side a flip makes the read texture is new content
		if (DrawLog.on()) {
			DrawLog.flip(target, read(target), write(target));
		}
	}

	/** A target's clear colour (the constant's value or the documented default). */
	public float[] clearColor(int target) {
		return copy(this.clearColors[target]);
	}

	/**
	 * Refreshes colortex0's default clear from the frame block's fog colour (rgb; alpha stays 1). The clear hook calls
	 * it once per frame before the frame's clears, so each frame clears to its own fog (plan.md E4). A pack's
	 * {@code colortex0ClearColor} constant is kept: the clear reads the constant. Allocates nothing.
	 *
	 * <p>Same fog as the game's clear (javap of minecraft-merged-deobf-26.3.jar, 2026-11-14): {@code Minecraft.renderFrame}
	 * calls {@code GameRenderer.extract} at bytecode 426; that runs {@code extractCamera}, whose {@code setupFog} (63) and
	 * {@code fogData} store (66) fill the camera state, before {@code GameRenderer.render} at 500 runs the frame-start
	 * hook that captures this block. {@code GameRenderer.renderLevel} passes {@code cam.fogData.color} (getfield 367 and
	 * 370) as the {@code Vector4f} argument of {@code LevelRenderer.render} (invokevirtual 385) with no copy.
	 * {@code LevelRenderer.render} captures that same object in its "clear" pass (aload 5 at 564, invokedynamic at 566).
	 * {@code lambda$render$0(Vector4f)} reads its x, y and z (offsets 25 to 33) when the pass runs, for the main target's
	 * clear (alpha 0 there). Both sides read the one {@code FogData.color} of the frame.
	 */
	public void refreshFogClear(FrameUniforms frame) {
		frame.fogColor(this.fog);
	}

	/** Copies a target's clear colour into {@code out[0..3]} without allocating (the clears read it each frame). */
	public void clearColorInto(int target, float[] out) {
		System.arraycopy(this.clearColors[target], 0, out, 0, 4);
	}

	/** Whether the target clears before the first pass ({@code <target>Clear}). */
	public boolean clears(int target) {
		return this.clear[target];
	}

	/**
	 * Makes the colour textures of the targets a program uses (and the first four), and the depth textures. Returns false
	 * (and releases what it made) when the device refuses one; the engine then fails the pack (ADR 0006 section 4). A target
	 * no program uses holds no texture: its handles stay 0, and the engine binds nothing for it. The depth targets (plan.md
	 * E4): {@code depthtex0} always, {@code depthtex1}/{@code depthtex2} when wanted, {@link TargetFormats#DEPTH32_FLOAT} at
	 * the main target's size, one mip level, render-target and shader-read usage (the redirected passes attach them and the
	 * pack samples them).
	 */
	public boolean create() {
		for (int t = 0; t < this.count; t++) {
			if (!this.made[t]) {
				continue;
			}
			int mips = this.mips[t] ? mipLevels(this.width[t], this.height[t]) : 1;
			for (int side = 0; side < 2; side++) {
				this.handles[t][side] = this.textures.create(this.formats[t].format(), this.width[t], this.height[t], mips);
				if (this.handles[t][side] == 0) {
					releaseHandles(t, side);
					return false;
				}
			}
		}
		for (int d = 0; d < DEPTH_TARGETS; d++) {
			if (!this.depthWanted[d]) {
				continue;
			}
			this.depths[d] = this.textures.create(TargetFormats.DEPTH32_FLOAT, this.mainWidth, this.mainHeight, 1);
			if (this.depths[d] == 0) {
				releaseDepths();
				releaseHandles(this.count - 1, 2);
				return false;
			}
		}
		if (this.distantWanted) {
			for (int d = 0; d < this.distantDepths.length; d++) {
				this.distantDepths[d] = this.textures.create(TargetFormats.DEPTH32_FLOAT, this.mainWidth, this.mainHeight, 1);
				if (this.distantDepths[d] == 0) {
					releaseDepths();
					releaseDistantDepths();
					releaseHandles(this.count - 1, 2);
					return false;
				}
			}
		}
		if (this.upscaleFormat >= 0) {
			// Render-target and shader-read usage, private: what the final pass writes and MetalFX's spatial scaler reads.
			this.upscaleInput = this.textures.create(this.upscaleFormat, this.mainWidth, this.mainHeight, 1);
			this.overlayDepth = this.upscaleInput == 0 ? 0
				: this.textures.create(TargetFormats.DEPTH32_FLOAT, this.mainWidth, this.mainHeight, 1);
			if (this.upscaleInput == 0 || this.overlayDepth == 0) {
				releaseUpscale();
				releaseDepths();
				releaseDistantDepths();
				releaseHandles(this.count - 1, 2);
				return false;
			}
		}
		return true;
	}

	private void releaseHandles(int upToTarget, int upToSide) {
		for (int t = 0; t <= upToTarget; t++) {
			int sides = t == upToTarget ? upToSide : 2;
			for (int side = 0; side < sides; side++) {
				if (this.handles[t][side] != 0) {
					this.textures.release(this.handles[t][side]);
					this.handles[t][side] = 0;
				}
			}
		}
	}

	/** Frees every texture of the set, colour and depth (engine off, pack switch, resource reload; render thread). */
	public void release() {
		releaseHandles(this.count - 1, 2);
		releaseDepths();
		releaseDistantDepths();
		releaseUpscale();
		this.customs.release();
	}

	private void releaseUpscale() {
		if (this.upscaleInput != 0) {
			this.textures.release(this.upscaleInput);
			this.upscaleInput = 0;
		}
		if (this.overlayDepth != 0) {
			this.textures.release(this.overlayDepth);
			this.overlayDepth = 0;
		}
	}

	private void releaseDistantDepths() {
		for (int d = 0; d < this.distantDepths.length; d++) {
			if (this.distantDepths[d] != 0) {
				this.textures.release(this.distantDepths[d]);
				this.distantDepths[d] = 0;
			}
		}
	}

	private void releaseDepths() {
		for (int d = 0; d < DEPTH_TARGETS; d++) {
			if (this.depths[d] != 0) {
				this.textures.release(this.depths[d]);
				this.depths[d] = 0;
			}
		}
	}

	/** The number of mip levels of a full chain over a size (Metal caps 2D chains at the larger dimension). */
	public static int mipLevels(int width, int height) {
		return 32 - Integer.numberOfLeadingZeros(Math.max(1, Math.max(width, height)));
	}

	/** Marks both textures of a target as changed (a clear, a world or carry copy, an image store): their chains refill. */
	public void bump(int target) {
		this.generation[target][0]++;
		this.generation[target][1]++;
	}

	/** Whether the read texture's mip chain is stale (its content changed since the last fill). */
	public boolean mipsStale(int target) {
		int side = this.current[target];
		return this.mips[target] && this.mippedAt[target][side] != this.generation[target][side];
	}

	/** Records that the read texture's mip chain holds the content it has now. */
	public void mipsFilled(int target) {
		int side = this.current[target];
		this.mippedAt[target][side] = this.generation[target][side];
	}




	/** The union this set was built from (for diagnostics). */
	public TargetUnion union() {
		return this.union;
	}

	/** The engine's texture factory over the backend's device (render thread; handles released with it). */
	public static Textures engineTextures(long ctx) {
		return new Textures() {
			@Override
			public long create(int format, int width, int height, int mips) {
				return dev.mcms.shaders.metal.ShaderNative.textureNew(ctx, format,
					dev.mcms.shaders.metal.ShaderNative.TYPE_2D, width, height, 1, mips,
					dev.mcms.shaders.metal.ShaderNative.USAGE_SHADER_READ | dev.mcms.shaders.metal.ShaderNative.USAGE_SHADER_WRITE
						| 4 /* MTLTextureUsageRenderTarget */,
					dev.mcms.shaders.metal.ShaderNative.STORAGE_PRIVATE);
			}

			@Override
			public void release(long handle) {
				MetalBridge.release(handle);
			}
		};
	}

	/** Unknown-format warnings, one line per word (the caller logs them once per pack). */
	public static Optional<String> fallbackNote(String word) {
		String why = TargetFormats.fallbackWord(word);
		return why == null ? Optional.empty() : Optional.of(word + " -> " + why);
	}

	private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

	/** Warns once per word about a format fallback (plan.md E3 logging rules). */
	public static void warnOnce(String word) {
		if (word != null && WARNED.add(word.trim().toUpperCase(java.util.Locale.ROOT))) {
			Optional<String> why = fallbackNote(word);
			why.ifPresent(s -> org.slf4j.LoggerFactory.getLogger("Quicksilver")
				.warn("[mcms-shaders] target format {} served as {}", word, s.substring(s.indexOf("-> ") + 3)));
		}
	}
}
