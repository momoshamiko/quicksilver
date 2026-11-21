// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.gbuffers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import dev.mcms.metal.MetalBridge;
import dev.mcms.shaders.metal.HeadlessGpu;
import dev.mcms.shaders.pack.DimensionPrograms;
import dev.mcms.shaders.pack.PackModel;
import dev.mcms.shaders.pack.PackSource;
import dev.mcms.shaders.pack.StandardMacros;
import dev.mcms.shaders.runtime.EngineSlots;
import dev.mcms.shaders.runtime.Targets;
import dev.mcms.shaders.runtime.shadow.ShadowTargets;
import dev.mcms.shaders.runtime.terrain.TerrainLayout;
import dev.mcms.shaders.runtime.terrain.TerrainVertexType;
import dev.mcms.shaders.runtime.uniforms.CustomUniforms;
import dev.mcms.metal.BackendPassHarness;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lwjgl.system.MemoryUtil;

/**
 * The shadow pass's terrain draw on the GPU (plan.md E5, "the pass"): a terrain quad drawn through the {@code shadow_solid}
 * variant of Sodium's solid terrain pipeline, inside a {@link PassKind#SHADOW} window, lands in {@code shadowcolor0} of
 * {@link ShadowTargets}. The plans are built by {@link GbufferPlans#build} from a pack with a real shadow program; the
 * terrain pipeline's own states come from {@link GbufferPlans#sodiumTerrainPipelines} exactly as the game's build makes them.
 */
@Tag("gpu")
class ShadowVariantGpuTest {

	/** Sodium's solid terrain pipeline's identifier (the plan's key). */
	private static final String SOLID_TERRAIN = "sodium:pipeline/solid_terrain";

	/** The pack: a {@code shadow_solid} program with the given vertex shader; its fragment writes green to {@code shadowcolor0}. */
	private static Path pack(Path dir, String vertex) throws IOException {
		Path shaders = Files.createDirectories(dir.resolve("shaders"));
		Files.writeString(shaders.resolve("shadow_solid.vsh"), vertex);
		Files.writeString(shaders.resolve("shadow_solid.fsh"),
			"#version 120\n/* DRAWBUFFERS:0 */\nvoid main() { gl_FragData[0] = vec4(0.0, 1.0, 0.0, 1.0); }\n");
		Files.writeString(shaders.resolve("shaders.properties"), "shadow.culling=false\n");
		return dir;
	}

	/** The game's terrain pipelines by path (the three Sodium builds from; their own vertex layouts are Sodium's). */
	private static List<RenderPipeline> terrainPipelines() {
		List<RenderPipeline> pipelines = new ArrayList<>();
		for (String path : List.of("pipeline/solid_terrain", "pipeline/cutout_terrain", "pipeline/translucent_terrain")) {
			pipelines.add(RenderPipeline.builder()
				.withLocation(Identifier.fromNamespaceAndPath("minecraft", path))
				.withVertexShader(Identifier.fromNamespaceAndPath("minecraft", "core/terrain"))
				.withFragmentShader(Identifier.fromNamespaceAndPath("minecraft", "core/terrain"))
				.withCull(true)
				.withDepthStencilState(DepthStencilState.DEFAULT)
				.withPrimitiveTopology(PrimitiveTopology.QUADS)
				.build());
		}
		return pipelines;
	}

	/** The engine's bindings for this draw: zeroed blocks (the region and camera offsets are zero), identity projection. */
	private record Bindings(long zeroBlock) implements GbufferDelegate.Bindings {
		@Override
		public long[] frameBlock(String program) {
			return new long[] {zeroBlock, 0};
		}

		@Override
		public long[] customBlock() {
			return null;
		}

		@Override
		public long[] block(String name) {
			return new long[] {zeroBlock, 0};
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
			new Matrix4f().get(out);
		}
	}

	/** One shadow pass of a terrain quad: shadowcolor0's RGBA8 texels and the buffer's side in texels. */
	private record Drawn(int size, byte[] rgba) {
		/** One channel of the texel at column {@code x}, row {@code y}. */
		int channel(int x, int y, int channel) {
			return rgba[(y * size + x) * 4 + channel] & 0xff;
		}
	}

	/**
	 * Draws the quad through the shadow variant of the pack's {@code shadow_solid} program, with the engine's shadow view:
	 * {@code modelView} (player space to shadow view space, as the shadow frame binds it) and an identity projection.
	 */
	private static Drawn drawShadowQuad(Path dir, String vertex, Matrix4f modelView) throws Exception {
		// MetalPipeline's cache dir reads the game dir, which no test has: the cache is off before any Metal class initialises.
		System.setProperty("mcms.metal.mslCache", "false");
		if (RenderSystem.tryGetDevice() == null) {
			RenderSystem.initRenderThread();  // the game's pipeline objects need the renderer
			RenderSystem.initRenderer(BackendPassHarness.newDevice());
		}
		PackModel model;
		try (PackSource source = PackSource.open(pack(dir, vertex))) {
			model = PackModel.load(source, StandardMacros.engine(), "en_us", new Properties());
		}
		VertexLayoutRegistry.register(TerrainVertexType.INSTANCE.getVertexFormat(), TerrainLayout.layout(0),
			new PrologueFacts(26, 0, 21, 0, -1));
		DimensionPrograms dimension = model.rootFolder();
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			long ctx = gpu.context();
			long enc = gpu.encoder();
			GbufferPlans plans = GbufferPlans.build(ctx, model, dimension, CustomUniforms.of(model),
				dimension.union().colorFormats(), terrainPipelines(), copy -> null);
			try {
				GbufferPlans.Plan plan = plans.planOf(SOLID_TERRAIN, new int[] {TerrainVertexType.STRIDE});
				assertNotNull(plan, "no plan for " + SOLID_TERRAIN);
				GbufferPlans.Variant variant = plan.variant(PassKind.SHADOW, GbufferPhase.OPAQUE,
					dev.mcms.shaders.runtime.gbuffers.GbufferMapping.Domain.ENTITY);
				assertTrue(variant instanceof GbufferPlans.PackVariant, "the shadow terrain draws through no pack program: "
					+ plan.reason(PassKind.SHADOW, GbufferPhase.OPAQUE, GbufferMapping.Domain.ENTITY));
				GbufferPlans.PackVariant pack = (GbufferPlans.PackVariant) variant;
				assertTrue(pack.depth(false).description().contains("LESS_THAN_OR_EQUAL"),
					"the shadow pass compares less-equal: " + pack.depth(false).description());
				assertTrue(pack.cull(), "shadow.culling=false turned off the game pipeline's face culling");

				ShadowTargets targets = new ShadowTargets(dimension.union(), false, true);
				assertTrue(targets.create(Targets.engineTextures(ctx)), "the shadow targets were refused");
				int size = targets.resolution();
				long colour = targets.color(0);
				assertTrue(colour != 0, "shadowcolor0 is not created");
				long zero = MetalBridge.newBuffer(ctx, 256);
				MemoryUtil.memSet(MetalBridge.bufferContents(zero), 0, 256);
				long vertices = quad(ctx);
				GbufferDelegate delegate = new GbufferDelegate(plans, new Bindings(zero));
				try {
					delegate.beginKind(PassKind.SHADOW);
					delegate.shadowView(modelView, new Matrix4f());
					MetalBridge.renderBegin(enc, new long[] {colour}, new float[][] {{0, 0, 0, 1}}, 0, false, 0.0f, size, size);
					delegate.begin(enc, false);
					delegate.vertexBuffer(0, vertices, 0);
					MetalBridge.vertexBuffer(enc, EngineSlots.VERTEX_DATA, vertices, 0);
					assertNotNull(delegate.select(enc, SOLID_TERRAIN, null, new int[] {TerrainVertexType.STRIDE}, List.of()),
						"select dropped the terrain draw");
					assertTrue(delegate.beforeDraw(enc), "the terrain draw was dropped");
					MetalBridge.renderDraw(enc, QUAD_VERTICES, 1, 0, 0);
					MetalBridge.endEncoders(enc);
					MetalBridge.commitAndWait(enc);
					delegate.endKind();
				} finally {
					delegate.close();
					MetalBridge.release(vertices);
					MetalBridge.release(zero);
				}
				Drawn drawn = new Drawn(size, gpu.readTexture(colour, 0, 0, size, size, 1, 4));
				targets.release();
				return drawn;
			} finally {
				plans.close();
			}
		}
	}

	@Test
	void aTerrainQuadDrawnThroughTheShadowVariantLandsInShadowcolor0AndItsBackFaceIsCulled(@TempDir Path dir)
			throws Exception {
		Drawn drawn = drawShadowQuad(dir, "#version 120\nvoid main() { gl_Position = gl_Vertex; }\n", new Matrix4f());
		int size = drawn.size();
		int row = size / 2;
		assertEquals(255, drawn.channel(size / 4, row, 1), "the front-facing half is missing from shadowcolor0");
		assertEquals(0, drawn.channel(size / 4, row, 0), "red leaked into shadowcolor0");
		assertEquals(0, drawn.channel(3 * size / 4, row, 1), "the back-facing half was drawn: back-face culling is off");
	}

	/**
	 * The engine's shadow view places the caster: {@code ftransform()} (the pack's {@code gl_ModelViewMatrix} then
	 * {@code gl_ProjectionMatrix}) moves the front-facing half (clip x -1 to 0) by the view's +0.5 in X, to -0.5 to 0.5. A
	 * texel centre at clip x below -0.5 misses it, so columns below size/4 keep the pass's black clear; columns size/4 to
	 * 3*size/4 - 1 are green (the back-facing half stays culled).
	 */
	@Test
	void anFtransformShadowProgramLandsItsCasterAtTheShiftedTexel(@TempDir Path dir) throws Exception {
		Drawn drawn = drawShadowQuad(dir, "#version 120\nvoid main() { gl_Position = ftransform(); }\n",
			new Matrix4f().translation(0.5f, 0f, 0f));
		int size = drawn.size();
		int row = size / 2;
		assertEquals(0, drawn.channel(size / 4 - 1, row, 1), "the texel left of the shifted caster is covered");
		assertEquals(255, drawn.channel(size / 4, row, 1), "the first texel of the shifted caster is missing");
		assertEquals(255, drawn.channel(3 * size / 4 - 1, row, 1), "the shifted caster ends early");
	}

	/** Vertices of the two halves: the left half counter-clockwise (front-facing), the right half clockwise (back-facing). */
	private static final int QUAD_VERTICES = 12;

	/**
	 * Twelve vertices in Sodium's packed terrain format ({@link TerrainLayout}): each component is a 20-bit word {@code v}
	 * that decodes to {@code v * 32 / 2^20 - 8} (section 0, no region offset), split as {@code hi} bits in word x and
	 * {@code lo} bits in word y at shifts 0/10/20. Clip coordinate c maps to v = (c + 8) * 2^20 / 32. The left half
	 * (x in [-1, 0]) is two counter-clockwise triangles; the right half (x in [0, 1]) is the same two triangles clockwise.
	 */
	private static long quad(long ctx) {
		int stride = TerrainVertexType.STRIDE;
		long buffer = MetalBridge.newBuffer(ctx, QUAD_VERTICES * stride);
		long at = MetalBridge.bufferContents(buffer);
		MemoryUtil.memSet(at, 0, (long) QUAD_VERTICES * stride);
		float[] corners = {
			-1, -1, 0, -1, -1, 1, -1, 1, 0, -1, 0, 1,   // left, counter-clockwise
			0, -1, 0, 1, 1, -1, 0, 1, 1, 1, 1, -1,      // right, clockwise
		};
		for (int v = 0; v < QUAD_VERTICES; v++) {
			long vertex = at + (long) v * stride;
			int[] words = new int[2];
			int[] coordinates = {packed(corners[2 * v]), packed(corners[2 * v + 1]), packed(0.5f)};
			for (int i = 0; i < 3; i++) {
				words[0] |= ((coordinates[i] >> 10) & 0x3FF) << (10 * i);
				words[1] |= (coordinates[i] & 0x3FF) << (10 * i);
			}
			MemoryUtil.memPutInt(vertex, words[0]);
			MemoryUtil.memPutInt(vertex + 4, words[1]);
		}
		return buffer;
	}

	/** The 20-bit word that decodes to {@code c} (the inverse of the position decode, section 0). */
	private static int packed(float c) {
		return Math.round((c + 8f) * (1 << 20) / 32f);
	}
}
