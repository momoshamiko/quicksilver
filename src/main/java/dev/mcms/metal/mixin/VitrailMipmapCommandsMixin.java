package dev.mcms.metal.mixin;

import com.mojang.renderpearl.api.textures.GpuTexture;
import dev.mcms.metal.MetalBridge;
import dev.vitrail.render.MipmapCommands;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Seam part 3 (spec v0.2 §5; gap list M0a-3, mip chains): Vitrail fills the mip chain of a pack's colour target before a
 * program reads it at a lod, through {@code MipmapCommands}, which it duck-types onto Vulkan's command encoder
 * (VulkanCommandEncoderMixin, a blit per level). Applied to this mod's own encoder, so Vitrail's
 * {@code backend instanceof MipmapCommands} holds on Metal too; without it, Vitrail binds those targets at level 0 and
 * BSL's exposure reads one pixel (results/2026-10-11-m0c-dims, finding 3). Applied only when Vitrail is loaded.
 */
@Mixin(targets = "dev.mcms.metal.MetalEncoder")
abstract class VitrailMipmapCommandsMixin implements MipmapCommands {
	@Override
	public boolean vitrail$generateMipmaps(GpuTexture texture) {
		return MetalBridge.generateMipmaps(this, texture);
	}

	/**
	 * Vitrail's level viewport for an open pass. Nothing in Vitrail 0.12.0-beta calls it (its own Vulkan implementation is the
	 * only reference), and this backend's passes take their viewport from the attachment, so it answers false: "this backend
	 * cannot", which the interface documents as leaving the pass's viewport as it was.
	 */
	@Override
	public boolean vitrail$viewport(int width, int height) {
		return false;
	}
}
