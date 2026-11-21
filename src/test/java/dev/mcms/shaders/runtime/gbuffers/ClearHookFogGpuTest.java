// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.gbuffers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcms.metal.MetalBridge;
import dev.mcms.shaders.metal.HeadlessGpu;
import dev.mcms.shaders.metal.ShaderNative;
import dev.mcms.shaders.pack.PackModel;
import dev.mcms.shaders.pack.PackSource;
import dev.mcms.shaders.pack.StandardMacros;
import dev.mcms.shaders.runtime.DimensionSet;
import dev.mcms.shaders.runtime.EngineFrame;
import dev.mcms.shaders.runtime.Targets;
import dev.mcms.shaders.runtime.uniforms.CustomUniforms;
import dev.mcms.shaders.runtime.uniforms.FrameInputs;
import dev.mcms.shaders.runtime.uniforms.FrameUniforms;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The clear hook's colortex0 default (plan.md E4, the clear pass; engine-pack-format.md section 7): without a
 * {@code colortex0ClearColor} constant (or its legacy name {@code gcolorClearColor}), colortex0 clears to the frame's
 * fog colour with alpha 1, and each frame's clear follows that frame's fog. A pack's constant wins over the fog.
 * colortex1 stays white. The tests call {@link Targets#refreshFogClear} and {@link EngineFrame#clearHook} themselves,
 * the two steps of a frame, and read both targets back from the device. The call in {@code Engine.clearHook} (just before
 * its {@code frame.clearHook} call) is not run here: the game runs cover it.
 */
@Tag("gpu")
class ClearHookFogGpuTest {

	private static final int W = 16, H = 16;
	private static final int RGBA8 = 70;

	@Test
	void colortex0ClearsToTheFrameFogAndFollowsEachFrame(@TempDir Path dir) throws Exception {
		PackModel model = pack(dir, "");
		try (HeadlessGpu gpu = new HeadlessGpu(); Scene scene = new Scene(gpu, model)) {
			// The frame's fog is (0.75, 0.84, 0.95) on frame one and (0.2, 0.4, 0.6) on frame two; alpha is 1.
			byte[][] one = scene.clearFrame(inputs(0.75f, 0.84f, 0.95f));
			assertPixels(one[0], 191, 214, 242, 255, "colortex0 under frame one's fog");
			assertPixels(one[1], 255, 255, 255, 255, "colortex1 stays white on frame one");
			byte[][] two = scene.clearFrame(inputs(0.2f, 0.4f, 0.6f));
			assertPixels(two[0], 51, 102, 153, 255, "colortex0 follows frame two's fog");
			assertPixels(two[1], 255, 255, 255, 255, "colortex1 stays white on frame two");
		}
	}

	@Test
	void aPackConstantWinsOverTheFrameFog(@TempDir Path dir) throws Exception {
		for (String name : List.of("colortex0ClearColor", "gcolorClearColor")) {
			PackModel model = pack(dir.resolve(name), "const vec4 " + name + " = vec4(0.5, 0.25, 0.125, 1.0);\n");
			try (HeadlessGpu gpu = new HeadlessGpu(); Scene scene = new Scene(gpu, model)) {
				byte[][] frame = scene.clearFrame(inputs(0.2f, 0.4f, 0.6f));
				assertPixels(frame[0], 128, 64, 32, 255, name + " sets colortex0's clear; the frame's fog does not");
				assertPixels(frame[1], 255, 255, 255, 255, name + " leaves colortex1 white");
			}
		}
	}

	/** The inputs of a frame with the given fog colour (the capture's other fields are GbufferFixtures' defaults). */
	private static FrameInputs inputs(float fogR, float fogG, float fogB) {
		return GbufferFixtures.inputs(W, H, new Matrix4f(), 90f, 256f, fogR, fogG, fogB);
	}

	/** A two-program pack: the composite (draws colortex1) takes the given constant lines, the final reads colortex1. */
	private static PackModel pack(Path dir, String constants) throws Exception {
		Path shaders = Files.createDirectories(dir.resolve("shaders"));
		String vsh = """
			#version 130
			varying vec2 texcoord;
			void main() {
				texcoord = gl_MultiTexCoord0.xy;
				gl_Position = ftransform();
			}
			""";
		Files.writeString(shaders.resolve("composite.vsh"), vsh);
		Files.writeString(shaders.resolve("composite.fsh"), "#version 130\n" + constants + """
			/* DRAWBUFFERS:1 */
			void main() {
				gl_FragColor = vec4(0.0, 1.0, 0.0, 0.5);
			}
			""");
		Files.writeString(shaders.resolve("final.vsh"), vsh);
		Files.writeString(shaders.resolve("final.fsh"), """
			#version 130
			uniform sampler2D colortex1;
			varying vec2 texcoord;
			void main() {
				gl_FragColor = texture2D(colortex1, texcoord);
			}
			""");
		try (PackSource source = PackSource.open(dir)) {
			return PackModel.load(source, StandardMacros.engine(), "en_us", new Properties());
		}
	}

	/** Every pixel of an RGBA8 picture within one step of the expected bytes. */
	private static void assertPixels(byte[] picture, int r, int g, int b, int a, String what) {
		assertEquals(W * H * 4, picture.length, what + ": picture size");
		for (int at = 0; at < picture.length; at += 4) {
			assertEquals(r, picture[at] & 0xff, 1, what + ": red at pixel " + at / 4);
			assertEquals(g, picture[at + 1] & 0xff, 1, what + ": green at pixel " + at / 4);
			assertEquals(b, picture[at + 2] & 0xff, 1, what + ": blue at pixel " + at / 4);
			assertEquals(a, picture[at + 3] & 0xff, 1, what + ": alpha at pixel " + at / 4);
		}
	}

	private static long[] upload(long ctx, ByteBuffer bytes, List<Long> tracker) {
		bytes.rewind();
		long buffer = MetalBridge.newBuffer(ctx, Math.max(16, bytes.remaining()));
		MemorySegment.ofAddress(MetalBridge.bufferContents(buffer)).reinterpret(bytes.remaining())
			.copyFrom(MemorySegment.ofBuffer(bytes));
		tracker.add(buffer);
		return new long[] {buffer, 0};
	}

	/** One loaded set with its targets and its frame on the test device; closing frees everything the scene made. */
	private static final class Scene implements AutoCloseable {
		private final HeadlessGpu gpu;
		private final long ctx;
		private final long enc;
		private final List<Long> toRelease = new ArrayList<>();
		private final DimensionSet set;
		private final Targets targets;
		private final EngineFrame frame;
		private final FrameUniforms uniforms = new FrameUniforms();
		private final EngineFrame.PassBlocks blocks = new EngineFrame.PassBlocks() {
			@Override
			public long[] frameBlock(String program) {
				return null;
			}

			@Override
			public long[] customBlock() {
				return null;
			}
		};

		Scene(HeadlessGpu gpu, PackModel model) {
			this.gpu = gpu;
			this.ctx = gpu.context();
			this.enc = gpu.encoder();
			this.set = DimensionSet.load(this.ctx, "fog", model, model.rootFolder(), CustomUniforms.of(model), RGBA8,
				model.rootFolder().union().colorFormats(), GbufferFixtures.gamePipelines(), copy -> null);
			assertTrue(this.set.absent() == 0, "the pack's programs must all run: " + this.set.notes());
			this.targets = new Targets(this.set.dimension().union(), model.properties().bufferSizes(),
				this.set.usedTargets(), false, false, W, H, Targets.engineTextures(this.ctx), null);
			assertTrue(this.targets.create());
			long noise = ShaderNative.textureNew(this.ctx, RGBA8, ShaderNative.TYPE_2D, 16, 16, 1, 1,
				ShaderNative.USAGE_SHADER_READ, ShaderNative.STORAGE_PRIVATE);
			this.toRelease.add(noise);
			this.frame = new EngineFrame(this.set, this.targets, null, noise, 0, 0, (bytes, out) -> {
				long[] slice = upload(this.ctx, bytes, this.toRelease);
				out[0] = slice[0];
				out[1] = slice[1];
			}, Map.of(), Map.of(), new EngineFrame.Gbuffers(new int[0], new int[0], new int[0], false,
				phase -> {}, null));
		}

		/** Runs one frame's clear hook with the frame's inputs; returns colortex0 and colortex1 read back. */
		byte[][] clearFrame(FrameInputs inputs) {
			this.uniforms.update(inputs);
			this.targets.refreshFogClear(this.uniforms);
			this.frame.clearHook(this.enc, null, this.targets, this.blocks);
			MetalBridge.commitAndWait(this.enc);
			return new byte[][] {
				this.gpu.readTexture(this.targets.read(0), 0, 0, W, H, 1, 4),
				this.gpu.readTexture(this.targets.read(1), 0, 0, W, H, 1, 4)};
		}

		@Override
		public void close() {
			this.frame.close();
			this.set.passes().forEach(pass -> this.toRelease.add(pass.pipeline()));
			this.targets.release();
			this.toRelease.forEach(handle -> {
				if (handle != null && handle != 0) {
					MetalBridge.release(handle);
				}
			});
		}
	}
}
