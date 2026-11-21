// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.gbuffers;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import dev.mcms.metal.BackendPassHarness;
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
 * The shadow pass's blend (plan.md E5; the blend directive): the same pack program ({@code shadow}, alpha 0.5, green)
 * draws a solid terrain quad and a translucent terrain quad into {@code shadowcolor0} over a red clear. Without a directive
 * neither quad blends, as Iris 1.11.7 writes the shadow water (GbufferPlans.blendOf); a {@code blend.shadow} directive
 * blends the shadow program's draws.
 */
@Tag("gpu")
class ShadowBlendGpuTest {

	private static final String SOLID_TERRAIN = "sodium:pipeline/solid_terrain";
	private static final String TRANSLUCENT_TERRAIN = "sodium:pipeline/translucent_terrain";
	/** The vertices of each half: the left half is the solid quad, the right half the translucent quad (both front-facing). */
	private static final int HALF_VERTICES = 6;

	/** The pack: a {@code shadow} program writing green at alpha 0.5, and the given shaders.properties (or none). */
	private static Path pack(Path dir, String properties, String formatDeclaration) throws IOException {
		Path shaders = Files.createDirectories(dir.resolve("shaders"));
		Files.writeString(shaders.resolve("shadow.vsh"), "#version 120\nvoid main() { gl_Position = gl_Vertex; }\n");
		Files.writeString(shaders.resolve("shadow.fsh"), "#version 120\n" + formatDeclaration
			+ "/* DRAWBUFFERS:0 */\nvoid main() { gl_FragData[0] = vec4(0.0, 1.0, 0.0, 0.5); }\n");
		if (!properties.isEmpty()) {
			Files.writeString(shaders.resolve("shaders.properties"), properties);
		}
		return dir;
	}

	/** The game's terrain pipelines by path (Sodium's own are built from these by the plans: their blend is Sodium's). */
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

	/** The engine's bindings for this draw: zeroed blocks, identity projection. */
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

	/**
	 * Twelve vertices in Sodium's packed terrain format: the left half (x in [-1, 0]) and the right half (x in [0, 1]), each
	 * two counter-clockwise triangles covering the full height. Clip coordinate c maps to the 20-bit word (c + 8) * 2^20 / 32.
	 */
	private static long quads(long ctx) {
		int stride = TerrainVertexType.STRIDE;
		int vertices = 2 * HALF_VERTICES;
		long buffer = MetalBridge.newBuffer(ctx, vertices * stride);
		long at = MetalBridge.bufferContents(buffer);
		MemoryUtil.memSet(at, 0, (long) vertices * stride);
		float[] corners = {
			-1, -1, 0, -1, -1, 1, -1, 1, 0, -1, 0, 1,   // left, counter-clockwise
			0, -1, 1, -1, 0, 1, 0, 1, 1, -1, 1, 1,      // right, counter-clockwise
		};
		for (int v = 0; v < vertices; v++) {
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

	/**
	 * Draws both quads into a red-cleared {@code shadowcolor0} and returns the texels: the left column is the solid quad, the
	 * right column the translucent one.
	 */
	private static byte[] draw(Path dir, String properties) throws Exception {
		return draw(dir, properties, "");
	}

	private static byte[] draw(Path dir, String properties, String formatDeclaration) throws Exception {
		// MetalPipeline's cache dir reads the game dir, which no test has: the cache is off before any Metal class initialises.
		System.setProperty("mcms.metal.mslCache", "false");
		if (RenderSystem.tryGetDevice() == null) {
			RenderSystem.initRenderThread();
			RenderSystem.initRenderer(BackendPassHarness.newDevice());
		}
		PackModel model;
		try (PackSource source = PackSource.open(pack(dir, properties, formatDeclaration))) {
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
				assertNotNull(plans.planOf(SOLID_TERRAIN, new int[] {TerrainVertexType.STRIDE}), "no solid terrain plan");
				assertNotNull(plans.planOf(TRANSLUCENT_TERRAIN, new int[] {TerrainVertexType.STRIDE}),
					"no translucent terrain plan");
				ShadowTargets targets = new ShadowTargets(dimension.union(), false, true);
				assertTrue(targets.create(Targets.engineTextures(ctx)), "the shadow targets were refused");
				int size = targets.resolution();
				long colour = targets.color(0);
				assertTrue(colour != 0, "shadowcolor0 is not created");
				long zero = MetalBridge.newBuffer(ctx, 256);
				MemoryUtil.memSet(MetalBridge.bufferContents(zero), 0, 256);
				long vertices = quads(ctx);
				GbufferDelegate delegate = new GbufferDelegate(plans, new Bindings(zero));
				try {
					delegate.beginKind(PassKind.SHADOW);
					delegate.shadowView(new Matrix4f(), new Matrix4f());  // the quads are in clip space already
					MetalBridge.renderBegin(enc, new long[] {colour}, new float[][] {{1, 0, 0, 1}}, 0, false, 0.0f, size, size);
					delegate.begin(enc, false);
					drawHalf(delegate, enc, vertices, SOLID_TERRAIN, 0);
					drawHalf(delegate, enc, vertices, TRANSLUCENT_TERRAIN, HALF_VERTICES);
					MetalBridge.endEncoders(enc);
					MetalBridge.commitAndWait(enc);
					delegate.endKind();
				} finally {
					delegate.close();
					MetalBridge.release(vertices);
					MetalBridge.release(zero);
				}
				byte[] pixels = gpu.readTexture(colour, 0, 0, size, size, 1, 4);
				targets.release();
				return pixels;
			} finally {
				plans.close();
			}
		}
	}

	/** One terrain pipeline's draw of its half: the vertex buffer at slot 0, then the half's six vertices from {@code base}. */
	private static void drawHalf(GbufferDelegate delegate, long enc, long vertices, String pipeline, int base) {
		delegate.vertexBuffer(0, vertices, 0);
		MetalBridge.vertexBuffer(enc, EngineSlots.VERTEX_DATA, vertices, 0);
		assertNotNull(delegate.select(enc, pipeline, null, new int[] {TerrainVertexType.STRIDE}, List.of()),
			pipeline + " was dropped");
		assertTrue(delegate.beforeDraw(enc), pipeline + " draw was dropped");
		MetalBridge.renderDraw(enc, HALF_VERTICES, 1, base, 0);
	}

	/** The channel of the texel at column {@code x} of the middle row. */
	private static int channel(byte[] pixels, int size, int x, int channel) {
		int at = ((size / 2) * size + x) * 4;
		return pixels[at + channel] & 0xff;
	}

	/** No directive: the translucent quad writes its colour as is, as the solid quad does (RIL-574). */
	@Test
	void aTranslucentTerrainQuadWritesUnblendedWithNoDirective(@TempDir Path dir) throws Exception {
		byte[] pixels = draw(dir, "");
		int size = (int) Math.round(Math.sqrt(pixels.length / 4.0));
		assertTrue(channel(pixels, size, size / 4, 1) >= 250, "the solid quad is not green");
		assertTrue(channel(pixels, size, size / 4, 0) <= 5, "red leaked through the opaque solid quad");
		int green = channel(pixels, size, 3 * size / 4, 1), red = channel(pixels, size, 3 * size / 4, 0);
		assertTrue(green >= 250, "the translucent quad blended without a directive: green " + green);
		assertTrue(red <= 5, "the clear's red came through the translucent quad: red " + red);
	}

	@Test
	void aBlendDirectiveBlendsTheTranslucentQuadOverTheDestination(@TempDir Path dir) throws Exception {
		byte[] pixels = draw(dir, "blend.shadow=SRC_ALPHA ONE_MINUS_SRC_ALPHA ONE ONE_MINUS_SRC_ALPHA\n");
		int size = (int) Math.round(Math.sqrt(pixels.length / 4.0));
		int green = channel(pixels, size, 3 * size / 4, 1), red = channel(pixels, size, 3 * size / 4, 0);
		assertTrue(green >= 110 && green <= 150, "the translucent quad did not blend: green " + green);
		assertTrue(red >= 110 && red <= 150, "the translucent quad did not blend over the red: red " + red);
	}

	/** One unsigned small float of {@code mantissaBits} mantissa bits under a 5-bit exponent (RG11B10Float's channels). */
	private static float smallFloat(int value, int mantissaBits) {
		int mantissa = value & ((1 << mantissaBits) - 1);
		int exponent = value >>> mantissaBits;
		if (exponent == 31) {
			return mantissa == 0 ? Float.POSITIVE_INFINITY : Float.NaN;
		}
		float fraction = mantissa / (float) (1 << mantissaBits);
		return exponent == 0 ? fraction * (float) Math.pow(2, -14) : (1 + fraction) * (float) Math.pow(2, exponent - 15);
	}

	/**
	 * A target with no alpha channel (R11F_G11F_B10F, as Complementary's colortex0) still blends with the shader's alpha,
	 * as GL does: the output keeps its fourth component for the blend (a three-component output left the source alpha
	 * undefined, and every blended water and cloud pixel read NaN). The shadow pass blends by directive here.
	 */
	@Test
	void aTranslucentQuadBlendsWithTheShaderAlphaOnATargetWithoutAlpha(@TempDir Path dir) throws Exception {
		byte[] pixels = draw(dir, "blend.shadow=SRC_ALPHA ONE_MINUS_SRC_ALPHA ONE ONE_MINUS_SRC_ALPHA\n",
			"/*\nconst int shadowcolor0Format = R11F_G11F_B10F;\n*/\n");
		int size = (int) Math.round(Math.sqrt(pixels.length / 4.0));
		int at = ((size / 2) * size + 3 * size / 4) * 4;
		int word = (pixels[at] & 0xff) | (pixels[at + 1] & 0xff) << 8 | (pixels[at + 2] & 0xff) << 16
			| (pixels[at + 3] & 0xff) << 24;
		float red = smallFloat(word & 0x7ff, 6), green = smallFloat((word >>> 11) & 0x7ff, 6);
		float blue = smallFloat(word >>> 22, 5);
		assertTrue(Math.abs(red - 0.5f) < 0.02f && Math.abs(green - 0.5f) < 0.02f && blue < 0.02f,
			"the translucent quad did not blend with alpha 0.5: " + red + ", " + green + ", " + blue);
	}
}
