// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.metal.translator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcms.glsl.msl.ProgramRequest.BufferBinding;
import dev.mcms.glsl.msl.ProgramTranslation;
import dev.mcms.glsl.msl.ProgramTranslation.Reflection.ResourceUse;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * E6-B: the reflection marks a buffer block as a storage buffer (the engine binds its bufferObject there) and a uniform block
 * as not storage. The block's Metal slot comes from the request; the bufferObject index is the request's own number.
 */
final class StorageReflectionTest {

	private static final String GLSL = """
		#version 430
		layout(local_size_x = 1) in;
		layout(std430, binding = 2) buffer Values { uint v[]; };
		layout(std430, binding = 3) buffer Counter { uint c[]; };
		void main() {
			v[0] = 1u;
			c[0] = 2u;
		}
		""";

	private static final String UNIFORM_GLSL = """
		#version 430
		layout(local_size_x = 1) in;
		layout(std140, binding = 2) uniform Params { float k; };
		layout(std430, binding = 3) buffer Out { uint v[]; };
		void main() {
			v[0] = uint(k);
		}
		""";

	private static final String INSTANCE_GLSL = """
		#version 430
		layout(local_size_x = 1) in;
		layout(std430, binding = 4) buffer scanBuffer { uint voxelAny[2]; } scanSSBO;
		void main() {
			scanSSBO.voxelAny[0] = 1u;
		}
		""";

	private static ResourceUse named(List<ResourceUse> uses, String name) {
		for (ResourceUse use : uses) {
			if (use.name().equals(name)) {
				return use;
			}
		}
		throw new AssertionError("no reflected block " + name + " in " + uses);
	}

	@Test
	void everyBufferBlockIsReflectedAsStorageAtItsSlot() {
		ProgramTranslation t = TranslateSupport.translateOk(TranslateSupport.compute(GLSL,
			List.of(new BufferBinding("Values", 2, 2), new BufferBinding("Counter", 3, 3)), List.of(), List.of(), List.of()));
		List<ResourceUse> buffers = t.reflection().buffers();
		assertEquals(2, buffers.size(), buffers.toString());
		for (ResourceUse use : buffers) {
			assertTrue(use.storage(), use + " is a buffer block, so storage");
		}
		assertEquals(2, buffers.get(0).slot());
		assertEquals(3, buffers.get(1).slot());
	}

	@Test
	void aUniformBlockIsReflectedAsNotStorageAndABufferBlockAsStorage() {
		ProgramTranslation t = TranslateSupport.translateOk(TranslateSupport.compute(UNIFORM_GLSL,
			List.of(new BufferBinding("Params", 0), new BufferBinding("Out", 3, 3)), List.of(), List.of(), List.of()));
		List<ResourceUse> buffers = t.reflection().buffers();
		assertFalse(named(buffers, "Params").storage(), "a uniform block is not a storage buffer");
		assertTrue(named(buffers, "Out").storage(), "a buffer block is a storage buffer");
	}

	@Test
	void anInstanceNamedBlockCarriesTheBufferObjectOfItsBlockName() {
		// The reflection names the block by its instance (scanSSBO); the request names it by its block name (scanBuffer). The
		// merged E6 tree reflected -1 here, so the compute test pack's composite and shadowcomp were refused (2026-11-15).
		ProgramTranslation t = TranslateSupport.translateOk(TranslateSupport.compute(INSTANCE_GLSL,
			List.of(new BufferBinding("scanBuffer", 1, 4)), List.of(), List.of(), List.of()));
		ResourceUse use = named(t.reflection().buffers(), "scanSSBO");
		assertTrue(use.storage(), use.toString());
		assertEquals(1, use.slot(), "the Metal slot is the request's");
		assertEquals(4, use.bufferObject(), "the bufferObject is the block's layout binding");
	}
}
