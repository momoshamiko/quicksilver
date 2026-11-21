// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcms.metal.MetalBridge;
import dev.mcms.shaders.metal.HeadlessGpu;
import dev.mcms.shaders.pack.DimensionPrograms;
import dev.mcms.shaders.pack.PackModel;
import dev.mcms.shaders.pack.PackSource;
import dev.mcms.shaders.pack.StandardMacros;
import dev.mcms.shaders.runtime.gbuffers.GbufferPhase;
import dev.mcms.shaders.runtime.gbuffers.GbufferPlans;
import dev.mcms.shaders.runtime.gbuffers.Horizon;
import dev.mcms.shaders.runtime.uniforms.CustomUniforms;
import dev.mcms.shaders.runtime.uniforms.FrameUniforms;
import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Complementary's Overworld set, built the way {@code Engine.install} builds it (with the bench's options file), on the
 * headless device. Every target a gbuffers phase layout names must exist with two live textures, and the clear hook (the
 * primed load copies included) must run on a real command buffer. Before the fix the opaque programs' colortex13 had no
 * texture: its handle was zero and the primed copy read it (the game's EXC_BAD_ACCESS in the clear hook).
 */
@Tag("gpu")
@Tag("corpus")
class ComplementaryPrimeGpuTest {

	private static final int W = 1920, H = 1080, RGBA8 = 70;
	private static final String PACK = "ComplementaryReimagined_r5.9.3.zip";

	@Test
	void gbuffersTargetsExistAndTheClearHookRuns() throws IOException {
		Path zip = corpusFile();
		Properties options = new Properties();
		options.load(Files.newBufferedReader(optionsFile()));
		PackModel model;
		try (PackSource source = PackSource.open(zip)) {
			model = PackModel.load(source, StandardMacros.engine(), "en_us", options);
		}
		CustomUniforms custom = CustomUniforms.of(model);
		DimensionPrograms dimension = Engine.programsOf(model, "minecraft:overworld");
		List<Long> toRelease = new ArrayList<>();
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			long ctx = gpu.context();
			DimensionSet set = DimensionSet.load(ctx, PACK, model, dimension, custom, RGBA8,
				dimension.union().colorFormats(), Engine.gamePipelines(), Engine.COPIES_NOT_COMPILED);
			try {
				GbufferPlans plans = set.plans();
				assertTrue(plans != null, "the set must carry its gbuffers plans");
				plans.completeCopies(GbufferPlans.renderThreadCopier());
				Targets targets = Engine.newTargets(set, model, W, H, ctx);
				assertTrue(targets.create(), "the device must create the targets");
				long noise = Engine.makeNoise(ctx, set.noiseResolution());
				toRelease.add(noise);
				long copy = Engine.copyPipeline(ctx, targets.format(0));
				toRelease.add(copy);
				EngineFrame.Gbuffers gbuffers = Engine.gbuffersOf(plans, phase -> { });

				List<String> unbuilt = new ArrayList<>();
				List<String> dead = new ArrayList<>();
				for (GbufferPhase phase : GbufferPhase.values()) {
					for (int t : plans.layout(phase)) {
						if (t >= targets.count()) {
							unbuilt.add(phase + " colortex" + t);
						} else if (targets.read(t) == 0 || targets.write(t) == 0) {
							dead.add(phase + " colortex" + t);
						}
					}
				}
				assertTrue(unbuilt.isEmpty(), "gbuffers layouts name targets the set never builds: " + unbuilt);
				assertTrue(dead.isEmpty(), "gbuffers targets without live textures: " + dead);

				EngineFrame.PassBlocks blocks = blocks(ctx, custom, toRelease);
				EngineFrame frame = new EngineFrame(set, targets, null, noise, copy, 0, (bytes, out) -> {
					long[] slice = upload(ctx, bytes, toRelease);
					out[0] = slice[0];
					out[1] = slice[1];
				}, model.properties().flips(), model.properties().scales(), gbuffers);
				Horizon horizon = new Horizon(ctx, Engine.gamePipelines(), gbuffers.sky().groups().length, new FrameUniforms());
				frame.horizon(horizon);
				// E9: at the bench's options colortex7 (written by composite alone) and colortex13 (written by the opaque and
				// translucent programs) are read by no program, so composite, whose one output is colortex7, does not run.
				assertEquals(java.util.Set.of(7, 13), set.deadTargets(), "Complementary's dead targets at the bench's options");
				assertEquals(java.util.Set.of("composite"), frame.skippedPasses(), "the passes the frame skips");
				// E9: every full-screen pass of Complementary puts the quad over the whole viewport (gl_Position = ftransform()
				// alone) and discards nothing, so each keeps its "don't care" loads.
				for (CompiledProgram pass : set.passes()) {
					if (pass.runs()) {  // a compute-only pass has no stages, so no quad
						assertTrue(pass.reflection().flags().coversViewport() && !pass.reflection().flags().discards(),
							pass.name() + " covers the viewport");
					}
				}
				for (int t : frame.primed()) {
					assertTrue(targets.read(t) != 0 && targets.write(t) != 0, "primed colortex" + t + " has live textures");
					assertEquals(targets.format(t), targets.formatRecord(t).format(), "primed colortex" + t + " format");
				}
				long enc = gpu.encoder();
				frame.clearHook(enc, null, targets, blocks);
				MetalBridge.commitAndWait(enc);
				horizon.close();
				frame.close();
			} finally {
				Engine.releaseSet(set);
				for (long handle : toRelease) {
					MetalBridge.release(handle);
				}
			}
		}
	}

	private static EngineFrame.PassBlocks blocks(long ctx, CustomUniforms custom, List<Long> toRelease) {
		ByteBuffer frameBytes = ByteBuffer.allocate(FrameUniforms.size()).order(ByteOrder.LITTLE_ENDIAN);
		new FrameUniforms().write(frameBytes);
		long[] frame = upload(ctx, frameBytes, toRelease);
		long[] customBlock = null;
		if (custom != null && custom.size() > 0) {
			ByteBuffer customBytes = ByteBuffer.allocate(custom.size()).order(ByteOrder.LITTLE_ENDIAN);
			custom.write(customBytes);
			customBlock = upload(ctx, customBytes, toRelease);
		}
		long[] customSlice = customBlock;
		return new EngineFrame.PassBlocks() {
			@Override
			public long[] frameBlock(String program) {
				return frame;
			}

			@Override
			public long[] customBlock() {
				return customSlice;
			}
		};
	}

	private static long[] upload(long ctx, ByteBuffer bytes, List<Long> tracker) {
		bytes.rewind();
		long buffer = MetalBridge.newBuffer(ctx, Math.max(16, bytes.remaining()));
		MemorySegment.ofAddress(MetalBridge.bufferContents(buffer)).reinterpret(bytes.remaining())
			.copyFrom(MemorySegment.ofBuffer(bytes));
		tracker.add(buffer);
		return new long[] {buffer, 0};
	}

	private static Path corpusFile() {
		String dir = System.getProperty("mcms.test.corpus");
		Assumptions.assumeTrue(dir != null, "no corpus folder given");
		Path zip = Path.of(dir).resolve(PACK);
		Assumptions.assumeTrue(Files.isRegularFile(zip), "no " + PACK + " in the corpus folder");
		return zip;
	}

	private static Path optionsFile() {
		Path dir = Path.of(System.getProperty("user.dir"));
		for (int i = 0; i < 8; i++) {
			Path candidate = dir.resolve(Path.of("bench", "pack-options", PACK + ".txt"));
			if (Files.isRegularFile(candidate)) {
				return candidate;
			}
			dir = dir.getParent();
		}
		Assumptions.abort("bench/pack-options/" + PACK + ".txt not found above the test folder");
		return null;
	}
}
