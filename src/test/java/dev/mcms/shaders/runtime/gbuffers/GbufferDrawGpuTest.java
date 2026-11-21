// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.gbuffers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.vertex.VertexFormat;
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
import dev.mcms.metal.BackendPassHarness;
import dev.mcms.shaders.metal.HeadlessGpu;
import dev.mcms.shaders.metal.ShaderNative;
import dev.mcms.shaders.runtime.EngineSlots;
import dev.mcms.shaders.runtime.ProgramRequests;
import dev.mcms.shaders.runtime.entities.EntityCapture;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.StagedVertexBuffer.Draw;
import net.minecraft.client.renderer.StagedVertexBuffer.ExecuteInfo;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;

/**
 * The delegate's draw path on the GPU (plan.md E4): translated gbuffers programs into a multi-target layout with the
 * flipped depth compare, a game-pipeline variant's projection, the pack's per-draw values, blocks, samplers, constant
 * bytes and per-vertex ids at their slots, and the per-draw allocation count.
 */
@Tag("gpu")
class GbufferDrawGpuTest {

	private static final int W = 4, H = 4;
	private static final int RGBA8 = 70;
	/** A float3 position at buffer 0 (the vertex half of a request with one POSITION attribute). */
	private static final int[] POSITION_FLOAT3 = {0, 0, MetalBridge.vertexFormat(GpuFormat.RGB32_FLOAT)};
	private static final float[] IDENTITY = new Matrix4f().get(new float[16]);
	/**
	 * A pack variant whose pipelines are hand-written libraries (the delegate does not care which compiler built them):
	 * {@code pipeline} with a depth attachment, {@code pipelineNoDepth} without one.
	 */
	private record Stub(long pipeline, long pipelineNoDepth, GbufferPlans.DepthBinding depth,
			List<ProgramRequest.TextureBinding> textures, List<ProgramRequest.BufferBinding> buffers, int perVertexIdSlot,
			int renderStage) implements GbufferPlans.PackVariant {
		@Override
		public int group() {
			return 0;
		}

		@Override
		public long pipeline(boolean hasDepth) {
			return hasDepth ? this.pipeline : this.pipelineNoDepth;
		}

		@Override
		public GbufferPlans.DepthBinding depth(boolean reversed) {
			return this.depth;
		}

		@Override
		public boolean cull() {
			return false;
		}

		@Override
		public boolean wireframe() {
			return false;
		}

		@Override
		public int primitive() {
			return MetalBridge.primitive(PrimitiveTopology.TRIANGLES);
		}

		@Override
		public String program() {
			return "stub";
		}

		@Override
		public float alphaTestRef() {
			return 0.1f;
		}

		@Override
		public ProgramTranslation.Reflection reflection() {
			// The stub's pipelines sample each game texture in the fragment stage, through the slot its binding names.
			List<ProgramTranslation.Reflection.SamplerUse> samplers = new ArrayList<>();
			for (ProgramRequest.TextureBinding t : this.textures) {
				if (t.samplerSlot() >= 0) {
					samplers.add(new ProgramTranslation.Reflection.SamplerUse(t.name(), Stage.FRAGMENT, t.samplerSlot()));
				}
			}
			return new ProgramTranslation.Reflection(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
				new ProgramTranslation.Reflection.Flags(false, false, false, false, false, false, false, false, false),
				new int[] {1, 1, 1}, 0, List.of(), samplers);
		}

		@Override
		public List<ProgramRequest.ImageBinding> images() {
			return List.of();
		}

		@Override
		public int overlaySlot() {
			return -1;
		}
	}

	/** The engine's frame as the delegate reads it: an identity GL projection, one pack block named Foo, no textures. */
	private record Frame(long block) implements GbufferDelegate.Bindings {
		@Override
		public long[] frameBlock(String program) {
			return null;
		}

		@Override
		public long[] customBlock() {
			return null;
		}

		@Override
		public long[] block(String name) {
			return name.equals("Foo") ? new long[] {block, 0} : null;
		}

		@Override
		public long texture(String colortexName, boolean write) {
			return 0;
		}

		@Override
		public long depth(String name) {
			return 0;
		}

		@Override
		public long noise() {
			return 0;
		}

		@Override
		public long defaultTexture(String name) {
			return 0;
		}

		@Override
		public void projection(float[] out) {
			System.arraycopy(IDENTITY, 0, out, 0, 16);
		}
	}

	private static List<SourceLine> lines(String text) {
		List<SourceLine> out = new ArrayList<>();
		int count = (int) text.lines().count();
		for (int i = 1; i <= count; i++) {
			out.add(new SourceLine("draw.glsl", i));
		}
		return out;
	}

	/** Translates a gbuffers draw of one POSITION attribute (buffer 0) from GLSL sources. */
	private static ProgramTranslation translateDraw(String name, String vs, String fs, List<ColorTarget> outputs) {
		ProgramRequest request = new ProgramRequest(name, ProgramRequest.Family.GBUFFERS,
			List.of(new StageSource(Stage.VERTEX, vs, lines(vs)), new StageSource(Stage.FRAGMENT, fs, lines(fs))),
			VertexLayout.of(new VertexAttribute(Semantic.POSITION, "float3", 0, 0)),
			outputs, List.of(), List.of(), List.of(), UniformSchema.none(), null, false);
		ProgramTranslation translation = Translator.translate(request);
		assertTrue(translation.ok(), "translation of " + name + " failed");
		return translation;
	}

	/** A pipeline built with the descriptor the plans build: targets 0..n-1 in RGBA8, all written, no blend. */
	private static long pipeline(long ctx, String msl, String vertex, String fragment, int[] vertexWords, int targets) {
		int[] indices = new int[targets];
		int[] formats = new int[targets];
		Set<Integer> written = new HashSet<>();
		for (int i = 0; i < targets; i++) {
			indices[i] = i;
			formats[i] = RGBA8;
			written.add(i);
		}
		int[] descriptor = GbufferPlans.descriptorWords(vertexWords, indices, formats, written, target -> null,
			MetalBridge.topologyClass(PrimitiveTopology.TRIANGLES), true);
		ShaderNative.Compiled library = ShaderNative.libraryNew(ctx, msl, ShaderNative.MSL_3_1, ShaderNative.MATH_PACKS, true);
		assertTrue(library.ok(), "library failed: " + library.error());
		try {
			return MetalBridge.pipelineNew(ctx, library.library(), vertex, library.library(), fragment, descriptor);
		} finally {
			MetalBridge.release(library.library());
		}
	}

	/** The flipped game depth state (GREATER_THAN_OR_EQUAL written, so LESS_THAN_OR_EQUAL against a 1.0 clear). */
	private static long flippedDepth(long ctx) {
		DepthStencilState flipped = GameDepth.flipped(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, true));
		return MetalBridge.depthStateNew(ctx, MetalBridge.compareWord(flipped.depthTest()), flipped.writeDepth());
	}

	/** A full-screen triangle at depth z, from a vertex buffer the caller releases after the commit. */
	private static void fullScreenTriangle(long ctx, long enc, long pso, long depth, float z, List<Long> buffers) {
		long buffer = MetalBridge.newBuffer(ctx, 36);
		buffers.add(buffer);
		long at = MetalBridge.bufferContents(buffer);
		float[] v = {-1, -1, z, 3, -1, z, -1, 3, z};
		for (int i = 0; i < v.length; i++) {
			MemoryUtil.memPutFloat(at + 4L * i, v[i]);
		}
		MetalBridge.renderPipeline(enc, pso, depth, false, false, 0, 0, MetalBridge.primitive(PrimitiveTopology.TRIANGLES));
		MetalBridge.vertexBuffer(enc, EngineSlots.VERTEX_DATA, buffer, 0);
		MetalBridge.renderDraw(enc, 3, 1, 0, 0);
	}

	private static int channel(byte[] pixels, int x, int y, int c) {
		return pixels[(y * W + x) * 4 + c] & 0xff;
	}

	private static long color(long ctx) {
		return MetalBridge.newTexture(ctx, GpuFormat.RGBA8_UNORM, W, H, false);
	}

	@Test
	void translatedProgramsWriteEveryTargetAndTheFlippedDepthRejectsTheFarDraw() {
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			long ctx = gpu.context();
			long enc = gpu.encoder();
			String vs = "#version 120\nvoid main() { gl_Position = gl_Vertex; }\n";
			String near = "#version 120\nvoid main() { gl_FragData[0] = vec4(0.0, 1.0, 0.0, 1.0); gl_FragData[1] = vec4(0.0, 0.0, 1.0, 1.0); }\n";
			String far = "#version 120\nvoid main() { gl_FragData[0] = vec4(1.0, 0.0, 0.0, 1.0); gl_FragData[1] = vec4(1.0, 1.0, 0.0, 1.0); }\n";
			List<ColorTarget> outputs = List.of(new ColorTarget(0, 0, 4), new ColorTarget(1, 1, 4));
			ProgramTranslation nearProgram = translateDraw("near", vs, near, outputs);
			ProgramTranslation farProgram = translateDraw("far", vs, far, outputs);
			long nearPso = pipeline(ctx, nearProgram.msl(), nearProgram.vertexEntry(), nearProgram.fragmentEntry(),
				GbufferPlans.vertexWords(12, List.of(POSITION_FLOAT3)), 2);
			long farPso = pipeline(ctx, farProgram.msl(), farProgram.vertexEntry(), farProgram.fragmentEntry(),
				GbufferPlans.vertexWords(12, List.of(POSITION_FLOAT3)), 2);
			long depth = flippedDepth(ctx);
			long target0 = color(ctx);
			long target1 = color(ctx);
			long depthTarget = MetalBridge.newTexture(ctx, GpuFormat.D32_FLOAT, W, H, false);
			float[] clear = {0, 0, 0, 1};
			List<Long> buffers = new ArrayList<>();
			MetalBridge.renderBegin(enc, new long[] {target0, target1}, new float[][] {clear, clear}, depthTarget, true, 1.0f, W, H);
			fullScreenTriangle(ctx, enc, nearPso, depth, -0.4f, buffers);
			fullScreenTriangle(ctx, enc, farPso, depth, 0.2f, buffers);
			MetalBridge.endEncoders(enc);
			MetalBridge.commitAndWait(enc);
			byte[] first = gpu.readTexture(target0, 0, 0, W, H, 1, 4);
			byte[] second = gpu.readTexture(target1, 0, 0, W, H, 1, 4);
			for (int y = 0; y < H; y++) {
				for (int x = 0; x < W; x++) {
					assertEquals(0, channel(first, x, y, 0), "target 0 red at " + x + "," + y);
					assertEquals(255, channel(first, x, y, 1), "target 0 green (the near draw) at " + x + "," + y);
					assertEquals(0, channel(second, x, y, 0), "target 1 red at " + x + "," + y);
					assertEquals(255, channel(second, x, y, 2), "target 1 blue (the near draw) at " + x + "," + y);
				}
			}
			buffers.forEach(MetalBridge::release);
		}
	}

	/**
	 * A pass with no depth attachment ("See through features") draws through the pack state built without depth. The state
	 * with a Depth32Float attachment is invalid for that pass, so the pixel is written only when the delegate chose the
	 * no-depth state from {@code begin(enc, false)}.
	 */
	@Test
	void passWithoutADepthAttachmentDrawsThroughTheStateBuiltWithoutDepth() {
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			long ctx = gpu.context();
			long enc = gpu.encoder();
			String msl = """
				#include <metal_stdlib>
				using namespace metal;
				struct VOut { float4 position [[position]]; };
				vertex VOut packVs(uint vid [[vertex_id]]) {
					VOut o;
					o.position = float4(vid == 1 ? 3.0 : -1.0, vid == 2 ? 3.0 : -1.0, 0.5, 1.0);
					return o;
				}
				fragment float4 packFs(VOut in [[stage_in]]) {
					return float4(0.0, 1.0, 0.0, 1.0);
				}
				""";
			ShaderNative.Compiled library = ShaderNative.libraryNew(ctx, msl, ShaderNative.MSL_3_1, ShaderNative.MATH_PACKS, true);
			assertTrue(library.ok(), "pack library failed: " + library.error());
			long withDepth;
			long withoutDepth;
			try {
				int topology = MetalBridge.topologyClass(PrimitiveTopology.TRIANGLES);
				int[] vertex = GbufferPlans.vertexWords(0, List.of());
				withDepth = MetalBridge.pipelineNew(ctx, library.library(), "packVs", library.library(), "packFs",
					GbufferPlans.descriptorWords(vertex, new int[] {0}, new int[] {RGBA8}, Set.of(0), target -> null, topology, true));
				withoutDepth = MetalBridge.pipelineNew(ctx, library.library(), "packVs", library.library(), "packFs",
					GbufferPlans.descriptorWords(vertex, new int[] {0}, new int[] {RGBA8}, Set.of(0), target -> null, topology, false));
			} finally {
				MetalBridge.release(library.library());
			}
			GbufferPlans.PackVariant variant = new Stub(withDepth, withoutDepth,
				new GbufferPlans.DepthBinding(flippedDepth(ctx), 0, 0), List.of(), List.of(), -1, 0);
			GbufferDelegate delegate = new GbufferDelegate(GbufferPlans.forTest(Map.of()), new Frame(0));
			long target = color(ctx);
			MetalBridge.renderBegin(enc, new long[] {target}, new float[][] {{0, 0, 0, 1}}, 0, false, 0.0f, W, H);
			delegate.begin(enc, false);
			delegate.bindPack(enc, variant, List.of());
			assertTrue(delegate.beforeDraw(enc));
			MetalBridge.renderDraw(enc, 3, 1, 0, 0);
			MetalBridge.endEncoders(enc);
			MetalBridge.commitAndWait(enc);
			byte[] pixels = gpu.readTexture(target, 0, 0, W, H, 1, 4);
			assertEquals(255, channel(pixels, 0, 0, 1), "the depth-less pass drew through the state built without depth");
			delegate.close();
		}
	}

	@Test
	void packDrawBindsIdsBlocksSamplersConstantsAndQsDrawAtTheirSlots() throws Exception {
		try (HeadlessGpu gpu = new HeadlessGpu(); Arena arena = Arena.ofConfined()) {
			long ctx = gpu.context();
			long enc = gpu.encoder();
			String msl = """
				#include <metal_stdlib>
				using namespace metal;
				struct VOut { float4 position [[position]]; uint id [[flat]]; };
				vertex VOut packVs(uint vid [[vertex_id]]) {
					VOut o;
					o.position = float4(vid == 1 ? 3.0 : -1.0, vid == 2 ? 3.0 : -1.0, 0.5, 1.0);
					o.id = 10;
					return o;
				}
				fragment float4 packFs(VOut in [[stage_in]], constant uchar* draw [[buffer(25)]],
						constant float* sodium [[buffer(21)]], constant float4* block [[buffer(0)]]) {
					int stage = *(constant int*)(draw + %d);
					return float4(float(in.id) / 255.0, sodium[0], block[0].z, float(stage) / 255.0);
				}
				""".formatted(GbufferDelegate.drawOffset("renderStage"));
			ShaderNative.Compiled library = ShaderNative.libraryNew(ctx, msl, ShaderNative.MSL_3_1, ShaderNative.MATH_PACKS, true);
			assertTrue(library.ok(), "pack library failed: " + library.error());
			long pso;
			try {
				int[] descriptor = GbufferPlans.descriptorWords(GbufferPlans.vertexWords(0, List.of()), new int[] {0},
					new int[] {RGBA8}, Set.of(0), target -> null, MetalBridge.topologyClass(PrimitiveTopology.TRIANGLES), true);
				pso = MetalBridge.pipelineNew(ctx, library.library(), "packVs", library.library(), "packFs", descriptor);
			} finally {
				MetalBridge.release(library.library());
			}
			long block = MetalBridge.newBuffer(ctx, 16);
			long blockAt = MetalBridge.bufferContents(block);
			MemoryUtil.memPutFloat(blockAt + 8, 0.25f);
			MemoryUtil.memPutFloat(blockAt + 12, 1.0f);
			MemorySegment sodium = arena.allocate(4);
			sodium.set(ValueLayout.JAVA_FLOAT, 0, 0.5f);

			GbufferDelegate delegate = new GbufferDelegate(GbufferPlans.forTest(Map.of()), new Frame(block));
			delegate.constantBytes(EngineSlots.SODIUM_GLOBALS, sodium);
			GbufferPlans.PackVariant variant = new Stub(pso, pso, new GbufferPlans.DepthBinding(flippedDepth(ctx), 0, 0),
				List.of(new ProgramRequest.TextureBinding("texture", 0, ProgramRequest.TextureKind.COLOUR, -1,
					ProgramRequest.SamplerState.linear(), 1)),
				List.of(new ProgramRequest.BufferBinding("Foo", 0)), -1, 7);

			long target = color(ctx);
			long depthTarget = MetalBridge.newTexture(ctx, GpuFormat.D32_FLOAT, W, H, false);
			MetalBridge.renderBegin(enc, new long[] {target}, new float[][] {{0, 0, 0, 1}}, depthTarget, true, 1.0f, W, H);
			delegate.bindPack(enc, variant, List.of("Sampler0"));
			assertEquals(1, delegate.fragmentSamplerSlot(0), "the fragment stage samples through the reflection's slot");
			assertEquals(-1, delegate.vertexSamplerSlot(0), "the vertex stage samples nothing from the atlas");
			assertTrue(delegate.beforeDraw(enc));
			MetalBridge.renderDraw(enc, 3, 1, 0, 0);

			// The steady-state draw path allocates nothing (the E3 rule): warm up, then count the bytes of many draws.
			for (int i = 0; i < 1000; i++) {
				delegate.beforeDraw(enc);
			}
			com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
			long thread = Thread.currentThread().threadId();
			long before = threads.getThreadAllocatedBytes(thread);
			for (int i = 0; i < 10_000; i++) {
				delegate.beforeDraw(enc);
			}
			long allocated = threads.getThreadAllocatedBytes(thread) - before;
			assertTrue(allocated < 4096, "bytes allocated by 10000 draws: " + allocated);
			MetalBridge.endEncoders(enc);
			MetalBridge.commitAndWait(enc);

			byte[] pixels = gpu.readTexture(target, 0, 0, W, H, 1, 4);
			assertEquals(10, channel(pixels, 0, 0, 0), 1.0, "the constant id the vertex stage writes");
			assertEquals(128, channel(pixels, 0, 0, 1), 2.0, "constant bytes at slot 21");
			assertEquals(64, channel(pixels, 0, 0, 2), 2.0, "pack block at its slot");
			assertEquals(7, channel(pixels, 0, 0, 3), 1.0, "renderStage in QsDraw at slot 25");
			delegate.close();
			MetalBridge.release(block);
		}
	}

	/** The pass-through gbuffers_basic: its vertex stage writes gl_Color (26.3 sky.vsh has no colour input). */
	private static final String PASS_VS = "#version 120\nvoid main() { gl_Position = gl_Vertex; gl_FrontColor = gl_Color; }\n";
	private static final String PASS_FS = "#version 120\nvoid main() { gl_FragData[0] = gl_Color; }\n";

	/**
	 * Draws a full-screen triangle through the program of the sources {@code vs} and {@code fs} (one colour target), with
	 * the game's DynamicTransforms holding {@code modulator} (null: the draw has none; the game's ColorModulator sits at
	 * offset 128, assets/minecraft/shaders/include/dynamictransforms.glsl: two mat4s before it). {@code vertexColour} (null:
	 * the layout has no colour attribute) is the colour of every vertex, bound as the COLOR semantic. Returns the target's
	 * pixels; with the transforms bound, the steady-state draw path allocates nothing.
	 */
	private static byte[] draw(String vs, String fs, float[] modulator, float[] vertexColour) {
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			long ctx = gpu.context();
			long enc = gpu.encoder();
			int stride = vertexColour == null ? 12 : 28;
			int[] colour = {1, 12, MetalBridge.vertexFormat(GpuFormat.RGBA32_FLOAT)};
			List<VertexAttribute> attributes = vertexColour == null
				? List.of(new VertexAttribute(Semantic.POSITION, "float3", 0, 0))
				: List.of(new VertexAttribute(Semantic.POSITION, "float3", 0, 0),
					new VertexAttribute(Semantic.COLOR, "float4", 0, 12));
			ProgramRequest request = new ProgramRequest("basic", ProgramRequest.Family.GBUFFERS,
				List.of(new StageSource(Stage.VERTEX, vs, lines(vs)), new StageSource(Stage.FRAGMENT, fs, lines(fs))),
				VertexLayout.of(attributes.toArray(new VertexAttribute[0])),
				List.of(new ColorTarget(0, 0, 4)), List.of(), List.of(), List.of(),
				new UniformSchema(List.of(ProgramRequests.DRAW_BLOCK)), null, false);
			ProgramTranslation program = Translator.translate(request);
			assertTrue(program.ok(), "translation failed: " + program.diagnostics());
			List<int[]> words = vertexColour == null ? List.of(POSITION_FLOAT3) : List.of(POSITION_FLOAT3, colour);
			long pso = pipeline(ctx, program.msl(), program.vertexEntry(), program.fragmentEntry(),
				GbufferPlans.vertexWords(stride, words), 1);
			GbufferPlans.PackVariant variant = new Stub(pso, pso, new GbufferPlans.DepthBinding(flippedDepth(ctx), 0, 0),
				List.of(), List.of(), -1, 0);

			GpuBuffer transforms = MetalBridge.newGpuBuffer(ctx, 192);
			long transformsHandle = MetalBridge.bufferHandle(transforms);
			if (modulator != null) {
				for (int i = 0; i < 4; i++) {
					MemoryUtil.memPutFloat(MetalBridge.bufferAddress(transforms) + 128 + 4L * i, modulator[i]);
				}
			}
			long vertices = MetalBridge.newBuffer(ctx, 3 * stride);
			long at = MetalBridge.bufferContents(vertices);
			float[] corners = {-1, -1, -0.4f, 3, -1, -0.4f, -1, 3, -0.4f};
			for (int v = 0; v < 3; v++) {
				for (int c = 0; c < 3; c++) {
					MemoryUtil.memPutFloat(at + (long) stride * v + 4L * c, corners[3 * v + c]);
				}
				for (int c = 0; vertexColour != null && c < 4; c++) {
					MemoryUtil.memPutFloat(at + (long) stride * v + 12 + 4L * c, vertexColour[c]);
				}
			}
			long depthTarget = MetalBridge.newTexture(ctx, GpuFormat.D32_FLOAT, W, H, false);
			long target = color(ctx);
			GbufferDelegate delegate = new GbufferDelegate(GbufferPlans.forTest(Map.of()), new Frame(0));
			MetalBridge.renderBegin(enc, new long[] {target}, new float[][] {{0, 0, 0, 1}}, depthTarget, true, 1.0f, W, H);
			delegate.bindPack(enc, variant, List.of());
			if (modulator != null) {
				delegate.uniform(enc, "DynamicTransforms", new GpuBufferSlice(transforms, 0, 192));
			}
			MetalBridge.vertexBuffer(enc, EngineSlots.VERTEX_DATA, vertices, 0);
			assertTrue(delegate.beforeDraw(enc));
			MetalBridge.renderDraw(enc, 3, 1, 0, 0);

			if (modulator != null) {
				// The E3 rule, per draw: the transforms path (the colour copy) allocates nothing once warm.
				for (int i = 0; i < 1000; i++) {
					delegate.beforeDraw(enc);
				}
				com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
				long thread = Thread.currentThread().threadId();
				long before = threads.getThreadAllocatedBytes(thread);
				for (int i = 0; i < 10_000; i++) {
					delegate.beforeDraw(enc);
				}
				long allocated = threads.getThreadAllocatedBytes(thread) - before;
				assertTrue(allocated < 4096, "bytes allocated by 10000 draws with DynamicTransforms: " + allocated);
			}
			MetalBridge.endEncoders(enc);
			MetalBridge.commitAndWait(enc);
			byte[] pixels = gpu.readTexture(target, 0, 0, W, H, 1, 4);
			delegate.close();
			MetalBridge.release(vertices);
			MetalBridge.release(transformsHandle);
			return pixels;
		}
	}

	/** The sky's draw: {@link #draw} through the pass-through gbuffers_basic. */
	private static byte[] skyDraw(float[] modulator, float[] vertexColour) {
		return draw(PASS_VS, PASS_FS, modulator, vertexColour);
	}

	/** A sky-style draw whose DynamicTransforms ColorModulator is (0.25, 0.5, 0.75, 1) writes that colour. */
	@Test
	void skyStyleDrawWritesTheGameColorModulatorThroughPassThroughBasic() {
		byte[] pixels = skyDraw(new float[] {0.25f, 0.5f, 0.75f, 1.0f}, null);
		for (int[] at : new int[][] {{0, 0}, {W - 1, H - 1}}) {
			assertEquals(64, channel(pixels, at[0], at[1], 0), 2.0, "red at " + at[0] + "," + at[1]);
			assertEquals(128, channel(pixels, at[0], at[1], 1), 2.0, "green at " + at[0] + "," + at[1]);
			assertEquals(191, channel(pixels, at[0], at[1], 2), 2.0, "blue at " + at[0] + "," + at[1]);
			assertEquals(255, channel(pixels, at[0], at[1], 3), 2.0, "alpha at " + at[0] + "," + at[1]);
		}
	}

	/** A draw with no DynamicTransforms takes white for its colour modulator: the sky writes white. */
	@Test
	void skyStyleDrawWithoutDynamicTransformsWritesWhite() {
		byte[] pixels = skyDraw(null, null);
		for (int c = 0; c < 4; c++) {
			assertEquals(255, channel(pixels, 0, 0, c), 1.0, "channel " + c + " without DynamicTransforms");
		}
	}

	/**
	 * The vertex colour times the modulator: a colour attribute of (0.5, 1, 0.5, 1) under a modulator of (0.5, 0.5, 1, 1)
	 * writes (0.25, 0.5, 0.5, 1). The colour input with an attribute is a separate translation path from the sky's.
	 */
	@Test
	void vertexColourMultipliesTheColorModulator() {
		byte[] pixels = skyDraw(new float[] {0.5f, 0.5f, 1.0f, 1.0f}, new float[] {0.5f, 1.0f, 0.5f, 1.0f});
		assertEquals(64, channel(pixels, 0, 0, 0), 2.0, "red");
		assertEquals(128, channel(pixels, 0, 0, 1), 2.0, "green");
		assertEquals(128, channel(pixels, 0, 0, 2), 2.0, "blue");
		assertEquals(255, channel(pixels, 0, 0, 3), 2.0, "alpha");
	}

	/** vaColor is the raw vertex colour: OptiFine lists colorModulator as its own uniform, so the core input stays raw. */
	@Test
	void vaColorStaysTheRawVertexColour() {
		String vs = "#version 120\nattribute vec4 vaColor;\nvoid main() { gl_Position = gl_Vertex; gl_FrontColor = vaColor; }\n";
		byte[] pixels = draw(vs, PASS_FS, new float[] {0.5f, 0.5f, 1.0f, 1.0f}, new float[] {0.5f, 1.0f, 0.5f, 1.0f});
		assertEquals(128, channel(pixels, 0, 0, 0), 2.0, "red: the raw vertex colour, not times the modulator");
		assertEquals(255, channel(pixels, 0, 0, 1), 2.0, "green: the raw vertex colour");
		assertEquals(128, channel(pixels, 0, 0, 2), 2.0, "blue: the raw vertex colour");
		assertEquals(255, channel(pixels, 0, 0, 3), 2.0, "alpha");
	}

	/** The pack's colorModulator uniform is served from QsDraw: it reads the draw's modulator, not zero. */
	@Test
	void packColorModulatorUniformIsServedFromTheDrawBlock() {
		String fs = "#version 120\nuniform vec4 colorModulator;\nvoid main() { gl_FragData[0] = colorModulator; }\n";
		byte[] pixels = draw(PASS_VS, fs, new float[] {0.25f, 0.5f, 0.75f, 1.0f}, null);
		assertEquals(64, channel(pixels, 0, 0, 0), 2.0, "red");
		assertEquals(128, channel(pixels, 0, 0, 1), 2.0, "green");
		assertEquals(191, channel(pixels, 0, 0, 2), 2.0, "blue");
		assertEquals(255, channel(pixels, 0, 0, 3), 2.0, "alpha");
	}

	/** Three staged submits' ids (one triangle each) and the defaults for a buffer no upload published. */
	private static final int[] IDS_A = {21, 200, 7}, IDS_B = {42, 240, 9}, IDS_C = {5, 6, 8}, DEFAULTS = {3, 4, 5};
	private static final float[] CORNERS_A = {-1, -1, 0, 0, -1, 0, -1, 0, 0};
	private static final float[] CORNERS_B = {0, 0, 0, 1, 0, 0, 0, 1, 0};
	private static final float[] CORNERS_C = {0, -1, 0, 1, -1, 0, 1, 0, 0};
	private static final float[] CORNERS_D = {-1, 0, 0, 0, 0, 0, -1, 1, 0};
	private static final String IDS_VS = "#version 330\nin vec3 vaPosition;\nvoid main() { gl_Position = vec4(vaPosition, 1.0); }\n";
	private static final String IDS_FS = "#version 330\nuniform int entityId;\nuniform int blockEntityId;\n"
		+ "uniform int currentRenderedItemId;\nout vec4 c;\n"
		+ "void main() { c = vec4(float(entityId), float(blockEntityId), float(currentRenderedItemId), 255.0) / 255.0; }\n";

	/**
	 * The per-vertex ids through the game's own staging path (E4-D; review blocker 2): a real StagedVertexBuffer takes three
	 * submits' ids through the builder hooks (called by hand, with the write offsets the staging bytes have), the real
	 * upload packs them, the delegate publishes the upload, and a translated gbuffers program reads entityId,
	 * blockEntityId and currentRenderedItemId at each draw's base vertex. The pixels read back the submits' ids. A draw of
	 * a buffer no upload published reads the defaults record, and nothing is dropped.
	 */
	@Test
	void stagedUploadDeliversEachSubmitsIdsAndTheDefaultsToItsDraws() throws Exception {
		// MetalPipeline's cache dir reads the game dir, which no test has: the cache is off before any Metal class initialises.
		System.setProperty("mcms.metal.mslCache", "false");
		if (RenderSystem.tryGetDevice() == null) {
			RenderSystem.initRenderThread();  // the staged buffers assert they run on the render thread
			RenderSystem.initRenderer(BackendPassHarness.newDevice());
		}
		long ctx = MetalBridge.currentContext();
		long enc = MetalBridge.createHeadlessEncoder(ctx);
		int size = 8;
		VertexFormat format = DefaultVertexFormat.POSITION_COLOR;
		StagedVertexBuffer staged = new StagedVertexBuffer(() -> "per-vertex ids", 1 << 18);
		StagedVertexBuffer unpublished = new StagedVertexBuffer(() -> "no upload", 1 << 18);
		try {
			EntityCapture.Ranges ranges = new EntityCapture.Ranges();
			Draw a = staged.appendDraw(format, PrimitiveTopology.TRIANGLES);
			Draw b = staged.appendDraw(format, PrimitiveTopology.TRIANGLES);
			Draw c = staged.appendDraw(format, PrimitiveTopology.TRIANGLES);
			Draw d = unpublished.appendDraw(format, PrimitiveTopology.TRIANGLES);
			long at = submit(staged, ranges, a, IDS_A, 0, CORNERS_A);
			at = submit(staged, ranges, b, IDS_B, at, CORNERS_B);
			at = submit(staged, ranges, c, IDS_C, at, CORNERS_C);
			submit(unpublished, null, d, null, 0, CORNERS_D);
			ranges.lastBuilderFlushed(at);
			staged.upload();
			unpublished.upload();
			ExecuteInfo ia = staged.getExecuteInfo(a);
			ExecuteInfo ib = staged.getExecuteInfo(b);
			ExecuteInfo ic = staged.getExecuteInfo(c);
			ExecuteInfo id = unpublished.getExecuteInfo(d);
			ranges.addUploadedDraw(a, 16, ia.baseVertex());
			ranges.addUploadedDraw(b, 16, ib.baseVertex());
			ranges.addUploadedDraw(c, 16, ic.baseVertex());
			ranges.resolve(at, DEFAULTS);

			ProgramTranslation program = Translator.translate(new ProgramRequest("gbuffers_ids",
				ProgramRequest.Family.GBUFFERS,
				List.of(new StageSource(Stage.VERTEX, IDS_VS, lines(IDS_VS)),
					new StageSource(Stage.FRAGMENT, IDS_FS, lines(IDS_FS))),
				VertexLayout.of(new VertexAttribute(Semantic.POSITION, "float3", 0, 0)),
				List.of(new ColorTarget(0, 0, 4)), List.of(), List.of(), List.of(),
				new UniformSchema(List.of(ProgramRequests.DRAW_BLOCK)), null, false,
				new ProgramRequest.PerVertexIds(EngineSlots.PER_VERTEX_IDS, ProgramRequests.DRAW_BLOCK.name(),
					ProgramRequests.DRAW_IDS_DEFAULT)));
			assertTrue(program.ok(), "translation failed: " + program.diagnostics());
			long pso = pipeline(ctx, program.msl(), program.vertexEntry(), program.fragmentEntry(),
				GbufferPlans.vertexWords(16, List.of(POSITION_FLOAT3)), 1);
			GbufferPlans.PackVariant variant = new Stub(pso, pso, new GbufferPlans.DepthBinding(flippedDepth(ctx), 0, 0),
				List.of(), List.of(), EngineSlots.PER_VERTEX_IDS, 0);
			GbufferPlans.Plan plan = new GbufferPlans.Plan() {
				@Override
				public GbufferPlans.Variant variant(PassKind kind, GbufferPhase phase, GbufferMapping.Domain domain) {
					return variant;
				}

				@Override
				public String location() {
					return "ids";
				}

				@Override
				public String reason(PassKind kind, GbufferPhase phase, GbufferMapping.Domain domain) {
					return "";
				}
			};
			// Keyed under the 16-byte layout the draws select with: known strides take only their own layout's plan.
			GbufferDelegate delegate = new GbufferDelegate(GbufferPlans.forTestLayouts(Map.of("gbuffers_ids",
				Map.of(GbufferPlans.layoutKey(new int[] {16}), plan))), new Frame(0));
			delegate.publishVertexIds(ia.vertexBuffer(), ranges, DEFAULTS);

			long target = MetalBridge.newTexture(ctx, GpuFormat.RGBA8_UNORM, size, size, false);
			MetalBridge.renderBegin(enc, new long[] {target}, new float[][] {{0, 0, 0, 1}}, 0, false, 0.0f, size, size);
			delegate.begin(enc, false);
			drawStaged(delegate, enc, ia);
			drawStaged(delegate, enc, ib);
			drawStaged(delegate, enc, ic);
			drawStaged(delegate, enc, id);
			MetalBridge.endEncoders(enc);
			MetalBridge.commitAndWait(enc);
			byte[] pixels = new byte[size * size * 4];
			assertTrue(ShaderNative.textureRead(ctx, target, 0, 0, 0, 0, 0, size, size, 1, pixels, (long) size * 4,
				(long) size * size * 4), "texture read failed");
			// Each triangle's centroid pixel (row 0 is the top; the id channels are the readback bytes).
			assertIdsAt(pixels, size, 1, 1, IDS_A, "submit A");
			assertIdsAt(pixels, size, 5, 5, IDS_B, "submit B");
			assertIdsAt(pixels, size, 6, 1, IDS_C, "submit C");
			assertIdsAt(pixels, size, 1, 5, DEFAULTS, "the unpublished buffer's draw");
			delegate.close();
			MetalBridge.release(target);
		} finally {
			staged.close();
			unpublished.close();
		}
	}

	/** One submit: the ids are requested for {@code draw} at staging offset {@code at}; three vertices follow. */
	private static long submit(StagedVertexBuffer buffer, EntityCapture.Ranges ranges, Draw draw, int[] ids, long at,
			float[] corners) {
		if (ranges != null) {
			ranges.builderRequested(at, draw, ids);
		}
		VertexConsumer consumer = buffer.getVertexBuilder(draw);
		for (int v = 0; v < 3; v++) {
			consumer.addVertex(corners[3 * v], corners[3 * v + 1], corners[3 * v + 2]).setColor(1f, 1f, 1f, 1f);
		}
		return at + 3L * 16;
	}

	/** One staged draw as the game issues it: its vertex buffer at slot 0, then the triangle at its base vertex. */
	private static void drawStaged(GbufferDelegate delegate, long enc, ExecuteInfo info) {
		long buffer = MetalBridge.bufferHandle(info.vertexBuffer());
		delegate.vertexBuffer(0, buffer, 0);
		MetalBridge.vertexBuffer(enc, EngineSlots.VERTEX_DATA, buffer, 0);
		assertTrue(delegate.select(enc, "gbuffers_ids", null, new int[] {16}, List.of()) != null, "select dropped the draw");
		assertTrue(delegate.beforeDraw(enc), "the draw was dropped");
		MetalBridge.renderDraw(enc, 3, 1, info.baseVertex(), 0);
	}

	private static void assertIdsAt(byte[] pixels, int size, int x, int y, int[] ids, String which) {
		int at = (y * size + x) * 4;
		assertEquals(ids[0], pixels[at] & 0xff, which + " entityId");
		assertEquals(ids[1], pixels[at + 1] & 0xff, which + " blockEntityId");
		assertEquals(ids[2], pixels[at + 2] & 0xff, which + " currentRenderedItemId");
	}
}
