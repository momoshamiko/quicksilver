// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.metal.translator;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.mcms.glsl.msl.ProgramRequest.BufferBinding;
import dev.mcms.glsl.msl.ProgramTranslation;
import dev.mcms.shaders.metal.HeadlessGpu;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * E6-B: a pack's {@code layout(std430, binding = N) buffer} keeps the GLSL offsets exactly on the device. The block below
 * has a vec3 (aligned to 16, so {@code b} sits at 16), a float after it (packed at 28), a vec3 array (stride 16: 32 and 48),
 * a vec2 (aligned to 8: 64) and a float array (stride 4: 72, 76, 80). The kernel writes each member; the read-back bytes
 * must sit at exactly those offsets.
 */
@Tag("gpu")
final class Std430ExactGpuTest {

	private static final String GLSL = """
		#version 430
		layout(local_size_x = 1) in;
		layout(std430, binding = 0) buffer Out {
			float a;
			vec3 b;
			float c;
			vec3 arr[2];
			vec2 d;
			float e[3];
		};
		void main() {
			a = 1.5;
			b = vec3(2.0, 3.0, 4.0);
			c = 5.5;
			arr[0] = vec3(6.0, 7.0, 8.0);
			arr[1] = vec3(9.0, 10.0, 11.0);
			d = vec2(12.0, 13.0);
			e[0] = 14.0;
			e[1] = 15.0;
			e[2] = 16.0;
		}
		""";

	@Test
	void everyMemberOfAStd430BlockSitsAtItsGlslOffset() {
		ProgramTranslation t = TranslateSupport.translateOk(TranslateSupport.compute(GLSL,
			List.of(new BufferBinding("Out", 0)), List.of(), List.of(), List.of()));
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			byte[] out = gpu.runKernel(t.msl(), t.computeEntry(), null, new byte[0][], new int[] {96}, 1, 1, 1, 1, 1, 1)[0];
			ByteBuffer bb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN);
			assertEquals(1.5f, bb.getFloat(0), "a at 0");
			assertEquals(2.0f, bb.getFloat(16), "b.x at 16");
			assertEquals(3.0f, bb.getFloat(20), "b.y at 20");
			assertEquals(4.0f, bb.getFloat(24), "b.z at 24");
			assertEquals(5.5f, bb.getFloat(28), "c at 28 (packed after the vec3)");
			assertEquals(6.0f, bb.getFloat(32), "arr[0].x at 32");
			assertEquals(8.0f, bb.getFloat(40), "arr[0].z at 40");
			assertEquals(9.0f, bb.getFloat(48), "arr[1].x at 48 (stride 16)");
			assertEquals(11.0f, bb.getFloat(56), "arr[1].z at 56");
			assertEquals(12.0f, bb.getFloat(64), "d.x at 64 (vec2 aligned to 8)");
			assertEquals(13.0f, bb.getFloat(68), "d.y at 68");
			assertEquals(14.0f, bb.getFloat(72), "e[0] at 72");
			assertEquals(15.0f, bb.getFloat(76), "e[1] at 76 (stride 4)");
			assertEquals(16.0f, bb.getFloat(80), "e[2] at 80");
		}
	}
}
