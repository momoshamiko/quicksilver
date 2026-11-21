// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcms.metal.MetalBridge;
import dev.mcms.shaders.metal.HeadlessGpu;
import dev.mcms.shaders.metal.ShaderNative;
import dev.mcms.shaders.pack.PackModel;
import dev.mcms.shaders.pack.PackSource;
import dev.mcms.shaders.pack.StandardMacros;
import dev.mcms.shaders.runtime.uniforms.CustomUniforms;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Passes that write a custom image on the device (E6; the compute-only pass and the vertex stage of a draw):
 * <ul>
 * <li>a composite program with only a compute stage has no render stage, so the pass runs its kernel and draws nothing;</li>
 * <li>a composite vertex stage stores into the custom image and the fragment stage reads it back through its sampler, in the
 *     same pass (the write is fenced before the read).</li>
 * </ul>
 */
@Tag("gpu")
final class PassImageGpuTest {

	private static final int W = 16, H = 16, RGBA8 = 70;
	private static final int RT_READ = ShaderNative.USAGE_SHADER_READ | ShaderNative.USAGE_RENDER_TARGET;

	private static final String COMPUTE_ONLY_PROPERTIES = "image.sums=sums_s RED_INTEGER R32UI UNSIGNED_INT false false 16 16\n";

	private static final String COMPUTE_KERNEL = """
		#version 430
		layout(local_size_x = 1, local_size_y = 1) in;
		const ivec3 workGroups = ivec3(16, 16, 1);
		layout(r32ui) uniform uimage2D sums;
		void main() {
			imageStore(sums, ivec2(gl_GlobalInvocationID.xy), uvec4(42u));
		}
		""";

	private static final String VERTEX_STORES = """
		#version 430 compatibility
		layout(r32ui) uniform uimage2D sums;
		void main() {
			imageStore(sums, ivec2(3, 5), uvec4(77u));
			gl_Position = ftransform();
		}
		""";

	private static final String FRAGMENT_READS = """
		#version 430 compatibility
		uniform usampler2D sums_s;
		layout(location = 0) out vec4 fragColor;
		/* DRAWBUFFERS:0 */
		void main() {
			uint value = texelFetch(sums_s, ivec2(3, 5), 0).r;
			fragColor = vec4(float(value) / 255.0, 0.0, 0.0, 1.0);
		}
		""";

	private static final String VERTEX = """
		#version 120
		void main() {
			gl_Position = ftransform();
		}
		""";

	private static final String FINAL = """
		#version 120
		uniform sampler2D colortex0;
		void main() {
			gl_FragColor = texture2D(colortex0, gl_FragCoord.xy / vec2(16.0, 16.0));
		}
		""";

	@TempDir
	Path dir;

	@Test
	void aComputeOnlyCompositePassRunsItsKernelAndHasNoRenderStage() throws Exception {
		Path shaders = dir.resolve("pack/shaders");
		Files.createDirectories(shaders);
		Files.writeString(shaders.resolve("shaders.properties"), COMPUTE_ONLY_PROPERTIES);
		Files.writeString(shaders.resolve("composite.csh"), COMPUTE_KERNEL);
		PackModel model;
		try (PackSource source = PackSource.open(dir.resolve("pack"))) {
			model = PackModel.load(source, StandardMacros.engine(), "en_us", new Properties());
		}
		assertTrue(model.problems().isEmpty(), model.problems().toString());
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			long ctx = gpu.context(), enc = gpu.encoder();
			DimensionSet set = DimensionSet.load(ctx, "compute-only", model, model.rootFolder(), CustomUniforms.of(model), RGBA8,
				model.rootFolder().union().colorFormats(), Engine.gamePipelines(), Engine.COPIES_NOT_COMPILED);
			assertEquals(0, set.absent(), "the kernel must compile: " + set.notes());
			CompiledProgram composite = set.passes().stream().filter(p -> p.name().equals("composite")).findFirst()
				.orElseThrow(() -> new AssertionError("no composite pass"));
			assertEquals(0, composite.pipeline(), "the composite pass has no render stage");
			Targets targets = new Targets(set.dimension().union(), model.properties().bufferSizes(), set.usedTargets(), false,
				false, W, H, Targets.engineTextures(ctx), null, CustomImages.create(ctx, model.properties().images(), W, H));
			assertTrue(targets.create());
			long world = ShaderNative.textureNew(ctx, RGBA8, ShaderNative.TYPE_2D, W, H, 1, 1, RT_READ, ShaderNative.STORAGE_PRIVATE);
			EngineFrame.Main main = new EngineFrame.Main();
			main.set(world, RGBA8, W, H, null);
			EngineFrame frame = new EngineFrame(set, targets, null, 0L, 0, 0, (bytes, out) -> { }, Map.of(), Map.of(),
				new EngineFrame.Gbuffers(new int[0], new int[0], new int[0], false, phase -> { }, null));
			try {
				frame.compositeHook(enc, null, targets, main, passBlocks());
				MetalBridge.commitAndWait(enc);
				assertEquals(42, texel(gpu, targets, 15, 15), "the compute-only pass wrote its kernel's texel");
			} finally {
				frame.close();
				targets.release();
				MetalBridge.release(world);
			}
		}
	}

	@Test
	void aVertexStageStoresIntoAnImageAndTheFragmentStageOfTheSamePassReadsIt() throws Exception {
		Path shaders = dir.resolve("vpack/shaders");
		Files.createDirectories(shaders);
		Files.writeString(shaders.resolve("shaders.properties"), COMPUTE_ONLY_PROPERTIES);
		Files.writeString(shaders.resolve("composite1.vsh"), VERTEX_STORES);
		Files.writeString(shaders.resolve("composite1.fsh"), FRAGMENT_READS);
		Files.writeString(shaders.resolve("final.vsh"), VERTEX);
		Files.writeString(shaders.resolve("final.fsh"), FINAL);
		PackModel model;
		try (PackSource source = PackSource.open(dir.resolve("vpack"))) {
			model = PackModel.load(source, StandardMacros.engine(), "en_us", new Properties());
		}
		assertTrue(model.problems().isEmpty(), model.problems().toString());
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			long ctx = gpu.context(), enc = gpu.encoder();
			DimensionSet set = DimensionSet.load(ctx, "vertex-image", model, model.rootFolder(), CustomUniforms.of(model), RGBA8,
				model.rootFolder().union().colorFormats(), Engine.gamePipelines(), Engine.COPIES_NOT_COMPILED);
			assertEquals(0, set.absent(), "the stages must compile: " + set.notes());
			Targets targets = new Targets(set.dimension().union(), model.properties().bufferSizes(), set.usedTargets(), false,
				false, W, H, Targets.engineTextures(ctx), null, CustomImages.create(ctx, model.properties().images(), W, H));
			assertTrue(targets.create());
			long world = ShaderNative.textureNew(ctx, RGBA8, ShaderNative.TYPE_2D, W, H, 1, 1, RT_READ, ShaderNative.STORAGE_PRIVATE);
			EngineFrame.Main main = new EngineFrame.Main();
			main.set(world, RGBA8, W, H, null);
			EngineFrame frame = new EngineFrame(set, targets, null, 0L, 0, 0, (bytes, out) -> { }, Map.of(), Map.of(),
				new EngineFrame.Gbuffers(new int[0], new int[0], new int[0], false, phase -> { }, null));
			try {
				frame.clearHook(enc, null, targets, passBlocks());
				frame.compositeHook(enc, null, targets, main, passBlocks());
				MetalBridge.commitAndWait(enc);
				assertEquals(77, texel(gpu, targets, 3, 5), "the vertex stage's store reached the image");
				byte[] picture = gpu.readTexture(world, 0, 0, W, H, 1, 4);
				assertEquals(77, picture[((H - 1 - 8) * W + 8) * 4] & 0xff, "the fragment stage read the stored value (red 77)");
			} finally {
				frame.close();
				targets.release();
				MetalBridge.release(world);
			}
		}
	}

	private static EngineFrame.PassBlocks passBlocks() {
		return new EngineFrame.PassBlocks() {
			@Override public long @Nullable [] frameBlock(String program) { return null; }
			@Override public long @Nullable [] customBlock() { return null; }
		};
	}

	/** The texel of the custom image at (x, y), read as a little-endian word (the image is W by H texels). */
	private static int texel(HeadlessGpu gpu, Targets targets, int x, int y) {
		byte[] bytes = gpu.readTexture(targets.customImages().texture(0), 0, 0, W, H, 1, 4);
		return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt((y * W + x) * 4);
	}
}
