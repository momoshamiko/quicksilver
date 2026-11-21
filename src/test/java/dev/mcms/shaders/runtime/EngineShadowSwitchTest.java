// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.device.GpuDevice;
import dev.mcms.metal.BackendPassHarness;
import dev.mcms.shaders.metal.HeadlessGpu;
import dev.mcms.shaders.pack.DimensionPrograms;
import dev.mcms.shaders.pack.PackModel;
import dev.mcms.shaders.pack.PackSource;
import dev.mcms.shaders.pack.ResolvedProgram;
import dev.mcms.shaders.pack.StandardMacros;
import dev.mcms.shaders.runtime.gbuffers.GbufferPlans;
import dev.mcms.shaders.runtime.shadow.ShadowEntityDispatch;
import dev.mcms.shaders.runtime.shadow.ShadowFrame;
import dev.mcms.shaders.runtime.shadow.ShadowFrameDriver;
import dev.mcms.shaders.runtime.shadow.ShadowPassSequence;
import dev.mcms.shaders.runtime.shadow.ShadowTargets;
import dev.mcms.shaders.runtime.uniforms.CustomUniforms;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The shadow pass against the pack's switches (E5 review items 1, 4 and 17). A pack that switches its {@code shadow} program
 * off gets no built-in one and no shadow pass, and its maps are cleared once; a dropped shadow frame clears its maps once.
 * The install tests run on the headless device; the rules are checked without one.
 */
class EngineShadowSwitchTest {

	private static final int W = 64, H = 48;
	private static final int RGBA8 = 70;

	/** The shadow test pack's folder: it serves the world and has a shadow program (bench/test-packs/mcms-shadow). */
	private static final String SHADOW_PACK = "mcms-shadow";

	/** A copy of a test pack in {@code dir} with {@code extra} appended to its shaders.properties. */
	private static PackModel copyPack(Path dir, String extra) throws IOException {
		Path source = testPack(SHADOW_PACK);
		try (Stream<Path> files = Files.walk(source)) {
			for (Path file : files.toList()) {
				Path to = dir.resolve(source.relativize(file).toString());
				if (Files.isDirectory(file)) {
					Files.createDirectories(to);
				} else {
					Files.createDirectories(to.getParent());
					Files.copy(file, to);
				}
			}
		}
		Path properties = dir.resolve("shaders").resolve("shaders.properties");
		Files.writeString(properties, Files.readString(properties) + extra);
		return load(dir);
	}

	/** A pack whose composite reads a shadow map and which has no shadow program of its own. */
	private static PackModel packReadingShadowMap(Path dir) throws IOException {
		Path shaders = Files.createDirectories(dir.resolve("shaders"));
		Files.writeString(shaders.resolve("composite.vsh"), """
			#version 130
			varying vec2 texcoord;
			void main() {
				texcoord = gl_MultiTexCoord0.xy;
				gl_Position = ftransform();
			}
			""");
		Files.writeString(shaders.resolve("composite.fsh"), """
			#version 130
			const int shadowMapResolution = 64;
			uniform sampler2D shadowtex0;
			varying vec2 texcoord;
			void main() {
				gl_FragData[0] = vec4(texture2D(shadowtex0, texcoord).r);
			}
			""");
		return load(dir);
	}

	/**
	 * A pack whose composite reads a shadow map, with its own shadow program switched off in its shaders.properties. Written
	 * here, so the rule's test runs in the public source too, which has no bench/test-packs.
	 */
	private static PackModel packWithShadowProgramOff(Path dir) throws IOException {
		Path shaders = Files.createDirectories(dir.resolve("shaders"));
		Files.writeString(shaders.resolve("shadow.vsh"), """
			#version 130
			void main() {
				gl_Position = ftransform();
			}
			""");
		Files.writeString(shaders.resolve("shadow.fsh"), """
			#version 130
			void main() {
				gl_FragData[0] = vec4(1.0);
			}
			""");
		Files.writeString(shaders.resolve("shaders.properties"), "program.shadow.enabled=false\n");
		return packReadingShadowMap(dir);  // writes the composite that reads the map, then loads the pack
	}

	private static PackModel load(Path dir) throws IOException {
		try (PackSource source = PackSource.open(dir)) {
			return PackModel.load(source, StandardMacros.engine(), "en_us", new Properties());
		}
	}

	/** The test pack's folder, found above the working directory as the other engine tests find theirs. */
	private static Path testPack(String name) {
		Path dir = Path.of(System.getProperty("user.dir"));
		for (int i = 0; i < 6; i++) {
			Path candidate = dir.resolve(Path.of("bench", "test-packs", name));
			if (Files.isDirectory(candidate)) {
				return candidate;
			}
			dir = dir.getParent();
		}
		throw new IllegalStateException("bench/test-packs/" + name + " not found above " + System.getProperty("user.dir"));
	}

	/** The copy's root folder: the set the install tests load (its world passes take the game pipelines). */
	private static DimensionSet loadRoot(long ctx, PackModel model) {
		return DimensionSet.load(ctx, "shadow-switch", model, model.rootFolder(), CustomUniforms.of(model), RGBA8,
			model.rootFolder().union().colorFormats(), Engine.gamePipelines(), Engine.COPIES_NOT_COMPILED);
	}

	/** The game's device for the staged buffers of the dispatch (the same setup as the dispatch's buffer test). */
	private static void device() throws Exception {
		System.setProperty("mcms.metal.mslCache", "false");
		if (RenderSystem.tryGetDevice() == null) {
			RenderSystem.initRenderThread();
		}
		if (RenderSystem.tryGetDevice() == null) {
			GpuDevice device = BackendPassHarness.newDevice();
			RenderSystem.initRenderer(device);
		}
	}

	@Test
	void aSwitchedOffShadowProgramIsNotReplacedByTheBuiltInOne(@TempDir Path dir) throws Exception {
		PackModel model = packWithShadowProgramOff(dir);
		DimensionPrograms root = model.rootFolder();
		ShadowTargets targets = ShadowTargets.of(root);
		assertTrue(targets.used(), "the pack's composite reads a shadow map, so the set uses shadow targets");
		assertTrue(targets.shadowProgramOff(), "the pack switches its shadow program off");
		Map<String, ResolvedProgram> runnable = GbufferPlans.runnablePrograms(root, targets);
		assertFalse(runnable.containsKey("shadow"), "a pack that switched its shadow program off gets no built-in one");
	}

	@Test
	void aPackWithNoShadowProgramGetsTheBuiltInOneWhenItsGbuffersReadAShadowMap(@TempDir Path dir) throws Exception {
		DimensionPrograms root = packReadingShadowMap(dir).rootFolder();
		ShadowTargets targets = ShadowTargets.of(root);
		assertTrue(targets.used());
		assertFalse(targets.shadowProgramOff(), "no shadow program entry at all is not a switched-off one");
		assertEquals(ResolvedProgram.Source.FALLBACK, GbufferPlans.runnablePrograms(root, targets).get("shadow").source(),
			"the built-in program stands in for the missing shadow program");
	}

	@Test
	void theMapsAreClearedOnceAfterAShadowFrameIsDropped() {
		ShadowFrame.Ops failing = (ShadowFrame.Ops) Proxy.newProxyInstance(ShadowFrame.Ops.class.getClassLoader(),
			new Class<?>[] {ShadowFrame.Ops.class}, (proxy, method, args) -> {
				if (method.getName().equals("openPass")) {
					throw new IllegalStateException("a fake open failure");
				}
				return null;
			});
		ShadowFrameDriver driver = new ShadowFrameDriver(new ShadowFrame(failing),
			new ShadowPassSequence.Content(true, true, true, true, true), () -> { });
		assertFalse(Engine.clearsMapsWithoutPass(driver), "an active frame runs its pass, and the pass clears the maps");

		driver.run();

		assertFalse(driver.active());
		assertTrue(Engine.clearsMapsWithoutPass(driver), "a dropped frame's maps are cleared once: no pass writes them now");
	}

	@Test
	@Tag("gpu")
	void aPackWithItsShadowProgramOnRunsTheShadowPass(@TempDir Path dir) throws Exception {
		device();
		PackModel model = copyPack(dir, "");
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			long ctx = gpu.context();
			Engine engine = new Engine();
			engine.setShadowDispatchForTest(ShadowEntityDispatch::withoutGame);
			engine.prepareLoad(model);
			engine.install(loadRoot(ctx, model), new Engine.Surface(ctx, W, H, RGBA8));
			try {
				assertEquals(Engine.State.ACTIVE, engine.state());
				assertNotNull(engine.shadowDriverForTest(), "a pack with its shadow program on installs the shadow pass");
			} finally {
				engine.shutdown();
			}
		}
	}

	@Test
	@Tag("gpu")
	void aPackThatSwitchesItsShadowProgramOffInstallsNoPassAndItsMapsAreClearedOnce(@TempDir Path dir) throws Exception {
		device();
		PackModel model = copyPack(dir, "\nprogram.shadow.enabled=false\n");
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			long ctx = gpu.context();
			Engine engine = new Engine();
			engine.setShadowDispatchForTest(ShadowEntityDispatch::withoutGame);
			engine.prepareLoad(model);
			engine.install(loadRoot(ctx, model), new Engine.Surface(ctx, W, H, RGBA8));
			try {
				assertEquals(Engine.State.ACTIVE, engine.state(), "the set installs, so its passes run");
				assertNull(engine.shadowDriverForTest(), "a switched-off shadow program runs no shadow pass");
				assertTrue(Engine.clearsMapsWithoutPass(engine.shadowDriverForTest()),
					"with no pass the frame clears the maps once");
			} finally {
				engine.shutdown();
			}
		}
	}
}
