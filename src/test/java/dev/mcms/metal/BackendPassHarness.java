// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.metal;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout.UniformDescription;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.backend.api.RenderPassBackend;
import com.mojang.renderpearl.frontend.FrontendCommandEncoder;
import com.mojang.renderpearl.frontend.FrontendGpuDevice;
import java.util.List;

/**
 * The backend's own pass objects for a GPU test outside this package (2026-11-14, E4 clouds): {@link MetalRenderPass}
 * and {@link MetalEncoder} are package-private, and a test that must go through the backend's real render-pass entry
 * points (as {@code FrontendRenderPass} drives them in the game) needs exactly those.
 */
public final class BackendPassHarness {

	private BackendPassHarness() {
	}

	/** A backend encoder on the given context (its native encoder handle with {@link #encoderHandle}). */
	public static Object newEncoder(long ctx) {
		return new MetalEncoder(ctx);
	}

	/** The native encoder handle the bridge's render helpers take. */
	public static long encoderHandle(Object encoder) {
		return ((MetalEncoder) encoder).enc;
	}

	/**
	 * A render pass on the encoder, with {@code delegate} taking over its pipelines (what
	 * {@code MetalEncoder.createRenderPass} returns for a redirected pass; the caller opened the render encoder).
	 */
	public static RenderPassBackend openPass(Object encoder, boolean hasDepth, int width, int height,
			PassDelegate delegate) {
		return new MetalRenderPass((MetalEncoder) encoder, hasDepth, 0, 0, width, height, delegate, 0, width, height, 1f, 1f);
	}

	/**
	 * A pass descriptor with one colour attachment: a new render-target texture of the context, {@code width} by
	 * {@code height}, on the encoder (its view is what the backend's pass takes as the main colour).
	 */
	public static RenderPassDescriptor passDescriptor(CommandEncoderBackend encoder, long ctx, String label, int width,
			int height) {
		long handle = MetalBridge.newTexture(ctx, GpuFormat.RGBA8_UNORM, width, height, true);
		MetalTexture texture = new MetalTexture((MetalEncoder) encoder, handle, GpuTexture.USAGE_RENDER_ATTACHMENT, label,
			GpuFormat.RGBA8_UNORM, width, height, 1, 1);
		MetalTexture.View view = new MetalTexture.View((MetalEncoder) encoder, texture, 0, 1);
		return RenderPassDescriptor.builder(() -> label).withColorAttachment(view)
			.withRenderArea(new RenderPass.RenderArea(0, 0, width, height)).build();
	}

	/** A backend pipeline object over Metal states a test built (see {@link MetalPipeline#withStates}). */
	public static BackendRenderPipeline pipelineWithStates(String name, List<UniformDescription> uniforms, long withDepth,
			long withoutDepth) {
		return MetalPipeline.withStates(name, uniforms, withDepth, withoutDepth);
	}

	/** {@link #pipelineWithStates} with each uniform's sampler slot per stage (-1: that stage samples none). */
	public static BackendRenderPipeline pipelineWithStates(String name, List<UniformDescription> uniforms, long withDepth,
			long withoutDepth, int[] vertexSamplers, int[] fragmentSamplers) {
		return MetalPipeline.withStates(name, uniforms, withDepth, withoutDepth, vertexSamplers, fragmentSamplers);
	}

	/**
	 * A backend pipeline object with the name and uniform list the delegate maps (no GPU state: the delegate binds its
	 * own pipeline state; see {@code MetalPipeline.detached}).
	 */
	public static BackendRenderPipeline detachedPipeline(String name, List<UniformDescription> uniforms) {
		return MetalPipeline.detached(name, uniforms);
	}

	/**
	 * A real frontend {@code GpuDevice} on the Metal backend (its own context, no window): for tests that drive the
	 * game's own render classes — {@code RenderSystem.initRenderer}, the staged vertex buffers — which reach the GPU
	 * only through {@code RenderSystem.getDevice()} (the block-entity split's two-staged-buffer test, 2026-11-14).
	 */
	public static com.mojang.renderpearl.api.device.GpuDevice newDevice() throws Exception {
		return new MetalBackend().createDevice(new com.mojang.renderpearl.api.device.GpuDebugOptions(0, false, false, false));
	}

	/** The native context of a device from {@link #newDevice}: the textures it makes and the encoders on it share it. */
	public static long contextOf(GpuDevice device) {
		FrontendCommandEncoder command = (FrontendCommandEncoder) ((FrontendGpuDevice) device).createCommandEncoder();
		return MetalBridge.ctx(MetalBridge.encoder(command.backend()));
	}
}
