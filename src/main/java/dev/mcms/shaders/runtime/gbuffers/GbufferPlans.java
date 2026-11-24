// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.gbuffers;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PolygonMode;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import com.mojang.renderpearl.api.vertex.VertexFormatElement;
import com.mojang.renderpearl.frontend.FrontendRenderPipeline;
import dev.mcms.glsl.Translator;
import dev.mcms.shaders.runtime.shadow.ShadowTargets;
import dev.mcms.shaders.runtime.shadow.DefaultShadowProgram;
import dev.mcms.glsl.msl.ProgramRequest;
import dev.mcms.glsl.msl.ProgramTranslation;
import dev.mcms.metal.MetalBridge;
import dev.mcms.shaders.metal.ShaderNative;
import dev.mcms.shaders.pack.DimensionPrograms;
import dev.mcms.shaders.pack.PackModel;
import dev.mcms.shaders.pack.ProgramDirectives;
import dev.mcms.shaders.pack.ResolvedProgram;
import dev.mcms.shaders.pack.ShaderProperties;
import dev.mcms.shaders.runtime.DimensionSet;
import dev.mcms.shaders.runtime.EngineSlots;
import dev.mcms.shaders.runtime.ProgramRequests;
import dev.mcms.shaders.runtime.TargetFormats;
import dev.mcms.shaders.runtime.Targets;
import dev.mcms.shaders.runtime.compute.ComputeKernel;
import dev.mcms.shaders.runtime.uniforms.CustomUniforms;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.IntFunction;
import net.minecraft.client.renderer.ShaderDefines;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The per-(game pipeline, pass kind, phase) plans the delegate draws through (plan.md E4, the pipeline mapping bullet;
 * ADR 0006 section 3). When a set becomes active the engine lists the game's pipelines and {@link #build} resolves every
 * one of them, so no draw ever waits for a compile.
 *
 * <p>Resolution per (pass kind, phase, location id): {@link GbufferMapping}'s row names a fallback chain; the first
 * program of the chain that exists, runs, translates and builds a Metal pipeline wins, and its {@link PackVariant}
 * draws the game pipeline's geometry. A game pipeline no pack program serves gets a {@link GameVariant}: the game's own
 * pipeline when its colour format is colortex0's, else a copy of it that writes colortex0.
 *
 * <h2>Pipeline states</h2>
 * One pack program is built per (program, phase, vertex layout, topology) in two states: with a {@code Depth32Float}
 * depth attachment, and without one. The pass decides which one it draws with: {@link GbufferDelegate} takes
 * {@code hasDepth} from the pass's {@code begin}, because a see-through pass has no depth attachment at all.
 *
 * <h2>Depth conventions</h2>
 * A pass whose depth is the engine's ({@code Sky}, {@code Solid}, hand, shadow) uses the engine convention: the game's
 * depth compare and bias flipped ({@link GameDepth}), the frame's projection in OpenGL's convention, cleared to 1.0.
 * A pass that keeps its own depth ({@code Always on top features}, {@code Screen effects}, {@code 3d crosshair}) is
 * cleared to the game's 0.0 and uses the game's reversed convention: the game's own depth compare and bias, and the
 * projection's z row reversed, so a fragment's depth is {@code 1 - d} of the engine convention. Both bindings are built
 * for every pipeline ({@link PackVariant#depth}, {@link GameVariant#depth}); the delegate picks one per pass.
 *
 * <h2>Phase layouts</h2>
 * Each phase's colour layout is the union of the targets its programs write (every program of the phase's fallback
 * chains, so a fallback cannot write a target the layout lacks), {@code colortex0} pinned at attachment 0, the rest
 * ascending by target index. Metal allows {@link #MAX_ATTACHMENTS} colour attachments per pass, so a phase whose union is
 * larger splits into attachment groups ({@link PhaseLayout#groups}): each group's union fits, a pass opens on group 0, and
 * a draw whose program writes another group's targets switches the open pass onto that group's attachments (2026-11-15).
 * A program whose own targets exceed the limit fails the build with a message that names it (the set does not become
 * active). A game-pipeline variant is compiled once per group, as a copy with that group's attachments (the ones it does
 * not write masked): a draw with a one-attachment pipeline in a wider pass destroys the other attachments' contents.
 *
 * <h2>Lifetime and threads</h2>
 * {@link #build} runs on the loader thread and creates no game-pipeline copies: a {@link GameCopier} is called inside
 * it, so the caller passes a deferred one (it records the request and returns null). The copies are compiled once by
 * {@link #completeCopies} on the render thread, before any draw; a copy still pending draws nothing. Every pipeline
 * state and depth state the build created is released by {@link #close}. Copies belong to the game's pipeline cache
 * ({@link #renderThreadCopier}).
 */
public final class GbufferPlans implements AutoCloseable {

	/**
	 * The build's measurements, logged as one {@code [mcms-shaders]} INFO line per dimension set. Times are summed over
	 * the workers (CPU time); {@code wallNanos} is the build's elapsed time.
	 *
	 * @param packVariants (kind, phase, domain) cells that draw through a pack program
	 * @param gameVariants cells that draw through a game variant (the game's pipeline or its colortex0 copy)
	 * @param translations distinct translation requests (one translator run each; equal requests share it, E9)
	 * @param mslSources   distinct MSL source texts translated (one Metal library each)
	 * @param pipelines    distinct (program, phase, layout, topology) states built
	 * @param psos         Metal pipeline states created (two per state: with and without depth)
	 */
	public record Stats(int packVariants, int gameVariants, int translations, int mslSources, int pipelines, int psos,
			long translateNanos, long libraryNanos, long psoNanos, long wallNanos, int threads) {
	}

	/**
	 * How a build shares the machine: the pool's worker count and priority, and the check that ends the build early (a
	 * request a newer one replaced, or the engine turning off). The frame's own set builds with {@link Options#foreground};
	 * the other dimensions' sets build with {@link Options#background}, which takes fewer workers at minimum priority so
	 * the game keeps its cores. The worker count does the main work; the priority is a hint the operating system may ignore.
	 *
	 * @param threads   the pool's worker count (at least one)
	 * @param priority  the workers' Java thread priority
	 * @param cancelled read at each stop point (before each round, each translation and each Metal call); true ends the build
	 */
	public record Options(int threads, int priority, BooleanSupplier cancelled) {

		/** The frame's own set: all spare cores, normal priority. */
		public static Options foreground(BooleanSupplier cancelled) {
			return new Options(Math.max(1, Runtime.getRuntime().availableProcessors() - 2), Thread.NORM_PRIORITY, cancelled);
		}

		/** Another dimension's set: a quarter of the cores (at least one), minimum priority. */
		public static Options background(BooleanSupplier cancelled) {
			return new Options(Math.max(1, Runtime.getRuntime().availableProcessors() / 4), Thread.MIN_PRIORITY, cancelled);
		}
	}

	/**
	 * A build ended early at a stop point (a request a newer one replaced, or the engine turning off). Nothing it made is
	 * kept: the caller releases what it created, and nothing is installed.
	 */
	public static final class BuildCancelled extends RuntimeException {
		private static final long serialVersionUID = 1L;

		public BuildCancelled() {
			super("the plan build was cancelled by a newer request or the engine turning off", null, false, false);
		}
	}

	/**
	 * The loader's stop point: throws {@link BuildCancelled} when the check says the build must stop. Every Metal call of a
	 * background build is preceded by one (a library, a pipeline state, a compute kernel), so a stop waits for at most the
	 * call in flight, never for a whole round.
	 */
	public static void checkCancelled(BooleanSupplier cancelled) {
		if (cancelled.getAsBoolean()) {
			throw new BuildCancelled();
		}
	}

	/** Metal's per-pass colour attachment limit; a phase layout above this fails the build. */
	public static final int MAX_ATTACHMENTS = PhaseLayout.MAX_ATTACHMENTS;

	/**
	 * The alpha-test reference of a program without a configured {@code alphaTest.<program>} whose game pipeline cuts
	 * nothing: none (Iris and Vitrail draw solid terrain from alpha 0, results/2026-11-19-ril562-alpharef).
	 */
	private static final float NO_ALPHA_TEST_REF = 0f;

	/** The shader define that carries a game pipeline's own alpha cutout ({@code RenderPipelines}, Minecraft 26.3). */
	private static final String ALPHA_CUTOUT = "ALPHA_CUTOUT";

	/**
	 * The game's fragment shaders that cut a fixed alpha without {@link #ALPHA_CUTOUT} (Minecraft 26.3,
	 * {@code assets/minecraft/shaders/core}): particle, text, glint and crumbling discard below 0.1; position_color,
	 * position_tex, position_tex_color, rendertype_outline, world_border and gui discard alpha 0 only, which the default test
	 * (alpha at or above the reference) gets with the smallest normal float. Terrain, block, entity and item cut only under
	 * the define.
	 */
	private static final Map<String, Float> FIXED_CUTOUTS = Map.of(
		"core/particle", 0.1f, "core/text", 0.1f, "core/glint", 0.1f, "core/rendertype_crumbling", 0.1f,
		"core/position_color", Float.MIN_NORMAL, "core/position_tex", Float.MIN_NORMAL,
		"core/position_tex_color", Float.MIN_NORMAL, "core/rendertype_outline", Float.MIN_NORMAL,
		"core/world_border", Float.MIN_NORMAL, "core/gui", Float.MIN_NORMAL);

	/**
	 * The alpha-test reference of a gbuffers or shadow program without a configured {@code alphaTest.<program>} (RIL-562):
	 * the cut of the game pipeline's own shader, so a pack program cuts what the game's shader cuts there — its
	 * {@code ALPHA_CUTOUT} (0.5 for cutout terrain and blocks, 0.1 for translucent terrain, entities and items), else the
	 * shader's fixed cut ({@link #FIXED_CUTOUTS}: 0.1 for particles and text), else none (solid terrain among them).
	 * Minecraft 26.3 builds cutout mip levels for the 0.5 cut: {@code MipmapGenerator.scaleAlphaToCoverage} raises every
	 * texel of level 1 and up by the sprite's {@code alpha_cutoff_bias} plus 0.025, so kelp's transparent texels read 0.125
	 * there and a 0.1 cut drew kelp as whole quads where Iris and the game cut it out. The shadow pass samples the same
	 * atlas, minified, so its programs take the same reference. Measured (mcms-alpharef): Iris 1.11.7 cuts nothing on solid
	 * terrain and 0.1 on water and on the shadow pass's other geometry (water and entities); Vitrail on Metal cuts 0.5 on
	 * cutout terrain; Iris reports the cutout terrain as the solid stage, so its cutout reference shows only in real packs
	 * (kelp). Particles and text are not measured on Iris.
	 */
	static float defaultAlphaTestRef(RenderPipeline game) {
		Float cutout = gameCutout(game);
		if (cutout != null) {
			return cutout;
		}
		Identifier fragment = game.getShaders().get(ShaderType.FRAGMENT);
		Float fixed = fragment != null ? FIXED_CUTOUTS.get(fragment.getPath()) : null;
		return fixed != null ? fixed : NO_ALPHA_TEST_REF;
	}

	/** The game pipeline's own alpha cutout ({@code ALPHA_CUTOUT}), or null when it names none or one that is no number. */
	private static @Nullable Float gameCutout(RenderPipeline game) {
		String cutout = game.getShaderDefines().values().get(ALPHA_CUTOUT);
		if (cutout == null) {
			return null;
		}
		try {
			return Float.parseFloat(cutout);
		} catch (NumberFormatException e) {
			return null;
		}
	}

	/**
	 * The prefix of a gbuffers program's name: the mapping's chains name programs as the documentation does ({@code basic},
	 * {@code terrain_solid}), and the pack's programs are {@code gbuffers_basic} and so on (ProgramCatalog).
	 */
	private static final String PROGRAM_PREFIX = "gbuffers_";

	/**
	 * The pack program a documented chain name runs: {@code gbuffers_<name>} for the gbuffers chains, and the name itself for
	 * the Distant Horizons programs, which the documents name without a prefix ({@code dh_terrain}, {@code dh_water}; the shadow
	 * programs are looked up by name directly, {@link #firstShadow}).
	 */
	static String programName(String chainName) {
		return DistantPipelines.isProgram(chainName) ? chainName : PROGRAM_PREFIX + chainName;
	}

	/** The Sodium formats the engine plans Sodium's own terrain pipelines for (every registered format but Distant Horizons'). */
	private static List<VertexFormat> sodiumFormats() {
		return VertexLayoutRegistry.registeredFormats().stream().filter(format -> format != DistantPipelines.FORMAT).toList();
	}

	/**
	 * The game pipeline paths Sodium's terrain passes build from ({@code TerrainRenderPass.getPipeline} is
	 * {@code ChunkSectionLayer.pipeline(false)}, the non-multidraw ids; both allowed jars, javap 2026-11-14):
	 * {@code sodiumTerrainPipelines} mirrors one Sodium pipeline per path.
	 */
	private static final Set<String> SODIUM_TERRAIN_PATHS = Set.of("pipeline/solid_terrain", "pipeline/cutout_terrain",
		"pipeline/translucent_terrain");

	/** The prefix of Sodium's pipeline names (its terrain pipelines are named under this namespace). */
	private static final String SODIUM_PREFIX = "sodium:";

	/** Whether {@code name} is one of Sodium's terrain pipelines ({@code sodium:pipeline/solid_terrain} and its siblings). */
	static boolean isSodiumTerrain(String name) {
		return name.startsWith(SODIUM_PREFIX) && SODIUM_TERRAIN_PATHS.contains(name.substring(SODIUM_PREFIX.length()));
	}

	/** Logs each (location, kind, phase) with the variant it draws through and, for a game variant, why no pack program serves it. */
	private static final Logger LOG = LoggerFactory.getLogger("Quicksilver");
	/** A phase's layout asked for more attachments than Metal allows; the set this was built for must not activate. */
	public static final class LayoutException extends RuntimeException {
		LayoutException(String message) {
			super(message);
		}
	}

	/** One pipeline's per-(kind, phase, domain) variants: the delegate's {@code setPipeline} lookup. */
	public interface Plan {
		/**
		 * The variant this (kind, phase) draws through in the entity domain; null when this pipeline draws in none.
		 */
		default @Nullable Variant variant(PassKind kind, GbufferPhase phase) {
			return variant(kind, phase, GbufferMapping.Domain.ENTITY);
		}

		/** The variant this (kind, phase, domain) draws through; null when this pipeline draws in none (not the engine's). */
		@Nullable Variant variant(PassKind kind, GbufferPhase phase, GbufferMapping.Domain domain);

		/**
		 * The game-pipeline variant this (kind, phase, domain) draws through in one attachment group of the phase (2026-11-15: a
		 * game pipeline is compiled per group). The default is the cell's own variant when that is a game variant; null when the
		 * cell draws a pack program or none.
		 */
		default @Nullable GameVariant gameVariant(PassKind kind, GbufferPhase phase, GbufferMapping.Domain domain, int group) {
			return variant(kind, phase, domain) instanceof GameVariant game ? game : null;
		}

		/** The location id the mapping resolved (GbufferMapping.locationOf the pipeline's name). */
		String location();

		/** Why no pack program serves this (kind, phase) in the entity domain: the text the build logged. */
		default String reason(PassKind kind, GbufferPhase phase) {
			return reason(kind, phase, GbufferMapping.Domain.ENTITY);
		}

		/** Why no pack program serves this (kind, phase, domain): the text the build logged (empty when one does). */
		String reason(PassKind kind, GbufferPhase phase, GbufferMapping.Domain domain);
	}

	/** A bound pipeline state: a pack program or a game-pipeline variant. */
	public interface Variant {
		/** True for a translated pack program. */
		boolean pack();
	}

	/** The depth state, negated or not by convention, and the bias the draw binds with. */
	public record DepthBinding(long state, float biasConstant, float biasSlope, String description) {
		/** A binding whose description is not named (tests). */
		public DepthBinding(long state, float biasConstant, float biasSlope) {
			this(state, biasConstant, biasSlope, "unnamed");
		}
	}

	/** The pack-program half: everything {@link GbufferDelegate} binds per draw. */
	public interface PackVariant extends Variant {
		@Override
		default boolean pack() {
			return true;
		}

		/** The native pipeline state: with the depth attachment when {@code hasDepth}, else without one. */
		long pipeline(boolean hasDepth);

		/** The depth binding of a pass: {@code reversed} is the game's own convention ({@link GbufferPlans}, depth conventions). */
		DepthBinding depth(boolean reversed);

		/** The game pipeline's cull and wireframe flags. */
		boolean cull();

		boolean wireframe();

		/** The attachment group of the program's phase this variant draws in: the pass must be open on that group's attachments. */
		int group();

		/** The MTLPrimitiveType the draws bind with (MetalBridge.primitive of the game pipeline's topology). */
		int primitive();

		/** The pack program's name. */
		String program();

		/** The {@code renderStage} value of the mapping row (QsDraw serves it per draw). */
		int renderStage();

		/** The pack's alpha-test reference for the program (the documented default 0.1). */
		float alphaTestRef();

		/** The translation's reflection: the slots the program's blocks, samplers and images read. */
		ProgramTranslation.Reflection reflection();

		/** The program's texture bindings (its game samplers and the pack's own textures), with their slots. */
		List<ProgramRequest.TextureBinding> textures();

		/** The program's image bindings (storage textures). */
		List<ProgramRequest.ImageBinding> images();

		/** The program's uniform and buffer blocks, each at its slot (indices 0 to {@link ProgramRequest#MAX_BUFFER_BINDINGS} - 1). */
		List<ProgramRequest.BufferBinding> buffers();

		/** The buffer index E4-D's per-vertex id side buffer binds at, or -1 when the program reads none. */
		int perVertexIdSlot();

		/** The texture index the entity overlay ({@code Sampler1}) binds at, or -1 when the program reads no overlay. */
		int overlaySlot();

		/**
		 * The prologue facts of the layout the program's vertices use (Sodium's region-relative terrain: the region offset,
		 * the globals mirror and the model-view it serves); {@link PrologueFacts#NONE} for every other layout.
		 */
		default PrologueFacts prologue() {
			return PrologueFacts.NONE;
		}

		/** Whether the program is a clouds program: its vertices are pulled from the game's CloudFaces, so its bindings are the clouds' alone. */
		default boolean clouds() {
			return false;
		}
	}

	/**
	 * The game-pipeline half: the game's own pipeline (or its copy writing colortex0) drawn with the game's shaders. Its
	 * {@code Projection} block is served the engine's projection in Metal's zero-to-one depth range, per draw (plan.md E4,
	 * "Game pipelines without a pack program").
	 */
	public interface GameVariant extends Variant {
		@Override
		default boolean pack() {
			return false;
		}

		/** True when the game's own pipeline object is drawn (no copy writes colortex0 in another format). */
		boolean ownPipeline();

		/** The compiled copy to draw, or null while it is pending ({@link #completeCopies}); unused when {@link #ownPipeline}. */
		@Nullable Object copy();

		/** The depth binding of a pass: see {@link PackVariant#depth}. */
		DepthBinding depth(boolean reversed);

		boolean cull();

		boolean wireframe();

		/** The MTLPrimitiveType the draws bind with. */
		int primitive();
	}

	/**
	 * Compiles a copy of a game pipeline (the render-thread path is {@link #renderThreadCopier}); returns the backend
	 * pipeline object, or null when the copy is pending or refused. The copy's owner is the game's pipeline cache.
	 */
	public interface GameCopier {
		@Nullable Object compile(RenderPipeline copy);
	}

	/** The copy of a game pipeline that writes colortex0 in another format: its request and, once compiled, its object. */
	static final class CopyRef {
		final RenderPipeline request;
		@Nullable Object compiled;

		CopyRef(RenderPipeline request) {
			this.request = request;
		}
	}

	/**
	 * The result of one pipeline build: pso 0 when the program did not build (the chain goes on), with the reason it did not
	 * ({@code reason} is empty when it built).
	 */
	private record Built(long pso, long psoNoDepth, ProgramTranslation.Reflection reflection, List<ProgramRequest.TextureBinding> textures,
			List<ProgramRequest.ImageBinding> images, List<ProgramRequest.BufferBinding> buffers, int perVertexIdSlot, int overlaySlot,
			PrologueFacts prologue, String reason) {
		static Built failed(String reason) {
			return new Built(0, 0, null, List.of(), List.of(), List.of(), -1, -1, PrologueFacts.NONE, reason);
		}
	}

	/** The pack-program variant record. */
	record Pack(long pso, long psoNoDepth, DepthBinding engineDepth, DepthBinding reversedDepth, boolean cull, boolean wireframe,
			int primitive, String program, int renderStage, float alphaTestRef, ProgramTranslation.Reflection reflection,
			List<ProgramRequest.TextureBinding> textures, List<ProgramRequest.ImageBinding> images,
			List<ProgramRequest.BufferBinding> buffers, int perVertexIdSlot, int overlaySlot, PrologueFacts prologue,
			boolean clouds, int group)
			implements PackVariant {
		@Override
		public long pipeline(boolean hasDepth) {
			return hasDepth ? this.pso : this.psoNoDepth;
		}

		@Override
		public DepthBinding depth(boolean reversed) {
			return reversed ? this.reversedDepth : this.engineDepth;
		}

		@Override
		public PrologueFacts prologue() {
			return this.prologue;
		}

		@Override
		public boolean clouds() {
			return this.clouds;
		}
	}

	/** The game-pipeline variant record: {@code copyRef} is null when the game's own pipeline draws. */
	record Game(@Nullable CopyRef copyRef, DepthBinding engineDepth, DepthBinding reversedDepth, boolean cull, boolean wireframe,
			int primitive) implements GameVariant {
		@Override
		public boolean ownPipeline() {
			return this.copyRef == null;
		}

		@Override
		public @Nullable Object copy() {
			return this.copyRef == null ? null : backendOf(this.copyRef.compiled);
		}

		@Override
		public DepthBinding depth(boolean reversed) {
			return reversed ? this.reversedDepth : this.engineDepth;
		}
	}

	/** A pipeline's plan: its location and its variants by (kind, phase, domain). */
	private record PlanImpl(String location, Variant[][][] variants, String[][][] reasons,
			GameVariant[][][][] groupVariants) implements Plan {
		/** The phase slot a (kind, phase) is held in: the shadow kind has one layout, held in the OPAQUE slot. */
		private static int slot(PassKind kind, GbufferPhase phase) {
			return kind == PassKind.SHADOW ? GbufferPhase.OPAQUE.ordinal() : phase.ordinal();
		}

		@Override
		public @Nullable Variant variant(PassKind kind, GbufferPhase phase, GbufferMapping.Domain domain) {
			return this.variants[kind.ordinal()][slot(kind, phase)][domain.ordinal()];
		}

		/** Why no pack program serves this (kind, phase, domain): the text the build logged (empty when one does). */
		public String reason(PassKind kind, GbufferPhase phase, GbufferMapping.Domain domain) {
			return this.reasons[kind.ordinal()][slot(kind, phase)][domain.ordinal()];
		}

		/** The game variant of this (kind, phase, domain) in one attachment group of the phase; null when it has none there. */
		@Override
		public @Nullable GameVariant gameVariant(PassKind kind, GbufferPhase phase, GbufferMapping.Domain domain, int group) {
			GameVariant[] perGroup = this.groupVariants[kind.ordinal()][slot(kind, phase)][domain.ordinal()];
			return perGroup != null && group < perGroup.length ? perGroup[group] : null;
		}
	}
	/** Key of a pipeline state: the program, its phase, its vertex layout, its topology and the game pipeline's blend. */
	private record PipelineKey(String program, GbufferPhase phase, VertexFormat format, PrimitiveTopology topology,
			@Nullable BlendFunction blend) {
	}

	/** The game pipeline's blend function: its first colour target state's, null when that state does not blend. */
	static @Nullable BlendFunction gameBlend(RenderPipeline game) {
		List<ColorTargetState> states = game.getColorTargetStates();
		return states.isEmpty() ? null : states.get(0).blendFunction().orElse(null);
	}

	/**
	 * The blend of one pack buffer of a draw (E4 gbuffers and E5 shadow; the documents' Rendering page, {@code blend}).
	 * Directives win in this order: the program's {@code blend.<program>.<buffer>}, its {@code blend.<program>}, then the same
	 * two for the serving program (the program whose files run when the draw's program falls back; {@code blend.shadow}
	 * applies to a {@code shadow_*} program that serves through {@code shadow} and sets none). Without a directive the
	 * game pipeline's own blend applies ({@code game}: its colour target state's blend function). The docs' "any buffers
	 * that aren't specified will use the program's blending attributes" is that default: one GL blend state covers every
	 * draw buffer of a draw, so the game's blend is the same for every attachment. A directive, program-wide or per buffer,
	 * replaces it, and {@code off} is a directive that disables it. Null: no blend (the game pipeline does not blend).
	 *
	 * <p>The shadow pass passes no game blend ({@code firstShadow}): Iris 1.11.7 writes a translucent caster's shadow colours
	 * as they are, whatever the map holds behind them. Measured with Complementary Unbound's water (results/2026-11-20-
	 * ril574-probe4 and -probe5): on Iris the water's texels read the same with an opaque caster behind them and with none,
	 * while the engine's, mixed by the game's translucent blend with what lay behind, read 0.7 of Iris's shadowcolor0 red
	 * over the ground and the clear white over nothing. That white, where the caster cull drops the ground under the near
	 * water, lit the shore below it near white (RIL-574). [choice: measured on translucent terrain; the shadow pass's
	 * entities, block entities and every other shadow draw take the same rule unmeasured]
	 *
	 * @param buffers the buffer names a directive may name ({@link ProgramDirectives#targetNames} or {@code shadowcolor<n>})
	 * @param game    the game pipeline's blend function, or null when it does not blend or the draw is a shadow draw
	 */
	static ShaderProperties.@Nullable BlendState blendOf(Map<String, ShaderProperties.BlendState> blends, String program,
			String serving, List<String> buffers, @Nullable BlendFunction game) {
		for (String owner : program.equals(serving) ? List.of(program) : List.of(program, serving)) {
			for (String buffer : buffers) {
				ShaderProperties.BlendState state = blends.get(owner + "." + buffer);
				if (state != null) {
					return state;
				}
			}
			ShaderProperties.BlendState state = blends.get(owner);
			if (state != null) {
				return state;
			}
		}
		return game == null ? null : blendStateOf(game);
	}

	/** The game pipeline's blend as a directive-shaped state: its factors and equation ops by their renderpearl names. */
	static ShaderProperties.BlendState blendStateOf(BlendFunction game) {
		return new ShaderProperties.BlendState(false, game.color().sourceFactor().name(), game.color().destFactor().name(),
			game.alpha().sourceFactor().name(), game.alpha().destFactor().name(), game.color().op().name(),
			game.alpha().op().name());
	}

	/** A blend equation's word in the descriptor: MTLBlendOperation, numbered as {@code MetalConst} maps renderpearl's ops. */
	static int blendOp(String name) {
		return switch (name) {
			case "ADD" -> 0;
			case "SUBTRACT" -> 1;
			case "REVERSE_SUBTRACT" -> 2;
			case "MIN" -> 3;
			case "MAX" -> 4;
			default -> throw new IllegalArgumentException("unknown blend op " + name);
		};
	}

	/** The Metal context the build ran on (the delegate's clouds texel views; 0 in test-only plans). */
	private final long ctx;

	private final PhaseLayout[][] layouts;
	/** Per phase: every target its programs write, in any attachment group ({@link #targets(GbufferPhase)}). */
	private final int[][] targets;
	/** Per phase (by ordinal): the attachment group of each program the phase's chains name, by program name. */
	private final List<Map<String, Integer>> programGroups;
	private final int[][] feedback;
	/** Per phase: the targets its programs sample ({@link #readTargets}). */
	private final int[][] reads;
	/** Per phase: the targets its built programs' code samples ({@link #sampledTargets}). */
	private final int[][] sampled;
	/** Per phase: the targets its built programs can read at a level other than 0 ({@link #mipReadTargets}). */
	private final int[][] mipReads;
	/** The targets the built shadow programs can read at a level other than 0 ({@link #shadowMipReadTargets}). */
	private final int[] shadowMipReads;
	private final Map<Integer, String> colorFormats;
	private final Map<String, Map<String, Plan>> plans;
	private final List<Long> owned;
	private final List<CopyRef> copyRefs;
	private final boolean servesWorld;
	/** The shadow pass's attachment layout (E5) and the shadowcolor formats its attachments take. */
	private final PhaseLayout shadowLayout;
	private final Map<Integer, String> shadowFormats;
	/** The build's measurements; null for a test-only plans object. */
	private final @Nullable Stats stats;
	private boolean closed;

	private GbufferPlans(long ctx, PhaseLayout[][] layouts, int[][] targets, int[][] feedback, int[][] reads,
			int[][] sampled, int[][] mipReads, int[] shadowMipReads, Map<Integer, String> colorFormats,
			Map<String, Map<String, Plan>> plans, List<Long> owned, List<CopyRef> copyRefs, boolean servesWorld,
			PhaseLayout shadowLayout, Map<Integer, String> shadowFormats, @Nullable Stats stats,
			List<Map<String, Integer>> programGroups) {
		this.ctx = ctx;
		this.layouts = layouts;
		this.targets = targets;
		this.programGroups = programGroups;
		this.feedback = feedback;
		this.reads = reads;
		this.sampled = sampled;
		this.mipReads = mipReads;
		this.shadowMipReads = shadowMipReads;
		this.colorFormats = colorFormats;
		this.plans = plans;
		this.owned = owned;
		this.copyRefs = copyRefs;
		this.servesWorld = servesWorld;
		this.shadowLayout = shadowLayout;
		this.shadowFormats = shadowFormats;
		this.stats = stats;
	}

	/** The Metal context this was built on (the delegate makes the clouds' texel-buffer views on it). */
	long context() {
		return this.ctx;
	}

	/** The build's measurements (null for a test-only plans object). */
	public @Nullable Stats stats() {
		return this.stats;
	}

	/**
	 * The copier that compiles copies on the render thread: the game's pipeline cache compiles each one
	 * ({@code RenderSystem.getCompiledPipeline}). Call it only on the render thread; a refused compile returns null and is
	 * logged, once at each activation of the set ({@link #completeCopies} tries a pending copy again); its draws are dropped
	 * with the delegate's WARN.
	 */
	public static GameCopier renderThreadCopier() {
		return copy -> {
			try {
				return RenderSystem.getCompiledPipeline(copy);
			} catch (RuntimeException refused) {
				LOG.warn("Quicksilver: the copy {} of a game pipeline did not compile ({}); its draws are dropped",
					copy.getLocation(), refused.toString());
				return null;
			}
		};
	}

	/**
	 * The backend pipeline a copy draws with, or null once the game closed it. The game's pipeline cache returns its frontend
	 * record, which holds the backend object ({@code FrontendRenderPipeline.backendRenderPipeline}, javap of the 26.3 jar,
	 * 2026-11-15); the delegate binds the backend object, as for the game's own pipeline. Before this, the delegate got the
	 * frontend record, read no pipeline state from it, and dropped each copied pipeline's draws (mcms-targets' entities and
	 * clouds, the E6 draw log of 2026-11-15). The cache owns the copy: a resource reload's shader apply closes the old cache
	 * ({@code ShaderManager.apply}) before the engine releases its set at the reload's end, so a closed copy reads null and
	 * its draws are dropped until the set loads again, never drawn with a released pipeline state. Read at each bind: two
	 * field reads, no allocation.
	 */
	static @Nullable Object backendOf(@Nullable Object compiled) {
		if (compiled instanceof FrontendRenderPipeline frontend) {
			return frontend.isClosed() ? null : frontend.backendRenderPipeline();
		}
		return compiled;
	}

	/**
	 * Builds every plan of one dimension's set (plan.md E4: the game's pipelines are listed now, no first-use compiles).
	 * Runs off the render thread: pass a deferred {@code copier} that returns null, then call {@link #completeCopies}
	 * on the render thread before the set draws.
	 *
	 * @param ctx           the Metal device context (libraries and pipeline states only)
	 * @param model         the loaded pack
	 * @param dimension     the dimension's programs (the gbuffers programs resolve from)
	 * @param custom        the pack's custom uniforms (the schema the programs read)
	 * @param colorFormats  the union's format words by target index (colortex0's word is the game variants' format)
	 * @param gamePipelines the game's own pipelines (required and optional), listed by the engine
	 * @param copier        called once per copy of a game pipeline whose colour format is not colortex0's
	 * @throws LayoutException a phase layout is above {@link #MAX_ATTACHMENTS} (the message names phase and targets)
	 */
	public static GbufferPlans build(long ctx, PackModel model, DimensionPrograms dimension, CustomUniforms custom,
			Map<Integer, String> colorFormats, List<RenderPipeline> gamePipelines, GameCopier copier) {
		return build(ctx, model, dimension, custom, colorFormats, gamePipelines, copier, Options.foreground(() -> false));
	}

	/**
	 * The same build with the pool's shape and the cancel check given by {@code options} (the engine's background loads
	 * use fewer, lower-priority workers and a check that ends a build a newer request replaced).
	 *
	 * @throws BuildCancelled the options' cancel check ended the build (nothing it made is kept)
	 */
	public static GbufferPlans build(long ctx, PackModel model, DimensionPrograms dimension, CustomUniforms custom,
			Map<Integer, String> colorFormats, List<RenderPipeline> gamePipelines, GameCopier copier, Options options) {
		return build(ctx, model, dimension, custom, colorFormats, gamePipelines, copier, options, true);
	}

	/**
	 * The same build with every state compiled inside the plan pass, one at a time, as before the pre-build existed. The
	 * equivalence test compares {@link #build} with this: the plans must resolve to the same programs and states.
	 */
	public static GbufferPlans buildSequential(long ctx, PackModel model, DimensionPrograms dimension, CustomUniforms custom,
			Map<Integer, String> colorFormats, List<RenderPipeline> gamePipelines, GameCopier copier) {
		return build(ctx, model, dimension, custom, colorFormats, gamePipelines, copier, Options.foreground(() -> false),
			false);
	}

	/**
	 * The runnable programs of a dimension (E5): each program that runs, its shadow programs clamped to the shadow buffers the
	 * set holds (E5 review item 5), and the built-in shadow program when the pack has no shadow program at all (review item
	 * 17). A pack that lists a shadow program and switches it off has no built-in one (review item 1): the pack chose none.
	 */
	public static Map<String, ResolvedProgram> runnablePrograms(DimensionPrograms dimension, ShadowTargets shadowTargets) {
		Map<String, ResolvedProgram> runnable = new HashMap<>();
		for (ResolvedProgram program : dimension.programs()) {
			if (program.runs()) {
				runnable.put(program.name(), program.name().startsWith("shadow")
					? ShadowTargets.clampWrites(program, shadowTargets.colorCount()) : program);
			}
		}
		if (shadowTargets.used() && !runnable.containsKey(DefaultShadowProgram.NAME) && !shadowTargets.shadowProgramOff()) {
			runnable.put(DefaultShadowProgram.NAME, DefaultShadowProgram.create());
		}
		return runnable;
	}

	/**
	 * Whether the set plans Distant Horizons' pipelines (plan.md E7): the pack was loaded with the DISTANT_HORIZONS define (DH 3.3.4
	 * draws in this session, and the pack has a dh_ program: {@link dev.mcms.shaders.pack.PackModel#declaresDistantPrograms}), and the
	 * set has a runnable one. A set without them builds as it did before the DISTANT phase.
	 */
	static boolean plansDistant(dev.mcms.shaders.pack.StandardMacros macros, Map<String, ResolvedProgram> runnable) {
		return macros.isDefined("DISTANT_HORIZONS") && (runnable.containsKey("dh_terrain") || runnable.containsKey("dh_water"));
	}

	/**
	 * The shadow pass's layout (E5) over the runnable programs: the {@code shadowcolor} targets every shadow program of every
	 * shadow row writes, in ascending index order, with nothing pinned (see {@link PhaseLayout#ofShadow}).
	 */
	public static PhaseLayout shadowLayoutOf(Map<String, ResolvedProgram> runnable) {
		TreeSet<Integer> written = new TreeSet<>();
		for (String location : GbufferMapping.table().keySet()) {
			for (GbufferMapping.Domain domain : GbufferMapping.Domain.values()) {
				for (boolean blends : new boolean[] {false, true}) {
					GbufferMapping.row(PassKind.SHADOW, GbufferPhase.OPAQUE, location, domain, blends).ifPresent(row -> {
						for (String name : row.chain()) {
							ResolvedProgram program = runnable.get(name);
							if (program != null) {
								written.addAll(writesOf(program));
							}
						}
					});
				}
			}
		}
		return PhaseLayout.ofShadow(written);
	}

	private static GbufferPlans build(long ctx, PackModel model, DimensionPrograms dimension, CustomUniforms custom,
			Map<Integer, String> colorFormats, List<RenderPipeline> gamePipelines, GameCopier copier, Options options,
			boolean prebuild) {
		ShadowTargets shadowTargets = ShadowTargets.of(dimension);
		Map<String, ResolvedProgram> runnable = withinOneSize(runnablePrograms(dimension, shadowTargets),
			model.properties().bufferSizes());
		if (plansDistant(model.macros(), runnable)) {
			DistantPipelines.register();
		}
		Builder builder = new Builder(ctx, model, custom, colorFormats, dimension.union().mipmaps(),
			dimension.union().shadowFormats(), runnable, copier, shadowTargets, options.cancelled());
		try {
			for (GbufferPhase phase : GbufferPhase.values()) {
				builder.layout(phase);
			}
			builder.layoutShadow();
			if (prebuild) {
				builder.buildStates(gamePipelines, options);
			}
			for (RenderPipeline game : gamePipelines) {
				builder.plan(game);
			}
			// Sodium's own terrain pipelines are in no game list: the engine builds the same objects itself
			// (sodiumTerrainPipelines) for every registered format (E4-B's extended terrain) and plans them like a
			// game pipeline, under their sodium namespace identifiers.
			for (VertexFormat extended : sodiumFormats()) {
				for (RenderPipeline sodium : sodiumTerrainPipelines(gamePipelines, extended)) {
					builder.plan(sodium);
				}
			}
			// Distant Horizons' terrain pipelines (E7) are created by DH lazily, so the engine plans its own copies of them
			// (DistantPipelines); they draw only through the dh_ programs, in the DISTANT phase. A set without those programs
			// plans none, so its build is the same as before the phase.
			if (plansDistant(model.macros(), runnable)) {
				for (RenderPipeline distant : DistantPipelines.pipelines()) {
					builder.plan(distant);
				}
			}
			GbufferPlans built = builder.finish();
			LOG.info("[mcms-shaders] gbuffers plans {}: {} pack-program variants, {} game variants; {} translations, {} MSL"
				+ " sources, {} pipeline states, {} Metal pipelines; translate {} ms, Metal library {} ms, PSO {} ms (summed over"
				+ " {} workers); wall {} ms",
				dimension.label(), built.stats().packVariants(), built.stats().gameVariants(), built.stats().translations(),
				built.stats().mslSources(), built.stats().pipelines(), built.stats().psos(), ms(built.stats().translateNanos()),
				ms(built.stats().libraryNanos()), ms(built.stats().psoNanos()), built.stats().threads(),
				ms(built.stats().wallNanos()));
			String failures = builder.failureSummary();
			if (!failures.isEmpty()) {
				LOG.warn("[mcms-shaders] programs of {} that did not build for some pipelines (their chains went on): {}",
					dimension.label(), failures);
			}
			return built;
		} catch (RuntimeException e) {
			builder.abandon();
			throw e;
		}
	}

	private static String ms(long nanos) {
		return String.format(java.util.Locale.ROOT, "%.1f", nanos / 1_000_000.0);
	}

	/**
	 * Waits for every future of one wave, then throws the first failure in wave order (the later ones are added to it as
	 * suppressed). Every worker of the wave has finished when this returns or throws, so the caller may release what the
	 * workers used at once (E4 review item 2: a failed worker must not leave another one inside a released library).
	 */
	static <T> void joinAll(Collection<Future<T>> wave) {
		RuntimeException first = null;
		boolean interrupted = false;
		for (Future<T> future : wave) {
			while (true) {
				try {
					future.get();
					break;
				} catch (InterruptedException e) {
					interrupted = true;  // a worker still runs: keep waiting for it
				} catch (ExecutionException e) {
					RuntimeException failure = e.getCause() instanceof RuntimeException runtime ? runtime
						: new IllegalStateException("the plan build failed", e.getCause());
					if (first == null) {
						first = failure;
					} else {
						first.addSuppressed(failure);
					}
					break;
				}
			}
		}
		if (interrupted) {
			Thread.currentThread().interrupt();
		}
		if (first != null) {
			throw first;
		}
		if (interrupted) {
			throw new IllegalStateException("the plan build was interrupted");
		}
	}

	/**
	 * Compiles every copy this build left pending, on the render thread ({@link #renderThreadCopier} is the usual
	 * copier). Copies that already compiled stay as they are; a copy the copier refuses stays pending (it draws nothing).
	 */
	public void completeCopies(GameCopier renderCopier) {
		for (CopyRef copy : this.copyRefs) {
			if (copy.compiled == null) {
				copy.compiled = renderCopier.compile(copy.request);
			}
		}
	}

	/** The targets at each attachment of a phase's open layout, group 0 ({@code layout()[0] == 0}: colortex0 at attachment 0). */
	public int[] layout(GbufferPhase phase) {
		return layout(phase, 0);
	}

	/** The targets at each attachment of one attachment group of a phase ({@code [0] == 0}: colortex0 at attachment 0). */
	public int[] layout(GbufferPhase phase, int group) {
		return this.layouts[phase.ordinal()][group].targets().clone();
	}

	/** The number of attachment groups of a phase: 1, unless the phase's programs need the attachment split. */
	public int groups(GbufferPhase phase) {
		return this.layouts[phase.ordinal()].length;
	}

	/** Every target the phase's programs write, in any attachment group, ascending: what its copies and flips cover. */
	public int[] targets(GbufferPhase phase) {
		return this.targets[phase.ordinal()].clone();
	}

	/**
	 * The attachment group of each program the phase's fallback chains name, by program name (group 0 is the layout the
	 * phase's pass opens with; a phase that fits one layout names group 0 for all of them).
	 */
	public Map<String, Integer> programGroups(GbufferPhase phase) {
		return Map.copyOf(this.programGroups.get(phase.ordinal()));
	}

	/** The pixel format word of a phase's attachment (the union's format of its target). */
	public int attachmentFormat(GbufferPhase phase, int attachment) {
		int target = this.layouts[phase.ordinal()][0].targets()[attachment];
		return TargetFormats.of(this.colorFormats.get(target), target).format();
	}

	/**
	 * The format word a pipeline descriptor gives one attachment (E5 carry-over). A shadow target passes index -1, as its
	 * texture does (ShadowTargets, DimensionSet), so the colortex1 upgrade of RGBA to RGBA32F applies to colour targets only.
	 */
	static int descriptorFormat(Map<Integer, String> formats, int target, boolean shadow) {
		return TargetFormats.of(formats.get(target), shadow ? -1 : target).format();
	}

	/** The attachment index a target holds in a phase's layout; -1 when the layout has none. */
	public int attachmentOf(GbufferPhase phase, int target) {
		return this.layouts[phase.ordinal()][0].attachmentOf(target);
	}

	/**
	 * The targets at each attachment of the shadow pass's layout (E5): {@code shadowcolor} indices in ascending order, none
	 * pinned. Empty when no shadow program writes a colour (a depth-only shadow pass).
	 */
	public int[] shadowLayout() {
		return this.shadowLayout.targets().clone();
	}

	/** The pixel format word of the shadow pass's attachment (the union's shadow format of its shadowcolor target). */
	public int shadowAttachmentFormat(int attachment) {
		int target = this.shadowLayout.targets()[attachment];
		return TargetFormats.of(this.shadowFormats.get(target), -1).format();
	}

	/**
	 * The targets a phase's programs both write and read (engine-pack-format: the feedback rule; Complementary's
	 * gbuffers_water reads colortex6 while the translucent phase writes it). Before the phase's first draw the engine
	 * copies the read texture into the write texture, so the phase's writes start from the picture before the phase
	 * (the load copy of {@code EngineFrame}; the same copy the phases' load already makes for the translucent targets).
	 */
	public int[] feedbackTargets(GbufferPhase phase) {
		return this.feedback[phase.ordinal()].clone();
	}

	/**
	 * The targets a phase's programs sample (feedback targets included). The engine uses it for the sky-to-opaque
	 * hand-over: a target the sky wrote is copied into its read texture before the opaque phase, when the opaque
	 * programs sample it.
	 */
	public int[] readTargets(GbufferPhase phase) {
		return this.reads[phase.ordinal()].clone();
	}

	/**
	 * The targets a phase's built programs sample in their code (E9): each built pipeline's reflection, its textures and
	 * colour images (a used sampler is bound; a declared one the code never reaches is not). {@link #readTargets} counts every
	 * declaration, and a pack that declares every colortex in a shared include (Complementary's {@code uniforms.glsl})
	 * reads them all there. The phase boundaries' copies use this set: a copy is needed only where a program samples the
	 * target. Every pipeline the phase can use is built before the plans exist ({@link Builder#finish}); a shadow program
	 * is keyed to the opaque phase, so its reads join the opaque phase's (a copy more, never one less). A test-only plans
	 * object answers {@link #readTargets}.
	 */
	public int[] sampledTargets(GbufferPhase phase) {
		return this.sampled[phase.ordinal()].clone();
	}

	/**
	 * The targets a phase's built programs can read at a mip level other than 0 (E9; the reflection's
	 * {@code ResourceUse.levels}): the phase boundary refills only these chains, since an implicit lookup of a program that
	 * does not declare the target reads level 0 (E6). Every pipeline the phase can use is built before the plans exist
	 * ({@link Builder#finish}); a test-only plans object answers {@link #readTargets}.
	 */
	public int[] mipReadTargets(GbufferPhase phase) {
		return this.mipReads[phase.ordinal()].clone();
	}

	/**
	 * The targets the built shadow programs can read at a mip level other than 0 (E9): the frame head refills these chains
	 * before the shadow pass. A subset of the opaque phase's {@link #mipReadTargets}, which also holds the opaque programs'
	 * (refilled at the opaque phase's start). Empty for a test-only plans object.
	 */
	public int[] shadowMipReadTargets() {
		return this.shadowMipReads.clone();
	}

	/**
	 * Whether any redirected pipeline serves the world (one pack variant exists): the redirector takes the world passes
	 * over and {@code EngineFrame} stops copying the vanilla picture into colortex0.
	 */
	public boolean servesWorld() {
		return this.servesWorld;
	}

	/**
	 * Whether the set has a runnable {@code dh_terrain} program in the DISTANT phase: the engine then draws DH's opaque terrain through the
	 * pack (plan.md E7), and takes DH's own call over.
	 */
	public boolean servesDistant() {
		return !this.programGroups.get(GbufferPhase.DISTANT.ordinal()).isEmpty();
	}

	/**
	 * Whether the set has a runnable {@code dh_water} program in the TRANSLUCENT phase: DH's transparent terrain then draws through it
	 * (plan.md E7, decision 6).
	 */
	public boolean servesDistantWater() {
		return this.programGroups.get(GbufferPhase.TRANSLUCENT.ordinal()).containsKey("dh_water");
	}

	/**
	 * Every pack program variant these plans hold, each once: the (plan, kind, phase, domain) cells that draw through one.
	 * The sampler and binding checks of the corpus tests walk them.
	 */
	public List<PackVariant> packVariants() {
		Set<PackVariant> out = new java.util.LinkedHashSet<>();
		for (Map<String, Plan> byLayout : this.plans.values()) {
			for (Plan plan : byLayout.values()) {
				for (PassKind kind : PassKind.values()) {
					for (GbufferPhase phase : GbufferPhase.values()) {
						for (GbufferMapping.Domain domain : GbufferMapping.Domain.values()) {
							if (plan.variant(kind, phase, domain) instanceof PackVariant pack) {
								out.add(pack);
							}
						}
					}
				}
			}
		}
		return List.copyOf(out);
	}

	/**
	 * Whether any pack variant of these plans reads per-vertex ids (its program's {@code [[vertex_id]]} input). The
	 * delegate publishes the staged uploads' ids only then. Scans every (kind, phase, domain) of every plan once, at the
	 * delegate's construction.
	 */
	public boolean readsPerVertexIds() {
		for (Map<String, Plan> byLayout : this.plans.values()) {
			for (Plan plan : byLayout.values()) {
				for (PassKind kind : PassKind.values()) {
					for (GbufferPhase phase : GbufferPhase.values()) {
						for (GbufferMapping.Domain domain : GbufferMapping.Domain.values()) {
							if (plan.variant(kind, phase, domain) instanceof PackVariant pack && pack.perVertexIdSlot() >= 0) {
								return true;
							}
						}
					}
				}
			}
		}
		return false;
	}

	/**
	 * The plan of a game backend pipeline, by its name ({@code MetalBridge.pipelineName}: the location identifier,
	 * {@code minecraft:pipeline/solid_terrain} for the game's own, {@code sodium:pipeline/solid_terrain} for Sodium's
	 * terrain, which {@link #build} plans beside them) and its vertex layout: several game pipelines share one
	 * location (26.3's three {@code pipeline/item_cutout}s — the plain and glint ones on {@code ENTITY}, the
	 * glint-special one on {@code ENTITY_GLINT_SPECIAL}, RenderPipelines javap 2026-11-14), and a plan built for one
	 * layout misreads every other's staged vertices (the 44-byte glint-special plan over 36-byte item data drew
	 * nothing at all, log 2026-11-14-e4-preview7), so {@link #planOf(String, int[])} tells them apart by the vertex
	 * strides of the pipeline the pass set. This overload serves a name whose pipelines all share one layout, or the
	 * first one built when the caller cannot know (a test that selects by name alone). Null when the name is no
	 * pipeline this was built for.
	 */
	public @Nullable Plan planOf(String backendPipelineName) {
		Map<String, Plan> layouts = this.plans.get(backendPipelineName);
		if (layouts == null || layouts.isEmpty()) {
			return null;
		}
		for (Plan plan : layouts.values()) {
			return plan;
		}
		return null;
	}

	/**
	 * The plan of the pipeline the pass set: its name and the strides of its vertex bindings (the backend pipeline
	 * knows them; {@link #layoutKey} is the key they were built under). Null when no plan was built for that name and
	 * layout.
	 */
	public @Nullable Plan planOf(String backendPipelineName, int @Nullable [] bindingStrides) {
		Map<String, Plan> layouts = this.plans.get(backendPipelineName);
		if (layouts == null || layouts.isEmpty()) {
			return null;
		}
		Plan plan = layouts.get(layoutKey(bindingStrides));
		if (plan != null || bindingStrides != null) {
			return plan;
		}
		// Unknown strides (a test that selects by name alone) take the name's only layout. Known strides with no plan
		// are no plan: Sodium's compact 20-byte meshes on the install frame must not reach its 44-byte plan.
		return layouts.size() == 1 ? layouts.values().iterator().next() : null;
	}

	/**
	 * The key a pipeline's vertex layout builds and resolves under: its bindings' strides, ascending (a same-named
	 * pipeline set differs in them — {@code ENTITY} 36 against {@code ENTITY_GLINT_SPECIAL} 44 — and nothing else the
	 * backend can name). Null strides (a test that selects by name alone, a pipeline without bindings) key as empty.
	 */
	static String layoutKey(int @Nullable [] bindingStrides) {
		if (bindingStrides == null || bindingStrides.length == 0) {
			return "";
		}
		int[] sorted = bindingStrides.clone();
		java.util.Arrays.sort(sorted);
		StringBuilder key = new StringBuilder();
		for (int stride : sorted) {
			if (!key.isEmpty()) {
				key.append(',');
			}
			key.append(stride);
		}
		return key.toString();
	}

	/** Releases every pipeline state and depth state this built. Idempotent. */
	@Override
	public void close() {
		if (this.closed) {
			return;
		}
		this.closed = true;
		release(this.owned);
		this.plans.clear();
	}

	private static void release(List<Long> handles) {
		for (long handle : handles) {
			MetalBridge.release(handle);
		}
		handles.clear();
	}

	/**
	 * A plans object over the given plans alone (the delegate's tests bind plans without a build): one layout per
	 * name. Its layouts hold colortex0 only and it owns no handles.
	 */
	static GbufferPlans forTest(Map<String, Plan> plans) {
		Map<String, Map<String, Plan>> byLayout = new HashMap<>();
		for (Map.Entry<String, Plan> plan : plans.entrySet()) {
			byLayout.put(plan.getKey(), Map.of("", plan.getValue()));
		}
		return forTestLayouts(byLayout);
	}

	/**
	 * A plans object over plans keyed by name and then by layout key ({@link #layoutKey} of the strides the plan is built
	 * under; "" for none): the stride lookup's tests bind a plan per layout. Its layouts hold colortex0 only and it owns no
	 * handles.
	 */
	static GbufferPlans forTestLayouts(Map<String, Map<String, Plan>> plansByName) {
		PhaseLayout[][] layouts = new PhaseLayout[GbufferPhase.values().length][];
		for (GbufferPhase phase : GbufferPhase.values()) {
			layouts[phase.ordinal()] = new PhaseLayout[] {PhaseLayout.of(phase.name(), new TreeSet<>(Set.of(0)))};
		}
		return forTestGroups(layouts, plansByName);
	}

	/**
	 * A plans object over each phase's attachment groups (the phase split's tests give the groups; the targets are their
	 * union) and plans by name and layout key. It owns no handles, and its programs name no groups.
	 */
	static GbufferPlans forTestGroups(PhaseLayout[][] layouts, Map<String, Map<String, Plan>> plansByName) {
		int[][] targets = new int[layouts.length][];
		int[][] feedback = new int[layouts.length][];
		int[][] reads = new int[layouts.length][];
		List<Map<String, Integer>> programGroups = new ArrayList<>();
		for (GbufferPhase phase : GbufferPhase.values()) {
			TreeSet<Integer> all = new TreeSet<>();
			for (PhaseLayout layout : layouts[phase.ordinal()]) {
				for (int target : layout.targets()) {
					all.add(target);
				}
			}
			targets[phase.ordinal()] = all.stream().mapToInt(Integer::intValue).toArray();
			feedback[phase.ordinal()] = new int[0];
			reads[phase.ordinal()] = new int[0];
			programGroups.add(Map.of());
		}
		return new GbufferPlans(0, layouts, targets, feedback, reads, reads, reads, new int[0], Map.of(),
			new HashMap<>(plansByName),
			new ArrayList<>(), new ArrayList<>(), true, PhaseLayout.ofShadow(Set.of()), Map.of(), null, programGroups);
	}

	/**
	 * The programs (gbuffers, shadow and Distant Horizons ones) whose builds failed, each with how many of its builds failed
	 * and one reason, by program name; the reason is the smallest of the program's reasons, so the line is the same whatever
	 * order the concurrent builds finished in (E7, S1's runtime half: a program the pack serves must build).
	 *
	 * @param failed one (program, reason) pair per failed build
	 */
	static String summarizeFailures(List<Map.Entry<String, String>> failed) {
		Map<String, int[]> counts = new TreeMap<>();
		Map<String, String> reasons = new TreeMap<>();
		for (Map.Entry<String, String> build : failed) {
			counts.computeIfAbsent(build.getKey(), p -> new int[1])[0]++;
			reasons.merge(build.getKey(), build.getValue(), (a, b) -> a.compareTo(b) <= 0 ? a : b);
		}
		StringBuilder out = new StringBuilder();
		for (Map.Entry<String, int[]> entry : counts.entrySet()) {
			if (!out.isEmpty()) {
				out.append("; ");
			}
			out.append(entry.getKey()).append(" (").append(entry.getValue()[0]).append("): ").append(reasons.get(entry.getKey()));
		}
		return out.toString();
	}

	/** The build's state: the phase unions, the pipeline caches and the handles it owns until it is finished. */
	private static final class Builder {
		private final long ctx;
		private final PackModel model;
		private final CustomUniforms custom;
		private final Map<Integer, String> colorFormats;
		/** The dimension's mipped targets (TargetUnion.mipmaps): every program samples them with the mip filter. */
		private final Map<Integer, Boolean> mipmaps;
		private final ShadowTargets shadowTargets;
		private final Map<String, ResolvedProgram> runnable;
		private final GameCopier copier;
		private final PhaseLayout[][] layouts = new PhaseLayout[GbufferPhase.values().length][];
		private final int[][] targets = new int[GbufferPhase.values().length][];
		/** The attachment group of each (phase, program) a phase's programs split into ({@link #groupOf}). */
		private final Map<PhaseProgram, Integer> groupByProgram = new HashMap<>();
		private final int[][] feedback = new int[GbufferPhase.values().length][];
		private final int[][] reads = new int[GbufferPhase.values().length][];
		/** The pipeline states by (program, phase, format, topology). Written by pool workers, read by the plan pass. */
		private final Map<PipelineKey, Built> pipelines = new ConcurrentHashMap<>();
		/** The shadow programs' pipeline keys (keyed to the opaque phase, {@link #firstShadow}); written by pool workers. */
		private final Set<PipelineKey> shadowKeys = ConcurrentHashMap.newKeySet();
		/** The depth state per (compare, write) word: {@code compare * 2 + write}. Sequential pass only. */
		private final Map<Integer, Long> depthStates = new HashMap<>();
		/** The copy of each game pipeline that writes colortex0 in another format, by location and vertex layout. */
		private final Map<String, CopyRef> copies = new HashMap<>();
		/** The handles this build owns until it is finished (workers add to it). */
		private final List<Long> owned = Collections.synchronizedList(new ArrayList<>());
		/** One Metal library per distinct MSL source text (same text, one library); released when the build ends. */
		private final Map<String, Library> libraries = new ConcurrentHashMap<>();
		/** One Metal pipeline state per distinct (functions, descriptor): equal states are created once. */
		private final Map<PsoKey, Long> psos = new ConcurrentHashMap<>();
		private final Map<String, Map<String, Plan>> plans = new HashMap<>();
		/** The shadow pass's colour formats by shadowcolor index (the union's shadow formats), and its layout ({@link #layoutShadow}). */
		private final Map<Integer, String> shadowFormats;
		private PhaseLayout shadowLayout = PhaseLayout.ofShadow(Set.of());
		private boolean servesWorld;
		private final AtomicLong translateNanos = new AtomicLong();
		private final AtomicLong libraryNanos = new AtomicLong();
		private final AtomicLong psoNanos = new AtomicLong();
		/**
		 * One translation per distinct request (E9, load time): the build makes hundreds of pipeline variants of the same
		 * programs, and most of their requests are equal (Complementary's Overworld at the bench options: 964 pack-program
		 * variants and 51 MSL sources, with the translator run for every variant, 205.8 s of worker time;
		 * results/2026-11-17-e9-speed). A request is a record of records and lists, so equal requests have equal
		 * translations: the first worker to ask translates, and the others wait for its result.
		 */
		private final Map<ProgramRequest, CompletableFuture<ProgramTranslation>> translations = new ConcurrentHashMap<>();
		private final AtomicInteger packVariants = new AtomicInteger();
		private final AtomicInteger gameVariants = new AtomicInteger();
		private int threads = 1;
		private long startNanos = System.nanoTime();
		/** The build's stop check (the options' cancel check), read at each stop point before a translation or a Metal call. */
		private final BooleanSupplier cancelled;

		/** A compiled library, or Metal's text for a source that did not compile. */
		private record Library(long handle, String error) {
			boolean ok() {
				return this.handle != 0;
			}
		}

		/** A pipeline state's identity as Metal sees it: both functions and the whole descriptor. */
		private record PsoKey(long vertexLibrary, String vertexEntry, long fragmentLibrary, String fragmentEntry,
				List<Integer> descriptor) {
		}

		Builder(long ctx, PackModel model, CustomUniforms custom, Map<Integer, String> colorFormats,
				Map<Integer, Boolean> mipmaps, Map<Integer, String> shadowFormats, Map<String, ResolvedProgram> runnable,
				GameCopier copier, ShadowTargets shadowTargets, BooleanSupplier cancelled) {
			this.mipmaps = mipmaps;
			this.ctx = ctx;
			this.model = model;
			this.custom = custom;
			this.colorFormats = colorFormats;
			this.shadowFormats = shadowFormats;
			this.runnable = runnable;
			this.copier = copier;
			this.shadowTargets = shadowTargets;
			this.cancelled = cancelled;
		}

		/**
		 * Computes the phase's attachment groups (ADR 0006 section 3: one group when the union fits), its target union and its
		 * feedback set from every program its fallback chains name.
		 */
		void layout(GbufferPhase phase) {
			Map<String, Set<Integer>> writes = new TreeMap<>();
			TreeSet<Integer> written = new TreeSet<>();
			TreeSet<Integer> read = new TreeSet<>();
			for (List<String> chain : chainsOf(phase)) {
				for (String name : chain) {
					ResolvedProgram program = this.runnable.get(programName(name));
					if (program != null) {
						writes.put(program.name(), writesOf(program));
						written.addAll(writesOf(program));
						read.addAll(readsOf(program));
					}
				}
			}
			PhaseLayout.Groups groups = PhaseLayout.groups(phase.name(), writes);
			this.layouts[phase.ordinal()] = groups.layouts().toArray(PhaseLayout[]::new);
			TreeSet<Integer> all = new TreeSet<>();
			for (PhaseLayout layout : this.layouts[phase.ordinal()]) {
				for (int target : layout.targets()) {
					all.add(target);
				}
			}
			this.targets[phase.ordinal()] = all.stream().mapToInt(Integer::intValue).toArray();
			groups.groupOf().forEach((program, group) -> this.groupByProgram.put(new PhaseProgram(phase, program), group));
			this.feedback[phase.ordinal()] = PhaseLayout.feedback(read, written).stream().mapToInt(Integer::intValue).toArray();
			this.reads[phase.ordinal()] = read.stream().mapToInt(Integer::intValue).toArray();
		}

		/** A program of a phase: the key of its attachment group. */
		private record PhaseProgram(GbufferPhase phase, String program) {
		}

		/** The attachment group of a program in a phase; the fallback chains name every such program. */
		private int groupOf(GbufferPhase phase, String program) {
			Integer group = this.groupByProgram.get(new PhaseProgram(phase, program));
			if (group == null) {
				throw new IllegalStateException("program " + program + " is in no attachment group of phase " + phase);
			}
			return group;
		}

		/** The attachment layout of a program's group in a phase: the attachments its pipeline states describe. */
		private PhaseLayout layoutOf(GbufferPhase phase, ResolvedProgram program) {
			return this.layouts[phase.ordinal()][groupOf(phase, program.name())];
		}

		/**
		 * The shadow pass's layout (E5): the {@code shadowcolor} targets every shadow program of every shadow row writes, in
		 * ascending index order, with nothing pinned (see {@link PhaseLayout#ofShadow}).
		 */
		void layoutShadow() {
			this.shadowLayout = shadowLayoutOf(this.runnable);
		}

		/**
		 * The first shadow program of the row's chain that builds for this pipeline, with the reasons the others did not
		 * (E5). The shadow programs are named as the documents name them ({@code shadow_solid}), never {@code gbuffers_}.
		 */
		private Found firstShadow(GbufferMapping.Row row, RenderPipeline game, VertexFormat format, PrimitiveTopology topology) {
			StringBuilder reasons = new StringBuilder();
			for (String name : row.chain()) {
				ResolvedProgram program = this.runnable.get(name);
				if (program == null) {
					reasons.append(name).append(": not runnable; ");
					continue;
				}
				// The shadow pass blends only by directive (blendOf): its pipelines take no game blend.
				PipelineKey key = new PipelineKey(program.name(), GbufferPhase.OPAQUE, format, topology, null);
				this.shadowKeys.add(key);
				Built built = this.pipelines.get(key);
				if (built == null) {
					built = buildShadow(program, format, topology);
					this.pipelines.put(key, built);
				}
				if (built.pso() != 0) {
					return new Found(shadowPack(built, program, GbufferMapping.renderStage(row.renderStage()), game), "");
				}
				reasons.append(name).append(": ").append(built.reason()).append("; ");
			}
			return new Found(null, reasons.isEmpty() ? "the row names no program" : reasons.toString());
		}

		/**
		 * Every fallback chain the phase's geometry can resolve to (the hand's chains in both phases): every domain's
		 * and blend state's row of every location that draws in the phase, so a phase layout cannot miss a target a
		 * routed chain writes (the routing rules re-route rows per domain and blend).
		 */
		private static List<List<String>> chainsOf(GbufferPhase phase) {
			List<List<String>> chains = new ArrayList<>();
			for (String location : GbufferMapping.table().keySet()) {
				if (contains(GbufferMapping.phases(PassKind.GBUFFERS, location), phase)) {
					for (GbufferMapping.Domain domain : GbufferMapping.Domain.values()) {
						for (boolean blends : new boolean[] {false, true}) {
							GbufferMapping.row(PassKind.GBUFFERS, phase, location, domain, blends)
								.ifPresent(row -> chains.add(row.chain()));
						}
					}
				}
			}
			GbufferMapping.row(PassKind.HAND, phase, "").ifPresent(row -> chains.add(row.chain()));
			return chains;
		}

		/** One (kind, phase, domain) cell of a pipeline a draw can reach: its row's chain and how far the walk got. */
		private static final class Cell {
			final boolean clouds;
			final @Nullable VertexFormat format;
			final PrimitiveTopology topology;
			final @Nullable BlendFunction blend;
			final GbufferPhase phase;
			final List<String> chain;
			int next;
			boolean resolved;

			Cell(boolean clouds, @Nullable VertexFormat format, PrimitiveTopology topology, GbufferPhase phase,
					List<String> chain, @Nullable BlendFunction blend) {
				this.clouds = clouds;
				this.format = format;
				this.topology = topology;
				this.phase = phase;
				this.chain = chain;
				this.blend = blend;
			}
		}

		/**
		 * Compiles, on a pool of {@code options.threads()} workers, the pipeline states the plan pass will resolve. It walks
		 * each cell's fallback chain as {@link #firstPack} and {@link #firstClouds} do: a round submits the next unbuilt
		 * program of every unresolved cell and joins the whole round; a cell whose program failed asks for its next program
		 * in the next round. The states built and every cell's winner are therefore those of the sequential walk; only the
		 * compiles overlap. Cells with no vertex format that are not clouds draw through a game variant and build nothing.
		 * The options' cancel check is read before each round and at every stop point of a state (before its translation and
		 * before each Metal call): a cancelled build throws {@link BuildCancelled} and the caller releases what it made. A round
		 * that fails throws only after every worker of the round has finished.
		 */
		void buildStates(List<RenderPipeline> gamePipelines, Options options) {
			List<Cell> cells = new ArrayList<>();
			List<RenderPipeline> all = new ArrayList<>(gamePipelines);
			for (VertexFormat extended : sodiumFormats()) {
				all.addAll(sodiumTerrainPipelines(gamePipelines, extended));
			}
			if (plansDistant(this.model.macros(), this.runnable)) {
				all.addAll(DistantPipelines.pipelines());
			}
			for (RenderPipeline game : all) {
				String location = GbufferMapping.locationOf(game.getLocation().getPath());
				VertexFormat format = game.getVertexFormatBindings().isEmpty() ? null : game.getVertexFormatBinding(0);
				boolean clouds = format == null && isClouds(game);
				if (format == null && !clouds) {
					continue;
				}
				boolean blends = blends(game);
				PrimitiveTopology topology = game.getPrimitiveTopology();
				for (PassKind kind : new PassKind[] {PassKind.GBUFFERS, PassKind.HAND}) {
					for (GbufferPhase phase : GbufferPhase.values()) {
						if (!contains(GbufferMapping.phases(kind, location), phase)) {
							continue;
						}
						for (GbufferMapping.Domain domain : GbufferMapping.Domain.values()) {
							Optional<GbufferMapping.Row> row = GbufferMapping.row(kind, phase, location, domain, blends);
							if (row.isPresent()) {
								cells.add(new Cell(clouds, format, topology, phase, row.get().chain(), gameBlend(game)));
							}
						}
					}
				}
			}
			if (cells.isEmpty()) {
				return;
			}
			this.threads = options.threads();
			ExecutorService pool = Executors.newFixedThreadPool(this.threads, runnable -> {
				Thread thread = new Thread(runnable, "mcms-shaders-plan-build");
				thread.setDaemon(true);
				thread.setPriority(options.priority());
				return thread;
			});
			try {
				while (true) {
					if (options.cancelled().getAsBoolean()) {
						throw new BuildCancelled();
					}
					Map<PipelineKey, Future<Built>> wave = new HashMap<>();
					for (Cell cell : cells) {
						while (!cell.resolved && cell.next < cell.chain.size()) {
							ResolvedProgram program = this.runnable.get(programName(cell.chain.get(cell.next)));
							if (program == null) {
								cell.next++;  // not runnable: the chain goes on, as firstPack does
								continue;
							}
							PipelineKey key = new PipelineKey(program.name(), cell.phase, cell.format, cell.topology, cell.blend);
							Built built = this.pipelines.get(key);
							if (built == null) {
								if (!wave.containsKey(key)) {
									wave.put(key, pool.submit(() -> buildState(program, cell)));
								}
								break;  // this cell waits for the wave to join
							}
							if (built.pso() != 0) {
								cell.resolved = true;  // the first program that built wins
							} else {
								cell.next++;  // a failed program: the chain goes on
							}
						}
					}
					if (wave.isEmpty()) {
						return;
					}
					joinAll(wave.values());
				}
			} finally {
				pool.shutdown();  // every round has joined: no worker is left running
			}
		}

		/** One pipeline state's build (a pool worker's body); the result is cached under its key. */
		private Built buildState(ResolvedProgram program, Cell cell) {
			checkCancelled(this.cancelled);  // a stop before any translation or compile of this state
			Built built = cell.clouds ? buildClouds(program, cell.phase, cell.topology, cell.blend)
				: build(program, cell.phase, cell.format, cell.topology, cell.blend);
			this.pipelines.put(new PipelineKey(program.name(), cell.phase, cell.format, cell.topology, cell.blend), built);
			return built;
		}

		/**
		 * Builds the variants of one game pipeline for every (kind, phase, domain) its location draws in. The domain
		 * and the pipeline's blend state pick the row (the E4 routing rules, GbufferMapping); the pipeline states stay
		 * shared — one per (program, phase, layout, topology) — so a routed row adds states only when it resolves a
		 * different program.
		 */
		void plan(RenderPipeline game) {
			String name = game.getLocation().toString();  // the full identifier: the plan's key (planOf)
			String location = GbufferMapping.locationOf(game.getLocation().getPath());
			VertexFormat format = game.getVertexFormatBindings().isEmpty() ? null : game.getVertexFormatBinding(0);
			PrimitiveTopology topology = game.getPrimitiveTopology();
			boolean blends = blends(game);
			Variant[][][] variants = new Variant[PassKind.values().length][GbufferPhase.values().length][GbufferMapping.Domain.values().length];
			GameVariant[][][][] groupVariants = new GameVariant[PassKind.values().length][GbufferPhase.values().length][GbufferMapping.Domain.values().length][];
			String[][][] reasons = new String[PassKind.values().length][GbufferPhase.values().length][GbufferMapping.Domain.values().length];
			for (PassKind kind : new PassKind[] {PassKind.GBUFFERS, PassKind.HAND}) {
				for (GbufferPhase phase : GbufferPhase.values()) {
					if (!contains(GbufferMapping.phases(kind, location), phase)) {
						continue;
					}
					for (GbufferMapping.Domain domain : GbufferMapping.Domain.values()) {
						Optional<GbufferMapping.Row> row = GbufferMapping.row(kind, phase, location, domain, blends);
						// A location the table does not name (the screen effects, item_cutout, ...) has no pack program
						// chain, but its geometry still draws in the phases above: a game variant must be built for every
						// one of them, or the draw drops (minecraft:pipeline/item_cutout did, log 2026-11-14-e4-preview2).
						Found found = row.isEmpty() ? new Found(null, "the location names no pack program")
							: format == null && isClouds(game) ? firstClouds(row.get(), phase, topology, game)
							: format == null ? new Found(null, "the game pipeline has no vertex format")
							: firstPack(row.get(), phase, game, format, topology);
						Variant variant = found.variant();
						String why = found.reason();
						if (variant == null) {
							// A game pipeline is compiled for every attachment group of the phase: the group the pass has open draws it.
							GameVariant[] perGroup = new GameVariant[this.layouts[phase.ordinal()].length];
							for (int group = 0; group < perGroup.length; group++) {
								perGroup[group] = gameVariant(game, phase, group);
							}
							groupVariants[kind.ordinal()][phase.ordinal()][domain.ordinal()] = perGroup;
							variant = perGroup[0];
							why = why + (variant == null ? " and no game variant either" : " (game variant)");
						}
						variants[kind.ordinal()][phase.ordinal()][domain.ordinal()] = variant;
						if (variant instanceof PackVariant) {
							this.packVariants.incrementAndGet();
						} else if (variant != null) {
							this.gameVariants.incrementAndGet();
						}
						reasons[kind.ordinal()][phase.ordinal()][domain.ordinal()] = variant instanceof PackVariant ? "" : why;
						if (kind == PassKind.GBUFFERS && domain == GbufferMapping.Domain.ENTITY) {
							LOG.info("Quicksilver: {} {} {} draws through {}{}", name, kind, phase,
								variant instanceof PackVariant pv ? "pack program " + pv.program() : "a game variant",
								found.variant() == null ? ": " + why : "");
						} else if (kind == PassKind.GBUFFERS && domain == GbufferMapping.Domain.BLOCK_ENTITY
								&& variant instanceof PackVariant pv) {
							LOG.info("Quicksilver: {} {} {} block entities draw through pack program {}", name, kind, phase,
								pv.program());
						}
					}
				}
			}
			// E5: the shadow pass's variants. The shadow kind has one layout, so its variants sit in the OPAQUE slot of the
			// kind's row (PlanImpl.variant ignores the phase for this kind). A location with no shadow row, or no shadow
			// program that builds, draws nothing in the shadow pass: the game's own shader never draws into the shadow maps.
			for (GbufferMapping.Domain domain : GbufferMapping.Domain.values()) {
				Optional<GbufferMapping.Row> shadowRow = GbufferMapping.row(PassKind.SHADOW, GbufferPhase.OPAQUE, location,
					domain, blends);
				Found found = shadowRow.isEmpty() ? new Found(null, "the location names no shadow program")
					: format == null ? new Found(null, "the game pipeline has no vertex format")
					: firstShadow(shadowRow.get(), game, format, topology);
				Variant variant = found.variant();
				variants[PassKind.SHADOW.ordinal()][GbufferPhase.OPAQUE.ordinal()][domain.ordinal()] = variant;
				reasons[PassKind.SHADOW.ordinal()][GbufferPhase.OPAQUE.ordinal()][domain.ordinal()] =
					variant instanceof PackVariant ? "" : found.reason();
				if (variant instanceof PackVariant) {
					this.packVariants.incrementAndGet();
				}
			}
			// Same-named pipelines (the three item_cutouts) each keep their own plan under their vertex strides: a
			// plan of one layout must never serve another's staged vertices (strideWords: a null binding adds none).
			this.plans.computeIfAbsent(name, key -> new HashMap<>())
				.put(layoutKey(strideWords(game)), new PlanImpl(location, variants, reasons, groupVariants));
			// A world variant of either kind serves the world: a game variant draws the world's own shader, which still
			// takes the world passes over (the engine installs the takeover on this). HAND-only variants do not count.
			for (Variant[] phaseVariants : variants[PassKind.GBUFFERS.ordinal()]) {
				for (Variant variant : phaseVariants) {
					if (variant != null && !DistantPipelines.isPipeline(name)) {
						this.servesWorld = true;
					}
				}
			}
		}
		/**
		 * The strides of a pipeline's vertex bindings, in binding order; a binding renderpearl leaves null (the clouds
		 * pipelines' list) contributes none. The layout key of a plan and of a game copy is built from these.
		 */
		private static int[] strideWords(RenderPipeline game) {
			int[] strides = new int[game.getVertexFormatBindings().size()];
			int count = 0;
			for (VertexFormat binding : game.getVertexFormatBindings()) {
				if (binding != null) {
					strides[count++] = binding.getVertexSize();
				}
			}
			return java.util.Arrays.copyOf(strides, count);
		}

		/**
		 * Whether the game pipeline blends: any of its colour target states carries a blend function (rule 1's fact;
		 * 26.3 RenderPipelines javap 2026-11-14: entity_shadow and the text pipelines carry
		 * {@code BlendFunction.TRANSLUCENT}, the cutout and solid ones {@code ColorTargetState.DEFAULT}).
		 */
		private static boolean blends(RenderPipeline game) {
			for (ColorTargetState state : game.getColorTargetStates()) {
				if (state.blendFunction().isPresent()) {
					return true;
				}
			}
			return false;
		}

		/** The outcome of the search for a pack program: the variant (null when none builds) and why not, per chain program. */
		private record Found(@Nullable Variant variant, String reason) {
		}

		/** The first program of the row's chain that builds for this pipeline, with the reasons the others did not. */
		private Found firstPack(GbufferMapping.Row row, GbufferPhase phase, RenderPipeline game, VertexFormat format,
				PrimitiveTopology topology) {
			StringBuilder reasons = new StringBuilder();
			for (String name : row.chain()) {
				ResolvedProgram program = this.runnable.get(programName(name));
				if (program == null) {
					reasons.append(name).append(": not runnable; ");
					continue;
				}
				Built built = pipeline(program, phase, format, topology, gameBlend(game));
				if (built.pso() != 0) {
					int group = groupOf(phase, program.name());
					return new Found(pack(built, program, group, GbufferMapping.renderStage(row.renderStage()), game), "");
				}
				reasons.append(name).append(": ").append(built.reason()).append("; ");
			}
			return new Found(null, reasons.isEmpty() ? "the row names no program" : reasons.toString());
		}

		/** Whether the pipeline is a clouds pipeline: no vertex format, and a bind group names the CloudFaces texel buffer. */
		private static boolean isClouds(RenderPipeline game) {
			for (BindGroupLayout group : game.getBindGroupLayouts()) {
				for (BindGroupLayout.UniformDescription uniform : group.uniforms()) {
					if (uniform.name().equals("CloudFaces")) {
						return true;
					}
				}
			}
			return false;
		}

		/**
		 * The first program of the row's chain that builds for a clouds pipeline (plan.md E4): no vertex format — the
		 * request carries {@link CloudsLayout#LAYOUT} and {@link CloudsLayout#PROLOGUE}, and the translator's prologue
		 * pulls the geometry from the CloudFaces texel buffer (the delegate binds it and the CloudInfo block).
		 */
		private Found firstClouds(GbufferMapping.Row row, GbufferPhase phase, PrimitiveTopology topology, RenderPipeline game) {
			StringBuilder reasons = new StringBuilder();
			for (String name : row.chain()) {
				ResolvedProgram program = this.runnable.get(programName(name));
				if (program == null) {
					reasons.append(name).append(": not runnable; ");
					continue;
				}
				Built built = cloudsPipeline(program, phase, topology, gameBlend(game));
				if (built.pso() != 0) {
					int group = groupOf(phase, program.name());
					return new Found(pack(built, program, group, GbufferMapping.renderStage(row.renderStage()), game), "");
				}
				reasons.append(name).append(": ").append(built.reason()).append("; ");
			}
			return new Found(null, reasons.isEmpty() ? "the row names no program" : reasons.toString());
		}

		/** The pipeline states of a clouds program, cached like the format-keyed ones (key format null: the clouds). */
		private Built cloudsPipeline(ResolvedProgram program, GbufferPhase phase, PrimitiveTopology topology,
				@Nullable BlendFunction blend) {
			PipelineKey key = new PipelineKey(program.name(), phase, null, topology, blend);
			Built built = this.pipelines.get(key);
			if (built == null) {
				built = buildClouds(program, phase, topology, blend);
				this.pipelines.put(key, built);
			}
			return built;
		}

		private Built buildClouds(ResolvedProgram program, GbufferPhase phase, PrimitiveTopology topology,
				@Nullable BlendFunction blend) {
			PhaseLayout attachments = layoutOf(phase, program);
			ProgramRequest request = ProgramRequests.gbuffers(program, this.model, this.custom, this.colorFormats,
				this.mipmaps, CloudsLayout.LAYOUT, attachments::attachmentOf, topology == PrimitiveTopology.POINTS,
				CloudsLayout.PROLOGUE, this.shadowTargets);
			return request == null ? Built.failed("the program has no gbuffers request (no vertex or fragment stage)")
				: compile(request, program, attachments, this.colorFormats, false, vertexWords(0, List.of()), topology,
					blend, PrologueFacts.NONE);
		}

		/**
		 * The variant of a game pipeline no pack program serves, in one attachment group of a phase (2026-11-15). A game pipeline
		 * draws in whatever attachments its pass has, so the variant is a copy of the pipeline with the group's targets: attachment
		 * 0 takes the game's own target (colortex0's format), every other attachment its group's format with no write, so the pass
		 * keeps its contents there. The game's own pipeline draws only where the group is colortex0 alone in its format. Null when
		 * a format has no Metal word, and for Sodium's terrain pipelines ({@link #sodiumTerrainPipelines}) where a copy would be
		 * needed: their shaders come from Sodium's own loader, which the game's pipeline compile cannot reach (the copy failed with
		 * "Couldn't find source for VERTEX shader (sodium:blocks/block_layer_opaque)", mcms-targets, 2026-11-15), so their draws
		 * in such a group are dropped with one WARN, as a variant whose copy did not compile is.
		 */
		private @Nullable GameVariant gameVariant(RenderPipeline game, GbufferPhase phase, int group) {
			if (game.getColorTargetStates().isEmpty()) {
				return null;
			}
			int[] targets = this.layouts[phase.ordinal()][group].targets();
			GpuFormat[] formats = new GpuFormat[targets.length];
			for (int attachment = 0; attachment < targets.length; attachment++) {
				GpuFormat format = MetalBridge.gpuFormatOf(TargetFormats.of(this.colorFormats.get(targets[attachment]),
					targets[attachment]).format());
				if (format == null) {
					return null;
				}
				formats[attachment] = format;
			}
			CopyRef copy = null;
			if (targets.length > 1 || game.getColorTargetStates().get(0).format() != formats[0]) {
				if (isSodiumTerrain(game.getLocation().toString()) || DistantPipelines.isPipeline(game.getLocation().toString())) {
					return null;  // never a copy of Sodium's or Distant Horizons' own terrain pipelines (above)
				}
				// Keyed by the attachment formats, not the target indices: the copy depends only on the formats (attachment 0 takes
				// the game's blend and mask, every other attachment is masked), so two groups with equal format lists share one
				// Metal state. The name carries the formats' ordinals, since a pipeline location path is lower case. The key keeps
				// the game's name and vertex layout: the same-named pipelines of two layouts are different copies (review item 7).
				StringBuilder suffix = new StringBuilder("/qs_formats");
				for (GpuFormat format : formats) {
					suffix.append('_').append(format.ordinal());
				}
				String key = game.getLocation() + "|" + layoutKey(strideWords(game)) + suffix;
				copy = this.copies.computeIfAbsent(key, unused -> {
					CopyRef ref = new CopyRef(sharedCopy(key, game, formats, suffix.toString()));
					ref.compiled = this.copier.compile(ref.request);
					return ref;
				});
			}
			DepthStencilState state = game.getDepthStencilState();
			return new Game(copy, binding(GameDepth.flipped(state)), binding(state), game.isCull(),
				game.getPolygonMode() == PolygonMode.WIREFRAME, MetalBridge.primitive(game.getPrimitiveTopology()));
		}

		/**
		 * The pipeline states of (program, phase, layout, topology, game blend), built once; a failed build is remembered as
		 * failed. The game blend is part of the key: the same program draws a translucent pipeline and an opaque one with
		 * different states (the blend is baked into the Metal pipeline).
		 */
		private Built pipeline(ResolvedProgram program, GbufferPhase phase, VertexFormat format, PrimitiveTopology topology,
				@Nullable BlendFunction blend) {
			PipelineKey key = new PipelineKey(program.name(), phase, format, topology, blend);
			Built built = this.pipelines.get(key);
			if (built == null) {
				built = build(program, phase, format, topology, blend);
				this.pipelines.put(key, built);
			}
			return built;
		}

		private Built build(ResolvedProgram program, GbufferPhase phase, VertexFormat format, PrimitiveTopology topology,
				@Nullable BlendFunction blend) {
			return buildWith(program, layoutOf(phase, program), this.colorFormats, false, format, topology, blend);
		}

		/**
		 * A shadow program's state for one vertex format and topology (E5): the shadow layout and formats, and no game blend
		 * (the shadow pass blends only by directive, {@link GbufferPlans#blendOf}).
		 */
		private Built buildShadow(ResolvedProgram program, VertexFormat format, PrimitiveTopology topology) {
			return buildWith(program, this.shadowLayout, this.shadowFormats, true, format, topology, null);
		}

		private Built buildWith(ResolvedProgram program, PhaseLayout attachments, Map<Integer, String> targetFormats,
				boolean shadow, VertexFormat format, PrimitiveTopology topology, @Nullable BlendFunction blend) {
			ProgramRequest.VertexLayout layout = VertexLayoutRegistry.vertexLayoutFor(format);
			PrologueFacts facts = VertexLayoutRegistry.prologueFor(format);
			if (layout != null && (facts.hasRegion() || facts.hasGlobals())) {
				// The registered layout's prologue (E4-B's facts): the request carries it, and the translator's vertex
				// prologue adds the region offset and subtracts the camera offset around the decoded position.
				layout = new ProgramRequest.VertexLayout(layout.attributes(), layout.fullScreen(),
					new ProgramRequest.VertexPrologue(facts.regionSlot(), facts.regionOffset(), facts.globalsSlot(),
						facts.cameraOffsetAt()));
			}
			int[] vertex = layout == null ? null : vertexWordsOf(format, layout);
			if (layout == null || vertex == null) {
				StringBuilder elements = new StringBuilder();
				for (VertexFormatElement element : format.getElements()) {
					elements.append(element.name()).append(':').append(element.format().name()).append(' ');
				}
				return Built.failed("the vertex format has no engine layout (elements " + elements.toString().trim() + ")");
			}
			boolean points = topology == PrimitiveTopology.POINTS;
			ProgramRequest request = shadow
				? ProgramRequests.shadow(program, this.model, this.custom, targetFormats, layout, attachments::attachmentOf,
					points, this.shadowTargets)
				: ProgramRequests.gbuffers(program, this.model, this.custom, targetFormats, this.mipmaps, layout,
					attachments::attachmentOf, points, this.shadowTargets);
			if (request == null) {
				return Built.failed("the program has no gbuffers request (no vertex or fragment stage)");
			}
			return compile(request, program, attachments, targetFormats, shadow, vertex, topology, blend, facts);
		}

		/** Translates, compiles and builds both pipeline states of one request (the tail {@link #build} and the clouds share). */
		private Built compile(ProgramRequest unfitted, ResolvedProgram program, PhaseLayout attachments,
				Map<Integer, String> targetFormats, boolean shadow, int[] vertex, PrimitiveTopology topology,
				@Nullable BlendFunction blend, PrologueFacts prologue) {
			ProgramRequest request = ProgramRequests.fitTextureIndices(unfitted);  // unused names take no texture index
			if (request == null) {
				return Built.failed("refused: the program needs more texture indices than Metal binds (logged with its name)");
			}
			checkCancelled(this.cancelled);
			ProgramTranslation translation = translate(request);
			if (!translation.ok()) {
				return Built.failed("translation failed: " + translation.diagnostics());
			}
			String storage = DimensionSet.storageRefusal(translation.reflection().buffers(), this.model.buffers());
			if (storage != null) {
				return Built.failed(storage);  // E6: a storage block with no bufferObject would bind nothing
			}
			Library library = library(translation.msl());
			if (!library.ok()) {
				return Built.failed("the Metal library failed: " + library.error());
			}
			try {
				long pso = pipelineState(library, translation,
					descriptor(program, attachments, targetFormats, shadow, vertex, topology, blend, true));
				long psoNoDepth = pipelineState(library, translation,
					descriptor(program, attachments, targetFormats, shadow, vertex, topology, blend, false));
				int perVertexSlot = request.perVertexIds() == null ? -1 : request.perVertexIds().slot();
				int overlaySlot = request.overlayColor() == null ? -1 : request.overlayColor().textureSlot();
				return new Built(pso, psoNoDepth, translation.reflection(), request.textures(), request.images(),
					request.buffers(), perVertexSlot, overlaySlot, prologue, "");
			} catch (IllegalStateException | IllegalArgumentException e) {
				return Built.failed("the Metal pipeline was refused: " + e.getMessage());
			}
		}

		/**
		 * The translation of a request, made once per distinct request ({@link #translations}). The time counted is the
		 * translator's own, once per distinct request; a worker that waits for another's translation adds nothing.
		 */
		private ProgramTranslation translate(ProgramRequest request) {
			CompletableFuture<ProgramTranslation> mine = new CompletableFuture<>();
			CompletableFuture<ProgramTranslation> known = this.translations.putIfAbsent(request, mine);
			if (known != null) {
				try {
					return known.join();
				} catch (CompletionException e) {
					// A waiter throws its own exception, never the owner's object: joinAll adds the failures it collects as
					// suppressed exceptions of the first one, and an exception cannot suppress itself (E9 review).
					if (e.getCause() instanceof Error err) {
						throw err;
					}
					throw new IllegalStateException("the shared translation of " + request.name() + " failed", e.getCause());
				}
			}
			long t0 = System.nanoTime();
			try {
				ProgramTranslation translation = Translator.translate(request);
				mine.complete(translation);
				return translation;
			} catch (RuntimeException | Error e) {
				mine.completeExceptionally(e);
				throw e;
			} finally {
				this.translateNanos.addAndGet(System.nanoTime() - t0);
			}
		}

		/**
		 * The Metal library of one MSL text, compiled once per distinct text: a later request for the same text (another phase
		 * or pipeline whose layout gives the same source) reuses it. The compile runs outside any map callback, so no bin of
		 * the map waits for it (E4 review item 14). Two workers that race on one text both compile it; the first to publish
		 * keeps its library and the other is released.
		 */
		private Library library(String msl) {
			Library known = this.libraries.get(msl);
			if (known != null) {
				return known;
			}
			checkCancelled(this.cancelled);  // before the library: a stop never starts a Metal compile
			long t0 = System.nanoTime();
			ShaderNative.Compiled compiled = ShaderNative.libraryNew(this.ctx, msl, ShaderNative.MSL_3_1,
				ShaderNative.MATH_PACKS, true);
			this.libraryNanos.addAndGet(System.nanoTime() - t0);
			Library made = new Library(compiled.library(), compiled.error());
			Library winner = this.libraries.putIfAbsent(msl, made);
			if (winner != null) {
				if (made.ok()) {
					MetalBridge.release(made.handle());
				}
				return winner;
			}
			return made;
		}

		/**
		 * One render pipeline state from the library and the descriptor, created once per distinct (functions, descriptor):
		 * equal states (the same program in two phases with the same layout, say) share one object. The Metal call runs
		 * outside any map callback (E4 review item 14); a racing worker's equal state is released in favour of the first one
		 * published. A state Metal refuses throws {@link IllegalStateException}, as {@link MetalBridge#pipelineNew} does, and
		 * nothing is cached for it.
		 */
		private long pipelineState(Library library, ProgramTranslation translation, int[] descriptor) {
			List<Integer> words = new ArrayList<>(descriptor.length);
			for (int word : descriptor) {
				words.add(word);
			}
			PsoKey key = new PsoKey(library.handle(), translation.vertexEntry(), library.handle(), translation.fragmentEntry(),
				words);
			Long known = this.psos.get(key);
			if (known != null) {
				return known;
			}
			checkCancelled(this.cancelled);  // before the pipeline state: a stop never starts a Metal call
			long t0 = System.nanoTime();
			long pso = MetalBridge.pipelineNew(this.ctx, library.handle(), translation.vertexEntry(), library.handle(),
				translation.fragmentEntry(), descriptor);
			this.psoNanos.addAndGet(System.nanoTime() - t0);
			Long winner = this.psos.putIfAbsent(key, pso);
			if (winner != null) {
				MetalBridge.release(pso);
				return winner;
			}
			this.owned.add(pso);
			return pso;
		}

		/**
		 * The int descriptor of one pack program for a phase's layout (see {@link #descriptorWords}). The blend lookup
		 * tries the slot's name first and then the serving program's ({@code gbuffers_terrain_solid} runs
		 * {@code gbuffers_terrain}'s files when the pack ships no solid ones, so a pack's {@code blend.gbuffers_terrain}
		 * must reach it — measured: Vitrail's picture of a pack that blends only through the base name keeps the
		 * blended target at its clear colour, 2026-11-14-e4-preview12). The slot's own directive always wins over the
		 * serving program's.
		 */
		private int[] descriptor(ResolvedProgram program, PhaseLayout attachments, Map<Integer, String> targetFormats,
				boolean shadow, int[] vertex, PrimitiveTopology topology, @Nullable BlendFunction blend, boolean depth) {
			int[] formats = new int[attachments.size()];
			for (int attachment = 0; attachment < formats.length; attachment++) {
				int target = attachments.targets()[attachment];
				formats[attachment] = descriptorFormat(targetFormats, target, shadow);
			}
			String serving = program.servedBy().orElse(program.name());
			return descriptorWords(vertex, attachments.targets(), formats, writesOf(program),
				target -> blendOf(this.model.properties().blends(), program.name(), serving,
					shadow ? List.of("shadowcolor" + target) : ProgramDirectives.targetNames(target), blend),
				MetalBridge.topologyClass(topology), depth);
		}

		/**
		 * The per-draw alpha-test reference of a program on one game pipeline: the pack's configured {@code alphaTest} for it
		 * ({@link ShaderProperties#alphaTestOf}: its own name's, else the serving program's); 0 when the pack switches the test
		 * off, so a program that reads {@code alphaTestRef} and tests itself discards nothing, as a compatibility-profile
		 * program then gets no test (ProgramRequests); else the game pipeline's own cutout ({@link #defaultAlphaTestRef}).
		 */
		private float alphaTestRef(ResolvedProgram program, RenderPipeline game) {
			ShaderProperties.AlphaTest test = this.model.properties().alphaTestOf(program).orElse(null);
			if (test == null) {
				return defaultAlphaTestRef(game);
			}
			return test.off() ? 0f : (float) test.ref();
		}

		/** The variant of a built pipeline for one game pipeline: both depth bindings, its rasteriser state and its per-draw values. */
		private Pack pack(Built built, ResolvedProgram program, int group, int renderStage, RenderPipeline game) {
			DepthStencilState state = game.getDepthStencilState();
			float alphaTestRef = alphaTestRef(program, game);
			return new Pack(built.pso(), built.psoNoDepth(), binding(GameDepth.flipped(state)), binding(state), game.isCull(),
				game.getPolygonMode() == PolygonMode.WIREFRAME, MetalBridge.primitive(game.getPrimitiveTopology()), program.name(),
				renderStage, alphaTestRef, built.reflection(), built.textures(), built.images(), built.buffers(),
				built.perVertexIdSlot(), built.overlaySlot(), built.prologue(), isClouds(game), group);
		}

		/**
		 * The variant of a built shadow program (E5): both depth bindings are the shadow pass's own, less-equal with depth
		 * write and no bias (the engine convention's flip of the game's reversed compare, GameDepth), its face culling is the
		 * game pipeline's, and its render stage is its row's. The pack's {@code shadow.culling} is not a pipeline state: it
		 * sets the distance rule of the light's caster lists ({@code ShadowCamera.cullRadius}), so it is not read here (E5
		 * review item 14).
		 */
		private Pack shadowPack(Built built, ResolvedProgram program, int renderStage, RenderPipeline game) {
			float alphaTestRef = alphaTestRef(program, game);
			DepthBinding depth = binding(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, true, 0, 0));
			return new Pack(built.pso(), built.psoNoDepth(), depth, depth, game.isCull(),
				game.getPolygonMode() == PolygonMode.WIREFRAME, MetalBridge.primitive(game.getPrimitiveTopology()), program.name(),
				renderStage, alphaTestRef, built.reflection(), built.textures(), built.images(), built.buffers(),
				built.perVertexIdSlot(), built.overlaySlot(), built.prologue(), false, 0);
		}

		/** The binding of a depth state: its handle and its bias (no state means always pass and no write, MetalPipeline's rule). */
		private DepthBinding binding(@Nullable DepthStencilState state) {
			return new DepthBinding(depthState(state), state == null ? 0 : state.depthBiasConstant(),
				state == null ? 0 : state.depthBiasScaleFactor(),
				state == null ? "none" : "compare=" + state.depthTest() + " write=" + (state.writeDepth() ? 1 : 0));
		}

		private long depthState(@Nullable DepthStencilState state) {
			int compare = state == null ? 7 : MetalBridge.compareWord(state.depthTest());
			boolean write = state != null && state.writeDepth();
			int key = compare * 2 + (write ? 1 : 0);
			Long handle = this.depthStates.get(key);
			if (handle == null) {
				handle = MetalBridge.depthStateNew(this.ctx, compare, write);
				this.depthStates.put(key, handle);
				this.owned.add(handle);
			}
			return handle;
		}

		/** The pack programs that did not build for some pipeline ({@link #summarizeFailures}); empty when every build succeeded. */
		String failureSummary() {
			List<Map.Entry<String, String>> failed = new ArrayList<>();
			for (Map.Entry<PipelineKey, Built> entry : this.pipelines.entrySet()) {
				if (entry.getValue().pso() == 0) {
					failed.add(Map.entry(entry.getKey().program(), entry.getValue().reason()));
				}
			}
			return summarizeFailures(failed);
		}

		GbufferPlans finish() {
			Stats stats = new Stats(this.packVariants.get(), this.gameVariants.get(), this.translations.size(),
				this.libraries.size(), this.pipelines.size(), this.psos.size(), this.translateNanos.get(),
				this.libraryNanos.get(), this.psoNanos.get(), System.nanoTime() - this.startNanos, this.threads);
			this.translations.clear();  // the translations live as long as the build; the plans keep their reflections
			releaseLibraries();
			List<Map<String, Integer>> programGroups = new ArrayList<>();
			for (GbufferPhase phase : GbufferPhase.values()) {
				Map<String, Integer> byName = new TreeMap<>();
				for (Map.Entry<PhaseProgram, Integer> entry : this.groupByProgram.entrySet()) {
					if (entry.getKey().phase() == phase) {
						byName.put(entry.getKey().program(), entry.getValue());
					}
				}
				programGroups.add(byName);
			}
			return new GbufferPlans(this.ctx, this.layouts, this.targets, this.feedback, this.reads, pipelineReads(false),
				pipelineReads(true), shadowLevelReads(), this.colorFormats, this.plans, this.owned,
				new ArrayList<>(this.copies.values()), this.servesWorld, this.shadowLayout, this.shadowFormats, stats,
				programGroups);
		}

		/**
		 * The targets the built shadow programs can read at a mip level other than 0 (the frame head refills these chains
		 * before the shadow pass, E9): {@link #pipelineReads} with levels, over the shadow pipelines alone.
		 */
		private int[] shadowLevelReads() {
			TreeSet<Integer> set = new TreeSet<>();
			for (Map.Entry<PipelineKey, Built> entry : this.pipelines.entrySet()) {
				Built built = entry.getValue();
				if (!this.shadowKeys.contains(entry.getKey()) || built.pso() == 0 || built.reflection() == null) {
					continue;
				}
				for (ProgramTranslation.Reflection.ResourceUse texture : built.reflection().textures()) {
					int target = ProgramRequests.targetIndex(texture.name());
					if (target >= 0 && texture.levels()) {
						set.add(target);
					}
				}
			}
			return set.stream().mapToInt(Integer::intValue).toArray();
		}

		/**
		 * Per phase, the targets a built pipeline's program samples (the reflection's textures and colour images), or with
		 * {@code levelsOnly} those it can read at a mip level other than 0 (the reflection's {@code ResourceUse.levels},
		 * E9). A shadow program is keyed to the opaque phase ({@link #firstShadow}), so its reads join the opaque phase's:
		 * a refill or copy more, never one less.
		 */
		private int[][] pipelineReads(boolean levelsOnly) {
			List<TreeSet<Integer>> sets = new ArrayList<>();
			for (int i = 0; i < GbufferPhase.values().length; i++) {
				sets.add(new TreeSet<>());
			}
			for (Map.Entry<PipelineKey, Built> entry : this.pipelines.entrySet()) {
				Built built = entry.getValue();
				if (built.pso() == 0 || built.reflection() == null) {
					continue;
				}
				TreeSet<Integer> set = sets.get(entry.getKey().phase().ordinal());
				for (ProgramTranslation.Reflection.ResourceUse texture : built.reflection().textures()) {
					int target = ProgramRequests.targetIndex(texture.name());
					if (target >= 0 && (!levelsOnly || texture.levels())) {
						set.add(target);
					}
				}
				if (!levelsOnly) {
					for (ProgramTranslation.Reflection.ResourceUse image : built.reflection().images()) {
						int target = ComputeKernel.colourTargetOf(image.name());
						if (target >= 0) {
							set.add(target);
						}
					}
				}
			}
			int[][] out = new int[sets.size()][];
			for (int i = 0; i < out.length; i++) {
				out[i] = sets.get(i).stream().mapToInt(Integer::intValue).toArray();
			}
			return out;
		}

		/** Releases what this build created (a failed build leaves nothing alive). */
		void abandon() {
			releaseLibraries();
			release(this.owned);
		}

		/**
		 * The libraries are only needed while the pipeline states are created (a state keeps what it uses), so the
		 * build releases them as it ends, shared or not.
		 */
		private void releaseLibraries() {
			for (Library library : this.libraries.values()) {
				if (library.ok()) {
					MetalBridge.release(library.handle());
				}
			}
			this.libraries.clear();
		}
	}

	/**
	 * The vertex half of the descriptor {@code mc_pipeline_new} reads (mcmetal.m's layout): one vertex buffer at the
	 * engine's vertex-data slot with its stride (per vertex), and its attributes as (location, offset, vertex format word);
	 * no buffer and no attribute when {@code attributes} is empty.
	 */
	static int[] vertexWords(int stride, List<int[]> attributes) {
		if (attributes.isEmpty()) {
			return new int[] {0, 0};
		}
		int[] words = new int[4 + 1 + attributes.size() * 4];
		int i = 0;
		words[i++] = 1;
		words[i++] = EngineSlots.VERTEX_DATA;
		words[i++] = stride;
		words[i++] = 0;
		words[i++] = attributes.size();
		for (int[] attribute : attributes) {
			words[i++] = attribute[0];
			words[i++] = EngineSlots.VERTEX_DATA;
			words[i++] = attribute[1];
			words[i++] = attribute[2];
		}
		return words;
	}

	/**
	 * The vertex half for a vertex format: one attribute per layout attribute, at its offset, whose device format is the
	 * element of the format at that offset with the attribute's Metal format (null when none matches, so the layout and
	 * the format disagree and the program does not build). Elements the layout does not name (the glint-special UV3,
	 * {@link VertexLayoutRegistry#UNREAD_ELEMENT}) stay in the stride only: no program reads them.
	 */
	static int @Nullable [] vertexWordsOf(VertexFormat format, ProgramRequest.VertexLayout layout) {
		List<int[]> attributes = new ArrayList<>();
		for (ProgramRequest.VertexAttribute attribute : layout.attributes()) {
			VertexFormatElement element = elementAt(format, attribute.offset(), attribute.format());
			if (element == null) {
				return null;
			}
			try {
				attributes.add(new int[] {attributes.size(), attribute.offset(), MetalBridge.vertexFormat(element.format())});
			} catch (IllegalArgumentException unsupported) {
				return null;
			}
		}
		return vertexWords(format.getVertexSize(), attributes);
	}

	/**
	 * The element of a format at an offset whose Metal format is {@code metal}; null when there is none. The layout's
	 * format may be wider of the same kind ({@code uint2} reads {@code RG16_UINT}, Metal converts on load — the terrain
	 * layout decodes its packed words as uints), so the kind, the channel count and the normalisation must agree, not
	 * the exact width.
	 */
	private static @Nullable VertexFormatElement elementAt(VertexFormat format, int offset, String metal) {
		for (VertexFormatElement element : format.getElements()) {
			if (element.offset() == offset && compatible(metal, VertexLayoutRegistry.metalFormat(element.format()))) {
				return element;
			}
		}
		return null;
	}

	/**
	 * Whether two Metal vertex format names read the same kind of value (the width may differ within the kind: the
	 * layout's {@code uint2} reads an {@code RG16_UINT} element's {@code ushort2}, Metal converts on load). The names
	 * agree when their kind letter, component count and normalisation agree — not their string lengths, which differ
	 * with the width ({@code uint2} against {@code ushort2}).
	 */
	private static boolean compatible(String layoutFormat, @Nullable String elementFormat) {
		return elementFormat != null && layoutFormat.charAt(0) == elementFormat.charAt(0)
			&& components(layoutFormat) == components(elementFormat)
			&& layoutFormat.endsWith("Normalized") == elementFormat.endsWith("Normalized");
	}

	/** The component count of an emitter Metal vertex format name ({@code float3} → 3, {@code uchar4Normalized} → 4). */
	private static int components(String format) {
		for (int i = 0; i < format.length(); i++) {
			if (Character.isDigit(format.charAt(i))) {
				return format.charAt(i) - '0';
			}
		}
		return 1;
	}

	/**
	 * The int descriptor {@code mc_pipeline_new} reads (mcmetal.m's layout): the vertex half ({@link #vertexWords}), one
	 * colour attachment per layout slot with its format word, the written targets writing all components with their blend
	 * (from {@code blendOf}), the unwritten slots masked; the depth attachment in Depth32Float (the engine's and the game's
	 * depth) when {@code depth}, none otherwise; and the topology class.
	 *
	 * @param vertex     the vertex half
	 * @param targets    the target index at each attachment
	 * @param formats    the format word at each attachment
	 * @param written    the targets the pipeline writes
	 * @param blendOf    the blend of a written target (null: no blending)
	 * @param topologyClass the MTLPrimitiveTopologyClass word
	 * @param depth      whether the pass this state draws in has a depth attachment
	 */
	static int[] descriptorWords(int[] vertex, int[] targets, int[] formats, Set<Integer> written,
			IntFunction<ShaderProperties.BlendState> blendOf, int topologyClass, boolean depth) {
		int slots = targets.length;
		int[] words = new int[vertex.length + 1 + slots * 9 + 2];
		System.arraycopy(vertex, 0, words, 0, vertex.length);
		int i = vertex.length;
		words[i++] = slots;
		for (int attachment = 0; attachment < slots; attachment++) {
			int target = targets[attachment];
			int format = formats[attachment];
			words[i++] = format;
			boolean draws = written.contains(target);
			words[i++] = draws ? 15 : 0;
			// OpenGL 4.6 section 17.3.6 ignores blending on integer attachments; Metal aborts pipeline validation if enabled.
			ShaderProperties.BlendState blend = draws && !TargetFormats.isInteger(format) ? blendOf.apply(target) : null;
			if (blend != null && !blend.off()) {
				words[i++] = 1;
				words[i++] = DimensionSet.blendFactor(blend.src());
				words[i++] = DimensionSet.blendFactor(blend.dst());
				words[i++] = blendOp(blend.op());
				words[i++] = DimensionSet.blendFactor(blend.srcAlpha());
				words[i++] = DimensionSet.blendFactor(blend.dstAlpha());
				words[i++] = blendOp(blend.alphaOp());
			} else {
				words[i++] = 0;
				i += 6;
			}
		}
		words[i++] = depth ? MetalBridge.pixelFormat(GpuFormat.D32_FLOAT) : 0;
		words[i] = topologyClass;
		return words;
	}

	/**
	 * Every build's copies of this session, by key (E8; plan.md E6, "Known item"): the game's pipeline cache keys a compiled
	 * pipeline by the pipeline object, so a later build (a pack switch, an option change, the reload key) that asks for an
	 * equal copy gets the same object, and with it the cache's compiled pipeline, instead of a new object that the cache would
	 * keep until the next resource reload. Bounded by the game's pipelines times their format lists. An entry belongs to the
	 * game pipeline it was made from: another object under the same key (a mod's pipeline that reuses a name) gets its own copy.
	 */
	private static final ConcurrentHashMap<String, SharedCopy> SHARED_COPIES = new ConcurrentHashMap<>();

	private record SharedCopy(RenderPipeline game, RenderPipeline copy) {
	}

	/** The session's copy of {@code game} for {@code key}, made once ({@link #copyWithTargets}); any thread. */
	static RenderPipeline sharedCopy(String key, RenderPipeline game, GpuFormat[] formats, String suffix) {
		return SHARED_COPIES.compute(key, (unused, old) -> old != null && old.game() == game ? old
			: new SharedCopy(game, copyWithTargets(game, formats, suffix))).copy();
	}

	/**
	 * A copy of a game pipeline with one colour target per attachment of a phase's group (2026-11-15): attachment 0 takes the
	 * game's own target (its blend and write mask) in {@code formats[0]}, every other attachment is {@code formats[k]} with no
	 * write, so the pass keeps its contents there. The game's shaders, bind groups, depth state, cull, topology and vertex formats
	 * are kept, under the location {@code <game><suffix>}; its shader defines are the game's, except as {@link #copyDefines} sets
	 * them. It is built through the renderpearl constructor so the copy keeps the game's defines (the builder cannot copy them).
	 */
	static RenderPipeline copyWithTargets(RenderPipeline game, GpuFormat[] formats, String suffix) {
		ColorTargetState first = game.getColorTargetStates().get(0);
		ColorTargetState[] targets = new ColorTargetState[formats.length];
		targets[0] = new ColorTargetState(first.blendFunction(), formats[0], first.writeMask());
		for (int attachment = 1; attachment < formats.length; attachment++) {
			targets[attachment] = new ColorTargetState(Optional.empty(), formats[attachment], 0);
		}
		List<VertexFormat> vertexFormats = game.getVertexFormatBindings();
		Identifier location = Identifier.fromNamespaceAndPath(game.getLocation().getNamespace(),
			game.getLocation().getPath() + suffix);
		return new RenderPipeline(location, game.getShaders(), copyDefines(game, formats), game.getBindGroupLayouts(), targets,
				game.getDepthStencilState(), game.getPolygonMode(), game.isCull(), vertexFormats.toArray(new VertexFormat[0]),
				game.getPrimitiveTopology(), game.pushConstantSize(), game.getSortKey()) {
		};
	}

	/** The transmittance pipelines' coefficient count define (OIT_SNIPPET) and the flag that marks a transmittance pipeline. */
	private static final String OIT_COEFFICIENT_COUNT = "OIT_COEFF_ATTACHMENT_COUNT";
	private static final String OIT_TRANSMITTANCE = "OIT_TRANSMITTANCE";

	/**
	 * The shader defines of a copy (2026-11-23). A transmittance pipeline's fragment writes one float4 output per counted attachment:
	 * oit_add_transmittance.glsl declares {@code coeff[OIT_COEFF_ATTACHMENT_COUNT]} (locations 0 and 1 for the count of 2 that OIT_SNIPPET
	 * sets), and writes coeff[a] from coefficients 4a to 4a+3 for each a below the count (Minecraft 26.3's assets). Metal checks each
	 * fragment output against the pipeline's attachment of its index, integer ones included, even with no write: a float4 output on an
	 * RGBA32Uint attachment fails the pipeline (Noble 1.9.9's copies). So when a masked attachment at index k is an integer format and
	 * k is below the game's count, the copy counts k outputs, and the shader declares only the outputs for attachments below k. Attachment
	 * 0 is the same: coeff[0] takes coefficients 0 to 3 for any count of one or more. Every other pipeline keeps the game's defines.
	 */
	static ShaderDefines copyDefines(RenderPipeline game, GpuFormat[] formats) {
		ShaderDefines defines = game.getShaderDefines();
		String count = defines.values().get(OIT_COEFFICIENT_COUNT);
		if (count == null || !defines.flags().contains(OIT_TRANSMITTANCE)) {
			return defines;
		}
		int limit;
		try {
			limit = Integer.parseInt(count);
		} catch (NumberFormatException notANumber) {
			return defines;  // a count the shader does not read as a number: the game's defines stay
		}
		for (int attachment = 1; attachment < formats.length; attachment++) {
			if (TargetFormats.isInteger(MetalBridge.pixelFormat(formats[attachment]))) {
				if (attachment >= limit) {
					return defines;  // every counted output lands on an attachment below this one
				}
				return defines.withOverrides(ShaderDefines.builder().define(OIT_COEFFICIENT_COUNT, attachment).build());
			}
		}
		return defines;
	}

	/**
	 * The pipelines of Sodium's own terrain (plan.md E4, "Sodium terrain"): Sodium builds one renderpearl pipeline per
	 * terrain pass, named after the pass's game pipeline path under the {@code sodium} namespace, and they appear in no
	 * game pipeline list — without them here every redirected terrain draw drops ("no plan was built for game pipeline
	 * sodium:pipeline/solid_terrain", log 2026-11-14-e4-preview2). The engine builds the same objects from the facts of
	 * both allowed jars ({@code ShaderChunkRenderer.createShader}, javap 2026-11-14 on g6ju16VJ and v7Lw0h1m;
	 * engine-sodium-terrain.md section 3): location {@code sodium:pipeline/<pass path>} ({@code ChunkSectionLayer
	 * .pipeline(false)}'s path, never the multidraw ids), cull on, {@code DepthStencilState.DEFAULT}, QUADS, vertex
	 * binding 0 the swapped extended format, shaders {@code sodium:blocks/block_layer_opaque}, 20 push-constant bytes,
	 * one RGBA8 colour target with TRANSLUCENT blending on the translucent pass alone. Each carries its game pipeline's
	 * {@code ALPHA_CUTOUT} (RIL-562: {@link #defaultAlphaTestRef} reads it, 0.5 on the cutout pass); Sodium's own shader
	 * takes its cutout per vertex, so the define is a fact for the plans only.
	 *
	 * <p>{@link Builder#plan} treats them like a game pipeline: the registered layout serves the pack program, and a
	 * game variant without one draws the backend object the pass set (Sodium's own compiled pipeline, with its defines)
	 * — never a copy of these.
	 */
	static List<RenderPipeline> sodiumTerrainPipelines(List<RenderPipeline> gamePipelines, VertexFormat extended) {
		List<RenderPipeline> sodium = new ArrayList<>();
		for (RenderPipeline game : gamePipelines) {
			String path = game.getLocation().getPath();
			if (!SODIUM_TERRAIN_PATHS.contains(path)) {
				continue;
			}
			ColorTargetState target = path.endsWith("translucent_terrain")
				? new ColorTargetState(Optional.of(BlendFunction.TRANSLUCENT), GpuFormat.RGBA8_UNORM, -1)
				: ColorTargetState.DEFAULT;
			RenderPipeline.Builder builder = RenderPipeline.builder()
				.withLocation(Identifier.fromNamespaceAndPath("sodium", path))
				.withCull(true)
				.withVertexShader(Identifier.fromNamespaceAndPath("sodium", "blocks/block_layer_opaque"))
				.withFragmentShader(Identifier.fromNamespaceAndPath("sodium", "blocks/block_layer_opaque"))
				.withDepthStencilState(DepthStencilState.DEFAULT)
				.withPrimitiveTopology(PrimitiveTopology.QUADS)
				.withVertexBinding(0, extended)
				.withPushConstantSize(20)
				.withColorTargetState(target);
			Float cutout = gameCutout(game);
			if (cutout != null) {
				builder.withShaderDefine(ALPHA_CUTOUT, cutout);
			}
			sodium.add(builder.build());
		}
		return sodium;
	}

	/**
	 * The runnable programs without the gbuffers programs that write a colour target {@code size.buffer} sizes apart from the
	 * render (2026-11-15). A gbuffers pass attaches one size: Metal clips a pass whose attachments differ to the smallest one
	 * (AttachmentSizesGpuTest), and engine-pack-format.md section 7 gives {@code size.buffer} to prepare, deferred and
	 * composite passes only. Such a program is not run: its draws take its next fallback or the game's pipeline, with one
	 * WARN that names the program and the target. Composite and shadow programs are kept as they are.
	 */
	static Map<String, ResolvedProgram> withinOneSize(Map<String, ResolvedProgram> runnable,
			Map<String, ShaderProperties.BufferSize> sizes) {
		Map<String, ResolvedProgram> kept = new HashMap<>(runnable);
		for (ResolvedProgram program : runnable.values()) {
			if (!program.name().startsWith(PROGRAM_PREFIX)) {
				continue;
			}
			Optional<Integer> apart = sizedApart(writesOf(program), sizes);
			if (apart.isPresent()) {
				kept.remove(program.name());
				LOG.warn("[mcms-shaders] {} writes colortex{}, which size.buffer sizes apart from the render; a gbuffers pass"
					+ " attaches one size, so the program is not run", program.name(), apart.get());
			}
		}
		return kept;
	}

	/** The lowest colour target of {@code writes} that {@code sizes} sizes apart from the render, or empty when none does. */
	static Optional<Integer> sizedApart(Set<Integer> writes, Map<String, ShaderProperties.BufferSize> sizes) {
		for (int target : new TreeSet<>(writes)) {
			ShaderProperties.BufferSize size = Targets.bufferSizeOf(sizes, target);
			if (size != null && !(size.relative() && size.width() == 1.0 && size.height() == 1.0)) {
				return Optional.of(target);
			}
		}
		return Optional.empty();
	}

	/** The targets a program writes: its directive's list; attachment 0 alone when it writes none (colortex0 stays pinned). */
	private static Set<Integer> writesOf(ResolvedProgram program) {
		Optional<ProgramDirectives> directives = program.directives();
		List<Integer> writes = directives.map(ProgramDirectives::writes).orElse(List.of());
		return writes.isEmpty() ? Set.of(0) : Set.copyOf(writes);
	}

	/** The targets a program reads (its directive's list; none without one). */
	private static Set<Integer> readsOf(ResolvedProgram program) {
		return program.directives().map(ProgramDirectives::reads).orElse(Set.of());
	}

	private static boolean contains(GbufferPhase[] phases, GbufferPhase phase) {
		for (GbufferPhase candidate : phases) {
			if (candidate == phase) {
				return true;
			}
		}
		return false;
	}
}
