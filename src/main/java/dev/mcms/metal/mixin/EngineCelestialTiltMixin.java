// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.metal.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.mojang.renderpearl.api.commands.RenderPass;
import dev.mcms.shaders.runtime.Engine;
import net.minecraft.client.renderer.SkyRenderer;
import net.minecraft.world.level.MoonPhase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tilts the game's sun, moon and stars by the pack's {@code sunPathRotation} while the engine serves a set (E7), so the
 * vanilla celestial bodies a pack draws in {@code gbuffers_skytextured} travel the same path as the engine's sunPosition,
 * moonPosition and shadow-light uniforms (FrameUniforms: the world direction (cos t, sin t cos p, -sin t sin p)).
 *
 * <p>javap of minecraft-merged-deobf-26.3.jar (2026-11-15): {@code SkyRenderer.renderSunMoonAndStars(RenderPass, PoseStack,
 * float, float, float, MoonPhase, float, float)} pushes the pose, turns it with {@code rotateDegrees(Axis.YP, -90)} (its only
 * {@code rotateDegrees} call, bytecode 11), then turns each body with {@code rotate(Axis.XP, angle)} before drawing it at
 * the local +Y. The tilt goes between the two: the pose becomes Ry(-90) Rz(p) Rx(a), which carries a body's local +Y to
 * (-sin a, cos a cos p, -cos a sin p), the direction FrameUniforms gives (and the one BSL's sunmoon.glsl rebuilds). OptiFine's
 * documentation names the constant "sun path rotation"; the axis is this derivation's.
 *
 * <p>Inert while no set is active or the set's tilt is 0 ({@link Engine#celestialTilt()}). An engine mixin
 * ({@code McmsMetalMixinPlugin}); Vitrail cannot load beside Quicksilver: fabric.mod.json breaks it (E10).
 */
@Mixin(SkyRenderer.class)
abstract class EngineCelestialTiltMixin {

	@Inject(method = "renderSunMoonAndStars", at = @At(value = "INVOKE",
		target = "Lcom/mojang/blaze3d/vertex/PoseStack;rotateDegrees(Lcom/mojang/math/Axis;F)V", ordinal = 0,
		shift = At.Shift.AFTER))
	private void mcms$tiltCelestialPath(RenderPass pass, PoseStack pose, float a, float b, float c, MoonPhase phase,
			float d, float e, CallbackInfo ci) {
		float tilt = Engine.get().celestialTilt();
		if (tilt != 0f) {
			pose.rotateDegrees(Axis.ZP, tilt);
		}
	}
}
