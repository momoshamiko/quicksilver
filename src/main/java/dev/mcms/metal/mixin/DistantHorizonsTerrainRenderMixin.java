// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.metal.mixin;

import com.seibel.distanthorizons.common.render.blaze.BlazeDhTerrainRenderer;
import com.seibel.distanthorizons.core.render.RenderParams;
import com.seibel.distanthorizons.core.util.objects.SortedArraySet;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IProfilerWrapper;
import dev.mcms.metal.distant.DistantHorizonsDriver;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The head of Distant Horizons' terrain render ({@code BlazeDhTerrainRenderer.render(RenderParams, boolean, SortedArraySet,
 * IProfilerWrapper)}, javap 2026-11-15; the boolean is the opaque pass). The handler tells the engine which pass is starting
 * ({@link DistantHorizonsDriver#onTerrainRender}), and takes DH's projection from the opaque pass. It does nothing while the engine does not
 * serve DH, so DH's own rendering is unchanged then.
 */
@Mixin(BlazeDhTerrainRenderer.class)
abstract class DistantHorizonsTerrainRenderMixin {

	@Inject(method = "render", at = @At("HEAD"))
	private void mcmsMetal$distantTerrainRender(RenderParams params, boolean opaque, SortedArraySet<?> containers,
			IProfilerWrapper profiler, CallbackInfo ci) {
		DistantHorizonsDriver.onTerrainRender(params, opaque);
	}
}
