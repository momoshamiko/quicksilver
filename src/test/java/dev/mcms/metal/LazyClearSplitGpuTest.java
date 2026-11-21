// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.metal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.textures.GpuTexture;
import dev.mcms.shaders.metal.HeadlessGpu;
import java.util.ArrayList;
import java.util.List;
import org.joml.Vector4f;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * A lazy-clears split reopens the open pass on the attachments of the last split (E4 review item 10). The game's pass
 * draws into A; the engine's phase split moves the pass onto B; then a texture with a deferred clear is sampled inside
 * the pass, which splits the pass around writing that clear and reopens it. The reopen must target B, where the game's
 * draws continue; before the fix it reopened on A. Observed through the encoder trace, which names the colour handles of
 * every render encoder the backend begins.
 */
@Tag("gpu")
class LazyClearSplitGpuTest {

	private static final int W = 64, H = 64;

	private record Begin(String why, long[] colors) {
	}

	@Test
	void theReopenedPassUsesTheAttachmentsOfTheLastSplit() {
		List<Begin> begins = new ArrayList<>();
		MetalHooks.EncoderTrace trace = new MetalHooks.EncoderTrace() {
			@Override
			public void begin(String why, int mode, boolean endedPrevious, long[] colors, int[] loads, long depth,
					boolean clearDepth) {
				begins.add(new Begin(why, colors));
			}

			@Override
			public void split(String why, long texture) {
			}

			@Override
			public void restore(String pipeline) {
			}
		};
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			MetalEncoder encoder = new MetalEncoder(gpu.context(), true);
			MetalTexture.View a = view(encoder, gpu.context(), "a");
			MetalTexture.View b = view(encoder, gpu.context(), "b");
			MetalTexture sampled = view(encoder, gpu.context(), "sampled").metalTexture();
			sampled.pendingColorClear = new Vector4f(0, 0, 0, 1);

			MetalHooks.encoderTrace = trace;
			try {
				RenderPassDescriptor pass = RenderPassDescriptor.builder(() -> "lazy split")
					.withColorAttachment(a)
					.withRenderArea(new RenderPass.RenderArea(0, 0, W, H))
					.build();
				encoder.createRenderPass(pass);
				encoder.splitRenderPass(new long[] { b.handle }, null, 0, false, 0f, W, H);
				encoder.flushClearInPass(sampled);
			} finally {
				MetalHooks.encoderTrace = null;
			}

			Begin reopen = null;
			for (Begin begin : begins) {
				if (begin.why().equals("restoreOpen")) {
					reopen = begin;
				}
			}
			assertNotNull(reopen, "the sampled clear must split the pass");
			assertEquals(b.handle, reopen.colors()[0], "the reopened pass must target the split's attachment, not the old one");
		}
	}

	/** A render-target texture of the encoder's context, and its whole-texture view. */
	private static MetalTexture.View view(MetalEncoder encoder, long ctx, String label) {
		long handle = MetalBridge.newTexture(ctx, GpuFormat.RGBA8_UNORM, W, H, true);
		MetalTexture texture = new MetalTexture(encoder, handle, GpuTexture.USAGE_RENDER_ATTACHMENT, label,
			GpuFormat.RGBA8_UNORM, W, H, 1, 1);
		return new MetalTexture.View(encoder, texture, 0, 1);
	}
}
