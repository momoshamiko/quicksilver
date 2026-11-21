// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.metal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.textures.GpuTexture;
import dev.mcms.shaders.metal.HeadlessGpu;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Suspending the open render pass for Distant Horizons' transparent call inside the phase hook (Iris's order). The game's pass is open
 * on A and split onto B by the phase's split; DH's pass then runs on C in its place. The suspend saves the backend's pass state, DH's
 * pass submits, and the resume puts the state back, so the game's final split reopens the pass on its own attachments.
 */
@Tag("gpu")
class DistantPassSuspendGpuTest {

	private static final int W = 64, H = 64;

	/** One encoder begin: the trace's label and its colour handles. */
	private record Begin(String why, long[] colors) {
	}

	/** A delegate that holds its splitter between begin and end, as the pack's delegates do, and switches attachment group through it. */
	private static final class GroupDelegate implements PassDelegate {
		private @Nullable PassSplitter splitter;

		@Override
		public void begin(long enc, boolean hasDepth, PassSplitter splitter) {
			this.splitter = splitter;
		}

		/** A group switch: the delegate splits the open pass onto {@code colors} through the splitter it was given at begin. */
		void switchGroup(long[] colors) {
			this.splitter.split(colors);
		}

		@Override
		public void end(long enc) {
			this.splitter = null;
		}

		@Override
		public int @Nullable [] setPipeline(long enc, Object pipeline, List<BindGroupLayout.UniformDescription> uniforms) {
			return null;
		}

		@Override
		public boolean beforeDraw(long enc) {
			return true;
		}
	}

	private static MetalTexture.View view(MetalEncoder encoder, long ctx, String label) {
		long handle = MetalBridge.newTexture(ctx, GpuFormat.RGBA8_UNORM, W, H, true);
		MetalTexture texture = new MetalTexture(encoder, handle, GpuTexture.USAGE_RENDER_ATTACHMENT, label,
			GpuFormat.RGBA8_UNORM, W, H, 1, 1);
		return new MetalTexture.View(encoder, texture, 0, 1);
	}

	private static RenderPassDescriptor pass(String label, MetalTexture.View colour) {
		return RenderPassDescriptor.builder(() -> label).withColorAttachment(colour)
			.withRenderArea(new RenderPass.RenderArea(0, 0, W, H)).build();
	}

	/** Suspends the encoder's open pass into a fresh holder. */
	private static MetalEncoder.PassSuspension suspend(MetalEncoder encoder) {
		MetalEncoder.PassSuspension saved = new MetalEncoder.PassSuspension();
		encoder.suspendPass(saved);
		return saved;
	}

	/** Records every render-encoder begin of the test (the trace's "pass" and "split" entries). */
	private static List<Begin> record(List<Begin> begins) {
		MetalHooks.encoderTrace = new MetalHooks.EncoderTrace() {
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
		return begins;
	}

	@Test
	void aSuspendedPassIsRestoredWithItsStateAndAttachments() {
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			MetalEncoder encoder = new MetalEncoder(gpu.context(), true);
			MetalTexture.View a = view(encoder, gpu.context(), "a");
			MetalTexture.View b = view(encoder, gpu.context(), "b");
			MetalTexture.View c = view(encoder, gpu.context(), "c");
			encoder.createRenderPass(pass("game", a));
			encoder.splitRenderPass(new long[] {b.handle}, null, 0, false, 0f, W, H);
			String before = encoder.passDescription();

			MetalEncoder.PassSuspension saved = suspend(encoder);
			assertFalse(encoder.inRenderPass(), "no pass is open while suspended");
			encoder.createRenderPass(pass("dh", c));
			encoder.submitRenderPass();
			assertFalse(encoder.inRenderPass(), "DH's pass submitted: still no pass");

			encoder.resumePass(saved);
			assertTrue(encoder.inRenderPass(), "the game's pass is open again");
			assertEquals(before, encoder.passDescription(), "every saved field is restored");
		}
	}

	@Test
	void aPassDrawnWhileSuspendedLeavesTheGamesStateAlone() {
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			MetalEncoder encoder = new MetalEncoder(gpu.context(), true);
			MetalTexture.View a = view(encoder, gpu.context(), "a");
			MetalTexture.View b = view(encoder, gpu.context(), "b");
			MetalTexture.View c = view(encoder, gpu.context(), "c");
			encoder.createRenderPass(pass("game", a));
			encoder.splitRenderPass(new long[] {b.handle}, null, 0, false, 0f, W, H);
			String before = encoder.passDescription();
			MetalEncoder.PassSuspension saved = suspend(encoder);
			encoder.createRenderPass(pass("dh", c));
			encoder.submitRenderPass();
			encoder.resumePass(saved);
			assertEquals(before, encoder.passDescription(), "the pass drawn while suspended changed nothing the game's pass holds");
		}
	}

	@Test
	void theFinalSplitAfterTheResumeReopensTheGamePassOnItsOwnAttachments() {
		List<Begin> begins = record(new ArrayList<>());
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			MetalEncoder encoder = new MetalEncoder(gpu.context(), true);
			MetalTexture.View a = view(encoder, gpu.context(), "a");
			MetalTexture.View b = view(encoder, gpu.context(), "b");
			MetalTexture.View c = view(encoder, gpu.context(), "c");
			MetalTexture.View d = view(encoder, gpu.context(), "d");
			try {
				encoder.createRenderPass(pass("game", a));
				encoder.splitRenderPass(new long[] {b.handle}, null, 0, false, 0f, W, H);
				MetalEncoder.PassSuspension saved = suspend(encoder);
				encoder.createRenderPass(pass("dh", c));
				encoder.submitRenderPass();
				encoder.resumePass(saved);
				// The phase hook's final split: the game's pass continues on the translucent attachments D.
				encoder.splitRenderPass(new long[] {d.handle}, null, 0, false, 0f, W, H);
				assertTrue(encoder.inRenderPass());
			} finally {
				MetalHooks.encoderTrace = null;
			}
			assertEquals(4, begins.size(), "game pass, phase split, DH pass, final split");
			assertArrayEquals(new long[] {a.handle}, begins.get(0).colors());
			assertArrayEquals(new long[] {b.handle}, begins.get(1).colors());
			assertArrayEquals(new long[] {c.handle}, begins.get(2).colors());
			assertArrayEquals(new long[] {d.handle}, begins.get(3).colors());
		}
	}

	@Test
	void aGroupSwitchAfterTheResumeUsesTheSplitterTheDelegateBeganWith() {
		List<Begin> begins = record(new ArrayList<>());
		GroupDelegate group = new GroupDelegate();
		MetalHooks.setRedirector(descriptor -> MetalHooks.Redirect.keep(group));
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			MetalEncoder encoder = new MetalEncoder(gpu.context(), true);
			MetalTexture.View a = view(encoder, gpu.context(), "a");
			MetalTexture.View b = view(encoder, gpu.context(), "b");
			MetalTexture.View c = view(encoder, gpu.context(), "c");
			MetalTexture.View d = view(encoder, gpu.context(), "d");
			try {
				// The game's translucent pass (through the delegate), its phase split onto B, then DH's pass through the same delegate.
				encoder.createRenderPass(pass("game", a));
				encoder.splitRenderPass(new long[] {b.handle}, null, 0, false, 0f, W, H);
				MetalEncoder.PassSuspension saved = suspend(encoder);
				encoder.createRenderPass(pass("dh", c));
				encoder.submitRenderPass();
				encoder.resumePass(saved);
				// The game's translucent draws switch attachment group after the resume: the delegate's splitter must be live.
				group.switchGroup(new long[] {d.handle});
				assertTrue(encoder.inRenderPass());
			} finally {
				MetalHooks.encoderTrace = null;
				MetalHooks.setRedirector(null);
			}
			assertEquals("group", begins.get(begins.size() - 1).why());
			assertArrayEquals(new long[] {d.handle}, begins.get(begins.size() - 1).colors());
		}
	}

	@Test
	void suspendingWithNoPassOpenRestoresNoPass() {
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			MetalEncoder encoder = new MetalEncoder(gpu.context(), true);
			MetalEncoder.PassSuspension saved = suspend(encoder);
			encoder.resumePass(saved);
			assertFalse(encoder.inRenderPass());
			assertNull(saved.pass);
		}
	}
}
