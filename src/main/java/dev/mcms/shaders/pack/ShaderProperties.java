// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.pack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The directives of {@code shaders.properties} (engine-pack-format.md section 5) as typed values. Keys this model does not
 * read are listed in {@link #unreadKeys()}. Profiles and screens keep their raw items here and are parsed by
 * {@link ProfileSet} and {@link ScreenTree}, which need the option catalog.
 */
public record ShaderProperties(
	Optional<Clouds> clouds,
	Map<String, Boolean> booleans,
	Optional<ShadowCulling> shadowCulling,
	Optional<ParticleOrdering> particleOrdering,
	Map<String, String> minimumVersions,
	Optional<String> noiseTexture,
	List<TextureBinding> textures,
	Map<String, AlphaTest> alphaTests,
	Map<String, BlendState> blends,
	Map<String, Scale> scales,
	Map<String, Boolean> flips,
	Map<String, BufferSize> bufferSizes,
	Map<String, String> programRules,
	List<CustomValueSource> customValues,
	List<String> sliders,
	Map<String, String> screens,
	Map<String, String> profiles,
	List<String> requiredFeatures,
	List<String> optionalFeatures,
	List<ImageBinding> images,
	List<BufferObject> bufferObjects,
	List<String> unreadKeys,
	List<String> problems) {

	/** Copies every collection, so the record cannot change after it is read. Maps keep their order (Map.copyOf does not). */
	public ShaderProperties {
		booleans = copyMap(booleans);
		minimumVersions = copyMap(minimumVersions);
		textures = List.copyOf(textures);
		alphaTests = copyMap(alphaTests);
		blends = copyMap(blends);
		scales = copyMap(scales);
		flips = copyMap(flips);
		bufferSizes = copyMap(bufferSizes);
		programRules = copyMap(programRules);
		customValues = List.copyOf(customValues);
		sliders = List.copyOf(sliders);
		screens = copyMap(screens);
		profiles = copyMap(profiles);
		requiredFeatures = List.copyOf(requiredFeatures);
		optionalFeatures = List.copyOf(optionalFeatures);
		images = List.copyOf(images);
		bufferObjects = List.copyOf(bufferObjects);
		unreadKeys = List.copyOf(unreadKeys);
		problems = List.copyOf(problems);
	}

	private static <K, V> Map<K, V> copyMap(Map<K, V> m) {
		return Collections.unmodifiableMap(new LinkedHashMap<>(m));
	}

	/** The feature flags the pack asks for, required and optional together (iris.features.*), each once, in file order. */
	public List<String> requestedFeatures() {
		Set<String> all = new LinkedHashSet<>(requiredFeatures);
		all.addAll(optionalFeatures);
		return List.copyOf(all);
	}

	/** Whether the pack turns the vanilla clouds off ({@code clouds=off}; fast and fancy do not). */
	public boolean disablesVanillaClouds() {
		return clouds.filter(c -> c == Clouds.OFF).isPresent();
	}

	/**
	 * The pack's {@code alphaTest} for a program: its own directive, else that of the program whose files run for it (a
	 * program the pack has no file for runs a fallback's files under its own name, as {@code gbuffers_terrain_cutout} runs
	 * {@code gbuffers_terrain}'s, and the fallback's directive belongs to those files), else empty. The blend directives
	 * take the same two names in the same order (GbufferPlans.blendOf).
	 */
	public Optional<AlphaTest> alphaTestOf(ResolvedProgram program) {
		AlphaTest own = alphaTests.get(program.name());
		return own != null ? Optional.of(own) : program.servedBy().map(alphaTests::get);
	}

	public enum Clouds { FAST, FANCY, OFF }

	public enum ShadowCulling { FALSE, TRUE, REVERSED }

	public enum ParticleOrdering { AFTER, BEFORE, MIXED }

	/** The raw form of a texture: target ({@code TEXTURE_2D}...), internal format, dimensions, pixel format and type. */
	public record RawTexture(String target, String internalFormat, List<Integer> dimensions, String pixelFormat, String pixelType) {
		public RawTexture {
			dimensions = List.copyOf(dimensions);
		}
	}

	/**
	 * A texture bound to a unit ({@code texture.<stage>.<unit>[.n]}), or a new sampler ({@code customTexture.<name>[.n]}).
	 *
	 * @param stage the stage (gbuffers, deferred, composite, ...); empty for a custom texture
	 * @param unit  the unit name (colortex1, shadowcolor0, noise, ...), or the custom texture's name
	 * @param index the {@code .n} suffix, 0 when absent
	 * @param raw   the raw form, when the value has more than a path
	 */
	public record TextureBinding(String stage, String unit, int index, boolean custom, String path, Optional<RawTexture> raw) {
	}

	/** {@code alphaTest.<program>}: off, or a function and reference value. */
	public record AlphaTest(boolean off, String func, double ref) {
	}

	/**
	 * {@code blend.<program>[.<buffer>]}: {@code off}, or the factors. Two factors name the colour pair alone and
	 * mean the same pair for the alpha equation ({@code glBlendFunc(src, dst)}); four are the documented full form
	 * (OF-doc {@code <off|src dst srcA dstA>}). Factor names are case-insensitive and stored upper-case; anything
	 * else is a load problem. The two-factor form is measured behaviour: Vitrail's picture of a pack using it keeps
	 * the blended target at its clear colour (2026-11-14-e4-preview12), where this engine dropped the directive and
	 * blended nothing.
	 */
	public record BlendState(boolean off, String src, String dst, String srcAlpha, String dstAlpha, String op,
			String alphaOp) {
		/** A directive's blend: the documented form names no equation, so both are ADD. */
		public BlendState(boolean off, String src, String dst, String srcAlpha, String dstAlpha) {
			this(off, src, dst, srcAlpha, dstAlpha, "ADD", "ADD");
		}
	}

	/** {@code scale.<program>}: the scale and the offsets (0 when absent). */
	public record Scale(double scale, double offsetX, double offsetY) {
	}

	/** {@code size.buffer.<buffer>}: fixed sizes, or fractions of the render size when the text had a decimal point. */
	public record BufferSize(double width, double height, boolean relative) {
	}

	/**
	 * {@code image.<name>} (Iris): the custom image of section 14. {@code x} and {@code y} are pixels, or factors of the render
	 * size when {@code relative} is true (the documentation's {@code 0.5} examples are fractional factors).
	 */
	public record ImageBinding(String name, String sampler, String format, String internalFormat, String pixelType,
		boolean clearEachFrame, boolean relative, double x, double y, Optional<Integer> z) {
	}

	/** {@code bufferObject.<index>} (Iris): an SSBO, sized in bytes, or screen-relative, or loaded from a file. */
	public record BufferObject(int index, long bytes, Optional<String> file, boolean relative, double scaleX, double scaleY) {
	}

	/**
	 * A {@code uniform.<type>.<name>} or {@code variable.<type>.<name>} line, in file order. Compiled by the pack model.
	 */
	public record CustomValueSource(boolean variable, String typeName, String name, String expression, String key) {
	}

	/** Every boolean directive of section 5 (the "true|false" ones). */
	public static final Set<String> BOOLEAN_KEYS = Set.of(
		"oldHandLight", "dynamicHandLight", "oldLighting", "shadowTerrain", "shadowTranslucent", "shadowEntities",
		"shadowBlockEntities", "shadowPlayer", "shadowLightBlockEntities", "shadow.enabled", "dhShadow.enabled",
		"underwaterOverlay", "sun", "moon", "vignette", "sky", "stars", "weather", "endFlashShadows",
		"supportsColorCorrection", "backFace.solid", "backFace.cutout", "backFace.translucent", "rain.depth",
		"beacon.beam.depth", "separateAo", "frustum.culling", "occlusion.culling", "particles.before.deferred",
		"separateEntityDraws", "skipAllRendering", "voxelizeLightBlocks", "indirect.pass", "allowConcurrentCompute");

	private static final Set<String> FUNCS = Set.of("NEVER", "LESS", "EQUAL", "LEQUAL", "GREATER", "NOTEQUAL", "GEQUAL",
		"GL_ALWAYS");
	private static final Set<String> FACTORS = Set.of("ZERO", "ONE", "SRC_COLOR", "ONE_MINUS_SRC_COLOR", "DST_COLOR",
		"ONE_MINUS_DST_COLOR", "SRC_ALPHA", "ONE_MINUS_SRC_ALPHA", "DST_ALPHA", "ONE_MINUS_DST_ALPHA", "SRC_ALPHA_SATURATE");
	private static final Set<String> TARGETS = Set.of("TEXTURE_1D", "TEXTURE_2D", "TEXTURE_3D", "TEXTURE_RECTANGLE");

	/** Reads the typed directives from the parsed key=value lines. */
	public static ShaderProperties from(PropertiesText.Parsed parsed) {
		List<String> problems = new ArrayList<>(parsed.problems());
		Map<String, String> last = parsed.lastWins();
		Optional<Clouds> clouds = Optional.empty();
		Map<String, Boolean> booleans = new LinkedHashMap<>();
		Optional<ShadowCulling> culling = Optional.empty();
		Optional<ParticleOrdering> ordering = Optional.empty();
		Map<String, String> versions = new LinkedHashMap<>();
		Optional<String> noise = Optional.empty();
		List<TextureBinding> textures = new ArrayList<>();
		Map<String, AlphaTest> alphas = new LinkedHashMap<>();
		Map<String, BlendState> blends = new LinkedHashMap<>();
		Map<String, Scale> scales = new LinkedHashMap<>();
		Map<String, Boolean> flips = new LinkedHashMap<>();
		Map<String, BufferSize> sizes = new LinkedHashMap<>();
		Map<String, String> rules = new LinkedHashMap<>();
		List<String> sliders = List.of();
		Map<String, String> screens = new LinkedHashMap<>();
		Map<String, String> profiles = new LinkedHashMap<>();
		List<String> required = List.of();
		List<String> optional = List.of();
		List<ImageBinding> images = new ArrayList<>();
		List<BufferObject> buffers = new ArrayList<>();
		List<String> unread = new ArrayList<>();

		for (Map.Entry<String, String> e : last.entrySet()) {
			String k = e.getKey();
			String v = e.getValue();
			if (k.equals("clouds")) {
				clouds = switch (v) {
					case "fast" -> Optional.of(Clouds.FAST);
					case "fancy" -> Optional.of(Clouds.FANCY);
					case "off" -> Optional.of(Clouds.OFF);
					default -> bad(problems, k, v, "fast, fancy or off", clouds);
				};
			} else if (BOOLEAN_KEYS.contains(k)) {
				Optional<Boolean> b = bool(v);
				if (b.isPresent()) booleans.put(k, b.get());
				else problems.add(k + "=" + v + ": must be true or false");
			} else if (k.equals("shadow.culling")) {
				culling = switch (v) {
					case "true" -> Optional.of(ShadowCulling.TRUE);
					case "false" -> Optional.of(ShadowCulling.FALSE);
					case "reversed" -> Optional.of(ShadowCulling.REVERSED);
					default -> bad(problems, k, v, "true, false or reversed", culling);
				};
			} else if (k.equals("particles.ordering")) {
				ordering = switch (v) {
					case "after" -> Optional.of(ParticleOrdering.AFTER);
					case "before" -> Optional.of(ParticleOrdering.BEFORE);
					case "mixed" -> Optional.of(ParticleOrdering.MIXED);
					default -> bad(problems, k, v, "after, before or mixed", ordering);
				};
			} else if (k.startsWith("version.")) {
				versions.put(k.substring("version.".length()), v);
			} else if (k.equals("texture.noise")) {
				noise = Optional.of(v);
			} else if (k.startsWith("texture.")) {
				textureBinding(k, k.substring("texture.".length()), v, false, problems).ifPresent(textures::add);
			} else if (k.startsWith("customTexture.")) {
				textureBinding(k, k.substring("customTexture.".length()), v, true, problems).ifPresent(textures::add);
			} else if (k.startsWith("alphaTest.")) {
				alphaTest(k, v, problems).ifPresent(a -> alphas.put(k.substring("alphaTest.".length()), a));
			} else if (k.startsWith("blend.")) {
				blend(k, v, problems).ifPresent(b -> blends.put(k.substring("blend.".length()), b));
			} else if (k.startsWith("scale.")) {
				scale(k, v, problems).ifPresent(s -> scales.put(k.substring("scale.".length()), s));
			} else if (k.startsWith("flip.")) {
				Optional<Boolean> b = bool(v);
				if (b.isPresent()) flips.put(k.substring("flip.".length()), b.get());
				else problems.add(k + "=" + v + ": must be true or false");
			} else if (k.startsWith("size.buffer.")) {
				bufferSize(k, v, problems).ifPresent(s -> sizes.put(k.substring("size.buffer.".length()), s));
			} else if (k.startsWith("program.") && k.endsWith(".enabled")) {
				rules.put(k.substring("program.".length(), k.length() - ".enabled".length()), v);
			} else if (k.equals("sliders")) {
				sliders = split(v);
			} else if (k.equals("screen") || k.startsWith("screen.")) {
				screens.put(k, v);
			} else if (k.startsWith("profile.")) {
				profiles.put(k.substring("profile.".length()), v);
			} else if (k.equals("iris.features.required")) {
				required = features(v, problems, k);
			} else if (k.equals("iris.features.optional")) {
				optional = features(v, problems, k);
			} else if (k.startsWith("image.")) {
				image(k, v, problems).ifPresent(images::add);
			} else if (k.startsWith("bufferObject.")) {
				bufferObject(k, v, problems).ifPresent(buffers::add);
			} else if (!k.startsWith("uniform.") && !k.startsWith("variable.")) {
				unread.add(k);
			}
		}
		List<CustomValueSource> custom = new ArrayList<>();
		for (PropertiesText.Entry en : parsed.entries()) {
			if (!en.key().startsWith("uniform.") && !en.key().startsWith("variable.")) continue;
			String[] parts = en.key().split("\\.", -1);
			if (parts.length != 3) {
				problems.add("line " + en.line() + ": expected " + parts[0] + ".<type>.<name>, got " + en.key());
				continue;
			}
			custom.add(new CustomValueSource(parts[0].equals("variable"), parts[1], parts[2], en.value(), en.key()));
		}
		return new ShaderProperties(clouds, booleans, culling, ordering, versions, noise, textures, alphas, blends, scales,
			flips, sizes, rules, custom, sliders, screens, profiles, required, optional, images, buffers, unread, problems);
	}

	private static <T> Optional<T> bad(List<String> problems, String key, String value, String allowed, Optional<T> keep) {
		problems.add(key + "=" + value + ": expected " + allowed);
		return keep;
	}

	private static Optional<Boolean> bool(String v) {
		if (v.equalsIgnoreCase("true")) return Optional.of(true);
		if (v.equalsIgnoreCase("false")) return Optional.of(false);
		return Optional.empty();
	}

	private static List<String> split(String v) {
		List<String> out = new ArrayList<>();
		for (String t : v.strip().split("\\s+")) {
			if (!t.isEmpty()) out.add(t);
		}
		return out;
	}

	private static List<String> features(String v, List<String> problems, String key) {
		List<String> out = split(v);
		for (String f : out) {
			if (!StandardMacros.IRIS_FEATURES.contains(f)) problems.add(key + ": unknown feature flag " + f);
		}
		return out;
	}

	private static Optional<TextureBinding> textureBinding(String key, String rest, String value, boolean custom,
		List<String> problems) {
		String[] parts = rest.split("\\.");
		String stage = "";
		String unit;
		int index = 0;
		if (custom) {
			unit = parts[0];
			if (parts.length > 1 && parts[1].matches("\\d")) index = Integer.parseInt(parts[1]);
		} else {
			if (parts.length < 2) {
				problems.add(key + ": expected texture.<stage>.<unit>");
				return Optional.empty();
			}
			stage = parts[0];
			unit = parts[1];
			if (parts.length > 2 && parts[2].matches("\\d")) index = Integer.parseInt(parts[2]);
		}
		String[] tokens = value.split("\\s+");
		Optional<RawTexture> raw = Optional.empty();
		if (tokens.length > 1) {
			if (tokens.length < 6 || !TARGETS.contains(tokens[1])) {
				problems.add(key + "=" + value + ": raw texture needs target, format, dimensions, pixel format and type");
				return Optional.empty();
			}
			List<Integer> dims = new ArrayList<>();
			try {
				for (int i = 3; i < tokens.length - 2; i++) dims.add(Integer.parseInt(tokens[i]));
			} catch (NumberFormatException ex) {
				problems.add(key + ": dimensions must be integers");
				return Optional.empty();
			}
			raw = Optional.of(new RawTexture(tokens[1], tokens[2], dims, tokens[tokens.length - 2], tokens[tokens.length - 1]));
		}
		return Optional.of(new TextureBinding(stage, unit, index, custom, tokens[0], raw));
	}

	private static Optional<AlphaTest> alphaTest(String key, String value, List<String> problems) {
		// The docs name "off" only; Bliss and Mellow write "false" here, so it is read as off (observed in those packs).
		if (value.equals("off") || value.equals("false")) return Optional.of(new AlphaTest(true, "", 0));
		String[] t = value.split("\\s+");
		if (t.length != 2 || !FUNCS.contains(t[0])) {
			problems.add(key + "=" + value + ": expected off or <func> <ref>");
			return Optional.empty();
		}
		try {
			return Optional.of(new AlphaTest(false, t[0], Double.parseDouble(t[1])));
		} catch (NumberFormatException ex) {
			problems.add(key + ": reference is not a number");
			return Optional.empty();
		}
	}

	private static Optional<BlendState> blend(String key, String value, List<String> problems) {
		if (value.equals("off")) return Optional.of(new BlendState(true, "", "", "", ""));
		String[] t = value.split("\\s+");
		// Factor names are case-insensitive (packs write "zero src_color"; the documented names are upper case,
		// OF-doc "blend.<program>[.<buffer>]"), and the stored names are the upper-case ones the blend-factor
		// lookups switch on.
		for (int i = 0; i < t.length; i++) {
			t[i] = t[i].toUpperCase(java.util.Locale.ROOT);
		}
		// Two factors name the colour pair alone: the alpha pair is the same (glBlendFunc(src, dst), the GL form
		// the directive mirrors; Metal's per-component factors give the same equation — sourceColor contributes
		// the source alpha there, as GL's substitution rules do).
		if (t.length == 2) {
			t = new String[] {t[0], t[1], t[0], t[1]};
		}
		if (t.length != 4 || !FACTORS.containsAll(Arrays.asList(t))) {
			problems.add(key + "=" + value + ": expected off or two or four blend factors");
			return Optional.empty();
		}
		return Optional.of(new BlendState(false, t[0], t[1], t[2], t[3]));
	}

	private static Optional<Scale> scale(String key, String value, List<String> problems) {
		String[] t = value.split("\\s+");
		try {
			double s = Double.parseDouble(t[0]);
			double x = t.length > 1 ? Double.parseDouble(t[1]) : 0;
			double y = t.length > 2 ? Double.parseDouble(t[2]) : 0;
			return Optional.of(new Scale(s, x, y));
		} catch (NumberFormatException ex) {
			problems.add(key + "=" + value + ": expected numbers");
			return Optional.empty();
		}
	}

	private static Optional<BufferSize> bufferSize(String key, String value, List<String> problems) {
		String[] t = value.split("\\s+");
		if (t.length != 2) {
			problems.add(key + "=" + value + ": expected width and height");
			return Optional.empty();
		}
		try {
			boolean relative = t[0].contains(".") || t[1].contains(".");
			return Optional.of(new BufferSize(Double.parseDouble(t[0]), Double.parseDouble(t[1]), relative));
		} catch (NumberFormatException ex) {
			problems.add(key + "=" + value + ": expected numbers");
			return Optional.empty();
		}
	}

	private static Optional<ImageBinding> image(String key, String value, List<String> problems) {
		String[] t = value.split("\\s+");
		if (t.length != 8 && t.length != 9) {
			problems.add(key + "=" + value + ": expected sampler, format, internal format, pixel type, clear, relative, x, y [, z]");
			return Optional.empty();
		}
		Optional<Boolean> clear = bool(t[4]);
		Optional<Boolean> relative = bool(t[5]);
		if (clear.isEmpty() || relative.isEmpty()) {
			problems.add(key + ": clear and relative must be true or false");
			return Optional.empty();
		}
		try {
			double x = Double.parseDouble(t[6]);
			double y = Double.parseDouble(t[7]);
			Optional<Integer> z = t.length == 9 ? Optional.of(Integer.parseInt(t[8])) : Optional.empty();
			if (!relative.get() && (x != Math.rint(x) || y != Math.rint(y))) {
				problems.add(key + ": x and y are whole pixels unless relative is true");
				return Optional.empty();
			}
			return Optional.of(new ImageBinding(key.substring("image.".length()), t[0], t[1], t[2], t[3], clear.get(),
				relative.get(), x, y, z));
		} catch (NumberFormatException ex) {
			problems.add(key + ": x, y and z must be numbers (z an integer)");
			return Optional.empty();
		}
	}

	private static Optional<BufferObject> bufferObject(String key, String value, List<String> problems) {
		String[] t = value.split("\\s+");
		try {
			int index = Integer.parseInt(key.substring("bufferObject.".length()));
			long bytes = Long.parseLong(t[0]);
			return switch (t.length) {
				case 1 -> Optional.of(new BufferObject(index, bytes, Optional.empty(), false, 0, 0));
				case 2 -> Optional.of(new BufferObject(index, bytes, Optional.of(t[1]), false, 0, 0));
				case 4 -> {
					Optional<Boolean> rel = bool(t[1]);
					if (rel.isEmpty()) {
						problems.add(key + ": isRelative must be true or false");
						yield Optional.empty();
					}
					yield Optional.of(new BufferObject(index, bytes, Optional.empty(), rel.get(),
						Double.parseDouble(t[2]), Double.parseDouble(t[3])));
				}
				default -> {
					problems.add(key + "=" + value + ": expected bytes, bytes and file, or bytes, isRelative, scaleX, scaleY");
					yield Optional.empty();
				}
			};
		} catch (NumberFormatException ex) {
			problems.add(key + "=" + value + ": expected numbers");
			return Optional.empty();
		}
	}
}
