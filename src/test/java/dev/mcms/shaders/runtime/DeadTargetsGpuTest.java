// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcms.metal.MetalBridge;
import dev.mcms.shaders.metal.HeadlessGpu;
import dev.mcms.shaders.metal.ShaderNative;
import dev.mcms.shaders.pack.PackModel;
import dev.mcms.shaders.pack.PackSource;
import dev.mcms.shaders.pack.StandardMacros;
import dev.mcms.shaders.runtime.uniforms.CustomUniforms;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Dead targets (E9, the memoryless item): a target some program writes and no program reads is dead, and a full-screen
 * pass whose every output is dead does not run. The pack: composite writes green into colortex7, composite1 writes blue
 * into colortex0, final shows colortex0; in the second pack the final also samples colortex7, so it is read; in the third,
 * composite writes both colortex0 and colortex7, so it runs with one dead output.
 */
@Tag("gpu")
class DeadTargetsGpuTest {

	private static final int W = 32, H = 32;
	private static final int RGBA8 = 70;
	private static final int RT_READ = ShaderNative.USAGE_SHADER_READ | ShaderNative.USAGE_RENDER_TARGET;
	private static final String VSH = """
		#version 130
		varying vec2 texcoord;
		void main() {
			texcoord = gl_MultiTexCoord0.xy;
			gl_Position = ftransform();
		}
		""";
	private static final String COMPOSITE_GREEN_7 = """
		#version 130
		/* DRAWBUFFERS:7 */
		void main() {
			gl_FragData[0] = vec4(0.0, 1.0, 0.0, 1.0);
		}
		""";
	private static final String COMPOSITE1_BLUE_0 = """
		#version 130
		/* DRAWBUFFERS:0 */
		void main() {
			gl_FragData[0] = vec4(0.0, 0.0, 1.0, 1.0);
		}
		""";
	private static final String COMPOSITE_BLUE_0_GREEN_7 = """
		#version 130
		/* DRAWBUFFERS:07 */
		void main() {
			gl_FragData[0] = vec4(0.0, 0.0, 1.0, 1.0);
			gl_FragData[1] = vec4(0.0, 1.0, 0.0, 1.0);
		}
		""";
	private static final String FINAL_COPY = """
		#version 130
		uniform sampler2D colortex0;
		varying vec2 texcoord;
		void main() {
			gl_FragColor = texture2D(colortex0, texcoord);
		}
		""";
	/** The final also reads colortex7 (a zero weight: the picture stays colortex0's, the read keeps colortex7 alive). */
	private static final String FINAL_COPY_READS_7 = """
		#version 130
		uniform sampler2D colortex0;
		uniform sampler2D colortex7;
		varying vec2 texcoord;
		void main() {
			gl_FragColor = texture2D(colortex0, texcoord) + 0.0 * texture2D(colortex7, texcoord);
		}
		""";

	private static PackModel writePack(Path dir, String composite, String composite1, String finalSource) throws Exception {
		Path shaders = Files.createDirectories(dir.resolve("shaders"));
		Files.writeString(shaders.resolve("composite.vsh"), VSH);
		Files.writeString(shaders.resolve("composite.fsh"), composite);
		if (composite1 != null) {
			Files.writeString(shaders.resolve("composite1.vsh"), VSH);
			Files.writeString(shaders.resolve("composite1.fsh"), composite1);
		}
		Files.writeString(shaders.resolve("final.vsh"), VSH);
		Files.writeString(shaders.resolve("final.fsh"), finalSource);
		try (PackSource source = PackSource.open(dir)) {
			return PackModel.load(source, StandardMacros.engine(), "en_us", new Properties());
		}
	}

	/** What one frame left: the dead targets, the passes it skipped, the window's centre texel, colortex7's read texel. */
	private record Frame(Set<Integer> dead, Set<String> skipped, int[] window, int[] colortex7Read) {
	}

	private static Frame runFrame(HeadlessGpu gpu, PackModel model) throws Exception {
		long ctx = gpu.context();
		long enc = gpu.encoder();
		List<Long> toRelease = new ArrayList<>();
		try {
			DimensionSet set = DimensionSet.load(ctx, "dead-targets", model, model.rootFolder(), CustomUniforms.of(model),
				RGBA8, model.rootFolder().union().colorFormats(), Engine.gamePipelines(), Engine.COPIES_NOT_COMPILED);
			assertEquals(0, set.absent(), "every program builds: " + set.notes());
			Targets targets = new Targets(set.dimension().union(), model.properties().bufferSizes(), set.usedTargets(),
				false, false, W, H, Targets.engineTextures(ctx), null);
			assertTrue(targets.create());
			long noise = Engine.makeNoise(ctx, set.noiseResolution());
			long copy = Engine.copyPipeline(ctx, targets.format(0));
			long finalCopy = Engine.copyPipeline(ctx, RGBA8);
			long main = ShaderNative.textureNew(ctx, RGBA8, ShaderNative.TYPE_2D, W, H, 1, 1, RT_READ,
				ShaderNative.STORAGE_PRIVATE);
			toRelease.add(main);
			long block = MetalBridge.newBuffer(ctx, 256);
			toRelease.add(block);
			EngineFrame.PassBlocks blocks = new EngineFrame.PassBlocks() {
				@Override
				public long[] frameBlock(String program) {
					return null;
				}

				@Override
				public long[] customBlock() {
					return null;
				}
			};
			EngineFrame frame = new EngineFrame(set, targets, null, noise, copy, finalCopy, (bytes, out) -> {
				out[0] = block;
				out[1] = 0;
			}, model.properties().flips(), model.properties().scales(), new EngineFrame.Gbuffers(new int[0], new int[0],
				new int[0], false, phase -> { }, null));
			EngineFrame.Main mainColour = new EngineFrame.Main();
			mainColour.set(main, RGBA8, W, H, null);
			frame.clearHook(enc, null, targets, blocks);
			frame.compositeHook(enc, null, targets, mainColour, blocks);
			MetalBridge.commitAndWait(enc);
			int at = ((H / 2) * W + W / 2) * 4;
			Frame result = new Frame(set.deadTargets(), frame.skippedPasses(),
				texel(gpu.readTexture(main, 0, 0, W, H, 1, 4), at), texel(gpu.readTexture(targets.read(7), 0, 0, W, H, 1, 4), at));
			frame.close();
			set.passes().forEach(pass -> toRelease.add(pass.pipeline()));
			targets.release();
			toRelease.add(noise);
			toRelease.add(copy);
			toRelease.add(finalCopy);
			return result;
		} finally {
			toRelease.forEach(handle -> {
				if (handle != null && handle != 0) {
					MetalBridge.release(handle);
				}
			});
		}
	}

	private static int[] texel(byte[] bytes, int at) {
		return new int[] {bytes[at] & 0xff, bytes[at + 1] & 0xff, bytes[at + 2] & 0xff, bytes[at + 3] & 0xff};
	}

	private static boolean green(int[] texel) {
		return texel[0] == 0 && texel[1] == 255 && texel[2] == 0;
	}

	@Test
	void aTargetNoProgramReadsIsDeadAndItsOnlyWriterDoesNotRun(@TempDir Path dir) throws Exception {
		PackModel model = writePack(dir, COMPOSITE_GREEN_7, COMPOSITE1_BLUE_0, FINAL_COPY);
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			Frame frame = runFrame(gpu, model);
			assertEquals(Set.of(7), frame.dead(), "colortex7: composite writes it, no program reads it");
			assertEquals(Set.of("composite"), frame.skipped(), "composite writes only colortex7, so it does not run");
			assertFalse(green(frame.colortex7Read()), "composite did not run: colortex7 never held its green");
			assertEquals(255, frame.window()[2], "the window shows composite1's blue through the final");
		}
	}

	@Test
	void aTargetTheFinalReadsIsAliveAndItsWriterRuns(@TempDir Path dir) throws Exception {
		PackModel model = writePack(dir, COMPOSITE_GREEN_7, COMPOSITE1_BLUE_0, FINAL_COPY_READS_7);
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			Frame frame = runFrame(gpu, model);
			assertEquals(Set.of(), frame.dead(), "the final samples colortex7");
			assertEquals(Set.of(), frame.skipped(), "every pass runs");
			assertEquals(255, frame.window()[2], "the window shows composite1's blue through the final");
			assertTrue(green(frame.colortex7Read()), "composite ran: colortex7's read texture holds its green after the flip");
		}
	}

	@Test
	void aPassWithOneDeadOutputStillRunsAndDrawsItsLiveOne(@TempDir Path dir) throws Exception {
		PackModel model = writePack(dir, COMPOSITE_BLUE_0_GREEN_7, null, FINAL_COPY);
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			Frame frame = runFrame(gpu, model);
			assertEquals(Set.of(7), frame.dead(), "colortex7: composite writes it, no program reads it");
			assertEquals(Set.of(), frame.skipped(), "composite also writes colortex0, which the final reads");
			assertEquals(255, frame.window()[2], "the window shows composite's blue through the final");
		}
	}
}
