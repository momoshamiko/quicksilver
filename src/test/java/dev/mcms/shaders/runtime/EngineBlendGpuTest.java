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

	/**
	 * OpenGL 4.6 section 17.3.6 ignores blending on integer color attachments, whereas Metal aborts pipeline validation
	 * if blendingEnabled is set on an integer pixel format. Both DimensionSet and mc_pipeline_new must force blending off.
	 */
	@org.junit.jupiter.api.Test
	void integerAttachmentIgnoresBlendingWithoutMetalValidationAbort() throws Exception {
		Path dir = Files.createTempDirectory("qs-int-blend");
		try {
			Path shaders = dir.resolve("shaders");
			Files.createDirectories(shaders);
			Files.writeString(shaders.resolve("shaders.properties"), "blend.composite=ONE ONE ONE ONE\n");
			String vsh = """
				#version 430
				void main() { gl_Position = vec4(0.0, 0.0, 0.0, 1.0); }
				""";
			Files.writeString(shaders.resolve("composite.vsh"), vsh);
			Files.writeString(shaders.resolve("composite.fsh"), """
				#version 430
				/*
				const int colortex1Format = RGBA32UI;
				*/
				/* RENDERTARGETS: 1 */
				layout(location = 0) out uvec4 outData;
				void main() { outData = uvec4(1u, 2u, 3u, 4u); }
				""");
			Files.writeString(shaders.resolve("final.vsh"), vsh);
			Files.writeString(shaders.resolve("final.fsh"), """
				#version 430
				/* RENDERTARGETS: 0 */
				layout(location = 0) out vec4 fragColor;
				void main() { fragColor = vec4(1.0); }
				""");
			PackModel intModel;
			try (PackSource pack = PackSource.open(dir)) {
				intModel = PackModel.load(pack, StandardMacros.engine(), "en_us", new java.util.Properties());
			}
			dev.mcms.shaders.pack.DimensionPrograms dim = intModel.rootFolder();
			try (HeadlessGpu gpu = new HeadlessGpu()) {
				DimensionSet set = DimensionSet.load(gpu.context(), "int-blend", intModel, dim,
					CustomUniforms.of(intModel), RGBA8, dim.union().colorFormats(), List.of(),
					Engine.COPIES_NOT_COMPILED);
				try {
					assertEquals(2, set.passes().size(),
						"composite writing RGBA32UI with blend directive must build cleanly: notes=" + set.notes());
					assertEquals(0, set.absent(), "no pass should fail: " + set.notes());
				} finally {
					set.passes().forEach(p -> MetalBridge.release(p.pipeline()));
					if (set.plans() != null) {
						set.plans().close();
					}
				}
				// Also exercise the native mc_pipeline_new guard directly with blending=1 on MTLPixelFormatRGBA32Uint (123).
				var translated = dev.mcms.glsl.Translator.translate(new dev.mcms.glsl.msl.ProgramRequest(
					"int_direct", dev.mcms.glsl.msl.ProgramRequest.Family.FULL_SCREEN,
					List.of(
						new dev.mcms.glsl.msl.ProgramRequest.StageSource(dev.mcms.glsl.sema.Stage.VERTEX,
							"#version 430\nvoid main(){gl_Position=vec4(0.0,0.0,0.0,1.0);}\n",
							List.of(new dev.mcms.glsl.sema.SourceLine("v", 1), new dev.mcms.glsl.sema.SourceLine("v", 2))),
						new dev.mcms.glsl.msl.ProgramRequest.StageSource(dev.mcms.glsl.sema.Stage.FRAGMENT,
							"#version 430\nlayout(location=0) out uvec4 o;\nvoid main(){o=uvec4(1u);}\n",
							List.of(new dev.mcms.glsl.sema.SourceLine("f", 1), new dev.mcms.glsl.sema.SourceLine("f", 2),
								new dev.mcms.glsl.sema.SourceLine("f", 3)))),
					dev.mcms.glsl.msl.ProgramRequest.VertexLayout.fullScreenQuad(),
					List.of(new dev.mcms.glsl.msl.ProgramRequest.ColorTarget(0, 1, 4)),
					List.of(), List.of(), List.of(),
					dev.mcms.glsl.msl.ProgramRequest.UniformSchema.none(), null, false));
				assertTrue(translated.ok(), translated.diagnostics().toString());
				ShaderNative.Compiled lib = ShaderNative.libraryNew(gpu.context(), translated.msl(),
					ShaderNative.MSL_3_1, ShaderNative.MATH_PACKS, true);
				assertTrue(lib.ok(), lib.error());
				try {
					int[] desc = {0, 0, 1, 123, 15, 1, 1, 1, 0, 1, 1, 0, 0, 3};
					long pso = MetalBridge.pipelineNew(gpu.context(), lib.library(), translated.vertexEntry(),
						lib.library(), translated.fragmentEntry(), desc);
					assertTrue(pso != 0, "native mc_pipeline_new must guard integer format blending");
					MetalBridge.release(pso);
				} finally {
					MetalBridge.release(lib.library());
				}
			}
		} finally {
			try (var walk = Files.walk(dir)) {
				walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
			}
		}
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
