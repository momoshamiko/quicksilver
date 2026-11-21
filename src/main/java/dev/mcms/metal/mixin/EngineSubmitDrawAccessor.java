/*
 * Quicksilver: original code (ADR 0006), Apache-2.0. The per-entity id capture's view into a staged draw.
 */
package dev.mcms.metal.mixin;

import com.mojang.renderpearl.api.vertex.VertexFormat;
import net.minecraft.client.renderer.StagedVertexBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * A draw's vertex format ({@code StagedVertexBuffer$Draw.format}, private final — javap -p against
 * minecraft-merged-deobf-26.3.jar, 2026-11-14; {@code getVertexSize()} is public on the format): the stride that
 * keys the id side buffer and slices the staging bytes into its records.
 */
@Mixin(StagedVertexBuffer.Draw.class)
public interface EngineSubmitDrawAccessor {

	@Accessor("format")
	VertexFormat mcms$format();
}
