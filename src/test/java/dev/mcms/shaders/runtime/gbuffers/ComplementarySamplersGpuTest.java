// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.gbuffers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.renderpearl.api.pipeline.BindGroupLayout.UniformDescription;
import com.mojang.renderpearl.api.pipeline.UniformType;
import dev.mcms.metal.MetalBridge;
import dev.mcms.shaders.metal.HeadlessGpu;
import dev.mcms.shaders.metal.ShaderNative;
import dev.mcms.shaders.pack.DimensionPrograms;
import dev.mcms.shaders.pack.PackModel;
import dev.mcms.shaders.pack.PackSource;
import dev.mcms.shaders.pack.StandardMacros;
import dev.mcms.shaders.runtime.CompiledProgram;
import dev.mcms.shaders.runtime.DimensionSet;
import dev.mcms.shaders.runtime.Engine;
import dev.mcms.shaders.runtime.EngineSlots;
import dev.mcms.shaders.runtime.ProgramRequests;
import dev.mcms.shaders.runtime.SamplerSources;
import dev.mcms.shaders.runtime.Targets;
import dev.mcms.shaders.runtime.terrain.TerrainLayout;
import dev.mcms.shaders.runtime.terrain.TerrainUniforms;
import dev.mcms.shaders.runtime.terrain.TerrainVertexType;
import dev.mcms.shaders.runtime.uniforms.CustomUniforms;
import dev.mcms.shaders.runtime.uniforms.FrameUniforms;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Complementary's gbuffers_terrain (the pack's own program for Sodium's solid terrain), bound through the delegate on the
 * headless device with the pack's options file. Its declared samplers resolve to real sources: {@code tex} is the game's
 * atlas (bound at the slot the select returns), {@code normals} and {@code specular} the engine's defaults, {@code depthtex0},
 * {@code depthtex1}, {@code noisetex} and the colour targets {@code gaux2}/{@code gaux4} the engine's own textures. Every
 * name {@link SamplerSources} gives a source must come back non-zero from the bindings.
 *
 * <p>Complementary declares no {@code lightmap} sampler in any gbuffers program (its light comes from the vertex {@code
 * lmCoord}), so Sodium's {@code u_LightTex} has no slot here: the test checks that too.
 */
@Tag("gpu")
@Tag("corpus")
class ComplementarySamplersGpuTest {

	private static final int W = 640, H = 360, RGBA8 = 70;
	private static final String PACK = "ComplementaryReimagined_r5.9.3.zip";
	private static final int[] ATLAS_TEXEL = {64, 128, 192, 255};

	/** Sodium's extended terrain format: the set's plans key Sodium's solid terrain pipeline by it (the sampler test's rule). */
	private static final com.mojang.renderpearl.api.vertex.VertexFormat EXTENDED = TerrainVertexType.INSTANCE.getVertexFormat();
	private static final PrologueFacts FACTS = new PrologueFacts(EngineSlots.PUSH_CONSTANTS, 0,
		TerrainUniforms.GLOBALS_SLOT, 0, -1);

	@BeforeEach
	void registerSodiumTerrainFormat() {
		VertexLayoutRegistry.register(EXTENDED, TerrainLayout.layout(0), FACTS);
	}

	@AfterEach
	void unregisterSodiumTerrainFormat() {
		VertexLayoutRegistry.unregister(EXTENDED);
	}

	@Test
	void complementaryTerrainSamplersResolveToTheirSources() throws IOException {
		Path zip = corpusFile();
		Properties options = new Properties();
		options.load(Files.newBufferedReader(optionsFile()));
		PackModel model;
		try (PackSource source = PackSource.open(zip)) {
			model = PackModel.load(source, StandardMacros.engine(), "en_us", options);
		}
		CustomUniforms custom = CustomUniforms.of(model);
		DimensionPrograms dimension = overworld(model);
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			long ctx = gpu.context();
			long enc = gpu.encoder();
			GbufferFixtures.Owned owned = new GbufferFixtures.Owned();
			DimensionSet set = DimensionSet.load(ctx, PACK, model, dimension, custom, RGBA8,
				dimension.union().colorFormats(), GbufferFixtures.gamePipelines(), copy -> null);
			Targets targets = new Targets(set.dimension().union(), model.properties().bufferSizes(), set.usedTargets(),
				true, true, W, H, Targets.engineTextures(ctx), null);
			try {
				assertTrue(set.absent() == 0, "the pack's programs must all run: " + set.notes());
				assertTrue(targets.create(), "the device must create the targets");
				GbufferPlans plans = set.plans();
				assertNotNull(plans, "the set must carry its gbuffers plans");
				GbufferPlans.Plan plan = plans.planOf("sodium:pipeline/solid_terrain");
				assertNotNull(plan, "Sodium's solid terrain pipeline must have a plan");
				GbufferPlans.Variant variant = plan.variant(PassKind.GBUFFERS, GbufferPhase.OPAQUE);
				assertTrue(variant instanceof GbufferPlans.PackVariant, "solid terrain must draw through Complementary's gbuffers_terrain");
				GbufferPlans.PackVariant pv = (GbufferPlans.PackVariant) variant;
				List<String> declared = new ArrayList<>();
				for (int i = 0; i < pv.textures().size(); i++) {
					declared.add(pv.textures().get(i).name());
				}
				assertTrue(declared.containsAll(List.of("tex", "normals", "specular", "depthtex0")),
					"gbuffers_terrain declares tex, normals, specular and depthtex0: " + declared);

				FrameUniforms uniforms = new FrameUniforms();
				uniforms.update(GbufferFixtures.inputs(W, H, new Matrix4f(), 70f, 192f));
				long[] frame = GbufferFixtures.upload(ctx, GbufferFixtures.block(uniforms), owned);
				Recording sources = new Recording(targets, Engine.makeDefaultNormals(ctx), Engine.makeDefaultSpecular(ctx),
					noiseTexture(ctx, owned), frame, uniforms);
				owned.add(sources.normals);
				owned.add(sources.specular);
				GbufferDelegate delegate = new GbufferDelegate(plans, sources);
				long[] colours = new long[plans.layout(GbufferPhase.OPAQUE).length];
				float[][] clears = new float[colours.length][];
				for (int i = 0; i < colours.length; i++) {
					colours[i] = targets.write(plans.layout(GbufferPhase.OPAQUE)[i]);
					clears[i] = new float[] {0, 0, 0, 0, 1};
				}
				MetalBridge.renderBegin(enc, colours, clears, targets.depth(0), true, 1.0f, W, H);
				delegate.begin(enc, true);
				delegate.phase(GbufferPhase.OPAQUE);
				List<UniformDescription> sodiumUniforms = List.of(
					new UniformDescription("u_BlockTex", UniformType.COMBINED_IMAGE_SAMPLER),
					new UniformDescription("u_LightTex", UniformType.COMBINED_IMAGE_SAMPLER),
					new UniformDescription("u_Globals", UniformType.UNIFORM_BUFFER));
				int[] slots = delegate.select(enc, "sodium:pipeline/solid_terrain", null, null, sodiumUniforms);
				assertNotNull(slots, "the terrain pipeline must not be dropped");

				// tex: the game's atlas, bound the way MetalRenderPass binds it (texture and sampler slots).
				assertTrue(slots[0] >= 0 && delegate.fragmentSamplerSlot(0) >= 0, "u_BlockTex must bind tex (slot " + slots[0] + ")");
				long atlas = ShaderNative.textureNew(ctx, RGBA8, ShaderNative.TYPE_2D, 4, 4, 1, 1,
					ShaderNative.USAGE_SHADER_READ, ShaderNative.STORAGE_PRIVATE);
				owned.add(atlas);
				byte[] texels = new byte[4 * 4 * 4];
				for (int i = 0; i < texels.length; i++) {
					texels[i] = (byte) ATLAS_TEXEL[i % 4];
				}
				assertTrue(ShaderNative.textureUpload(ctx, atlas, texels, 16, 0, 0, 0, 0, 0, 0, 4, 4, 1));
				long sampler = MetalBridge.samplerNew(ctx, 0, 0, 0, 0, 0, 1, 0.25f);
				owned.add(sampler);
				MetalBridge.renderTextureSlots(enc, slots[0], atlas, sampler, delegate.vertexSamplerSlot(0), delegate.fragmentSamplerSlot(0));
				delegate.atlas(atlas, sampler);

				// Every declared name with an engine source reached the bindings and got a live texture back.
				for (String name : declared) {
					SamplerSources.Source source = SamplerSources.of(name);
					if (source == SamplerSources.Source.ATLAS || source == SamplerSources.Source.LIGHTMAP
							|| source == SamplerSources.Source.UNBOUND) {
						continue;
					}
					Long handle = sources.asked.get(name);
					assertNotNull(handle, name + " (" + source + ") was never bound by the delegate");
					assertTrue(handle != 0, name + " (" + source + ") bound to a zero texture");
				}
				assertEquals(-1, slots[1], "Complementary declares no lightmap sampler, so u_LightTex has no slot");
				assertTrue(!declared.contains("lightmap"), "no lightmap sampler is declared: " + declared);

				MetalBridge.endEncoders(enc);
				MetalBridge.commitAndWait(enc);
				delegate.close();
			} finally {
				targets.release();
				releaseSet(set);
				owned.release();
			}
		}
	}

	/** Releases a set's plans and pass pipelines, as Engine.releaseSet does (package-private there). */
	private static void releaseSet(DimensionSet set) {
		if (set.plans() != null) {
			set.plans().close();
		}
		for (CompiledProgram pass : set.passes()) {
			if (pass.pipeline() != 0) {
				MetalBridge.release(pass.pipeline());
			}
		}
	}

	/** The overworld's programs (the pack's dimension folder when it has one; the root otherwise), as Engine.programsOf picks. */
	private static DimensionPrograms overworld(PackModel model) {
		for (DimensionPrograms programs : model.dimensions()) {
			if (programs.dimension().isPresent() && programs.dimension().get().id.equals("minecraft:overworld")) {
				return programs;
			}
		}
		return model.rootFolder();
	}

	/** A 4x4 RGBA8 noise texture (its texels are not read: the test checks the binding is live). */
	private static long noiseTexture(long ctx, GbufferFixtures.Owned owned) {
		long texture = ShaderNative.textureNew(ctx, RGBA8, ShaderNative.TYPE_2D, 4, 4, 1, 1,
			ShaderNative.USAGE_SHADER_READ, ShaderNative.STORAGE_PRIVATE);
		owned.add(texture);
		return texture;
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

	/** The delegate's bindings over the test's targets; records the handle each name was asked for. */
	private static final class Recording implements GbufferDelegate.Bindings {
		private final Targets targets;
		private final long normals, specular, noise;
		private final long[] frame;
		private final FrameUniforms uniforms;
		/** Each sampler name the delegate asked for, with the handle it got. */
		final Map<String, Long> asked = new HashMap<>();

		Recording(Targets targets, long normals, long specular, long noise, long[] frame, FrameUniforms uniforms) {
			this.targets = targets;
			this.normals = normals;
			this.specular = specular;
			this.noise = noise;
			this.frame = frame;
			this.uniforms = uniforms;
		}

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
			long handle = index >= 0 && index < this.targets.count()
				? write ? this.targets.write(index) : this.targets.read(index) : 0;
			this.asked.put(colortexName, handle);
			return handle;
		}

		@Override
		public long depth(String name) {
			long handle = this.targets.depth(name.equals("depthtex1") ? 1 : name.equals("depthtex2") ? 2 : 0);
			this.asked.put(name, handle);
			return handle;
		}

		@Override
		public long noise() {
			this.asked.put("noisetex", this.noise);
			return this.noise;
		}

		@Override
		public long defaultTexture(String name) {
			long handle = switch (name) {
				case "normals" -> this.normals;
				case "specular" -> this.specular;
				default -> 0;
			};
			this.asked.put(name, handle);
			return handle;
		}

		/**
		 * The shadow names get a live colour target: this fixture has no shadow targets (Engine's bindings return
		 * ShadowTargets' textures; ShadowVariantGpuTest checks those). The test asks only that the name reached the bindings.
		 */
		@Override
		public long shadow(String name) {
			long handle = this.targets.read(0);
			this.asked.put(name, handle);
			return handle;
		}

		@Override
		public void projection(float[] out) {
			this.uniforms.gbufferProjection(out);
		}
	}
}
