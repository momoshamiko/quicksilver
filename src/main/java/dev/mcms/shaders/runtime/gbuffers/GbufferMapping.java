// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.gbuffers;

import dev.mcms.shaders.pack.StandardMacros;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The documented mapping from a game pipeline to the pack program that draws its geometry (plan.md E4, the pipeline
 * mapping bullet; engine-pack-format.md section 2's program table and fallback chains; location ids from
 * engine-entity-draws.md section 5): per (pass kind, game pipeline location id) one program chain in preference order
 * (the first whose program exists in the loaded set and runs wins), the {@code renderStage} the draws report, and the
 * gbuffers phase the geometry belongs to. Pure data: the unit tests check every documented program and chain.
 *
 * <p>The location id is the pipeline's {@code RenderPipeline.getLocation().getPath()} with the {@code pipeline/}
 * prefix stripped ({@code pipeline/solid_terrain} → {@code solid_terrain}). A location the table does not name (the
 * screen effects, the debug crosshair, post chains) has no pack program: its pipelines draw through variants of the
 * game's own pipeline (plan.md E4, that bullet).
 *
 * <p>Kind rules (plan.md E4): with kind {@link PassKind#HAND} the entity and item pipelines are the hand's — they map
 * to {@code gbuffers_hand} (solid) or {@code gbuffers_hand_water} (translucent) whatever their location, and the render
 * stage becomes {@code HAND_SOLID} or {@code HAND_TRANSLUCENT}. {@link PassKind#SHADOW} is E5's; its table lands with
 * that milestone and this class reports no mapping for it until then.
 *
 * <h2>The E4 routing rules (decided 2026-11-14, from the shaders.properties gbuffers reference)</h2>
 * <ol>
 * <li>Entity-domain draws whose game pipeline blends draw through {@code gbuffers_entities_translucent}: the reference
 * table says "gbuffers_entities_translucent | translucent entities | gbuffers_entities", and the Iris additional
 * programs note says those programs "separate translucent geometry that would otherwise render in the same program as
 * opaque geometry". A pipeline blends when any of its colour target states carries a blend function (26.3
 * {@code RenderPipelines}: entity_shadow and the text pipelines build {@code ColorTargetState(BlendFunction.TRANSLUCENT)},
 * the cutout and solid ones {@code ColorTargetState.DEFAULT}, javap 2026-11-14). Rows that name a more specific
 * documented program (the glint's {@code armor_glint}, {@code spidereyes}, {@code leash}'s basic) keep it: blending
 * alone does not override a documented row.
 * <li>Draws submitted by block-entity renderers draw through {@code gbuffers_block}: the reference table says
 * "gbuffers_block | block entities, sign text | gbuffers_terrain". The chest, shulker box, sign, bell, decorated pot,
 * lectern and enchanting-table book are block entities whose renderers submit through the entity and text pipelines
 * (measured, the 2026-11-14 classes run), so the block-entity domain re-routes those rows to the block chain whatever
 * pipeline carries them — including a sign's text, which the table lists under {@code gbuffers_block} although it
 * blends. A blending entities_translucent row (the translucent skulls, items on campfires, spawners and vaults) and a
 * blending block-family row (the banner's pattern layers) draw through "gbuffers_block_translucent | translucent block
 * entities | gbuffers_block". Entity draws stay on {@code gbuffers_entities}.
 * </ol>
 */
public final class GbufferMapping {

	/**
	 * Which renderer submits a draw (the E4 routing rules): the world's entity renderers ({@link #ENTITY}) or the
	 * block-entity renderers ({@link #BLOCK_ENTITY}). The game batches every feature submit that shares a render type
	 * into one draw ({@code LevelRenderer.submitFeatures} passes one collector to {@code submitEntities} at bytecode 14
	 * and to {@code submitBlockEntities} at bytecode 31, javap 2026-11-14), so the domain cannot be told per draw: the
	 * engine submits the block entities into its own storage (the way E4-C's hand renderer does) and draws them in a
	 * {@link GbufferDelegate} domain window, and the two rules above route by it.
	 */
	public enum Domain {
		ENTITY, BLOCK_ENTITY
	}

	/** One mapping row: the program chain in preference order and the render stage's macro name. */
	public record Row(List<String> chain, String renderStage) {
		public Row {
			chain = List.copyOf(chain);
		}
	}

	/** The chain every textured program falls back into (engine-pack-format.md section 2's fallback list). */
	private static final List<String> TEXTURED_LIT = List.of("textured_lit", "textured", "basic");
	private static final List<String> ENTITIES = concat("entities", TEXTURED_LIT);
	private static final List<String> TERRAIN = concat("terrain", TEXTURED_LIT);
	private static final List<String> BLOCK = concat("block", TERRAIN);
	/** The rule-1 and rule-2 chains: the translucent-entity row and the block-entity rows (the class comment). */
	private static final Row ENTITIES_TRANSLUCENT_ROW = new Row(concat("entities_translucent", ENTITIES), "ENTITIES");
	private static final Row BLOCK_ROW = new Row(BLOCK, "BLOCK_ENTITIES");
	private static final Row BLOCK_TRANSLUCENT_ROW = new Row(concat("block_translucent", BLOCK), "BLOCK_ENTITIES");

	private static final Map<String, Row> LOCATIONS = Map.ofEntries(
		// Sky pass (engine-entity-draws.md section 5: the sky-pass membership note).
		Map.entry("sky", new Row(of("skybasic", "basic"), "SKY")),
		Map.entry("stars", new Row(of("skybasic", "basic"), "STARS")),
		Map.entry("sunrise_sunset", new Row(of("skybasic", "basic"), "SUNSET")),
		// The sun and the moon share the celestial pipeline, so one stage serves both (documented choice: SUN).
		Map.entry("celestial", new Row(of("skytextured", "textured", "basic"), "SUN")),
		// The End panorama is a textured sky; CUSTOM_SKY is the closest documented stage [inference].
		Map.entry("end_sky", new Row(of("skytextured", "textured", "basic"), "CUSTOM_SKY")),
		// Terrain (the Iris solid and cutout names come before gbuffers_terrain, plan.md E4's Sodium bullet). Sodium's cutout
		// pass reports the solid stage, as Iris 1.11.7 does (RIL-570, mcms-cutout: Iris draws it with gbuffers_terrain_cutout
		// and renderStage TERRAIN_SOLID, results/2026-11-19-ril570-cutout); its shadow row is shadow_cutout (shadowRow).
		Map.entry("solid_terrain", new Row(concat("terrain_solid", TERRAIN), "TERRAIN_SOLID")),
		Map.entry("cutout_terrain", new Row(concat("terrain_cutout", TERRAIN), "TERRAIN_SOLID")),
		Map.entry("translucent_terrain", new Row(concat("water", TERRAIN), "TERRAIN_TRANSLUCENT")),
		// Entities and the model path (entities covers armor and items-in-world; engine-entity-draws.md section 5).
		// Item entities and item frames are entities (Iris reference, gbuffers: "entities"; OptiFine's gbuffers_item
		// is "not used"), so the item pipelines take the entities chains; the item glint takes the armor glint.
		Map.entry("entity_solid", new Row(ENTITIES, "ENTITIES")),
		Map.entry("entity_solid_offset_forward", new Row(ENTITIES, "ENTITIES")),
		Map.entry("entity_cutout", new Row(ENTITIES, "ENTITIES")),
		Map.entry("entity_cutout_cull", new Row(ENTITIES, "ENTITIES")),
		Map.entry("entity_cutout_no_cull", new Row(ENTITIES, "ENTITIES")),
		Map.entry("entity_cutout_z_offset", new Row(ENTITIES, "ENTITIES")),
		Map.entry("entity_cutout_dissolve", new Row(ENTITIES, "ENTITIES")),
		Map.entry("entity_shadow", new Row(ENTITIES, "ENTITIES")),
		Map.entry("entity_translucent", new Row(concat("entities_translucent", ENTITIES), "ENTITIES")),
		Map.entry("entity_translucent_cull", new Row(concat("entities_translucent", ENTITIES), "ENTITIES")),
		Map.entry("entity_translucent_emissive", new Row(concat("entities_translucent", ENTITIES), "ENTITIES")),
		Map.entry("armor_cutout_no_cull", new Row(ENTITIES, "ENTITIES")),
		Map.entry("armor_decal_cutout_no_cull", new Row(ENTITIES, "ENTITIES")),
		Map.entry("armor_translucent", new Row(concat("entities_translucent", ENTITIES), "ENTITIES")),
		Map.entry("item_cutout", new Row(ENTITIES, "ENTITIES")),
		Map.entry("item_translucent", new Row(concat("entities_translucent", ENTITIES), "ENTITIES")),
		// Block entities and the block-model geometry (a banner's pattern layers are block-entity geometry).
		Map.entry("banner_pattern", new Row(BLOCK, "BLOCK_ENTITIES")),
		Map.entry("solid_block", new Row(BLOCK, "BLOCK_ENTITIES")),
		Map.entry("cutout_block", new Row(BLOCK, "BLOCK_ENTITIES")),
		Map.entry("translucent_block", new Row(concat("block_translucent", BLOCK), "BLOCK_ENTITIES")),
		Map.entry("end_portal", new Row(BLOCK, "BLOCK_ENTITIES")),
		Map.entry("end_gateway", new Row(BLOCK, "BLOCK_ENTITIES")),
		Map.entry("beacon_beam_opaque", new Row(of("beaconbeam", "textured", "basic"), "BLOCK_ENTITIES")),
		Map.entry("beacon_beam_translucent", new Row(of("beaconbeam", "textured", "basic"), "BLOCK_ENTITIES")),
		// Lines (the outline formats; gbuffers_line falls back to basic).
		Map.entry("lines", new Row(of("line", "basic"), "OUTLINE")),
		Map.entry("lines_translucent", new Row(of("line", "basic"), "OUTLINE")),
		Map.entry("lines_translucent_no_depth_write", new Row(of("line", "basic"), "OUTLINE")),
		Map.entry("lines_depth_bias", new Row(of("line", "basic"), "OUTLINE")),
		Map.entry("secondary_block_outline", new Row(of("line", "basic"), "OUTLINE")),
		// Distant Horizons' terrain (plan.md E7; the Iris DH page): DH's opaque and transparent terrain pipelines are named
		// distanthorizons:opaque_terrain and transparent_terrain. The documented programs have no fallback chain.
		Map.entry("opaque_terrain", new Row(of("dh_terrain"), "TERRAIN_SOLID")),
		Map.entry("transparent_terrain", new Row(of("dh_water"), "TERRAIN_TRANSLUCENT")),
		// Destroy stages.
		Map.entry("crumbling", new Row(concat("damagedblock", TERRAIN), "DESTROY")),
		// Text (name tags, world text): entities covers nametags (engine-pack-format.md section 2).
		Map.entry("text", new Row(ENTITIES, "ENTITIES")),
		Map.entry("text_see_through", new Row(ENTITIES, "ENTITIES")),
		Map.entry("text_grayscale", new Row(ENTITIES, "ENTITIES")),
		Map.entry("text_polygon_offset", new Row(ENTITIES, "ENTITIES")),
		Map.entry("text_grayscale_see_through", new Row(ENTITIES, "ENTITIES")),
		Map.entry("text_grayscale_polygon_offset", new Row(ENTITIES, "ENTITIES")),
		// Leash and glint (leash is gbuffers_basic's geometry; the glint foil maps to armor_glint).
		Map.entry("leash", new Row(of("basic"), "ENTITIES")),
		Map.entry("glint", new Row(of("armor_glint", "textured", "basic"), "ENTITIES")),
		Map.entry("glint_special", new Row(of("armor_glint", "textured", "basic"), "ENTITIES")),
		Map.entry("entity_solid_glint", new Row(of("armor_glint", "textured", "basic"), "ENTITIES")),
		Map.entry("armor_cutout_no_cull_glint", new Row(of("armor_glint", "textured", "basic"), "ENTITIES")),
		Map.entry("item_translucent_glint", new Row(of("armor_glint", "textured", "basic"), "ENTITIES")),
		// Particles, weather, clouds, world border, lightning.
		Map.entry("opaque_particle", new Row(concat("particles", TEXTURED_LIT), "PARTICLES")),
		Map.entry("translucent_particle", new Row(of("particles_translucent", "particles", "textured_lit", "textured",
			"basic"), "PARTICLES")),
		Map.entry("weather", new Row(concat("weather", TEXTURED_LIT), "RAIN_SNOW")),
		Map.entry("clouds", new Row(of("clouds", "textured", "basic"), "CLOUDS")),
		Map.entry("flat_clouds", new Row(of("clouds", "textured", "basic"), "CLOUDS")),
		// The OIT transmittance variant (OitPipelineSet builds it as pipeline/oit_transmittance_flat_clouds): the same
		// clouds geometry, so the same chain.
		Map.entry("oit_transmittance_flat_clouds", new Row(of("clouds", "textured", "basic"), "CLOUDS")),
		Map.entry("world_border", new Row(of("textured", "basic"), "WORLD_BORDER")),
		Map.entry("lightning", new Row(concat("lightning", ENTITIES), "ENTITIES")),
		Map.entry("dragon_rays", new Row(concat("lightning", ENTITIES), "ENTITIES")),
		Map.entry("eyes", new Row(of("spidereyes", "textured", "basic"), "ENTITIES")));

	private static final Row HAND = new Row(concat("hand", TEXTURED_LIT), "HAND_SOLID");
	private static final Row HAND_TRANSLUCENT = new Row(of("hand_water", "hand", "textured_lit", "textured", "basic"),
		"HAND_TRANSLUCENT");

	private GbufferMapping() {
	}

	private static List<String> concat(String first, List<String> rest) {
		return java.util.stream.Stream.concat(java.util.stream.Stream.of(first), rest.stream()).toList();
	}

	private static List<String> of(String... names) {
		return List.of(names);
	}

	/**
	 * The row a (kind, location) maps to in the entity domain with a pipeline that does not blend: the table row
	 * itself. The hand kinds override every location (plan.md E4), the shadow kind has no table until E5, and a
	 * translucent-phase hand is {@code gbuffers_hand_water}. Empty when the location names no documented geometry (a
	 * game-pipeline variant draws it).
	 */
	public static Optional<Row> row(PassKind kind, GbufferPhase phase, String location) {
		return row(kind, phase, location, Domain.ENTITY, false);
	}

	/**
	 * The row a (kind, phase, location) maps to under the two E4 routing rules (the class comment):
	 * <ul>
	 * <li>Rule 1: an entity-domain row whose chain is the plain entity chain re-routes to
	 * {@code gbuffers_entities_translucent} when the game pipeline blends. Rows already naming a translucent or
	 * specific program keep theirs.
	 * <li>Rule 2: a block-entity-domain row whose chain is an entity chain (entities or entities_translucent first)
	 * re-routes to {@code gbuffers_block}; a blending entities_translucent row or a blending block-family row takes
	 * {@code gbuffers_block_translucent} first. Specific documented programs (beacon beams, the glint, leash) survive
	 * both domains.
	 * </ul>
	 * The hand and shadow kinds ignore the domain.
	 *
	 * @param domain which renderer submits the draw ({@code LevelRenderer.submitEntities} or the block-entity
	 *               renderers inside {@code LevelRenderer.submitBlockEntities})
	 * @param blends whether the game pipeline's colour target states carry a blend function
	 */
	public static Optional<Row> row(PassKind kind, GbufferPhase phase, String location, Domain domain, boolean blends) {
		if (kind == PassKind.HAND) {
			return Optional.of(phase == GbufferPhase.TRANSLUCENT ? HAND_TRANSLUCENT : HAND);
		}
		if (kind == PassKind.SHADOW) {
			return shadowRow(location, domain);
		}
		Row row = LOCATIONS.get(location);
		if (row == null) {
			return Optional.empty();
		}
		if (domain == Domain.ENTITY) {
			// Rule 1: only the plain entity chain re-routes (entities_translucent and the specific rows already serve
			// their documented programs).
			if (blends && row.chain().get(0).equals("entities")) {
				return Optional.of(ENTITIES_TRANSLUCENT_ROW);
			}
			return Optional.of(row);
		}
		// Rule 2: an entity-family row (entities or entities_translucent first) becomes gbuffers_block — sign text is
		// documented there although it blends, so the blend does not split the plain entity family. A blending
		// entities_translucent row (the translucent skulls, items on campfires, spawners and vaults: entity_translucent,
		// item_translucent, armor_translucent) takes gbuffers_block_translucent first, the Iris reference's translucent
		// block-entity program. A blending block-family row takes it too (the banner's pattern layers; translucent_block
		// already does).
		String first = row.chain().get(0);
		if (blends && first.equals("entities_translucent")) {
			return Optional.of(BLOCK_TRANSLUCENT_ROW);
		}
		if (first.equals("entities") || first.equals("entities_translucent")) {
			return Optional.of(BLOCK_ROW);
		}
		if (blends && first.equals("block")) {
			return Optional.of(BLOCK_TRANSLUCENT_ROW);
		}
		return Optional.of(row);
	}

	/** The shadow kind's rows (plan.md E5; engine-pack-format.md sections 2 and 16): each program falls back to {@code shadow}. */
	private static final Row SHADOW_SOLID_ROW = new Row(of("shadow_solid", "shadow"), "TERRAIN_SOLID");
	// Sodium's cutout terrain reports the solid stage in the shadow pass too, as Iris 1.11.7 does (RIL-570): in mcms-alpharef-set's
	// shadow view (results/2026-11-19-ril562-alpharef, checks/alpharef-set.txt) Iris draws no cutout-stage texel, and its
	// solid-stage texels equal the engine's solid plus cutout texels (dense 156,294 = 125,163 + 31,131). The shadow_cutout
	// program is the documented one.
	private static final Row SHADOW_CUTOUT_ROW = new Row(of("shadow_cutout", "shadow"), "TERRAIN_SOLID");
	private static final Row SHADOW_WATER_ROW = new Row(of("shadow_water", "shadow"), "TERRAIN_TRANSLUCENT");
	private static final Row SHADOW_ENTITIES_ROW = new Row(of("shadow_entities", "shadow"), "ENTITIES");
	private static final Row SHADOW_BLOCK_ROW = new Row(of("shadow_block", "shadow"), "BLOCK_ENTITIES");

	/**
	 * The shadow kind's row (plan.md E5): the Iris and OptiFine shadow programs. Solid terrain takes {@code shadow_solid},
	 * cutout terrain {@code shadow_cutout}, translucent terrain {@code shadow_water}, entities and the beacon beam
	 * {@code shadow_entities}, and block entities {@code shadow_block} (the Shadow program page's table). A location the
	 * shadow pass does not draw (sky, clouds, particles, weather, the world border, outlines, destroy stages, nametags, the
	 * overlays of {@link #shadowOverlay}) has no row, so its draws are dropped in the shadow pass: the game's own shader never
	 * draws into the shadow targets. The chains end at {@code shadow}: no textured fallback applies there. Decision (E5):
	 * nametag text is not drawn into the shadow maps [inference: the docs name no shadow program for text].
	 */
	private static Optional<Row> shadowRow(String location, Domain domain) {
		if (location.startsWith("text") || shadowOverlay(location) || isDistant(location)) {
			return Optional.empty();
		}
		Row world = LOCATIONS.get(location);
		if (world == null) {
			return Optional.empty();
		}
		// Sodium's cutout terrain shares the solid stage, so its location picks its shadow program.
		if (location.equals("cutout_terrain")) {
			return Optional.of(SHADOW_CUTOUT_ROW);
		}
		return switch (world.renderStage()) {
			case "TERRAIN_SOLID" -> Optional.of(SHADOW_SOLID_ROW);
			case "TERRAIN_TRANSLUCENT" -> Optional.of(SHADOW_WATER_ROW);
			// The beacon beam is entity-program geometry in the shadow pass (Iris: shadow_entities covers it).
			case "BLOCK_ENTITIES" -> location.startsWith("beacon_beam") ? Optional.of(SHADOW_ENTITIES_ROW)
				: Optional.of(SHADOW_BLOCK_ROW);
			case "ENTITIES" -> domain == Domain.BLOCK_ENTITY ? Optional.of(SHADOW_BLOCK_ROW)
				: Optional.of(SHADOW_ENTITIES_ROW);
			default -> Optional.empty();
		};
	}

	/**
	 * The overlays the shadow pass does not draw (nit 25; E5 decision). The docs name only entities and the beacon beam for
	 * {@code shadow_entities}. The blob shadow under a mob ({@code entity_shadow}) is a ground decal, not geometry the light
	 * sees. The enchantment glint is a second draw of an item's or armour's own geometry, which the entity already casts.
	 * The spider and enderman eyes ({@code eyes}) are emissive overlays. None of them casts.
	 */
	private static boolean shadowOverlay(String location) {
		return location.equals("entity_shadow") || location.equals("eyes") || location.contains("glint");
	}

	/** The phases each location draws in (engine-entity-draws.md section 5's pass/phase column). */
	private static final Map<String, GbufferPhase[]> PHASES = Map.ofEntries(
		Map.entry("sky", sky()), Map.entry("stars", sky()), Map.entry("sunrise_sunset", sky()),
		Map.entry("celestial", sky()), Map.entry("end_sky", sky()),
		Map.entry("opaque_terrain", distant()), Map.entry("transparent_terrain", translucent()),
		Map.entry("solid_terrain", opaque()), Map.entry("cutout_terrain", opaque()),
		Map.entry("entity_solid", opaque()), Map.entry("entity_solid_offset_forward", opaque()),
		Map.entry("entity_cutout", opaque()), Map.entry("entity_cutout_cull", opaque()),
		Map.entry("entity_cutout_no_cull", opaque()), Map.entry("entity_cutout_z_offset", opaque()),
		Map.entry("entity_cutout_dissolve", opaque()), Map.entry("solid_block", opaque()),
		Map.entry("cutout_block", opaque()), Map.entry("end_portal", opaque()), Map.entry("end_gateway", opaque()),
		Map.entry("beacon_beam_opaque", opaque()), Map.entry("leash", opaque()), Map.entry("glint", opaque()),
		Map.entry("glint_special", opaque()), Map.entry("opaque_particle", opaque()),
		Map.entry("banner_pattern", opaque()), Map.entry("armor_cutout_no_cull", opaque()),
		Map.entry("armor_decal_cutout_no_cull", opaque()), Map.entry("armor_cutout_no_cull_glint", opaque()),
		Map.entry("entity_solid_glint", opaque()),
		Map.entry("translucent_terrain", translucent()), Map.entry("entity_translucent", translucent()),
		Map.entry("entity_translucent_emissive", translucent()), Map.entry("entity_shadow", translucent()),
		Map.entry("translucent_block", translucent()), Map.entry("beacon_beam_translucent", translucent()),
		Map.entry("lines_translucent", translucent()), Map.entry("lines_translucent_no_depth_write", translucent()),
		Map.entry("lines_depth_bias", translucent()), Map.entry("secondary_block_outline", translucent()),
		Map.entry("text_see_through", translucent()), Map.entry("translucent_particle", translucent()),
		Map.entry("weather", translucent()), Map.entry("clouds", translucent()), Map.entry("flat_clouds", translucent()),
		Map.entry("oit_transmittance_flat_clouds", translucent()),
		Map.entry("world_border", translucent()), Map.entry("lightning", translucent()),
		Map.entry("dragon_rays", translucent()),
		Map.entry("armor_translucent", translucent()), Map.entry("entity_translucent_cull", translucent()),
		Map.entry("item_translucent", translucent()), Map.entry("item_translucent_glint", translucent()),
		Map.entry("text_grayscale_see_through", translucent()),
		// The alpha-255 routes draw solid; the same pipelines serve the translucent phases (outline, text, destroy).
		Map.entry("lines", both()), Map.entry("crumbling", both()), Map.entry("text", both()),
		Map.entry("text_grayscale", both()), Map.entry("text_polygon_offset", both()),
		Map.entry("eyes", both()), Map.entry("text_grayscale_polygon_offset", both()));

	private static GbufferPhase[] sky() {
		return new GbufferPhase[] {GbufferPhase.SKY};
	}

	private static GbufferPhase[] opaque() {
		return new GbufferPhase[] {GbufferPhase.OPAQUE};
	}

	private static GbufferPhase[] translucent() {
		return new GbufferPhase[] {GbufferPhase.TRANSLUCENT};
	}

	private static GbufferPhase[] both() {
		return new GbufferPhase[] {GbufferPhase.OPAQUE, GbufferPhase.TRANSLUCENT};
	}

	private static GbufferPhase[] distant() {
		return new GbufferPhase[] {GbufferPhase.DISTANT};
	}

	/**
	 * The Distant Horizons terrain locations (plan.md E7): DH's own pipelines, drawn by the engine only, the opaque one in the DISTANT phase
	 * and the transparent one in the TRANSLUCENT phase.
	 */
	private static final java.util.Set<String> DISTANT_LOCATIONS = java.util.Set.of("opaque_terrain", "transparent_terrain");

	/** Whether a location is one of Distant Horizons' terrain pipelines ({@code distanthorizons:opaque_terrain} and its sibling). */
	public static boolean isDistant(String location) {
		return DISTANT_LOCATIONS.contains(location);
	}

	/**
	 * The phases a location's pipelines draw in with this kind: the hand kinds serve both phases (the solid and the
	 * translucent hand), and every other location — mapped or not — draws in both too: an unmapped location's solid
	 * geometry draws in the solid phase ({@code item_cutout} did, dropped silently, log 2026-11-14-e4-preview2; the model
	 * path's solid variants draw main solid, engine-entity-draws.md section 5) and the post-hook passes run translucent,
	 * so a variant must exist in both or one of the two drops.
	 */
	public static GbufferPhase[] phases(PassKind kind, String location) {
		if (kind == PassKind.HAND) {
			return isDistant(location) ? new GbufferPhase[0] : both();
		}
		GbufferPhase[] phases = PHASES.get(location);
		return phases != null ? phases : both();
	}

	/** The {@code renderStage} value of a macro name (its list position; {@link StandardMacros#RENDER_STAGES}). */
	public static int renderStage(String name) {
		int index = StandardMacros.RENDER_STAGES.indexOf(name);
		if (index < 0) {
			throw new IllegalArgumentException("not a documented render stage: " + name);
		}
		return index;
	}

	/**
	 * The location id of a pipeline location path ({@code pipeline/sky} → {@code sky}). The multidraw backend (Vulkan's
	 * VK_MULTIDRAW style, Sodium's {@code createShader} takes the pass's pipeline location) names its terrain pipelines
	 * {@code solid_terrain_multidraw}, {@code cutout_terrain_multidraw}, {@code translucent_terrain_multidraw} and the
	 * wireframe ones: they map to the same rows as their non-multidraw pipelines ({@code _multidraw} is dropped).
	 */
	public static String locationOf(String path) {
		String location = path.startsWith("pipeline/") ? path.substring("pipeline/".length()) : path;
		return location.endsWith("_multidraw") ? location.substring(0, location.length() - "_multidraw".length()) : location;
	}

	/** Every location the table names (the unit test checks the documented programs are reachable). */
	public static Map<String, Row> table() {
		return LOCATIONS;
	}

	/** The fallback chain the resolver walks for one row, ending at basic where the documented chain does. */
	public static @Nullable List<String> chainOf(Row row) {
		return row.chain();
	}
}
