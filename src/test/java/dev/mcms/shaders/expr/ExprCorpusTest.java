// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.expr;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every custom uniform, custom variable and {@code program.*.enabled} expression of the eight corpus packs compiles
 * with its declared type and evaluates once on a zero frame without throwing. Names resolve the way the runtime
 * resolves them: the standard uniforms (GLSL types from engine-pack-format.md section 10 and the OptiFine doc's
 * parameter list), the switch options the pack defines with {@code #define}, and the pack's own uniforms and variables
 * in file order, so a name must be defined earlier in shaders.properties than it is used. The expression count per
 * pack is pinned, so a pack that loses or gains expressions fails the test. The packs are read from
 * {@code -Pcorpus=<dir>} at run time and never committed; only identifiers are read, never pack code
 * (docs/research/engine-provenance.md).
 */
@Tag("corpus")
class ExprCorpusTest {
	private static final Pattern DEFINE = Pattern.compile("^\\s*(?://\\s*)?#define\\s+([A-Za-z_]\\w*)", Pattern.MULTILINE);

	/** Expressions per pack (uniform, variable and program switch lines), as observed on the eight corpus packs. */
	private static final Map<String, Integer> EXPECTED = Map.of(
		"BSL_v10.1.8.zip", 65,
		"Bliss_v2.1.2_(Chocapic13_Shaders_edit).zip", 109,
		"ComplementaryReimagined_r5.9.3.zip", 39,
		"ComplementaryUnbound_r5.9.3.zip", 39,
		"MakeUp-UltraFast-9.5g.zip", 36,
		"Mellow v3.4.1a.zip", 84,
		"Solas Shader V3.8.zip", 35,
		"photon_v1.4.zip", 161);

	@Test
	void everyCorpusExpressionCompilesAndEvaluates() throws IOException {
		Path dir = Path.of(System.getProperty("mcms.test.corpus", ""));
		Assumptions.assumeTrue(Files.isDirectory(dir), "no corpus folder: " + dir);
		List<Path> zips;
		try (var s = Files.list(dir)) {
			zips = s.filter(p -> p.toString().endsWith(".zip")).sorted().toList();
		}
		assertEquals(EXPECTED.keySet(), new java.util.HashSet<>(names(zips)), "the corpus folder holds the eight packs");
		List<String> failures = new ArrayList<>();
		int total = 0;
		for (Path zip : zips) {
			String name = zip.getFileName().toString();
			int count = 0;
			try (ZipFile zf = new ZipFile(zip.toFile())) {
				String properties = null;
				String propertiesName = null;
				List<String> options = new ArrayList<>();
				Enumeration<? extends ZipEntry> entries = zf.entries();
				while (entries.hasMoreElements()) {
					ZipEntry entry = entries.nextElement();
					if (entry.isDirectory() || !entry.getName().contains("shaders/")) {
						continue;
					}
					String text = new String(zf.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8);
					if (entry.getName().endsWith("shaders.properties")) {
						properties = text;
						propertiesName = entry.getName();
					} else if (!entry.getName().endsWith(".lang")) {
						Matcher m = DEFINE.matcher(text);
						while (m.find()) {
							options.add(m.group(1));
						}
					}
				}
				if (properties == null) {
					failures.add(name + ": no shaders.properties");
					continue;
				}
				PackScope scope = new PackScope();
				standardNames(scope);
				for (String option : options) {
					scope.bindIfAbsent(option, new ExprBinding.ConstBool(true));
				}
				for (String line : logicalLines(properties)) {
					int eq = line.indexOf('=');
					if (eq <= 0) {
						continue;
					}
					String key = line.substring(0, eq).trim();
					String value = cleanValue(line.substring(eq + 1).trim());
					ExprType declared = declaredType(key);
					if (declared == null) {
						continue;
					}
					count++;
					String where = name + " " + propertiesName + " " + key + " = " + value;
					try {
						CompiledExpr compiled = ExprCompiler.compile(value, scope, declared);
						evaluateOnZeroFrame(compiled, scope);
					} catch (ExprError e) {
						failures.add(where + " -> " + e.getMessage());
					} catch (RuntimeException e) {
						failures.add(where + " -> evaluation threw " + e);
					}
					if (key.startsWith("uniform.") || key.startsWith("variable.")) {
						scope.bind(key.split("\\.")[2], declared); // defined for the lines after this one
					}
				}
			} catch (IOException e) {
				failures.add(name + ": " + e);
			}
			assertEquals(EXPECTED.get(name), (Integer) count, name + ": expression count");
			total += count;
		}
		assertEquals(568, total, "expressions across the eight packs");
		assertTrue(failures.isEmpty(), failures.size() + " corpus expressions failed:\n" + String.join("\n", failures));
	}

	private static List<String> names(List<Path> zips) {
		List<String> names = new ArrayList<>();
		for (Path zip : zips) {
			names.add(zip.getFileName().toString());
		}
		return names;
	}

	/**
	 * Evaluates once on a frame of zeros, sized for the scope so every slot exists. Throwing on this frame is a bug:
	 * the runtime evaluates these every frame.
	 */
	private static void evaluateOnZeroFrame(CompiledExpr compiled, PackScope scope) {
		ExprFrame frame = new ExprFrame();
		frame.ensureSlots(scope.slotCount());
		ExprEval eval = new ExprEval(frame, compiled.vectorScratch());
		compiled.requireScratch(eval);
		switch (compiled.type()) {
			case FLOAT -> compiled.evalF(eval);
			case INT -> compiled.evalI(eval);
			case BOOL -> compiled.evalB(eval);
			case VEC2, VEC3, VEC4 -> compiled.evalV(eval, new float[4], 0);
			case MAT3, MAT4 -> throw new AssertionError("an expression never has a matrix type");
		}
	}

	/** The declared type of a key: uniform.<type>.<name>, variable.<type>.<name>, program.<name>.enabled (bool). */
	private static ExprType declaredType(String key) {
		if (key.startsWith("program.") && key.endsWith(".enabled")) {
			return ExprType.BOOL;
		}
		if (key.startsWith("uniform.") || key.startsWith("variable.")) {
			String[] parts = key.split("\\.");
			if (parts.length != 3) {
				return null;
			}
			return switch (parts[1]) {
				case "float" -> ExprType.FLOAT;
				case "int" -> ExprType.INT;
				case "bool" -> ExprType.BOOL;
				case "vec2" -> ExprType.VEC2;
				case "vec3" -> ExprType.VEC3;
				case "vec4" -> ExprType.VEC4;
				default -> null;
			};
		}
		return null;
	}

	/** A value cleaned the way the runtime receives it after the properties preprocessor. */
	private static String cleanValue(String value) {
		// A preprocessor marker that leaked into a joined value (one pack writes the #else of an inactive branch behind
		// the expression) ends the expression here; the runtime meets these values after that preprocessor.
		int hash = value.indexOf(" #");
		return hash >= 0 ? value.substring(0, hash).trim() : value;
	}

	/** Names every pack may use before its own definitions: standard uniforms, parameters and constants. */
	private static void standardNames(PackScope scope) {
		// Parameters (float) of the OptiFine doc's Custom uniforms section: biome, biome_category, biome_precipitation,
		// temperature, rainfall; and the float uniforms of engine-pack-format.md section 10 (screen, time, weather, fog).
		for (String n : ("biome biome_category biome_precipitation temperature rainfall"
			+ " frameTime frameTimeCounter sunAngle shadowAngle rainStrength wetness thunderStrength"
			+ " screenBrightness aspectRatio viewWidth viewHeight near far alphaTestRef centerDepthSmooth eyeAltitude"
			+ " playerMood darknessFactor darknessLightFactor nightVision blindness fogStart fogEnd fogDensity"
			+ " endFlashIntensity currentPlayerHealth maxPlayerHealth"
			+ " currentPlayerArmor maxPlayerArmor currentPlayerAir maxPlayerAir currentPlayerHunger maxPlayerHunger"
			+ " ambientLight cloudHeight texelSizeX texelSizeY pixelSizeX pixelSizeY").split(" ")) {
			scope.bind(n, ExprType.FLOAT);
		}
		// IS_IRIS is defined when an Iris-family loader runs (engine-pack-format.md section 3); the engine's macro values
		// are W2-A's, so this test only mirrors the documented name.
		scope.bindIfAbsent("IS_IRIS", new ExprBinding.ConstBool(true));
		// Fixed scalars the doc lists as int (worldTime, frameCounter, heldItemId, ...).
		for (String n : ("worldTime worldDay moonPhase frameCounter heldItemId heldItemId2 heldBlockLightValue"
			+ " heldBlockLightValue2 isEyeInWater fogMode fogShape entityId blockEntityId renderStage hideGUI"
			+ " bossBattle instanceId dhRenderDistance currentRenderedItemId currentSelectedItemId").split(" ")) {
			scope.bind(n, ExprType.INT);
		}
		// Entity booleans of the OptiFine doc's Parameters (boolean) list.
		for (String n : ("is_alive is_burning is_child is_glowing is_hurt is_in_lava is_in_water is_invisible"
			+ " is_on_ground is_ridden is_riding is_sneaking is_sprinting is_wet").split(" ")) {
			scope.bind(n, ExprType.BOOL);
		}
		for (String n : ("cameraPosition previousCameraPosition sunPosition moonPosition shadowLightPosition"
			+ " upPosition fogColor skyColor relativeEyePosition eyePosition playerBodyVector playerLookVector").split(" ")) {
			scope.bind(n, ExprType.VEC3);
		}
		for (String n : ("eyeBrightness eyeBrightnessSmooth spriteBounds entityColor lightningBoltPosition"
			+ " colorModulator blendFunc").split(" ")) {
			scope.bind(n, ExprType.VEC4);
		}
		for (String n : ("gbufferModelView gbufferModelViewInverse gbufferProjection gbufferProjectionInverse"
			+ " gbufferPreviousModelView gbufferPreviousProjection shadowModelView shadowModelViewInverse"
			+ " shadowProjection shadowProjectionInverse dhProjection").split(" ")) {
			scope.bind(n, ExprType.MAT4);
		}
		// Biome, category and precipitation constants (OptiFine doc: "The available biome ids, categories and
		// precipitation types are defined as constants"): the engine's own (BiomeIds), so the corpus compiles against them.
		java.util.List<String> biomes = dev.mcms.shaders.pack.BiomeIds.names();
		for (int k = 0; k < biomes.size(); k++) {
			scope.bindIfAbsent("BIOME_" + biomes.get(k), new ExprBinding.ConstFloat(k));
		}
		java.util.List<String> categories = dev.mcms.shaders.pack.BiomeIds.CATEGORIES;
		for (int k = 0; k < categories.size(); k++) {
			scope.bindIfAbsent("CAT_" + categories.get(k), new ExprBinding.ConstFloat(k));
		}
		scope.bindIfAbsent("PPT_NONE", new ExprBinding.ConstFloat(0));
		scope.bindIfAbsent("PPT_RAIN", new ExprBinding.ConstFloat(1));
		scope.bindIfAbsent("PPT_SNOW", new ExprBinding.ConstFloat(2));
	}

	/** A name table for one pack: each binding gets its own frame slot; definitions in file order replace earlier ones. */
	private static final class PackScope implements ExprScope {
		private final Map<String, ExprBinding> names = new HashMap<>();
		private int slots;

		@Override
		public ExprBinding resolve(String name) {
			return names.get(name);
		}

		void bind(String name, ExprType type) {
			names.put(name, new ExprBinding.Frame(slots++, type));
		}

		void bindIfAbsent(String name, ExprBinding binding) {
			names.putIfAbsent(name, binding);
		}

		int slotCount() {
			return slots;
		}
	}

	/** Joins properties continuation lines (a single backslash at the end) and drops comments and blanks. */
	private static List<String> logicalLines(String text) {
		List<String> lines = new ArrayList<>();
		String pending = null;
		for (String raw : text.split("\n")) {
			String line = raw.stripTrailing().replace("\r", "");
			if (pending != null) {
				line = pending + line;
				pending = null;
			}
			if (line.endsWith("\\") && !line.endsWith("\\\\")) {
				pending = line.substring(0, line.length() - 1);
				continue;
			}
			line = line.stripLeading();
			if (!line.isEmpty() && !line.startsWith("#")) {
				lines.add(line);
			}
		}
		return lines;
	}
}
