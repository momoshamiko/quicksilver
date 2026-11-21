// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.metal.distant;

import com.mojang.blaze3d.systems.RenderSystem;
import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.common.wrappers.McObjectConverter;
import com.seibel.distanthorizons.core.api.internal.ClientApi;
import com.seibel.distanthorizons.core.api.internal.rendering.DhRenderState;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.render.RenderParams;
import dev.mcms.shaders.runtime.Engine;
import dev.mcms.shaders.runtime.distant.DistantFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Distant Horizons calls of the shader engine (plan.md E7, "Distant Horizons, as built"). Everything here touches DH's classes, so
 * it runs only while Distant Horizons 3.3.4 runs: the engine calls it only when
 * {@link dev.mcms.shaders.runtime.distant.DistantHorizonsRuntime#active()}, and the mixins that call it are gated to that version
 * ({@code McmsMetalMixinPlugin}).
 *
 * <p>DH's two terrain calls are the vanilla ones, moved by the engine. Vanilla, {@code MixinLevelRenderer.prepareTranslucents}'s head
 * calls {@code renderLods} (LevelRenderer.lambda$addMainPass$0, bytecode 93: before the solid pass opens at 156 and draws at 174), and
 * {@code executeOutline}'s head calls {@code renderDeferredLodsForShaders} (bytecode 248: after the solid world). While the engine serves
 * DH, {@link #suppressesPostSolid} cancels both, and the engine makes them itself at its hooks:
 * <ul>
 * <li>{@link #renderTerrain}: DH's opaque terrain, from {@code EngineFrame.opaqueHook}'s position (after the sky copies, before the
 * solid pass draws). Transparency is deferred ({@code DhApi.Delayed.renderProxy}'s switch), so the call draws the opaque layer only.</li>
 * <li>{@link #renderTransparent}: DH's deferred call, from {@code EngineFrame.phaseHook} (Iris's order: the water draws after the solid
 * world and before the game's translucent draws and the pack's translucent flips, with the game's pass suspended around it). On the
 * fallback frames (a phase hook that did not run) it runs from the engine's after-level hook instead, where the water blends over the
 * game's translucents.</li>
 * </ul>
 * and {@link #onTerrainRender} (the render head of each terrain pass) tells the engine which pass is which, so the redirect picks the phase.
 */
public final class DistantHorizonsDriver {

	private static final Logger LOG = LoggerFactory.getLogger("Quicksilver");

	/** Whether the engine's own DH call is in progress (that call is not suppressed). Render thread only. */
	private static boolean engineCall;
	private static boolean stateFailureLogged;
	private static boolean clearFailureLogged;

	private DistantHorizonsDriver() {
	}

	/** Whether DH's post-solid terrain calls are suppressed now: the engine serves DH, and the call is not the engine's own. */
	public static boolean suppressesPostSolid() {
		return !engineCall && Engine.get().servesDistant();
	}

	/**
	 * DH's opaque terrain of the frame (plan.md E7, decision 1). Returns false when DH is not ready to render this frame (its reason is
	 * logged once); the frame then has no DH terrain.
	 */
	public static boolean renderTerrain(DistantFrame frame) {
		return renderLayer(frame, false);
	}

	/**
	 * DH's transparent terrain of the frame (plan.md E7, decision 6): DH's deferred call. Returns false when DH is not ready to render.
	 */
	public static boolean renderTransparent(DistantFrame frame) {
		return renderLayer(frame, true);
	}

	private static boolean renderLayer(DistantFrame frame, boolean transparent) {
		// The view rotation is the game's while the opaque call runs, inside the level. The transparent call runs in the phase hook (or,
		// on a fallback frame, after the level in Engine.afterLevel): either way the game's pass is suspended, and the call's draws take
		// the rotation and DH's model-view state the opaque call left (the transparent call runs only after the opaque one drew this frame).
		if (!transparent) {
			frame.setViewRotation(RenderSystem.getModelViewStack());
			ClientApi.RENDER_STATE.mcModelViewMatrix = McObjectConverter.convert(RenderSystem.getModelViewStack());
		}
		DhRenderState state = ClientApi.RENDER_STATE;
		try {
			state.canRenderOrThrow();
		} catch (IllegalStateException notReady) {
			// DH cannot render this frame (its own reason is in the exception): the engine's DH terrain is skipped, as DH skips it.
			if (!stateFailureLogged) {
				stateFailureLogged = true;
				LOG.info("[mcms-shaders] Distant Horizons is not ready to render this frame ({}); its terrain is skipped",
					notReady.getMessage());
			}
			return false;
		}
		var proxy = DhApi.Delayed.renderProxy;
		boolean previousDefer = proxy.getDeferTransparentRendering();
		proxy.setDeferTransparentRendering(true);
		engineCall = true;
		try {
			if (transparent) {
				ClientApi.INSTANCE.renderDeferredLodsForShaders();
			} else {
				ClientApi.INSTANCE.renderLods();
			}
		} finally {
			engineCall = false;
			proxy.setDeferTransparentRendering(previousDefer);
		}
		return true;
	}

	/**
	 * DH's terrain render head (the mixin on {@code BlazeDhTerrainRenderer.render}). Each pass tells the engine which DH pass the render pass
	 * that follows is, so the redirect picks the phase: the opaque one draws into the DISTANT phase, the transparent one into TRANSLUCENT.
	 * The opaque pass also hands the engine DH's projection and clip planes for the frame's {@code QsDh} block.
	 */
	public static void onTerrainRender(RenderParams params, boolean opaque) {
		Engine engine = Engine.get();
		engine.distantPass(opaque);
		if (opaque) {
			engine.distantProjection(params.apiCopy.dhProjectionMatrix.getValuesAsArray(), params.apiCopy.nearClipPlane,
				params.apiCopy.farClipPlane, renderDistanceBlocks());
		}
	}

	/** DH's model position of the draw about to run (the mixin on the event parameter's update, once per draw). */
	public static void onDrawOffset(float x, float y, float z) {
		Engine.get().distantDrawOffset(x, y, z);
	}

	/**
	 * Drops DH's cached render data ({@code IDhApiRenderProxy.clearRenderDataCache}, DH 3.3.4), so DH builds its LOD buffers again: called
	 * when the face shade DH bakes into its colours changes (TerrainEngine.distantFlatShade, DistantHorizonsShadeMixin). It runs at the head
	 * of Sodium's terrain setup, so DH's refusal or a throw from DH is logged once and the frame goes on.
	 */
	public static void clearRenderData() {
		try {
			var result = DhApi.Delayed.renderProxy.clearRenderDataCache();
			if (!result.success && !clearFailureLogged) {
				clearFailureLogged = true;
				LOG.info("[mcms-shaders] Distant Horizons kept its render data ({}); LOD colours built before keep their shade", result.message);
			}
		} catch (RuntimeException failure) {
			if (!clearFailureLogged) {
				clearFailureLogged = true;
				LOG.warn("[mcms-shaders] Distant Horizons' render data clear failed; LOD colours built before keep their shade", failure);
			}
		}
	}

	/** DH's render distance in blocks: its radius in chunks, 16 blocks each. */
	private static int renderDistanceBlocks() {
		return Config.Client.Advanced.Graphics.Quality.lodChunkRenderDistanceRadius.get() * 16;
	}
}
