// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime;

import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.mcms.metal.MetalBridge;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The uniform upload guards (the E3 crash: a heap buffer has no native address, and the upload copied from address 0).
 * {@code MetalBridge.transientUniform} refuses a heap buffer before it touches the encoder, for both overloads, and a
 * direct buffer passes that guard. The upload itself needs a backend encoder (a game frame's {@code MetalEncoder}),
 * which the headless device does not have: the direct case therefore stops at the encoder cast, and the game tests
 * cover the copy. The frame path's own buffers are checked where they are uploaded ({@code EngineScaleAndFinalGpuTest}
 * asserts every block the frame uploads is a direct buffer).
 */
@Tag("gpu")
class TransientUniformGpuTest {

	@Test
	void aHeapBufferIsRefusedBeforeTheEncoder() {
		ByteBuffer heap = ByteBuffer.allocate(64);
		assertThrows(IllegalArgumentException.class, () -> MetalBridge.transientUniform(new Object(), heap),
			"a heap buffer has no native address: refused");
		assertThrows(IllegalArgumentException.class, () -> MetalBridge.transientUniform(new Object(), heap, new long[2]),
			"the array-out overload refuses a heap buffer too");
	}

	@Test
	void aDirectBufferPassesTheGuard() {
		ByteBuffer direct = ByteBuffer.allocateDirect(64);
		// The guard passed: the next step casts the encoder to the backend's MetalEncoder, which a plain object is not.
		assertThrows(ClassCastException.class, () -> MetalBridge.transientUniform(new Object(), direct),
			"a direct buffer reaches the encoder");
		assertThrows(ClassCastException.class, () -> MetalBridge.transientUniform(new Object(), direct, new long[2]),
			"the array-out overload accepts a direct buffer too");
	}
}
