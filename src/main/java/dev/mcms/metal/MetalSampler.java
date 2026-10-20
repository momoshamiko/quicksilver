/*
 * Derived from mcopt v0.3.0-alpha.2 (https://github.com/noahdunnagan/mcopt), metal/src/main/java/mcopt/metal/MetalSampler.java.
 * Copyright Noah Dunnagan, Apache License 2.0. Changed by MC Metal Shaders, 2026-10-11: package renamed to dev.mcms.metal.
 */
package dev.mcms.metal;

import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import java.util.OptionalDouble;

record MetalSampler(long handle, AddressMode getAddressModeU, AddressMode getAddressModeV, FilterMode getMinFilter, FilterMode getMagFilter,
	int getMaxAnisotropy, OptionalDouble getMaxLod) implements GpuSampler {

	static MetalSampler create(long ctx, AddressMode u, AddressMode v, FilterMode min, FilterMode mag, int anisotropy, OptionalDouble maxLod) {
		// Same mip policy as the Vulkan backend: a max LOD at or below 0.25 means "base level only".
		double lod = maxLod.orElse(1000.0);
		int mipFilter = lod > 0.25 ? 2 : 0;
		long handle = Native.samplerNew(ctx, MetalConst.addressMode(u), MetalConst.addressMode(v), MetalConst.filter(min), MetalConst.filter(mag),
			mipFilter, Math.max(1, anisotropy), (float) Math.max(0.25, lod));
		return new MetalSampler(handle, u, v, min, mag, anisotropy, maxLod);
	}

	// Samplers are created once and cached by the game for its lifetime.
	@Override
	public boolean isClosed() {
		return false;
	}

	@Override
	public void close() {
	}
}
