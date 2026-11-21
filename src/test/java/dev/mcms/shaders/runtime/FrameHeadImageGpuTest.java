// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcms.metal.MetalBridge;
import dev.mcms.shaders.metal.HeadlessGpu;
import dev.mcms.shaders.pack.PackModel;
import dev.mcms.shaders.pack.PackSource;
import dev.mcms.shaders.pack.StandardMacros;
import dev.mcms.shaders.runtime.uniforms.CustomUniforms;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The frame head runs the pack's kernels on the device (E6, finding 5a; the recording test is EngineFrameHeadGpuTest): the
 * setup kernel stores 0.25 into a custom image once after a load or a resize, and the begin kernel adds 0.25 every frame.
 * The image is read back after one frame, after a second frame, and after a resize, where setup runs again and the value
 * returns to 0.5.
 */
@Tag("gpu")
final class FrameHeadImageGpuTest {

	private static final int VIEW = 64, SIZE = 16, RGBA8 = 70;

	private static final String PROPERTIES = "image.counts=counts_s RGBA RGBA8 UNSIGNED_BYTE false false 16 16\n";

	private static final String SETUP = """
		#version 430
		layout(local_size_x = 1, local_size_y = 1) in;
		const ivec3 workGroups = ivec3(16, 16, 1);
		layout(rgba8) uniform image2D counts;
		void main() {
			imageStore(counts, ivec2(gl_GlobalInvocationID.xy), vec4(0.25));
		}
		""";

	private static final String BEGIN = """
		#version 430
		layout(local_size_x = 1, local_size_y = 1) in;
		const ivec3 workGroups = ivec3(16, 16, 1);
		layout(rgba8) uniform image2D counts;
		void main() {
			ivec2 p = ivec2(gl_GlobalInvocationID.xy);
			imageStore(counts, p, imageLoad(counts, p) + vec4(0.25));
		}
		""";

	@TempDir
	Path dir;

	@Test
	void theSetupAndBeginKernelsWriteTheImageThroughTheFrameHead() throws Exception {
		Files.createDirectories(dir.resolve("shaders"));
		Files.writeString(dir.resolve("shaders/shaders.properties"), PROPERTIES);
		Files.writeString(dir.resolve("shaders/setup.csh"), SETUP);
		Files.writeString(dir.resolve("shaders/begin.csh"), BEGIN);
		PackModel model;
		try (PackSource source = PackSource.open(dir)) {
			model = PackModel.load(source, StandardMacros.engine(), "en_us", new Properties());
		}
		assertTrue(model.problems().isEmpty(), model.problems().toString());
		List<Long> toRelease = new ArrayList<>();
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			long ctx = gpu.context(), enc = gpu.encoder();
			DimensionSet set = DimensionSet.load(ctx, "head-image", model, model.rootFolder(), CustomUniforms.of(model), RGBA8,
				model.rootFolder().union().colorFormats(), Engine.gamePipelines(), Engine.COPIES_NOT_COMPILED);
			assertEquals(0, set.absent(), "both kernels must compile: " + set.notes());
			CustomImages images = CustomImages.create(ctx, model.properties().images(), VIEW, VIEW);
			Targets targets = new Targets(set.dimension().union(), model.properties().bufferSizes(), set.usedTargets(), false,
				false, VIEW, VIEW, Targets.engineTextures(ctx), null, images);
			assertTrue(targets.create());
			long noise = Engine.makeNoise(ctx, set.noiseResolution());
			EngineFrame frame = new EngineFrame(set, targets, null, noise, 0, 0, (bytes, out) -> {
				bytes.rewind();
				long buffer = MetalBridge.newBuffer(ctx, Math.max(16, bytes.remaining()));
				MemorySegment.ofAddress(MetalBridge.bufferContents(buffer)).reinterpret(bytes.remaining())
					.copyFrom(MemorySegment.ofBuffer(bytes));
				toRelease.add(buffer);
				out[0] = buffer;
				out[1] = 0;
			}, Map.of(), Map.of(), new EngineFrame.Gbuffers(new int[0], new int[0], new int[0], false, phase -> { }, null));
			EngineFrame.PassBlocks blocks = new EngineFrame.PassBlocks() {
				@Override public long @Nullable [] frameBlock(String program) { return null; }
				@Override public long @Nullable [] customBlock() { return null; }
			};
			try {
				runFrame(frame, enc, targets, blocks);
				assertNear(128, value(gpu, images), "after one frame: setup stored 0.25 and begin added 0.25");
				runFrame(frame, enc, targets, blocks);
				assertNear(192, value(gpu, images), "after a second frame: begin added 0.25 again, setup did not run");
				frame.resized();
				runFrame(frame, enc, targets, blocks);
				assertNear(128, value(gpu, images), "after a resize: setup ran again, then begin added 0.25");
			} finally {
				frame.close();
				targets.release();
				set.passes().forEach(pass -> MetalBridge.release(pass.pipeline()));
				toRelease.forEach(MetalBridge::release);
				MetalBridge.release(noise);
			}
		}
	}

	/** One frame: the shadow hook's head, then the clear hook, committed so the image's writes are visible to a read. The targets own the image. */
	private static void runFrame(EngineFrame frame, long enc, Targets targets, EngineFrame.PassBlocks blocks) {
		frame.frameHead(enc, targets, blocks);
		frame.clearHook(enc, null, targets, blocks);
		MetalBridge.commitAndWait(enc);
	}

	/** The red byte of the image's first texel (every texel holds the same value). */
	private static int value(HeadlessGpu gpu, CustomImages images) {
		return gpu.readTexture(images.texture(0), 0, 0, SIZE, SIZE, 1, 4)[0] & 0xff;
	}

	/** An 8-bit value within two steps of the expected one (0.25 and 0.5 round to 64 and 128 in unorm8). */
	private static void assertNear(int expected, int actual, String message) {
		assertTrue(Math.abs(expected - actual) <= 2, message + ": expected " + expected + " but read " + actual);
	}
}
