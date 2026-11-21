// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.metal.translator;

import static dev.mcms.shaders.metal.translator.TranslateSupport.compute;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcms.glsl.msl.ProgramRequest;
import dev.mcms.glsl.msl.ProgramTranslation;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A compute store to a read-write image is followed by texture.fence() (plan.md E6, the fence bullet): the store and its
 * read-back by the same thread are ordered by Metal only after the fence. A fragment stage's store is not fenced (its
 * writes are ordered by the render pass).
 */
final class ComputeFenceTest {

	private static final String READ_BACK = """
		#version 430
		layout(local_size_x = 1) in;
		layout(r32ui) uniform uimage2D img;
		layout(std430, binding = 0) buffer Out { uint data[]; };
		void main() {
			imageStore(img, ivec2(0), uvec4(7u));
			data[0] = imageLoad(img, ivec2(0)).x;
		}
		""";

	@Test
	void aComputeStoreIsFencedBeforeItsReadBack() {
		ProgramTranslation t = TranslateSupport.translateOk(compute(READ_BACK,
			List.of(new ProgramRequest.BufferBinding("Out", 0)), List.of(),
			List.of(new ProgramRequest.ImageBinding("img", 1)), List.of()));
		assertTrue(t.msl().contains(".fence()"), "the store must be fenced:\n" + t.msl());
		assertTrue(t.msl().contains(".write("), "the store is still a write:\n" + t.msl());
	}

	@Test
	void aComputeKernelWithNoStoreHasNoFence() {
		String glsl = """
			#version 430
			layout(local_size_x = 1) in;
			layout(std430, binding = 0) buffer Out { uint data[]; };
			void main() { data[0] = 1u; }
			""";
		ProgramTranslation t = TranslateSupport.translateOk(compute(glsl,
			List.of(new ProgramRequest.BufferBinding("Out", 0)), List.of(), List.of(), List.of()));
		assertFalse(t.msl().contains(".fence()"), t.msl());
	}
}
