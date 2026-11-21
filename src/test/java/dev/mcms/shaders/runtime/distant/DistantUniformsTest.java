// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.distant;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.mcms.glsl.msl.ProgramRequest.UniformBlock;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

class DistantUniformsTest {

	@Test
	void blockOffsetsFollowStd140() {
		UniformBlock block = DistantUniforms.block(19);
		assertEquals("QsDh", block.name());
		assertEquals(19, block.slot());
		assertEquals(0, DistantUniforms.offsetOf(block, "dhNearPlane"));
		assertEquals(4, DistantUniforms.offsetOf(block, "dhFarPlane"));
		assertEquals(8, DistantUniforms.offsetOf(block, "dhRenderDistance"));
		// mat4 aligns to 16: the three matrices are 64 bytes each after the 16-byte scalar head.
		assertEquals(16, DistantUniforms.offsetOf(block, "dhProjection"));
		assertEquals(80, DistantUniforms.offsetOf(block, "dhProjectionInverse"));
		assertEquals(144, DistantUniforms.offsetOf(block, "dhPreviousProjection"));
		assertEquals(208, DistantUniforms.size(block));
	}

	@Test
	void writeStoresScalarsAndColumnMajorMatrices() {
		UniformBlock block = DistantUniforms.block(19);
		Matrix4f projection = new Matrix4f().perspective((float) Math.toRadians(70), 1.5f, 0.05f, 2048f, false);
		Matrix4f inverse = new Matrix4f(projection).invert();
		Matrix4f previous = new Matrix4f().identity().translate(1f, 2f, 3f);
		DistantUniforms.Values values = new DistantUniforms.Values(0.05f, 2048f, 4096, projection, inverse, previous);
		ByteBuffer out = ByteBuffer.allocate(DistantUniforms.size(block)).order(ByteOrder.LITTLE_ENDIAN);
		DistantUniforms.write(block, values, out);
		out.flip();
		assertEquals(0.05f, out.getFloat(0), 0f);
		assertEquals(2048f, out.getFloat(4), 0f);
		assertEquals(4096, out.getInt(8));
		// Column-major: element (column 1, row 0) is the 5th float of the matrix.
		assertEquals(projection.get(1, 0), out.getFloat(16 + 4 * Float.BYTES), 0f);
		assertEquals(projection.get(0, 1), out.getFloat(16 + 1 * Float.BYTES), 0f);
		assertEquals(inverse.get(3, 3), out.getFloat(80 + 15 * Float.BYTES), 0f);
		// The translation column of the previous projection sits at column 3, rows 0-2.
		assertEquals(1f, out.getFloat(144 + 12 * Float.BYTES), 0f);
		assertEquals(3f, out.getFloat(144 + 14 * Float.BYTES), 0f);
	}

	@Test
	void writeRefusesABufferThatIsTooSmall() {
		UniformBlock block = DistantUniforms.block(19);
		DistantUniforms.Values values = new DistantUniforms.Values(0.05f, 2048f, 4096, new Matrix4f(), new Matrix4f(),
			new Matrix4f());
		ByteBuffer small = ByteBuffer.allocate(DistantUniforms.size(block) - 1);
		org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
			() -> DistantUniforms.write(block, values, small));
	}
}
