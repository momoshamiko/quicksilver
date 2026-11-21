// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.metal.mixin;

import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The engine's replacement of the second {@code FeatureRenderDispatcher}'s staged buffer (plan.md E4, "Hand",
 * buffers bullet). {@code FeatureRenderDispatcher}'s constructor takes a {@code RenderBuffers} and only stores its
 * {@code stagedVertexBuffer()} (the call at bytecode 51, the store at 54; javap of minecraft-merged-deobf-26.3.jar,
 * 2026-11-14); the field is {@code private final StagedVertexBuffer stagedVertexBuffer}, so the swap needs a
 * {@code @Mutable} accessor. The engine passes the game's {@code RenderBuffers} (no second section buffer pack and
 * pool) and then puts its own {@code StagedVertexBuffer(label, 4194304)} in the field
 * ({@code dev.mcms.shaders.runtime.hand.HandRenderer}).
 */
@Mixin(FeatureRenderDispatcher.class)
public interface EngineHandFeatureDispatcherAccessor {
	@Mutable
	@Accessor("stagedVertexBuffer")
	void mcms$setStagedVertexBuffer(StagedVertexBuffer buffer);
}
