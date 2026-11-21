// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.gbuffers;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Every pipeline location the game defines is either mapped to a documented gbuffers row or listed here as a game-pipeline
 * variant (plan.md E4, "Game pipelines without a pack program"). An unmapped location that is in neither set would drop
 * its draws silently, so this test fails for it. The ids are the {@code pipeline/*} constants of
 * {@code net.minecraft.client.renderer.RenderPipelines} in minecraft-merged-deobf-26.3.jar (javap -constants, 2026-11-14);
 * Sodium's terrain pipelines use the game's terrain pipelines, with the {@code _multidraw} ids on the multidraw backend.
 */
class GameLocationCoverageTest {

	/** The pipeline ids the game defines (the locations the frontend lists and the engine maps). */
	static final List<String> GAME_PIPELINES = List.of(
		"pipeline/animate_sprite_blit", "pipeline/animate_sprite_interpolate", "pipeline/armor_cutout_no_cull",
		"pipeline/armor_cutout_no_cull_glint", "pipeline/armor_decal_cutout_no_cull", "pipeline/armor_translucent",
		"pipeline/banner_pattern", "pipeline/beacon_beam_opaque", "pipeline/beacon_beam_translucent", "pipeline/blit_depth",
		"pipeline/blit_depth_bounds", "pipeline/blit_depth_during_depth_bounds", "pipeline/block_screen_effect",
		"pipeline/breeze_wind", "pipeline/celestial", "pipeline/clouds", "pipeline/crosshair", "pipeline/crumbling",
		"pipeline/cutout_block", "pipeline/cutout_terrain", "pipeline/cutout_terrain_multidraw", "pipeline/debug_filled_box",
		"pipeline/debug_points", "pipeline/debug_quads", "pipeline/debug_triangle_fan", "pipeline/dragon_rays",
		"pipeline/end_crystal_beam", "pipeline/end_gateway", "pipeline/end_portal", "pipeline/end_sky", "pipeline/energy_swirl",
		"pipeline/entity_cutout", "pipeline/entity_cutout_cull", "pipeline/entity_cutout_dissolve",
		"pipeline/entity_cutout_z_offset", "pipeline/entity_outline_blit", "pipeline/entity_shadow", "pipeline/entity_solid",
		"pipeline/entity_solid_glint", "pipeline/entity_solid_offset_forward", "pipeline/entity_translucent",
		"pipeline/entity_translucent_cull", "pipeline/entity_translucent_emissive", "pipeline/eyes",
		"pipeline/fire_screen_effect", "pipeline/flat_clouds", "pipeline/glint", "pipeline/gui", "pipeline/gui_invert",
		"pipeline/gui_nausea_overlay", "pipeline/gui_opaque_textured_background", "pipeline/gui_text",
		"pipeline/gui_text_grayscale", "pipeline/gui_text_highlight", "pipeline/gui_textured",
		"pipeline/gui_textured_premultiplied_alpha", "pipeline/integrate_depth", "pipeline/item_cutout",
		"pipeline/item_translucent", "pipeline/item_translucent_glint", "pipeline/leash", "pipeline/lightmap",
		"pipeline/lightning", "pipeline/lines", "pipeline/lines_depth_bias", "pipeline/lines_translucent",
		"pipeline/lines_translucent_no_depth_write", "pipeline/mojang_logo", "pipeline/oit_composite",
		"pipeline/oit_depth_bounds_cull", "pipeline/oit_water_mask", "pipeline/opaque_particle", "pipeline/outline_cull",
		"pipeline/outline_no_cull", "pipeline/panorama", "pipeline/secondary_block_outline", "pipeline/sky",
		"pipeline/solid_block", "pipeline/solid_terrain", "pipeline/solid_terrain_multidraw", "pipeline/stars",
		"pipeline/sunrise_sunset", "pipeline/text", "pipeline/text_grayscale", "pipeline/text_grayscale_polygon_offset",
		"pipeline/text_grayscale_see_through", "pipeline/text_polygon_offset", "pipeline/text_see_through",
		"pipeline/tracy_blit", "pipeline/translucent_block", "pipeline/translucent_particle", "pipeline/translucent_terrain",
		"pipeline/translucent_terrain_multidraw", "pipeline/vignette", "pipeline/water_mask", "pipeline/weather",
		"pipeline/wireframe", "pipeline/wireframe_multidraw", "pipeline/world_border");

	/**
	 * The locations no documented row serves: a game-pipeline variant draws them (the game's own shader, the engine's
	 * projection). Each entry is a location the gbuffers table does not name.
	 */
	static final Set<String> GAME_VARIANT_ONLY = Set.of(
		"animate_sprite_blit", "animate_sprite_interpolate", "blit_depth", "blit_depth_bounds",
		"blit_depth_during_depth_bounds", "block_screen_effect", "breeze_wind", "crosshair", "debug_filled_box",
		"debug_points", "debug_quads", "debug_triangle_fan", "end_crystal_beam", "energy_swirl", "entity_outline_blit",
		"fire_screen_effect", "gui", "gui_invert", "gui_nausea_overlay", "gui_opaque_textured_background", "gui_text",
		"gui_text_grayscale", "gui_text_highlight", "gui_textured", "gui_textured_premultiplied_alpha", "integrate_depth",
		"lightmap", "mojang_logo", "oit_composite", "oit_depth_bounds_cull", "oit_water_mask", "outline_cull",
		"outline_no_cull", "panorama", "tracy_blit", "vignette", "water_mask", "wireframe");

	@Test
	void everyDefinedPipelineIsMappedOrListedAsAGameVariant() {
		for (String id : GAME_PIPELINES) {
			String location = GbufferMapping.locationOf(id);
			boolean mapped = GbufferMapping.table().containsKey(location);
			assertTrue(mapped || GAME_VARIANT_ONLY.contains(location),
				id + " (location " + location + ") is neither mapped to a gbuffers row nor a listed game variant");
		}
	}

	@Test
	void theGameVariantListHoldsOnlyUnmappedLocations() {
		for (String location : GAME_VARIANT_ONLY) {
			assertFalse(GbufferMapping.table().containsKey(location), location + " is mapped; drop it from the variant list");
		}
	}

	@Test
	void theMultidrawTerrainIdsMapToTheRowsOfTheirPlainPipelines() {
		assertTrue(GbufferMapping.table().containsKey(GbufferMapping.locationOf("pipeline/solid_terrain_multidraw")));
		assertTrue(GbufferMapping.table().containsKey(GbufferMapping.locationOf("pipeline/cutout_terrain_multidraw")));
		assertTrue(GbufferMapping.table().containsKey(GbufferMapping.locationOf("pipeline/translucent_terrain_multidraw")));
		assertTrue(GbufferMapping.locationOf("pipeline/solid_terrain_multidraw").equals("solid_terrain"));
	}
}
