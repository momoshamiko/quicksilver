// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.gbuffers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.renderpearl.api.pipeline.BindGroupLayout.UniformDescription;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import com.mojang.renderpearl.api.vertex.VertexFormatElement;
import dev.mcms.metal.MetalBridge;
import dev.mcms.shaders.metal.HeadlessGpu;
import dev.mcms.shaders.metal.ShaderNative;
import dev.mcms.shaders.pack.PackModel;
import dev.mcms.shaders.pack.PackSource;
import dev.mcms.shaders.pack.StandardMacros;
import dev.mcms.shaders.runtime.DimensionSet;
import dev.mcms.shaders.runtime.EngineSlots;
import dev.mcms.shaders.runtime.TargetFormats;
import dev.mcms.shaders.runtime.Targets;
import dev.mcms.shaders.runtime.hand.HandProjection;
import dev.mcms.shaders.runtime.uniforms.CustomUniforms;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;

/**
 * The item-in-hand draw of log 2026-11-14-e4-preview7 (objects scene, draw n=86) replayed through the real chain: the
 * mcms-classes pack, real plans, the real {@link GbufferDelegate} ({@code passKind(HAND)}, {@code setHandProjection},
 * the {@code DynamicTransforms} slice, {@code select} with the game's own uniform list and the pipeline's vertex
 * strides), item-format vertex bytes at the format's own offsets, quad indices expanded as the game stages them, and
 * the engine pass state (colortex0+colortex4 attachments, {@code LESS_THAN_OR_EQUAL} written depth cleared to 1.0 with
 * a world quad pre-drawn at 0.985, the plan's cull and the pack winding).
 *
 * <p>The regression this file guards: 26.3 registers <em>three</em> pipelines under {@code minecraft:pipeline/item_cutout}
 * (RenderPipelines javap 2026-11-14) — the plain and glint ones on {@code DefaultVertexFormat.ENTITY} (36 bytes) and
 * the glint-special one on {@code DefaultVertexFormat.ENTITY_GLINT_SPECIAL} (44 bytes, a UV3 element). The engine's
 * plans were keyed by name alone, so the last same-named pipeline's plan (built for the 44-byte layout) served every
 * draw under that name: the hand's and the armor stand sword's 36-byte vertices were read at stride 44 and drew
 * nothing (0 changed pixels between the pass's picture dumps, the class maps missing the sword). The plan lookup now
 * tells them apart by the vertex strides of the pipeline the pass set, and both formats draw here.
 */
@Tag("gpu")
class HandItemDrawGpuTest {

	private static final int RGBA8 = 70;
	private static final int W = 192, H = 108;
	/** The logged view: rows 1 and 2 of the model-view hold 0.9397 and 0.342 — a pitch of 20 degrees. */
	private static final Matrix4f MODEL_VIEW = new Matrix4f().rotationX((float) Math.toRadians(20f));
	private static final Matrix4f HAND_PROJECTION = new Matrix4f();
	private static final Matrix4f FRAME_PROJECTION = new Matrix4f();

	private static PackModel model;
	private static CustomUniforms custom;
	private static Map<Integer, String> colorFormats;

	private PassKind kind;

	@BeforeAll
	static void loadPack() throws Exception {
		Path pack = null;
		Path dir = Path.of(System.getProperty("user.dir"));
		for (int i = 0; i < 6 && pack == null; i++) {
			Path candidate = dir.resolve(Path.of("bench", "test-packs", "mcms-classes"));
			if (Files.isDirectory(candidate)) {
				pack = candidate;
			}
			dir = dir.getParent();
		}
		assertNotNull(pack, "bench/test-packs/mcms-classes not found");
		try (PackSource source = PackSource.open(pack)) {
			model = PackModel.load(source, StandardMacros.engine(), "en_us", new Properties());
		}
		assertTrue(model.problems().isEmpty(), "the test pack must load clean: " + model.problems());
		custom = CustomUniforms.of(model);
		colorFormats = model.rootFolder().union().colorFormats();
		// The logged hand projection (draw n=86): hudFov 70, near 0.05, far 1000 (the logged -1.0001 z row), 16:9.
		HandProjection.perspective(70f, 1000f, 16f, 9f, HandProjection.HAND_DEPTH, HAND_PROJECTION);
		// The logged frame projection (draw n=81: rows 0.7072, 1.2572 — fov 70 at 4:3): the same fov at 16:9 gives
		// the logged hand x scale 0.8033, so the same fov serves both spellings here.
		FRAME_PROJECTION.setPerspective((float) Math.toRadians(70f), 16f / 9f, 0.05f, 1000f);
	}

	/** The logged hand projection's rows (the draw the class comment names). */
	@Test
	void theHandProjectionMatchesTheLoggedRows() {
		assertEquals(0.8033f, HAND_PROJECTION.m00(), 0.0002f, "qsProj.r0's x scale (1/tan(35 deg) / (16/9))");
		assertEquals(1.4281f, HAND_PROJECTION.m11(), 0.0002f, "qsProj.r1's y scale (1/tan(35 deg))");
		// qsProj.r2 = [0 0 -0.125 -0.0125]: the near 0.05 far 1000 row (-1.0001, -0.1) scaled by MC_HAND_DEPTH.
		assertEquals(-0.125f, HAND_PROJECTION.m22(), 0.0002f, "qsProj.r2's z scale (MC_HAND_DEPTH of -1.0001)");
		assertEquals(-0.0125f, HAND_PROJECTION.m32(), 0.0002f, "qsProj.r2's z offset (MC_HAND_DEPTH of -0.1)");
		assertEquals(-1f, HAND_PROJECTION.m23(), "qsProj.r3's z (the GL w row)");
	}

	/** The control: the entity format (36 bytes, no UV3) draws through the plan of its own pipeline. */
	@Test
	void controlEntityCutoutDrawsThroughGbuffersEntities() {
		drawScene(PassKind.GBUFFERS, "minecraft:pipeline/entity_cutout", "gbuffers_entities",
			() -> entityCutout().getVertexFormatBinding(0));
	}

	/** The logged hand draw itself: the plain 36-byte ENTITY item format the game's hand stages (draw n=86). */
	@Test
	void handPlainItemCutoutDrawsThroughGbuffersHand() {
		drawScene(PassKind.HAND, "minecraft:pipeline/item_cutout", "gbuffers_hand",
			() -> net.minecraft.client.renderer.RenderPipelines.ITEM_CUTOUT.getVertexFormatBinding(0));
	}

	/** The armor stand's sword: the same plain item format through the entity chain (the GBUFFERS item draws). */
	@Test
	void gbuffersPlainItemCutoutDrawsThroughGbuffersEntities() {
		drawScene(PassKind.GBUFFERS, "minecraft:pipeline/item_cutout", "gbuffers_entities",
			() -> net.minecraft.client.renderer.RenderPipelines.ITEM_CUTOUT.getVertexFormatBinding(0));
	}

	/**
	 * The glint-special item pipeline shares the plain one's location and carries the 44-byte UV3 format: its plan
	 * (its own stride key) must decode its vertices and draw too.
	 */
	@Test
	void glintSpecialItemCutoutDrawsThroughItsOwnPlan() {
		drawScene(PassKind.GBUFFERS, "minecraft:pipeline/item_cutout", "gbuffers_entities", () -> {
			for (RenderPipeline candidate : gamePipelines()) {
				if (candidate.getLocation().toString().equals("minecraft:pipeline/item_cutout")
					&& candidate.getVertexFormatBinding(0) != null
					&& candidate.getVertexFormatBinding(0).getVertexSize() == 44) {
					return candidate.getVertexFormatBinding(0);
				}
			}
			return null;
		});
	}

	private record Rect(int x0, int y0, int x1, int y1) {
		int area() {
			return Math.max(0, this.x1 - this.x0 + 1) * Math.max(0, this.y1 - this.y0 + 1);
		}

		boolean inside(int x, int y) {
			return x >= this.x0 && x <= this.x1 && y >= this.y0 && y <= this.y1;
		}
	}

	/**
	 * Draws the sword quads through the named pipeline's plan. The format resolves only once the engine has listed
	 * the game's pipelines (renderpearl fills a pipeline's format bindings during that listing, not before), so the
	 * supplier runs after {@link DimensionSet#load}.
	 */
	private void drawScene(PassKind passKind, String pipeline, String program,
			java.util.function.Supplier<VertexFormat> formatOf) {
		this.kind = passKind;
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			long ctx = gpu.context();
			long enc = gpu.encoder();
			List<Long> owned = new ArrayList<>();
			DimensionSet set = DimensionSet.load(ctx, "mcms-classes", model, model.rootFolder(), custom, RGBA8,
				colorFormats, gamePipelines(), copy -> null);
			Targets targets = new Targets(set.dimension().union(), model.properties().bufferSizes(),
				set.usedTargets(), false, false, W, H, Targets.engineTextures(ctx), null);
			VertexFormat format = formatOf.get();
			assertNotNull(format, "the pipeline must carry a vertex format once the engine listed the pipelines");
			try {
				assertTrue(set.absent() == 0, "the pack's programs must all run: " + set.notes());
				assertTrue(targets.create());
				GbufferPlans plans = set.plans();
				GbufferPlans.Plan plan = plans.planOf(pipeline, new int[] {format.getVertexSize()});
				assertNotNull(plan, "the pipeline must have a plan for stride " + format.getVertexSize() + ": " + pipeline);
				GbufferPlans.PackVariant pv = plan.variant(passKind, GbufferPhase.OPAQUE) instanceof GbufferPlans.PackVariant v ? v : null;
				assertNotNull(pv, pipeline + " must draw through " + program + " as " + passKind);
				assertEquals(program, pv.program(), pipeline + " must draw through " + program + " as " + passKind);
				int[] layout = plans.layout(GbufferPhase.OPAQUE);
				long[] colours = new long[layout.length];
				float[][] clears = new float[layout.length][];
				for (int i = 0; i < layout.length; i++) {
					colours[i] = targets.write(layout[i]);
					clears[i] = new float[] {0, 0, 0, 1};
				}
				MetalBridge.renderBegin(enc, colours, clears, targets.depth(0), true, 1.0f, W, H);

				// The world under the hand: a full-screen quad at window depth 0.985 (terrain at the scene's
				// distance), green in colortex0, through the same flipped depth state.
				drawWorldQuad(ctx, enc, flippedDepth(ctx), layout, owned);

				GbufferDelegate delegate = new GbufferDelegate(plans, new TestBindings(targets, -1, FRAME_PROJECTION));
				delegate.begin(enc, true);
				delegate.phase(GbufferPhase.OPAQUE);
				if (passKind == PassKind.HAND) {
					float[] columnMajor = new float[16];
					HAND_PROJECTION.get(columnMajor);
					delegate.setHandProjection(columnMajor);
				}
				final Rect[] rects = new Rect[2];
				final float[] depthBand = new float[2];
				delegate.passKind(passKind, () -> {
					List<UniformDescription> uniforms = uniforms(pipeline);
					// The strides of the pipeline the pass set: the same-named item_cutouts resolve to their own plans.
					int[] slots = delegate.select(enc, pipeline, null, new int[] {format.getVertexSize()}, uniforms);
					assertNotNull(slots, "the item pipeline must not be dropped");
					int atlasIndex = -1;
					for (int i = 0; i < uniforms.size(); i++) {
						if (uniforms.get(i).name().equals("Sampler0")) {
							atlasIndex = i;
						}
					}
					assertTrue(atlasIndex >= 0 && slots[atlasIndex] >= 0, "Sampler0 must map to the program's atlas slot");
					long sampler = MetalBridge.samplerNew(ctx, 0, 0, 0, 0, 0, 1, 0.25f);
					owned.add(sampler);
					MetalBridge.renderTextureSlots(enc, slots[atlasIndex], whiteTexture(ctx, owned), sampler,
						delegate.vertexSamplerSlot(atlasIndex), delegate.fragmentSamplerSlot(atlasIndex));

					delegate.uniform(enc, "DynamicTransforms", transforms(ctx, owned));

					// The sword: a front quad (GL counter-clockwise seen from the eye) at x 0.10..0.28,
					// y -0.30..-0.10, z -0.52, and a clockwise quad left of it (x -0.30..-0.12) at the same depth.
					Rect[] quads = swordQuads(ctx, enc, format, owned);
					rects[0] = quads[0];
					rects[1] = quads[1];
					Matrix4f projection = this.kind == PassKind.HAND ? HAND_PROJECTION : FRAME_PROJECTION;
					depthBand[0] = Float.MAX_VALUE;
					depthBand[1] = -Float.MAX_VALUE;
					for (float[] corner : FRONT_EYE) {
						float depth = windowDepth(corner, projection);
						depthBand[0] = Math.min(depthBand[0], depth);
						depthBand[1] = Math.max(depthBand[1], depth);
					}
					assertTrue(delegate.beforeDraw(enc), "the item draw must run");
					MetalBridge.drawIndexed(enc, 12, 1, 0, 0, 0);
				});
				MetalBridge.endEncoders(enc);
				MetalBridge.commitAndWait(enc);
				delegate.close();

				byte[] coloursOut = gpu.readTexture(targets.write(0), 0, 0, W, H, 1, 4);
				byte[] classesOut = gpu.readTexture(targets.write(4), 0, 0, W, H, 1, 1);
				byte[] depthOut = gpu.readTexture(targets.depth(0), 0, 0, W, H, 1, 4);
				ByteBuffer depths = ByteBuffer.wrap(depthOut).order(ByteOrder.LITTLE_ENDIAN);
				int cls = passKind == PassKind.HAND ? 14 : 8;

				int drawn = 0;
				int clockwiseDrawn = 0;
				int outside = 0;
				for (int y = 0; y < H; y++) {
					for (int x = 0; x < W; x++) {
						int index = y * W + x;
						if ((classesOut[index] & 0xff) != cls) {
							continue;
						}
						if (rects[0].inside(x, y)) {
							drawn++;
							// The program writes glcolor * texture: white * white.
							assertEquals(255, coloursOut[4 * index] & 0xff, 2, "colortex0 red at " + x + "," + y);
							assertEquals(255, coloursOut[4 * index + 1] & 0xff, 2, "colortex0 green at " + x + "," + y);
							assertEquals(255, coloursOut[4 * index + 2] & 0xff, 2, "colortex0 blue at " + x + "," + y);
							float depth = depths.getFloat(4 * index);
							assertTrue(depth >= depthBand[0] - 0.01f && depth <= depthBand[1] + 0.01f,
								"the window depth of the quad's corners at " + x + "," + y + ": " + depth);
						} else if (rects[1].inside(x, y)) {
							clockwiseDrawn++;
						} else {
							outside++;
						}
					}
				}
				assertTrue(drawn >= rects[0].area() * 3 / 4,
					"the front quad's pixels carry the class (" + drawn + " of " + rects[0].area() + ")");
				if (pv.cull()) {
					assertEquals(0, clockwiseDrawn, "the clockwise quad must cull away (the pipeline culls)");
				}
				assertEquals(0, outside, "no class pixel outside the sword");
			} finally {
				targets.release();
				for (long handle : owned) {
					MetalBridge.release(handle);
				}
			}
		}
	}

	/** The entity control pipeline: the game's own {@code entity_cutout} object. */
	private static RenderPipeline entityCutout() {
		for (RenderPipeline candidate : gamePipelines()) {
			if (candidate.getLocation().toString().equals("minecraft:pipeline/entity_cutout")) {
				return candidate;
			}
		}
		throw new IllegalStateException("the game lists no entity_cutout");
	}

	/** The game's own pipeline list the engine maps at install (Engine.gamePipelines's sources): the registry lists
	 * hold one pipeline per location, so the {@code RenderPipelines} statics join them — the plain 36-byte
	 * {@code ITEM_CUTOUT} the hand draws with is otherwise missing (the glint-special displaced it). */
	private static List<RenderPipeline> gamePipelines() {
		List<RenderPipeline> all = new ArrayList<>(net.minecraft.client.renderer.RenderPipelines.requiredPipelines());
		all.addAll(net.minecraft.client.renderer.RenderPipelines.optionalPipelines());
		try {
			for (java.lang.reflect.Field field : net.minecraft.client.renderer.RenderPipelines.class.getFields()) {
				if (RenderPipeline.class.isAssignableFrom(field.getType())) {
					RenderPipeline pipeline = (RenderPipeline) field.get(null);
					if (pipeline != null && !all.contains(pipeline)) {
						all.add(pipeline);
					}
				}
			}
		} catch (IllegalAccessException unreadable) {
			// the registry list alone
		}
		return all;
	}

	private static List<UniformDescription> uniforms(String pipeline) {
		RenderPipeline found = null;
		for (RenderPipeline candidate : gamePipelines()) {
			if (candidate.getLocation().toString().equals(pipeline)) {
				found = candidate;
				break;
			}
		}
		assertNotNull(found, "the game lists " + pipeline);
		List<UniformDescription> uniforms = new ArrayList<>();
		for (var group : found.getBindGroupLayouts()) {
			uniforms.addAll(group.uniforms());
		}
		return uniforms;
	}

	/** The game's DynamicTransforms of the logged draw: the pitch model-view, identity texture matrix, white modulator
	 * ({@code mat4 ModelViewMat; mat4 TextureMat; vec4 ColorModulator; vec3 ModelOffset}, std140). */
	private static GpuBufferSlice transforms(long ctx, List<Long> owned) {
		GpuBuffer buffer = MetalBridge.newGpuBuffer(ctx, 192);
		owned.add(MetalBridge.bufferHandle(buffer));
		long at = MetalBridge.bufferAddress(buffer);
		float[] view = new float[16];
		MODEL_VIEW.get(view);
		float[] identity = new Matrix4f().get(new float[16]);
		for (int i = 0; i < 16; i++) {
			MemoryUtil.memPutFloat(at + 4L * i, view[i]);
			MemoryUtil.memPutFloat(at + 64 + 4L * i, identity[i]);
		}
		for (int i = 0; i < 4; i++) {
			MemoryUtil.memPutFloat(at + 128 + 4L * i, 1.0f);
		}
		return new GpuBufferSlice(buffer, 0, 192);
	}

	/** The sword's front quad in eye space (GL counter-clockwise seen from the eye, which looks down -z). */
	private static final float[][] FRONT_EYE = {{0.10f, -0.30f, -0.52f}, {0.28f, -0.30f, -0.52f}, {0.28f, -0.10f, -0.52f},
		{0.10f, -0.10f, -0.52f}};
	/** The clockwise quad left of it, at the same depth: the item pipeline's cull must drop it. */
	private static final float[][] BACK_EYE = {{-0.12f, -0.30f, -0.52f}, {-0.30f, -0.30f, -0.52f}, {-0.30f, -0.10f, -0.52f},
		{-0.12f, -0.10f, -0.52f}};

	/**
	 * Writes the two quads (front counter-clockwise, clockwise) in the given format's own layout — each element at its
	 * offset in the format's stride, model-space positions ({@code inverse(ModelViewMat) * eye}, the staged buffer's
	 * convention), the quad indices expanded as the game's staged buffer does — binds them, and returns the quads'
	 * screen rectangles under this test's kind's projection.
	 */
	private Rect[] swordQuads(long ctx, long enc, VertexFormat format, List<Long> owned) {
		float[][] frontUv = {{0.2f, 0.2f}, {0.8f, 0.2f}, {0.8f, 0.8f}, {0.2f, 0.8f}};
		float[][] backUv = {{0.8f, 0.2f}, {0.2f, 0.2f}, {0.2f, 0.8f}, {0.8f, 0.8f}};
		int stride = format.getVertexSize();
		byte[] bytes = new byte[stride * 8];
		ByteBuffer out = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
		Vector3f model = new Vector3f();
		Matrix4f inverseView = new Matrix4f(MODEL_VIEW).invert();
		for (int quad = 0; quad < 2; quad++) {
			float[][] eye = quad == 0 ? FRONT_EYE : BACK_EYE;
			float[][] uv = quad == 0 ? frontUv : backUv;
			for (int vertex = 0; vertex < 4; vertex++) {
				inverseView.transformPosition(eye[vertex][0], eye[vertex][1], eye[vertex][2], model);
				int base = stride * (quad * 4 + vertex);
				for (VertexFormatElement element : format.getElements()) {
					int at = base + element.offset();
					switch (element.name()) {
						case "Position" -> {
							out.putFloat(at, model.x);
							out.putFloat(at + 4, model.y);
							out.putFloat(at + 8, model.z);
						}
						case "Color" -> Arrays.fill(bytes, at, at + 4, (byte) 0xff);
						case "UV0" -> {
							out.putFloat(at, uv[vertex][0]);
							out.putFloat(at + 4, uv[vertex][1]);
						}
						case "UV1", "UV2", "UV3" -> {
							out.putShort(at, (short) 0);
							out.putShort(at + 2, (short) 0);
						}
						case "Normal" -> {
							bytes[at + 2] = (byte) 127;
						}
						default -> {
						}
					}
				}
			}
		}
		ByteBuffer indices = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
		for (int quad = 0; quad < 2; quad++) {
			int first = quad * 4;
			for (int index : new int[] {0, 1, 2, 0, 2, 3}) {
				indices.putShort((short) (first + index));
			}
		}
		indices.flip();
		MetalBridge.vertexBuffer(enc, EngineSlots.VERTEX_DATA, upload(ctx, bytes, owned), 0);
		MetalBridge.indexBuffer(enc, upload(ctx, indices.array(), owned), false);
		Matrix4f projection = this.kind == PassKind.HAND ? HAND_PROJECTION : FRAME_PROJECTION;
		return new Rect[] {screenRect(FRONT_EYE, projection), screenRect(BACK_EYE, projection)};
	}

	/** The pixel rectangle an eye-space quad covers, with a pixel of slack (the epilogue's y flip included). */
	private static Rect screenRect(float[][] eye, Matrix4f projection) {
		int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, y1 = Integer.MIN_VALUE;
		for (float[] corner : eye) {
			float w = -corner[2];
			float ndcX = projection.m00() * corner[0] / w;
			float ndcY = -projection.m11() * corner[1] / w;
			int x = Math.round((ndcX + 1f) * 0.5f * (W - 1));
			int y = Math.round((1f - ndcY) * 0.5f * (H - 1));
			x0 = Math.min(x0, x);
			y0 = Math.min(y0, y);
			x1 = Math.max(x1, x);
			y1 = Math.max(y1, y);
		}
		return new Rect(Math.max(0, x0 - 1), Math.max(0, y0 - 1), Math.min(W - 1, x1 + 1), Math.min(H - 1, y1 + 1));
	}

	/** The Metal window depth a corner maps to: the projection's GL clip z mapped by the epilogue's (z + w) / 2. */
	private static float windowDepth(float[] eye, Matrix4f projection) {
		float clipZ = projection.m22() * eye[2] + projection.m32();
		float clipW = -eye[2];
		return (clipZ / clipW + 1f) * 0.5f;
	}

	/** A full-screen quad at window depth 0.985: green in colortex0 (the world under the item). */
	private void drawWorldQuad(long ctx, long enc, long depthState, int[] layout, List<Long> owned) {
		int[] formats = new int[layout.length];
		for (int i = 0; i < layout.length; i++) {
			formats[i] = TargetFormats.of(colorFormats.get(layout[i]), layout[i]).format();
		}
		String msl = """
			#include <metal_stdlib>
			using namespace metal;
			struct VOut { float4 position [[position]]; };
			vertex VOut worldVs(uint vid [[vertex_id]]) {
				VOut o;
				o.position = float4(vid == 1 ? 3.0 : -1.0, vid == 2 ? 3.0 : -1.0, 0.985, 1.0);
				return o;
			}
			struct FOut { float4 c0 [[color(0)]]; };
			fragment FOut worldFs(VOut in [[stage_in]]) {
				FOut o;
				o.c0 = float4(0.0, 1.0, 0.0, 1.0);
				return o;
			}
			""";
		ShaderNative.Compiled library = ShaderNative.libraryNew(ctx, msl, ShaderNative.MSL_3_1, ShaderNative.MATH_PACKS, true);
		assertTrue(library.ok(), "the world library failed: " + library.error());
		try {
			int[] descriptor = GbufferPlans.descriptorWords(GbufferPlans.vertexWords(0, List.of()), layout,
				formats, Set.of(0, 4), target -> null, MetalBridge.topologyClass(PrimitiveTopology.TRIANGLES), true);
			long pso = MetalBridge.pipelineNew(ctx, library.library(), "worldVs", library.library(), "worldFs",
				descriptor);
			owned.add(pso);
			MetalBridge.renderPipeline(enc, pso, depthState, false, false, 0, 0,
				MetalBridge.primitive(PrimitiveTopology.TRIANGLES));
			MetalBridge.renderDraw(enc, 3, 1, 0, 0);
		} finally {
			MetalBridge.release(library.library());
		}
	}

	/** The flipped game depth state (the game's GREATER_THAN_OR_EQUAL written, LESS_THAN_OR_EQUAL in the engine's). */
	private static long flippedDepth(long ctx) {
		DepthStencilState flipped = GameDepth.flipped(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, true));
		return MetalBridge.depthStateNew(ctx, MetalBridge.compareWord(flipped.depthTest()), flipped.writeDepth());
	}

	private static long upload(long ctx, byte[] bytes, List<Long> owned) {
		long buffer = MetalBridge.newBuffer(ctx, Math.max(16, bytes.length));
		MemorySegment.ofAddress(MetalBridge.bufferContents(buffer)).reinterpret(bytes.length)
			.copyFrom(MemorySegment.ofArray(bytes));
		owned.add(buffer);
		return buffer;
	}

	private static long whiteTexture(long ctx, List<Long> owned) {
		long texture = ShaderNative.textureNew(ctx, RGBA8, ShaderNative.TYPE_2D, 4, 4, 1, 1,
			ShaderNative.USAGE_SHADER_READ, ShaderNative.STORAGE_PRIVATE);
		byte[] texels = new byte[4 * 4 * 4];
		Arrays.fill(texels, (byte) 0xff);
		assertTrue(ShaderNative.textureUpload(ctx, texture, texels, 16, 0, 0, 0, 0, 0, 0, 4, 4, 1));
		owned.add(texture);
		return texture;
	}

	/** The frame's bindings over the test's targets; the projection is the logged frame (world) projection. */
	private record TestBindings(Targets targets, long frameBuffer, Matrix4f projection) implements
			GbufferDelegate.Bindings {
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
			return null;
		}

		@Override
		public long texture(String colortexName, boolean write) {
			int index = dev.mcms.shaders.runtime.ProgramRequests.targetIndex(colortexName);
			return index >= 0 && index < this.targets.count()
				? write ? this.targets.write(index) : this.targets.read(index) : 0;
		}

		@Override
		public long depth(String name) {
			return this.targets.depth(name.equals("depthtex1") ? 1 : name.equals("depthtex2") ? 2 : 0);
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
			this.projection.get(out);
		}
	}
}
