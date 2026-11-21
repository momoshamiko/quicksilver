// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.compute;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.mcms.shaders.pack.StageFile;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The work groups of a kernel (engine-pack-format.md section 14; OptiFine shaders.txt): the fixed {@code workGroups}, the
 * render-relative {@code workGroupsRender}, the documented default {@code vec2(1.0, 1.0)}, and the constant evaluator's
 * suffixes and arithmetic (plan.md E6, finding 5).
 */
final class ComputeKernelWorkGroupsTest {

	private static ComputeKernel.WorkGroups rule(String constants) {
		return ComputeKernel.WorkGroups.of(new StageFile('c', "composite.csh", "#version 430\n" + constants
			+ "\nlayout(local_size_x = 8, local_size_y = 8) in;\nvoid main() {}\n"));
	}

	private static ComputeKernel kernel(ComputeKernel.WorkGroups groups) {
		return new ComputeKernel("k", 0L, new int[] {8, 8, 1}, groups, List.of(), List.of(), List.of(), 0);
	}

	private static int[] dispatch(ComputeKernel kernel, int width, int height) {
		int[] out = new int[3];
		kernel.groupsInto(width, height, out);
		return out;
	}

	@Test
	void aKernelWithNoConstantDispatchesTheDefaultRenderRelativeCount() {
		ComputeKernel.WorkGroups rule = rule("");
		assertNull(rule.fixed());
		assertArrayEquals(new double[] {1.0, 1.0}, rule.relative());
		// One group per local size of the render: 1920 / 8 by 1080 / 8, rounded up.
		assertArrayEquals(new int[] {240, 135, 1}, dispatch(kernel(rule), 1920, 1080));
	}

	@Test
	void theFSuffixAndHalfFactorsAreRead() {
		ComputeKernel.WorkGroups rule = rule("const vec2 workGroupsRender = vec2(0.5f, 0.5f);");
		assertArrayEquals(new double[] {0.5, 0.5}, rule.relative());
		// 960 / 8 = 120; 540 / 8 = 67.5, rounded up to 68.
		assertArrayEquals(new int[] {120, 68, 1}, dispatch(kernel(rule), 1920, 1080));
	}

	@Test
	void aFixedCountIsReadAndDoesNotScaleWithTheRender() {
		ComputeKernel.WorkGroups rule = rule("const ivec3 workGroups = ivec3(16, 16, 1);");
		assertNull(rule.relative());
		assertArrayEquals(new int[] {16, 16, 1}, rule.fixed());
		assertArrayEquals(new int[] {16, 16, 1}, dispatch(kernel(rule), 3840, 2160));
	}

	@Test
	void constructorArithmeticIsEvaluated() {
		ComputeKernel.WorkGroups rule = rule("const ivec3 workGroups = ivec3(4 * 2, 2 + 0, 1);");
		assertArrayEquals(new int[] {8, 2, 1}, rule.fixed());
	}

	@Test
	void aFixedCountWinsWhenBothConstantsAreNamed() {
		ComputeKernel.WorkGroups rule = rule("const ivec3 workGroups = ivec3(3, 4, 1);\n"
			+ "const vec2 workGroupsRender = vec2(0.5, 0.5);");
		assertArrayEquals(new int[] {3, 4, 1}, rule.fixed());
		assertNull(rule.relative());
	}

	@Test
	void aRelativeAxisRoundsUpAndNeverDispatchesZero() {
		ComputeKernel tiny = kernel(rule("const vec2 workGroupsRender = vec2(0.01, 0.01);"));
		assertArrayEquals(new int[] {1, 1, 1}, dispatch(tiny, 1, 1));
	}
}
