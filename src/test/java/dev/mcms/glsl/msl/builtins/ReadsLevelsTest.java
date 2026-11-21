// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.msl.builtins;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Which texture lookups can read a mip level other than 0 (E9): the engine refills a target's mip chain only before the
 * programs whose lookups can, so a lookup wrongly answered "level 0" would read a stale chain.
 */
final class ReadsLevelsTest {

	private static final BuiltinTable TABLE = BuiltinTable.create();
	/** A binding whose implicit-LOD lookups take a level-0 sampler (a program that does not declare the target's mips). */
	private static final BuiltinCall.Resource LEVEL0 = new BuiltinCall.Resource("tex", "smp", "smp0");
	/** A binding whose implicit-LOD lookups take the mip-filtered sampler (the program declares the target's mips). */
	private static final BuiltinCall.Resource MIPPED = new BuiltinCall.Resource("tex", "smp");

	private static BuiltinCall call(String name, BuiltinCall.Resource res, List<String> types, List<String> args) {
		return new BuiltinCall(name, types, "vec4", args, res);
	}

	@Test
	void explicitLevelsGradientsAndFetchesReadLevelsUnlessTheLevelIsALiteralZero() {
		List<String> lodTypes = List.of("sampler2D", "vec2", "float");
		assertTrue(TABLE.readsLevels(call("texture2DLod", LEVEL0, lodTypes, List.of("tex", "uv", "2.0f"))));
		assertTrue(TABLE.readsLevels(call("textureLod", LEVEL0, lodTypes, List.of("tex", "uv", "l_lod_3"))));
		assertFalse(TABLE.readsLevels(call("textureLod", LEVEL0, lodTypes, List.of("tex", "uv", "0.0f"))));
		assertTrue(TABLE.readsLevels(call("textureGrad", LEVEL0, List.of("sampler2D", "vec2", "vec2", "vec2"),
			List.of("tex", "uv", "dx", "dy"))));
		List<String> fetchTypes = List.of("sampler2D", "ivec2", "int");
		assertFalse(TABLE.readsLevels(call("texelFetch", LEVEL0, fetchTypes, List.of("tex", "p", "0"))));
		assertTrue(TABLE.readsLevels(call("texelFetch", LEVEL0, fetchTypes, List.of("tex", "p", "l_level_1"))));
	}

	@Test
	void anImplicitLookupReadsLevelsOnlyThroughTheMipFilteredSampler() {
		List<String> types = List.of("sampler2D", "vec2");
		assertFalse(TABLE.readsLevels(call("texture2D", LEVEL0, types, List.of("tex", "uv"))));
		assertTrue(TABLE.readsLevels(call("texture2D", MIPPED, types, List.of("tex", "uv"))));
		assertTrue(TABLE.readsLevels(call("texture", MIPPED, types, List.of("tex", "uv"))));
	}

	@Test
	void gathersSizesAndCountsReadNoLevelAndOtherCallsAreNoLookups() {
		assertFalse(TABLE.readsLevels(call("textureGather", MIPPED, List.of("sampler2D", "vec2"), List.of("tex", "uv"))));
		assertFalse(TABLE.readsLevels(call("textureSize", MIPPED, List.of("sampler2D", "int"), List.of("tex", "1"))));
		assertFalse(TABLE.readsLevels(call("textureQueryLevels", MIPPED, List.of("sampler2D"), List.of("tex"))));
		assertFalse(TABLE.readsLevels(BuiltinCall.of("sin", "float", "float", "x")));
	}
}
