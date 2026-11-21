// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.pack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What one program says about its targets, read from its preprocessed stages (engine-pack-format.md sections 7 to 9).
 *
 * @param writes        the color targets the fragment stage writes, in order: from the last {@code DRAWBUFFERS} or
 *                      {@code RENDERTARGETS} comment of the fragment stage (packs choose it with {@code #if}); with neither,
 *                      the targets its active code writes ({@link FragmentWriteScan}), listed from 0 up to the highest one;
 *                      0 to 7 when that scan cannot tell; empty when the program has no fragment stage or writes no target. The
 *                      composite passes flip the targets this list names (EngineFrame.plan): a composite with no comment that
 *                      writes {@code gl_FragData[0]} alone flips {@code colortex0} alone. A gap below the highest written
 *                      target stays listed (plan.md E6-drawbuffers, known gap), so it is flipped too.
 * @param writeSource   "DRAWBUFFERS", "RENDERTARGETS", "default-narrowed" (no comment: the active code's targets), "default"
 *                      (no comment and the scan cannot tell: the first eight) or "none"
 * @param reads         the color targets whose names (colortexN or an alias) appear as words in any stage
 * @param constants     every {@code const} declaration whose right-hand side the evaluator reads, by name (later wins)
 * @param colorFormats  the color targets' formats from {@code <target>Format} constants, as the pack writes them (RGBA16)
 * @param clears        the color targets' {@code <target>Clear} flags
 * @param clearColors   the color targets' {@code <target>ClearColor} values
 * @param mipmaps       the color targets' {@code <target>MipmapEnabled} flags
 * @param shadowFormats the shadow color buffers' formats from {@code shadowcolor<n>Format}
 * @param legacyValues  the comment-form constants such as {@code /* SHADOWRES:1024 *}{@code /}}, with their values
 */
public record ProgramDirectives(
	List<Integer> writes,
	String writeSource,
	Set<Integer> reads,
	Map<String, ConstValue> constants,
	Map<Integer, String> colorFormats,
	Map<Integer, Boolean> clears,
	Map<Integer, ConstValue> clearColors,
	Map<Integer, Boolean> mipmaps,
	Map<Integer, String> shadowFormats,
	Map<String, Double> legacyValues) {

	/** The color target names that map to indexes (engine-pack-format.md section 7). */
	private static final Map<String, Integer> ALIASES = Map.of("gcolor", 0, "gdepth", 1, "gnormal", 2, "composite", 3,
		"gaux1", 4, "gaux2", 5, "gaux3", 6, "gaux4", 7);
	private static final String TARGET = "(colortex\\d+|gcolor|gdepth|gnormal|composite|gaux[1-4])";
	private static final Pattern CONST_LINE = Pattern.compile("^\\s*const\\s+(\\w+)\\s+(\\w+)\\s*=\\s*([^;]+);");
	private static final Pattern FORMAT = Pattern.compile("^" + TARGET + "Format$");
	private static final Pattern CLEAR = Pattern.compile("^" + TARGET + "Clear$");
	private static final Pattern CLEAR_COLOR = Pattern.compile("^" + TARGET + "ClearColor$");
	private static final Pattern MIPMAP = Pattern.compile("^" + TARGET + "MipmapEnabled$");
	private static final Pattern SHADOW_FORMAT = Pattern.compile("^shadowcolor(\\d+)Format$");
	private static final Pattern WRITES = Pattern.compile(
		"DRAWBUFFERS:[ \\t]*([0-9]+)|RENDERTARGETS:[ \\t]*([0-9](?:[0-9, \\t]*[0-9])?)");
	private static final Pattern LEGACY = Pattern.compile(
		"/\\*\\s*(SHADOWRES|SHADOWFOV|SHADOWHPL|WETNESSHL|DRYNESSHL)\\s*:\\s*([0-9.]+)\\s*\\*/");
	/**
	 * The color target each read name stands for: its colortexN name and its aliases, read as whole words (one scan of a
	 * stage's words, E9 load time: sixteen regex scans of each stage were a seventh of a pack's read).
	 */
	private static final Map<String, Integer> READ_NAMES = new HashMap<>();
	/** The read names' first characters (ASCII) and length range: other words take no substring and no lookup. */
	private static final boolean[] READ_FIRST = new boolean[128];
	private static final int READ_MIN, READ_MAX;

	static {
		for (int t = 0; t <= 15; t++) {
			READ_NAMES.put("colortex" + t, t);
		}
		for (Map.Entry<String, Integer> e : ALIASES.entrySet()) {
			if (e.getValue() >= 0 && e.getValue() <= 15) {
				READ_NAMES.put(e.getKey(), e.getValue());
			}
		}
		int min = Integer.MAX_VALUE, max = 0;
		for (String name : READ_NAMES.keySet()) {
			READ_FIRST[name.charAt(0)] = true;
			min = Math.min(min, name.length());
			max = Math.max(max, name.length());
		}
		READ_MIN = min;
		READ_MAX = max;
	}

	public ProgramDirectives {
		writes = List.copyOf(writes);
		reads = java.util.Collections.unmodifiableSet(new LinkedHashSet<>(reads));
		constants = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(constants));
		colorFormats = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(colorFormats));
		clears = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(clears));
		clearColors = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(clearColors));
		mipmaps = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(mipmaps));
		shadowFormats = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(shadowFormats));
		legacyValues = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(legacyValues));
	}

	/** The index of a color target name ({@code colortex5}, {@code gaux2}), or -1 for anything else. */
	public static int targetIndex(String name) {
		if (name.startsWith("colortex")) {
			try {
				int i = Integer.parseInt(name.substring("colortex".length()));
				return i >= 0 && i <= 15 ? i : -1;
			} catch (NumberFormatException ex) {
				return -1;
			}
		}
		return ALIASES.getOrDefault(name, -1);
	}

	/** The names a color target index answers to: {@code colortex4}, plus its alias ({@code gaux1}) when it has one. */
	public static List<String> targetNames(int index) {
		List<String> names = new ArrayList<>(2);
		names.add("colortex" + index);
		for (Map.Entry<String, Integer> e : ALIASES.entrySet()) {
			if (e.getValue() == index) {
				names.add(e.getKey());
			}
		}
		return names;
	}

	/** The largest color target the program writes or reads, or empty when it touches none. */
	public OptionalInt largestTarget() {
		int max = -1;
		for (int w : writes) max = Math.max(max, w);
		for (int r : reads) max = Math.max(max, r);
		return max < 0 ? OptionalInt.empty() : OptionalInt.of(max);
	}

	/** Whether the write list is the no-comment default, which the code's own outputs set ({@code default-narrowed}) or the fallback ({@code default}). */
	public boolean writesImplicitly() {
		return this.writeSource.startsWith("default");
	}

	/** Reads the directives of a program from its preprocessed stages. */
	public static ProgramDirectives of(List<StageFile> stages) {
		Map<String, String[]> raw = new LinkedHashMap<>();
		Map<String, Double> legacy = new LinkedHashMap<>();
		for (StageFile s : stages) {
			for (String line : s.text().split("\n", -1)) {
				if (line.indexOf("const") < 0) {
					continue;  // CONST_LINE needs the word: a cheap test first (E9, load time)
				}
				Matcher m = CONST_LINE.matcher(line);
				if (m.find()) raw.put(m.group(2), new String[] {m.group(1), m.group(3).strip()});
			}
			Matcher lm = LEGACY.matcher(s.text());
			while (lm.find()) legacy.put(lm.group(1), Double.parseDouble(lm.group(2)));
		}
		Map<String, ConstValue> constants = new LinkedHashMap<>();
		for (Map.Entry<String, String[]> e : raw.entrySet()) {
			ConstValue.parse(e.getValue()[0], e.getValue()[1]).ifPresent(v -> constants.put(e.getKey(), v));
		}
		Map<Integer, String> formats = new LinkedHashMap<>();
		Map<Integer, Boolean> clears = new LinkedHashMap<>();
		Map<Integer, ConstValue> clearColors = new LinkedHashMap<>();
		Map<Integer, Boolean> mipmaps = new LinkedHashMap<>();
		Map<Integer, String> shadowFormats = new LinkedHashMap<>();
		for (Map.Entry<String, String[]> e : raw.entrySet()) {
			String name = e.getKey();
			String rhs = e.getValue()[1];
			Matcher f = FORMAT.matcher(name);
			if (f.matches()) {
				formats.put(targetIndex(f.group(1)), rhs);
				continue;
			}
			Matcher c = CLEAR.matcher(name);
			if (c.matches() && constants.containsKey(name)) {
				clears.put(targetIndex(c.group(1)), constants.get(name).asBoolean());
				continue;
			}
			Matcher cc = CLEAR_COLOR.matcher(name);
			if (cc.matches() && constants.containsKey(name)) {
				clearColors.put(targetIndex(cc.group(1)), constants.get(name));
				continue;
			}
			Matcher mm = MIPMAP.matcher(name);
			if (mm.matches() && constants.containsKey(name)) {
				mipmaps.put(targetIndex(mm.group(1)), constants.get(name).asBoolean());
				continue;
			}
			Matcher sf = SHADOW_FORMAT.matcher(name);
			if (sf.matches()) shadowFormats.put(Integer.parseInt(sf.group(1)), rhs);
		}
		List<Integer> writes = new ArrayList<>();
		String writeSource = "none";
		StageFile fragment = null;
		for (StageFile s : stages) {
			if (s.stage() == 'f') fragment = s;
		}
		if (fragment != null) {
			writeSource = "default";
			Matcher w = WRITES.matcher(fragment.text());
			while (w.find()) {
				writes.clear();
				if (w.group(1) != null) {
					writeSource = "DRAWBUFFERS";
					for (char ch : w.group(1).toCharArray()) writes.add(ch - '0');
				} else {
					writeSource = "RENDERTARGETS";
					for (String part : w.group(2).split(",")) {
						if (!part.isBlank()) writes.add(Integer.parseInt(part.strip()));
					}
				}
			}
			if (writeSource.equals("default")) {
				Optional<TreeSet<Integer>> active = FragmentWriteScan.of(fragment.text());
				if (active.isPresent()) {
					// Draw buffer i is target i, and a write list is read by position (FragmentOutputs, ProgramRequests), so
					// the list runs from 0 to the highest target the code writes; the gaps below it stay listed.
					int top = active.get().isEmpty() ? -1 : active.get().last();
					writeSource = "default-narrowed";
					for (int i = 0; i <= top; i++) writes.add(i);
				} else {
					for (int i = 0; i < FragmentWriteScan.DRAW_BUFFERS; i++) writes.add(i);
				}
			}
		}
		boolean[] read = new boolean[16];
		for (StageFile s : stages) {
			readNames(s.text(), read);
		}
		Set<Integer> reads = new LinkedHashSet<>();
		for (int t = 0; t <= 15; t++) {
			if (read[t]) {
				reads.add(t);
			}
		}
		return new ProgramDirectives(writes, writeSource, reads, constants, formats, clears, clearColors, mipmaps,
			shadowFormats, legacy);
	}

	/**
	 * Marks the targets whose read names occur in {@code text} as whole words: the words are the runs of ASCII letters,
	 * digits and underscores, the characters a regex word boundary ({@code \b}, no Unicode flag) separates.
	 */
	private static void readNames(String text, boolean[] read) {
		int n = text.length();
		int i = 0;
		while (i < n) {
			if (!isWordChar(text.charAt(i))) {
				i++;
				continue;
			}
			int start = i;
			while (i < n && isWordChar(text.charAt(i))) {
				i++;
			}
			int length = i - start;
			char first = text.charAt(start);
			if (first < 128 && READ_FIRST[first] && length >= READ_MIN && length <= READ_MAX) {
				Integer target = READ_NAMES.get(text.substring(start, i));
				if (target != null) {
					read[target] = true;
				}
			}
		}
	}

	private static boolean isWordChar(char c) {
		return c == '_' || c < 128 && Character.isLetterOrDigit(c);
	}
}
