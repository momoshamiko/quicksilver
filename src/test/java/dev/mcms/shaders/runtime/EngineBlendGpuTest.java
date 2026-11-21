// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcms.metal.MetalBridge;
import dev.mcms.shaders.metal.HeadlessGpu;
import dev.mcms.shaders.metal.ShaderNative;
import dev.mcms.shaders.pack.PackModel;
import dev.mcms.shaders.pack.PackSource;
import dev.mcms.shaders.pack.PropertiesText;
import dev.mcms.shaders.pack.ShaderProperties;
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
 * {@code blend.<program>[.<buffer>]} reaches the pipeline (the E3 review: the factors were ignored and every pass
 * overwrote its target). The pack's composite draws half-transparent green over colortex1 cleared red with
 * {@code blend.composite=SRC_ALPHA ONE_MINUS_SRC_ALPHA}: with blending the screen holds the half-and-half mix,
 * without it the plain green.
 */
@Tag("gpu")
class EngineBlendGpuTest {

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
			/* DRAWBUFFERS:1 */
			const vec4 colortex1ClearColor = vec4(1.0, 0.0, 0.0, 1.0);
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
		Files.writeString(shaders.resolve("shaders.properties"), """
			blend.composite=SRC_ALPHA ONE_MINUS_SRC_ALPHA SRC_ALPHA ONE_MINUS_SRC_ALPHA
			""");
		try (PackSource source = PackSource.open(dir)) {
			model = PackModel.load(source, StandardMacros.engine(), "en_us", new java.util.Properties());
		}
	}

	@Test
	void theBlendFactorsMixThePassOverItsTarget() {
		assertTrue(model.properties().blends().containsKey("composite"), "the pack's blend directive parsed");
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			long ctx = gpu.context();
			long enc = gpu.encoder();
			List<Long> toRelease = new ArrayList<>();
			try {
				DimensionSet set = DimensionSet.load(ctx, "blend", model, model.rootFolder(), custom(), RGBA8,
					model.rootFolder().union().colorFormats(), Engine.gamePipelines(), Engine.COPIES_NOT_COMPILED);
				assertEquals(List.of("composite", "final"), set.passes().stream().map(CompiledProgram::name).toList(),
					"the frame order of the pack: " + set.notes());

				Targets targets = new Targets(set.dimension().union(), model.properties().bufferSizes(),
					set.usedTargets(), false, false, W, H, Targets.engineTextures(ctx), null);
				assertTrue(targets.create());
				long noise = Engine.makeNoise(ctx, set.noiseResolution());
				long copy = Engine.copyPipeline(ctx, targets.format(0));
				long[] frameBlock = upload(ctx, block(), toRelease);
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
				// 0.5 * green over the red clear with the pack's factors: the half-and-half mix (128 +- 1 of 255).
				byte[] picture = gpu.readTexture(world, 0, 0, W, H, 1, 4);
				for (int y = 0; y < H; y++) {
					for (int x = 0; x < W; x++) {
						int at = (y * W + x) * 4;
						assertEquals(128, picture[at] & 0xff, 1, "red at " + x + "," + y);
						assertEquals(128, picture[at + 1] & 0xff, 1, "green at " + x + "," + y);
						assertEquals(0, picture[at + 2] & 0xff, 1, "blue at " + x + "," + y);
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

	/** The per-buffer lookup resolves the buffer by name and alias ({@code blend.<program>.<buffer>}, OF-doc). */
	@org.junit.jupiter.api.Test
	void perBufferBlendResolvesTheBuffersNameAndAlias() {
		ShaderProperties properties = ShaderProperties.from(PropertiesText.parse("""
			blend.composite.colortex4=ONE ZERO ONE ZERO
			blend.composite2.gaux2=DST_COLOR ONE_MINUS_DST_COLOR DST_COLOR ONE_MINUS_DST_COLOR
			blend.deferred=off
			"""));
		assertEquals("ONE", blendOf(properties, "composite", 4).src());
		assertEquals("ONE", blendOf(properties, "composite", 4).srcAlpha());  // a colortex name alone matches
		assertEquals("DST_COLOR", blendOf(properties, "composite2", 5).src());  // gaux2 is colortex5
		assertTrue(blendOf(properties, "deferred", 0).off());                   // the program-wide off
		assertTrue(blendOf(properties, "composite3", 0) == null);               // nothing configured: no blending
	}

	private static ShaderProperties.BlendState blendOf(ShaderProperties properties, String program, int target) {
		return DimensionSet.blendFor(properties.blends(), program, target);
	}

	private static CustomUniforms custom() {
		return CustomUniforms.of(model);
	}

	private static ByteBuffer block() {
		FrameUniforms uniforms = new FrameUniforms();
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

	private static long worldTexture(long ctx, List<Long> tracker) {
		byte[] texels = new byte[W * H * 4];
		for (int i = 0; i < texels.length; i += 4) {
			texels[i + 3] = (byte) 0xff;
		}
		long texture = ShaderNative.textureNew(ctx, RGBA8, ShaderNative.TYPE_2D, W, H, 1, 1, RT_READ,
			ShaderNative.STORAGE_PRIVATE);
		assertTrue(ShaderNative.textureUpload(ctx, texture, texels, (long) W * 4, 0, 0, 0, 0, 0, 0, W, H, 1));
		tracker.add(texture);
		return texture;
	}
}
