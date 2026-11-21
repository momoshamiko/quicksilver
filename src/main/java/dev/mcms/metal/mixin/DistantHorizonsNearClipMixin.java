// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.metal.mixin;

import com.seibel.distanthorizons.core.util.RenderUtil;
import dev.mcms.shaders.runtime.Engine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * Distant Horizons' near clip while the engine draws DH through a pack (plan.md E7, DH decision 13): the value DH takes when a shader
 * pack is in use. {@code RenderUtil.getNearClipPlaneInBlocks} (javap of DH 3.3.4, 2026-11-16), with its overdraw prevention on auto,
 * answers 0.2 when its Iris accessor reports a pack in use, and otherwise by render distance: 0.2 up to 2 chunks, then 0.3, 0.6, 0.8
 * and 0.9 past 10. Without Iris that accessor is absent, so under the engine DH took 0.9 at the bench's render distance where it takes
 * 0.2 under Iris with the same pack. The rule rests on that code; the one run with 0.2 set in DH's config (results/2026-11-16-e7-dhexp)
 * moved the DH pictures by less than their run-to-run noise. The render-distance answers become 0.2 while the engine serves DH; a set
 * overdraw prevention (DH's config, any value from 0) and the fast-movement reduction after it are untouched.
 */
@Mixin(value = RenderUtil.class, remap = false)
abstract class DistantHorizonsNearClipMixin {

	@ModifyConstant(method = "getNearClipPlaneInBlocks", constant = { @Constant(floatValue = 0.3f), @Constant(floatValue = 0.6f),
		@Constant(floatValue = 0.8f), @Constant(floatValue = 0.9f) })
	private static float mcmsMetal$packNearClip(float byRenderDistance) {
		return Engine.get().servesDistant() ? 0.2f : byRenderDistance;
	}
}
