// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.shadow;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.mcms.metal.MetalBridge;
import dev.mcms.shaders.pack.ConstValue;
import dev.mcms.shaders.pack.DimensionPrograms;
import dev.mcms.shaders.pack.ProgramDirectives;
import dev.mcms.shaders.pack.ResolvedProgram;
import dev.mcms.shaders.pack.StageFile;
import dev.mcms.shaders.pack.TargetUnion;
import dev.mcms.shaders.runtime.TargetFormats;
import dev.mcms.shaders.runtime.Targets;

/**
 * The shadow targets of a set (E5; engine-pack-format.md section 7 "Shadow buffers", section 16): the depth maps
 * {@code shadowtex0} (all casters) and {@code shadowtex1} (no translucents), and the colour buffers
 * {@code shadowcolor0} to {@code shadowcolor7} (the higher ones with {@code HIGHER_SHADOWCOLOR}). Square, sized by
 * {@code shadowMapResolution}; created once per set (the size does not follow the window) and released with it.
 *
 * <p>The layout ({@link #of}) is pure: it says which maps a set reads and how they are sampled, before any texture
 * exists; {@link #create} makes the textures on the render thread.
 *
 * <p>The documented rules the constants follow (Iris shadowtex and shadowcolor pages; the OptiFine table in
 * shaders.txt "Fragment Shader Configuration"):
 * <ul>
 * <li>Depth maps store one depth channel in a depth-format texture ({@link TargetFormats#DEPTH32_FLOAT}): the docs leave
 * the precision to the driver (R24 or R32), and the engine's depth targets use Depth32Float. [choice]</li>
 * <li>A depth map has no mip chain: Metal cannot generate one for a depth format (E5 review item 10), so a pack's
 * {@code shadowtex<n>Mipmap} is ignored with one WARN and the map keeps one level. [choice]</li>
 * <li>Colour buffers have mip chains: {@code shadowcolor<n>Mipmap}, then {@code generateShadowColorMipmap}; none when the
 * pack says nothing (the docs give no default). A chain is refilled after the shadow pass and each {@code shadowcomp}
 * pass that wrote the buffer, before the next pass that reads it (the Shadow Mipmaps page).</li>
 * <li>Nearest filtering: {@code shadowtex<n>Nearest} (and the {@code shadow<n>MinMagNearest} spelling, and {@code
 * shadowtexNearest} for map 0); linear otherwise. [choice: the docs name no default]</li>
 * <li>Hardware filtering: the shadowtex page says {@code shadowHardwareFiltering} "turns the shadowtex samplers into
 * sampler2DShadow samplers" and that the read then "no longer returns the distance stored in the buffer". So a declared
 * {@code shadowHardwareFiltering<n>} (then the generic one) of true rewrites the map's {@code sampler2D} declarations to
 * {@code sampler2DShadow} in each stage that does not read the map's value ({@link #rewriteDeclarations}; E5 review item
 * 26). The docs give no default and no declaration-free meaning, so with nothing declared the declared type stands.
 * [choice on the default: the docs' example value is true, but they name no default]</li>
 * <li>Colour buffers: RGBA8 unless {@code shadowcolor<n>Format} says otherwise, cleared to white unless
 * {@code shadowcolor<n>Clear} is false (the docs' clear colour is {@code shadowcolor<n>ClearColor}, default white).</li>
 * <li>Colour buffer count: two always exist; a higher index a program writes or names grows the count, up to eight
 * (E5 review item 5). A {@code shadowcomp} fragment program makes every colour buffer flip: the buffer then holds two
 * textures, the main one that later passes read and the alt one that a write goes to (the flip page).</li>
 * </ul>
 * The alias {@code shadow} is {@code shadowtex1} when a program declares {@code watershadow} (the docs: "if waterShadow
 * is present anywhere in the code"), otherwise {@code shadowtex0}; {@code watershadow} is always {@code shadowtex0}.
 * The {@code shadowtex<n>HW} names are served by the map they name, with its hardware rule; the docs make them separate
 * buffers only under the SEPARATE_HARDWARE_SAMPLERS flag, which the engine does not yet implement.
 */
public final class ShadowTargets {

	private static final Logger LOG = LoggerFactory.getLogger("Quicksilver");

	/** The depth maps: {@code shadowtex0} (index 0) and {@code shadowtex1} (index 1). */
	public static final int DEPTH_TARGETS = 2;

	/** The colour buffers the engine can hold: {@code shadowcolor0} to {@code shadowcolor7}. */
	public static final int COLOR_TARGETS = 8;

	/** Every texture a key names: the depth maps, then the colour buffers. */
	private static final int TEXTURES = DEPTH_TARGETS + COLOR_TARGETS;

	/** The colour buffers every set has (the docs: the shadow pass supplies two colour attachments). */
	private static final int MINIMUM_COLOR = 2;

	/** The documented default resolution (shaders.properties: shadowMapResolution). */
	private static final int DEFAULT_RESOLUTION = 1024;

	/** The colour buffer constants by index: {@code shadowcolor<n>...} and the {@code shadowColor<n>...} spelling. */
	private static final Pattern COLOR_CONSTANT = Pattern.compile("shadow[cC]olor([0-7])\\w*");

	/** A shadowcolor sampler name written out in a stage text ({@code shadowcolor3}): group 1 is its index. */
	private static final Pattern COLOR_SAMPLER = Pattern.compile("\\bshadow[cC]olor([0-7])\\b");

	/** A comment in a stage text: a block comment or a line comment. */
	private static final Pattern COMMENT = Pattern.compile("/\\*.*?\\*/|//[^\\n]*", Pattern.DOTALL);

	/**
	 * A sampler declaration of a shadow name. Group 1 is {@code Shadow} when the declared type is already a comparison
	 * sampler, group 2 the separator, group 3 the name.
	 */
	private static final Pattern SHADOW_DECLARATION = Pattern.compile(
		"\\bsampler2D(Shadow)?(\\s+)(shadow|watershadow|shadowtex[01](?:HW)?|shadowcolor[0-7]?)\\b");

	/** The water alias, spelled as the docs spell it ({@code waterShadow}) or as OptiFine's table does. */
	private static final Pattern WATER_SHADOW = Pattern.compile("(?i)\\bwatershadow\\b");

	/** The calls that read a sampler's value (its first argument is the sampler), not a comparison. */
	private static final String VALUE_CALLS = "texture2D|texture2DLod|texture2DGrad|texture2DProj|textureLod|textureGather"
		+ "|textureOffset|textureProj|textureLodOffset|texelFetch";

	private static final AtomicBoolean DEPTH_MIP_WARNED = new AtomicBoolean();
	private static final AtomicBoolean MIP_WARNED = new AtomicBoolean();

	private static final long[] NO_COLOURS = new long[0];

	private final boolean waterShadow;
	private final boolean used;
	private final boolean flipping;
	/** Whether the pack switched its shadow program off (see {@link #shadowProgramOff()}). */
	private final boolean shadowProgramOff;
	private final int resolution;
	private final int colorCount;
	private final TargetFormats[] colorFormats = new TargetFormats[COLOR_TARGETS];
	private final boolean[] depthNearest = new boolean[DEPTH_TARGETS];
	private final boolean[] depthHardware = new boolean[DEPTH_TARGETS];
	private final boolean[] colorMipmapped = new boolean[COLOR_TARGETS];
	private final boolean[] colorNearest = new boolean[COLOR_TARGETS];
	private final boolean[] colorClears = new boolean[COLOR_TARGETS];
	private final float[][] clearColors = new float[COLOR_TARGETS][];
	/** Bumps when a texture's content changed (a clear, a pass that wrote it); a mip chain is stale until refilled. */
	private final long[] generation = new long[TEXTURES];
	/** The generation each mip chain was last filled at; -1 (never filled) makes every chain stale. */
	private final long[] mippedAt = java.util.stream.LongStream.generate(() -> -1L).limit(TEXTURES).toArray();
	private final long[] depths = new long[DEPTH_TARGETS];
	/** The colour buffers' main textures: what later passes sample, and what the shadow pass writes. */
	private final long[] colors = new long[COLOR_TARGETS];
	/** The colour buffers' alt textures (a flipping set only): where a shadowcomp write goes before the flip. */
	private final long[] alternates = new long[COLOR_TARGETS];
	/** One-element scratch for {@link #clear}: one colour buffer per render pass, so a clear allocates nothing. */
	private final long[] oneColour = new long[1];
	private final float[][] oneClear = new float[1][];
	/** Whether the frame's first shadow pass clears its own attachments (armed by {@link #clearForPass}, taken once). */
	private boolean passClearArmed;
	private Targets.@Nullable Textures textures;

	/**
	 * The layout of a set's shadow targets. Nothing is created yet: {@link #create} makes the textures, and {@link #release}
	 * frees them (both on the render thread).
	 *
	 * @param union       the set's buffer settings (shadow formats, shadow constants)
	 * @param waterShadow a program of the set declares {@code watershadow}: the alias {@code shadow} is then
	 *                    {@code shadowtex1}, otherwise {@code shadowtex0} (engine-pack-format.md section 7)
	 * @param used        a program of the set reads or names a shadow map (see {@link #of})
	 */
	public ShadowTargets(TargetUnion union, boolean waterShadow, boolean used) {
		this(union, waterShadow, used, 0, false);
	}

	/**
	 * @param union         the set's buffer settings
	 * @param waterShadow   see {@link #ShadowTargets(TargetUnion, boolean, boolean)}
	 * @param used          see {@link #ShadowTargets(TargetUnion, boolean, boolean)}
	 * @param namedColours  the colour buffers the programs need beyond the union's (one past the highest index a program
	 *                      writes or names; 0 when none)
	 * @param flipping      a shadowcomp fragment program writes a colour buffer: each colour buffer then holds an alt texture
	 */
	public ShadowTargets(TargetUnion union, boolean waterShadow, boolean used, int namedColours, boolean flipping) {
		this(union, waterShadow, used, namedColours, flipping, false);
	}

	/**
	 * @param shadowProgramOff the pack switched its shadow program off (see {@link #shadowProgramOff()})
	 */
	public ShadowTargets(TargetUnion union, boolean waterShadow, boolean used, int namedColours, boolean flipping,
			boolean shadowProgramOff) {
		this.waterShadow = waterShadow;
		this.used = used;
		this.flipping = flipping;
		this.shadowProgramOff = shadowProgramOff;
		Map<String, ConstValue> c = union.shadowConstants();
		this.resolution = Math.max(1, intOf(c, DEFAULT_RESOLUTION, "shadowMapResolution"));
		int count = MINIMUM_COLOR;
		for (Integer index : union.shadowFormats().keySet()) {
			count = Math.max(count, Math.min(COLOR_TARGETS, index + 1));
		}
		for (String name : c.keySet()) {
			Matcher m = COLOR_CONSTANT.matcher(name);
			if (m.matches()) {
				count = Math.max(count, Math.min(COLOR_TARGETS, Integer.parseInt(m.group(1)) + 1));
			}
		}
		this.colorCount = Math.max(count, Math.min(COLOR_TARGETS, namedColours));
		for (int i = 0; i < DEPTH_TARGETS; i++) {
			// Depth32Float has no mip generation on Metal (E5 review item 10): a requested chain is ignored, with one WARN.
			boolean requested = flag(c, false, "shadowtex" + i + "Mipmap",
				i == 0 ? "shadowtexMipmap" : "generateShadowMipmap", "generateShadowMipmap");
			if (requested && DEPTH_MIP_WARNED.compareAndSet(false, true)) {
				LOG.warn("[mcms-shaders] the pack asks for a shadow depth mip chain (shadowtex{}Mipmap); depth maps have none in this engine, so the mip filter is off", i);
			}
			this.depthNearest[i] = i == 0
				? flag(c, false, "shadowtex0Nearest", "shadow0MinMagNearest", "shadowtexNearest")
				: flag(c, false, "shadowtex1Nearest", "shadow1MinMagNearest");
			this.depthHardware[i] = flag(c, false, "shadowHardwareFiltering" + i, "shadowHardwareFiltering");
		}
		for (int i = 0; i < COLOR_TARGETS; i++) {
			String word = union.shadowFormats().get(i);
			this.colorFormats[i] = TargetFormats.of(word, -1);
			this.colorMipmapped[i] = flag(c, false, "shadowcolor" + i + "Mipmap", "shadowColor" + i + "Mipmap",
				"generateShadowColorMipmap");
			this.colorNearest[i] = flag(c, false, "shadowcolor" + i + "Nearest", "shadowColor" + i + "Nearest",
				"shadowColor" + i + "MinMagNearest");
			this.colorClears[i] = flag(c, true, "shadowcolor" + i + "Clear", "shadowColor" + i + "Clear");
			this.clearColors[i] = clearColor(c, "shadowcolor" + i + "ClearColor", "shadowColor" + i + "ClearColor");
		}
	}

	/**
	 * The layout of a dimension's set: the settings of its union, the water alias from any running stage text, and {@code used}
	 * when a running program is named {@code shadow*} or a running stage declares a shadow sampler. Gbuffers programs count the
	 * same way as full-screen ones: their stage texts are read here. The colour buffers grow to the highest index a running
	 * shadow or shadowcomp program writes or names; a running shadowcomp fragment program turns flipping on. A switched-off
	 * program counts for nothing (E5 review item 1); a switched-off {@code shadow} program is recorded as
	 * {@link #shadowProgramOff()}.
	 */
	public static ShadowTargets of(DimensionPrograms dimension) {
		boolean used = false, water = false, flipping = false, off = false;
		int named = 0;
		for (ResolvedProgram program : dimension.programs()) {
			if (!program.runs()) {
				off |= program.name().equals(DefaultShadowProgram.NAME) && program.source() == ResolvedProgram.Source.DISABLED;
				continue;
			}
			used |= program.name().startsWith("shadow");
			for (StageFile file : program.stages()) {
				used |= readsShadow(file.text());
				water |= readsWaterShadow(file.text());
				named = Math.max(named, colorsNamedBy(file.text()));
			}
			if (program.name().startsWith("shadow")) {
				named = Math.max(named, writesNamedBy(program));
				if (program.name().startsWith("shadowcomp") && program.stages().stream().anyMatch(s -> s.stage() == 'f')) {
					flipping = true;
				}
			}
		}
		return new ShadowTargets(dimension.union(), water, used, named, flipping, off);
	}

	/** Whether a stage text declares a shadow sampler (any shadow name, comparison or not). */
	public static boolean readsShadow(String stageText) {
		return SHADOW_DECLARATION.matcher(stageText).find();
	}

	/** Whether a stage text names the water alias. */
	public static boolean readsWaterShadow(String stageText) {
		return WATER_SHADOW.matcher(stageText).find();
	}

	/**
	 * One past the highest {@code shadowcolor<n>} a stage text names; 0 when it names none. A name inside a comment needs no
	 * buffer, so comments are blanked first (E5 review item 9). A program that reads {@code shadowcolor<n>} needs buffer n.
	 */
	public static int colorsNamedBy(String stageText) {
		int top = 0;
		Matcher m = COLOR_SAMPLER.matcher(COMMENT.matcher(stageText).replaceAll(" "));
		while (m.find()) {
			top = Math.max(top, Integer.parseInt(m.group(1)) + 1);
		}
		return top;
	}

	/** One past the highest buffer a program writes explicitly ({@code DRAWBUFFERS}, {@code RENDERTARGETS}); 0 when none. */
	private static int writesNamedBy(ResolvedProgram program) {
		Optional<ProgramDirectives> directives = program.directives();
		if (directives.isEmpty() || directives.get().writesImplicitly()) {
			return 0;
		}
		int top = 0;
		for (int write : directives.get().writes()) {
			top = Math.max(top, write + 1);
		}
		return top;
	}

	/**
	 * The program with its implicit write targets clamped to the shadow colour buffers the set holds (E5 review item 5).
	 * Without a DRAWBUFFERS or RENDERTARGETS comment a program writes its active outputs ({@code default-narrowed}), or the
	 * first eight buffers when the scan cannot tell ({@code default}). A set has only {@code colorCount} shadow colour buffers,
	 * so a write past them is dropped: a {@code default} list becomes {@code shadowcolor0} to {@code colorCount - 1}, and a
	 * {@code default-narrowed} list keeps its targets below {@code colorCount}. The write source stays as it was. A program
	 * that names its targets, and a program with no fragment stage, come back unchanged.
	 */
	public static ResolvedProgram clampWrites(ResolvedProgram program, int colorCount) {
		Optional<ProgramDirectives> directives = program.directives();
		if (directives.isEmpty() || !directives.get().writesImplicitly()) {
			return program;
		}
		ProgramDirectives d = directives.get();
		List<Integer> writes = new ArrayList<>(colorCount);
		if (d.writeSource().equals("default")) {
			for (int i = 0; i < colorCount; i++) {
				writes.add(i);
			}
		} else {
			for (int write : d.writes()) {
				if (write < colorCount) {
					writes.add(write);
				}
			}
		}
		ProgramDirectives clamped = new ProgramDirectives(writes, d.writeSource(), d.reads(), d.constants(), d.colorFormats(),
			d.clears(), d.clearColors(), d.mipmaps(), d.shadowFormats(), d.legacyValues());
		return new ResolvedProgram(program.name(), program.definition(), program.source(), program.servedBy(),
			program.disabledBy(), program.stages(), program.errors(), Optional.of(clamped));
	}

	/**
	 * Whether a stage reads the named sampler's value through a call that needs a plain {@code sampler2D} ({@code texture2D}
	 * and its kin, or {@code texture} with a two-component coordinate). A comparison sampler cannot serve those calls.
	 */
	static boolean readsValue(String stageText, String name) {
		String n = Pattern.quote(name);
		return Pattern.compile("\\b(?:" + VALUE_CALLS + ")\\s*\\(\\s*" + n + "\\s*,").matcher(stageText).find()
			|| Pattern.compile("\\btexture\\s*\\(\\s*" + n + "\\s*,\\s*+(?!vec3)").matcher(stageText).find();
	}

	/** Whether a set's programs read or name a shadow map (the layout's own rule; see {@link #of}). */
	public boolean used() {
		return used;
	}

	/** Whether the alias {@code shadow} is {@code shadowtex1} (a program declares the water alias). */
	public boolean waterShadow() {
		return waterShadow;
	}

	/** The side of both depth maps and every colour buffer, in texels. */
	public int resolution() {
		return resolution;
	}

	/** The number of colour buffers the set uses (0 to {@link #COLOR_TARGETS}). */
	public int colorCount() {
		return colorCount;
	}

	/** Whether the colour buffers flip: a shadowcomp fragment program writes them, so each holds a main and an alt texture. */
	public boolean flipping() {
		return flipping;
	}

	/**
	 * Whether the pack switched its {@code shadow} program off: the program has files that a rule or profile disables. Such a
	 * set runs no shadow pass, clears its maps once, and gets no built-in shadow program (the pack chose none).
	 */
	public boolean shadowProgramOff() {
		return shadowProgramOff;
	}

	/**
	 * Whether every colour buffer index of a shadow layout is one these targets hold. The engine fails a set whose layout
	 * names a buffer beyond the count (a shadow program that writes shadowcolor8 or above).
	 */
	public boolean fits(int[] layout) {
		for (int index : layout) {
			if (index < 0 || index >= colorCount) {
				return false;
			}
		}
		return true;
	}

	/** The format of a colour buffer (the pack's {@code shadowcolor<n>Format}, RGBA8 by default). */
	public TargetFormats colorFormat(int index) {
		return index >= 0 && index < COLOR_TARGETS ? colorFormats[index] : TargetFormats.RGBA8;
	}

	/**
	 * The index a sampler name reads as a shadow depth map (0 or 1), or -1 for any other name. The binds call this per
	 * sampler of every draw, so it allocates nothing (RIL-567: a regex Matcher per call).
	 */
	public int depthIndexOf(String name) {
		return switch (name) {
			case "shadow" -> waterShadow ? 1 : 0;
			case "shadowtex0", "shadowtex0HW", "watershadow" -> 0;
			case "shadowtex1", "shadowtex1HW" -> 1;
			default -> -1;
		};
	}

	/** The index a sampler name reads as a shadow colour buffer (0 to 7), or -1 for any other name (allocates nothing). */
	public int colorIndexOf(String name) {
		return switch (name) {
			case "shadowcolor", "shadowcolor0" -> 0;
			case "shadowcolor1" -> 1;
			case "shadowcolor2" -> 2;
			case "shadowcolor3" -> 3;
			case "shadowcolor4" -> 4;
			case "shadowcolor5" -> 5;
			case "shadowcolor6" -> 6;
			case "shadowcolor7" -> 7;
			default -> -1;
		};
	}

	/**
	 * A stage text with each shadow map declared {@code sampler2D} rewritten to {@code sampler2DShadow} when that map's
	 * hardware filtering is declared on (the docs: "turns the shadowtex samplers into sampler2DShadow samplers") and the
	 * stage does not read the map's value (E5 review item 26: a pack that reads the map through {@code texture2D} in
	 * another program keeps its declaration). Other declarations, and every declaration when the rule is off, stand as
	 * written. Line breaks are kept.
	 */
	public String rewriteDeclarations(String stageText) {
		Matcher m = SHADOW_DECLARATION.matcher(stageText);
		StringBuilder out = new StringBuilder(stageText.length() + 16);
		while (m.find()) {
			int depth = depthIndexOf(m.group(3));
			boolean rewrite = m.group(1) == null && depth >= 0 && hardwareDepth(depth)
				&& !readsValue(stageText, m.group(3));
			String replacement = rewrite ? "sampler2DShadow" + m.group(2) + m.group(3) : m.group();
			m.appendReplacement(out, Matcher.quoteReplacement(replacement));
		}
		m.appendTail(out);
		return out.toString();
	}

	/** Whether a shadow depth map is sampled with a linear or a nearest filter (the pack's {@code shadowtex<n>Nearest}). */
	public boolean nearestDepth(int index) {
		return index >= 0 && index < DEPTH_TARGETS && depthNearest[index];
	}

	/** Whether a shadow depth map's hardware filtering is declared on ({@code shadowHardwareFiltering<n>}; see the class comment). */
	public boolean hardwareDepth(int index) {
		return index >= 0 && index < DEPTH_TARGETS && depthHardware[index];
	}

	/** Whether a shadow depth map carries a mip chain: never (Depth32Float; see the class comment). */
	public boolean mipmappedDepth(int index) {
		return false;
	}

	/** Whether a shadow colour buffer carries a mip chain ({@code shadowcolor<n>Mipmap}). */
	public boolean mipmappedColor(int index) {
		return index >= 0 && index < COLOR_TARGETS && colorMipmapped[index];
	}

	/** Whether a shadow colour buffer is sampled with a nearest filter ({@code shadowcolor<n>Nearest}). */
	public boolean nearestColor(int index) {
		return index >= 0 && index < COLOR_TARGETS && colorNearest[index];
	}

	/** Whether the pass clears a colour buffer before the frame ({@code shadowcolor<n>Clear}, true by default). */
	public boolean clearsColor(int index) {
		return index >= 0 && index < COLOR_TARGETS && colorClears[index];
	}

	/** The clear colour of a colour buffer (white by default, {@code shadowcolor<n>ClearColor}). */
	public float[] clearColor(int index) {
		return index >= 0 && index < COLOR_TARGETS ? clearColors[index].clone() : new float[] {1f, 1f, 1f, 1f};
	}

	/** The key of depth map {@code index} for the mip and texture methods. */
	public static int keyOfDepth(int index) {
		return index;
	}

	/** The key of colour buffer {@code index} for the mip and texture methods. */
	public static int keyOfColor(int index) {
		return DEPTH_TARGETS + index;
	}

	/** The depth map's native texture, or 0 before {@link #create} or when the device refused it. */
	public long depth(int index) {
		return index >= 0 && index < DEPTH_TARGETS ? depths[index] : 0L;
	}

	/**
	 * The colour buffer's native texture that later passes read, or 0 before {@link #create} or when the device refused it.
	 * The shadow pass writes this texture too.
	 */
	public long color(int index) {
		return index >= 0 && index < colorCount ? colors[index] : 0L;
	}

	/**
	 * The texture a shadowcomp write of colour buffer {@code index} targets: the alt texture of a flipping set, else the
	 * buffer itself. Its content reaches later passes through {@link #wroteColor}.
	 */
	public long colorWrite(int index) {
		if (index < 0 || index >= colorCount) {
			return 0L;
		}
		return flipping ? alternates[index] : colors[index];
	}

	/**
	 * Records that a shadowcomp pass wrote colour buffer {@code index}. In a flipping set with {@code flip} on, the two
	 * textures swap, so the write is the texture later passes read (the flip page: "the main and alt buffers are swapped
	 * so data written by the current program is read by the next program"). With {@code flip} off the write stays in the
	 * alt texture, which no later pass reads. The mip chain of the buffer is stale from now on.
	 */
	public void wroteColor(int index, boolean flip) {
		if (index < 0 || index >= colorCount) {
			return;
		}
		if (flipping && flip) {
			long main = colors[index];
			colors[index] = alternates[index];
			alternates[index] = main;
		}
		generation[keyOfColor(index)]++;
	}

	/** The native texture a shadow sampler name reads (depth or colour, aliases included), or 0 for any other name. */
	public long texture(String name) {
		int depth = depthIndexOf(name);
		if (depth >= 0) {
			return depth(depth);
		}
		int color = colorIndexOf(name);
		return color >= 0 ? color(color) : 0L;
	}

	/** The native texture of a key ({@link #keyOfDepth}, {@link #keyOfColor}), or 0 when none. */
	public long textureOf(int key) {
		return key < DEPTH_TARGETS ? depth(key) : color(key - DEPTH_TARGETS);
	}

	/** Whether the texture of a key has a mip chain. */
	public boolean mipmapped(int key) {
		return key < DEPTH_TARGETS ? mipmappedDepth(key) : mipmappedColor(key - DEPTH_TARGETS);
	}

	/**
	 * Records that the content of every shadow texture changed: the frame's clear, and a pass that wrote a map (the shadow
	 * pass calls this when it ends). A mip chain is refilled before the next pass that reads it.
	 */
	public void changed() {
		for (int k = 0; k < TEXTURES; k++) {
			generation[k]++;
		}
	}

	/**
	 * Clears the shadow targets on {@code enc}: each depth map to 1.0, and each colour buffer the pack clears to its clear
	 * colour ({@code shadowcolor<n>Clear}, true by default). Marks the content changed, so a mip chain is refilled before
	 * the next pass that reads it. Each clear is a render pass with no draws; the encoders are ended at the end. Allocates
	 * nothing.
	 */
	public void clear(long enc) {
		changed();
		for (int d = 0; d < DEPTH_TARGETS; d++) {
			if (depths[d] != 0L) {
				MetalBridge.renderBegin(enc, NO_COLOURS, null, depths[d], true, 1.0f, resolution, resolution);
			}
		}
		for (int c = 0; c < colorCount; c++) {
			if (colors[c] != 0L && colorClears[c]) {
				oneColour[0] = colors[c];
				oneClear[0] = clearColors[c];
				MetalBridge.renderBegin(enc, oneColour, oneClear, 0L, false, 0f, resolution, resolution);
			}
		}
		MetalBridge.endEncoders(enc);
	}

	/**
	 * The frame's clear when the first shadow pass clears its own attachments (E9): the pass's colour attachments
	 * ({@code layout}, shadowcolor indices in attachment order) and {@code shadowtex0}, its depth, clear in the pass's load
	 * actions ({@link #passClears}, taken through {@link #takePassClear}), so no clear pass runs ahead of it on the GPU. Here
	 * only the colour buffers the pack clears that are not attachments get a clear pass. {@code shadowtex1} needs none: the
	 * pass copies {@code shadowtex0} into it whole before anything reads it (the pass clears it with {@link #clearDepth} when
	 * that copy did not run). Marks the content changed. Allocates nothing.
	 */
	public void clearForPass(long enc, int[] layout) {
		changed();
		this.passClearArmed = true;
		boolean began = false;
		for (int c = 0; c < colorCount; c++) {
			if (colors[c] != 0L && colorClears[c] && !attachment(layout, c)) {
				oneColour[0] = colors[c];
				oneClear[0] = clearColors[c];
				MetalBridge.renderBegin(enc, oneColour, oneClear, 0L, false, 0f, resolution, resolution);
				began = true;
			}
		}
		if (began) {
			MetalBridge.endEncoders(enc);
		}
	}

	/** Whether the next shadow pass clears its own attachments; taking it disarms it, so the frame's later passes load. */
	public boolean takePassClear() {
		boolean armed = this.passClearArmed;
		this.passClearArmed = false;
		return armed;
	}

	/**
	 * The clear colours of the shadow pass's colour attachments ({@code layout}, attachment order), refilled into {@code out}:
	 * the buffer's clear colour where the pack clears it ({@code shadowcolor<n>Clear}), else null (the pass loads it).
	 */
	public float[][] passClears(int[] layout, float[][] out) {
		for (int a = 0; a < layout.length; a++) {
			out[a] = colorClears[layout[a]] ? clearColors[layout[a]] : null;
		}
		return out;
	}

	/** Clears depth map {@code index} to 1.0 alone (a render pass with no draws), ending the encoders after it. */
	public void clearDepth(long enc, int index) {
		if (index >= 0 && index < DEPTH_TARGETS && depths[index] != 0L) {
			MetalBridge.renderBegin(enc, NO_COLOURS, null, depths[index], true, 1.0f, resolution, resolution);
			MetalBridge.endEncoders(enc);
		}
	}

	private static boolean attachment(int[] layout, int colour) {
		for (int index : layout) {
			if (index == colour) {
				return true;
			}
		}
		return false;
	}

	/** Whether a texture's mip chain is out of date with its content (never filled, or changed since). */
	public boolean mipsStale(int key) {
		return generation[key] != mippedAt[key];
	}

	/** Records that a texture's mip chain was refilled for its current content. */
	public void mipsFilled(int key) {
		mippedAt[key] = generation[key];
	}

	/**
	 * Refills one texture's mip chain from its level 0 when its content changed since the last fill. A chain that is not
	 * mipmapped, or is not stale, is left alone. A device that cannot reduce the format refuses (the chain stays stale, one
	 * WARN for the process), and the caller then reads level 0 only.
	 *
	 * @return false when the chain could not be filled
	 */
	public boolean fillMip(long enc, int key) {
		long texture = textureOf(key);
		if (texture == 0L || !mipmapped(key) || !mipsStale(key)) {
			return true;
		}
		if (!MetalBridge.generateMipmaps(enc, texture)) {
			if (MIP_WARNED.compareAndSet(false, true)) {
				LOG.warn("[mcms-shaders] the device cannot generate the mip chain of a shadow buffer (key {}); it reads level 0 only", key);
			}
			return false;
		}
		mipsFilled(key);
		return true;
	}

	/**
	 * Refills every stale mip chain of the shadow textures ({@link #fillMip}): the shadow pass's close fills them before
	 * {@code shadowcomp}, and a shadowcomp pass's write makes its chain stale for the next reader.
	 *
	 * @return false when one chain could not be filled
	 */
	public boolean fillMips(long enc) {
		boolean ok = true;
		for (int key = 0; key < TEXTURES; key++) {
			ok &= fillMip(enc, key);
		}
		return ok;
	}

	/** Creates the textures with {@code textures}; false (and nothing left allocated) when the device refuses one. */
	public boolean create(Targets.Textures textures) {
		this.textures = textures;
		changed();  // new textures hold no mip chain yet: every chain is stale until it is filled
		for (int i = 0; i < DEPTH_TARGETS; i++) {
			depths[i] = textures.create(TargetFormats.DEPTH32_FLOAT, resolution, resolution, 1);
			if (depths[i] == 0L) {
				release();
				return false;
			}
		}
		for (int i = 0; i < colorCount; i++) {
			colors[i] = textures.create(colorFormats[i].format(), resolution, resolution,
				mipLevels(resolution, colorMipmapped[i]));
			if (colors[i] == 0L) {
				release();
				return false;
			}
			if (flipping) {
				alternates[i] = textures.create(colorFormats[i].format(), resolution, resolution,
					mipLevels(resolution, colorMipmapped[i]));
				if (alternates[i] == 0L) {
					release();
					return false;
				}
			}
		}
		return true;
	}

	/** Releases the textures this layout created (idempotent). */
	public void release() {
		for (int i = 0; i < DEPTH_TARGETS; i++) {
			if (depths[i] != 0L) {
				textures.release(depths[i]);
				depths[i] = 0L;
			}
		}
		for (int i = 0; i < COLOR_TARGETS; i++) {
			if (colors[i] != 0L) {
				textures.release(colors[i]);
				colors[i] = 0L;
			}
			if (alternates[i] != 0L) {
				textures.release(alternates[i]);
				alternates[i] = 0L;
			}
		}
	}

	/** The mip levels a shadow texture carries: the full chain when mipmapped, else 1. */
	public static int mipLevels(int resolution, boolean mipmapped) {
		return mipmapped ? 32 - Integer.numberOfLeadingZeros(Math.max(1, resolution)) : 1;
	}

	/** The first of the named constants the pack declares as a boolean, in precedence order; else the default. */
	private static boolean flag(Map<String, ConstValue> constants, boolean dflt, String... names) {
		for (String name : names) {
			ConstValue v = constants.get(name);
			if (v != null && !v.values().isEmpty()) {
				return v.asBoolean();
			}
		}
		return dflt;
	}

	private static int intOf(Map<String, ConstValue> constants, int dflt, String name) {
		ConstValue v = constants.get(name);
		return v == null || v.values().isEmpty() ? dflt : v.asInt();
	}

	/** A colour buffer's clear colour: the first of the named vec4 constants the pack declares, else opaque white. */
	private static float[] clearColor(Map<String, ConstValue> constants, String... names) {
		float[] out = {1f, 1f, 1f, 1f};
		for (String name : names) {
			ConstValue v = constants.get(name);
			if (v != null && !v.values().isEmpty()) {
				for (int k = 0; k < 4 && k < v.values().size(); k++) {
					out[k] = v.values().get(k).floatValue();
				}
				return out;
			}
		}
		return out;
	}
}
