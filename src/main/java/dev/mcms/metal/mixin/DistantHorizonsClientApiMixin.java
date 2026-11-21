// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.metal.mixin;

import com.seibel.distanthorizons.core.api.internal.ClientApi;
import dev.mcms.metal.distant.DistantHorizonsDriver;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Suppresses Distant Horizons' two terrain calls while the engine serves DH's terrain through the pack (plan.md E7, decision 1). Without a
 * pack, or with a pack without {@code dh_terrain}, the calls run as they always did.
 *
 * <ul>
 * <li>{@code renderLods}: called from the head of {@code MixinLevelRenderer.prepareTranslucents}, which the main pass calls before its
 * solid pass opens (LevelRenderer.lambda$addMainPass$0, bytecode 93 before 156). The engine makes that call at its opaque hook instead
 * ({@link DistantHorizonsDriver#renderTerrain}), which the suppression lets through.</li>
 * <li>{@code renderDeferredLodsForShaders}: called from {@code executeOutline}'s head, after the solid world (bytecode 248). The engine
 * makes that call itself in its phase hook ({@link DistantHorizonsDriver#renderTransparent}, Iris's order), or after the level on a
 * fallback frame, and the suppression lets its own calls through.</li>
 * </ul>
 * {@code renderFadeOpaque} and {@code renderFadeTransparent} are not suppressed: DH's far fade runs on DH's own colour, as before (plan.md
 * E7, decision 7).
 */
@Mixin(ClientApi.class)
abstract class DistantHorizonsClientApiMixin {

	@Inject(method = "renderLods", at = @At("HEAD"), cancellable = true)
	private void mcmsMetal$suppressOpaqueTerrainCall(CallbackInfo ci) {
		if (DistantHorizonsDriver.suppressesPostSolid()) {
			ci.cancel();
		}
	}

	@Inject(method = "renderDeferredLodsForShaders", at = @At("HEAD"), cancellable = true)
	private void mcmsMetal$suppressDeferredTerrainCall(CallbackInfo ci) {
		if (DistantHorizonsDriver.suppressesPostSolid()) {
			ci.cancel();
		}
	}
}
