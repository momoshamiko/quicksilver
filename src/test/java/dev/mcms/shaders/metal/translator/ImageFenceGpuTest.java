// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.metal.translator;

import static dev.mcms.shaders.metal.translator.TranslateSupport.compute;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcms.glsl.msl.ProgramRequest;
import dev.mcms.glsl.msl.ProgramTranslation;
import dev.mcms.metal.MetalBridge;
import dev.mcms.shaders.metal.HeadlessGpu;
import dev.mcms.shaders.metal.ShaderNative;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * A kernel stores into a read-write image and reads its own store back: the store is fenced (plan.md E6), so the read sees
 * the write. A second kernel then samples the same texture through a sampler and sees every write of the first.
 */
@Tag("gpu")
final class ImageFenceGpuTest {

	private static final int SIZE = 16;

	private static final String STORE_AND_READ_BACK = """
		#version 430
		layout(local_size_x = 8, local_size_y = 8) in;
		layout(r32f) uniform image2D img;
		layout(std430, binding = 0) buffer Out { float readBack[]; };
		void main() {
			ivec2 p = ivec2(gl_GlobalInvocationID.xy);
			imageStore(img, p, vec4(float(p.x + p.y * 16) + 1.0));
			readBack[p.y * 16 + p.x] = imageLoad(img, p).x;
		}
		""";

	private static final String SAMPLE = """
		#version 430
		layout(local_size_x = 8, local_size_y = 8) in;
		uniform sampler2D tex;
		layout(std430, binding = 0) buffer Out { float sampled[]; };
		void main() {
			ivec2 p = ivec2(gl_GlobalInvocationID.xy);
			vec2 uv = (vec2(p) + 0.5) / 16.0;
			sampled[p.y * 16 + p.x] = textureLod(tex, uv, 0.0).x;
		}
		""";

	@Test
	void aStoreIsReadBackByItsThreadAndSampledByTheNextKernel() {
		ProgramTranslation store = TranslateSupport.translateOk(compute(STORE_AND_READ_BACK,
			List.of(new ProgramRequest.BufferBinding("Out", 0)), List.of(),
			List.of(new ProgramRequest.ImageBinding("img", 0)), List.of()));
		ProgramRequest.SamplerState nearest = new ProgramRequest.SamplerState(ProgramRequest.Filter.NEAREST, null,
			ProgramRequest.Wrap.CLAMP_TO_EDGE, false, false);
		ProgramTranslation sample = TranslateSupport.translateOk(compute(SAMPLE,
			List.of(new ProgramRequest.BufferBinding("Out", 0)),
			List.of(ProgramRequest.TextureBinding.constexpr("tex", 0, ProgramRequest.TextureKind.COLOUR, nearest)),
			List.of(), List.of()));
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			long texture = ShaderNative.textureNew(gpu.context(), HeadlessGpu.R32_FLOAT, ShaderNative.TYPE_2D, SIZE, SIZE, 1,
				1, ShaderNative.USAGE_SHADER_READ | ShaderNative.USAGE_SHADER_WRITE, ShaderNative.STORAGE_PRIVATE);
			assertTrue(texture != 0, "the device refused the texture");
			try {
				int bytes = SIZE * SIZE * 4;
				byte[] readBack = gpu.runKernelOn(store.msl(), store.computeEntry(), new long[] {texture}, new byte[0][],
					new int[] {bytes}, SIZE / 8, SIZE / 8, 1, 8, 8, 1)[0];
				byte[] sampled = gpu.runKernelOn(sample.msl(), sample.computeEntry(), new long[] {texture}, new byte[0][],
					new int[] {bytes}, SIZE / 8, SIZE / 8, 1, 8, 8, 1)[0];
				ByteBuffer read = ByteBuffer.wrap(readBack).order(ByteOrder.LITTLE_ENDIAN);
				ByteBuffer seen = ByteBuffer.wrap(sampled).order(ByteOrder.LITTLE_ENDIAN);
				for (int i = 0; i < SIZE * SIZE; i++) {
					assertEquals(i + 1.0f, read.getFloat(i * 4), "the store's read-back at pixel " + i + "\n" + store.msl());
					assertEquals(i + 1.0f, seen.getFloat(i * 4), "the sampled pixel " + i + "\n" + sample.msl());
				}
			} finally {
				MetalBridge.release(texture);
			}
		}
	}
}
