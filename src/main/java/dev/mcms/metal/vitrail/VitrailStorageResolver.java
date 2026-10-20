/*
 * MC Metal Shaders, Apache License 2.0. Original code (2026-10-12, M4).
 */
package dev.mcms.metal.vitrail;

import dev.mcms.metal.MetalHooks;
import dev.vitrail.render.storage.StorageBuffers;
import dev.vitrail.render.storage.StorageImages;

/**
 * Vitrail's storage images and buffers by uniform name, for MetalRenderPass (MetalHooks.storage): the names Vitrail's
 * VulkanRenderPassMixin swaps real descriptors in for on Vulkan. On Metal their bound views and buffers are Metal handles
 * (mixin.VitrailStorageImagesMixin, mixin.VitrailStorageBuffersMixin).
 */
public final class VitrailStorageResolver implements MetalHooks.StorageResolver {
	@Override
	public long image(String name) {
		StorageImages.Bound bound = StorageImages.bound(name);
		return bound == null ? 0L : bound.view();
	}

	@Override
	public long buffer(String name) {
		StorageBuffers.Bound bound = StorageBuffers.bound(name);
		return bound == null ? 0L : bound.buffer();
	}
}
