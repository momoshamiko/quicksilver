// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.metal;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.util.TextureViewAndSampler;
import dev.mcms.shaders.metal.ShaderNative;
import java.nio.ByteBuffer;
import java.util.OptionalDouble;

/**
 * The resources a GPU test binds to a render pass the way the game does: a depth attachment, a vertex or index buffer,
 * a sampled texture with its sampler. The backend's classes are package-private, so a test outside this package reaches
 * them through here.
 */
public final class PhaseSplitResources {

	/** MTLPixelFormatRGBA8Unorm: the pixel format number ShaderNative takes. */
	private static final int RGBA8_UNORM = 70;

	private PhaseSplitResources() {
	}

	/** A depth attachment of the context: D32_FLOAT, a render target. */
	public static long depthTexture(long ctx, int width, int height) {
		return MetalBridge.newTexture(ctx, GpuFormat.D32_FLOAT, width, height, false);
	}

	/** A buffer of the encoder's context holding the remaining bytes of {@code data}. */
	public static GpuBuffer buffer(Object encoder, long ctx, ByteBuffer data) {
		ByteBuffer direct = ByteBuffer.allocateDirect(data.remaining()).put(data.duplicate()).flip();
		GpuBuffer buffer = MetalBridge.newGpuBuffer(encoder, ctx, direct.remaining());
		((MetalEncoder) encoder).writeToBuffer(buffer.slice(0, direct.remaining()), direct);
		return buffer;
	}

	/** A sampled texture of the context holding one RGBA8 texel, with a nearest sampler: one texture binding. */
	public static TextureViewAndSampler sampled(Object encoder, long ctx, byte[] rgba) {
		long handle = ShaderNative.textureNew(ctx, RGBA8_UNORM, ShaderNative.TYPE_2D, 1, 1, 1, 1,
			ShaderNative.USAGE_SHADER_READ, ShaderNative.STORAGE_PRIVATE);
		if (handle == 0 || !ShaderNative.textureUpload(ctx, handle, rgba, 4, 4, 0, 0, 0, 0, 0, 1, 1, 1)) {
			throw new IllegalStateException("the device rejected the sampled texture");
		}
		MetalEncoder owner = (MetalEncoder) encoder;
		MetalTexture texture = new MetalTexture(owner, handle, GpuTexture.USAGE_RENDER_ATTACHMENT, "sampled",
			GpuFormat.RGBA8_UNORM, 1, 1, 1, 1);
		MetalTexture.View view = new MetalTexture.View(owner, texture, 0, 1);
		return new TextureViewAndSampler(view, MetalSampler.create(ctx, AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
			FilterMode.NEAREST, FilterMode.NEAREST, 1, OptionalDouble.empty()));
	}
}
