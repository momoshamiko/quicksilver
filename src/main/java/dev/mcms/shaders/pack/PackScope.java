// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.pack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.mcms.shaders.expr.ExprBinding;
import dev.mcms.shaders.expr.ExprScope;
import dev.mcms.shaders.expr.ExprType;

/**
 * The names an expression of a pack may use at load time (engine-pack-format.md section 13). A program rule
 * ({@code program.<name>.enabled}) sees the option switches and the standard macros, as booleans and ints: at load there is no
 * frame. A custom uniform or variable also sees the standard uniforms (frame values) and the pack's earlier definitions.
 */
final class PackScope implements ExprScope {
	/**
	 * The standard uniforms and parameters with their types (OptiFine doc shaders.properties, "Custom uniforms", and
	 * engine-pack-format.md section 10). The dynamic uniforms the doc forbids as parameters (entityColor, entityId,
	 * blockEntityId, fogMode, fogColor) are left out, so a pack that names one fails at load as the doc requires.
	 */
	static final Map<String, ExprType> STANDARD_FRAME = new LinkedHashMap<>();

	static {
		String[] floats = {"frameTime", "frameTimeCounter", "sunAngle", "shadowAngle", "rainStrength", "wetness",
			"thunderStrength", "screenBrightness", "aspectRatio", "viewWidth", "viewHeight", "near", "far", "alphaTestRef",
			"centerDepthSmooth", "eyeAltitude", "playerMood", "darknessFactor", "darknessLightFactor", "nightVision",
			"blindness", "fogStart", "fogEnd", "fogDensity", "endFlashIntensity", "currentPlayerHealth", "maxPlayerHealth",
			"currentPlayerArmor", "maxPlayerArmor", "currentPlayerAir", "maxPlayerAir", "currentPlayerHunger",
			"maxPlayerHunger", "ambientLight", "cloudHeight", "texelSizeX", "texelSizeY", "pixelSizeX", "pixelSizeY",
			"temperature", "rainfall", "biome", "biome_category", "biome_precipitation"};
		String[] ints = {"worldTime", "worldDay", "moonPhase", "frameCounter", "heldItemId", "heldItemId2",
			"heldBlockLightValue", "heldBlockLightValue2", "isEyeInWater", "renderStage", "hideGUI", "bossBattle",
			"instanceId", "dhRenderDistance", "currentRenderedItemId", "currentSelectedItemId"};
		String[] bools = {"is_alive", "is_burning", "is_child", "is_glowing", "is_hurt", "is_in_lava", "is_in_water",
			"is_invisible", "is_on_ground", "is_ridden", "is_riding", "is_sneaking", "is_sprinting", "is_wet"};
		String[] vec2 = {"eyeBrightness", "eyeBrightnessSmooth", "atlasSize"};
		String[] vec3 = {"cameraPosition", "previousCameraPosition", "sunPosition", "moonPosition", "shadowLightPosition",
			"upPosition", "skyColor", "relativeEyePosition", "eyePosition", "playerBodyVector", "playerLookVector",
			"modelOffset", "vehicleLookVector"};
		String[] vec4 = {"spriteBounds", "colorModulator", "lightningBoltPosition", "blendFunc"};
		String[] mat4 = {"gbufferModelView", "gbufferModelViewInverse", "gbufferProjection", "gbufferProjectionInverse",
			"gbufferPreviousModelView", "gbufferPreviousProjection", "shadowModelView", "shadowModelViewInverse",
			"shadowProjection", "shadowProjectionInverse", "dhProjection", "dhProjectionInverse", "dhPreviousProjection",
			"modelViewMatrix", "modelViewMatrixInverse", "projectionMatrix", "projectionMatrixInverse", "textureMatrix"};
		for (String n : floats) STANDARD_FRAME.put(n, ExprType.FLOAT);
		for (String n : ints) STANDARD_FRAME.put(n, ExprType.INT);
		for (String n : bools) STANDARD_FRAME.put(n, ExprType.BOOL);
		for (String n : vec2) STANDARD_FRAME.put(n, ExprType.VEC2);
		for (String n : vec3) STANDARD_FRAME.put(n, ExprType.VEC3);
		for (String n : vec4) STANDARD_FRAME.put(n, ExprType.VEC4);
		for (String n : mat4) STANDARD_FRAME.put(n, ExprType.MAT4);
		STANDARD_FRAME.put("normalMatrix", ExprType.MAT3);
	}

	private final Map<String, ExprBinding> names = new HashMap<>();
	private int slots;
	private final List<FrameLayout.Parameter> parameters = new ArrayList<>();

	private PackScope() {
	}

	@Override
	public ExprBinding resolve(String name) {
		return names.get(name);
	}

	/**
	 * Binds a frame value of the given type to the next slot, and returns the slot. Each call takes a new slot, so a later
	 * definition shadows an earlier one.
	 */
	int frame(String name, ExprType type) {
		int slot = slots++;
		names.put(name, new ExprBinding.Frame(slot, type));
		return slot;
	}

	/** Binds a standard parameter: a frame value that the layout lists by name, type and slot. */
	private void parameter(String name, ExprType type) {
		parameters.add(new FrameLayout.Parameter(name, type, frame(name, type)));
	}

	int slots() {
		return slots;
	}

	/** The parameters and the slot count, as E3 needs them to fill a frame. Custom values are in the slots after these. */
	FrameLayout layout() {
		return new FrameLayout(parameters, slots);
	}

	/** The scope of a program rule: the macros and the option switches. */
	static PackScope forPrograms(StandardMacros macros, OptionCatalog catalog, OptionValues values) {
		PackScope s = new PackScope();
		s.addMacros(macros);
		s.addOptions(catalog, values);
		return s;
	}

	/** The scope of a custom uniform or variable: the rule scope, the standard uniforms and the biome constants. */
	static PackScope forUniforms(StandardMacros macros, OptionCatalog catalog, OptionValues values) {
		PackScope s = forPrograms(macros, catalog, values);
		for (Map.Entry<String, ExprType> e : STANDARD_FRAME.entrySet()) s.parameter(e.getKey(), e.getValue());
		// Biome, category and precipitation constants (OptiFine doc: "defined as constants"): the biome uniforms' numbers (BiomeIds).
		List<String> biomes = BiomeIds.names();
		for (int k = 0; k < biomes.size(); k++) s.names.putIfAbsent("BIOME_" + biomes.get(k), new ExprBinding.ConstFloat(k));
		for (int k = 0; k < BiomeIds.CATEGORIES.size(); k++) {
			s.names.putIfAbsent("CAT_" + BiomeIds.CATEGORIES.get(k), new ExprBinding.ConstFloat(k));
		}
		s.names.putIfAbsent("PPT_NONE", new ExprBinding.ConstFloat(0));
		s.names.putIfAbsent("PPT_RAIN", new ExprBinding.ConstFloat(1));
		s.names.putIfAbsent("PPT_SNOW", new ExprBinding.ConstFloat(2));
		return s;
	}

	/**
	 * Macros as the preprocessor would see them: a defined flag is true, an undefined flag false, a defined number its value,
	 * and an undefined number 0 (an undefined macro is 0 in {@code #if}).
	 */
	private void addMacros(StandardMacros macros) {
		Map<String, String> defined = macros.predefined();
		for (String name : StandardMacros.DOCUMENTED) bindMacro(name, defined);
		for (String f : StandardMacros.IRIS_FEATURES) bindMacro("IRIS_FEATURE_" + f, defined);
	}

	private void bindMacro(String name, Map<String, String> defined) {
		if (defined.containsKey(name)) {
			String v = defined.get(name);
			if (StandardMacros.takesValue(name)) {
				names.put(name, numeric(v));
			} else {
				names.put(name, new ExprBinding.ConstBool(true));
			}
		} else {
			names.put(name, StandardMacros.takesValue(name) ? new ExprBinding.ConstInt(0) : new ExprBinding.ConstBool(false));
		}
	}

	private void addOptions(OptionCatalog catalog, OptionValues values) {
		for (PackOption o : catalog.options().values()) {
			String v = values.get(o.name());
			if (v == null) continue;
			if (o.kind() == OptionKind.BOOLEAN || "bool".equals(o.type())) {
				names.put(o.name(), new ExprBinding.ConstBool(v.equals("true")));
			} else {
				try {
					names.put(o.name(), new ExprBinding.ConstInt(Integer.parseInt(v)));
				} catch (NumberFormatException ex) {
					try {
						names.put(o.name(), new ExprBinding.ConstFloat(Float.parseFloat(v)));
					} catch (NumberFormatException ex2) {
						// A string value (not usable in an expression) stays unbound: a name in an expression then fails at load.
					}
				}
			}
		}
	}

	private static ExprBinding numeric(String v) {
		try {
			return new ExprBinding.ConstInt(Integer.parseInt(v));
		} catch (NumberFormatException ex) {
			return new ExprBinding.ConstFloat(Float.parseFloat(v));
		}
	}
}
