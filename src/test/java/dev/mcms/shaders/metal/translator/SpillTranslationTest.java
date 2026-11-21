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
 * The translated text of the compute features whose slots and orderings the GPU tests cannot tell apart (plan.md E6,
 * findings 6, 14 and 16): the spill buffer binds at the fixed spill slot beside a storage block at binding 1, the shared
 * barrier orders device memory for spilled arrays, and a store to a write-only image has no fence.
 */
final class SpillTranslationTest {

	private static final String SPILL = """
		#version 430
		layout(local_size_x = 8) in;
		layout(std430, binding = 1) buffer Out { uint data[]; };
		shared uint big[8200];
		void main() {
			big[gl_LocalInvocationID.x] = gl_LocalInvocationID.x;
			memoryBarrierShared();
			barrier();
			data[gl_GlobalInvocationID.x] = big[(gl_LocalInvocationID.x + 1u) % 8u];
		}
		""";

	@Test
	void theSpillBufferBindsAtTheFixedSlot() {
		ProgramTranslation t = TranslateSupport.translateOk(compute(SPILL,
			List.of(new ProgramRequest.BufferBinding("Out", 1, 1)), List.of(), List.of(), List.of()));
		assertTrue(t.msl().contains("device uchar *qs_spill [[buffer(" + ProgramRequest.SHARED_SPILL_SLOT + ")]]"),
			t.msl());
		assertTrue(t.msl().contains("[[buffer(1)]]"), "the storage block keeps its binding:\n" + t.msl());
	}

	@Test
	void theSharedBarrierOrdersDeviceMemoryForSpilledArrays() {
		ProgramTranslation t = TranslateSupport.translateOk(compute(SPILL,
			List.of(new ProgramRequest.BufferBinding("Out", 1, 1)), List.of(), List.of(), List.of()));
		assertTrue(t.msl().contains("threadgroup_barrier(mem_flags::mem_device | mem_flags::mem_threadgroup)"),
			"memoryBarrierShared must order the device buffer a spilled array lives in:\n" + t.msl());
	}

	@Test
	void aStoreToAWriteOnlyImageHasNoFence() {
		String glsl = """
			#version 430
			layout(local_size_x = 1) in;
			layout(r32ui) writeonly uniform uimage2D img;
			void main() { imageStore(img, ivec2(0), uvec4(7u)); }
			""";
		ProgramTranslation t = TranslateSupport.translateOk(compute(glsl, List.of(), List.of(),
			List.of(new ProgramRequest.ImageBinding("img", 0)), List.of()));
		assertFalse(t.msl().contains(".fence()"), "access::write has no fence:\n" + t.msl());
		assertTrue(t.msl().contains(".write("), t.msl());
	}
}
