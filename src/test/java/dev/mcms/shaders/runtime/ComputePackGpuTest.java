// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcms.shaders.metal.HeadlessGpu;
import dev.mcms.shaders.pack.PackModel;
import dev.mcms.shaders.pack.PackSource;
import dev.mcms.shaders.pack.StandardMacros;
import dev.mcms.shaders.runtime.uniforms.CustomUniforms;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The mcms-compute test pack (plan.md E6) through the engine's load on the headless Metal device: its compute kernels
 * translate and compile (shadowcomp is compute-only; composite runs {@code composite_a} before its own stages), nothing is
 * absent, and its three custom images are created with the names the pack binds them under. The frame's verdict on the
 * device is the game run (bench/compute_check.py); this test proves the pipelines exist.
 */
@Tag("gpu")
class ComputePackGpuTest {

	private static final int W = 1920, H = 1080;
	private static final int RGBA8 = 70;

	private static PackModel model;
	private static CustomUniforms custom;

	@BeforeAll
	static void loadPack() throws Exception {
		Path packDir = findPack();
		Properties options = new Properties();
		try (PackSource source = PackSource.open(packDir)) {
			model = PackModel.load(source, StandardMacros.engine(), "en_us", options);
		}
		assertTrue(model.problems().isEmpty(), "the test pack must load clean: " + model.problems());
		custom = CustomUniforms.of(model);
	}

	private static Path findPack() {
		Path dir = Path.of(System.getProperty("user.dir"));
		for (int i = 0; i < 6; i++) {
			Path candidate = dir.resolve(Path.of("bench", "test-packs", "mcms-compute"));
			if (Files.isDirectory(candidate)) {
				return candidate;
			}
			dir = dir.getParent();
		}
		throw new IllegalStateException("bench/test-packs/mcms-compute not found above " + System.getProperty("user.dir"));
	}

	@Test
	void theKernelsOfTheComputePackCompileAndNothingIsAbsent() {
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			long ctx = gpu.context();
			DimensionSet set = DimensionSet.load(ctx, "mcms-compute", model, model.rootFolder(), custom, RGBA8,
				model.rootFolder().union().colorFormats(), Engine.gamePipelines(), Engine.COPIES_NOT_COMPILED);
			assertEquals(0, set.absent(), "every program must run: " + set.notes());

			CompiledProgram shadowcomp = passNamed(set, "shadowcomp");
			assertEquals(0, shadowcomp.pipeline(), "shadowcomp has no render stage: compute-only");
			assertEquals(1, shadowcomp.kernels().size(), "shadowcomp.csh is its one kernel");
			assertEquals(List.of(4, 4, 4), List.of(shadowcomp.kernels().get(0).localX(),
				shadowcomp.kernels().get(0).localY(), shadowcomp.kernels().get(0).localZ()), "its local size");
			assertNotEquals(0L, shadowcomp.kernels().get(0).pipeline());

			CompiledProgram composite = passNamed(set, "composite");
			assertNotEquals(0L, composite.pipeline(), "composite has its own vertex and fragment stages");
			assertEquals(1, composite.kernels().size(), "composite_a.csh runs before composite's stages");
			assertEquals("composite_a", composite.kernels().get(0).name().replaceAll(".*/", "").replace(".csh", ""));
		}
	}

	@Test
	void theCustomImagesAreCreatedUnderTheNamesThePackBindsThem() {
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			CustomImages images = CustomImages.create(gpu.context(), model.properties().images(), W, H);
			assertEquals(3, images.count());
			assertEquals(1, images.indexOfImage("voxel_img"));
			assertEquals(1, images.indexOfSampler("voxel_sampler"));
			assertEquals(-1, images.indexOfImage("missing_img"));
			for (int i = 0; i < images.count(); i++) {
				assertNotEquals(0L, images.texture(i), "custom image " + i + " must exist on the device");
			}
			images.release();
		}
	}

	private static CompiledProgram passNamed(DimensionSet set, String name) {
		return set.passes().stream().filter(p -> p.name().equals(name)).findFirst()
			.orElseThrow(() -> new AssertionError("no pass " + name + " in " + set.passes().stream()
				.map(CompiledProgram::name).toList()));
	}
}
