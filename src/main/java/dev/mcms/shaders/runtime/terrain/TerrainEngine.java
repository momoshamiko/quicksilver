// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.terrain;

import com.mojang.renderpearl.api.vertex.VertexFormat;
import dev.mcms.shaders.runtime.Engine;
import dev.mcms.shaders.runtime.distant.DistantHorizonsRuntime;
import dev.mcms.shaders.runtime.gbuffers.GbufferDelegate;
import dev.mcms.shaders.runtime.gbuffers.PrologueFacts;
import dev.mcms.shaders.runtime.gbuffers.VertexLayoutRegistry;
import java.util.Map;
import java.util.logging.Logger;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * The terrain side's static hub (plan.md E4 "Sodium terrain"): the latched answer of {@code ChunkMeshFormats.getCurrent}
 * (the format Sodium builds with changes only together with {@code SodiumWorldRenderer.reload()}, which the engine
 * calls when a set becomes active or inactive, or a different set becomes active: {@link #frameCheck}), the active
 * set's block-id table, and the layout registration with E4-A's registry.
 *
 * <p>While no set is active every hook's path through here is one static read and no allocation (the engine-inert
 * rule of the plan's mixin gates): {@link #active()} is false, {@link #blockIdOf} answers -1 from the empty table, and
 * the format hooks pass Sodium's own answers through.
 */
public final class TerrainEngine {
	private static final Logger LOG = Logger.getLogger(TerrainEngine.class.getName());

	/**
	 * The buffer slot where the backend binds Sodium's per-region push constants (the region offset at byte 0). The
	 * backend's push-constant index, {@code EngineSlots.PUSH_CONSTANTS}: the terrain prologue reads it directly.
	 */
	static final int REGION_SLOT = dev.mcms.shaders.runtime.EngineSlots.PUSH_CONSTANTS;

	/** Whether Sodium builds terrain with the extended format (latched; flips only with a world reload). */
	private static volatile boolean extended;

	/** The active set's block ids; the empty table while no set is active. */
	private static volatile BlockIds blockIds = BlockIds.EMPTY;

	/** The parsed layout of Sodium's globals block; null until {@link #registerLayout} read it. */
	private static volatile @Nullable SodiumGlobals globals;

	/**
	 * The cardinal lighting of the pack's terrain when it does not ask for the old lighting: every face 1, so Sodium's
	 * light pipelines multiply no directional shade into a vertex's brightness (plan.md E7, measured with mcms-vcolor:
	 * Iris 1.11.7 drew every face unshaded at {@code oldLighting=false} and with the directive unset).
	 */
	public static final CardinalLighting FLAT_SHADE = new CardinalLighting(1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f);

	/** Whether the active set's terrain is built without the directional shade ({@code oldLighting} not true). */
	private static volatile boolean flatShade;

	/** Whether the active set's terrain carries its ambient occlusion in the colour's alpha ({@code separateAo=true}). */
	private static volatile boolean separateAo;

	/** Whether the engine draws Distant Horizons through the pack in this session (Engine's {@code distantActive}). */
	private static volatile boolean distantServed;

	/** The {@link #distantFlatShade()} answer DH's cached render data was last built under (render thread only). */
	private static boolean distantShadeBuilt;

	/** Whether the layout is registered and Sodium's globals block passed its check (decided once, at registration). */
	private static volatile boolean terrainReady;

	/** Whether the layout registration ran (the mixin calls it every tick; the first call does the work). */
	private static boolean layoutRegistered;

	/** The set the latch last applied (render thread only); a different set reloads the meshes. */
	private static @Nullable Object latchedSet;

	/** The per-release adapter (ADR 0007), bound when a set becomes active. */
	private static final SodiumAdapter ADAPTER = new SodiumAdapter();

	private TerrainEngine() {
	}

	/** The engine's terrain vertex type, the answer of the {@code getCurrent} mixin while a set is active. */
	public static TerrainVertexType terrainType() {
		return TerrainVertexType.INSTANCE;
	}

	/** Whether the extended format is latched in (the {@code getCurrent} mixin's answer). */
	public static boolean active() {
		return extended;
	}

	/** The pack's id of the block state, -1 while no set is active or the pack lists it nowhere. */
	public static int blockIdOf(BlockState state) {
		return blockIds.idOf(state);
	}

	/**
	 * Whether chunk meshes are built without the directional shade: the {@code LevelSlice} hook's question for blocks and the
	 * fluid renderer hook's for fluids (engine-sodium-hooks.md rows 15 and 16).
	 */
	public static boolean flatShade() {
		return extended && flatShade;
	}

	/**
	 * Whether Distant Horizons builds its LOD colours without the face shade (DistantHorizonsShadeMixin): the terrain rule of
	 * {@link #flatShade()} holds and the engine draws DH through the pack in this session. With a pack that has no {@code dh_}
	 * program DH draws with its own shader and keeps its shade.
	 */
	public static boolean distantFlatShade() {
		return extended && flatShade && distantServed;
	}

	/** Records whether the engine draws Distant Horizons through the pack in this session (the engine's pack load). */
	public static void distantServed(boolean served) {
		distantServed = served;
	}

	/**
	 * Whether the encoder writes the ambient occlusion to the colour's alpha and leaves red, green and blue unscaled
	 * (OptiFine's documentation of {@code separateAo}: "the AO brightness ... is separated from color.rgb and put in
	 * color.a"); otherwise it multiplies red, green and blue by it, as Sodium's own encoder does.
	 */
	public static boolean separateAo() {
		return extended && separateAo;
	}

	/**
	 * The pack's two vertex-colour directives (engine-pack-format.md section 5): the shade is flat unless the pack sets
	 * {@code oldLighting=true}, and the occlusion moves to alpha only when it sets {@code separateAo=true}. Both defaults
	 * are Iris's, measured with the mcms-vcolor pack that sets neither (plan.md E7).
	 */
	record VertexColourRule(boolean flatShade, boolean separateAo) {
		static VertexColourRule of(Map<String, Boolean> directives) {
			return new VertexColourRule(!Boolean.TRUE.equals(directives.get("oldLighting")),
				Boolean.TRUE.equals(directives.get("separateAo")));
		}
	}

	/** The parsed globals layout (null before registration or when Sodium's jar could not be read). */
	public static @Nullable SodiumGlobals globals() {
		return globals;
	}

	/** The adapter; bound when a set becomes active (ADR 0007: engine start). */
	public static SodiumAdapter adapter() {
		return ADAPTER;
	}

	/**
	 * Registers the extended terrain format with E4-A's registry: the layout with its decode expressions, and the
	 * prologue facts from Sodium's own {@code globals.glsl} (the region offset in the push constants, the globals mirror
	 * slot, the camera offset's byte offset, which only the release's own block knows; ADR 0007 decision 3).
	 *
	 * <p>Called from {@code SodiumEngineStartMixin} on the render thread at every game tick, before any set can load. It
	 * does not run in the mixin plugin: Mixin asks the plugin while it prepares the configs, and this loads the
	 * renderpearl {@code VertexFormat} that Sodium's {@code VertexFormatMixin} targets, which would fail there
	 * (ReEntrantTransformerError or MixinTargetAlreadyLoadedException, E4-B review item 1).
	 */
	public static synchronized void registerLayout() {
		if (layoutRegistered) {
			return;
		}
		registerLayout(SodiumGlobals.read());
	}

	/**
	 * The registration with the globals block already read ({@code null} when Sodium's globals cannot be read). A failed
	 * check logs nothing here: the engine's frame start logs the refusal once, as the set's one ERROR (E4 review item 6).
	 */
	static synchronized void registerLayout(@Nullable SodiumGlobals parsed) {
		if (layoutRegistered) {
			return;
		}
		layoutRegistered = true;
		GbufferDelegate.addReleaseListener(TerrainUniforms::release);
		globals = parsed;
		if (globalsFailure(parsed) != null) {
			return;
		}
		VertexFormat format = TerrainVertexType.INSTANCE.getVertexFormat();
		VertexLayoutRegistry.register(format, TerrainLayout.layout(parsed.offsetOf("u_TexCoordShrink")),
			new PrologueFacts(REGION_SLOT, 0, TerrainUniforms.GLOBALS_SLOT, parsed.cameraOffsetAt(),
				parsed.offsetOf("u_ModelViewMatrix")));
		terrainReady = true;
	}

	/**
	 * Why the terrain extension cannot run on this Sodium's globals block, or null when it can (ADR 0007 decision 3: a
	 * block that lacks a field the engine needs fails the set's load, with one ERROR; the set then draws terrain with the
	 * game's pipelines).
	 */
	public static @Nullable String globalsFailure(@Nullable SodiumGlobals block) {
		if (block == null) {
			return "Sodium's globals.glsl could not be read; the terrain stays on the game's pipelines";
		}
		if (!block.problems().isEmpty()) {
			return "Sodium's globals block has problems, so the set's terrain stays on the game's pipelines: "
				+ String.join("; ", block.problems());
		}
		return null;
	}

	/**
	 * The latch (the setupTerrain mixin calls this every frame on the render thread, before the terrain layers draw).
	 * When the extended state or the active set differs from the latched one, the answer flips first (the reload that
	 * follows must build with the new format), the block ids of the new set and the adapter refresh, and the world
	 * reloads its meshes: every region, arena and worker reads {@code getCurrent} again ({@code SodiumWorldRenderer
	 * .reload} builds a fresh render-section manager; engine-sodium-hooks.md section 2). A pack or option change that
	 * keeps the engine active is a new set, so it reloads too (the block ids, the vertex-colour rule and the meshes
	 * depend on it). When {@link #distantFlatShade()} flips with it, Distant Horizons drops its cached render data, so its
	 * LOD colours are built again with the new shade (it bakes the shade in when it builds a buffer).
	 *
	 * @param engineActive whether the engine's state is ACTIVE
	 * @param set          the active set, or null
	 */
	public static void frameCheck(boolean engineActive, @Nullable Object set, SodiumWorldRenderer renderer) {
		latch(engineActive, set, renderer);
		boolean distantShade = distantFlatShade();
		// Compared with the answer DH last built under, not with the answer before the latch: distantServed changes at the pack's
		// load, before the latch sees the new set, so a switch from a pack that serves DH to one that does not is caught here too.
		if (distantShade != distantShadeBuilt && DistantHorizonsRuntime.active()) {
			distantShadeBuilt = distantShade;
			dev.mcms.metal.distant.DistantHorizonsDriver.clearRenderData();
		}
	}

	private static void latch(boolean engineActive, @Nullable Object set, SodiumWorldRenderer renderer) {
		TerrainUniforms.syncConstantBlocks();
		boolean on = engineActive && set != null && terrainReady;
		if (!on) {
			if (!extended) {
				return;
			}
			extended = false;
			latchedSet = null;
			blockIds = BlockIds.EMPTY;
			renderer.reload();
			return;
		}
		if (extended && set == latchedSet) {
			return;
		}
		Engine engine = Engine.get();
		var model = engine.model();
		VertexColourRule rule = VertexColourRule.of(model != null ? model.properties().booleans() : Map.of());
		flatShade = rule.flatShade();
		separateAo = rule.separateAo();
		extended = true;
		latchedSet = set;
		blockIds = model != null ? BlockIds.of(model.blocks()) : BlockIds.EMPTY;
		TerrainUniforms.activate();
		if (!ADAPTER.bind()) {
			LOG.severe("[mcms-shaders] the Sodium adapter could not bind this release: its update and traverse handles stay unset");
		}
		renderer.reload();
	}
}
