/*
 * Quicksilver: original code (ADR 0006), Apache-2.0. The item half of the per-entity id capture (plan.md E4,
 * "Per-entity values"): the bracket that learns which item a render state holds.
 */
package dev.mcms.metal.mixin;

import dev.mcms.shaders.runtime.entities.EntityCapture;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ItemOwner;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Records the item of every item render state the resolver fills ({@code currentRenderedItemId},
 * engine-pack-format.md section 10: the item being rendered). The resolver is the one choke point every item path
 * passes with the stack — {@code submitItem} itself carries only {@code ItemQuads} (javap of
 * {@code SubmitNodeCollection.submitItem}). javap -p -c against minecraft-merged-deobf-26.3.jar (2026-11-14):
 * {@code ItemClusterRenderState.extractItemGroupRenderState} calls {@code updateForNonLiving} at bytecode 10
 * (dropped item entities), {@code ItemFrameRenderer} at bytecode 35, {@code ShelfRenderer} fills its shelf states
 * with {@code updateForTopItem} at bytecode 117, {@code LivingEntityRenderer} calls {@code updateForLiving} (mob
 * held items and armour), and {@code FirstPersonHandsAndItems} calls {@code updateForTopItem} at bytecode 243 and
 * 278 (the held item for the hand; its renderer then calls {@code ItemStackRenderState.submit}, javap of
 * {@code FirstPersonHandsAndItemsRenderer}). The state is argument 0 and the stack argument 1 in all four methods.
 * Inert while no set is active: one volatile read.
 */
@Mixin(ItemModelResolver.class)
abstract class EngineSubmitItemModelResolverMixin {

	@Inject(method = "updateForLiving", at = @At("HEAD"))
	private void mcms$item(ItemStackRenderState state, ItemStack stack, ItemDisplayContext context,
			LivingEntity entity, CallbackInfo ci) {
		if (EntityCapture.active()) {
			EntityCapture.itemState(state, stack);
		}
	}

	@Inject(method = "updateForNonLiving", at = @At("HEAD"))
	private void mcms$itemNonLiving(ItemStackRenderState state, ItemStack stack, ItemDisplayContext context,
			Entity entity, CallbackInfo ci) {
		if (EntityCapture.active()) {
			EntityCapture.itemState(state, stack);
		}
	}

	@Inject(method = "updateForTopItem", at = @At("HEAD"))
	private void mcms$item(ItemStackRenderState state, ItemStack stack, ItemDisplayContext context, Level level,
			ItemOwner owner, int seed, CallbackInfo ci) {
		if (EntityCapture.active()) {
			EntityCapture.itemState(state, stack);
		}
	}

	@Inject(method = "appendItemLayers", at = @At("HEAD"))
	private void mcms$itemLayers(ItemStackRenderState state, ItemStack stack, ItemDisplayContext context, Level level,
			ItemOwner owner, int seed, CallbackInfo ci) {
		if (EntityCapture.active()) {
			EntityCapture.itemState(state, stack);
		}
	}
}
