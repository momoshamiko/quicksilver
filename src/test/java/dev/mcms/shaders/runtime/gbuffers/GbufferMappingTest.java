// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.gbuffers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The mapping table (plan.md E4, "Pipeline mapping"): every documented gbuffers program is reachable through some
 * location's chain, every chain is the documented fallback order (engine-pack-format.md section 2: the fallback is
 * used when the program file is absent, so a chain must walk it without gaps), every render stage names a documented
 * macro, and the hand kinds override the location. A wrong chain serves the wrong program silently, so the shapes are
 * checked exactly.
 */
class GbufferMappingTest {

	/** The documented gbuffers programs (engine-pack-format.md section 2's list, Iris extras included). */
	private static final Set<String> DOCUMENTED = Set.of("basic", "line", "textured", "textured_lit", "skybasic",
		"skytextured", "clouds", "terrain", "terrain_solid", "terrain_cutout", "damagedblock", "block",
		"block_translucent", "beaconbeam", "item", "entities", "entities_glowing", "entities_translucent",
		"armor_glint", "spidereyes", "hand", "weather", "water", "hand_water", "particles", "particles_translucent",
		"lightning");

	@Test
	void everyDocumentedProgramIsReachableThroughALocation() {
		Set<String> reachable = new HashSet<>();
		GbufferMapping.table().values().forEach(row -> reachable.addAll(row.chain()));
		reachable.addAll(GbufferMapping.row(PassKind.HAND, GbufferPhase.OPAQUE, "entity_solid").orElseThrow().chain());
		reachable.addAll(GbufferMapping.row(PassKind.HAND, GbufferPhase.TRANSLUCENT, "entity_solid").orElseThrow().chain());
		for (String program : DOCUMENTED) {
			// item: OptiFine's table says "<not used>" (engine-pack-format.md section 2, optifine.readthedocs.io
			// Programs) and the Iris gbuffers reference lists no item program; item entities and item frames are
			// entities. entities_glowing: OptiFine only ("renders entities that have the spectral effect"), a per-entity
			// state the location table cannot route; Iris does not recognize it. Both stay unmapped (see the test below).
			if (program.equals("entities_glowing") || program.equals("item")) {
				continue;
			}
			assertTrue(reachable.contains(program), program + " is not reachable through any chain");
		}
	}

	@Test
	void terrainChainsWalkTheDocumentedFallbackOrder() {
		// gbuffers_terrain_solid -> terrain -> textured_lit -> textured -> basic (the Iris names first, plan.md E4).
		assertEquals(List.of("terrain_solid", "terrain", "textured_lit", "textured", "basic"),
			GbufferMapping.row(PassKind.GBUFFERS, GbufferPhase.OPAQUE, "solid_terrain").orElseThrow().chain());
		assertEquals(List.of("water", "terrain", "textured_lit", "textured", "basic"),
			GbufferMapping.row(PassKind.GBUFFERS, GbufferPhase.TRANSLUCENT, "translucent_terrain").orElseThrow().chain());
	}

	@Test
	void theHandOverridesEveryLocationAndTheTranslucentHandIsHandWater() {
		assertEquals(List.of("hand", "textured_lit", "textured", "basic"),
			GbufferMapping.row(PassKind.HAND, GbufferPhase.OPAQUE, "entity_translucent").orElseThrow().chain());
		assertEquals("HAND_SOLID",
			GbufferMapping.row(PassKind.HAND, GbufferPhase.OPAQUE, "entity_solid").orElseThrow().renderStage());
		assertEquals(List.of("hand_water", "hand", "textured_lit", "textured", "basic"),
			GbufferMapping.row(PassKind.HAND, GbufferPhase.TRANSLUCENT, "entity_solid").orElseThrow().chain());
		assertEquals("HAND_TRANSLUCENT",
			GbufferMapping.row(PassKind.HAND, GbufferPhase.TRANSLUCENT, "entity_solid").orElseThrow().renderStage());
	}

	@Test
	void everyRenderStageNamesADocumentedMacro() {
		GbufferMapping.table().values().forEach(row -> assertTrue(GbufferMapping.renderStage(row.renderStage()) >= 0,
			row.renderStage() + " is not a documented render stage"));
	}

	@Test
	void theShadowKindMapsTerrainAndUnknownLocationsHaveNoProgram() {
		assertEquals(List.of("shadow_solid", "shadow"),
			GbufferMapping.row(PassKind.SHADOW, GbufferPhase.OPAQUE, "solid_terrain").orElseThrow().chain());
		assertTrue(GbufferMapping.row(PassKind.GBUFFERS, GbufferPhase.OPAQUE, "fire_screen_effect").isEmpty());
		assertTrue(GbufferMapping.row(PassKind.GBUFFERS, GbufferPhase.TRANSLUCENT, "debug_crosshair").isEmpty());
	}

	@Test
	void anUnmappedLocationDrawsInBothPhases() {
		// gui_textured is a screen pipeline the table does not name (a game variant by design): its variant must exist in
		// both phases, or a draw in the other phase drops ("no pipeline is built for ...", log 2026-11-14-e4-preview2).
		GbufferPhase[] phases = GbufferMapping.phases(PassKind.GBUFFERS, "gui_textured");
		assertEquals(2, phases.length, () -> "gui_textured draws in " + List.of(phases));
		assertEquals(GbufferPhase.OPAQUE, phases[0]);
		assertEquals(GbufferPhase.TRANSLUCENT, phases[1]);
	}

	@Test
	void itemEntitiesAndItemFramesDrawThroughTheEntityChains() {
		// Item entities and item frames are entities (Iris reference: gbuffers_entities covers entities); the item
		// pipeline that was a game variant (log 2026-11-14, objects scene) now takes the entity chain.
		assertEquals(List.of("entities", "textured_lit", "textured", "basic"),
			GbufferMapping.row(PassKind.GBUFFERS, GbufferPhase.OPAQUE, "item_cutout").orElseThrow().chain());
		assertEquals("ENTITIES",
			GbufferMapping.row(PassKind.GBUFFERS, GbufferPhase.OPAQUE, "item_cutout").orElseThrow().renderStage());
		assertEquals(List.of("entities_translucent", "entities", "textured_lit", "textured", "basic"),
			GbufferMapping.row(PassKind.GBUFFERS, GbufferPhase.TRANSLUCENT, "item_translucent").orElseThrow().chain());
	}

	@Test
	void sodiumsCutoutTerrainDrawsThroughTerrainCutoutWithTheSolidStage() {
		// Iris 1.11.7 (RIL-570, results/2026-11-19-ril570-cutout): gbuffers_terrain_cutout draws the cutout pass, and the pass
		// reports renderStage TERRAIN_SOLID.
		GbufferMapping.Row cutout = GbufferMapping.row(PassKind.GBUFFERS, GbufferPhase.OPAQUE, "cutout_terrain").orElseThrow();
		assertEquals(List.of("terrain_cutout", "terrain", "textured_lit", "textured", "basic"), cutout.chain());
		assertEquals("TERRAIN_SOLID", cutout.renderStage());
	}

	@Test
	void everyFoilTakesTheArmorGlintProgramWithItsDocumentedFallback() {
		// gbuffers_armor_glint: the enchantment effect on items and armor, falling back to gbuffers_textured
		// (engine-pack-format.md section 2; Iris reference table).
		List<String> glint = List.of("armor_glint", "textured", "basic");
		for (String location : List.of("glint", "glint_special", "entity_solid_glint", "armor_cutout_no_cull_glint",
			"item_translucent_glint")) {
			assertEquals(glint, GbufferMapping.table().get(location).chain(), location + " must take gbuffers_armor_glint");
		}
	}

	@Test
	void armorDrawsThroughTheEntityProgramsAndABannerThroughTheBlockTranslucentProgram() {
		assertEquals(List.of("entities", "textured_lit", "textured", "basic"),
			GbufferMapping.row(PassKind.GBUFFERS, GbufferPhase.OPAQUE, "armor_cutout_no_cull").orElseThrow().chain());
		assertEquals(List.of("entities_translucent", "entities", "textured_lit", "textured", "basic"),
			GbufferMapping.row(PassKind.GBUFFERS, GbufferPhase.TRANSLUCENT, "armor_translucent").orElseThrow().chain());
		// A banner is a block entity (its pattern layers blend), so it draws in the block-entity domain:
		// gbuffers_block_translucent, falling back to gbuffers_block (Iris reference).
		assertEquals(List.of("block_translucent", "block", "terrain", "textured_lit", "textured", "basic"),
			GbufferMapping.row(PassKind.GBUFFERS, GbufferPhase.OPAQUE, "banner_pattern", GbufferMapping.Domain.BLOCK_ENTITY,
				true).orElseThrow().chain());
		assertEquals("BLOCK_ENTITIES",
			GbufferMapping.row(PassKind.GBUFFERS, GbufferPhase.OPAQUE, "banner_pattern", GbufferMapping.Domain.BLOCK_ENTITY,
				true).orElseThrow().renderStage());
		assertEquals(GbufferPhase.OPAQUE, GbufferMapping.phases(PassKind.GBUFFERS, "banner_pattern")[0]);
	}

	@Test
	void spiderEyesTakeTheSpiderEyesProgramWithItsFallback() {
		// The 26.3 pipeline is minecraft:pipeline/eyes (spider, enderman and dragon eyes); gbuffers_spidereyes falls
		// back to gbuffers_textured (engine-pack-format.md section 2).
		assertEquals(List.of("spidereyes", "textured", "basic"),
			GbufferMapping.row(PassKind.GBUFFERS, GbufferPhase.OPAQUE, "eyes").orElseThrow().chain());
		assertFalse(GbufferMapping.table().containsKey("spidereyes"), "the 26.3 pipeline id is eyes");
	}

	@Test
	void theItemProgramAndTheGlowingProgramAreNotInAnyChain() {
		// Decision (E4, 2026-11-14): gbuffers_item has no documented geometry (OptiFine "<not used>"); gbuffers_entities_glowing
		// needs a per-entity spectral flag that the location table cannot route. Neither is a fallback target either.
		GbufferMapping.table().values().forEach(row -> {
			assertFalse(row.chain().contains("item"), row.chain() + " names gbuffers_item");
			assertFalse(row.chain().contains("entities_glowing"), row.chain() + " names gbuffers_entities_glowing");
		});
	}


	@Test
	void everyChainEndsAtABasicFamilyProgramTheFallbackCanAlwaysServe() {
		GbufferMapping.table().forEach((location, row) -> {
			if (GbufferMapping.isDistant(location)) {
				return;  // Distant Horizons' chains name dh_ programs (GbufferDistantMappingTest)
			}
			String last = row.chain().get(row.chain().size() - 1);
			assertTrue(last.equals("basic") || last.equals("entities") || last.equals("block") || last.equals("terrain")
				|| last.equals("textured_lit"), row.chain() + " ends at " + last);
		});
	}

	@Test
	void everyChainHasNoRepeatAndNoForeignProgram() {
		GbufferMapping.table().forEach((location, row) -> {
			if (GbufferMapping.isDistant(location)) {
				return;
			}
			assertEquals(Set.copyOf(row.chain()).size(), row.chain().size(), row.chain() + " repeats a program");
			for (String program : row.chain()) {
				assertTrue(DOCUMENTED.contains(program), program + " is not a documented program");
			}
		});
	}

	@Test
	void theLocationIdIsThePathWithoutThePipelinePrefix() {
		assertEquals("solid_terrain", GbufferMapping.locationOf("pipeline/solid_terrain"));
		assertEquals("core/terrain", GbufferMapping.locationOf("core/terrain"));
		assertFalse(GbufferMapping.table().containsKey("pipeline/sky"));
	}

	// ----- the routing rules (plan.md E4, the pipeline-mapping bullet's entity and block-entity rules) ----------

	@Test
	void blendingEntityPipelinesTakeTheEntitiesTranslucentChain() {
		// Iris reference (gbuffers additional programs): "gbuffers_entities_translucent | translucent entities |
		// gbuffers_entities"; the blending entity pipelines are the translucent ones. Blend facts: RenderPipelines
		// javap of minecraft-merged-deobf-26.3.jar (2026-11-14): entity_shadow, text, text_grayscale,
		// text_polygon_offset, text_grayscale_polygon_offset and text_see_through build a ColorTargetState with
		// BlendFunction.TRANSLUCENT.
		List<String> translucent = List.of("entities_translucent", "entities", "textured_lit", "textured", "basic");
		for (String location : List.of("entity_shadow", "text", "text_grayscale", "text_polygon_offset",
			"text_grayscale_polygon_offset", "text_see_through", "entity_cutout_no_cull")) {
			assertEquals(translucent,
				GbufferMapping.row(PassKind.GBUFFERS, GbufferPhase.TRANSLUCENT, location, GbufferMapping.Domain.ENTITY,
					true).orElseThrow().chain(),
				location + " blends, so it draws through gbuffers_entities_translucent");
			assertEquals("ENTITIES",
				GbufferMapping.row(PassKind.GBUFFERS, GbufferPhase.TRANSLUCENT, location, GbufferMapping.Domain.ENTITY,
					true).orElseThrow().renderStage());
		}
	}

	@Test
	void nonBlendingEntityPipelinesKeepTheEntitiesChain() {
		// The cutout and solid entity pipelines keep alpha testing, not blending (RenderPipelines javap:
		// ColorTargetState.DEFAULT), and text_grayscale_see_through is the opaque see-through variant (DEFAULT too),
		// so none of them is a translucent entity.
		List<String> entities = List.of("entities", "textured_lit", "textured", "basic");
		for (String location : List.of("entity_solid", "entity_cutout", "entity_cutout_cull", "entity_cutout_z_offset",
			"entity_cutout_dissolve", "entity_solid_offset_forward", "armor_cutout_no_cull",
			"armor_decal_cutout_no_cull", "item_cutout", "text_grayscale_see_through")) {
			assertEquals(entities,
				GbufferMapping.row(PassKind.GBUFFERS, GbufferPhase.OPAQUE, location, GbufferMapping.Domain.ENTITY,
					false).orElseThrow().chain(),
				location + " does not blend, so it keeps gbuffers_entities");
		}
	}

	@Test
	void blockEntitySubmitsTakeTheBlockProgramsWhateverEntityPipelineCarriesThem() {
		// Iris reference: "gbuffers_block | block entities, sign text | gbuffers_terrain" and
		// "gbuffers_block_translucent | translucent block entities | gbuffers_block". The chest, shulker box, sign,
		// bell, decorated pot, lectern and enchanting-table book are block entities whose renderers submit through the
		// entity and text pipelines, so the block-entity domain routes those rows to the block programs; a sign's text
		// stays gbuffers_block although it blends (the table lists it under gbuffers_block, not block_translucent).
		List<String> block = List.of("block", "terrain", "textured_lit", "textured", "basic");
		for (String location : List.of("entity_solid", "entity_solid_offset_forward", "entity_cutout",
			"entity_cutout_cull", "entity_cutout_no_cull", "entity_cutout_z_offset", "entity_cutout_dissolve",
			"armor_cutout_no_cull", "armor_decal_cutout_no_cull", "item_cutout",
			"text", "text_see_through", "text_grayscale", "text_polygon_offset", "text_grayscale_polygon_offset",
			"text_grayscale_see_through")) {
			assertEquals(block,
				GbufferMapping.row(PassKind.GBUFFERS, GbufferPhase.OPAQUE, location, GbufferMapping.Domain.BLOCK_ENTITY,
					false).orElseThrow().chain(),
				location + " submitted by a block-entity renderer draws through gbuffers_block");
			assertEquals(block,
				GbufferMapping.row(PassKind.GBUFFERS, GbufferPhase.OPAQUE, location, GbufferMapping.Domain.BLOCK_ENTITY,
					true).orElseThrow().chain(),
				location + " keeps gbuffers_block in the block-entity domain although it blends (sign text)");
			assertEquals("BLOCK_ENTITIES",
				GbufferMapping.row(PassKind.GBUFFERS, GbufferPhase.OPAQUE, location, GbufferMapping.Domain.BLOCK_ENTITY,
					true).orElseThrow().renderStage());
		}
		// The translucent entity family (skulls, items on campfires, spawners and vaults) blends, and its blending draws take
		// the block-entity translucent program (the Iris reference's gbuffers_block_translucent). Without blending they keep
		// gbuffers_block; the list is the entities_translucent rows of GbufferMapping.
		List<String> blockTranslucent = List.of("block_translucent", "block", "terrain", "textured_lit", "textured", "basic");
		for (String location : List.of("entity_translucent", "entity_translucent_cull", "entity_translucent_emissive",
			"armor_translucent", "item_translucent")) {
			assertEquals(block,
				GbufferMapping.row(PassKind.GBUFFERS, GbufferPhase.OPAQUE, location, GbufferMapping.Domain.BLOCK_ENTITY,
					false).orElseThrow().chain(),
				location + " without blending keeps gbuffers_block in the block-entity domain");
			assertEquals(blockTranslucent,
				GbufferMapping.row(PassKind.GBUFFERS, GbufferPhase.OPAQUE, location, GbufferMapping.Domain.BLOCK_ENTITY,
					true).orElseThrow().chain(),
				location + " blends, so it draws through gbuffers_block_translucent in the block-entity domain");
			assertEquals("BLOCK_ENTITIES",
				GbufferMapping.row(PassKind.GBUFFERS, GbufferPhase.OPAQUE, location, GbufferMapping.Domain.BLOCK_ENTITY,
					true).orElseThrow().renderStage());
		}
	}

	@Test
	void blendingBlockEntitiesTakeTheBlockTranslucentChain() {
		// "gbuffers_block_translucent | translucent block entities | gbuffers_block": the banner's pattern layers
		// blend (RenderPipelines javap: BlendFunction.TRANSLUCENT), and translucent_block names them already.
		List<String> blockTranslucent = List.of("block_translucent", "block", "terrain", "textured_lit", "textured",
			"basic");
		assertEquals(blockTranslucent,
			GbufferMapping.row(PassKind.GBUFFERS, GbufferPhase.OPAQUE, "banner_pattern", GbufferMapping.Domain.BLOCK_ENTITY,
				true).orElseThrow().chain());
		assertEquals("BLOCK_ENTITIES",
			GbufferMapping.row(PassKind.GBUFFERS, GbufferPhase.OPAQUE, "banner_pattern", GbufferMapping.Domain.BLOCK_ENTITY,
				true).orElseThrow().renderStage());
		assertEquals(blockTranslucent,
			GbufferMapping.row(PassKind.GBUFFERS, GbufferPhase.TRANSLUCENT, "translucent_block",
				GbufferMapping.Domain.BLOCK_ENTITY, true).orElseThrow().chain());
		// Without blending the block rows keep gbuffers_block (solid_block, cutout_block, the end portals).
		assertEquals(List.of("block", "terrain", "textured_lit", "textured", "basic"),
			GbufferMapping.row(PassKind.GBUFFERS, GbufferPhase.OPAQUE, "solid_block", GbufferMapping.Domain.BLOCK_ENTITY,
				false).orElseThrow().chain());
	}

	@Test
	void documentedSpecificProgramsSurviveBothDomains() {
		// gbuffers_beaconbeam is documented for "beacon beam" without a translucent split (Iris reference), so both
		// beam pipelines keep it in the block-entity domain; the glint keeps gbuffers_armor_glint and leash keeps
		// gbuffers_basic (both documented), whatever domain draws them.
		for (GbufferMapping.Domain domain : GbufferMapping.Domain.values()) {
			for (boolean blends : new boolean[] {false, true}) {
				assertEquals(List.of("beaconbeam", "textured", "basic"),
					GbufferMapping.row(PassKind.GBUFFERS, GbufferPhase.OPAQUE, "beacon_beam_opaque", domain, blends)
						.orElseThrow().chain(), domain + " " + blends);
				assertEquals(List.of("beaconbeam", "textured", "basic"),
					GbufferMapping.row(PassKind.GBUFFERS, GbufferPhase.TRANSLUCENT, "beacon_beam_translucent", domain,
						blends).orElseThrow().chain(), domain + " " + blends);
				assertEquals(List.of("armor_glint", "textured", "basic"),
					GbufferMapping.row(PassKind.GBUFFERS, GbufferPhase.OPAQUE, "glint", domain, blends).orElseThrow()
						.chain(), domain + " " + blends);
				assertEquals(List.of("basic"),
					GbufferMapping.row(PassKind.GBUFFERS, GbufferPhase.OPAQUE, "leash", domain, blends).orElseThrow()
						.chain(), domain + " " + blends);
			}
		}
	}

	@Test
	void theHandIgnoresTheDomainAndTheShadowKindFollowsIt() {
		// The hand rows override every location (plan.md E4). The shadow rows follow the domain: entities take
		// shadow_entities, block entities shadow_block (plan.md E5).
		for (GbufferMapping.Domain domain : GbufferMapping.Domain.values()) {
			assertEquals("HAND_SOLID",
				GbufferMapping.row(PassKind.HAND, GbufferPhase.OPAQUE, "entity_cutout", domain, true).orElseThrow()
					.renderStage(), domain.name());
		}
		assertEquals(List.of("shadow_entities", "shadow"),
			GbufferMapping.row(PassKind.SHADOW, GbufferPhase.OPAQUE, "entity_cutout", GbufferMapping.Domain.ENTITY,
				true).orElseThrow().chain());
		assertEquals(List.of("shadow_block", "shadow"),
			GbufferMapping.row(PassKind.SHADOW, GbufferPhase.OPAQUE, "entity_cutout", GbufferMapping.Domain.BLOCK_ENTITY,
				true).orElseThrow().chain());
	}
}
