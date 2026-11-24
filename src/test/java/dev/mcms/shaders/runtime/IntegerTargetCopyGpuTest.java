// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import dev.mcms.metal.BackendPassHarness;
import dev.mcms.shaders.pack.DimensionPrograms;
import dev.mcms.shaders.pack.PackModel;
import dev.mcms.shaders.pack.PackSource;
import dev.mcms.shaders.pack.StandardMacros;
import dev.mcms.shaders.runtime.gbuffers.GbufferMapping;
import dev.mcms.shaders.runtime.gbuffers.GbufferPhase;
import dev.mcms.shaders.runtime.gbuffers.GbufferPlans;
import dev.mcms.shaders.runtime.gbuffers.PassKind;
import dev.mcms.shaders.runtime.gbuffers.VertexLayoutRegistry;
import dev.mcms.shaders.runtime.terrain.TerrainLayout;
import dev.mcms.shaders.runtime.uniforms.CustomUniforms;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * A copy of a game pipeline in a group with an integer target compiles on Metal (2026-11-23). Noble 1.9.9 declares
 * colortex1 as RGBA32UI. The game's oit_transmittance pipelines write two float4 outputs (locations 0 and 1, the coefficients of
 * oit_add_transmittance.glsl: LevelRenderer.OIT_TRANSMITTANCE_TARGET_COUNT is 2), and the copy of each puts its second output on
 * the group's integer attachment with no write. Metal checks an output's type against its attachment even with the write mask
 * none, so those copies failed to build ("output of type float4 is not compatible with a MTLPixelFormatRGBA32Uint color attachment"):
 * 52 "did not compile" lines in Noble's live log. The same log has no "has no compiled copy" line, and EngineLevelTransparencyMixin
 * keeps the game's OIT passes out of the frame while the engine serves the world, so no draw was lost. The fix removes the 52
 * refusals, so that a draw which needs a copy has one. The copies here compile the way the game's pipeline cache compiles them:
 * the frontend's own pipeline builder over the game's shader files, not a stub. A refused copy fails the test with Metal's message.
 */
@Tag("gpu")
class IntegerTargetCopyGpuTest {

	private static final String PACK = "mcms-uint-target";

	@BeforeAll
	static void headless() throws Exception {
		// MetalPipeline's cache directory asks Fabric for the game dir, which a headless test has none of.
		System.setProperty("mcms.metal.mslCache", "false");
		// The frontend's pipeline builder reads the render system's device, as the game's pipeline cache does.
		if (RenderSystem.tryGetDevice() == null) {
			RenderSystem.initRenderThread();  // once per JVM: another GPU test class may have initialised it with the device
		}
		if (RenderSystem.tryGetDevice() == null) {
			RenderSystem.initRenderer(BackendPassHarness.newDevice());
		}
	}

	@Test
	void everyCopyOfAGameOutputPipelineInAGroupWithAnIntegerTargetCompiles() throws Exception {
		PackModel model;
		try (PackSource source = PackSource.open(findTestPack(PACK))) {
			model = PackModel.load(source, StandardMacros.engine(), "en_us", new Properties());
		}
		assertTrue(model.problems().isEmpty(), "the test pack loads clean: " + model.problems());
		DimensionPrograms dimension = model.rootFolder();
		// The game pipelines whose transmittance pass writes two outputs (Noble's failing copies, all 26 of them).
		List<RenderPipeline> transmittance = new ArrayList<>();
		for (RenderPipeline pipeline : Engine.gamePipelines()) {
			if (pipeline.getLocation().getPath().startsWith("pipeline/oit_transmittance_")) {
				transmittance.add(pipeline);
			}
		}
		assertFalse(transmittance.isEmpty(), "the game lists its oit_transmittance pipelines");

		GpuDevice device = RenderSystem.getDevice();
		List<String> refused = new ArrayList<>();
		try (GameShaders shaders = new GameShaders()) {
			GbufferPlans.GameCopier copier = copy -> compile(device, shaders, copy, refused);
			VertexLayoutRegistry.register(GbufferRoutingGpuTest.EXTENDED, TerrainLayout.layout(0), GbufferBuildSupport.TERRAIN_FACTS);
			try (GbufferPlans plans = GbufferPlans.build(BackendPassHarness.contextOf(device), model, dimension,
					CustomUniforms.of(model), dimension.union().colorFormats(), transmittance, copier)) {
				assertEquals(List.of(), refused, "every copy of a game pipeline in an integer group compiles on the backend");
				for (GbufferPhase phase : List.of(GbufferPhase.OPAQUE, GbufferPhase.TRANSLUCENT)) {
					List<Integer> groups = integerGroups(plans, phase);
					assertFalse(groups.isEmpty(), phase + " has a group with the integer colortex1 (the premise)");
					assertTrue(assertIntegerGroupCopiesDraw(plans, transmittance, phase, groups) > 0,
						phase + " draws the game's transmittance pipelines through copies in its integer groups (the premise)");
				}
			} finally {
				VertexLayoutRegistry.unregister(GbufferRoutingGpuTest.EXTENDED);
			}
		}
	}

	/** Compiles one copy the way the game's pipeline cache does (FrontendGpuDevice.compilePipeline); null and noted when refused. */
	private static @Nullable Object compile(GpuDevice device, ShaderSource shaders, RenderPipeline copy, List<String> refused) {
		try {
			return device.compilePipeline(copy, shaders, Runnable::run).join().finishCompile();
		} catch (RuntimeException failed) {
			refused.add(copy.getLocation() + ": " + failed.getMessage());
			return null;
		}
	}

	/** The groups of the phase whose attachments include colortex1, the integer target: where copyDefines narrows the copies. */
	private static List<Integer> integerGroups(GbufferPlans plans, GbufferPhase phase) {
		List<Integer> groups = new ArrayList<>();
		for (int group = 0; group < plans.groups(phase); group++) {
			for (int layoutTarget : plans.layout(phase, group)) {
				if (layoutTarget == 1) {
					groups.add(group);
					break;
				}
			}
		}
		return groups;
	}

	/**
	 * Every copy of a transmittance pipeline in one of the phase's integer groups has a compiled backend object, so its draws bind
	 * (the copies are compiled in build). Only those groups count: a copy in a group without colortex1 does not exercise copyDefines.
	 * Returns the number of copies checked, which the caller requires to be positive.
	 */
	private static int assertIntegerGroupCopiesDraw(GbufferPlans plans, List<RenderPipeline> transmittance, GbufferPhase phase,
			List<Integer> groups) {
		int copies = 0;
		for (RenderPipeline game : transmittance) {
			GbufferPlans.Plan plan = plans.planOf(game.getLocation().toString());
			assertNotNull(plan, "the plans hold " + game.getLocation());
			for (GbufferMapping.Domain domain : GbufferMapping.Domain.values()) {
				for (int group : groups) {
					GbufferPlans.GameVariant variant = plan.gameVariant(PassKind.GBUFFERS, phase, domain, group);
					if (variant != null && !variant.ownPipeline()) {
						assertNotNull(variant.copy(), game.getLocation() + " draws with its compiled copy in " + phase + " group " + group);
						copies++;
					}
				}
			}
		}
		return copies;
	}

	/**
	 * The game's shader files, read from the game jar's assets under the ids the game's pipelines name ({@code minecraft:core/entity},
	 * {@code minecraft:oit.glsl}), as the game's shader source does.
	 */
	private static final class GameShaders implements ShaderSource {
		private final Map<String, CachedIncludeSource> includes = new HashMap<>();

		@Override
		public String getShader(Identifier id, ShaderType type) {
			return read("assets/" + id.getNamespace() + "/shaders/" + id.getPath() + (type == ShaderType.VERTEX ? ".vsh" : ".fsh"));
		}

		@Override
		public CachedIncludeSource getInclude(Identifier id) {
			return this.includes.computeIfAbsent(id.toString(),
				unused -> CachedIncludeSource.create(id, read("assets/" + id.getNamespace() + "/shaders/include/" + id.getPath())));
		}

		@Override
		public void close() {
			this.includes.values().forEach(CachedIncludeSource::close);
			this.includes.clear();
		}

		private static String read(String resource) {
			try (InputStream in = GameShaders.class.getClassLoader().getResourceAsStream(resource)) {
				if (in == null) {
					throw new IllegalStateException(resource + " is not on the test classpath");
				}
				return new String(in.readAllBytes(), StandardCharsets.UTF_8);
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		}
	}

	private static Path findTestPack(String name) {
		Path dir = Path.of(System.getProperty("user.dir"));
		for (int i = 0; i < 6 && dir != null; i++) {
			Path candidate = dir.resolve(Path.of("bench", "test-packs", name));
			if (Files.isDirectory(candidate)) {
				return candidate;
			}
			dir = dir.getParent();
		}
		throw new IllegalStateException("bench/test-packs/" + name + " not found above " + System.getProperty("user.dir"));
	}
}
