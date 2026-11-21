// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.gbuffers;

import static org.junit.jupiter.api.Assertions.assertNull;

import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * After the last split of the main pass, the engine restores the pass with the last opaque pipeline under the translucent
 * phase (ReviewE4Frame's contract risk). That pipeline draws in no translucent phase: the delegate answers null, and the
 * answer is not a dropped draw (no variant is built or logged for it).
 */
class TranslucentRestoreSelectTest {

	private static final String SOLID_TERRAIN = "minecraft:pipeline/solid_terrain";

	@Test
	void theOpaquePipelineSetUnderTheTranslucentPhaseIsNullAndNothingIsBuilt() {
		// A plan whose location draws in the opaque phase only, with no variant built for any (kind, phase).
		GbufferPlans.Plan solid = new GbufferPlans.Plan() {
			@Override
			public GbufferPlans.Variant variant(PassKind kind, GbufferPhase phase, GbufferMapping.Domain domain) {
				return null;
			}

			@Override
			public String location() {
				return "solid_terrain";
			}

			@Override
			public String reason(PassKind kind, GbufferPhase phase, GbufferMapping.Domain domain) {
				return "";
			}
		};
		GbufferDelegate delegate = new GbufferDelegate(GbufferPlans.forTest(Map.of(SOLID_TERRAIN, solid)), null);
		delegate.phase(GbufferPhase.TRANSLUCENT);
		assertNull(delegate.select(0L, SOLID_TERRAIN, null, null, List.<BindGroupLayout.UniformDescription>of()),
			"the restore binds nothing and returns null");
	}

	@Test
	void aGameLocationWithoutAPlanIsNullToo() {
		GbufferDelegate delegate = new GbufferDelegate(GbufferPlans.forTest(Map.of()), null);
		assertNull(delegate.select(0L, "minecraft:pipeline/gui", null, null, List.<BindGroupLayout.UniformDescription>of()));
	}
}
