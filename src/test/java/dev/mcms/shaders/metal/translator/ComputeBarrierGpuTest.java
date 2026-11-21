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

/** barrier() on the GPU: the writes of every thread of a group are visible to the group's threads after it (E6). */
@Tag("gpu")
final class ComputeBarrierGpuTest {

	@Test
	void aThreadReadsTheValueItsNeighbourStoredBeforeTheBarrier() {
		String glsl = """
			#version 430
			layout(local_size_x = 8) in;
			layout(std430, binding = 0) buffer Out { uint data[]; };
			shared uint tile[8];
			void main() {
				uint lid = gl_LocalInvocationID.x;
				tile[lid] = gl_GlobalInvocationID.x * 10u;
				barrier();
				data[gl_GlobalInvocationID.x] = tile[(lid + 1u) % 8u];
			}
			""";
		ProgramTranslation t = TranslateSupport.translateOk(compute(glsl,
			List.of(new ProgramRequest.BufferBinding("Out", 0)), List.of(), List.of(), List.of()));
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			byte[] out = gpu.runKernel(t.msl(), t.computeEntry(), null, new byte[0][], new int[] {16 * 4}, 2, 1, 1, 8, 1,
				1)[0];
			ByteBuffer bb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN);
			for (int i = 0; i < 16; i++) {
				int group = i / 8;
				int neighbour = group * 8 + (i % 8 + 1) % 8;
				assertEquals(neighbour * 10, bb.getInt(i * 4), "invocation " + i + "\n" + t.msl());
			}
		}
	}
}
