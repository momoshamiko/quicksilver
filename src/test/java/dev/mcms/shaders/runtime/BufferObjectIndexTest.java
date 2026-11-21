// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcms.glsl.msl.ProgramRequest;
import org.junit.jupiter.api.Test;

/**
 * The bufferObject index a block binds (ProgramRequest.BufferBinding) is carried apart from its Metal slot: a storage block
 * with a layout binding N binds bufferObject N, a storage block with no binding binds bufferObject 0 (GL's default), and a
 * uniform block binds none.
 */
final class BufferObjectIndexTest {

	private static ProgramRequest.BufferBinding named(BufferSlots.Assignment a, String name) {
		return a.buffers().stream().filter(b -> b.name().equals(name)).findFirst().orElseThrow();
	}

	@Test
	void aStorageBlockBindsTheBufferObjectOfItsLayoutBinding() {
		BufferSlots.Assignment a = BufferSlots.assign(
			"layout(std430, binding = 3) buffer Verts { vec4 v[]; };\n", ProgramRequest.MAX_BUFFER_BINDINGS);
		assertEquals(3, named(a, "Verts").slot());
		assertEquals(3, named(a, "Verts").bufferObject());
		assertTrue(named(a, "Verts").storage());
	}

	@Test
	void aStorageBlockWithNoBindingBindsBufferObjectZeroAtAFreeSlot() {
		BufferSlots.Assignment a = BufferSlots.assign(
			"layout(binding = 0) buffer Zero { uint z[]; };\nbuffer Free { uint f[]; };\n", ProgramRequest.MAX_BUFFER_BINDINGS);
		assertEquals(1, named(a, "Free").slot(), "the slot 0 is Zero's; Free takes the next free index");
		assertEquals(0, named(a, "Free").bufferObject(), "its bufferObject is GL's default, 0");
		assertTrue(named(a, "Free").storage());
	}

	@Test
	void aUniformBlockBindsNoBufferObject() {
		BufferSlots.Assignment a = BufferSlots.assign("layout(std140, binding = 2) uniform Params { float k; };\n",
			ProgramRequest.MAX_BUFFER_BINDINGS);
		assertEquals(-1, named(a, "Params").bufferObject());
		assertFalse(named(a, "Params").storage());
	}
}
