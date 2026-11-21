// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcms.metal.MetalBridge;
import dev.mcms.shaders.metal.HeadlessGpu;
import dev.mcms.shaders.metal.ShaderNative;
import dev.mcms.shaders.pack.PackModel;
import dev.mcms.shaders.pack.PackSource;
import dev.mcms.shaders.pack.StandardMacros;
import dev.mcms.shaders.runtime.uniforms.CustomUniforms;
import dev.mcms.shaders.runtime.uniforms.FrameUniforms;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A program that fails to compile must never reach the frame (the E3 review's crash: a pipeline-0 program was bound,
 * a SIGSEGV in {@code setRenderPipelineState}), and {@link EngineFrame} skips a non-running pass anyway (the same
 * crash through a set that still holds one). A set built from a pack with one broken composite program runs without
 * it: the failed pass is absent from {@link DimensionSet#passes()}, the other passes draw, the screen is green.
 */
@Tag("gpu")
class EngineFrameBrokenProgramGpuTest {

	private static final int W = 64, H = 64;
	private static final int RGBA8 = 70, DEPTH32F = 252;
	private static final int RT_READ = ShaderNative.USAGE_SHADER_READ | ShaderNative.USAGE_RENDER_TARGET;

	private static PackModel model;

	@BeforeAll
	static void makePack(@TempDir Path dir) throws Exception {
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
		Files.writeString(shaders.resolve("composite.fsh"), """
			#version 130
			/* DRAWBUFFERS:0 */
			varying vec2 texcoord;
			void main() {
				gl_FragColor = vec4(0.0, 1.0, 0.0, 1.0);
			}
			""");
		Files.writeString(shaders.resolve("composite1.vsh"), vsh);
		// Not GLSL: the pack model reports it with errors, the set must count it absent and run without it.
		Files.writeString(shaders.resolve("composite1.fsh"), """
			#version 130
			this is not a shader (
			""");
		// Nine colour attachments: Metal's limit is eight, so newRenderPipelineState refuses the descriptor and the
		// standard road (the same attachment list) refuses too. The set must count the program absent, never run it.
		Files.writeString(shaders.resolve("composite2.vsh"), vsh);
		Files.writeString(shaders.resolve("composite2.fsh"), """
			#version 330
			/* DRAWBUFFERS:012345678 */
			layout(location = 0) out vec4 out0;
			layout(location = 1) out vec4 out1;
			layout(location = 2) out vec4 out2;
			layout(location = 3) out vec4 out3;
			layout(location = 4) out vec4 out4;
			layout(location = 5) out vec4 out5;
			layout(location = 6) out vec4 out6;
			layout(location = 7) out vec4 out7;
			layout(location = 8) out vec4 out8;
			void main() {
				out0 = vec4(1.0, 0.0, 0.0, 1.0);
				out1 = vec4(1.0, 0.0, 0.0, 1.0);
				out2 = vec4(1.0, 0.0, 0.0, 1.0);
				out3 = vec4(1.0, 0.0, 0.0, 1.0);
				out4 = vec4(1.0, 0.0, 0.0, 1.0);
				out5 = vec4(1.0, 0.0, 0.0, 1.0);
				out6 = vec4(1.0, 0.0, 0.0, 1.0);
				out7 = vec4(1.0, 0.0, 0.0, 1.0);
				out8 = vec4(1.0, 0.0, 0.0, 1.0);
			}
			""");
		Files.writeString(shaders.resolve("final.vsh"), vsh);
		Files.writeString(shaders.resolve("final.fsh"), """
			#version 130
			uniform sampler2D colortex0;
			varying vec2 texcoord;
			void main() {
				gl_FragColor = texture2D(colortex0, texcoord);
			}
			""");
		try (PackSource source = PackSource.open(dir)) {
			model = PackModel.load(source, StandardMacros.engine(), "en_us", new java.util.Properties());
		}
	}


	@Test
	void theFrameRunsAndSkipsTheFailedPass() {
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			long ctx = gpu.context();
			long enc = gpu.encoder();
			List<Long> toRelease = new ArrayList<>();
			try {
				DimensionSet set = DimensionSet.load(ctx, "broken", model, model.rootFolder(), custom(), RGBA8,
					model.rootFolder().union().colorFormats(), Engine.gamePipelines(), Engine.COPIES_NOT_COMPILED);
				assertEquals(List.of("composite", "final"), set.passes().stream().map(CompiledProgram::name).toList(),
					"a failed program must not be in the frame's passes");
				assertTrue(set.absent() >= 2, "the broken programs count absent: " + set.notes());
				// The review's crash arm: a pass whose Metal compilation failed (pipeline 0) still inside the set. It
				// must be skipped, never bound (before the fix this crashed the JVM in setRenderPipelineState).
				CompiledProgram green = set.passes().get(0);
				CompiledProgram broken = new CompiledProgram("composite1", green.phase(), 0, false, green.outputs(),
					green.blending(), green.unknownUniforms(), green.reflection(),
					"injected: Metal rejected the MSL (review arm)");
				set = new DimensionSet(set.dimension(),
					List.of(green, broken, set.passes().get(set.passes().size() - 1)), set.fromPack(),
					set.byFallback() + 1, set.absent() + 1, set.onRoad(), 0, 0, set.notes());
				assertEquals(List.of("composite", "composite1", "final"),
					set.passes().stream().map(CompiledProgram::name).toList(), "the set holds the failed pass");

				Targets targets = new Targets(set.dimension().union(), model.properties().bufferSizes(),
					set.usedTargets(), false, false, W, H, Targets.engineTextures(ctx), null);
				assertTrue(targets.create());
				long noise = Engine.makeNoise(ctx, set.noiseResolution());
				long copy = Engine.copyPipeline(ctx, targets.format(0));
				assertTrue(copy != 0, "the engine's copy program must compile");

				FrameUniforms uniforms = new FrameUniforms();
				long[] frameBlock = upload(ctx, block(uniforms), toRelease);
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
				EngineFrame frame = new EngineFrame(set, targets, null, noise, copy, 0, (bytes, out) -> {
					long[] slice = upload(ctx, bytes, toRelease);
					out[0] = slice[0];
					out[1] = slice[1];
				}, Map.of(), Map.of(), new EngineFrame.Gbuffers(new int[0], new int[0], new int[0], false,
					phase -> {}, null));
				long world = worldTexture(ctx, toRelease);
				EngineFrame.Main main = new EngineFrame.Main();
				main.set(world, RGBA8, W, H, null);
				frame.clearHook(enc, null, targets, blocks);
				frame.compositeHook(enc, null, targets, main, blocks);
				MetalBridge.commitAndWait(enc);
				frame.close();

				byte[] picture = gpu.readTexture(world, 0, 0, W, H, 1, 4);
				for (int y = 0; y < H; y++) {
					for (int x = 0; x < W; x++) {
						int at = (y * W + x) * 4;
						assertEquals(0, picture[at] & 0xff, "red at " + x + "," + y);
						assertEquals(255, picture[at + 1] & 0xff, "green at " + x + "," + y);
						assertEquals(0, picture[at + 2] & 0xff, "blue at " + x + "," + y);
					}
				}

				set.passes().forEach(pass -> toRelease.add(pass.pipeline()));
				targets.release();
				toRelease.add(noise);
				toRelease.add(copy);
			} finally {
				toRelease.forEach(handle -> {
					if (handle != null && handle != 0) {
						MetalBridge.release(handle);
					}
				});
			}
		}
	}

	private static CustomUniforms custom() {
		return CustomUniforms.of(model);
	}

	private static ByteBuffer block(FrameUniforms uniforms) {
		ByteBuffer bytes = ByteBuffer.allocateDirect(FrameUniforms.size()).order(ByteOrder.LITTLE_ENDIAN);
		uniforms.write(bytes);
		return bytes;
	}

	private static long[] upload(long ctx, ByteBuffer bytes, List<Long> tracker) {
		bytes.rewind();
		long buffer = MetalBridge.newBuffer(ctx, Math.max(16, bytes.remaining()));
		MemorySegment.ofAddress(MetalBridge.bufferContents(buffer)).reinterpret(bytes.remaining())
			.copyFrom(MemorySegment.ofBuffer(bytes));
		tracker.add(buffer);
		return new long[] {buffer, 0};
	}

	/** A grey world picture; the composite overwrites every pixel of colortex0 with green. */
	private static long worldTexture(long ctx, List<Long> tracker) {
		byte[] texels = new byte[W * H * 4];
		for (int i = 0; i < texels.length; i += 4) {
			texels[i] = (byte) 128;
			texels[i + 1] = (byte) 128;
			texels[i + 2] = (byte) 128;
			texels[i + 3] = (byte) 0xff;
		}
		long texture = ShaderNative.textureNew(ctx, RGBA8, ShaderNative.TYPE_2D, W, H, 1, 1, RT_READ,
			ShaderNative.STORAGE_PRIVATE);
		assertTrue(texture != 0, "the device refused the synthetic main colour");
		assertTrue(ShaderNative.textureUpload(ctx, texture, texels, (long) W * 4, 0, 0, 0, 0, 0, 0, W, H, 1));
		tracker.add(texture);
		return texture;
	}
}
