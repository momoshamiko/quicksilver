// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.gbuffers;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import com.mojang.renderpearl.api.vertex.VertexFormatElement;
import dev.mcms.glsl.msl.ProgramRequest;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.renderer.RenderPipelines;
import org.junit.jupiter.api.Test;

/**
 * The item pipelines in hand ({@code item_cutout} with kind {@code HAND}) fell back to the game's own pipeline with
 * "the vertex format has no engine layout" (log 2026-11-14, objects scene). The cause: the game's glint-special item
 * pipelines share the location ids of the item pipelines, and their format has a {@code UV3} element with no engine
 * semantic, so every plan built from them failed. A pack program draws a game pipeline only when every element of its
 * vertex format has a layout and vertex words. These assert that for every item-location pipeline the game lists.
 */
class ItemPipelineLayoutTest {

	@Test
	void everyItemLocationPipelineHasAnEngineLayout() {
		List<RenderPipeline> items = new ArrayList<>();
		for (RenderPipeline pipeline : RenderPipelines.requiredPipelines()) {
			if (GbufferMapping.locationOf(pipeline.getLocation().getPath()).startsWith("item_")) {
				items.add(pipeline);
			}
		}
		assertTrue(items.size() >= 3, "the game lists item pipelines: " + items.size());
		for (RenderPipeline pipeline : items) {
			assertLayout(pipeline);
		}
	}

	@Test
	void theItemCutoutGlintVariantHasAnEngineLayout() {
		assertLayout(RenderPipelines.ITEM_CUTOUT_GLINT);
	}

	@Test
	void theItemTranslucentGlintSpecialVariantHasAnEngineLayout() {
		assertLayout(RenderPipelines.ITEM_TRANSLUCENT_GLINT_SPECIAL);
	}

	private static void assertLayout(RenderPipeline pipeline) {
		VertexFormat format = pipeline.getVertexFormatBinding(0);
		StringBuilder elements = new StringBuilder();
		for (VertexFormatElement element : format.getElements()) {
			elements.append(element.name()).append(':').append(element.format().name()).append(' ');
		}
		ProgramRequest.VertexLayout layout = VertexLayoutRegistry.vertexLayoutFor(format);
		assertNotNull(layout, pipeline.getLocation() + " has no engine layout; elements: " + elements);
		assertNotNull(GbufferPlans.vertexWordsOf(format, layout),
			pipeline.getLocation() + " has an engine layout but no vertex words; elements: " + elements);
	}
}
