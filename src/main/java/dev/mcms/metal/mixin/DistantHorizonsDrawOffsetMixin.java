// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.metal.mixin;

import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBeforeBufferRenderEvent;
import com.seibel.distanthorizons.api.objects.math.DhApiVec3f;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiRenderParam;
import dev.mcms.metal.distant.DistantHorizonsDriver;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Reads Distant Horizons' model position of each terrain draw (plan.md E7). DH's terrain render sets its model position
 * ({@code BlazeDhTerrainRenderer.MODEL_POS}, the section's corner minus the camera) and passes it to the event parameter's
 * {@code update} before it binds the draw's buffers and calls {@code drawIndexed}; the engine's draw of the pack program
 * takes its model-view from it ({@link DistantHorizonsDriver#onDrawOffset}). Nothing changes for DH's own draws.
 */
@Mixin(DhApiBeforeBufferRenderEvent.EventParam.class)
abstract class DistantHorizonsDrawOffsetMixin {

	@Inject(method = "update(Lcom/seibel/distanthorizons/api/methods/events/sharedParameterObjects/DhApiRenderParam;"
		+ "Lcom/seibel/distanthorizons/api/objects/math/DhApiVec3f;)V", at = @At("HEAD"))
	private void mcmsMetal$drawOffset(DhApiRenderParam renderParam, DhApiVec3f modelPos, CallbackInfo ci) {
		DistantHorizonsDriver.onDrawOffset(modelPos.x, modelPos.y, modelPos.z);
	}
}
