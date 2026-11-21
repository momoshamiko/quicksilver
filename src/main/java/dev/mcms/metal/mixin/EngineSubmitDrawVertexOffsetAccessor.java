/*
 * Quicksilver: original code (ADR 0006), Apache-2.0. The per-entity id capture's view into a staged draw.
 */
package dev.mcms.metal.mixin;

import net.minecraft.client.renderer.StagedVertexBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * A draw's vertex offset in the uploaded vertex buffer ({@code StagedVertexBuffer$Draw.vertexOffset}, private int,
 * assigned at {@code upload()} bytecode 93 as {@code Mth.roundToward(running, stride)} — javap -p against
 * minecraft-merged-deobf-26.3.jar, 2026-11-14): the side buffer's base vertex for this draw is
 * {@code vertexOffset / stride}, what the draw's {@code [[vertex_id]]} indexes.
 */
@Mixin(StagedVertexBuffer.Draw.class)
public interface EngineSubmitDrawVertexOffsetAccessor {

	@Accessor("vertexOffset")
	int mcms$vertexOffset();
}
