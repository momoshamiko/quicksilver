/*
 * Quicksilver: original code (ADR 0006), Apache-2.0. The shader engine's resource-reload hook (plan.md E3, "Threads").
 */
package dev.mcms.metal.mixin;

import dev.mcms.shaders.runtime.Engine;
import net.minecraft.client.GameLoadCookie;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The engine's third hook: the completion of a successful resource reload (javap against
 * minecraft-merged-deobf-26.3.jar, 2026-11-13). {@code Minecraft.onResourceLoadFinished(GameLoadCookie)V} is called
 * from exactly two places: {@code lambda$reloadResourcePacks$2} at bytecode 29 (an in-game reload that succeeded —
 * F3+T, the pack screens or a language switch; the lambda also finishes the reload tracker and completes the reload
 * future) and {@code lambda$new$5} at bytecode 19 (the game's first load). Both continuations run through
 * {@code LoadingOverlay.tick()}, which calls the completion consumer ({@code Consumer.accept}, bytecodes 46 and 63) on
 * the main render thread. The method's own body acts on the first load only ({@code gameLoadFinished} flag, bytecode
 * 0-4); the HEAD injection fires on every successful completion, which is what the engine needs: a failed reload rolls
 * the packs back ({@code lambda$reloadResourcePacks$1}), so the engine keeps what it has until a reload succeeds.
 *
 * <p>There the engine drops everything it holds and reads the pack's files again on its next frame: a changed shader
 * pack on disk shows after F3+T without a game restart (plan.md E3's release rule: resource reload releases targets
 * and pipelines). Inert while the engine is off: one state check.
 */
@Mixin(Minecraft.class)
abstract class EngineResourceReloadMixin {
	@Inject(method = "onResourceLoadFinished", at = @At("HEAD"))
	private void mcms$engineResourceReloadFinished(GameLoadCookie cookie, CallbackInfo ci) {
		// The engine skips the first call (the game's first load): a reload restarts its loads (plan.md E3).
		Engine.get().onResourceLoadFinished();
	}
}
