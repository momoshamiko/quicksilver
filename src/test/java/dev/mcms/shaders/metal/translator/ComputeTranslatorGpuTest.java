// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.metal.translator;

import static dev.mcms.shaders.metal.translator.TranslateSupport.compute;
import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.mcms.glsl.msl.ProgramRequest;
import dev.mcms.glsl.msl.ProgramTranslation;
import dev.mcms.shaders.metal.HeadlessGpu;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("gpu")
final class ComputeTranslatorGpuTest {

	@Test
	void storesIntoAStd430BufferWithAnUnsizedArray() {
		String glsl = """
			#version 430
			layout(local_size_x = 4) in;
			layout(std430, binding = 0) buffer B { float data[]; };
			void main() {
				data[gl_GlobalInvocationID.x] = float(gl_GlobalInvocationID.x) * 2.0;
			}
			""";
		ProgramTranslation t = TranslateSupport.translateOk(compute(glsl,
			List.of(new ProgramRequest.BufferBinding("B", 0)), List.of(), List.of(), List.of()));
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			byte[] out = gpu.runKernel(t.msl(), t.computeEntry(), null, new byte[0][], new int[] {16 * 4}, 4, 1, 1, 4,
				1, 1)[0];
			ByteBuffer bb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN);
			for (int i = 0; i < 16; i++) {
				assertEquals(2.0f * i, bb.getFloat(i * 4), "element " + i + "\n" + t.msl());
			}
		}
	}
}
