// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.gbuffers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import dev.mcms.glsl.Translator;
import dev.mcms.glsl.msl.ProgramRequest;
import dev.mcms.glsl.msl.ProgramRequest.ColorTarget;
import dev.mcms.glsl.msl.ProgramRequest.Semantic;
import dev.mcms.glsl.msl.ProgramRequest.StageSource;
import dev.mcms.glsl.msl.ProgramRequest.UniformSchema;
import dev.mcms.glsl.msl.ProgramRequest.VertexAttribute;
import dev.mcms.glsl.msl.ProgramRequest.VertexLayout;
import dev.mcms.glsl.msl.ProgramTranslation;
import dev.mcms.glsl.sema.SourceLine;
import dev.mcms.glsl.sema.Stage;
import dev.mcms.metal.MetalBridge;
import dev.mcms.shaders.metal.HeadlessGpu;
import dev.mcms.shaders.metal.ShaderNative;
import dev.mcms.shaders.pack.PackModel;
import dev.mcms.shaders.pack.PackSource;
import dev.mcms.shaders.pack.StandardMacros;
import dev.mcms.shaders.runtime.DimensionSet;
import dev.mcms.shaders.runtime.EngineFrame;
import dev.mcms.shaders.runtime.EngineSlots;
import dev.mcms.shaders.runtime.Targets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;

/**
 * The flip and carry model for one target written by all three gbuffers phases (E4 review, Main's open-scene bug). The
 * sky draws the left half of colortex0, the opaque phase the bottom right quarter, the translucent phase the top right
 * quarter; the composite hook then shows colortex0 on the main colour (the final). The final must hold all three
 * colours: red for the sky half, green and blue for the quarters, in equal counts. The translucent phase's hand-over is
 * either kind (E9): the flip when no program of the phase samples colortex0, the load copy when one does.
 */
@Tag("gpu")
class EngineFlipGpuTest {

	private static final int W = 8, H = 8;
	private static final int RGBA8 = 70;
	private static PackModel model;

	@BeforeAll
	static void loadPack() throws Exception {
		Path dir = Path.of(System.getProperty("user.dir"));
		Path pack = null;
		for (int i = 0; i < 6 && pack == null; i++) {
			Path candidate = dir.resolve(Path.of("bench", "test-packs", "mcms-passes"));
			if (Files.isDirectory(candidate)) {
				pack = candidate;
			}
			dir = dir.getParent();
		}
		assertTrue(pack != null, "bench/test-packs/mcms-passes not found above " + System.getProperty("user.dir"));
		Properties options = new Properties();
		options.setProperty("MCMS_HIGH_TARGETS", "true");
		try (PackSource source = PackSource.open(pack)) {
			model = PackModel.load(source, StandardMacros.engine(), "en_us", options);
		}
	}

	private static List<SourceLine> lines(String text) {
		List<SourceLine> out = new ArrayList<>();
		int count = (int) text.lines().count();
		for (int i = 1; i <= count; i++) {
			out.add(new SourceLine("flip.glsl", i));
		}
		return out;
	}

	/** A colour-only pipeline writing {@code colour} to target 0 (the translated constant-colour program). */
	private static long colourPipeline(long ctx, String name, String colour) {
		String vs = "#version 120\nvoid main() { gl_Position = gl_Vertex; }\n";
		String fs = "#version 120\nvoid main() { gl_FragData[0] = vec4(" + colour + ", 1.0); }\n";
		ProgramRequest request = new ProgramRequest(name, ProgramRequest.Family.GBUFFERS,
			List.of(new StageSource(Stage.VERTEX, vs, lines(vs)), new StageSource(Stage.FRAGMENT, fs, lines(fs))),
			VertexLayout.of(new VertexAttribute(Semantic.POSITION, "float3", 0, 0)),
			List.of(new ColorTarget(0, 0, 4)), List.of(), List.of(), List.of(), UniformSchema.none(), null, false);
		ProgramTranslation translation = Translator.translate(request);
		assertTrue(translation.ok(), "translation of " + name + " failed");
		int[] vertex = GbufferPlans.vertexWords(12, List.of(new int[] {0, 0, MetalBridge.vertexFormat(GpuFormat.RGB32_FLOAT)}));
		int[] descriptor = GbufferPlans.descriptorWords(vertex, new int[] {0}, new int[] {RGBA8}, Set.of(0), target -> null,
			MetalBridge.topologyClass(PrimitiveTopology.TRIANGLES), false);
		ShaderNative.Compiled library = ShaderNative.libraryNew(ctx, translation.msl(), ShaderNative.MSL_3_1, ShaderNative.MATH_PACKS, true);
		assertTrue(library.ok(), "library failed: " + library.error());
		try {
			return MetalBridge.pipelineNew(ctx, library.library(), translation.vertexEntry(), library.library(),
				translation.fragmentEntry(), descriptor);
		} finally {
			MetalBridge.release(library.library());
		}
	}

	/** A quad over the NDC rectangle, as two triangles, from a vertex buffer the caller releases after the commit. */
	private static void quad(long ctx, long enc, long pso, float x0, float y0, float x1, float y1, List<Long> buffers) {
		long buffer = MetalBridge.newBuffer(ctx, 4L * 18);
		buffers.add(buffer);
		long at = MetalBridge.bufferContents(buffer);
		float[] v = {x0, y0, 0, x1, y0, 0, x1, y1, 0, x0, y0, 0, x1, y1, 0, x0, y1, 0};
		for (int i = 0; i < v.length; i++) {
			MemoryUtil.memPutFloat(at + 4L * i, v[i]);
		}
		MetalBridge.renderPipeline(enc, pso, 0, false, false, 0, 0, MetalBridge.primitive(PrimitiveTopology.TRIANGLES));
		MetalBridge.vertexBuffer(enc, EngineSlots.VERTEX_DATA, buffer, 0);
		MetalBridge.renderDraw(enc, 6, 1, 0, 0);
	}

	private static void run(boolean opaqueSamplesColortex0, boolean translucentSamplesColortex0) {
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			long ctx = gpu.context();
			long enc = gpu.encoder();
			DimensionSet set = new DimensionSet(model.rootFolder(), List.of(), 0, 0, 0, 0, 0, 0, List.of());
			Targets targets = new Targets(set.dimension().union(), model.properties().bufferSizes(), Set.of(0), false, false,
				W, H, Targets.engineTextures(ctx), null);
			assertTrue(targets.create(), "the device must create the targets");
			long sky = colourPipeline(ctx, "sky", "1.0, 0.0, 0.0");
			long opaque = colourPipeline(ctx, "opaque", "0.0, 1.0, 0.0");
			long translucent = colourPipeline(ctx, "translucent", "0.0, 0.0, 1.0");
			long main = MetalBridge.newTexture(ctx, GpuFormat.RGBA8_UNORM, W, H, false);
			int[] carried = opaqueSamplesColortex0 ? new int[] {0} : new int[0];
			int[] translucentSampled = translucentSamplesColortex0 ? new int[] {0} : new int[0];
			EngineFrame.Gbuffers gbuffers = new EngineFrame.Gbuffers(new int[] {0}, new int[] {0}, new int[] {0}, true,
				phase -> { }, null, new int[0], carried, translucentSampled);
			EngineFrame frame = new EngineFrame(set, targets, null, 0, 0, 0, (bytes, out) -> { }, Map.of(), Map.of(), gbuffers);
			EngineFrame.PassBlocks blocks = new EngineFrame.PassBlocks() {
				@Override
				public long @org.jspecify.annotations.Nullable [] frameBlock(String program) {
					return null;
				}

				@Override
				public long @org.jspecify.annotations.Nullable [] customBlock() {
					return null;
				}
			};
			List<Long> buffers = new ArrayList<>();
			try {
				frame.clearHook(enc, null, targets, blocks);
				// The sky: the left half of colortex0 (its write texture).
				MetalBridge.renderBegin(enc, new long[] {targets.write(0)}, null, 0, false, 0, W, H);
				quad(ctx, enc, sky, -1f, -1f, 0f, 1f, buffers);
				MetalBridge.endEncoders(enc);
				frame.opaqueHook(enc, targets);
				// The opaque phase: the bottom right quarter.
				MetalBridge.renderBegin(enc, new long[] {targets.write(0)}, null, 0, false, 0, W, H);
				quad(ctx, enc, opaque, 0f, -1f, 1f, 0f, buffers);
				MetalBridge.endEncoders(enc);
				frame.phaseHook(enc, null, targets, blocks, null);
				// The translucent phase: the top right quarter, on the attachments the phase's split reopens.
				MetalBridge.renderBegin(enc, new long[] {targets.write(0)}, null, 0, false, 0, W, H);
				quad(ctx, enc, translucent, 0f, 0f, 1f, 1f, buffers);
				MetalBridge.endEncoders(enc);
				frame.compositeHook(enc, null, targets, withMain(main), blocks);
				MetalBridge.endEncoders(enc);
				MetalBridge.commitAndWait(enc);
				byte[] pixels = gpu.readTexture(main, 0, 0, W, H, 1, 4);
				int red = 0, green = 0, blue = 0, other = 0;
				for (int i = 0; i < W * H; i++) {
					int r = pixels[4 * i] & 0xff, g = pixels[4 * i + 1] & 0xff, b = pixels[4 * i + 2] & 0xff;
					if (r == 255 && g == 0 && b == 0) red++;
					else if (r == 0 && g == 255 && b == 0) green++;
					else if (r == 0 && g == 0 && b == 255) blue++;
					else other++;
				}
				assertEquals(W * H / 2, red, "the sky's half of the final");
				assertEquals(W * H / 4, green, "the opaque phase's quarter of the final (lost if only the sky reaches it)");
				assertEquals(W * H / 4, blue, "the translucent phase's quarter of the final (lost if only the sky reaches it)");
				assertEquals(0, other, "no other colour in the final");
			} finally {
				frame.close();
				buffers.forEach(MetalBridge::release);
				targets.release();
			}
		}
	}

	private static EngineFrame.Main withMain(long texture) {
		EngineFrame.Main main = new EngineFrame.Main();
		main.set(texture, RGBA8, W, H, null);
		return main;
	}

	@Test
	void aTargetWrittenByTheSkyOpaqueAndTranslucentPhasesReachesTheFinalWithoutACarry() {
		run(false, false);
	}

	@Test
	void aTargetWrittenByTheSkyOpaqueAndTranslucentPhasesReachesTheFinalWithTheCarry() {
		run(true, false);
	}

	@Test
	void aTargetWrittenByAllThreePhasesReachesTheFinalWithTheTranslucentLoadCopy() {
		run(false, true);
	}

	/**
	 * RIL-563: a target only Distant Horizons' terrain writes (the distant phase, drawn after the opaque hook) flips before
	 * {@code deferred}, so the full-screen passes read the distant draw, not the target's clear (mcms-dhproj read zeros).
	 */
	@Test
	void aTargetOnlyTheDistantPhaseWritesReachesTheDeferredPasses() {
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			long ctx = gpu.context();
			long enc = gpu.encoder();
			DimensionSet set = new DimensionSet(model.rootFolder(), List.of(), 0, 0, 0, 0, 0, 0, List.of());
			Targets targets = new Targets(set.dimension().union(), model.properties().bufferSizes(), Set.of(0, 1), false,
				false, W, H, Targets.engineTextures(ctx), null);
			assertTrue(targets.create(), "the device must create the targets");
			long distant = colourPipeline(ctx, "distant", "0.0, 1.0, 0.0");
			EngineFrame.Gbuffers gbuffers = new EngineFrame.Gbuffers(PhaseTargets.single(new int[] {0}),
				PhaseTargets.single(new int[] {0}), PhaseTargets.single(new int[0]), true, phase -> { }, null, new int[0],
				new int[0], new int[0], new int[GbufferPhase.values().length][0], new int[0], new int[] {1});
			EngineFrame frame = new EngineFrame(set, targets, null, 0, 0, 0, (bytes, out) -> { }, Map.of(), Map.of(), gbuffers);
			EngineFrame.PassBlocks blocks = new EngineFrame.PassBlocks() {
				@Override
				public long @org.jspecify.annotations.Nullable [] frameBlock(String program) {
					return null;
				}

				@Override
				public long @org.jspecify.annotations.Nullable [] customBlock() {
					return null;
				}
			};
			List<Long> buffers = new ArrayList<>();
			try {
				frame.clearHook(enc, null, targets, blocks);
				frame.opaqueHook(enc, targets);
				// Distant Horizons' terrain: all of colortex1 (its write texture), which no other phase writes.
				MetalBridge.renderBegin(enc, new long[] {targets.write(1)}, null, 0, false, 0, W, H);
				quad(ctx, enc, distant, -1f, -1f, 1f, 1f, buffers);
				MetalBridge.endEncoders(enc);
				frame.phaseHook(enc, null, targets, blocks, null);
				MetalBridge.endEncoders(enc);
				MetalBridge.commitAndWait(enc);
				byte[] pixels = gpu.readTexture(targets.read(1), 0, 0, W, H, 1, 4);
				int green = 0;
				for (int i = 0; i < W * H; i++) {
					if ((pixels[4 * i] & 0xff) == 0 && (pixels[4 * i + 1] & 0xff) == 255 && (pixels[4 * i + 2] & 0xff) == 0) {
						green++;
					}
				}
				assertEquals(W * H, green, "colortex1's read texture after the phase hook holds the distant draw");
			} finally {
				frame.close();
				buffers.forEach(MetalBridge::release);
				MetalBridge.release(distant);
				targets.release();
			}
		}
	}
}
