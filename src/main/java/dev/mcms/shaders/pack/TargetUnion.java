// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.pack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The buffer settings that the programs of one dimension share: color formats, clears, clear colours, mip flags, shadow
 * formats and shadow constants. Constants are read per program (ProgramDirectives), but the docs make these pack-wide
 * (OptiFine shaders.txt, Color Attachments; Iris constants pages). A setting takes the value of the first running program in
 * catalog order that sets it. A later running program that sets the same name to another value is a conflict, reported in
 * {@link #conflicts()} with both programs. A mip flag is the exception: it is on when any running program declares it on,
 * because the chain serves every program that samples the target and an earlier program's {@code false} must not drop it.
 *
 * @param colorFormats    color target index to its format name (RGBA16F ...)
 * @param clears          color target index to its clear flag
 * @param clearColors     color target index to its clear colour
 * @param mipmaps         color target index to its mip flag
 * @param shadowFormats   shadow color index to its format name
 * @param shadowConstants the shadow constants by name (shadowMapResolution, generateShadowMipmap, ...)
 * @param conflicts       one line per setting that two running programs give different values
 */
public record TargetUnion(
	Map<Integer, String> colorFormats,
	Map<Integer, Boolean> clears,
	Map<Integer, ConstValue> clearColors,
	Map<Integer, Boolean> mipmaps,
	Map<Integer, String> shadowFormats,
	Map<String, ConstValue> shadowConstants,
	List<String> conflicts) {

	public TargetUnion {
		colorFormats = Collections.unmodifiableMap(new LinkedHashMap<>(colorFormats));
		clears = Collections.unmodifiableMap(new LinkedHashMap<>(clears));
		clearColors = Collections.unmodifiableMap(new LinkedHashMap<>(clearColors));
		mipmaps = Collections.unmodifiableMap(new LinkedHashMap<>(mipmaps));
		shadowFormats = Collections.unmodifiableMap(new LinkedHashMap<>(shadowFormats));
		shadowConstants = Collections.unmodifiableMap(new LinkedHashMap<>(shadowConstants));
		conflicts = List.copyOf(conflicts);
	}

	/**
	 * The union over the programs that run directly. A fallback runs its target's files, and the target's own entry (when it
	 * is in the list) already contributes them, so fallbacks add nothing here.
	 */
	public static TargetUnion of(List<ResolvedProgram> programs) {
		Builder b = new Builder();
		for (ResolvedProgram p : programs) {
			if (p.source() != ResolvedProgram.Source.DIRECT || p.directives().isEmpty()) continue;
			ProgramDirectives d = p.directives().get();
			String prog = p.name();
			d.colorFormats().forEach((i, v) -> b.offer(b.colorFormats, i, "colortex" + i + "Format", v, prog));
			d.clears().forEach((i, v) -> b.offer(b.clears, i, "colortex" + i + "Clear", v, prog));
			d.clearColors().forEach((i, v) -> b.offer(b.clearColors, i, "colortex" + i + "ClearColor", v, prog));
			d.mipmaps().forEach((i, v) -> b.offerMipmap(i, v, prog));
			d.shadowFormats().forEach((i, v) -> b.offer(b.shadowFormats, i, "shadowcolor" + i + "Format", v, prog));
			for (Map.Entry<String, ConstValue> e : d.constants().entrySet()) {
				if (isShadowConstant(e.getKey())) b.offer(b.shadowConstants, e.getKey(), e.getKey(), e.getValue(), prog);
			}
		}
		return new TargetUnion(b.colorFormats, b.clears, b.clearColors, b.mipmaps, b.shadowFormats, b.shadowConstants,
			b.conflicts);
	}

	/**
	 * The shadow constants of the docs all start with {@code shadow} or {@code generateShadow}; {@code voxelDistance} shapes
	 * the shadow pass's culling too (the voxelDistance page: the area {@code shadow.culling=reversed} keeps).
	 */
	static boolean isShadowConstant(String name) {
		return name.startsWith("shadow") || name.startsWith("generateShadow") || name.equals("voxelDistance");
	}

	/** Collects the first value of each setting and the conflicts with later values. */
	private static final class Builder {
		final Map<Integer, String> colorFormats = new LinkedHashMap<>();
		final Map<Integer, Boolean> clears = new LinkedHashMap<>();
		final Map<Integer, ConstValue> clearColors = new LinkedHashMap<>();
		final Map<Integer, Boolean> mipmaps = new LinkedHashMap<>();
		final Map<Integer, String> shadowFormats = new LinkedHashMap<>();
		final Map<String, ConstValue> shadowConstants = new LinkedHashMap<>();
		final List<String> conflicts = new ArrayList<>();
		private final Map<String, String> origins = new HashMap<>();

		/** Offers one value: the first offer sets it, a different later value is a conflict. */
		<K, V> void offer(Map<K, V> into, K key, String label, V value, String program) {
			V first = into.get(key);
			if (first == null) {
				into.put(key, value);
				origins.put(label, program);
			} else if (!first.equals(value)) {
				conflicts.add(label + ": " + first + " (" + origins.get(label) + ") and " + value + " (" + program + ")");
			}
		}

		/**
		 * Offers one target's mip flag: on when any running program declares it on. A later {@code true} replaces an earlier
		 * {@code false}; a different value is still a conflict, reported with both programs.
		 */
		void offerMipmap(int target, boolean value, String program) {
			String label = "colortex" + target + "MipmapEnabled";
			Boolean first = mipmaps.get(target);
			if (first == null) {
				mipmaps.put(target, value);
				origins.put(label, program);
				return;
			}
			if (first == value) return;
			conflicts.add(label + ": " + first + " (" + origins.get(label) + ") and " + value + " (" + program + ")");
			if (value) {
				mipmaps.put(target, true);
				origins.put(label, program);
			}
		}
	}
}
