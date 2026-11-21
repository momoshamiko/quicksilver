/*
 * Quicksilver: original code (ADR 0006), Apache-2.0. The per-entity id capture's view into a block entity state.
 */
package dev.mcms.metal.mixin;

import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The block a block entity renders ({@code BlockEntityRenderState.blockState}, private — javap -p against
 * minecraft-merged-deobf-26.3.jar, 2026-11-14; {@code blockPos} and {@code blockEntityType} are public but name the
 * type, and {@code blockEntityId} matches the block.properties entry of the block, with its state properties).
 */
@Mixin(BlockEntityRenderState.class)
public interface EngineSubmitBlockEntityStateAccessor {

	@Accessor("blockState")
	BlockState mcms$blockState();
}
