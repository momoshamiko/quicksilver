// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.pack.textures;

import dev.mcms.glsl.msl.ProgramRequest.Filter;
import dev.mcms.glsl.msl.ProgramRequest.Wrap;
import dev.mcms.shaders.pack.ColourNames;
import dev.mcms.shaders.pack.PackSource;
import dev.mcms.shaders.pack.ShaderProperties;
import dev.mcms.shaders.pack.ShaderProperties.RawTexture;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The pack's custom textures (E6-B): {@code texture.<stage>.<unit>[.n]} (OptiFine's form, which replaces the sampler's
 * binding in the stage's programs), {@code customTexture.<name>[.n]} (Iris's form, a new sampler), and {@code texture.noise}.
 * Each binding is classified by its source and read once at load: a shader pack file is decoded here (a PNG to RGBA8, a
 * raw file to its internal format), a {@code minecraft:} source is named and read by the render side (the game's resources
 * need the game's resource manager), and the lightmap, the atlas and the default normal and specular atlases are bound to
 * the game's current views or to engine defaults. A binding whose internal format the engine does not store is refused
 * here, at load, so the program's sampler keeps the engine's own source and no custom sampler state.
 *
 * <p>Rules (shaders.properties "Custom textures", Iris's custom textures and image format pages; engine-pack-format.md
 * section 7):
 * <ul>
 * <li>Stages: {@code setup}, {@code begin}, {@code shadowcomp}, {@code prepare}, {@code gbuffers} (also the shadow
 *     programs), {@code deferred}, {@code composite}. The OptiFine page's {@code composite} stage covers the composite and
 *     final programs, and Iris binds a composite texture in {@code final} too: the engine's final programs take them.
 * <li>A 2D binding of a stage on a target's own sampler ({@code texture.composite.colortex6}) holds in a full-screen stage
 *     only until a program of that stage writes the target: the writer itself still reads the binding, every later program
 *     of the stage (final after the composites) reads what was written, and the next frame's stage reads the binding again
 *     ({@link #targetReplaces}; measured on Iris 1.11.7 with the mcms-texover test pack, E7). Bliss 2.1.2 relies on it: its
 *     colortex6 is a blue noise for the first composite passes and its bloom chain from the pass that writes it on. Not
 *     measured, and kept to the plain reading: a writer whose {@code flip.<program>.<target>} is false, a compute kernel
 *     that writes the target's image, and the gbuffers stage (whose programs flip nothing, so its bindings always hold).
 * <li>Filter and wrap: image files nearest and repeat; raw files linear and clamp; a standard {@code .mcmeta} beside a shader
 *     pack file overrides them ({@code "texture": {"blur": ..., "clamp": ...}}). Pack {@code .mcmeta} files do not apply
 *     to resource or atlas textures. The lightmap is linear and clamped (the game's own lightmap sampler). A rectangle
 *     texture is clamped (pixel addressing takes clamp-to-edge only).
 * <li>Several textures may share one unit with different types (suffix {@code .0} to {@code .9}); the program's sampler
 *     type picks one ({@code sampler1D} a 1D texture, {@code sampler3D} a 3D one, {@code sampler2DRect} a rectangle).
 * <li>{@code minecraft:textures/atlas/<name>.png} is an atlas of the game; {@code <name>_n.png} and {@code <name>_s.png} are
 *     its normal and specular atlases, which packs without PBR resources do not have: they bind the engine's default texel
 *     (the documented normal and specular defaults), which is the same at every coordinate.
 * <li>{@code texture.noise} replaces the engine's generated {@code noisetex}: a program's {@code noisetex} resolves to it
 *     when no stage binds the name, with its own filter and wrap.
 * </ul>
 */
public final class CustomTextureTable {

	/** Where a binding's texels come from. */
	public enum Source {
		/** A file in the shader pack, decoded at load (image or raw). */
		PACK_FILE,
		/** A {@code minecraft:} resource other than an atlas: read by the render side, the default when it is missing. */
		RESOURCE,
		/** An atlas of the game ({@code minecraft:textures/atlas/<name>.png}): the game's own atlas view, asked for per draw. */
		ATLAS,
		/** The lightmap ({@code minecraft:dynamic/lightmap_1} or {@code light_map_1}): the game's lightmap view, asked for per draw. */
		LIGHTMAP,
		/** An atlas's normal variant ({@code _n}) without PBR resources: the documented default normal texel. */
		DEFAULT_NORMAL,
		/** An atlas's specular variant ({@code _s}) without PBR resources: the documented default specular texel. */
		DEFAULT_SPECULAR
	}

	/** The texture types a binding names (the raw {@code <type>}; an image file is 2D). */
	public enum Dim { D1, D2, D3, RECT }

	/**
	 * A raw pack file's description and bytes: its size and the internal, pixel format and pixel type words as the pack wrote
	 * them. The runtime converts the bytes to the internal format when it creates the texture (TexelCodec).
	 */
	public record RawData(int width, int height, int depth, String internalFormat, String pixelFormat, String pixelType,
			byte[] bytes) {
	}

	/**
	 * One binding.
	 *
	 * @param stage  the stage ({@code ""} for {@code customTexture})
	 * @param unit   the sampler name the binding serves (colortexN for a target, canonical; the custom name for customTexture)
	 * @param index  the {@code .n} suffix
	 * @param source where the texels come from
	 * @param path   the path as the pack wrote it
	 * @param dim    the texture type
	 * @param filter the filter (minification and magnification)
	 * @param wrap   the address mode
	 * @param image  the decoded texels of an image pack file (PNG), null for every other source
	 * @param raw    the raw pack file's description and bytes, null for every other source
	 */
	public record Entry(String stage, String unit, int index, Source source, String path, Dim dim, Filter filter, Wrap wrap,
			@Nullable TextureImage image, @Nullable RawData raw) {
	}

	/** The stages a texture binds in; {@code final} programs take the composite textures (Iris, and OptiFine's composite stage). */
	public static final Set<String> STAGES = Set.of("setup", "begin", "shadowcomp", "prepare", "gbuffers", "deferred",
		"composite");

	private static final Pattern MCMETA_BLUR = Pattern.compile("\"blur\"\\s*:\\s*(true|false)");
	private static final Pattern MCMETA_CLAMP = Pattern.compile("\"clamp\"\\s*:\\s*(true|false)");

	private final Map<String, List<Entry>> units;
	private final Map<String, List<Entry>> customs;
	private final @Nullable Entry noise;
	private final List<String> problems;
	/** What each program's samplers resolved to, recorded as the requests are built (program, sampler, entry). */
	private final Map<String, Map<String, Entry>> bound = new ConcurrentHashMap<>();

	private CustomTextureTable(Map<String, List<Entry>> units, Map<String, List<Entry>> customs, @Nullable Entry noise,
			List<String> problems) {
		this.units = units;
		this.customs = customs;
		this.noise = noise;
		this.problems = List.copyOf(problems);
	}

	/** A pack with no custom textures. */
	public static CustomTextureTable empty() {
		return new CustomTextureTable(Map.of(), Map.of(), null, List.of());
	}

	/** The table of a pack's properties; pack files are read from {@code pack}. */
	public static CustomTextureTable of(ShaderProperties props, PackSource pack) {
		Map<String, List<Entry>> units = new HashMap<>();
		Map<String, List<Entry>> customs = new HashMap<>();
		List<String> problems = new ArrayList<>();
		for (ShaderProperties.TextureBinding b : props.textures()) {
			String where = (b.custom() ? "customTexture." : "texture.") + (b.custom() ? "" : b.stage() + ".") + b.unit()
				+ (b.index() == 0 ? "" : "." + b.index());
			if (!b.custom() && !STAGES.contains(b.stage())) {
				problems.add(where + ": no stage " + b.stage() + " (setup, begin, shadowcomp, prepare, gbuffers, deferred, composite)");
				continue;
			}
			Optional<Entry> entry = entryOf(b, where, pack, problems);
			if (entry.isEmpty()) {
				continue;
			}
			Entry e = entry.get();
			if (b.custom()) {
				customs.computeIfAbsent(e.unit(), k -> new ArrayList<>()).add(e);
			} else {
				units.computeIfAbsent(e.stage() + "|" + e.unit(), k -> new ArrayList<>()).add(e);
			}
		}
		Entry noise = null;
		Optional<String> noisePath = props.noiseTexture();
		if (noisePath.isPresent()) {
			// entryOf logs a missing or undecodable file; the engine then keeps its generated noise.
			ShaderProperties.TextureBinding b = new ShaderProperties.TextureBinding("", "noise", 0, false, noisePath.get(),
				Optional.empty());
			noise = entryOf(b, "texture.noise", pack, problems).filter(x -> x.image() != null).orElse(null);
		}
		return new CustomTextureTable(units, customs, noise, problems);
	}

	/** The source and the decoded texels of one binding, or empty with the reason in {@code problems}. */
	private static Optional<Entry> entryOf(ShaderProperties.TextureBinding b, String where, PackSource pack,
			List<String> problems) {
		String path = b.path();
		String stage = b.custom() ? "" : b.stage();
		String unit = canonicalUnit(b.unit());
		if (path.startsWith("minecraft:")) {
			if (b.raw().isPresent()) {
				problems.add(where + ": raw textures come from the shader pack's files, not from " + path);
				return Optional.empty();
			}
			String rest = path.substring("minecraft:".length());
			if (rest.equals("dynamic/lightmap_1") || rest.equals("dynamic/light_map_1")) {
				return Optional.of(new Entry(stage, unit, b.index(), Source.LIGHTMAP, path, Dim.D2, Filter.LINEAR, Wrap.CLAMP_TO_EDGE,
					null, null));
			}
			if (rest.startsWith("textures/atlas/")) {
				String name = rest.substring(rest.lastIndexOf('/') + 1);
				name = name.endsWith(".png") ? name.substring(0, name.length() - 4) : name;
				Source source = name.endsWith("_n") ? Source.DEFAULT_NORMAL : name.endsWith("_s") ? Source.DEFAULT_SPECULAR
					: Source.ATLAS;
				return Optional.of(new Entry(stage, unit, b.index(), source, path, Dim.D2, Filter.NEAREST, Wrap.REPEAT, null, null));
			}
			return Optional.of(new Entry(stage, unit, b.index(), Source.RESOURCE, path, Dim.D2, Filter.NEAREST, Wrap.REPEAT,
				null, null));
		}
		String file = path.startsWith("/") ? path.substring(1) : path;
		Optional<byte[]> bytes = pack.readBytes(file);
		if (bytes.isEmpty()) {
			problems.add(where + "=" + path + ": no such file in the shader pack's shaders folder");
			return Optional.empty();
		}
		Optional<String> mcmeta = pack.readText(file + ".mcmeta");
		Optional<Boolean> blur = mcmeta.flatMap(t -> flag(MCMETA_BLUR, t));
		Optional<Boolean> clamp = mcmeta.flatMap(t -> flag(MCMETA_CLAMP, t));
		if (b.raw().isPresent()) {
			return rawEntry(b, where, file, bytes.get(), blur, clamp, stage, unit, problems);
		}
		Optional<TextureImage> image = TextureImage.decodeImage(bytes.get());
		if (image.isEmpty()) {
			problems.add(where + "=" + path + ": not an image ImageIO reads");
			return Optional.empty();
		}
		Filter filter = blur.map(x -> x ? Filter.LINEAR : Filter.NEAREST).orElse(Filter.NEAREST);
		Wrap wrap = clamp.map(x -> x ? Wrap.CLAMP_TO_EDGE : Wrap.REPEAT).orElse(Wrap.REPEAT);
		return Optional.of(new Entry(stage, unit, b.index(), Source.PACK_FILE, path, Dim.D2, filter, wrap, image.get(), null));
	}

	/**
	 * A raw pack file: its type and dimensions checked, its internal format checked to be one the engine stores, its bytes
	 * checked to hold the texels of its pixel format; empty with the reason otherwise. The conversion itself is the runtime's
	 * (TexelCodec).
	 */
	private static Optional<Entry> rawEntry(ShaderProperties.TextureBinding b, String where, String file, byte[] bytes,
			Optional<Boolean> blur, Optional<Boolean> clamp, String stage, String unit, List<String> problems) {
		RawTexture raw = b.raw().get();
		Dim dim = switch (raw.target()) {
			case "TEXTURE_1D" -> Dim.D1;
			case "TEXTURE_2D" -> Dim.D2;
			case "TEXTURE_3D" -> Dim.D3;
			case "TEXTURE_RECTANGLE" -> Dim.RECT;
			default -> null;
		};
		if (dim == null) {
			problems.add(where + ": unknown texture type " + raw.target());
			return Optional.empty();
		}
		if (!InternalFormats.known(raw.internalFormat())) {
			problems.add(where + ": internal format " + raw.internalFormat() + " is not one the engine stores");
			return Optional.empty();
		}
		int need = dim == Dim.D1 ? 1 : dim == Dim.D3 ? 3 : 2;
		if (raw.dimensions().size() != need || raw.dimensions().stream().anyMatch(d -> d < 1)) {
			problems.add(where + ": " + raw.target() + " needs " + need + " positive dimensions");
			return Optional.empty();
		}
		int texelBytes = PixelLayouts.rawTexelBytes(raw.pixelFormat(), raw.pixelType());
		if (texelBytes < 0) {
			problems.add(where + ": pixel format " + raw.pixelFormat() + " with pixel type " + raw.pixelType()
				+ " is not one the engine reads");
			return Optional.empty();
		}
		int width = raw.dimensions().get(0);
		int height = dim == Dim.D1 ? 1 : raw.dimensions().get(1);
		int depth = dim == Dim.D3 ? raw.dimensions().get(2) : 1;
		long texels = (long) width * height * depth;
		if (bytes.length < texels * texelBytes) {
			problems.add(where + ": the file holds " + bytes.length + " bytes, the " + width + "x" + height + "x" + depth
				+ " texels of " + raw.pixelFormat() + " " + raw.pixelType() + " need " + texels * texelBytes);
			return Optional.empty();
		}
		Filter filter = blur.map(x -> x ? Filter.LINEAR : Filter.NEAREST).orElse(Filter.LINEAR);
		Wrap wrap = clamp.map(x -> x ? Wrap.CLAMP_TO_EDGE : Wrap.REPEAT).orElse(Wrap.CLAMP_TO_EDGE);
		if (dim == Dim.RECT) {
			wrap = Wrap.CLAMP_TO_EDGE;
		}
		return Optional.of(new Entry(stage, unit, b.index(), Source.PACK_FILE, file, dim, filter, wrap, null,
			new RawData(width, height, depth, raw.internalFormat(), raw.pixelFormat(), raw.pixelType(), bytes)));
	}

	private static Optional<Boolean> flag(Pattern p, String text) {
		var m = p.matcher(text);
		return m.find() ? Optional.of(m.group(1).equals("true")) : Optional.empty();
	}

	/** The sampler name a unit serves: a colour target's alias (gaux1, gcolor) becomes colortexN, anything else stays. */
	public static String canonicalUnit(String unit) {
		int target = ColourNames.index(unit);
		return target >= 0 ? "colortex" + target : unit;
	}

	/**
	 * Whether a program reads its target instead of the binding its sampler resolved to: the binding is a stage's
	 * ({@code texture.<stage>.<unit>}, not {@code customTexture}), a 2D texture (a 3D, 1D or rectangle binding cannot be
	 * replaced by a 2D target), and an earlier program of the stage wrote the target (class rules).
	 *
	 * @param entry        the binding the sampler resolved to
	 * @param sampler      the program's sampler name
	 * @param writtenBefore the targets earlier programs of the program's stage wrote, by the sampler names they serve
	 *                     (colortexN, shadowcolorN)
	 */
	public static boolean targetReplaces(Entry entry, String sampler, Set<String> writtenBefore) {
		return !entry.stage().isEmpty() && entry.dim() == Dim.D2 && writtenBefore.contains(canonicalUnit(sampler));
	}

	/**
	 * The stage a program's samplers read custom textures from: setup, begin, shadowcomp, prepare, deferred, composite (also
	 * the final program, as Iris binds them), and gbuffers (also the shadow programs). Null for a program no stage names.
	 */
	public static @Nullable String stageOf(String program) {
		if (program.startsWith("setup")) return "setup";
		if (program.startsWith("begin")) return "begin";
		if (program.startsWith("shadowcomp")) return "shadowcomp";
		if (program.startsWith("prepare")) return "prepare";
		if (program.startsWith("deferred")) return "deferred";
		if (program.startsWith("composite") || program.equals("final")) return "composite";
		if (program.startsWith("gbuffers_") || program.startsWith("shadow")) return "gbuffers";
		return null;
	}

	/** The texture type a GLSL sampler type word names. */
	public static Dim dimOf(String glslType) {
		String t = glslType.startsWith("i") || glslType.startsWith("u") ? glslType.substring(1) : glslType;
		if (t.endsWith("Rect")) return Dim.RECT;
		if (t.startsWith("sampler1D")) return Dim.D1;
		if (t.startsWith("sampler3D")) return Dim.D3;
		return Dim.D2;
	}

	/**
	 * The binding a program's sampler reads, or null when the pack binds none for it: the stage's texture of that unit with
	 * the wanted type, else the customTexture of that name with the wanted type, else (for {@code noisetex}) the pack's
	 * {@code texture.noise}. The resolution is recorded per program ({@link #bindings()}) for the render side, which creates
	 * the textures of every recorded entry.
	 */
	public @Nullable Entry resolve(String program, String sampler, Dim wanted) {
		Entry e = null;
		String stage = stageOf(program);
		if (stage != null) {
			e = pick(this.units.get(stage + "|" + canonicalUnit(sampler)), wanted);
		}
		if (e == null) {
			e = pick(this.customs.get(sampler), wanted);
		}
		if (e == null && sampler.equals("noisetex") && this.noise != null && wanted == Dim.D2) {
			e = this.noise;
		}
		if (e != null) {
			this.bound.computeIfAbsent(program, k -> new ConcurrentHashMap<>()).put(sampler, e);
		}
		return e;
	}

	private static @Nullable Entry pick(@Nullable List<Entry> list, Dim wanted) {
		if (list == null) {
			return null;
		}
		for (Entry e : list) {
			if (e.dim() == wanted) {
				return e;
			}
		}
		return null;
	}

	/** Every entry a program resolved to, by program then sampler name (recorded by {@link #resolve}). */
	public Map<String, Map<String, Entry>> bindings() {
		return this.bound;
	}

	/** The {@code texture.noise} binding with its decoded texels, or null (the engine generates its noise). */
	public @Nullable Entry noise() {
		return this.noise;
	}

	/** Every binding the pack declares, for the load line and the tests. */
	public List<Entry> all() {
		List<Entry> out = new ArrayList<>();
		this.units.values().forEach(out::addAll);
		this.customs.values().forEach(out::addAll);
		if (this.noise != null) {
			out.add(this.noise);
		}
		return out;
	}

	/** Why bindings were refused (the pack's own mistakes), one line each. */
	public List<String> problems() {
		return this.problems;
	}
}
