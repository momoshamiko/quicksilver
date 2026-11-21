// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.metal;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ShaderNativeBytesTest {

	@Test
	void multipliesDepthEvenWhenBytesPerImageIsZero() {
		// 4 bytes/row * 2 rows * 3 depth slices = 24 bytes
		assertDoesNotThrow(() -> ShaderNative.requireBytes(new byte[24], 4, 0, 2, 3, "read"));
		assertThrows(IllegalArgumentException.class,
			() -> ShaderNative.requireBytes(new byte[8], 4, 0, 2, 3, "read"),
			"depth > 1 with bytesPerImage == 0 must require height * bytesPerRow * depth bytes");
	}

	@Test
	void rejectsNegativeDimensionsAndOverflow() {
		assertThrows(IllegalArgumentException.class, () -> ShaderNative.requireBytes(null, 4, 0, 1, 1, "upload"));
		assertThrows(IllegalArgumentException.class, () -> ShaderNative.requireBytes(new byte[16], 4, 0, 0, 1, "upload"));
		assertThrows(IllegalArgumentException.class, () -> ShaderNative.requireBytes(new byte[16], 4, 0, 1, 0, "upload"));
		assertThrows(IllegalArgumentException.class, () -> ShaderNative.requireBytes(new byte[16], Long.MAX_VALUE, 0, 2, 1, "upload"));
	}
}
