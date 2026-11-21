// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.gbuffers;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout.UniformDescription;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import com.mojang.renderpearl.util.TextureViewAndSampler;
import dev.mcms.metal.BackendPassHarness;
import dev.mcms.metal.MetalBridge;
import dev.mcms.shaders.metal.HeadlessGpu;
import dev.mcms.shaders.pack.PackModel;
import dev.mcms.shaders.pack.PackSource;
import dev.mcms.shaders.pack.StandardMacros;
import dev.mcms.shaders.runtime.DimensionSet;
import dev.mcms.shaders.runtime.Engine;
import dev.mcms.shaders.runtime.EngineSlots;
import dev.mcms.shaders.runtime.ProgramRequests;
import dev.mcms.shaders.runtime.Targets;
import dev.mcms.shaders.runtime.terrain.TerrainEncoder;
import dev.mcms.shaders.runtime.terrain.TerrainLayout;
import dev.mcms.shaders.runtime.terrain.TerrainUniforms;
import dev.mcms.shaders.runtime.terrain.TerrainVertex;
import dev.mcms.shaders.runtime.terrain.TerrainVertexType;
import dev.mcms.shaders.runtime.textures.AtlasSizes;
import dev.mcms.shaders.runtime.uniforms.CustomUniforms;
import dev.mcms.shaders.runtime.uniforms.FrameUniforms;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.OptionalDouble;
import java.util.Properties;
import org.joml.Matrix4f;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The {@code atlasSize} uniform on the device, through Sodium's solid terrain pipeline. A pack program writes its
 * {@code atlasSize} into colortex4 (one byte per component) with alpha 1, so a texel of alpha 0 means the program did not
 * draw. The game's block atlas is bound at {@code u_BlockTex} the way the game binds it, through {@link GbufferDelegate#uniform}:
 * the draw reads the atlas's size, (64, 32). A texture no atlas names reads (0, 0), as the documents say (engine-pack-format.md
 * section 10), and so does a later pipeline of the same pass that binds no atlas, after a draw that did.
 */
@Tag("gpu")
class AtlasSizeGpuTest {

	private static final int W = 64, H = 48, RGBA8 = 70;
	/** The block atlas the draw binds, and the size the pack must read back. */
	private static final int ATLAS_W = 64, ATLAS_H = 32;
	/** A texture no atlas names (an entity or interface texture): the pack must read (0, 0) for it. */
	private static final int OTHER = 16;
	/** The quad depths: the first draw of the carry-over case is farther, so the second draw passes the depth test. */
	private static final float NEAR = 0f, FAR = 0.5f;
	/** The registered extended terrain format Sodium's passes draw with. */
	private static final VertexFormat EXTENDED = TerrainVertexType.INSTANCE.getVertexFormat();
	private static final PrologueFacts FACTS = new PrologueFacts(EngineSlots.PUSH_CONSTANTS, 0,
		TerrainUniforms.GLOBALS_SLOT, 0, -1);
	private static final String SOLID_TERRAIN = "sodium:pipeline/solid_terrain";
	/** Sodium's solid terrain uniforms: the atlas, the lightmap and the globals. */
	private static final List<UniformDescription> SODIUM_UNIFORMS = List.of(
		new UniformDescription("u_BlockTex", UniformType.COMBINED_IMAGE_SAMPLER),
		new UniformDescription("u_LightTex", UniformType.COMBINED_IMAGE_SAMPLER),
		new UniformDescription("u_Globals", UniformType.UNIFORM_BUFFER));
	/** The same pipeline's uniforms without the atlas: a pipeline of the pass that binds no atlas. */
	private static final List<UniformDescription> NO_ATLAS_UNIFORMS = List.of(
		new UniformDescription("u_LightTex", UniformType.COMBINED_IMAGE_SAMPLER),
		new UniformDescription("u_Globals", UniformType.UNIFORM_BUFFER));

	/** The pack program: atlasSize, one byte per component, in colortex4 (the second output of DRAWBUFFERS:04). */
	private static final String SIZE_FSH = """
		#version 130

		// Quicksilver test pack (AtlasSizeGpuTest): atlasSize written as two colour bytes. Original code.

		uniform ivec2 atlasSize;

		varying vec2 texcoord;

		void main() {
			/* DRAWBUFFERS:04 */
			gl_FragData[0] = vec4(0.0, 0.0, 0.0, 1.0);
			gl_FragData[1] = vec4(float(atlasSize.x) / 255.0, float(atlasSize.y) / 255.0, 0.0, 1.0);
		}
		""";

	/** Pass-through vertex stage, as the other Sodium terrain tests use. */
	private static final String TERRAIN_VSH = """
		#version 130

		// Quicksilver test pack (AtlasSizeGpuTest): pass-through vertex stage. Original code.

		varying vec2 texcoord;
		varying vec4 glcolor;

		void main() {
			gl_Position = ftransform();
			texcoord = (gl_TextureMatrix[0] * gl_MultiTexCoord0).xy;
			glcolor = gl_Color;
		}
		""";

	@BeforeAll
	static void noMslCache() {
		// MetalPipeline's cache dir reads Fabric's game dir, which no test has: the cache is off before any Metal
		// class initialises (the property is read once, in its static init).
		System.setProperty("mcms.metal.mslCache", "false");
	}

	@TempDir
	Path temp;

	@Test
	void aTerrainDrawReadsTheBlockAtlasSize() throws Exception {
		int[] texel = draw(writePack("block-atlas"), true, false);
		assertDrawn(texel);
		assertArrayEquals(new int[] {ATLAS_W, ATLAS_H}, sizeOf(texel), "a terrain draw binds the block atlas: atlasSize is its size");
	}

	@Test
	void aDrawOfATextureNoAtlasNamesReadsZero() throws Exception {
		int[] texel = draw(writePack("not-atlas"), false, false);
		assertDrawn(texel);
		assertArrayEquals(new int[] {0, 0}, sizeOf(texel), "a texture that is not an atlas: atlasSize is (0, 0)");
	}

	@Test
	void aLaterPipelineThatBindsNoAtlasReadsZeroNotTheAtlasOfTheDrawBefore() throws Exception {
		int[] texel = draw(writePack("no-atlas-later"), true, true);
		assertDrawn(texel);
		assertArrayEquals(new int[] {0, 0}, sizeOf(texel),
			"a pipeline with no atlas uniform reads (0, 0), not the view the draw before it bound");
	}

	/** The pack's program drew over the clear (alpha 0): the texel is the program's output, not an empty target. */
	private static void assertDrawn(int[] texel) {
		assertEquals(255, texel[3], "the pack's program must have drawn over the clear");
	}

	private static int[] sizeOf(int[] texel) {
		return new int[] {texel[0], texel[1]};
	}

	/** Writes a one-program test pack ({@code gbuffers_terrain}) and returns its folder. */
	private Path writePack(String name) throws Exception {
		Path shaders = temp.resolve(name).resolve("shaders");
		Files.createDirectories(shaders);
		Files.writeString(shaders.resolve("shaders.properties"), "# Quicksilver test pack (AtlasSizeGpuTest)\n",
			StandardCharsets.UTF_8);
		Files.writeString(shaders.resolve("gbuffers_terrain.vsh"), TERRAIN_VSH, StandardCharsets.UTF_8);
		Files.writeString(shaders.resolve("gbuffers_terrain.fsh"), SIZE_FSH, StandardCharsets.UTF_8);
		return temp.resolve(name);
	}

	/**
	 * Draws the terrain quad through {@code sodium:pipeline/solid_terrain} with the pack at {@code packDir}, bound to the block
	 * atlas ({@code atlasBound}) or to a texture no atlas names, and returns the centre texel of colortex4 (r, g, b, a). With
	 * {@code thenWithoutAtlas}, the pipeline is selected again with no atlas uniform and draws a nearer quad after the first:
	 * the texel is then the second draw's.
	 */
	private static int[] draw(Path packDir, boolean atlasBound, boolean thenWithoutAtlas) throws Exception {
		PackModel model;
		try (PackSource source = PackSource.open(packDir)) {
			model = PackModel.load(source, StandardMacros.engine(), "en_us", new Properties());
		}
		assertTrue(model.problems().isEmpty(), "the test pack must load clean: " + model.problems());
		CustomUniforms custom = CustomUniforms.of(model);
		VertexLayoutRegistry.register(EXTENDED, TerrainLayout.layout(0), FACTS);
		GpuDevice device = BackendPassHarness.newDevice();
		GpuTexture atlas = device.createTexture("test block atlas", GpuTexture.USAGE_TEXTURE_BINDING, GpuFormat.RGBA8_UNORM,
			ATLAS_W, ATLAS_H, 1, 1);
		GpuTexture other = device.createTexture("test entity texture", GpuTexture.USAGE_TEXTURE_BINDING,
			GpuFormat.RGBA8_UNORM, OTHER, OTHER, 1, 1);
		GpuSampler sampler = device.createSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE, FilterMode.NEAREST,
			FilterMode.NEAREST, 1, OptionalDouble.empty());
		GpuTextureView view = device.createTextureView(atlasBound ? atlas : other);
		AtlasSizes sizes = new AtlasSizes();
		sizes.add(atlas, true);
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			long ctx = gpu.context();
			long enc = gpu.encoder();
			GbufferFixtures.Owned owned = new GbufferFixtures.Owned();
			DimensionSet set = DimensionSet.load(ctx, packDir.getFileName().toString(), model, model.rootFolder(), custom,
				RGBA8, model.rootFolder().union().colorFormats(), GbufferFixtures.gamePipelines(), copy -> null);
			Targets targets = new Targets(set.dimension().union(), model.properties().bufferSizes(), set.usedTargets(),
				false, false, W, H, Targets.engineTextures(ctx), null);
			try {
				assertTrue(set.absent() == 0, "the pack's programs must all run: " + set.notes());
				assertTrue(targets.create());
				GbufferPlans plans = set.plans();
				assertNotNull(plans, "the plans must build");
				GbufferPlans.Plan plan = plans.planOf(SOLID_TERRAIN);
				assertNotNull(plan, "Sodium's solid terrain pipeline must have a plan");
				assertTrue(plan.variant(PassKind.GBUFFERS, GbufferPhase.OPAQUE) instanceof GbufferPlans.PackVariant,
					"solid terrain must draw through the pack program");

				// The frame block is the game's; the terrain quad is in clip space, so the projection is the identity.
				FrameUniforms uniforms = new FrameUniforms();
				uniforms.update(GbufferFixtures.inputs(W, H, new Matrix4f(), 70f, 192f));
				long[] frame = GbufferFixtures.upload(ctx, GbufferFixtures.block(uniforms), owned);
				long normals = Engine.makeDefaultNormals(ctx);
				owned.add(normals);
				long specular = Engine.makeDefaultSpecular(ctx);
				owned.add(specular);
				long[] colours = new long[plans.layout(GbufferPhase.OPAQUE).length];
				float[][] clears = new float[colours.length][];
				for (int i = 0; i < colours.length; i++) {
					colours[i] = targets.write(plans.layout(GbufferPhase.OPAQUE)[i]);
					clears[i] = new float[] {0, 0, 0, 0, 1};
				}
				MetalBridge.renderBegin(enc, colours, clears, targets.depth(0), true, 1.0f, W, H);

				GbufferDelegate delegate = new GbufferDelegate(plans, new Sources(targets, normals, specular, frame, sizes));
				delegate.begin(enc, true);
				delegate.phase(GbufferPhase.OPAQUE);
				int[] slots = delegate.select(enc, SOLID_TERRAIN, null, null, SODIUM_UNIFORMS);
				assertNotNull(slots, "the terrain pipeline must not be dropped");
				// The game sets its block atlas at u_BlockTex for the terrain draws; the delegate reads the view's size from it.
				delegate.uniform(enc, "u_BlockTex", new TextureViewAndSampler(view, sampler));
				// The globals and the region offset the terrain prologue reads: zeros (no camera offset, no region).
				long zeros = upload(ctx, new byte[256], owned);
				MetalBridge.renderBuffer(enc, TerrainUniforms.GLOBALS_SLOT, zeros, 0);
				MetalBridge.renderBuffer(enc, EngineSlots.PUSH_CONSTANTS, zeros, 0);
				drawQuad(ctx, enc, delegate, thenWithoutAtlas ? FAR : NEAR, owned);
				if (thenWithoutAtlas) {
					assertNotNull(delegate.select(enc, SOLID_TERRAIN, null, null, NO_ATLAS_UNIFORMS),
						"the pipeline without the atlas must not be dropped");
					drawQuad(ctx, enc, delegate, NEAR, owned);
				}
				MetalBridge.endEncoders(enc);
				MetalBridge.commitAndWait(enc);
				delegate.close();

				int centre = ((H / 2) * W + W / 2) * 4;
				return texel(gpu.readTexture(targets.write(4), 0, 0, W, H, 1, 4), centre);
			} finally {
				targets.release();
				owned.release();
			}
		} finally {
			view.close();
			sampler.close();
			other.close();
			atlas.close();
			VertexLayoutRegistry.unregister(EXTENDED);
		}
	}

	/** One quad over the screen at clip depth {@code z}, drawn with the delegate's bound pipeline; the draw must run. */
	private static void drawQuad(long ctx, long enc, GbufferDelegate delegate, float z, GbufferFixtures.Owned owned) {
		TerrainVertex[] quad = new TerrainVertex[4];
		float[][] positions = {{-1, -1, z}, {1, -1, z}, {1, 1, z}, {-1, 1, z}};
		for (int i = 0; i < 4; i++) {
			quad[i] = new TerrainVertex();
			quad[i].x = positions[i][0];
			quad[i].y = positions[i][1];
			quad[i].z = positions[i][2];
			quad[i].color = 0xFFFFFFFF;
			quad[i].u = 0.5f;
			quad[i].v = 0.5f;
			quad[i].light = 0x00F000F0;
		}
		byte[] vertices;
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment memory = arena.allocate(TerrainVertexType.STRIDE * 4L);
			new TerrainEncoder().write(memory.address(), 0x25, quad, 0);
			vertices = memory.toArray(ValueLayout.JAVA_BYTE);
		}
		MetalBridge.vertexBuffer(enc, EngineSlots.VERTEX_DATA, upload(ctx, vertices, owned), 0);
		// The game draws quads through the sequential index buffer (two triangles).
		ByteBuffer indices = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
		for (int index : new int[] {0, 1, 2, 0, 2, 3}) {
			indices.putShort((short) index);
		}
		indices.flip();
		MetalBridge.indexBuffer(enc, upload(ctx, indices.array(), owned), false);
		assertTrue(delegate.beforeDraw(enc), "the terrain draw must run");
		MetalBridge.drawIndexed(enc, 6, 1, 0, 0, 0);
	}

	private static int[] texel(byte[] pixels, int at) {
		int[] out = new int[4];
		for (int i = 0; i < 4; i++) {
			out[i] = pixels[at + i] & 0xff;
		}
		return out;
	}

	private static long upload(long ctx, byte[] bytes, GbufferFixtures.Owned owned) {
		long buffer = MetalBridge.newBuffer(ctx, Math.max(16, bytes.length));
		MemorySegment.ofAddress(MetalBridge.bufferContents(buffer)).reinterpret(bytes.length)
			.copyFrom(MemorySegment.ofArray(bytes));
		owned.add(buffer);
		return buffer;
	}

	/**
	 * The delegate's bindings over the test's targets: the frame block, the engine's default textures, the identity projection,
	 * and the atlas sizes the test's table holds (the same table the engine asks for each draw).
	 */
	private record Sources(Targets targets, long normals, long specular, long[] frame, AtlasSizes sizes)
		implements GbufferDelegate.Bindings {
		@Override
		public long[] frameBlock(String program) {
			return this.frame;
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
			int index = ProgramRequests.targetIndex(colortexName);
			return index >= 0 && index < this.targets.count()
				? write ? this.targets.write(index) : this.targets.read(index) : 0;
		}

		@Override
		public long depth(String name) {
			return this.targets.depth(0);
		}

		@Override
		public long noise() {
			return 0;
		}

		@Override
		public long defaultTexture(String name) {
			return switch (name) {
				case "normals" -> this.normals;
				case "specular" -> this.specular;
				default -> 0;
			};
		}

		@Override
		public void atlasSize(GpuTextureView view, int[] out) {
			this.sizes.sizeOf(view, out);
		}

		@Override
		public void projection(float[] out) {
			new Matrix4f().get(out);
		}
	}
}
