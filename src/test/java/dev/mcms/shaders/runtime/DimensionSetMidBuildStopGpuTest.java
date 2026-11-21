// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcms.metal.MetalBridge;
import dev.mcms.shaders.metal.HeadlessGpu;
import dev.mcms.shaders.pack.DimensionPrograms;
import dev.mcms.shaders.pack.PackModel;
import dev.mcms.shaders.pack.PackSource;
import dev.mcms.shaders.pack.StandardMacros;
import dev.mcms.shaders.runtime.gbuffers.GbufferPlans;
import dev.mcms.shaders.runtime.uniforms.CustomUniforms;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A load stopped in the middle of its build (plan.md E6, shutdown; the stop points of GbufferPlans and DimensionSet) releases
 * everything it made: the native pipeline states, libraries and depth states return to their counts before the load, and no
 * set is returned. The stop is a cancel check that turns true after N calls, so the stops fall at translations, library
 * compiles, pipeline states, and kernels and passes in hand. The first load that finishes is released as a set and checked the
 * same way. A small pack of a composite, a compute kernel and a gbuffers program covers each kind of object.
 */
@Tag("gpu")
final class DimensionSetMidBuildStopGpuTest {

	private static final int W = 64, H = 64;
	private static final int RGBA8 = 70;
	/** Stops at about 1.5 times the last: 0, 1, 2, 4, 7, 11, ... (the early stops and the late ones both reach the build). */
	private static final int STOP_GROWTH_NUMERATOR = 3, STOP_GROWTH_DENOMINATOR = 2;

	private static final String VERTEX = """
		#version 120
		void main() {
			gl_Position = ftransform();
		}
		""";

	private static final String CHECKER = """
		#version 120
		const bool colortex3MipmapEnabled = true;
		/* DRAWBUFFERS:3 */
		void main() {
			float c = mod(floor(gl_FragCoord.x) + floor(gl_FragCoord.y), 2.0);
			gl_FragData[0] = vec4(c, c, c, 1.0);
		}
		""";

	private static final String KERNEL = """
		#version 430
		layout(local_size_x = 1, local_size_y = 1) in;
		const ivec3 workGroups = ivec3(64, 64, 1);
		layout(rgba8) uniform image2D colorimg4;
		uniform sampler2D colortex3;
		void main() {
			vec2 uv = (vec2(gl_GlobalInvocationID.xy) + 0.5) / vec2(64.0);
			float v = textureLod(colortex3, uv, 2.0).r;
			imageStore(colorimg4, ivec2(gl_GlobalInvocationID.xy), vec4(v, v, v, 1.0));
		}
		""";

	private static final String FINAL = """
		#version 120
		uniform sampler2D colortex3;
		void main() {
			gl_FragColor = texture2D(colortex3, gl_FragCoord.xy / vec2(64.0));
		}
		""";

	private static final String TERRAIN = """
		#version 120
		/* DRAWBUFFERS:2 */
		void main() {
			gl_FragData[0] = vec4(0.5);
		}
		""";

	@TempDir
	Path dir;

	@Test
	void aBuildStoppedAtEachStopPointReleasesEveryPipelineLibraryAndDepthStateAndReturnsNoSet() throws Exception {
		PackModel model = load("stops",
			"composite1.vsh", VERTEX, "composite1.fsh", CHECKER,
			"composite2.csh", KERNEL,
			"gbuffers_terrain.vsh", VERTEX, "gbuffers_terrain.fsh", TERRAIN,
			"final.vsh", VERTEX, "final.fsh", FINAL);
		DimensionPrograms dimension = model.rootFolder();
		Map<Integer, String> formats = dimension.union().colorFormats();
		MetalBridge.trackOn();
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			long ctx = gpu.context();
			long[] before = counts();
			int stopped = 0;
			for (int stop = 0; ; stop = nextStop(stop)) {
				int at = stop;
				java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
				BooleanSupplier cancelAfter = () -> calls.incrementAndGet() > at;  // the build thread and the plan workers both ask
				try {
					// No game pipelines, as DimensionSetStopTest and the shutdown test pass. With the game's list, 7 depth states stayed
					// after the release: MetalPipeline defers their release to the encoder's frame end, which no headless test runs.
					DimensionSet set = DimensionSet.load(ctx, "stops", model, dimension, CustomUniforms.of(model), RGBA8,
						formats, List.of(), Engine.COPIES_NOT_COMPILED, new GbufferPlans.Options(1, Thread.MIN_PRIORITY, cancelAfter));
					// The build finished before the stop point: the set is released the way an engine release releases it.
					assertEquals(0, set.absent(), "the complete build must compile every program: " + set.notes());
					Engine.releaseSet(set);
					waitForCounts(before, "the released set at stop " + at);
					break;
				} catch (GbufferPlans.BuildCancelled cancelled) {
					stopped++;
					waitForCounts(before, "the build stopped at check " + at);
				}
			}
			assertTrue(stopped >= 4, "the stops must fall inside the build, not only at its start or end (" + stopped + ")");
		}
	}

	/** The next stop point after {@code stop}: 0, 1, 2, 4, 7, 11, 17, ... (about 1.5 times the last). */
	private static int nextStop(int stop) {
		return stop == 0 ? 1 : Math.max(stop + 1, stop * STOP_GROWTH_NUMERATOR / STOP_GROWTH_DENOMINATOR);
	}

	/** The native objects of the kinds a build makes: pipeline states, libraries and depth states. */
	private static long[] counts() {
		return new long[] {MetalBridge.trackCount(MetalBridge.TRACK_PIPELINE), MetalBridge.trackCount(MetalBridge.TRACK_LIBRARY),
			MetalBridge.trackCount(MetalBridge.TRACK_DEPTH)};
	}

	/** Waits for the counts to return to {@code before} (a release on another thread finishes after the call returns). */
	private static void waitForCounts(long[] before, String what) throws InterruptedException {
		long deadline = System.nanoTime() + 10_000_000_000L;
		while (!java.util.Arrays.equals(before, counts()) && System.nanoTime() < deadline) {
			Thread.sleep(10);
		}
		assertArrayEquals(before, counts(), "pipeline, library and depth counts after " + what);
	}

	/** Writes the pack's files under a fresh folder and loads its model; the arguments are file name and text pairs. */
	private PackModel load(String name, String... pairs) throws IOException {
		Path shaders = dir.resolve(name).resolve("shaders");
		Files.createDirectories(shaders);
		Files.writeString(shaders.resolve("shaders.properties"), "");
		for (int i = 0; i < pairs.length; i += 2) {
			Files.writeString(shaders.resolve(pairs[i]), pairs[i + 1]);
		}
		PackModel model;
		try (PackSource source = PackSource.open(dir.resolve(name))) {
			model = PackModel.load(source, StandardMacros.engine(), "en_us", new Properties());
		}
		assertTrue(model.problems().isEmpty(), model.problems().toString());
		return model;
	}
}
