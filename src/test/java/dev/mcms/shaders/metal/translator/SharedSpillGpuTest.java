// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.metal.translator;

import static dev.mcms.shaders.metal.translator.TranslateSupport.compute;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcms.glsl.msl.ProgramRequest;
import dev.mcms.glsl.msl.ProgramTranslation;
import dev.mcms.shaders.metal.HeadlessGpu;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * A shared array above Metal's 32 KB per group (plan.md E6): the translator moves it to a device buffer that holds every
 * group's copy, indexed by the group's linear index, bound at the fixed spill slot. Each group writes its own values,
 * synchronises, and reads a neighbour's; a group that shared another group's copy would read the wrong values, so the result
 * proves the groups do not overlap. The pack's storage block binds at its own index beside the spill slot, and its contents
 * must be the kernel's writes after the dispatch (finding 6: the old spill index collided with a block at binding 1).
 */
@Tag("gpu")
final class SharedSpillGpuTest {

	/** 8200 uints: 32800 bytes, above the 32768 threadgroup limit. */
	private static final String KERNEL = """
		#version 430
		layout(local_size_x = 8) in;
		layout(std430, binding = 0) buffer Out { uint data[]; };
		shared uint big[8200];
		void main() {
			uint lid = gl_LocalInvocationID.x;
			uint g = gl_WorkGroupID.x;
			big[lid] = g * 1000u + lid;
			big[8192u + lid] = g * 1000u + lid + 100u;
			barrier();
			data[gl_GlobalInvocationID.x] = big[(lid + 1u) % 8u] + big[8192u + (lid + 1u) % 8u];
		}
		""";

	/**
	 * The same spill on all three group axes, with the storage block at binding 1 (not 0): group (x, y, z) takes the linear
	 * index x + 2y + 6z, writes its copy of the arrays, and reads its neighbour's after the barrier.
	 */
	private static final String SPARSE_3D = """
		#version 430
		layout(local_size_x = 8, local_size_y = 1, local_size_z = 1) in;
		layout(std430, binding = 1) buffer Out { uint data[]; };
		shared uint big[8200];
		void main() {
			uint lid = gl_LocalInvocationID.x;
			uint g = gl_WorkGroupID.x + 2u * gl_WorkGroupID.y + 6u * gl_WorkGroupID.z;
			big[lid] = g * 1000u + lid;
			big[8192u + lid] = g * 1000u + lid + 100u;
			barrier();
			uint n = (lid + 1u) % 8u;
			data[g * 8u + lid] = big[n] + big[8192u + n];
		}
		""";

	@Test
	void sharedArraysAboveThe32KbLimitLiveInOneBufferPerDispatchWithNoOverlap() {
		ProgramTranslation t = TranslateSupport.translateOk(compute(KERNEL,
			List.of(new ProgramRequest.BufferBinding("Out", 0)), List.of(), List.of(), List.of()));
		assertTrue(t.msl().contains("device array<uint, 8200> *"), "the array moves to device memory:\n" + t.msl());
		assertTrue(t.msl().contains("[[buffer(" + ProgramRequest.SHARED_SPILL_SLOT + ")]]"),
			"the spill buffer binds at the fixed spill slot:\n" + t.msl());
		assertTrue(t.reflection().threadgroupBytes() > 32768, "the reflection reports the spilled bytes");
		int groups = 4;
		int bytesPerGroup = t.reflection().threadgroupBytes();
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			// Output 0 is Out (slot 0); output 1 is the spill buffer at the fixed slot.
			byte[][] out = gpu.runKernelAt(t.msl(), t.computeEntry(), new int[] {0, ProgramRequest.SHARED_SPILL_SLOT},
				new byte[0][], new int[] {groups * 8 * 4, groups * bytesPerGroup}, groups, 1, 1, 8, 1, 1);
			ByteBuffer bb = ByteBuffer.wrap(out[0]).order(ByteOrder.LITTLE_ENDIAN);
			for (int g = 0; g < groups; g++) {
				for (int lane = 0; lane < 8; lane++) {
					int n = (lane + 1) % 8;
					int expected = (g * 1000 + n) + (g * 1000 + n + 100);
					assertEquals(expected, bb.getInt((g * 8 + lane) * 4), "group " + g + " lane " + lane + "\n" + t.msl());
				}
			}
		}
	}

	@Test
	void aSparseStorageBindingSurvivesTheSpillOnEveryGroupAxis() {
		ProgramTranslation t = TranslateSupport.translateOk(compute(SPARSE_3D,
			List.of(new ProgramRequest.BufferBinding("Out", 1, 1)), List.of(), List.of(), List.of()));
		assertTrue(t.msl().contains("[[buffer(1)]]"), "the storage block binds at its binding:\n" + t.msl());
		assertTrue(t.msl().contains("[[buffer(" + ProgramRequest.SHARED_SPILL_SLOT + ")]]"), t.msl());
		int gx = 2, gy = 3, gz = 2, groups = gx * gy * gz;
		int bytesPerGroup = t.reflection().threadgroupBytes();
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			// Output 0 is the storage block at binding 1, read back after the dispatch; output 1 is the spill buffer at its own
			// slot. A spill bound at the block's index would replace the block and leave its contents untouched.
			byte[][] out = gpu.runKernelAt(t.msl(), t.computeEntry(), new int[] {1, ProgramRequest.SHARED_SPILL_SLOT},
				new byte[0][], new int[] {groups * 8 * 4, groups * bytesPerGroup}, gx, gy, gz, 8, 1, 1);
			ByteBuffer bb = ByteBuffer.wrap(out[0]).order(ByteOrder.LITTLE_ENDIAN);
			for (int g = 0; g < groups; g++) {
				for (int lane = 0; lane < 8; lane++) {
					int n = (lane + 1) % 8;
					int expected = (g * 1000 + n) + (g * 1000 + n + 100);
					assertEquals(expected, bb.getInt((g * 8 + lane) * 4), "group " + g + " lane " + lane + "\n" + t.msl());
				}
			}
		}
	}
}
