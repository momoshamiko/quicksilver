// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.gbuffers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import java.util.Optional;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

/**
 * The state of a game-variant copy (GbufferPlans.copyWithTargets): attachment 0 keeps the game's blend and write mask,
 * every other attachment is masked (no blend, no write), so a pass that binds the group's targets keeps their contents
 * there. The copy's colour target states are what Metal compiles, so the check is on the state the copy carries. And the
 * copy a later build asks for is the earlier build's object (GbufferPlans.sharedCopy), because the game's pipeline cache
 * keys its compiled pipelines by object.
 */
class GameVariantCopyTest {

	private static RenderPipeline game(ColorTargetState target) {
		return RenderPipeline.builder()
			.withLocation(Identifier.fromNamespaceAndPath("minecraft", "pipeline/copy_test_game"))
			.withVertexShader(Identifier.fromNamespaceAndPath("minecraft", "core/position_color"))
			.withFragmentShader(Identifier.fromNamespaceAndPath("minecraft", "core/position_color"))
			.withDepthStencilState(DepthStencilState.DEFAULT)
			.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
			.withColorTargetState(target)
			.build();
	}

	@Test
	void maskedAttachmentsAreNotWrittenAndTheGameBlendStaysOnAttachmentZero() {
		RenderPipeline copy = GbufferPlans.copyWithTargets(
			game(new ColorTargetState(Optional.of(BlendFunction.TRANSLUCENT), GpuFormat.RGBA8_UNORM, -1)),
			new GpuFormat[] {GpuFormat.RGBA8_UNORM, GpuFormat.RGBA16_FLOAT, GpuFormat.RGBA8_UNORM}, "/qs_test");
		ColorTargetState[] targets = copy.getColorTargetStates().toArray(new ColorTargetState[0]);
		assertEquals(3, targets.length, "one target per attachment of the group");
		assertTrue(targets[0].blendFunction().isPresent(), "attachment 0 keeps the game's blend");
		assertEquals(-1, targets[0].writeMask(), "attachment 0 keeps the game's write mask");
		assertEquals(GpuFormat.RGBA8_UNORM, targets[0].format());
		for (int k = 1; k < targets.length; k++) {
			assertFalse(targets[k].blendFunction().isPresent(), "attachment " + k + " has no blend");
			assertEquals(0, targets[k].writeMask(), "attachment " + k + " is masked: no write");
		}
		assertEquals(GpuFormat.RGBA16_FLOAT, targets[1].format(), "each masked attachment keeps its group's format");
	}

	@Test
	void aLaterBuildsEqualRequestGetsTheSameCopyObject() {
		RenderPipeline game = game(ColorTargetState.DEFAULT);
		GpuFormat[] formats = {GpuFormat.RGBA8_UNORM, GpuFormat.RGBA16_FLOAT};
		RenderPipeline first = GbufferPlans.sharedCopy("copy-test|shared", game, formats, "/qs_shared");
		RenderPipeline again = GbufferPlans.sharedCopy("copy-test|shared", game, formats.clone(), "/qs_shared");
		assertSame(first, again, "a new object would add one more compiled pipeline to the game's cache at each pack switch");
		RenderPipeline sameName = game(ColorTargetState.DEFAULT);
		assertNotSame(first, GbufferPlans.sharedCopy("copy-test|shared", sameName, formats, "/qs_shared"),
			"another game pipeline object under the same key gets its own copy");
	}
}
