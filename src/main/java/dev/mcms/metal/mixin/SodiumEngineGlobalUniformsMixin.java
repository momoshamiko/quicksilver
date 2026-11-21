// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.metal.mixin;

import dev.mcms.shaders.runtime.terrain.TerrainEngine;
import dev.mcms.shaders.runtime.terrain.TerrainUniforms;
import java.nio.ByteBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The u_Globals mirror of the terrain pipeline (plan.md E4, ADR 0007 decision 3; the E4-A contract of 2026-11-14): the
 * engine's translated terrain pipelines read the global block at buffer slot 21, and this hook keeps the engine's
 * copy current exactly where Sodium writes its own. {@code GlobalUniforms.write(ByteBuffer)} fills the block's bytes
 * into the buffer argument (projection and model-view, fog colour and ranges, texel size and shrink, fade and RGSS,
 * the camera offset on beta.5). The TAIL hook copies the just-written bytes, only while a set is active.
 *
 * <p>Frequency: {@code write} runs once per frame. Sodium's {@code UniformBufferManager.update} returns at once when
 * its {@code hasUpdatedThisFrame} flag is already set (javap 2026-11-14, both jars: the flag is tested at the head of
 * {@code update}), and the uniform record is written from that one update (E5 may rely on it).
 *
 * <p>Javap 2026-11-14, both jars ADR 0007 allows: the class is package-private
 * ({@code net.caffeinemc.mods.sodium.client.render.chunk.UniformBufferManager$GlobalUniforms}), so the mixin names it
 * as a target string; the method is {@code write(Ljava/nio/ByteBuffer;)V} with the same descriptor in
 * {@code g6ju16VJ} and {@code v7Lw0h1m} (the record's constructor gains the camera offset fields on beta.5, which
 * changes nothing for the injection; the enclosing {@code UniformBufferManager.update} is the member ADR 0007's
 * adapter wraps).
 */
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.UniformBufferManager$GlobalUniforms", remap = false)
abstract class SodiumEngineGlobalUniformsMixin {
	@Inject(method = "write", at = @At("TAIL"))
	private void mcmsMetal$mirrorGlobals(ByteBuffer bytes, CallbackInfo ci) {
		if (TerrainEngine.active()) {
			TerrainUniforms.globalsWritten(bytes);
		}
	}
}
