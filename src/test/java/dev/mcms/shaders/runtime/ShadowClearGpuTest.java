// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcms.metal.MetalBridge;
import dev.mcms.shaders.metal.HeadlessGpu;
import dev.mcms.shaders.pack.PackModel;
import dev.mcms.shaders.pack.PackSource;
import dev.mcms.shaders.pack.StandardMacros;
import dev.mcms.shaders.runtime.shadow.ShadowTargets;
import dev.mcms.shaders.runtime.uniforms.CustomUniforms;
import dev.mcms.shaders.runtime.uniforms.FrameUniforms;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A pack whose only shadow reads are in a composite gets live shadow targets, and the frame's clear sets them to their
 * documented values before any pass runs: each depth map to 1.0 and the colour buffer to white (the shadowtex page:
 * "These buffers all clear to vec4(1.0)"; the shadowcolor page: "clear their values after each frame to solid white").
 * Runs on the headless device.
 */
@Tag("gpu")
class ShadowClearGpuTest {

	private static final int SIZE = 64;
	private static final int W = 64, H = 64;
	private static final int RGBA8 = 70;

	@Test
	void theShadowMapsStartEachFrameClearedToTheDocumentedValues(@TempDir Path dir) throws Exception {
		PackModel model = writePack(dir);
		CustomUniforms custom = CustomUniforms.of(model);
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			long ctx = gpu.context();
			long enc = gpu.encoder();
			List<Long> toRelease = new ArrayList<>();
			ShadowTargets shadow = null;
			Targets targets = null;
			try {
				DimensionSet set = DimensionSet.load(ctx, "shadow-clear", model, model.rootFolder(), custom, RGBA8,
					model.rootFolder().union().colorFormats(), Engine.gamePipelines(), Engine.COPIES_NOT_COMPILED);
				assertTrue(set.absent() == 0, "the composite runs from the pack's files");
				shadow = ShadowTargets.of(set.dimension());
				assertTrue(shadow.used(), "a composite's shadow reads make the set use shadow targets");
				assertTrue(shadow.create(Targets.engineTextures(ctx)), "the device makes the shadow maps");
				targets = new Targets(set.dimension().union(), model.properties().bufferSizes(), set.usedTargets(),
					false, false, W, H, Targets.engineTextures(ctx), null);
				assertTrue(targets.create());
				long noise = Engine.makeNoise(ctx, set.noiseResolution());
				toRelease.add(noise);
				long copy = Engine.copyPipeline(ctx, targets.format(0));
				toRelease.add(copy);
				long block = MetalBridge.newBuffer(ctx, Math.max(16, FrameUniforms.size()));
				toRelease.add(block);
				long[] frameBlock = {block, 0};
				EngineFrame.PassBlocks blocks = new EngineFrame.PassBlocks() {
					@Override
					public long[] frameBlock(String program) {
						return frameBlock;
					}

					@Override
					public long[] customBlock() {
						return null;
					}
				};
				EngineFrame frame = new EngineFrame(set, targets, shadow, noise, copy, 0, (uploaded, out) -> {
					out[0] = block;
					out[1] = 0;
				}, Map.of(), Map.of(), new EngineFrame.Gbuffers(new int[0], new int[0], new int[0], false,
					phase -> {}, null));
				// The shadow pass clears the maps at its open (ShadowTargets.clear); the frame's clear does not touch them.
				shadow.clear(enc);
				MetalBridge.commitAndWait(enc);
				for (int d = 0; d < ShadowTargets.DEPTH_TARGETS; d++) {
					assertAllDepth(gpu, shadow.depth(d), 1f, "shadowtex" + d);
				}
				assertAllBytes(gpu.readTexture(shadow.color(0), 0, 0, SIZE, SIZE, 1, 4), 255, "shadowcolor0");

				// The shadow pass wrote the maps (a black colour, a 0.5 depth); the frame's clear node then runs.
				MetalBridge.renderBegin(enc, new long[] {shadow.color(0)}, new float[][] {{0, 0, 0, 0}}, shadow.depth(0),
					true, 0.5f, SIZE, SIZE);
				MetalBridge.endEncoders(enc);
				MetalBridge.commitAndWait(enc);
				frame.clearHook(enc, null, targets, blocks);
				MetalBridge.commitAndWait(enc);
				frame.close();
				assertAllDepth(gpu, shadow.depth(0), 0.5f, "shadowtex0 after the frame clear");
				assertAllBytes(gpu.readTexture(shadow.color(0), 0, 0, SIZE, SIZE, 1, 4), 0, "shadowcolor0 after the frame clear");
			} finally {
				if (shadow != null) {
					shadow.release();
				}
				if (targets != null) {
					targets.release();
				}
				for (long handle : toRelease) {
					MetalBridge.release(handle);
				}
			}
		}
	}

	private static PackModel writePack(Path dir) throws Exception {
		Path shaders = Files.createDirectories(dir.resolve("shaders"));
		Files.writeString(shaders.resolve("composite.vsh"), """
			#version 130
			varying vec2 texcoord;
			void main() {
				texcoord = gl_MultiTexCoord0.xy;
				gl_Position = ftransform();
			}
			""");
		Files.writeString(shaders.resolve("composite.fsh"), """
			#version 130
			const int shadowMapResolution = 64;
			uniform sampler2D shadowtex0;
			uniform sampler2D shadowcolor0;
			varying vec2 texcoord;
			void main() {
				gl_FragData[0] = texture2D(shadowcolor0, texcoord) + vec4(texture2D(shadowtex0, texcoord).r);
			}
			""");
		try (PackSource source = PackSource.open(dir)) {
			return PackModel.load(source, StandardMacros.engine(), "en_us", new Properties());
		}
	}

	/** Every texel of a depth map reads back as {@code expected}. */
	private static void assertAllDepth(HeadlessGpu gpu, long texture, float expected, String name) {
		byte[] bytes = gpu.readTexture(texture, 0, 0, SIZE, SIZE, 1, HeadlessGpu.bytesPerPixel(TargetFormats.DEPTH32_FLOAT));
		ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
		for (int i = 0; i < SIZE * SIZE; i++) {
			assertEquals(expected, buffer.getFloat(4 * i), 1e-6f, name + " texel " + i);
		}
	}

	/** Every byte of a colour readback is {@code value}. */
	private static void assertAllBytes(byte[] pixels, int value, String name) {
		assertEquals(SIZE * SIZE * 4, pixels.length, name + " size");
		for (int i = 0; i < pixels.length; i++) {
			assertEquals(value, pixels[i] & 0xff, name + " byte " + i);
		}
	}
}
