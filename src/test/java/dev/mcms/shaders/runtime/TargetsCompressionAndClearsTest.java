// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcms.shaders.pack.ShaderProperties;
import dev.mcms.shaders.pack.TargetUnion;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TargetsCompressionAndClearsTest {

	private record Created(int format, int width, int height, int mips, boolean shaderWrite) {
	}

	@Test
	void onlyColorImageTargetsRequestShaderWriteAndDepthNeverDoes() {
		List<Created> created = new ArrayList<>();
		long[] nextHandle = {100L};
		Targets.Textures fake = new Targets.Textures() {
			@Override
			public long create(int format, int width, int height, int mips) {
				return create(format, width, height, mips, false);
			}

			@Override
			public long create(int format, int width, int height, int mips, boolean shaderWrite) {
				created.add(new Created(format, width, height, mips, shaderWrite));
				return nextHandle[0]++;
			}
		};
		TargetUnion union = new TargetUnion(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), List.of());
		Targets targets = new Targets(union, Map.of(), Set.of(0, 1, 2, 3, 7), Set.of(7), true, true, 1280, 720, fake, null, CustomImages.NONE);
		targets.wantDistantDepth(true);
		targets.wantUpscale(70);
		assertTrue(targets.create());

		assertTrue(targets.made(0));
		assertTrue(targets.made(3));
		assertFalse(targets.made(4), "unreferenced gap colortex4 must not allocate textures");
		assertFalse(targets.made(6));
		assertTrue(targets.made(7));
		assertFalse(targets.imageWritten(0));
		assertTrue(targets.imageWritten(7));

		// Only the two ping-pong textures of colortex7 should have requested shaderWrite=true.
		long shaderWriteCount = created.stream().filter(Created::shaderWrite).count();
		assertEquals(2L, shaderWriteCount, "only colortex7 ping-pong pair should request ShaderWrite");
		assertTrue(created.stream().filter(c -> c.format() == TargetFormats.DEPTH32_FLOAT).noneMatch(Created::shaderWrite),
			"depth targets must never request ShaderWrite");
	}

	@Test
	void sizedTargetsAreTrackedSeparately() {
		TargetUnion union = new TargetUnion(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), List.of());
		Map<String, ShaderProperties.BufferSize> sizes = Map.of(
			"colortex5", new ShaderProperties.BufferSize(0.5, 0.5, true));
		long[] nextHandle = {1L};
		Targets.Textures fake = (f, w, h, m) -> nextHandle[0]++;
		Targets targets = new Targets(union, sizes, Set.of(0, 5), Set.of(), false, false, 1000, 500, fake, null, CustomImages.NONE);
		assertTrue(targets.create());
		assertEquals(1000, targets.width(0));
		assertEquals(500, targets.height(0));
		assertEquals(500, targets.width(5));
		assertEquals(250, targets.height(5));
		assertFalse(targets.made(4));
		assertTrue(targets.made(5));
	}
}
