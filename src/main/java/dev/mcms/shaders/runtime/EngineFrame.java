// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime;

import com.mojang.renderpearl.api.textures.GpuTexture;
import dev.mcms.glsl.msl.ProgramRequest.ColorTarget;
import dev.mcms.glsl.msl.ProgramTranslation;
import dev.mcms.glsl.msl.ProgramTranslation.Reflection.ResourceUse;
import dev.mcms.shaders.runtime.buffers.StorageBuffers;
import dev.mcms.shaders.runtime.textures.CustomTextureSet;
import dev.mcms.metal.MetalBridge;
import dev.mcms.shaders.metal.ShaderNative;
import dev.mcms.shaders.pack.ProgramCatalog.Phase;
import dev.mcms.shaders.pack.ProgramDirectives;
import dev.mcms.shaders.pack.ShaderProperties;
import dev.mcms.shaders.pack.textures.CustomTextureTable;
import dev.mcms.shaders.runtime.compute.ComputeKernel;
import dev.mcms.shaders.runtime.gbuffers.DrawLog;
import dev.mcms.shaders.runtime.gbuffers.GbufferDelegate;
import dev.mcms.shaders.runtime.gbuffers.GbufferPhase;
import dev.mcms.shaders.runtime.gbuffers.Horizon;
import dev.mcms.shaders.runtime.gbuffers.PhaseTargets;
import dev.mcms.shaders.runtime.uniforms.Std140;
import dev.mcms.shaders.runtime.shadow.ShadowTargets;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One active set's frame order (plan.md E3 and E4, the frame-order and phases bullets) on the backend's frame encoder.
 * Three hooks run in the game's order:
 *
 * <ul>
 * <li>{@link #clearHook}: the frame graph's "clear" pass (a mixin on {@code LevelRenderer.lambda$render$0}). Clears
 * both textures of every clearing target and every depth target (1.0), then the {@code prepare} passes.</li>
 * <li>{@link #phaseHook}: the opaque phase's end inside the game's one "Solid" pass (a mixin between
 * {@code lambda$addMainPass$0}'s {@code executeSolid} and {@code executeClassicTransparency}). Copies the live depth
 * into {@code depthtex2}, reopens the pass on the opaque attachments for the solid hand, copies it into
 * {@code depthtex1}, flips the sky and opaque targets so their pictures sit on the read side, runs {@code deferred},
 * loads the translucent targets' base and reopens the pass on the translucent attachments for the game's
 * translucent draws.</li>
 * <li>{@link #compositeHook}: E3's after-level hook. Flips the translucent targets, runs {@code composite} and
 * {@code final}. When the takeover serves no world, E3's world copy into {@code colortex0} runs first.</li>
 * </ul>
 *
 * <p>Attachment sides (ADR 0006 section 3): a gbuffers phase attaches the <em>write</em> texture of each target of its
 * layout and loads it; the programs' {@code colortexN} samplers read the <em>read</em> texture. So the picture of each
 * target lives on the read side at a phase boundary: the sky and opaque phases accumulate on the write side and are
 * flipped before {@code deferred}; the translucent phase loads its base from the read side (the load copy in the phase
 * hook, which is also the feedback copy of ADR 0006 section 3: a program reading a target its phase writes samples the
 * picture from before the phase) and is flipped at composite. Two copies cover the boundaries that have no flip: the
 * sky-to-opaque hand-over ({@link #opaqueHook}: a target the sky wrote is copied into its read texture before the opaque
 * phase when the opaque programs sample it) and the load bases of the sky and opaque phases ({@link #clearHook}: after the
 * prepare passes, each sky or opaque target that the clear did not refresh, or that a prepare pass wrote, is copied from
 * its read texture into its write texture).
 *
 * <p>One EngineFrame belongs to one active set (the E3 review). Everything the frame path needs that does not change
 * per frame is worked out here once: the labels (native memory in one arena closed with the set), the attachment and
 * clear arrays, the flip decisions, the {@code scale.<program>} viewports, the mip targets, the clear chunks, the
 * split arrays and the flip lists of each boundary. The hooks write only into that memory, so a steady-state frame
 * allocates no Java objects of its own.
 *
 * <p>Depth (plan.md E4, the depth bullet and engine-pack-format.md section 7): {@code depthtex0} is the live depth the
 * redirected world passes write; {@code depthtex1} (after the solid hand, no translucents) and {@code depthtex2}
 * (before the hand, no translucents and no hand) are copies made only when a program of the set reads them
 * ({@link Targets#depthWanted}).
 */
public final class EngineFrame implements AutoCloseable {

	private static final Logger LOG = LoggerFactory.getLogger("Quicksilver");
	private static final long[] NO_TEXTURES = new long[0];
	private static final float[][] NO_CLEARS = new float[0][];
	/** A sampled name's source: a colortex index (0 and up) or one of these. */
	private static final int SOURCE_NOISE = -1, SOURCE_DEPTH0 = -2, SOURCE_DEPTH1 = -3, SOURCE_DEPTH2 = -4;
	/** The shadow depth maps (E5) as sources: {@code SOURCE_SHADOW_DEPTH - index}; the colour buffers: {@code SOURCE_SHADOW_COLOR - index}. */
	private static final int SOURCE_SHADOW_DEPTH = -10, SOURCE_SHADOW_COLOR = -20, SOURCE_NONE = -30;
	/** The Distant Horizons depth targets (E7): {@code dhDepthTex0} and {@code dhDepthTex1}. */
	private static final int SOURCE_DISTANT0 = -40, SOURCE_DISTANT1 = -41;
	/**
	 * {@code -Dmcms.shaders.loadOutputs=true} (profiling only; E9's "don't care" item, plan.md E9): every full-screen pass
	 * loads the targets it overwrites, as before the item, so a run can set the item's cost beside the default's.
	 */
	private static final boolean LOAD_OUTPUTS = Boolean.getBoolean("mcms.shaders.loadOutputs");
	/**
	 * {@code -Dmcms.shaders.keepDeadTargets=true} (profiling only; E9, the memoryless item): the frame runs every pass and
	 * stores every output, as before the item, even where no program reads the target ({@link DimensionSet#deadTargets}).
	 */
	private static final boolean KEEP_DEAD_TARGETS = Boolean.getBoolean("mcms.shaders.keepDeadTargets");
	/** The set's shadow targets (E5), or null when the set uses no shadow; the plan and the binds read them. */
	private final @Nullable ShadowTargets shadow;

	/** The main colour target the engine copies from and the final pass writes; one instance, set per frame. */
	public static final class Main {
		private long texture;
		private int format;
		private int width;
		private int height;
		private @Nullable GpuTexture gameTexture;
		/** ADR 0008: the game's clear colour for the render-size stand-in this frame, or null ({@link #standInClear}). */
		private float @Nullable [] standInClear;

		/** This frame's main colour (the game's texture object is null in the headless tests). */
		public void set(long texture, int format, int width, int height, @Nullable GpuTexture gameTexture) {
			this.texture = texture;
			this.format = format;
			this.width = width;
			this.height = height;
			this.gameTexture = gameTexture;
			this.standInClear = null;
		}

		/**
		 * ADR 0008: this frame's main colour is the render-size stand-in, and {@code rgba} is the colour the game cleared its
		 * main target to this frame (null: no clear pending). A final pass that may leave texels unwritten clears the
		 * stand-in to it instead of loading the stand-in's previous frame, as the window-size path writes the game's clear
		 * first. Call after {@link #set}.
		 */
		public void standInClear(float @Nullable [] rgba) {
			this.standInClear = rgba;
		}
	}

	/** Uploads one uniform block as this frame's transient memory: writes {handle, offset} into {@code out}. */
	public interface BlockUpload {
		void upload(ByteBuffer bytes, long[] out);
	}

	/**
	 * This frame's engine blocks per pass: the frame block (its {@code alphaTestRef} field changes with the program,
	 * so a pass with another reference is served a fresh upload), the custom block, and the Distant Horizons block ({@code QsDh}, E7),
	 * which every program reads while Distant Horizons runs. Null slices are not bound.
	 */
	public interface PassBlocks {
		long @Nullable [] frameBlock(String program);

		long @Nullable [] customBlock();

		/** The QsDh block as {handle, offset}, uploaded for this frame; null while the engine holds none (no Distant Horizons). */
		default long @Nullable [] distantBlock() {
			return null;
		}
	}

	/**
	 * The gbuffers half of the frame: each phase's targets ({@link PhaseTargets}: its attachment groups, the group 0 layout a
	 * pass opens with, and every target its programs write), whether the takeover serves the world, the phase setter the
	 * delegate's phase follows (the hand's pipelines map by the phase they are drawn in), and the backend seam (the Metal
	 * calls by default; the frame-order test records the sequence instead). The feedback sets, from the samplers the built
	 * code reads ({@link GbufferPlans#sampledTargets}, E9): {@code feedbackOpaque} are the targets the opaque phase's programs
	 * both sample and write; {@code carriedOpaque} the targets the sky writes and the opaque programs sample;
	 * {@code feedbackTranslucent} the translucent targets a program drawn in the translucent phase samples (Distant
	 * Horizons' programs included, since its water draws in that phase). The sky's feedback needs no set of its own: the
	 * clear hook's primes copy every sky target the sky's programs may read before their first draw.
	 * {@code reads} is each phase's targets whose mip chains its programs read, by {@link GbufferPhase} ordinal
	 * ({@link GbufferPlans#mipReadTargets}; the distant phase's {@link GbufferPlans#readTargets}): the phase boundaries
	 * refill the chains of those the layout mips. {@code shadowMipReads}: the targets the shadow programs can read past
	 * level 0 ({@link GbufferPlans#shadowMipReadTargets}), whose chains the frame head refills before the shadow pass.
	 * {@code distant}: every target the distant phase's programs write ({@code dh_terrain}, drawn after the opaque hook): the
	 * clear hook primes them as it primes the opaque targets, and they flip before {@code deferred} with the sky and opaque
	 * targets, so a target only Distant Horizons' terrain writes reaches the full-screen passes (RIL-563).
	 */
	public record Gbuffers(PhaseTargets sky, PhaseTargets opaque, PhaseTargets translucent, boolean servesWorld,
			Consumer<GbufferPhase> phases, @Nullable HookOps ops, int[] feedbackOpaque, int[] carriedOpaque,
			int[] feedbackTranslucent, int[][] reads, int[] shadowMipReads, int[] distant) {

		/** No sampled targets: the phases' programs read nothing (the tests' frames). */
		private static final int[][] NO_READS = new int[GbufferPhase.values().length][0];

		/** No feedback copies: the bare gbuffers (a frame whose programs read nothing they write). */
		public Gbuffers(PhaseTargets sky, PhaseTargets opaque, PhaseTargets translucent, boolean servesWorld,
				Consumer<GbufferPhase> phases, @Nullable HookOps ops) {
			this(sky, opaque, translucent, servesWorld, phases, ops, new int[0], new int[0], new int[0], NO_READS,
				new int[0], new int[0]);
		}

		/** Single-group phases with no feedback copies (a test frame: each phase's layout is its only group). */
		public Gbuffers(int[] sky, int[] opaque, int[] translucent, boolean servesWorld, Consumer<GbufferPhase> phases,
				@Nullable HookOps ops) {
			this(PhaseTargets.single(sky), PhaseTargets.single(opaque), PhaseTargets.single(translucent), servesWorld,
				phases, ops, new int[0], new int[0], new int[0], NO_READS, new int[0], new int[0]);
		}

		/** Single-group phases with feedback copies (the frame-order tests). */
		public Gbuffers(int[] sky, int[] opaque, int[] translucent, boolean servesWorld, Consumer<GbufferPhase> phases,
				@Nullable HookOps ops, int[] feedbackOpaque, int[] carriedOpaque, int[] feedbackTranslucent) {
			this(PhaseTargets.single(sky), PhaseTargets.single(opaque), PhaseTargets.single(translucent), servesWorld,
				phases, ops, feedbackOpaque, carriedOpaque, feedbackTranslucent, NO_READS, new int[0], new int[0]);
		}
	}

	/**
	 * The frame's backend calls. The default ({@link MetalOps}) wraps the backend's own functions; the frame-order test
	 * supplies a fake that records each step, so the documented sequence is checked without a device.
	 */
	public interface HookOps {
		/** The clears of both textures of every clearing target (plan.md E3's clear rule). */
		void targetClears(long enc, Targets targets);

		/** The depth targets cleared to 1.0 (the frame's clear hook; plan.md E4). */
		void depthClears(long enc, Targets targets);

		/** Copies the live depth into depth copy {@code toIndex} (1 or 2). */
		void depthCopy(long enc, Targets targets, int toIndex);

		/** Copies a target's read texture into its write texture: the load base of a phase's attachment (feedback). */
		void loadCopy(long enc, Targets targets, int target);

		/** Copies a target's write texture into its read texture: the picture an earlier phase left on the write side. */
		void carryCopy(long enc, Targets targets, int target);

		/** Splits the game's open render pass onto other attachments and restores its state (plan.md E4). */
		void split(@Nullable Object encoder, long[] colors, long depth, int width, int height);

		/** Swaps a target's handles: its picture moves to the read side. */
		void flip(Targets targets, int target);

		/** The game's picture into colortex0's read texture (E3's world copy; plan.md E3's encoding bullet). */
		void worldCopy(long enc, @Nullable Object encoder, Targets targets, Main main);

		/** colortex0 on the main colour (a pack without a running final program; the E3 decision, see copyToMain). */
		void showColortex0(long enc, @Nullable Object encoder, Targets targets, Main main);

		/** The kernels of setup program {@code setupIndex} (once after a load and after each resize; plan.md E6). */
		void setup(long enc, Targets targets, PassBlocks blocks, int setupIndex);

		/** The kernels of the begin program at {@code planIndex} (every frame, before the shadow pass; plan.md E6). */
		void begin(long enc, Targets targets, PassBlocks blocks, int planIndex);

		/** Zeroes the custom images marked clearEachFrame (every frame, before the shadow pass; plan.md E6). */
		void clearImages(long enc, Targets targets);

		/** One full-screen pass of the plan at {@code planIndex}. */
		void runPass(long enc, @Nullable Object encoder, Targets targets, @Nullable Main main, PassBlocks blocks,
				int planIndex);

		/** Ends the engine's open encoders before the hook returns to the game. */
		void endFrame(long enc);
	}

	/** {@link HookOps} on the backend's own functions. */
	private final class MetalOps implements HookOps {
		@Override
		public void targetClears(long enc, Targets targets) {
			EngineFrame.this.clears(enc, targets);
		}

		@Override
		public void depthClears(long enc, Targets targets) {
			EngineFrame.this.depthClears(enc, targets);
		}

		@Override
		public void depthCopy(long enc, Targets targets, int toIndex) {
			if (DrawLog.on()) DrawLog.copy("depth", toIndex, targets.depth(0), targets.depth(toIndex));
			MetalBridge.blitTextureToTexture(enc, targets.depth(0), targets.depth(toIndex), 0, targets.mainWidth(),
				targets.mainHeight());
		}

		@Override
		public void loadCopy(long enc, Targets targets, int target) {
			if (DrawLog.on()) DrawLog.copy("load", target, targets.read(target), targets.write(target));
			MetalBridge.blitTextureToTexture(enc, targets.read(target), targets.write(target), 0,
				targets.width(target), targets.height(target));
		}

		@Override
		public void carryCopy(long enc, Targets targets, int target) {
			if (DrawLog.on()) DrawLog.copy("carry", target, targets.write(target), targets.read(target));
			targets.bump(target);  // the read texture's content changes: its chain refills before the next reader
			MetalBridge.blitTextureToTexture(enc, targets.write(target), targets.read(target), 0,
				targets.width(target), targets.height(target));
		}

		@Override
		public void split(@Nullable Object encoder, long[] colors, long depth, int width, int height) {
			if (encoder != null) {
				// The split loads depth (clearDepth false), so its clear value is unused: 0, never NaN (Metal API validation refuses a
				// descriptor whose clear depth is outside [0, 1]; 2026-11-15).
				MetalBridge.splitRenderPass(encoder, colors, null, depth, false, 0f, width, height);
			}
		}

		@Override
		public void flip(Targets targets, int target) {
			targets.flip(target);
		}

		@Override
		public void worldCopy(long enc, @Nullable Object encoder, Targets targets, Main main) {
			EngineFrame.this.copyWorld(enc, encoder, targets, main);
		}

		@Override
		public void showColortex0(long enc, @Nullable Object encoder, Targets targets, Main main) {
			EngineFrame.this.copyToMain(enc, encoder, targets, main);
		}

		@Override
		public void setup(long enc, Targets targets, PassBlocks blocks, int setupIndex) {
			EngineFrame.this.runKernels(enc, EngineFrame.this.setupPlans[setupIndex], targets, blocks);
		}

		@Override
		public void begin(long enc, Targets targets, PassBlocks blocks, int planIndex) {
			EngineFrame.this.runKernels(enc, EngineFrame.this.plans[planIndex], targets, blocks);
		}

		@Override
		public void clearImages(long enc, Targets targets) {
			targets.customImages().clearEachFrame(enc);
		}

		@Override
		public void runPass(long enc, @Nullable Object encoder, Targets targets, @Nullable Main main,
				PassBlocks blocks, int planIndex) {
			EngineFrame.this.runPass(enc, EngineFrame.this.plans[planIndex], targets, main, blocks);
		}

		@Override
		public void endFrame(long enc) {
			MetalBridge.endEncoders(enc);
		}
	}

	/** One pass's frame-path memory, worked out once per set. */
	private static final class PassPlan {
		final CompiledProgram pass;
		/** The label bytes ("qs {@code <program>}") in the set's native arena. */
		final MemorySegment label;
		/** The colour attachment handles, filled per frame (a flip moves a target's write texture). */
		final long[] colors;
		/**
		 * Per slot: {@link MetalBridge#DONT_CARE} when the pass overwrites the whole target, null when it blends over it or
		 * draws a sub-rectangle of it ({@code scale.<program>}: the rest of the target keeps its texels).
		 */
		final float[][] clears;
		/** The written target of each output, in the program's output order. */
		final int[] targets;
		/** Per output: whether this pass's write flips the target ({@code flip.<program>.<buffer>}). */
		final boolean[] flips;
		/** Per sampled texture, in the reflection's order: its source (a target, a depth or the noise texture). */
		final int[] sources;
		/** The targets whose mip chains this pass reads (refilled when stale), in the reflection's order. */
		final int[] mipTargets;
		/** The shadow mip chains this pass reads, as {@link ShadowTargets} keys (refilled when stale). */
		final int[] shadowMips;
		/** {@code scale.<program>}, null when the pack sets none. */
		final ShaderProperties.@Nullable Scale scale;
		/** Whether the pass writes shadow colour buffers (a shadowcomp pass): its outputs are shadowcolor indices (E5). */
		final boolean shadowOutputs;
		/** Per sampled texture: the custom texture id the pack binds to it in this pass (CustomTextureSet), or -1. */
		final int[] customIds;
		/** Per image of the reflection, its write source ({@link ComputeKernel}'s encoding; -1 when none). */
		final int[] imageSources;
		/** The program's compute kernels with their sources (E6), in dispatch order (an array: the frame path walks it). */
		final KernelPlan[] kernels;
		/**
		 * Whether the frame skips the pass (E9): every target it writes is dead ({@link DimensionSet#deadTargets}) and it has
		 * no kernels, images or storage buffers, so nothing it does is ever seen.
		 */
		final boolean skip;
		/** The slots whose target is dead: their texels are not stored after the pass (a "don't care" store). */
		final int[] discards;
		/**
		 * Whether the pass writes every texel of its targets: no scale.<program> viewport, a vertex stage that covers the
		 * viewport, no discard (the "don't care" rule). A final pass that does not drops no pending clear of the main colour.
		 */
		final boolean whole;

		PassPlan(CompiledProgram pass, MemorySegment label, int slots, float[][] clears, int[] targets,
		boolean[] flips, int[] sources, int[] mipTargets, int[] shadowMips, ShaderProperties.@Nullable Scale scale,
				boolean shadowOutputs, int[] imageSources, KernelPlan[] kernels, int[] customIds, boolean skip, int[] discards,
				boolean whole) {
			this.customIds = customIds;
			this.pass = pass;
			this.label = label;
			this.colors = new long[slots];
			this.clears = clears;
			this.targets = targets;
			this.imageSources = imageSources;
			this.kernels = kernels;
			this.flips = flips;
			this.sources = sources;
			this.mipTargets = mipTargets;
			this.shadowMips = shadowMips;
			this.scale = scale;
			this.shadowOutputs = shadowOutputs;
			this.skip = skip;
			this.discards = discards;
			this.whole = whole;
		}
	}

	/** The pack's custom textures (E6-B), or null when the pack binds none. */
	private final @Nullable CustomTextureSet customs;
	/** The pack's storage buffers (E6-B; the set's, resized in place), or null when the pack declares none. */
	private final @Nullable StorageBuffers storage;
	/** The programs skipped for a storage block with no buffer, one entry each (logged once per set; skipUnboundStorage). */
	private final Set<String> storageSkips = new HashSet<>();
	private final PassPlan[] plans;
	/** The set's shadowcomp passes (E5 review item 15), in run order; {@link #shadowComposite} runs them after the shadow pass. */
	private final PassPlan[] shadowComps;
	/** The setup programs (E6), run once after the set installs and after each resize ({@link #resized}). */
	private final PassPlan[] setupPlans;
	private boolean setupPending = true;
	/** Whether this frame's head ran (the shadow hook, or the clear hook when none did); the clear hook consumes it. */
	private boolean headRan;
	/** The first non-prepare pass ({@link #plans} is in run order): the prepare passes run at the clear hook. */
	private final int firstDeferred;
	/** The first composite-phase pass (deferred passes run at the phase hook, composite and final at the composite hook). */
	private final int firstComposite;
	private final long noise;
	private final long copyPipeline;
	/** The copy pipeline of the main colour's format (the no-final copy when the formats differ; 0 when not built). */
	private final long finalCopyPipeline;
	private final BlockUpload blocks;
	/** Direct: uploaded through {@link MetalBridge#transientUniform}, which copies from its native address. */
	private final ByteBuffer passBlock = ByteBuffer.allocateDirect(Std140.paddedSize(ProgramRequests.PASS_BLOCK))
		.order(ByteOrder.LITTLE_ENDIAN);
	private final long[] passBytes = new long[2];
	/** One arena for the set's labels (the review: not one per label call); closed with the set. */
	private final Arena labelArena = Arena.ofConfined();
	private final MemorySegment mainCopyLabel;
	private final MemorySegment clearsLabel;
	private final MemorySegment depthLabel;
	private final MemorySegment copyLabel;
	private final MemorySegment upscaleLabel;
	/** The clearing targets, chunked four per encoder (each brings both of its textures: eight attachments). */
	private int[][] clearingChunks = new int[0][];
	/** Per chunk: both textures of each target, filled per frame. */
	private long[][] clearHandles = new long[0][];
	/**
	 * Per chunk: the clear colours of each attachment, read from the targets at each clear (colortex0's default is this
	 * frame's fog, refreshed before the clear hook).
	 */
	private float[][][] clearColors = new float[0][][];
	/** The {@link Targets} instance {@link #clearingChunks} was grouped for; rebuilt lazily after any resize. */
	private @Nullable Targets clearTargets;
	private final long[] copyColors = new long[1];
	private final float[][] copyClears = {MetalBridge.DONT_CARE};
	/** The main colour's single attachment for the no-final draw (one array, filled per frame; N2: no per-frame arrays). */
	private final long[] mainColours = new long[1];
	private final Gbuffers gbuffers;
	private final HookOps ops;
	/** The opaque and translucent phases' open layouts (the group a pass opens with), which the phase hook's splits fill. */
	private final int[] opaqueOpen, translucentOpen;
	/** The translucent phase's every target: its flips at composite. */
	private final int[] translucentAll;
	/**
	 * The translucent phase's hand-over at the phase hook (E9): the targets a program drawn in that phase samples load their
	 * read texture into their write texture (the feedback rule); every other one flips, so the phase draws on the picture
	 * without a copy, and the composite hook's flip brings it back to the read side.
	 */
	private final int[] translucentLoad, translucentFlip;
	/** The opaque feedback copies (opaque hook) and the carried targets (opaque hook, sky to opaque). */
	private final int[] feedbackOpaque, carriedOpaque;
	/**
	 * The load bases the clear hook copies (read to write) after the prepare passes: each target of the sky or opaque
	 * layout that the clear does not refresh, or that a prepare pass wrote. Empty while no world is drawn.
	 */
	private final int[] primed;
	/** The mipped targets each phase's programs read: their chains are refilled at the phase boundary (plan.md E6, E7). */
	private final int[] skyMips, opaqueMips, translucentMips, distantMips;
	/** The mipped targets the shadow programs can read past level 0: refilled at the frame head, before their draws. */
	private final int[] shadowFrameMips;
	private final long[] splitOpaque, splitTranslucent;
	/** Targets flipped before {@code deferred} (the sky, opaque and distant phases' pictures), and the composite flips. */
	private final int[] flipBeforeDeferred, flipAllGbuffers;
	/** Whether the phase hook ran this frame (its flips then cover the translucent targets only at composite). */
	private boolean phaseRan;
	/** The horizon draw (plan.md E4), null while no world is drawn; the clear hook draws it. */
	private @Nullable Horizon horizon;
	private boolean closed;

	/**
	 * @param set       the active set whose passes this frame runs (the frame lives and dies with it)
	 * @param layout    the set's targets, read for their clear flags and colours only (the frame takes the live targets
	 *                  per hook: a resize rebuilds them)
	 * @param noise     the generated noisetex
	 * @param copy      the world-copy pipeline (a full-screen draw, for a format-changing copy)
	 * @param finalCopy the copy pipeline of the main colour's format (the no-final copy of a format-changing pair)
	 * @param blocks    the per-frame uniform block uploads
	 * @param flips     the pack's {@code flip.<program>.<target>} overrides
	 * @param scales    the pack's {@code scale.<program>} viewports
	 * @param gbuffers  the gbuffers half (layouts, world flag, phase setter, backend seam)
	 */
	public EngineFrame(DimensionSet set, Targets layout, @Nullable ShadowTargets shadow, long noise, long copy,
			long finalCopy, BlockUpload blocks, Map<String, Boolean> flips, Map<String, ShaderProperties.Scale> scales,
			Gbuffers gbuffers) {
		this(set, layout, shadow, noise, copy, finalCopy, blocks, flips, scales, gbuffers, null);
	}

	/**
	 * The frame of a set, with its custom textures (E6-B): a pass's sampler reads the custom texture the pack binds to it
	 * (CustomTextureSet), else the engine's own source. Null when the pack has none (the constructor above).
	 */
	public EngineFrame(DimensionSet set, Targets layout, @Nullable ShadowTargets shadow, long noise, long copy,
			long finalCopy, BlockUpload blocks, Map<String, Boolean> flips, Map<String, ShaderProperties.Scale> scales,
			Gbuffers gbuffers, @Nullable CustomTextureSet customs) {
		this(set, layout, shadow, noise, copy, finalCopy, blocks, flips, scales, gbuffers, customs, null);
	}

	/**
	 * The frame of a set with its custom textures and its storage buffers (E6-B): a pass or kernel binds a storage buffer
	 * its reflection marks at the buffer's slot. Null for either when the pack has none.
	 */
	public EngineFrame(DimensionSet set, Targets layout, @Nullable ShadowTargets shadow, long noise, long copy,
			long finalCopy, BlockUpload blocks, Map<String, Boolean> flips, Map<String, ShaderProperties.Scale> scales,
			Gbuffers gbuffers, @Nullable CustomTextureSet customs, @Nullable StorageBuffers storage) {
		this.customs = customs;
		this.storage = storage;
		this.noise = noise;
		this.shadow = shadow;
		this.copyPipeline = copy;
		this.finalCopyPipeline = finalCopy;
		this.blocks = blocks;
		this.gbuffers = gbuffers;
		this.ops = gbuffers.ops() != null ? gbuffers.ops() : new MetalOps();
		this.mainCopyLabel = label("qs copy to main");
		this.clearsLabel = label("qs clears");
		this.depthLabel = label("qs depth");
		this.copyLabel = label("qs copy world");
		this.upscaleLabel = label("qs upscale (bilinear)");
		buildClearingChunks(layout);
		List<CompiledProgram> frameRuns = new ArrayList<>();
		List<CompiledProgram> shadowRuns = new ArrayList<>();
		List<CompiledProgram> setupRuns = new ArrayList<>();
		for (CompiledProgram pass : set.passes()) {
			(pass.phase() == Phase.SHADOWCOMP ? shadowRuns : pass.phase() == Phase.SETUP ? setupRuns : frameRuns).add(pass);
		}
		this.shadowComps = new PassPlan[shadowRuns.size()];
		for (int s = 0; s < this.shadowComps.length; s++) {
			this.shadowComps[s] = plan(shadowRuns.get(s), layout, flips, scales, true,
				set.writtenBefore(shadowRuns.get(s).name()), Set.of());
		}
		this.setupPlans = new PassPlan[setupRuns.size()];
		for (int s = 0; s < this.setupPlans.length; s++) {
			this.setupPlans[s] = plan(setupRuns.get(s), layout, flips, scales, false,
				set.writtenBefore(setupRuns.get(s).name()), Set.of());
		}
		Set<Integer> dead = KEEP_DEAD_TARGETS ? Set.of() : set.deadTargets();
		this.plans = new PassPlan[frameRuns.size()];
		int deferred = this.plans.length, composite = this.plans.length;
		for (int p = 0; p < this.plans.length; p++) {
			CompiledProgram pass = frameRuns.get(p);
			this.plans[p] = plan(pass, layout, flips, scales, false, set.writtenBefore(pass.name()), dead);
			if (deferred == this.plans.length && pass.phase() != Phase.PREPARE && pass.phase() != Phase.BEGIN) {
				deferred = p;
			}
			if (composite == this.plans.length && pass.phase() != Phase.PREPARE && pass.phase() != Phase.BEGIN
					&& pass.phase() != Phase.DEFERRED) {
				composite = p;
			}
		}
		this.firstDeferred = deferred;
		this.firstComposite = Math.max(composite, deferred);
		this.opaqueOpen = gbuffers.opaque().open();
		this.feedbackOpaque = gbuffers.feedbackOpaque();
		this.carriedOpaque = gbuffers.carriedOpaque();
		this.translucentOpen = gbuffers.translucent().open();
		this.translucentAll = gbuffers.translucent().all();
		Set<Integer> sampled = new HashSet<>();
		for (int target : gbuffers.feedbackTranslucent()) {
			sampled.add(target);
		}
		this.translucentLoad = java.util.Arrays.stream(this.translucentAll).filter(sampled::contains).toArray();
		this.translucentFlip = java.util.Arrays.stream(this.translucentAll).filter(t -> !sampled.contains(t)).toArray();
		this.splitOpaque = new long[this.opaqueOpen.length];
		this.splitTranslucent = new long[this.translucentOpen.length];
		this.flipBeforeDeferred = union(gbuffers.sky().all(), gbuffers.opaque().all(), gbuffers.distant());
		this.flipAllGbuffers = union(gbuffers.sky().all(), gbuffers.opaque().all(), gbuffers.distant(),
			gbuffers.translucent().all());
		this.primed = primedTargets(gbuffers, layout);
		this.skyMips = mippedOf(gbuffers.reads()[GbufferPhase.SKY.ordinal()], layout);
		this.opaqueMips = mippedOf(gbuffers.reads()[GbufferPhase.OPAQUE.ordinal()], layout);
		this.translucentMips = mippedOf(gbuffers.reads()[GbufferPhase.TRANSLUCENT.ordinal()], layout);
		this.distantMips = mippedOf(gbuffers.reads()[GbufferPhase.DISTANT.ordinal()], layout);
		// E9: the shadow programs' chains from the built code (the reflection's level reads), not the declarations. A program
		// switched off or absent is not built, so its reads fill nothing (E6-fix-F).
		this.shadowFrameMips = mippedOf(gbuffers.shadowMipReads(), layout);
		writePassBlock();
	}

	/** The primed targets (the clear hook's load copies, in order); tests check them against the live targets. */
	int[] primed() {
		return this.primed.clone();
	}

	/** The frame passes the frame skips (E9: every output dead); tests check them against the set's dead targets. */
	Set<String> skippedPasses() {
		Set<String> skipped = new TreeSet<>();
		for (PassPlan plan : this.plans) {
			if (plan.skip) {
				skipped.add(plan.pass.name());
			}
		}
		return skipped;
	}

	/**
	 * The targets the clear hook primes (E4 review item 3): each sky, opaque or distant target that the clear leaves
	 * unrefreshed, or that a prepare pass wrote, copies its read texture into its write texture after the prepare
	 * passes. A cleared target is refreshed to its clear colour, so it needs no copy unless a prepare pass wrote it.
	 * None without a world: the sky and opaque phases never run then.
	 */
	private int[] primedTargets(Gbuffers gbuffers, Targets layout) {
		if (!gbuffers.servesWorld()) {
			return new int[0];
		}
		TreeSet<Integer> prepared = new TreeSet<>();
		for (int p = 0; p < this.firstDeferred; p++) {
			if (this.plans[p].pass.runs()) {
				for (int target : this.plans[p].targets) {
					prepared.add(target);
				}
			}
		}
		int[] candidates = union(gbuffers.sky().all(), gbuffers.opaque().all(), gbuffers.distant());
		int[] out = new int[candidates.length];
		int count = 0;
		for (int target : candidates) {
			if (!layout.clears(target) || prepared.contains(target)) {
				out[count++] = target;
			}
		}
		return java.util.Arrays.copyOf(out, count);
	}

	/** The distinct targets of several layouts, ascending (the flip lists of the phase boundaries). */
	private static int[] union(int[]... layouts) {
		TreeSet<Integer> targets = new TreeSet<>();
		for (int[] layout : layouts) {
			for (int target : layout) {
				targets.add(target);
			}
		}
		return targets.stream().mapToInt(Integer::intValue).toArray();
	}

	/** The targets of a list the layout mips, in list order (the phase boundaries' refills). */
	private static int[] mippedOf(int[] targets, Targets layout) {
		return java.util.Arrays.stream(targets).filter(t -> t < layout.count() && layout.mipmapped(t)).toArray();
	}

	/**
	 * One pass's plan. {@code written} names the targets earlier programs of its stage write (DimensionSet#writtenBefore):
	 * their samplers bind the target, not the stage's custom texture, as the pass's request was built. {@code dead} names
	 * the targets no program reads ({@link DimensionSet#deadTargets}; empty for shadowcomp and setup passes).
	 */
	private PassPlan plan(CompiledProgram pass, Targets layout, Map<String, Boolean> flips,
			Map<String, ShaderProperties.Scale> scales, boolean shadowOutputs, Set<String> written, Set<Integer> dead) {
		List<ColorTarget> outputs = pass.outputs();
		int count = outputs.size();
		int slots = count == 0 ? 0 : outputs.get(count - 1).attachment() + 1;  // sparse: the gaps stay unused slots
		float[][] clears = new float[slots][];
		int[] targets = new int[count];
		boolean[] flipsOfPass = new boolean[count];
		ShaderProperties.Scale scale = scales.get(pass.name());
		// A "don't care" load leaves the texels the pass does not write undefined, so it needs a pass that writes them all:
		// no scale.<program> viewport, a vertex stage that puts the quad over the whole viewport (ViewportCoverage: packs
		// shrink it to draw bloom tiles) and fragments that never discard.
		ProgramTranslation.Reflection.Flags shape = pass.reflection().flags();
		boolean whole = coversWhole(scale) && shape.coversViewport() && !shape.discards();
		for (int i = 0; i < count; i++) {
			ColorTarget output = outputs.get(i);
			targets[i] = output.target();
			clears[output.attachment()] = pass.blending().get(i) || !whole || LOAD_OUTPUTS ? null : MetalBridge.DONT_CARE;
			// The main colour target never flips (the final pass's output 0 is the main colour, not a colortex).
			flipsOfPass[i] = shadowOutputs ? flipsShadowColour(flips, pass.name(), output.target())
				: !(i == 0 && pass.finalPass()) && flipsTarget(flips, pass.name(), output.target());
		}
		// E9: the outputs into dead targets are not stored; a pass whose outputs are all dead, with no kernels, images or
		// storage buffers (whose effects another program could see), does not run.
		List<Integer> discardSlots = new ArrayList<>();
		boolean allDead = count > 0 && !shadowOutputs && !pass.finalPass();
		for (int i = 0; i < count; i++) {
			if (!shadowOutputs && dead.contains(targets[i])) {
				discardSlots.add(outputs.get(i).attachment());
			} else {
				allDead = false;
			}
		}
		// The engine's own blocks (QsFrame, QsCustom, QsDraw) are read-only; a pack's storage buffer may be written.
		boolean skip = allDead && pass.kernels().isEmpty() && pass.reflection().images().isEmpty()
			&& pass.reflection().buffers().stream().noneMatch(ResourceUse::storage);
		int[] discards = skip ? new int[0] : discardSlots.stream().mapToInt(Integer::intValue).toArray();
		List<ResourceUse> textures = pass.reflection().textures();
		int[] sources = new int[textures.size()];
		List<Integer> mips = new ArrayList<>();
		List<Integer> shadowMips = new ArrayList<>();
		for (int t = 0; t < textures.size(); t++) {
			ResourceUse texture = textures.get(t);
			String name = texture.name();
			int target = ProgramRequests.targetIndex(name);
			if (target >= 0 && target < layout.count()) {
				sources[t] = target;
				// The chain is refilled before the pass whenever the target changed since the last fill
				// (engine-pack-format.md section 7), and only when a lookup of the pass can read a level other than 0 (E9; the
				// reflection's levels): an implicit lookup of a program that does not declare the target reads level 0 (E6).
				if (layout.mipmapped(target) && texture.levels()) {
					mips.add(target);
				}
			} else {
				int customIndex = layout.customImages().indexOfSampler(name);
				sources[t] = customIndex >= 0 ? ComputeKernel.IMAGE_CUSTOM + customIndex : depthSource(name);
				if (this.shadow != null && SamplerSources.of(name) == SamplerSources.Source.SHADOW) {
					int depth = this.shadow.depthIndexOf(name);
					int key = depth >= 0 ? ShadowTargets.keyOfDepth(depth)
						: ShadowTargets.keyOfColor(this.shadow.colorIndexOf(name));
					if (this.shadow.mipmapped(key)) {
						shadowMips.add(key);
					}
				}
			}
		}
		int[] shadowMipKeys = shadowMips.stream().mapToInt(Integer::intValue).toArray();
		KernelPlan[] kernels = new KernelPlan[pass.kernels().size()];
		for (int ki = 0; ki < kernels.length; ki++) {
			ComputeKernel kernel = pass.kernels().get(ki);
			int[] reads = new int[kernel.textures().size()];
			for (int t = 0; t < reads.length; t++) {
				reads[t] = readSourceOf(kernel.textures().get(t).name(), layout);
				// Only a colour target has a chain here: readSourceOf also returns custom images (IMAGE_CUSTOM + index), depth
				// and shadow sources, which index past the target table (preview 13: Complementary's voxel_sampler, 300).
				if (reads[t] >= 0 && reads[t] < layout.count() && layout.mipmapped(reads[t])) {
					mips.add(reads[t]);
				}
			}
			int[] writes = new int[kernel.images().size()];
			for (int i = 0; i < writes.length; i++) {
				writes[i] = imageSourceOf(kernel.images().get(i).name(), layout);
			}
			int[] customIds = new int[reads.length];
			for (int t = 0; t < reads.length; t++) {
				customIds[t] = customIdOf(pass.name(), kernel.textures().get(t).name(), written);
			}
			kernels[ki] = new KernelPlan(kernel, reads, writes, customIds);
		}
		int[] imageSources = new int[pass.reflection().images().size()];
		for (int i = 0; i < imageSources.length; i++) {
			imageSources[i] = imageSourceOf(pass.reflection().images().get(i).name(), layout);
		}
		int[] customIds = new int[textures.size()];
		for (int t = 0; t < textures.size(); t++) {
			customIds[t] = customIdOf(pass.name(), textures.get(t).name(), written);
		}
		int[] mipTargets = mips.stream().distinct().mapToInt(Integer::intValue).toArray();
		return new PassPlan(pass, label("qs " + pass.name()), slots, clears, targets, flipsOfPass, sources, mipTargets,
				shadowMipKeys, scale, shadowOutputs, imageSources, kernels, customIds, skip, discards, whole);
	}

	/** Whether a pass with this {@code scale.<program>} (null: none) draws its whole target. */
	private static boolean coversWhole(ShaderProperties.@Nullable Scale scale) {
		return scale == null || scale.scale() >= 1.0 && scale.offsetX() == 0.0 && scale.offsetY() == 0.0;
	}

	/**
	 * The custom texture id a pass's sampler binds, or -1: the pack binds none, or the binding gives way to a target the
	 * pass's stage wrote earlier ({@link CustomTextureTable#targetReplaces}, the rule the pass's request was built with).
	 */
	private int customIdOf(String pass, String sampler, Set<String> written) {
		if (this.customs == null) {
			return -1;
		}
		int id = this.customs.id(pass, sampler);
		CustomTextureTable.Entry entry = this.customs.entry(id);
		return entry != null && CustomTextureTable.targetReplaces(entry, sampler, written) ? -1 : id;
	}

	/**
	 * The source a sampled name reads (engine-pack-format.md section 7): {@code depthtex0} everything,
	 * {@code depthtex1} no translucents, {@code depthtex2} no translucents and no hand; the shadow names read the set's
	 * shadow targets (E5); anything else reads {@code depthtex0} as E3's placeholder did.
	 */
	private int depthSource(String name) {
		if (SamplerSources.of(name) == SamplerSources.Source.SHADOW) {
			if (this.shadow == null) {
				return SOURCE_NONE;
			}
			int depth = this.shadow.depthIndexOf(name);
			return depth >= 0 ? SOURCE_SHADOW_DEPTH - depth : SOURCE_SHADOW_COLOR - this.shadow.colorIndexOf(name);
		}
		if (name.equals("noisetex")) {
			return SOURCE_NOISE;
		}
		if (name.equals("depthtex1")) {
			return SOURCE_DEPTH1;
		}
		if (name.equals("depthtex2")) {
			return SOURCE_DEPTH2;
		}
		int distant = SamplerSources.distantDepthOf(name);
		if (distant >= 0) {
			return distant == 0 ? SOURCE_DISTANT0 : SOURCE_DISTANT1;
		}
		return SOURCE_DEPTH0;
	}

	/** A label's bytes in the set's arena (one allocation per label per set, none per frame). */
	private MemorySegment label(String text) {
		return this.labelArena.allocateFrom(text, StandardCharsets.UTF_8);
	}

	/** Frees the set's label and spill memory (with the set's pipelines; engine off, pack switch, resource reload). */
	@Override
	public void close() {
		if (!this.closed) {
			this.closed = true;
			for (PassPlan[] group : new PassPlan[][] {this.plans, this.setupPlans, this.shadowComps}) {
				for (PassPlan plan : group) {
					for (KernelPlan kernel : plan.kernels) {
						kernel.releaseSpill();
					}
				}
			}
			this.labelArena.close();
		}
	}

	/**
	 * The pass block's constant bytes: the documented quad's values (identity model-view, the orthographic projection
	 * mapping [0,1]^2 to clip space, identity texture and normal matrices, and the inverses; the emitter substitutes
	 * the quad's projection and model-view for the compatibility built-ins itself, the block serves
	 * {@code gl_TextureMatrix[0]}, {@code gl_NormalMatrix} and the declared inverse uniforms).
	 */
	private void writePassBlock() {
		float[] identity = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
		// The emitter's QUAD_PROJECTION (StageEmitter): (u, v, z, 1) -> (2u - 1, 2v - 1, -z, 1), column-major.
		float[] quad = {2, 0, 0, 0, 0, 2, 0, 0, 0, 0, -1, 0, -1, -1, 0, 1};
		// Its inverse: (x, y, z, 1) -> (x/2 + 1/2, y/2 + 1/2, -z, 1).
		float[] quadInverse = {0.5f, 0, 0, 0, 0, 0.5f, 0, 0, 0, 0, -1, 0, 0.5f, 0.5f, 0, 1};
		float[] normal = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0};
		int at = 0;
		at = put(this.passBlock, at, identity);      // modelViewMatrix
		at = put(this.passBlock, at, quad);          // projectionMatrix
		at = put(this.passBlock, at, identity);      // modelViewMatrixInverse
		at = put(this.passBlock, at, quadInverse);   // projectionMatrixInverse
		at = put(this.passBlock, at, identity);      // textureMatrix
		put(this.passBlock, at, normal);             // normalMatrix (three vec4 columns)
		// atlasSize (ivec2, after the mat3) is not written: a full-screen draw binds no atlas, and the buffer is allocated zeroed,
		// so the program reads (0, 0) (E6-atlassize; ProgramRequests.PASS_BLOCK).
	}

	private static int put(ByteBuffer buffer, int offset, float[] values) {
		for (float value : values) {
			buffer.putFloat(offset, value);
			offset += 4;
		}
		return offset;
	}

	/**
	 * The frame's head (E6; the documented order setup, begin, shadow, shadowcomp, prepare): the setup programs' kernels once
	 * after a load or a resize, the begin programs' kernels every frame, then the custom images with clearEachFrame are
	 * zeroed. The engine calls it from the shadow hook, before the shadow pass, on every frame; a frame whose shadow hook
	 * did not run gets it from {@link #clearHook} instead, so it runs once per frame and before the frame's passes.
	 */
	public void frameHead(long enc, Targets targets, PassBlocks blocks) {
		if (this.headRan) {
			return;
		}
		this.headRan = true;
		this.blocks.upload(this.passBlock, this.passBytes);
		if (enc != 0L) {
			targets.customImages().clearInitial(enc);
		}
		if (this.setupPending) {
			this.setupPending = false;
			for (int s = 0; s < this.setupPlans.length; s++) {
				this.ops.setup(enc, targets, blocks, s);
			}
		}
		for (int i = 0; i < this.firstDeferred; i++) {
			if (this.plans[i].pass.phase() == Phase.BEGIN && this.plans[i].pass.active()) {
				this.ops.begin(enc, targets, blocks, i);
			}
		}
		this.ops.clearImages(enc, targets);
		fillMipTargets(enc, this.shadowFrameMips, targets);
	}

	/**
	 * Closes the frame's head window (E6, finding 12). The engine calls it when its clear hook returns, whether or not the
	 * frame drew, so the next frame's shadow hook runs the head: a frame whose clear hook returned early leaves no flag set.
	 */
	public void endFrameHead() {
		this.headRan = false;
	}

	/** The programs skipped so far for a storage block with no buffer, one per program (the tests read it). */
	int storageSkipCount() {
		return this.storageSkips.size();
	}

	/**
	 * The clear hook (the frame graph's "clear" pass, a mixin on {@code LevelRenderer.lambda$render$0}; plan.md E4's
	 * frame-order bullet): the pack targets' clears by E3's rule, the depth targets to 1.0, then the {@code prepare}
	 * passes. The sky and main passes follow the game's own order. The frame head runs first when no shadow hook did.
	 */
	public void clearHook(long enc, @Nullable Object encoder, Targets targets, PassBlocks blocks) {
		if (!this.headRan) {
			frameHead(enc, targets, blocks);
		}
		this.headRan = false;  // consumed: the next frame's shadow hook runs the head again
		this.phaseRan = false;
		this.blocks.upload(this.passBlock, this.passBytes);
		this.ops.targetClears(enc, targets);
		this.ops.depthClears(enc, targets);
		for (int i = 0; i < this.firstDeferred; i++) {
			if (this.plans[i].pass.active() && this.plans[i].pass.phase() != Phase.BEGIN) {
				this.ops.runPass(enc, encoder, targets, null, blocks, i);
			}
		}
		// The load base of the sky and opaque phases (E4 review item 3): after the prepare passes, so the picture they
		// left is the one copied; only the primed targets (see primed) start from their read texture.
		for (int target : this.primed) {
			if (DrawLog.on()) {
				DrawLog.primed(target, targets.read(target), targets.write(target), targets.width(target),
					targets.height(target), targets.format(target));
			}
			this.ops.loadCopy(enc, targets, target);
		}
		fillMipTargets(enc, this.skyMips, targets);
		// The horizon (plan.md E4): the directions below the horizon, painted by the pack's gbuffers_skybasic after the
		// load base is in place; the game's sky pass then loads the picture and draws over it.
		if (this.horizon != null) {
			this.horizon.draw(enc, GbufferDelegate.active(), targets, this.gbuffers.sky());
		}
		this.ops.endFrame(enc);
	}

	/**
	 * Installs the horizon draw (plan.md E4) for this set, or null while no world is drawn. The clear hook draws it once
	 * per frame; the frame never owns the horizon's GPU objects (the engine closes it with the set).
	 */
	public void horizon(@Nullable Horizon horizon) {
		this.horizon = horizon;
	}

	/**
	 * The phase hook (plan.md E4's phases bullet; a mixin between {@code lambda$addMainPass$0}'s {@code executeSolid}
	 * call, bytecode 174, and {@code executeClassicTransparency}, 187). The game's "Solid" pass is open on the opaque
	 * attachments here. The order: {@code depthtex2} copy (no hand, no translucents), the reopen on the opaque
	 * attachments and the solid hand ({@code solidHand}, null when no hand frame was prepared), the {@code depthtex1}
	 * copy (after the hand), the flips that move the sky and opaque pictures to the read side, {@code deferred}, the
	 * translucent targets' hand-over (a flip that moves the picture to the write side for a target no program of the phase
	 * samples, else the load copy; E9), and the reopen on the translucent attachments for the game's translucent draws.
	 */
	public void phaseHook(long enc, @Nullable Object encoder, Targets targets, PassBlocks blocks,
			@Nullable Runnable solidHand, @Nullable Runnable transparentDistant) {
		this.phaseRan = true;
		if (DrawLog.dumping()) {
			DrawDumps.point(encoder, enc, targets, "02-opaque-end");
		}
		if (targets.depthWanted(2)) {
			this.ops.depthCopy(enc, targets, 2);
		}
		if (solidHand != null) {
			this.gbuffers.phases().accept(GbufferPhase.OPAQUE);
			this.ops.split(encoder, fill(this.splitOpaque, this.opaqueOpen, targets), targets.depth(0), targets.mainWidth(),
				targets.mainHeight());
			solidHand.run();
		}
		if (targets.depthWanted(1)) {
			this.ops.depthCopy(enc, targets, 1);
		}
		for (int target : this.flipBeforeDeferred) {
			this.ops.flip(targets, target);
		}
		if (DrawLog.dumping()) {
			DrawDumps.point(encoder, enc, targets, "03-phase-flip");
		}
		for (int i = this.firstDeferred; i < this.firstComposite; i++) {
			if (this.plans[i].pass.active()) {
				this.ops.runPass(enc, encoder, targets, null, blocks, i);
				if (DrawLog.dumping()) {
					// The open encoder is the engine's pass, not the game's suspended pass: a readback restores no game state on it
					// (the game's pipeline on the pass's attachments fails Metal's validation); the split below reopens the game's pass.
					DrawDumps.point(null, enc, targets, String.format("04-deferred-%s", this.plans[i].pass.name()));
				}
			}
		}
		for (int target : this.translucentFlip) {
			this.ops.flip(targets, target);
		}
		for (int target : this.translucentLoad) {
			this.ops.loadCopy(enc, targets, target);
		}
		fillMipTargets(enc, this.translucentMips, targets);
		// Distant Horizons' transparent layer (Iris's order: dh_water before the game's translucents). The hook suspends the game's
		// open pass around its own pass; the split below reopens the game's pass on the translucent attachments.
		if (transparentDistant != null) {
			transparentDistant.run();
		}
		this.gbuffers.phases().accept(GbufferPhase.TRANSLUCENT);
		this.ops.split(encoder, fill(this.splitTranslucent, this.translucentOpen, targets), targets.depth(0),
			targets.mainWidth(), targets.mainHeight());
	}

	/** {@link #phaseHook(long, Object, Targets, PassBlocks, Runnable, Runnable)} without Distant Horizons' transparent call (its water then draws after the level). */
	public void phaseHook(long enc, @Nullable Object encoder, Targets targets, PassBlocks blocks, @Nullable Runnable solidHand) {
		phaseHook(enc, encoder, targets, blocks, solidHand, null);
	}

	/**
	 * The opaque phase's begin (a mixin before the level's Solid pass opens, {@code LevelRenderer.lambda$addMainPass$0}
	 * at its {@code createRenderPass} call, bytecode 156; no render pass is open there, so the copies are plain blits).
	 * Two copies, both only where a world is drawn ({@code Gbuffers.servesWorld}): each carried target (the sky wrote
	 * it, the opaque programs sample it) copies its write texture, where the sky's picture sits, into its read texture;
	 * then each opaque feedback target loads its read texture into its write texture. The opaque draws then sample the
	 * picture from before the phase.
	 */
	public void opaqueHook(long enc, Targets targets) {
		if (DrawLog.dumping()) {
			DrawDumps.point(null, enc, targets, "01-sky-end");
		}
		for (int target : this.carriedOpaque) {
			this.ops.carryCopy(enc, targets, target);
		}
		for (int target : this.feedbackOpaque) {
			this.ops.loadCopy(enc, targets, target);
		}
		fillMipTargets(enc, this.opaqueMips, targets);
	}

	/**
	 * The Distant Horizons phase's begin (plan.md E7): the mip chains its programs read are refilled before DH's terrain draws, as
	 * the other phases' are refilled at their boundaries. Called only while DH draws through the pack.
	 */
	public void distantHook(long enc, Targets targets) {
		fillMipTargets(enc, this.distantMips, targets);
	}

	/** The write textures of a layout, in attachment order, refilled into {@code out} (flips move them per hook). */
	private static long[] fill(long[] out, int[] layout, Targets targets) {
		for (int i = 0; i < layout.length; i++) {
			out[i] = targets.write(layout[i]);
		}
		return out;
	}

	/**
	 * The composite hook (E3's after-level hook, right after {@code renderLevel}): the translucent targets flip (their
	 * picture moves to the read side), then {@code composite} and {@code final} run. When the takeover serves no world
	 * (E3's packs), E3's world copy comes first and the composite passes read the game's picture; a pack without a
	 * running final shows colortex0 on the main colour.
	 */
	public void compositeHook(long enc, @Nullable Object encoder, Targets targets, Main main, PassBlocks blocks) {
		boolean copied = false;
		if (DrawLog.dumping()) {
			DrawDumps.point(encoder, enc, targets, "05-translucent-end");
		}
		if (this.gbuffers.servesWorld()) {
			for (int target : this.phaseRan ? this.translucentAll : this.flipAllGbuffers) {
				this.ops.flip(targets, target);
			}
		} else {
			// E3's order without gbuffers: the world copy, then deferred (the phase hook runs deferred only when a world
			// is drawn), then composite.
			copied = true;
			this.ops.worldCopy(enc, encoder, targets, main);
			for (int i = this.firstDeferred; i < this.firstComposite; i++) {
				if (this.plans[i].pass.active()) {
					this.ops.runPass(enc, encoder, targets, null, blocks, i);
				}
			}
		}
		boolean ranFinal = false;
		for (int i = this.firstComposite; i < this.plans.length; i++) {
			if (!this.plans[i].pass.active()) {
				continue;  // nothing to run: no pipeline and no kernel (a compute-only pass runs its kernels in runPass)
			}
			if (drawsFinal(this.plans[i].pass)) {
				// The final pass writes the whole main colour, so the pending clear is dropped only when it draws. A final pass the
				// storage check skips keeps the clear, and showColortex0 below shows colortex0 in the window (review item 6). A
				// final that may leave texels unwritten (scale.final, a discard, a quad that is not proven to cover the window)
				// gets the clear written first, and loads it around what it draws.
				ranFinal = true;
				if (encoder != null && main.gameTexture != null) {
					if (this.plans[i].whole) {
						MetalBridge.dropPendingClear(encoder, main.gameTexture);
					} else {
						MetalBridge.flushClear(encoder, main.gameTexture);
					}
				}
			}
			this.ops.runPass(enc, encoder, targets, main, blocks, i);
			if (DrawLog.dumping()) {
				DrawDumps.point(null, enc, targets, String.format("06-composite-%s", this.plans[i].pass.name()));
			}
		}
		if (DrawLog.dumping()) {
			DrawDumps.point(null, enc, targets, "07-final");
			DrawDumps.picture(null, enc, main.texture, "07-final-main", false);
		}
		if (!ranFinal && (copied || this.gbuffers.servesWorld())) {
			// Decision (E3), no documented behaviour: see copyToMain.
			this.ops.showColortex0(enc, encoder, targets, main);
		}
		this.ops.endFrame(enc);
	}

	/**
	 * Whether a composite pass draws this frame: it is the final pass, it runs, and every storage block it names has a buffer
	 * (runPass skips such a pass after its kernels). The hook decides with this before it sets {@code ranFinal} and drops the
	 * main colour's pending clear.
	 */
	private boolean drawsFinal(CompiledProgram pass) {
		return pass.finalPass() && pass.runs() && unboundStorage(pass.reflection().buffers()) == null;
	}

	/**
	 * Groups allocated clearing targets by live pixel dimensions (max 4 targets = 8 attachments per Metal render pass).
	 * Called at construction and lazily after any {@link Targets} rebuild so a window resize that separates fixed-size
	 * and screen-relative targets never leaves mismatched sizes in one clear pass.
	 */
	private void buildClearingChunks(Targets layout) {
		java.util.LinkedHashMap<Long, List<Integer>> clearingBySize = new java.util.LinkedHashMap<>();
		for (int t = 0; t < layout.count(); t++) {
			if (layout.made(t) && layout.clears(t)) {
				long sizeKey = (((long) layout.width(t)) << 32) | (layout.height(t) & 0xffffffffL);
				clearingBySize.computeIfAbsent(sizeKey, k -> new ArrayList<>()).add(t);
			}
		}
		List<int[]> chunkTargets = new ArrayList<>();
		List<long[]> chunkHandles = new ArrayList<>();
		List<float[][]> chunkColors = new ArrayList<>();
		for (List<Integer> group : clearingBySize.values()) {
			for (int start = 0; start < group.size(); start += 4) {
				int inChunk = Math.min(4, group.size() - start);
				int[] targetsInChunk = new int[inChunk];
				long[] handlesInChunk = new long[inChunk * 2];
				float[][] colorsInChunk = new float[inChunk * 2][];
				for (int i = 0; i < inChunk; i++) {
					int target = group.get(start + i);
					targetsInChunk[i] = target;
					float[] color = layout.clearColor(target);
					colorsInChunk[i * 2] = color;
					colorsInChunk[i * 2 + 1] = color;
				}
				chunkTargets.add(targetsInChunk);
				chunkHandles.add(handlesInChunk);
				chunkColors.add(colorsInChunk);
			}
		}
		this.clearingChunks = chunkTargets.toArray(int[][]::new);
		this.clearHandles = chunkHandles.toArray(long[][]::new);
		this.clearColors = chunkColors.toArray(float[][][]::new);
		this.clearTargets = layout;
	}

	/**
	 * The clears of one frame (plan.md E3): both textures of every clearing target, each in the colour the targets hold
	 * now (colortex0's default is this frame's fog, refreshed before the clear hook).
	 */
	private void clears(long enc, Targets targets) {
		if (targets != this.clearTargets) {
			buildClearingChunks(targets);
		}
		for (int c = 0; c < this.clearingChunks.length; c++) {
			long[] handles = this.clearHandles[c];
			for (int i = 0; i < this.clearingChunks[c].length; i++) {
				int target = this.clearingChunks[c][i];
				handles[i * 2] = targets.read(target);
				handles[i * 2 + 1] = targets.write(target);
				targets.clearColorInto(target, this.clearColors[c][i * 2]);  // both textures of the target share this array
			}
			int rep = this.clearingChunks[c][0];
			MetalBridge.renderBegin(enc, handles, this.clearColors[c], 0, false, 0, targets.width(rep),
				targets.height(rep));
			if (c == 0) {
				ShaderNative.renderLabel(enc, this.clearsLabel);
			}
			for (int target : this.clearingChunks[c]) {
				targets.bump(target);
			}
		}
	}

	/** Every depth target cleared to the far value 1.0 (the frame's clear hook; plan.md E4, and the DH depth of E7). */
	private void depthClears(long enc, Targets targets) {
		clearDepths(enc, targets, this.depthLabel);
	}

	/**
	 * The depth clears of a frame: the engine's depth targets, then the Distant Horizons pair when wanted (plan.md E7). Each is
	 * cleared to 1.0, the far value of the engine's convention, so the pair starts where the engine's own depth does. With the
	 * render scale above 1 (ADR 0008), the depth of the passes that keep their own is cleared to 0.0, the game's convention for
	 * those passes, so a first pass that loads it never reads undefined memory.
	 */
	static void clearDepths(long enc, Targets targets, MemorySegment label) {
		for (int d = 0; d < Targets.DEPTH_TARGETS; d++) {
			if (!targets.depthWanted(d)) {
				continue;
			}
			MetalBridge.renderBegin(enc, NO_TEXTURES, NO_CLEARS, targets.depth(d), true, 1.0f, targets.mainWidth(),
				targets.mainHeight());
			ShaderNative.renderLabel(enc, label);
		}
		if (targets.overlayDepth() != 0) {
			MetalBridge.renderBegin(enc, NO_TEXTURES, NO_CLEARS, targets.overlayDepth(), true, 0.0f, targets.mainWidth(),
				targets.mainHeight());
			ShaderNative.renderLabel(enc, label);
		}
		if (!targets.distantDepthWanted()) {
			return;
		}
		for (int d = 0; d < Targets.DISTANT_DEPTH_TARGETS; d++) {
			MetalBridge.renderBegin(enc, NO_TEXTURES, NO_CLEARS, targets.distantDepth(d), true, 1.0f, targets.mainWidth(),
				targets.mainHeight());
			ShaderNative.renderLabel(enc, label);
		}
	}

	/**
	 * Copies the Distant Horizons opaque depth into {@code dhDepthTex1}, the copy made before DH's transparent terrain (plan.md
	 * E7). No pass is open at that point (DH's terrain renders between passes), so the copy is a plain blit.
	 */
	public void distantDepthCopy(long enc, Targets targets) {
		if (!targets.distantDepthWanted()) {
			return;
		}
		MetalBridge.blitTextureToTexture(enc, targets.distantDepth(0), targets.distantDepth(1), 0, targets.mainWidth(),
			targets.mainHeight());
	}

	/**
	 * Copies the game's picture into colortex0's read texture: the game's deferred clear of the main colour is written
	 * to memory first (flushClear), then a blit when the target matches the main target's format and size, else a
	 * full-screen draw through the engine's copy program (plan.md E3, the encoding bullet).
	 */
	private void copyWorld(long enc, @Nullable Object encoder, Targets targets, Main main) {
		if (encoder != null && main.gameTexture != null) {
			MetalBridge.flushClear(encoder, main.gameTexture);
		}
		if (targets.format(0) == main.format && targets.width(0) == main.width && targets.height(0) == main.height) {
			if (DrawLog.on()) DrawLog.copy("world", 0, main.texture, targets.read(0));
			MetalBridge.blitTextureToTexture(enc, main.texture, targets.read(0), 0, main.width, main.height);
		} else {
			this.copyColors[0] = targets.read(0);
			MetalBridge.renderBegin(enc, this.copyColors, this.copyClears, 0, false, 0, targets.width(0),
				targets.height(0));
			ShaderNative.renderLabel(enc, this.copyLabel);
			MetalBridge.renderPipeline(enc, this.copyPipeline, 0, false, false, 0, 0, 3);
			MetalBridge.renderTexture(enc, 0, main.texture);
			MetalBridge.renderDraw(enc, 3, 1, 0, 0);
		}
		targets.bump(0);
	}

	/**
	 * Shows colortex0 on the main colour when the pack has no final program, or its final failed. Decision (E3), not
	 * documented behaviour: no documented behaviour exists for a missing final. OptiFine's shaders.txt lists
	 * {@code final <final> <none>} (a final program, no fallback program) and says only "The final shader renders
	 * directly to the screen." Iris's final page says final "outputs to the backbuffer (which gets written to the
	 * screen)" and says nothing about a missing final. The engine shows colortex0, the target the composite programs
	 * leave the picture in. A blit when the formats and sizes match, else a full-screen draw through the main-format
	 * copy pipeline, which the engine builds at activation.
	 */
	private void copyToMain(long enc, @Nullable Object encoder, Targets targets, Main main) {
		if (encoder != null && main.gameTexture != null) {
			MetalBridge.dropPendingClear(encoder, main.gameTexture);  // the copy overwrites the whole main colour
		}
		if (targets.format(0) == main.format && targets.width(0) == main.width && targets.height(0) == main.height) {
			if (DrawLog.on()) DrawLog.copy("show", 0, targets.read(0), main.texture);
			MetalBridge.blitTextureToTexture(enc, targets.read(0), main.texture, 0, main.width, main.height);
		} else {
			this.mainColours[0] = main.texture;
			MetalBridge.renderBegin(enc, this.mainColours, this.copyClears, 0, false, 0, main.width, main.height);
			ShaderNative.renderLabel(enc, this.mainCopyLabel);
			MetalBridge.renderPipeline(enc, this.finalCopyPipeline, 0, false, false, 0, 0, 3);
			MetalBridge.renderTexture(enc, 0, targets.read(0));
			MetalBridge.renderDraw(enc, 3, 1, 0, 0);
		}
	}

	/**
	 * The bilinear upscale (ADR 0008: MetalFX's fallback and the profiling arm): a full-screen draw of {@code input} (the
	 * render size) into {@code output} (the main colour, the window size) through the main-format copy pipeline, whose sampler
	 * is linear with normalised coordinates. Every pixel is written, so nothing is loaded. Ends its encoder.
	 */
	public void upscaleCopy(long enc, long input, long output, int width, int height) {
		this.mainColours[0] = output;
		MetalBridge.renderBegin(enc, this.mainColours, this.copyClears, 0, false, 0, width, height);
		ShaderNative.renderLabel(enc, this.upscaleLabel);
		MetalBridge.renderPipeline(enc, this.finalCopyPipeline, 0, false, false, 0, 0, 3);
		MetalBridge.renderTexture(enc, 0, input);
		MetalBridge.renderDraw(enc, 3, 1, 0, 0);
		this.ops.endFrame(enc);
	}

	/** One compute kernel of a pass with the sources its textures and images read, resolved at plan time (E6). */
	private static final class KernelPlan {
		final ComputeKernel kernel;
		/** Per sampled texture of the kernel's reflection: its read source (the encoding of {@link #sourceTexture}). */
		final int[] textureSources;
		/** Per image of the kernel's reflection: its write source ({@link ComputeKernel}'s encoding; -1 when none). */
		final int[] imageSources;
		/** Per sampled texture: the custom texture id the pack binds to it in this kernel's program (E6-B), or -1. */
		final int[] customIds;
		/** The work groups of the current dispatch, rewritten per dispatch (the frame path allocates nothing). */
		final int[] groups = new int[3];
		/** Whether this kernel's spill buffer size has been logged (once per set). */
		boolean spillLogged;
		/** Whether this kernel's spill refusal has been logged (once per set). */
		boolean spillRefused;
		/** The need the device last refused a buffer for (-1: none); the same need is not asked again. */
		long refusedNeed = -1L;
		/** This kernel's device buffer of spilled shared arrays: 0 until a dispatch needs it; regrown, released with the set. */
		long spill;
		/** The bytes {@link #spill} holds. */
		long spillCapacity;

		KernelPlan(ComputeKernel kernel, int[] textureSources, int[] imageSources, int[] customIds) {
			this.kernel = kernel;
			this.textureSources = textureSources;
			this.imageSources = imageSources;
			this.customIds = customIds;
		}

		/** Frees the spill buffer (the set closes); a command buffer that bound it retains it until it finishes. */
		void releaseSpill() {
			if (this.spill != 0L) {
				MetalBridge.release(this.spill);
				this.spill = 0L;
				this.spillCapacity = 0L;
			}
		}
	}

	/** The write source of an image name ({@link ComputeKernel}'s encoding), or -1 when the engine serves no such image. */
	private static int imageSourceOf(String name, Targets layout) {
		int customIndex = layout.customImages().indexOfImage(name);
		if (customIndex >= 0) {
			return ComputeKernel.IMAGE_CUSTOM + customIndex;
		}
		int colour = ComputeKernel.colourTargetOf(name);
		if (colour >= 0) {
			return ComputeKernel.IMAGE_TARGET + colour;
		}
		int shadow = ComputeKernel.shadowColourImageOf(name);
		if (shadow >= 0) {
			return ComputeKernel.IMAGE_SHADOW + shadow;
		}
		return -1;
	}

	/** The read source of a sampled name: the rule {@link #plan} applies, without the mip chain. */
	private int readSourceOf(String name, Targets layout) {
		int target = ProgramRequests.targetIndex(name);
		if (target >= 0 && target < layout.count()) {
			return target;
		}
		int customIndex = layout.customImages().indexOfSampler(name);
		return customIndex >= 0 ? ComputeKernel.IMAGE_CUSTOM + customIndex : depthSource(name);
	}

	/** The current (read-side) texture of an image source, or 0 when the engine holds none for it. */
	private long imageTexture(int source, Targets targets) {
		if (source >= ComputeKernel.IMAGE_CUSTOM) {
			return targets.customImages().texture(source - ComputeKernel.IMAGE_CUSTOM);
		}
		if (source < 0) {
			return 0L;
		}
		if (source >= ComputeKernel.IMAGE_SHADOW) {
			return this.shadow == null ? 0L : this.shadow.color(source - ComputeKernel.IMAGE_SHADOW);
		}
		int target = source - ComputeKernel.IMAGE_TARGET;
		return target < targets.count() ? targets.read(target) : 0L;
	}

	/**
	 * The compute kernels of a pass, in dispatch order, each on the frame's compute encoder before the pass's own stages
	 * (E6). Its textures and images bind through the same resource lists the translator reflected; the frame's, pass's and
	 * custom blocks bind at their slots. The work groups come from the kernel's own constants, with the view size. A
	 * dispatch whose spill buffer is refused, or whose storage block has no buffer, does not run (the log says why).
	 */
	private void runKernels(long enc, PassPlan plan, Targets targets, PassBlocks blocks) {
		KernelPlan[] kernels = plan.kernels;
		for (int ki = 0; ki < kernels.length; ki++) {
			KernelPlan k = kernels[ki];
			ComputeKernel kernel = k.kernel;
			ResourceUse unbound = unboundStorage(kernel.buffers());
			if (unbound != null) {
				skipUnboundStorage(kernel.name(), unbound);
				continue;
			}
			kernel.groupsInto(targets.mainWidth(), targets.mainHeight(), k.groups);  // the view size (the docs' viewWidth/Height)
			long spill = spillFor(k);  // E6: the shared arrays above 32 KB, in this kernel's own private buffer
			if (spill < 0L) {
				continue;
			}
			// Refilled before each dispatch, not once per pass: a kernel stores into the targets it reads through images, and the
			// kernels after it read the chain of those stores (the fill is generation-checked, so it costs nothing unchanged).
			fillMipTargets(enc, plan.mipTargets, targets);
			MetalBridge.computePipeline(enc, kernel.pipeline());
			long[] frameBlock = blocks.frameBlock(plan.pass.name());
			if (frameBlock != null) {
				MetalBridge.computeBuffer(enc, ProgramRequests.FRAME_SLOT, frameBlock[0], frameBlock[1]);
			}
			long[] customBlock = blocks.customBlock();
			if (customBlock != null) {
				MetalBridge.computeBuffer(enc, ProgramRequests.CUSTOM_SLOT, customBlock[0], customBlock[1]);
			}
			long[] distantBlock = blocks.distantBlock();
			if (distantBlock != null) {
				MetalBridge.computeBuffer(enc, EngineSlots.DISTANT_HORIZONS, distantBlock[0], distantBlock[1]);
			}
			MetalBridge.computeBuffer(enc, ProgramRequests.DRAW_SLOT, this.passBytes[0], this.passBytes[1]);
			bindStorage(kernel.buffers(), enc, true);
			List<ResourceUse> textures = kernel.textures();
			for (int t = 0; t < textures.size(); t++) {
				// E6-B: the pack's custom texture of this sampler in this kernel's program first, else the engine's source.
				long custom = this.customs == null || k.customIds[t] < 0 ? 0L : this.customs.handle(k.customIds[t]);
				long handle = custom != 0L ? custom : sourceTexture(k.textureSources[t], targets);
				if (handle != 0L) {
					MetalBridge.computeTexture(enc, textures.get(t).slot(), handle);
				}
			}
			List<ResourceUse> images = kernel.images();
			for (int i = 0; i < images.size(); i++) {
				long handle = imageTexture(k.imageSources[i], targets);
				if (handle != 0L) {
					MetalBridge.computeTexture(enc, images.get(i).slot(), handle);
				}
			}
			if (spill != 0L) {
				MetalBridge.computeBuffer(enc, EngineSlots.SHARED_SPILL, spill, 0L);
			}
			MetalBridge.computeDispatch(enc, k.groups[0], k.groups[1], k.groups[2], kernel.localX(), kernel.localY(),
				kernel.localZ());
			for (int i = 0; i < k.imageSources.length; i++) {
				int source = k.imageSources[i];
				if (source >= ComputeKernel.IMAGE_TARGET && source < ComputeKernel.IMAGE_SHADOW) {
					targets.bump(source - ComputeKernel.IMAGE_TARGET);  // a store changed the target: its mip chain refills
				} else if (source >= ComputeKernel.IMAGE_SHADOW && source < ComputeKernel.IMAGE_CUSTOM && this.shadow != null) {
					this.shadow.wroteColor(source - ComputeKernel.IMAGE_SHADOW, false);  // shadowcolorimgN store: mip chain refills
				}
			}
		}
	}

	/** The largest spill buffer one dispatch may use (bytes): a dispatch above it is refused, not allocated (plan.md E6). */
	static final long SPILL_CAP_BYTES = 256L * 1024 * 1024;

	/**
	 * The device buffer that holds a kernel's spilled shared arrays for one dispatch: each group's copy of them (the bytes
	 * per group) times the work groups, bound at {@link EngineSlots#SHARED_SPILL}. Private to the device (no CPU copy), one
	 * per kernel, regrown when a dispatch needs more, released with the set. Returns 0 when the kernel spills nothing and -1
	 * when the dispatch is refused: its buffer would pass {@link #SPILL_CAP_BYTES}, or the device refused the buffer (each
	 * refusal is logged once per kernel).
	 */
	private long spillFor(KernelPlan k) {
		ComputeKernel kernel = k.kernel;
		if (kernel.spillBytesPerGroup() == 0) {
			return 0L;
		}
		long need = (long) k.groups[0] * k.groups[1] * k.groups[2] * kernel.spillBytesPerGroup();
		if (need > SPILL_CAP_BYTES) {
			if (firstRefusal(k)) {
				LOG.error("[mcms-shaders] {}: the dispatch is skipped: a dispatch needs {} bytes of shared arrays spilled to device memory, above the cap of {} bytes (the frame goes on without it)", kernel.name(), need, SPILL_CAP_BYTES);
			}
			return -1L;
		}
		if (k.spill == 0L || k.spillCapacity < need) {
			if (need == k.refusedNeed) {
				return -1L;  // the device refused a buffer of this need: it is asked again only when the need changes
			}
			// At least the need, and twice the old capacity (capped): a view that grows a little does not regrow every frame.
			long capacity = Math.min(SPILL_CAP_BYTES, Math.max(need, 2 * k.spillCapacity));
			k.releaseSpill();  // a command buffer that bound the old buffer retains it until it finishes
			k.spill = MetalBridge.newBufferPrivate(MetalBridge.currentContext(), capacity);
			if (k.spill == 0L) {
				k.refusedNeed = need;
				if (firstRefusal(k)) {
					LOG.error("[mcms-shaders] {}: the dispatch is skipped: the device refused a shared-array buffer of {} bytes (the frame goes on without it)", kernel.name(), capacity);
				}
				return -1L;
			}
			k.refusedNeed = -1L;
			k.spillCapacity = capacity;
			if (!k.spillLogged) {
				k.spillLogged = true;
				LOG.info("[mcms-shaders] {}: shared arrays of {} bytes a group move to one device buffer per kernel (above Metal's 32 KB threadgroup memory)", kernel.name(), kernel.spillBytesPerGroup());
			}
		}
		return k.spill;
	}

	/** Claims a kernel's refusal log line: true the first time only, so a refused dispatch builds its message once. */
	private static boolean firstRefusal(KernelPlan k) {
		if (k.spillRefused) {
			return false;
		}
		k.spillRefused = true;
		return true;
	}

	/** Re-arms the setup programs: they run once after the set installs and after each resize (E6). */
	public void resized() {
		this.setupPending = true;
	}

	private void runPass(long enc, PassPlan plan, Targets targets, @Nullable Main main, PassBlocks blocks) {
		// E9: every target the pass writes is dead, and it has nothing else another program could see. A draw dump shows
		// the targets, so while one runs the pass runs and stores as before.
		boolean dumping = DrawLog.dumping();
		if (plan.skip && !dumping) {
			return;
		}
		CompiledProgram pass = plan.pass;
		runKernels(enc, plan, targets, blocks);  // E6: the kernels run before the pass's own stages
		if (!pass.runs()) {
			return;  // compute-only: nothing to draw
		}
		ResourceUse unbound = unboundStorage(pass.reflection().buffers());
		if (unbound != null) {
			skipUnboundStorage(pass.name(), unbound);  // the pass draws nothing; its kernels ran above
			return;
		}
		fillMips(enc, plan, targets);
		List<ColorTarget> outputs = pass.outputs();
		if (plan.targets.length == 0 || outputs.isEmpty()) {
			return;
		}
		for (int i = 0; i < plan.targets.length; i++) {
			int slot = outputs.get(i).attachment();
			plan.colors[slot] = plan.shadowOutputs ? this.shadow.colorWrite(plan.targets[i])
				: pass.finalPass() && i == 0 ? main.texture : targets.write(plan.targets[i]);
		}
		// The final pass draws the window: its viewport is the main colour's size, not colortex0's (a pack may size
		// the buffers apart). Other passes draw their first output's size.
		int width = plan.shadowOutputs ? this.shadow.resolution()
			: pass.finalPass() ? main.width : targets.width(plan.targets[0]);
		int height = plan.shadowOutputs ? this.shadow.resolution()
			: pass.finalPass() ? main.height : targets.height(plan.targets[0]);
		// ADR 0008: at a render scale the final draws into the render-size stand-in, which holds the last frame. A final that
		// may leave texels unwritten clears it to the game's clear colour (the window-size path writes the game's clear
		// first, compositeHook). The plan's own clear comes back after the pass opens, also if the open throws (render thread
		// only: the frame's plans are built and used there).
		int standInSlot = -1;
		float[] keptClear = null;
		if (pass.finalPass() && main != null && main.standInClear != null && !plan.whole) {
			standInSlot = outputs.get(0).attachment();
			keptClear = plan.clears[standInSlot];
			plan.clears[standInSlot] = main.standInClear;
		}
		try {
			MetalBridge.renderBegin(enc, plan.colors, plan.clears, 0, false, 0, width, height);
		} finally {
			if (standInSlot >= 0) {
				plan.clears[standInSlot] = keptClear;
			}
		}
		ShaderNative.renderLabel(enc, plan.label);
		if (!dumping) {
			for (int slot : plan.discards) {
				MetalBridge.discard(enc, plan.colors[slot]);  // E9: a dead target's texels are not stored
			}
		}
		MetalBridge.renderPipeline(enc, pass.pipeline(), 0, false, false, 0, 0, 3);
		ShaderProperties.Scale scale = plan.scale;
		if (scale != null) {
			applyScale(enc, scale, width, height);
		}
		long[] frameBlock = blocks.frameBlock(pass.name());
		if (frameBlock != null) {
			MetalBridge.renderBuffer(enc, ProgramRequests.FRAME_SLOT, frameBlock[0], frameBlock[1]);
		}
		long[] customBlock = blocks.customBlock();
		if (customBlock != null) {
			MetalBridge.renderBuffer(enc, ProgramRequests.CUSTOM_SLOT, customBlock[0], customBlock[1]);
		}
		long[] distantBlock = blocks.distantBlock();
		if (distantBlock != null) {
			MetalBridge.renderBuffer(enc, EngineSlots.DISTANT_HORIZONS, distantBlock[0], distantBlock[1]);
		}
		MetalBridge.renderBuffer(enc, ProgramRequests.DRAW_SLOT, this.passBytes[0], this.passBytes[1]);
		bindStorage(pass.reflection().buffers(), enc, false);
		List<ResourceUse> textures = pass.reflection().textures();
		for (int t = 0; t < textures.size(); t++) {
			long custom = this.customs == null || plan.customIds[t] < 0 ? 0L : this.customs.handle(plan.customIds[t]);
			MetalBridge.renderTexture(enc, textures.get(t).slot(), custom != 0 ? custom : sourceTexture(plan.sources[t], targets));
		}
		List<ResourceUse> images = pass.reflection().images();
		for (int i = 0; i < images.size(); i++) {
			long handle = imageTexture(plan.imageSources[i], targets);
			if (handle != 0L) {
				MetalBridge.renderTexture(enc, images.get(i).slot(), handle);
			}
		}
		MetalBridge.renderDraw(enc, 6, 1, 0, 0);  // the translated full-screen quad: two triangles (StageEmitter's corners)
		for (int i = 0; i < plan.targets.length; i++) {
			if (plan.shadowOutputs) {
				this.shadow.wroteColor(plan.targets[i], plan.flips[i]);
			} else if (plan.flips[i]) {
				targets.flip(plan.targets[i]);
			}
		}
	}

	/**
	 * {@code scale.<program>} (OptiFine shaders.properties; Iris's documented directive, shaders.properties/current/
	 * reference/shadersproperties/rendering, scale section): the pass draws a sub-rectangle of its buffers and the
	 * buffer keeps its size. Iris's wording: "Defines a new viewport for a composite style program. This does not affect
	 * the resolution of the output buffer, it only impacts the vertex position attribute, therefore only changing the
	 * portion of the texture which is rendered to." and, for the offsets, "the horizontal offset in each direction from
	 * 0.0 to 1.0". [inference] The offsets are read as fractions of the buffer's size (offset times the size, in
	 * pixels); the documents give the 0.0 to 1.0 range, not the formula.
	 */
	private static void applyScale(long enc, ShaderProperties.Scale scale, int width, int height) {
		double w = Math.max(1, Math.round(width * scale.scale()));
		double h = Math.max(1, Math.round(height * scale.scale()));
		ShaderNative.renderViewport(enc, Math.round(width * scale.offsetX()), Math.round(height * scale.offsetY()), w, h);
	}

	/**
	 * Whether a pass's write of a target flips it: {@code flip.<program>.<buffer>} false skips the swap for that one
	 * pass and buffer (engine-pack-format.md section 7; the pack names the buffer: colortex4, the alias gaux1); the
	 * documented default flips. Resolved once per pass when the frame is built.
	 */
	static boolean flipsTarget(Map<String, Boolean> flips, String program, int target) {
		for (String name : ProgramDirectives.targetNames(target)) {
			Boolean flip = flips.get(program + "." + name);
			if (flip != null) {
				return flip;
			}
		}
		return true;
	}

	/**
	 * The storage buffer of a bufferObject index the set holds, or 0 when it holds none (E6-B). A declared buffer can have no
	 * handle (see {@link #unboundStorage}).
	 */
	private long storageHandle(int index) {
		return this.storage == null ? 0L : this.storage.handle(index);
	}

	/**
	 * The first storage block of a reflection whose bufferObject has no buffer, or null when every storage block is bound
	 * (E6, finding 3). A declared bufferObject can have no buffer: its size is 0, above the device's largest buffer, scaled to 0
	 * by the view, or refused by a resize. The program with such a block is skipped; it never runs with an unbound slot.
	 */
	private @Nullable ResourceUse unboundStorage(List<ResourceUse> buffers) {
		for (int b = 0; b < buffers.size(); b++) {
			ResourceUse use = buffers.get(b);
			if (use.storage() && storageHandle(use.bufferObject()) == 0L) {
				return use;
			}
		}
		return null;
	}

	/** Logs a skipped program once per set (an ERROR: its outputs are not written this frame). */
	private void skipUnboundStorage(String program, ResourceUse use) {
		if (this.storageSkips.add(program)) {
			LOG.error("[mcms-shaders] {}: skipped: storage block {} binds bufferObject.{}, which has no buffer at this view size (size 0, above the device's largest buffer, or refused by a resize); the frame goes on without it", program, use.name(), use.bufferObject());
		}
	}

	/**
	 * Binds the storage buffers a reflection marks (E6-B), at their slots: the pack's bufferObject of that binding. The caller
	 * has checked that every storage block has a buffer ({@link #unboundStorage}).
	 */
	private void bindStorage(List<ResourceUse> buffers, long enc, boolean compute) {
		for (int b = 0; b < buffers.size(); b++) {
			ResourceUse use = buffers.get(b);
			if (!use.storage()) {
				continue;
			}
			long handle = storageHandle(use.bufferObject());  // the pack's bufferObject (BufferSlots), not the Metal slot
			if (compute) {
				MetalBridge.computeBuffer(enc, use.slot(), handle, 0);
			} else {
				MetalBridge.renderBuffer(enc, use.slot(), handle, 0);
			}
		}
	}

	/** The texture a sampled name reads, from the source the plan resolved (a target, a depth or noisetex). */
	private long sourceTexture(int source, Targets targets) {
		if (source >= ComputeKernel.IMAGE_CUSTOM) {
			return targets.customImages().texture(source - ComputeKernel.IMAGE_CUSTOM);
		}
		if (source == SOURCE_NONE) {
			return 0L;
		}
		if (source == SOURCE_DISTANT0 || source == SOURCE_DISTANT1) {
			// E7: checked before the shadow ranges, which run open-ended below SOURCE_SHADOW_COLOR (-40 is one of them).
			return targets.distantDepth(source == SOURCE_DISTANT0 ? 0 : 1);
		}
		if (source <= SOURCE_SHADOW_COLOR) {
			return this.shadow == null ? 0L : this.shadow.color(SOURCE_SHADOW_COLOR - source);
		}
		if (source <= SOURCE_SHADOW_DEPTH) {
			return this.shadow == null ? 0L : this.shadow.depth(SOURCE_SHADOW_DEPTH - source);
		}
		if (source >= 0) {
			return targets.read(source);
		}
		return switch (source) {
			case SOURCE_NOISE -> this.noise;
			case SOURCE_DEPTH1 -> targets.depth(1);
			case SOURCE_DEPTH2 -> targets.depth(2);
			default -> targets.depth(0);
		};
	}

	/** Refills the colour and shadow mip chains a pass reads, on each target whose content changed since its last fill. */
	private void fillMips(long enc, PassPlan plan, Targets targets) {
		fillMipTargets(enc, plan.mipTargets, targets);
		fillShadowMips(enc, plan);
	}

	/**
	 * Refills the mip chains of the targets' read textures that changed since their last fill (plan.md E6; the mip rule). A
	 * target no program uses holds no texture (Targets.create), so a read of it by a shadow program has no chain to fill, and
	 * generateMipmaps is never called with a zero texture (E6-fix-F).
	 */
	private static void fillMipTargets(long enc, int[] mipTargets, Targets targets) {
		for (int target : mipTargets) {
			if (targets.read(target) != 0L && targets.mipsStale(target)) {
				MetalBridge.generateMipmaps(enc, targets.read(target));
				targets.mipsFilled(target);
			}
		}
	}

	/**
	 * Refills the shadow mip chains this pass reads, on each map or colour buffer whose content changed since its last
	 * fill (E5; ShadowTargets.changed marks a clear and a shadow pass's writes). A chain the device refuses stays stale.
	 */
	private void fillShadowMips(long enc, PassPlan plan) {
		ShadowTargets shadow = this.shadow;
		if (shadow == null) {
			return;
		}
		for (int key : plan.shadowMips) {
			shadow.fillMip(enc, key);
		}
	}

	/**
	 * The set's shadowcomp passes (E5 review item 15): composite-style passes that run after the shadow pass and its mip
	 * fill, before the camera frame. Each writes shadow colour buffers through {@link #runPass}, which flips them as the
	 * pack's {@code flip} directives say; the next passes then read the writes. A set without shadow targets runs none.
	 */
	public void shadowComposite(long enc, Targets targets, PassBlocks blocks) {
		if (this.shadow == null) {
			return;
		}
		boolean any = false;
		for (PassPlan plan : this.shadowComps) {
			any |= plan.pass.active();
		}
		if (!any) {
			return;
		}
		// Every pass binds the pass block (gl_TextureMatrix[0], gl_NormalMatrix, the inverses). clearHook uploads it for the
		// camera's frame, which runs after the shadow frame; these passes run before that, so they upload it here (E5 review item 2).
		this.blocks.upload(this.passBlock, this.passBytes);
		for (PassPlan plan : this.shadowComps) {
			if (plan.pass.active()) {
				runPass(enc, plan, targets, null, blocks);
			}
		}
	}

	/** Whether a shadowcomp write of shadow colour buffer {@code colour} flips it ({@code flip.<program>.shadowcolor<n>}; default true). */
	static boolean flipsShadowColour(Map<String, Boolean> flips, String program, int colour) {
		Boolean flip = flips.get(program + ".shadowcolor" + colour);
		if (flip == null && colour == 0) {
			flip = flips.get(program + ".shadowcolor");
		}
		return flip == null || flip;
	}

}
