// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.mcms.glsl.msl.ProgramRequest.Filter;
import dev.mcms.glsl.msl.ProgramRequest.SamplerState;
import dev.mcms.glsl.msl.ProgramRequest.Wrap;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The sampler state of the engine's own targets (plan.md E7, measured with the mcms-filter pack against Iris): the depth
 * targets filter nearest with no mip filter, whatever a program declares about mipmaps; the colour targets filter
 * linearly and between levels when their chain is enabled; noisetex repeats.
 */
class TargetSamplerStateTest {

	@Test
	void depthTargetsSampleNearestAndColourTargetsLinearly() {
		for (String depth : List.of("depthtex0", "depthtex1", "depthtex2", "dhDepthTex0", "dhDepthTex1", "dhDepthTex")) {
			SamplerState state = ProgramRequests.targetState(depth, "sampler2D", true, false);
			assertEquals(Filter.NEAREST, state.filter(), depth);
			assertNull(state.mip(), depth + ": a depth target has no mip filter");
		}
		SamplerState colour = ProgramRequests.targetState("colortex3", "sampler2D", true, false);
		assertEquals(Filter.LINEAR, colour.filter());
		assertEquals(Filter.LINEAR, colour.mip(), "a mipmapped colour target filters between levels");
		assertNull(ProgramRequests.targetState("colortex3", "sampler2D", false, false).mip());
		assertEquals(Wrap.REPEAT, ProgramRequests.targetState("noisetex", "sampler2D", false, false).wrap());
		assertEquals(Wrap.CLAMP_TO_EDGE, ProgramRequests.targetState("depthtex0", "sampler2D", false, false).wrap());
	}
}
