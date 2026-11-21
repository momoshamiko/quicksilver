// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.gbuffers;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.renderpearl.api.pipeline.BindGroupLayout.UniformDescription;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import dev.mcms.metal.MetalBridge;
import dev.mcms.shaders.metal.HeadlessGpu;
import dev.mcms.shaders.metal.ShaderNative;
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
import java.util.Properties;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The gbuffers samplers a pack program declares, drawn through Sodium's solid terrain pipeline. Two pack programs, written
 * into a temporary folder, each draw one encoded quad in clip space (an identity projection, as the classes test's terrain
 * draws):
 * <ul>
 * <li>{@code tex} and {@code gtexture} (both atlas names, one program) sample the game's atlas: the texel the test binds
 * at Sodium's {@code u_BlockTex} reaches colortex4 and colortex5 (before the fix {@code tex} read nothing: zero texels);</li>
 * <li>{@code normals} and {@code specular} read the engine's defaults with no PBR pack: RGBA (127,127,255,255) (the documented normal default) and zeros
 * (before the fix both read nothing).</li>
 * </ul>
 * The atlas binds the way MetalRenderPass binds a game sampler: the texture and the sampler at the slots the delegate
 * reports, then the delegate's atlas (the value {@code uniform} would carry) for the mirrored names.
 */
@Tag("gpu")
class SodiumTerrainAtlasNamesGpuTest {

	private static final int W = 64, H = 48, RGBA8 = 70;
	/** The game's atlas texel the test binds: not the default normal or specular, so a wrong source shows. */
	private static final int[] ATLAS_TEXEL = {64, 128, 192, 255};
	/** The documented normal default (shaders.properties "Texture Atlases"): written as the literal, not as the engine's constant. */
	private static final int[] DEFAULT_NORMAL = {127, 127, 255, 255};
	private static final int[] DEFAULT_SPECULAR = {0, 0, 0, 0};
	/** The registered extended terrain format Sodium's passes draw with. */
	private static final VertexFormat EXTENDED = TerrainVertexType.INSTANCE.getVertexFormat();
	private static final PrologueFacts FACTS = new PrologueFacts(EngineSlots.PUSH_CONSTANTS, 0,
		TerrainUniforms.GLOBALS_SLOT, 0, -1);

	/** One program reads the atlas through two names: the mirror path binds the second one. */
	private static final String ATLAS_FSH = """
		#version 130

		// Quicksilver test pack (SodiumTerrainAtlasNamesGpuTest): the atlas through tex and gtexture. Original code.

		uniform sampler2D tex;
		uniform sampler2D gtexture;

		varying vec2 texcoord;

		void main() {
			/* DRAWBUFFERS:045 */
			gl_FragData[0] = vec4(0.0, 0.0, 0.0, 1.0);
			gl_FragData[1] = texture2D(tex, texcoord);
			gl_FragData[2] = texture2D(gtexture, texcoord);
		}
		""";

	/** The engine's PBR defaults: no pack supplies normals or specular, so the program reads the defaults. */
	private static final String DEFAULTS_FSH = """
		#version 130

		// Quicksilver test pack (SodiumTerrainAtlasNamesGpuTest): the PBR defaults through normals and specular. Original code.

		uniform sampler2D normals;
		uniform sampler2D specular;

		varying vec2 texcoord;

		void main() {
			/* DRAWBUFFERS:045 */
			gl_FragData[0] = vec4(0.0, 0.0, 0.0, 1.0);
			gl_FragData[1] = texture2D(normals, texcoord);
			gl_FragData[2] = texture2D(specular, texcoord);
		}
		""";

	/** Pass-through vertex stage, as the classes test pack's gbuffers_terrain. */
	private static final String TERRAIN_VSH = """
		#version 130

		// Quicksilver test pack (SodiumTerrainAtlasNamesGpuTest): pass-through vertex stage. Original code.

		varying vec2 texcoord;
		varying vec4 glcolor;

		void main() {
			gl_Position = ftransform();
			texcoord = (gl_TextureMatrix[0] * gl_MultiTexCoord0).xy;
			glcolor = gl_Color;
		}
		""";

	@TempDir
	Path temp;

	@Test
	void texAndGtextureSampleTheGameAtlas() throws Exception {
		int[][] colortexels = draw(writePack("atlas", ATLAS_FSH));
		assertArrayEquals(ATLAS_TEXEL, colortexels[0], "tex must sample the game's atlas (colortex4)");
		assertArrayEquals(ATLAS_TEXEL, colortexels[1], "gtexture must sample the same atlas (colortex5)");
	}

	@Test
	void normalsAndSpecularSampleTheDocumentedDefaults() throws Exception {
		int[][] colortexels = draw(writePack("defaults", DEFAULTS_FSH));
		assertArrayEquals(DEFAULT_NORMAL, colortexels[0], "normals with no PBR pack: RGBA (127,127,255,255)");
		assertArrayEquals(DEFAULT_SPECULAR, colortexels[1], "specular with no PBR pack: RGBA (0,0,0,0)");
	}

	/** Writes a one-program test pack ({@code gbuffers_terrain} with the given fragment stage) and returns its folder. */
	private Path writePack(String name, String fragment) throws Exception {
		Path shaders = temp.resolve(name).resolve("shaders");
		Files.createDirectories(shaders);
		Files.writeString(shaders.resolve("shaders.properties"), "# Quicksilver test pack (SodiumTerrainAtlasNamesGpuTest)\n",
			StandardCharsets.UTF_8);
		Files.writeString(shaders.resolve("gbuffers_terrain.vsh"), TERRAIN_VSH, StandardCharsets.UTF_8);
		Files.writeString(shaders.resolve("gbuffers_terrain.fsh"), fragment, StandardCharsets.UTF_8);
		return temp.resolve(name);
	}

	/**
	 * Draws one encoded quad through {@code sodium:pipeline/solid_terrain} with the pack at {@code packDir} and returns the
	 * centre texel of colortex4 and colortex5 (RGBA8: four unsigned bytes each).
	 */
	private static int[][] draw(Path packDir) throws Exception {
		PackModel model;
		try (PackSource source = PackSource.open(packDir)) {
			model = PackModel.load(source, StandardMacros.engine(), "en_us", new Properties());
		}
		assertTrue(model.problems().isEmpty(), "the test pack must load clean: " + model.problems());
		CustomUniforms custom = CustomUniforms.of(model);
		VertexLayoutRegistry.register(EXTENDED, TerrainLayout.layout(0), FACTS);
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
				GbufferPlans.Plan plan = plans.planOf("sodium:pipeline/solid_terrain");
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
				List<UniformDescription> sodiumUniforms = List.of(
					new UniformDescription("u_BlockTex", UniformType.COMBINED_IMAGE_SAMPLER),
					new UniformDescription("u_LightTex", UniformType.COMBINED_IMAGE_SAMPLER),
					new UniformDescription("u_Globals", UniformType.UNIFORM_BUFFER));
				long[] colours = new long[plans.layout(GbufferPhase.OPAQUE).length];
				float[][] clears = new float[colours.length][];
				for (int i = 0; i < colours.length; i++) {
					colours[i] = targets.write(plans.layout(GbufferPhase.OPAQUE)[i]);
					clears[i] = new float[] {0, 0, 0, 0, 1};
				}
				MetalBridge.renderBegin(enc, colours, clears, targets.depth(0), true, 1.0f, W, H);

				GbufferDelegate delegate = new GbufferDelegate(plans, new Sources(targets, normals, specular, frame));
				delegate.begin(enc, true);
				delegate.phase(GbufferPhase.OPAQUE);
				int[] slots = delegate.select(enc, "sodium:pipeline/solid_terrain", null, null, sodiumUniforms);
				assertNotNull(slots, "the terrain pipeline must not be dropped");
				if (slots[0] >= 0) {
					// The game's atlas, bound the way MetalRenderPass binds a game sampler (its texture and sampler slots).
					long atlas = atlasTexture(ctx, owned);
					long sampler = MetalBridge.samplerNew(ctx, 0, 0, 0, 0, 0, 1, 0.25f);
					owned.add(sampler);
					MetalBridge.renderTextureSlots(enc, slots[0], atlas, sampler, delegate.vertexSamplerSlot(0), delegate.fragmentSamplerSlot(0));
					delegate.atlas(atlas, sampler);
				}
				// The globals and the region offset the terrain prologue reads: zeros (no camera offset, no region).
				long zeros = upload(ctx, new byte[256], owned);
				MetalBridge.renderBuffer(enc, TerrainUniforms.GLOBALS_SLOT, zeros, 0);
				MetalBridge.renderBuffer(enc, EngineSlots.PUSH_CONSTANTS, zeros, 0);

				// One quad in Sodium's own encoding, covering the screen at clip z = 0.
				TerrainVertex[] quad = new TerrainVertex[4];
				float[][] positions = {{-1, -1, 0}, {1, -1, 0}, {1, 1, 0}, {-1, 1, 0}};
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
				MetalBridge.endEncoders(enc);
				MetalBridge.commitAndWait(enc);
				delegate.close();

				int centre = ((H / 2) * W + W / 2) * 4;
				return new int[][] {texel(gpu.readTexture(targets.write(4), 0, 0, W, H, 1, 4), centre),
					texel(gpu.readTexture(targets.write(5), 0, 0, W, H, 1, 4), centre)};
			} finally {
				targets.release();
				owned.release();
			}
		} finally {
			VertexLayoutRegistry.unregister(EXTENDED);
		}
	}

	private static int[] texel(byte[] pixels, int at) {
		int[] out = new int[4];
		for (int i = 0; i < 4; i++) {
			out[i] = pixels[at + i] & 0xff;
		}
		return out;
	}

	/** The game's atlas: a 4x4 RGBA8 texture filled with {@link #ATLAS_TEXEL}. */
	private static long atlasTexture(long ctx, GbufferFixtures.Owned owned) {
		long texture = ShaderNative.textureNew(ctx, RGBA8, ShaderNative.TYPE_2D, 4, 4, 1, 1,
			ShaderNative.USAGE_SHADER_READ, ShaderNative.STORAGE_PRIVATE);
		byte[] texels = new byte[4 * 4 * 4];
		for (int i = 0; i < texels.length; i++) {
			texels[i] = (byte) ATLAS_TEXEL[i % 4];
		}
		assertTrue(ShaderNative.textureUpload(ctx, texture, texels, 16, 0, 0, 0, 0, 0, 0, 4, 4, 1));
		owned.add(texture);
		return texture;
	}

	private static long upload(long ctx, byte[] bytes, GbufferFixtures.Owned owned) {
		long buffer = MetalBridge.newBuffer(ctx, Math.max(16, bytes.length));
		MemorySegment.ofAddress(MetalBridge.bufferContents(buffer)).reinterpret(bytes.length)
			.copyFrom(MemorySegment.ofArray(bytes));
		owned.add(buffer);
		return buffer;
	}

	/**
	 * The delegate's bindings over the test's targets: the frame block, the engine's default textures, the identity
	 * projection. Names that are not targets read nothing (the test packs use none).
	 */
	private record Sources(Targets targets, long normals, long specular, long[] frame) implements GbufferDelegate.Bindings {
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
		public void projection(float[] out) {
			new Matrix4f().get(out);
		}
	}
}
