// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.pack;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The standard macros of engine-pack-format.md section 3 with this engine's values, in one place. Each value carries its
 * source: the OptiFine doc (shaders.txt, "Standard Macros"), an Iris macro or feature page (https://shaders.properties),
 * one of our own reference logs, or an engine decision. The engine follows Iris as the reference: packs take the same code
 * path they take under Iris, so {@code IS_IRIS} is defined.
 */
public final class StandardMacros {
	/** One predefined macro: its replacement text ("" for a flag) and where the value comes from. */
	public record Macro(String name, String value, String source) {
	}

	/** Feature flags the Iris Feature Flags page documents. */
	public static final Set<String> IRIS_FEATURES = Set.of(
		"BLOCK_EMISSION_ATTRIBUTE", "CAN_DISABLE_WEATHER", "COMPUTE_SHADERS", "CUSTOM_IMAGES", "ENTITY_TRANSLUCENT",
		"HIGHER_SHADOWCOLOR", "PER_BUFFER_BLENDING", "REVERSED_CULLING", "SEPARATE_HARDWARE_SAMPLERS", "SSBO",
		"TESSELATION_SHADERS", "TESSELLATION_SHADERS", "FADE_VARIABLE", "TEXTURE_FILTERING");

	/**
	 * The optional Iris feature flags this engine provides (decided 2026-11-15, plan E6); {@link #FEATURE_SOURCES} gives the code
	 * behind each one. A pack gets a flag only when it lists the flag in {@code iris.features.optional} or
	 * {@code iris.features.required} ({@link #withRequestedFeatures}). The other flags of the Iris list are not provided: the engine
	 * does not provide their features yet, and SEPARATE_HARDWARE_SAMPLERS in particular (ShadowTargets, E5).
	 */
	public static final Set<String> ENGINE_FEATURES = Set.of("BLOCK_EMISSION_ATTRIBUTE", "COMPUTE_SHADERS", "CUSTOM_IMAGES",
		"ENTITY_TRANSLUCENT", "HIGHER_SHADOWCOLOR", "PER_BUFFER_BLENDING", "SSBO");

	/**
	 * Render stages in the order of the Iris render stages page (macros/render_stages). A stage's value is its list position;
	 * the page gives no numbers.
	 */
	public static final List<String> RENDER_STAGES = List.of(
		"NONE", "SKY", "SUNSET", "CUSTOM_SKY", "SUN", "MOON", "STARS", "VOID", "TERRAIN_SOLID", "TERRAIN_CUTOUT_MIPPED",
		"TERRAIN_CUTOUT", "ENTITIES", "BLOCK_ENTITIES", "DESTROY", "OUTLINE", "DEBUG", "HAND_SOLID", "TERRAIN_TRANSLUCENT",
		"TRIPWIRE", "PARTICLES", "CLOUDS", "RAIN_SNOW", "WORLD_BORDER", "HAND_TRANSLUCENT");

	/**
	 * The Distant Horizons block ids (engine-pack-format.md 17, Iris DH page). Each DH_BLOCK_ macro takes its position in
	 * this list, which is DH 3.3.4's {@code EDhApiBlockMaterial} in its index order (javap of its static initializer,
	 * 2026-11-16): the value DH writes into every vertex's material byte, which the pack reads as {@code dhMaterialId}
	 * and compares with these defines. The Iris page names the blocks in another order (GRASS after DIRT); that order
	 * made dh_water miss its WATER test and dh_terrain its GRASS test.
	 */
	public static final List<String> DH_BLOCKS = List.of("UNKNOWN", "LEAVES", "STONE", "WOOD", "METAL", "DIRT", "LAVA",
		"DEEPSLATE", "SNOW", "SAND", "TERRACOTTA", "NETHER_STONE", "WATER", "GRASS", "AIR", "ILLUMINATED");

	/** Macros whose value is a number (the rest are flags). The Iris pages and shaders.txt name them. */
	private static final Set<String> VALUE_MACROS = Set.of(
		"MC_VERSION", "MC_GL_VERSION", "MC_GLSL_VERSION", "MC_FXAA_LEVEL", "MC_RENDER_QUALITY", "MC_SHADOW_QUALITY",
		"MC_HAND_DEPTH", "MC_ANISOTROPIC_FILTERING", "IRIS_VERSION", "MAX_COLOR_BUFFERS");

	/** Every macro name the documents name, so an undefined one is told apart from an unknown name. */
	public static final Set<String> DOCUMENTED;

	static {
		Set<String> names = new TreeSet<>(VALUE_MACROS);
		names.addAll(List.of("MC_OS_WINDOWS", "MC_OS_MAC", "MC_OS_LINUX", "MC_OS_OTHER",
			"MC_GL_VENDOR_AMD", "MC_GL_VENDOR_ATI", "MC_GL_VENDOR_INTEL", "MC_GL_VENDOR_MESA", "MC_GL_VENDOR_NVIDIA",
			"MC_GL_VENDOR_XORG", "MC_GL_VENDOR_OTHER",
			"MC_GL_RENDERER_RADEON", "MC_GL_RENDERER_GEFORCE", "MC_GL_RENDERER_QUADRO", "MC_GL_RENDERER_INTEL",
			"MC_GL_RENDERER_GALLIUM", "MC_GL_RENDERER_MESA", "MC_GL_RENDERER_APPLE", "MC_GL_RENDERER_OTHER",
			"MC_NORMAL_MAP", "MC_SPECULAR_MAP", "MC_OLD_HAND_LIGHT", "MC_OLD_LIGHTING",
			"MC_TEXTURE_FORMAT_LAB_PBR", "MC_TEXTURE_FORMAT_LAB_PBR_1_3",
			"IS_IRIS", "DISTANT_HORIZONS", "IRIS_HAS_CONNECTED_TEXTURES", "IRIS_HAS_TRANSLUCENCY_SORTING", "IRIS_TAG_SUPPORT"));
		for (String stage : RENDER_STAGES) names.add("MC_RENDER_STAGE_" + stage);
		for (String block : DH_BLOCKS) names.add("DH_BLOCK_" + block);
		DOCUMENTED = Set.copyOf(names);
	}

	// Sources, cited once and used by the values below.
	private static final String SRC_MC_VERSION = "Iris macro page MC_VERSION: 122 format (major*10000 + minor*100 + release), "
		+ "so 26.3.0 is 260300; the page gives no 26.x rule, and this value sorts after every 1.x value";
	private static final String SRC_GL_460 = "engine decision (ADR 0006 E6: packs run the engine's full shader feature set): the engine "
		+ "runs compute, SSBOs and image load/store, the feature level of a GL 4.6 driver, and packs gate those paths on this "
		+ "macro. The Iris page defines it as the maximum OpenGL version; the reference Iris runs report 4.1 only because Apple's "
		+ "OpenGL stops at 4.1 (results/2026-10-11-m0c-dims/gl-iris/run1/game-latest.log: 'OpenGL Version: 4.1 Metal')";
	private static final String SRC_GLSL_460 = "engine decision, the same as MC_GL_VERSION: GL 4.6 provides GLSL 4.60 (Khronos "
		+ "GL version history), the highest version the engine's parser accepts (W1-B); the Iris page defines the macro as the "
		+ "maximum GLSL version";
	private static final String SRC_VENDOR = "OptiFine doc shaders.txt, E, and Iris driver vendor page: neither has an Apple "
		+ "vendor, so the vendor is OTHER; our reference logs (results/2026-10-11-m0c-dims/gl-iris/run1/game-latest.log) report "
		+ "'OpenGL Vendor: Apple'";
	private static final String SRC_RENDERER = "Iris GPU hardware page: MC_GL_RENDERER_APPLE is defined on an Apple GPU (Iris 1.8 "
		+ "or higher), so OTHER is not; our reference logs report 'OpenGL Renderer: Apple M5 Max'";
	private static final String SRC_RENDER_QUALITY = "engine decision: 1.0 is the default render scale; the value list is "
		+ "shaders.txt H. Options; the engine has no render-scale setting before E8";
	private static final String SRC_SHADOW_QUALITY = "engine decision: 1.0 is the default shadow quality; the value list is "
		+ "shaders.txt H. Options; no shadow-quality setting before E8";
	private static final String SRC_HAND_DEPTH = "engine decision, docs/research/engine-pack-format.md section 3 (2026-11-13): "
		+ "0.125, the middle value of shaders.txt H. Options";
	private static final String SRC_OFF = "engine decision: not defined, the engine's default for this setting is off until E8";
	private static final String SRC_NO_PBR = "engine decision: the engine loads no LabPBR or normal/specular texture packs";
	private static final String SRC_IRIS = "engine decision: packs take the Iris code path (plan.md, ADR 0006); the reference is Iris";
	private static final String SRC_IRIS_VERSION = "bench/mods.lock.json pins Iris 1.11.7 (the reference runs, "
		+ "results/2026-10-10-m0-baselines/gl-iris/run1/game-latest.log); Iris page IRIS_VERSION: 122 format, so 1.11.7 is 11107";
	private static final String SRC_MAX_COLOR = "engine decision: the count of colortex targets the engine serves, "
		+ "Targets.COLOR_TARGETS (colortex0 to colortex15). Iris's macOS page says 'all 16 buffers' (OptiFine gives macOS 8), "
		+ "but the black box reports 32 on Iris 1.11.7 and on Vitrail (results/2026-11-15-e6-macros/gl-iris-macros and "
		+ "metal-vitrail-macros), so 16 is the engine's count, not the reference's";
	private static final String SRC_TRANSLUCENCY = "Iris page IRIS_HAS_TRANSLUCENCY_SORTING: defined when Sodium 0.6 or later is "
		+ "loaded; the engine's Sodium pin is 0.9.3-beta.1 (bench/mods.lock.json). Vitrail on Metal does not define it "
		+ "(results/2026-11-15-e6-macros/metal-vitrail-macros), so a pack's translucency branch differs there";
	private static final String SRC_RENDER_STAGE = "Iris render stages page: the names and their order (TERRAIN_CUTOUT_MIPPED "
		+ "included); the value is the list position [inference: the page gives no numbers]";
	private static final String SRC_FEATURE = "Iris Feature Flags page: IRIS_FEATURE_<flag> is defined for each supported flag";
	/** The code behind each engine flag: the flag is defined only with the feature it names. */
	public static final Map<String, String> FEATURE_SOURCES = Map.of(
		"COMPUTE_SHADERS", "engine: compute stages run through ComputeKernel (plan E6)",
		"CUSTOM_IMAGES", "engine: image load/store for colorimgN and shadowcolorimgN (Engine, ComputeKernel; plan E6)",
		"SSBO", "engine: shader storage buffers for bufferObject.N (plan E6)",
		"ENTITY_TRANSLUCENT", "engine: GbufferMapping rules 1 and 2 (plan E4): a blending entity draw goes to "
			+ "gbuffers_entities_translucent; a blending block-entity draw of the entities_translucent family (skulls, items on "
			+ "campfires, spawners, vaults) and of the block family (banner layers) goes to gbuffers_block_translucent; sign text "
			+ "and other block-entity text stay on gbuffers_block",
		"BLOCK_EMISSION_ATTRIBUTE", "engine: TerrainEncoder writes the block light emission into at_midBlock.w of every "
			+ "terrain vertex (TerrainLayout, MID_BLOCK); the entity and block-entity vertex layouts have no at_midBlock input, "
			+ "so only terrain carries the emission",
		"PER_BUFFER_BLENDING", "engine: GbufferPlans.blendOf reads blend.<program>.<buffer> for each attachment of a gbuffers "
			+ "or shadow draw (plan E4, E5)",
		"HIGHER_SHADOWCOLOR", "engine: ShadowTargets serves shadowcolor0 to shadowcolor7 (COLOR_TARGETS = 8) and grows the "
			+ "count to the highest index a program writes or names");
	private static final String SRC_DH = "Iris macro DISTANT_HORIZONS: defined while Distant Horizons is active (milestone E7); "
		+ "not defined before E7";
	private static final String SRC_MAPS = "OptiFine doc shaders.txt, Normal Map and Specular Map options: the macro is "
		+ "defined when that map is enabled, and both are on by default (Main's reading, 2026-11-13). The reference "
		+ "compiles Bliss only with MC_NORMAL_MAP defined: results/2026-10-19-packs-dims/metal-vitrail-bliss/run1/"
		+ "game-latest.log lines 332 and 337 (terrain compiled, world0/gbuffers_terrain drawn with no error)";
	private static final String SRC_DH_BLOCK = "Iris DH page and engine-pack-format.md 17: the DH_BLOCK_ defines of the "
		+ "block ids, defined while Distant Horizons is active; the value is the block's index in DH 3.3.4's "
		+ "EDhApiBlockMaterial (javap), the byte DH writes as each vertex's material";

	private final List<Macro> macros;
	private final Map<String, String> predefined;
	/** The feature flags this engine provides; the pack's requests pick the ones defined (withRequestedFeatures). */
	private final Set<String> offered;
	private final boolean distantHorizons;
	/** {@code MC_HAND_DEPTH}'s value in this run (E8: the player's Hand depth setting, 0.0625, 0.125 or 0.25). */
	private final float handDepth;

	/** {@code MC_HAND_DEPTH} when no setting chooses: the middle documented value (engine-pack-format.md section 3). */
	public static final float DEFAULT_HAND_DEPTH = 0.125f;

	/**
	 * The macros of a run that provides the given feature flags. No feature flag is defined here: a pack defines each flag it
	 * requests ({@link #withRequestedFeatures}).
	 *
	 * @param offered         the feature flags the engine provides; each must be one of {@link #IRIS_FEATURES}
	 * @param distantHorizons whether Distant Horizons is active in this run
	 */
	public StandardMacros(Set<String> offered, boolean distantHorizons) {
		this(offered, Set.of(), distantHorizons, DEFAULT_HAND_DEPTH);
	}

	private StandardMacros(Set<String> offered, Set<String> requested, boolean distantHorizons, float handDepth) {
		for (String f : offered) {
			if (!IRIS_FEATURES.contains(f)) throw new IllegalArgumentException("unknown Iris feature flag: " + f);
		}
		this.offered = Set.copyOf(offered);
		this.distantHorizons = distantHorizons;
		this.handDepth = handDepth;
		List<Macro> out = new ArrayList<>();
		out.add(new Macro("MC_VERSION", MC_VERSION, SRC_MC_VERSION));
		out.add(new Macro("MC_GL_VERSION", MC_GL_VERSION, SRC_GL_460));
		out.add(new Macro("MC_GLSL_VERSION", MC_GLSL_VERSION, SRC_GLSL_460));
		out.add(new Macro("MC_GL_VENDOR_OTHER", "", SRC_VENDOR));
		out.add(new Macro("MC_GL_RENDERER_APPLE", "", SRC_RENDERER));
		out.add(new Macro("MC_RENDER_QUALITY", "1.0", SRC_RENDER_QUALITY));
		out.add(new Macro("MC_SHADOW_QUALITY", "1.0", SRC_SHADOW_QUALITY));
		out.add(new Macro("MC_HAND_DEPTH", Float.toString(handDepth), SRC_HAND_DEPTH));
		out.add(new Macro("IS_IRIS", "", SRC_IRIS));
		out.add(new Macro("IRIS_VERSION", IRIS_VERSION, SRC_IRIS_VERSION));
		out.add(new Macro("MAX_COLOR_BUFFERS", "16", SRC_MAX_COLOR));
		out.add(new Macro("IRIS_HAS_TRANSLUCENCY_SORTING", "", SRC_TRANSLUCENCY));
		// Flags of the normal and specular map options (Main's decision, 2026-11-13; plan E6 amended): the reasons are in
		// SRC_MAPS. With no PBR pack, the normals and specular samplers hold the engine's defaults.
		out.add(new Macro("MC_NORMAL_MAP", "", SRC_MAPS));
		out.add(new Macro("MC_SPECULAR_MAP", "", SRC_MAPS));
		if (distantHorizons) out.add(new Macro("DISTANT_HORIZONS", "", SRC_DH));
		if (distantHorizons) {
			for (int i = 0; i < DH_BLOCKS.size(); i++) {
				out.add(new Macro("DH_BLOCK_" + DH_BLOCKS.get(i), Integer.toString(i), SRC_DH_BLOCK));
			}
		}
		for (int i = 0; i < RENDER_STAGES.size(); i++) {
			out.add(new Macro("MC_RENDER_STAGE_" + RENDER_STAGES.get(i), Integer.toString(i), SRC_RENDER_STAGE));
		}
		for (String f : new TreeSet<>(requested)) {
			out.add(new Macro("IRIS_FEATURE_" + f, "", FEATURE_SOURCES.getOrDefault(f, SRC_FEATURE)));
		}
		// Not defined, each with its reason: the reasons are the source of the absence.
		// MC_OS_WINDOWS, MC_OS_MAC, MC_OS_LINUX, MC_OS_OTHER: no operating-system macro (decided 2026-11-15, plan E6). Packs
		// use MC_OS_MAC for Apple OpenGL's limits (Complementary: no colored lighting, and its error banner), and this engine
		// is not Apple's OpenGL: it runs compute, SSBOs and image load/store (SRC_GL_460). Vitrail on Metal defines no MC_OS_*
		// macro (results/2026-11-15-e6-macros/metal-vitrail-macros); our metal-qs run defined MC_OS_MAC before this decision
		// (results/2026-11-15-e6-macros/metal-qs-macros).
		// MC_FXAA_LEVEL (FXAA off), MC_TEXTURE_FORMAT_LAB_PBR(_1_3): SRC_NO_PBR, MC_OLD_HAND_LIGHT,
		// MC_OLD_LIGHTING, MC_ANISOTROPIC_FILTERING: SRC_OFF; IRIS_HAS_CONNECTED_TEXTURES (no such mod); MC_GL_<extension>
		// (no extension list yet; the report lists the extensions the packs name).
		this.macros = List.copyOf(out);
		Map<String, String> p = new LinkedHashMap<>();
		for (Macro m : macros) p.put(m.name(), m.value());
		this.predefined = java.util.Collections.unmodifiableMap(p);
	}

	/**
	 * The engine's own configuration without Distant Horizons: the feature flags it provides ({@link #ENGINE_FEATURES}). A
	 * pack defines each one it requests.
	 */
	public static StandardMacros engine() {
		return engine(false);
	}

	/**
	 * The engine's configuration for a run: the feature flags it provides, and {@code DISTANT_HORIZONS} with the
	 * {@code DH_BLOCK_*} defines when Distant Horizons draws in this run (plan.md E7, decision 8).
	 */
	public static StandardMacros engine(boolean distantHorizons) {
		return engine(distantHorizons, DEFAULT_HAND_DEPTH);
	}

	/** As {@link #engine(boolean)}, with the player's hand depth as {@code MC_HAND_DEPTH} (E8). */
	public static StandardMacros engine(boolean distantHorizons, float handDepth) {
		return new StandardMacros(ENGINE_FEATURES, Set.of(), distantHorizons, handDepth);
	}

	/** {@code MC_HAND_DEPTH}'s value in this run: the hand programs' projection scales clip z by it. */
	public float handDepth() {
		return handDepth;
	}

	/**
	 * This run's macros with the feature flags a pack requests (its {@code iris.features.optional} and {@code required} lists):
	 * each requested flag this run provides is defined as {@code IRIS_FEATURE_<flag>}, and no other. A flag the run does not
	 * provide stays undefined; a required one the run lacks is refused by the caller ({@link PackModel}).
	 */
	public StandardMacros withRequestedFeatures(Collection<String> requested) {
		Set<String> defined = new TreeSet<>();
		for (String f : requested) {
			if (offered.contains(f)) defined.add(f);
		}
		return new StandardMacros(offered, defined, distantHorizons, handDepth);
	}

	/** Whether this run provides the feature flag (a pack may require it). */
	public boolean offers(String feature) {
		return offered.contains(feature);
	}

	/** Every macro this run defines, with its value and source. */
	public List<Macro> macros() {
		return macros;
	}

	/** The predefined map for the preprocessor: name to replacement text ("" for flags). */
	public Map<String, String> predefined() {
		return predefined;
	}

	public boolean isDefined(String name) {
		return predefined.containsKey(name);
	}

	/** Whether the macro carries a number (the rest are flags). */
	public static boolean takesValue(String name) {
		return VALUE_MACROS.contains(name) || name.startsWith("MC_RENDER_STAGE_") || name.startsWith("DH_BLOCK_");
	}

	public static final String MC_VERSION = "260300";
	public static final String MC_GL_VERSION = "460";
	public static final String MC_GLSL_VERSION = "460";
	public static final String IRIS_VERSION = "11107";
}
