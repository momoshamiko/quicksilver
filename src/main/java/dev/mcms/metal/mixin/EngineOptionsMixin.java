/*
 * Quicksilver: original code (ADR 0006), Apache-2.0. The shader keys in the game's key list (plan.md E8, "Keys").
 */
package dev.mcms.metal.mixin;

import dev.mcms.shaders.ui.ShaderKeys;
import java.util.Arrays;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Appends the three shader keys ({@link ShaderKeys}) to {@code Options.keyMappings}, the array Controls lists and
 * {@code options.txt} saves. javap of minecraft-merged-deobf-26.3.jar (2026-11-16): {@code keyMappings} is
 * {@code public final KeyMapping[]}, and {@code Options(Minecraft, File)} sets it at bytecode 4260 and calls {@code load()} at
 * 5170, so the keys are in the array when the saved bindings load. Quicksilver has no Fabric API dependency (plan.md E8), so
 * this mixin stands in for its key-binding registry. An engine mixin (McmsMetalMixinPlugin).
 */
@Mixin(Options.class)
abstract class EngineOptionsMixin {
	@Mutable
	@Final
	@Shadow
	public KeyMapping[] keyMappings;

	@Inject(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Options;load()V"))
	private void mcms$engineShaderKeys(CallbackInfo ci) {
		KeyMapping[] ours = ShaderKeys.all();
		KeyMapping[] all = Arrays.copyOf(this.keyMappings, this.keyMappings.length + ours.length);
		System.arraycopy(ours, 0, all, this.keyMappings.length, ours.length);
		this.keyMappings = all;
	}
}
