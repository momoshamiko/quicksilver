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
import java.nio.ByteOrder;
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
 * A kernel whose shared array is above the threadgroup limit (E6, finding 5c): its arrays spill to the kernel's one device
 * buffer, which regrows when a larger view needs it (16 by 16 groups, then 64 by 64: 147 MB), and a view past the 256 MiB cap
 * (128 by 128 groups: 590 MB) is refused, so the kernel writes nothing there. Each group writes its own texel, so a texel
 * written by a larger dispatch proves the regrown buffer was bound.
 */
@Tag("gpu")
final class SpillDispatchGpuTest {

	private static final int RGBA8 = 70;
	private static final String PROPERTIES = "image.sums=sums_s RED_INTEGER R32UI UNSIGNED_INT false false 128 128\n";

	/** One group per texel (the view's groups, one invocation each); the shared array is 36000 bytes, above 32 KB. */
	private static final String KERNEL = """
		#version 430
		layout(local_size_x = 1, local_size_y = 1) in;
		const vec2 workGroupsRender = vec2(1.0, 1.0);
		layout(r32ui) uniform uimage2D sums;
		shared uint big[9000];
		void main() {
			ivec2 g = ivec2(gl_WorkGroupID.xy);
			big[8999] = uint(g.x * 1000 + g.y);
			imageStore(sums, g, uvec4(big[8999] + 1u));
		}
		""";

	/** The sums image is fixed at 128 by 128 texels, whatever the view. */
	private static final int IMAGE = 128;

	@TempDir
	Path dir;

	@Test
	void aSpillingKernelRegrowsItsBufferAtALargerViewAndIsRefusedOverTheCap() throws Exception {
		Files.createDirectories(dir.resolve("shaders"));
		Files.writeString(dir.resolve("shaders/shaders.properties"), PROPERTIES);
		Files.writeString(dir.resolve("shaders/begin.csh"), KERNEL);
		PackModel model;
		try (PackSource source = PackSource.open(dir)) {
			model = PackModel.load(source, StandardMacros.engine(), "en_us", new Properties());
		}
		assertTrue(model.problems().isEmpty(), model.problems().toString());
		List<Long> toRelease = new ArrayList<>();
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			long ctx = gpu.context(), enc = gpu.encoder();
			DimensionSet set = DimensionSet.load(ctx, "spill", model, model.rootFolder(), CustomUniforms.of(model), RGBA8,
				model.rootFolder().union().colorFormats(), Engine.gamePipelines(), Engine.COPIES_NOT_COMPILED);
			assertEquals(0, set.absent(), "the kernel must compile: " + set.notes());
			Targets small = targets(set, model, ctx, 16);
			Targets large = targets(set, model, ctx, 64);
			Targets huge = targets(set, model, ctx, 128);
			long noise = Engine.makeNoise(ctx, set.noiseResolution());
			EngineFrame frame = new EngineFrame(set, small, null, noise, 0, 0, (bytes, out) -> {
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
				runFrame(frame, enc, small, blocks);
				assertEquals(3006, texel(gpu, small, 3, 5), "16 by 16 groups: the spill buffer holds every group's copy");
				runFrame(frame, enc, large, blocks);
				assertEquals(3006, texel(gpu, large, 3, 5), "64 by 64 groups: the buffer regrew to 147 MB and the kernel ran");
				assertEquals(60061, texel(gpu, large, 60, 60), "the group (60, 60) of the larger view wrote its texel");
				runFrame(frame, enc, huge, blocks);
				assertEquals(0, texel(gpu, huge, 100, 100), "128 by 128 groups need 590 MB, over the cap: the dispatch is refused");
				runFrame(frame, enc, huge, blocks);
				assertEquals(0, texel(gpu, huge, 100, 100), "and it is refused again, with no second allocation");
			} finally {
				frame.close();
				small.release();
				large.release();
				huge.release();
				set.passes().forEach(pass -> MetalBridge.release(pass.pipeline()));
				toRelease.forEach(MetalBridge::release);
				MetalBridge.release(noise);
			}
		}
	}

	/** Lays out the targets of one view size, each with its own images (a Targets releases its images). */
	private static Targets targets(DimensionSet set, PackModel model, long ctx, int view) {
		CustomImages images = CustomImages.create(ctx, model.properties().images(), view, view);
		Targets targets = new Targets(set.dimension().union(), model.properties().bufferSizes(), set.usedTargets(), false,
			false, view, view, Targets.engineTextures(ctx), null, images);
		assertTrue(targets.create());
		return targets;
	}

	/** One frame's head and clear hook at a view size, committed so the kernel's writes are visible to a read. */
	private static void runFrame(EngineFrame frame, long enc, Targets targets, EngineFrame.PassBlocks blocks) {
		frame.frameHead(enc, targets, blocks);
		frame.clearHook(enc, null, targets, blocks);
		MetalBridge.commitAndWait(enc);
	}

	/** The texel of the sums image at (x, y), read as a little-endian word. */
	private static int texel(HeadlessGpu gpu, Targets targets, int x, int y) {
		byte[] bytes = gpu.readTexture(targets.customImages().texture(0), 0, 0, IMAGE, IMAGE, 1, 4);
		return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt((y * IMAGE + x) * 4);
	}
}
