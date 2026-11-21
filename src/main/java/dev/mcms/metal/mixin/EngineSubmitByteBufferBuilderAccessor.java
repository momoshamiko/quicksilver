/*
 * Quicksilver: original code (ADR 0006), Apache-2.0. The per-entity id capture's view into the staging buffer.
 */
package dev.mcms.metal.mixin;

import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The staging buffer's write position ({@code ByteBufferBuilder.writeOffset}, private long: the bytes handed out so
 * far — javap -p against minecraft-merged-deobf-26.3.jar, 2026-11-14). The id range bracket reads it at each vertex
 * builder request; it is the absolute byte address a draw's {@code vertexOffset} shares, so ranges recorded in these
 * bytes and a draw's {@code vertexOffset / vertexSize + k} index the same side-buffer record.
 */
@Mixin(ByteBufferBuilder.class)
public interface EngineSubmitByteBufferBuilderAccessor {

	@Accessor("writeOffset")
	long mcms$writeOffset();
}
