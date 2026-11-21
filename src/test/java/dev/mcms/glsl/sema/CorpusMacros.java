// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.sema;

import dev.mcms.shaders.pack.StandardMacros;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The macro table the corpus test runs with: the environment of Iris on Apple's OpenGL, as the public Iris and OptiFine
 * documentation describes it. Every entry names its source. Where no document gives a value, the entry says so and
 * the value is a placeholder.
 *
 * <p>Swap point: a later table (W2-A's standard macros) replaces {@link #table()}; the corpus test reads only this
 * method.
 *
 * <p>Sources, cited by the short names used in the comments:
 * <ul>
 * <li>[Iris MC_VERSION] https://shaders.properties/current/reference/macros/mc_version/ (122 format)</li>
 * <li>[Iris MC_GL_VERSION] https://shaders.properties/current/reference/macros/mc_gl_version/</li>
 * <li>[Iris MC_GLSL_VERSION] https://shaders.properties/current/reference/macros/mc_glsl_version/</li>
 * <li>[Iris macOS] https://shaders.properties/current/reference/miscellaneous/macos/ (no OpenGL past 4.1 on macOS)</li>
 * <li>[Iris Operating System] https://shaders.properties/current/reference/macros/operating_system/</li>
 * <li>[Iris Driver Vendor] https://shaders.properties/current/reference/macros/driver_vendor/</li>
 * <li>[Iris IS_IRIS] https://shaders.properties/current/reference/macros/is_iris/</li>
 * <li>[Iris IRIS_VERSION] https://shaders.properties/current/reference/macros/iris_version/ (122 format)</li>
 * <li>[Iris MC_HAND_DEPTH] https://shaders.properties/current/reference/macros/mc_hand_depth/</li>
 * <li>[Iris Render Stages] https://shaders.properties/current/reference/macros/render_stages/ (names)</li>
 * <li>[Iris Distant Horizons] https://shaders.properties/current/reference/mod-support/distant_horizons/ (DH_BLOCK names)</li>
 * <li>[OptiFine shaders.txt] https://raw.githubusercontent.com/sp614x/optifine/master/OptiFineDoc/doc/shaders.txt,
 * section "Standard Macros": A to J (render stage values are the list order: "given in the order in which the stages
 * are executed")</li>
 * <li>[Bench Iris] results/2026-10-26-pins-dims logs: "iris-fabric-1.11.7+mc26.3.jar" (the Iris the bench runs)</li>
 * </ul>
 */
final class CorpusMacros {

	private CorpusMacros() {
	}

	/** The macro table: name to value. Macros with no value are defined as "1". */
	static Map<String, String> table() {
		Map<String, String> m = new LinkedHashMap<>();
		// [Iris MC_VERSION]: 26.3 in the 122 format (major 26, minor 3, release 0).
		m.put("MC_VERSION", "260300");
		// [Iris MC_GL_VERSION] and [Iris macOS]: Apple's OpenGL stops at 4.1, so the maximum is 410.
		m.put("MC_GL_VERSION", "410");
		// [Iris MC_GLSL_VERSION]: the GLSL version of OpenGL 4.1 is 4.10 (macOS maximum, see above).
		m.put("MC_GLSL_VERSION", "410");
		// [Iris Operating System] and [OptiFine D]: one of MC_OS_WINDOWS, MC_OS_MAC, MC_OS_LINUX, MC_OS_OTHER.
		m.put("MC_OS_MAC", "1");
		// [Iris Driver Vendor] and [OptiFine E]: Apple is not in the vendor list, so OTHER applies.
		m.put("MC_GL_VENDOR_OTHER", "1");
		// [OptiFine F]: the renderer list has no Apple GPU name, so OTHER applies.
		m.put("MC_GL_RENDERER_OTHER", "1");
		// [OptiFine H]: values 0.5, 0.70710677, 1.0, 1.4142135, 2.0; 1.0 is the neutral setting (a choice).
		m.put("MC_RENDER_QUALITY", "1.0");
		m.put("MC_SHADOW_QUALITY", "1.0");
		// [Iris MC_HAND_DEPTH] and [OptiFine H]: values 0.0625, 0.125, 0.25; 0.125 is the middle value (a choice).
		m.put("MC_HAND_DEPTH", "0.125");
		// [OptiFine H]: "When the normal map is enabled". The bench runs with it: Vitrail compiles Bliss's
		// all_solid.vsh, which assigns FlatNormals unguarded, so the macro is defined there.
		m.put("MC_NORMAL_MAP", "1");
		// [Iris IS_IRIS]: defined when the shader is loaded by Iris.
		m.put("IS_IRIS", "1");
		// [Iris IRIS_VERSION]: 1.11.7 in the 122 format; the bench's Iris jar.
		m.put("IRIS_VERSION", "11107");
		// DISTANT_HORIZONS is not defined: the bench has no Distant Horizons mod, and the Vitrail run logs
		// "programs this place ships and does not run: dh_terrain (false), dh_water (false)".
		// [Iris Render Stages] names with [OptiFine J] values, the list order. TERRAIN_CUTOUT_MIPPED is in the
		// Iris list only, marked "currently unused by Iris"; no document gives its value, so -1 is a placeholder.
		String[] stages = {"NONE", "SKY", "SUNSET", "CUSTOM_SKY", "SUN", "MOON", "STARS", "VOID", "TERRAIN_SOLID",
				"TERRAIN_CUTOUT", "ENTITIES", "BLOCK_ENTITIES", "DESTROY", "OUTLINE", "DEBUG", "HAND_SOLID",
				"TERRAIN_TRANSLUCENT", "TRIPWIRE", "PARTICLES", "CLOUDS", "RAIN_SNOW", "WORLD_BORDER",
				"HAND_TRANSLUCENT"};
		for (int i = 0; i < stages.length; i++) {
			m.put("MC_RENDER_STAGE_" + stages[i], String.valueOf(i));
		}
		m.put("MC_RENDER_STAGE_TERRAIN_CUTOUT_MIPPED", "-1");
		// [Iris Distant Horizons]: the DH_BLOCK names; the values are DH 3.3.4's EDhApiBlockMaterial indices (javap), taken from
		// the engine's StandardMacros.DH_BLOCKS (DistantBlockIdsTest pins it to DH's enum).
		for (int i = 0; i < StandardMacros.DH_BLOCKS.size(); i++) {
			m.put("DH_BLOCK_" + StandardMacros.DH_BLOCKS.get(i), String.valueOf(i));
		}
		return Map.copyOf(m);
	}
}
