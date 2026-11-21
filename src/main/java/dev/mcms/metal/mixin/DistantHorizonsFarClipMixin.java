// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.metal.mixin;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.core.util.RenderUtil;
import dev.mcms.shaders.runtime.Engine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Distant Horizons' far clip while the engine draws DH through a pack (RIL-563): the value DH takes beside Iris.
 * {@code RenderUtil.getFarClipPlaneDistanceInBlocks} (javap of DH 3.3.4, 2026-11-19) answers
 * {@code (chunkRenderDistance x 16 + 512) x sqrt(2)} when its Iris accessor is set, and
 * {@code (lodChunkRenderDistanceRadius x 16 + 512) x 2} without it. Measured with the probe pack {@code mcms-dhproj}
 * (results/2026-11-19-ril563-dhproj): beside Iris, DH's draw has its far plane at 6515.8 and {@code dhFarPlane} reads
 * 6516.69, the first answer at DH's 256-chunk distance. The engine leaves the accessor unset, so DH answers the second
 * [inference from the javap]. While the engine serves DH this answers as DH answers beside Iris, through the same API
 * value; otherwise DH's own code runs.
 */
@Mixin(value = RenderUtil.class, remap = false)
abstract class DistantHorizonsFarClipMixin {

	@Inject(method = "getFarClipPlaneDistanceInBlocks", at = @At("HEAD"), cancellable = true)
	private static void mcmsMetal$packFarClip(CallbackInfoReturnable<Float> cir) {
		if (Engine.get().servesDistant()) {
			int chunks = DhApi.Delayed.configs.graphics().chunkRenderDistance().getValue();
			cir.setReturnValue((float) ((chunks * 16 + 512) * Math.sqrt(2.0)));
		}
	}
}
