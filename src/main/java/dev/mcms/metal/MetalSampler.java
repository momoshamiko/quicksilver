/*
 * Derived from mcopt v0.3.0-alpha.2 (https://github.com/noahdunnagan/mcopt), metal/src/main/java/mcopt/metal/MetalSampler.java.
 * Copyright Noah Dunnagan, Apache License 2.0. Changed by MC Metal Shaders, 2026-10-11: package renamed to dev.mcms.metal.
 */
package dev.mcms.metal;

import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import java.util.OptionalDouble;
import org.jspecify.annotations.Nullable;

final class MetalSampler implements GpuSampler {
	private final @Nullable MetalEncoder encoder;
	private final long handle;
	private final AddressMode addressModeU;
	private final AddressMode addressModeV;
	private final FilterMode minFilter;
	private final FilterMode magFilter;
	private final int maxAnisotropy;
	private final OptionalDouble maxLod;
	private boolean closed;

	private MetalSampler(@Nullable MetalEncoder encoder, long handle, AddressMode addressModeU, AddressMode addressModeV,
			FilterMode minFilter, FilterMode magFilter, int maxAnisotropy, OptionalDouble maxLod) {
		this.encoder = encoder;
		this.handle = handle;
		this.addressModeU = addressModeU;
		this.addressModeV = addressModeV;
		this.minFilter = minFilter;
		this.magFilter = magFilter;
		this.maxAnisotropy = maxAnisotropy;
		this.maxLod = maxLod;
	}

	static MetalSampler create(MetalEncoder encoder, AddressMode u, AddressMode v, FilterMode min, FilterMode mag, int anisotropy, OptionalDouble maxLod) {
		return createInternal(encoder.ctx, encoder, u, v, min, mag, anisotropy, maxLod);
	}

	static MetalSampler create(long ctx, AddressMode u, AddressMode v, FilterMode min, FilterMode mag, int anisotropy, OptionalDouble maxLod) {
		return createInternal(ctx, null, u, v, min, mag, anisotropy, maxLod);
	}

	private static MetalSampler createInternal(long ctx, @Nullable MetalEncoder encoder, AddressMode u, AddressMode v,
			FilterMode min, FilterMode mag, int anisotropy, OptionalDouble maxLod) {
		// Same mip policy as the Vulkan backend: a max LOD at or below 0.25 means "base level only".
		double lod = maxLod.orElse(1000.0);
		int mipFilter = lod > 0.25 ? 2 : 0;
		int clampedAniso = Math.max(1, Math.min(16, anisotropy));
		long handle = Native.samplerNew(ctx, MetalConst.addressMode(u), MetalConst.addressMode(v), MetalConst.filter(min), MetalConst.filter(mag),
			mipFilter, clampedAniso, (float) Math.max(0.25, lod));
		if (handle == 0) throw new IllegalStateException("Couldn't create Metal sampler");
		return new MetalSampler(encoder, handle, u, v, min, mag, clampedAniso, maxLod);
	}

	long handle() {
		return this.handle;
	}

	@Override
	public AddressMode getAddressModeU() {
		return this.addressModeU;
	}

	@Override
	public AddressMode getAddressModeV() {
		return this.addressModeV;
	}

	@Override
	public FilterMode getMinFilter() {
		return this.minFilter;
	}

	@Override
	public FilterMode getMagFilter() {
		return this.magFilter;
	}

	@Override
	public int getMaxAnisotropy() {
		return this.maxAnisotropy;
	}

	@Override
	public OptionalDouble getMaxLod() {
		return this.maxLod;
	}

	@Override
	public boolean isClosed() {
		return this.closed;
	}

	@Override
	public void close() {
		if (!this.closed) {
			this.closed = true;
			if (this.encoder != null) this.encoder.releaseLater(this.handle);
			else Native.release(this.handle);
		}
	}
}
