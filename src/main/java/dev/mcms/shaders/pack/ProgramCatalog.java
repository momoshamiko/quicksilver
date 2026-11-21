// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.pack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The documented programs (engine-pack-format.md section 2): the OptiFine list (shaders.txt, "Shader Programs"), the Iris
 * additions (the gbuffers and Distant Horizons pages), numbered variants (suffixes 1 to 99 where the docs allow them) and
 * compute suffixes {@code _a} to {@code _z}. Each program has the fallback the docs name, and a render phase.
 *
 * <p>{@link #base()} is the non-numbered list: 42 documented names, the virtual programs included. It is this engine's list,
 * not Vitrail's. Vitrail reports 39 programs per dimension in our bench logs (results/2026-10-26-pins-dims), and the logs do not
 * name them. Our hypothesis for those 39 (E1 report, difference 5): seven shadow programs (shadow, shadow_solid, shadow_cutout,
 * shadow_water, shadow_entities, shadow_lightning, shadow_block), the 27 gbuffers programs, dh_terrain, dh_water, dh_generic,
 * dh_shadow and final. shadow_lightning and dh_generic are not on the public pages, so they are hypotheses only and are not
 * catalog programs.
 */
public final class ProgramCatalog {
	/** Where a program runs in the frame. Gbuffers programs are split by whether they draw before or after deferred. */
	public enum Phase {
		SETUP, BEGIN, SHADOW, SHADOWCOMP, PREPARE, OPAQUE, DEFERRED, TRANSLUCENT, COMPOSITE, FINAL, VIRTUAL, DISTANT_HORIZONS
	}

	/**
	 * One documented program.
	 *
	 * @param name        the file stem
	 * @param phase       where it runs
	 * @param fallback    the program used when this one has no files, or null
	 * @param numbered    whether suffixes 1 to 99 are documented for it
	 * @param computeSuffix whether {@code _a} to {@code _z} compute files may follow it (not gbuffers-style programs)
	 * @param virtual     true for {@code deferred_pre} and {@code composite_pre}, which have no files
	 */
	public record Program(String name, Phase phase, String fallback, boolean numbered, boolean computeSuffix, boolean virtual) {
	}

	/** A file stem matched to a program, with its compute suffix letter (empty for the plain file). */
	public record Match(Program program, String suffix) {
	}

	private static final Map<String, Program> ALL_BY_NAME = new LinkedHashMap<>();
	private static final List<Program> BASE_LIST = new ArrayList<>();
	private static final List<Program> ALL_LIST = new ArrayList<>();

	/** Gbuffers programs drawn after deferred by default (Iris, gbuffers page): water, hand water, weather. */
	private static final Set<String> AFTER_DEFERRED = Set.of("gbuffers_water", "gbuffers_hand_water", "gbuffers_weather",
		"gbuffers_particles_translucent", "gbuffers_block_translucent", "gbuffers_entities_translucent");

	static {
		// Shadow pass (shaders.txt, Shader Programs; Iris shadow page).
		base("shadow", Phase.SHADOW, null, false, false);
		base("shadow_solid", Phase.SHADOW, "shadow", false, false);
		base("shadow_cutout", Phase.SHADOW, "shadow", false, false);
		// Iris 1.8+ shadow programs (Iris shadow page): each draws its geometry in place of shadow and falls back to it.
		base("shadow_water", Phase.SHADOW, "shadow", false, false);
		base("shadow_entities", Phase.SHADOW, "shadow", false, false);
		base("shadow_block", Phase.SHADOW, "shadow", false, false);
		// Gbuffers (shaders.txt, Shader Programs; Iris gbuffers page, Iris Additional Programs).
		gbuffers("gbuffers_basic", null);
		gbuffers("gbuffers_line", "gbuffers_basic");
		gbuffers("gbuffers_textured", "gbuffers_basic");
		gbuffers("gbuffers_textured_lit", "gbuffers_textured");
		gbuffers("gbuffers_skybasic", "gbuffers_basic");
		gbuffers("gbuffers_skytextured", "gbuffers_textured");
		gbuffers("gbuffers_clouds", "gbuffers_textured");
		gbuffers("gbuffers_terrain", "gbuffers_textured_lit");
		gbuffers("gbuffers_terrain_solid", "gbuffers_terrain");
		gbuffers("gbuffers_terrain_cutout", "gbuffers_terrain");
		gbuffers("gbuffers_damagedblock", "gbuffers_terrain");
		gbuffers("gbuffers_block", "gbuffers_terrain");
		gbuffers("gbuffers_beaconbeam", "gbuffers_textured");
		gbuffers("gbuffers_item", "gbuffers_textured_lit");
		gbuffers("gbuffers_entities", "gbuffers_textured_lit");
		gbuffers("gbuffers_entities_glowing", "gbuffers_entities");
		gbuffers("gbuffers_armor_glint", "gbuffers_textured");
		gbuffers("gbuffers_spidereyes", "gbuffers_textured");
		gbuffers("gbuffers_hand", "gbuffers_textured_lit");
		gbuffers("gbuffers_weather", "gbuffers_textured_lit");
		gbuffers("gbuffers_water", "gbuffers_terrain");
		gbuffers("gbuffers_hand_water", "gbuffers_hand");
		gbuffers("gbuffers_particles", "gbuffers_textured_lit");
		gbuffers("gbuffers_particles_translucent", "gbuffers_particles");
		gbuffers("gbuffers_block_translucent", "gbuffers_block");
		gbuffers("gbuffers_entities_translucent", "gbuffers_entities");
		gbuffers("gbuffers_lightning", "gbuffers_entities");
		// Composite-style passes (shaders.txt; Iris programs overview). Virtual programs flip buffers only.
		composite("setup", Phase.SETUP, true);
		composite("begin", Phase.BEGIN, true);
		composite("shadowcomp", Phase.SHADOWCOMP, true);
		composite("prepare", Phase.PREPARE, true);
		composite("deferred_pre", Phase.VIRTUAL, false);
		composite("deferred", Phase.DEFERRED, true);
		composite("composite_pre", Phase.VIRTUAL, false);
		composite("composite", Phase.COMPOSITE, true);
		composite("final", Phase.FINAL, false);
		// Distant Horizons (Iris, DH page; milestone E7). Not in the base list.
		for (String dh : List.of("dh_terrain", "dh_water", "dh_shadow")) {
			Program p = new Program(dh, Phase.DISTANT_HORIZONS, null, false, false, false);
			ALL_BY_NAME.put(dh, p);
			ALL_LIST.add(p);
		}
		// Numbered variants: 1 to 99 for the composite-style passes that the docs number.
		for (Program p : List.copyOf(BASE_LIST)) {
			if (!p.numbered()) continue;
			for (int i = 1; i <= 99; i++) {
				Program n = new Program(p.name() + i, p.phase(), null, false, true, false);
				ALL_BY_NAME.put(n.name(), n);
				ALL_LIST.add(n);
			}
		}
	}

	private ProgramCatalog() {
	}

	private static void base(String name, Phase phase, String fallback, boolean numbered, boolean computeSuffix) {
		Program p = new Program(name, phase, fallback, numbered, computeSuffix, false);
		ALL_BY_NAME.put(name, p);
		BASE_LIST.add(p);
		ALL_LIST.add(p);
	}

	private static void gbuffers(String name, String fallback) {
		Phase phase = AFTER_DEFERRED.contains(name) ? Phase.TRANSLUCENT : Phase.OPAQUE;
		Program p = new Program(name, phase, fallback, false, false, false);
		ALL_BY_NAME.put(name, p);
		BASE_LIST.add(p);
		ALL_LIST.add(p);
	}

	/** A composite-style program: numbered 1 to 99 when {@code numbered}; virtual programs have no files. */
	private static void composite(String name, Phase phase, boolean numbered) {
		Program p = new Program(name, phase, null, numbered, true, phase == Phase.VIRTUAL);
		ALL_BY_NAME.put(name, p);
		BASE_LIST.add(p);
		ALL_LIST.add(p);
	}

	/** The 39 non-numbered documented programs, virtual ones included, in the docs' order. */
	public static List<Program> base() {
		return List.copyOf(BASE_LIST);
	}

	/** Every documented program, numbered and Distant Horizons variants included. */
	public static List<Program> all() {
		return List.copyOf(ALL_LIST);
	}

	public static Optional<Program> lookup(String name) {
		return Optional.ofNullable(ALL_BY_NAME.get(name));
	}

	/**
	 * Matches a stage file's stem and extension to a program: the stem itself, or for {@code csh} files the stem without a
	 * {@code _a}..{@code _z} compute suffix. Empty when no documented program has the name.
	 */
	public static Optional<Match> match(String stem, String extension) {
		Program direct = ALL_BY_NAME.get(stem);
		if (direct != null) return Optional.of(new Match(direct, ""));
		if (extension.equals("csh") && stem.length() > 2 && stem.charAt(stem.length() - 2) == '_') {
			char letter = stem.charAt(stem.length() - 1);
			if (letter >= 'a' && letter <= 'z') {
				Program base = ALL_BY_NAME.get(stem.substring(0, stem.length() - 2));
				if (base != null && base.computeSuffix()) return Optional.of(new Match(base, String.valueOf(letter)));
			}
		}
		return Optional.empty();
	}
}
