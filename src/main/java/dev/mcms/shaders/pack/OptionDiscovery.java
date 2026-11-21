// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.pack;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the options of a pack (engine-pack-format.md section 4; shaders.txt and shaders.properties). The scan is lexical and
 * covers every stage file of the given folders and every file each of them reaches through {@code #include}. Declarations
 * in inactive branches are found too (the scan does not evaluate conditionals).
 *
 * <p>A boolean ({@code #define X} or {@code // #define X}) counts only when its program tests {@code X} with
 * {@code #ifdef}, {@code #ifndef} or {@code defined}. The test may sit in any file the program includes: the docs say "the
 * same file", and the reference's option list has options whose test is in an included file. Value and const declarations
 * need no test.
 */
public final class OptionDiscovery {
	/** The const names shaders.properties documents as options (the list in the OptiFine doc's shaders.properties). */
	public static final Set<String> DOCUMENTED_CONSTS = Set.of(
		"shadowMapResolution", "shadowDistance", "shadowDistanceRenderMul", "shadowIntervalSize",
		"generateShadowMipmap", "generateShadowColorMipmap", "shadowHardwareFiltering", "shadowHardwareFiltering0",
		"shadowHardwareFiltering1", "shadowtex0Mipmap", "shadowtexMipmap", "shadowtex1Mipmap", "shadowcolor0Mipmap",
		"shadowColor0Mipmap", "shadowcolor1Mipmap", "shadowColor1Mipmap", "shadowtex0Nearest", "shadowtexNearest",
		"shadow0MinMagNearest", "shadowtex1Nearest", "shadow1MinMagNearest", "shadowcolor0Nearest", "shadowColor0Nearest",
		"shadowColor0MinMagNearest", "shadowcolor1Nearest", "shadowColor1Nearest", "shadowColor1MinMagNearest",
		"wetnessHalflife", "drynessHalflife", "eyeBrightnessHalflife", "centerDepthHalflife", "sunPathRotation",
		"ambientOcclusionLevel", "superSamplingLevel", "noiseTextureResolution");

	/** A const declaration with an optional line comment, which may hold a value list (see {@link Comment}). Code after the
	 * semicolon is not a comment, so {@code const int N = 4; float w[3];} is no option. */
	private static final Pattern CONST_DECL = Pattern.compile(
		"^\\s*const\\s+(int|float|bool)\\s+([A-Za-z_]\\w*)\\s*=\\s*([^;]*?)\\s*;\\s*(//.*)?$");
	/** A value option: a define with a default and a comment. It is an option only when the comment holds a value list. */
	private static final Pattern VALUE_DECL = Pattern.compile(
		"^\\s*#\\s*define\\s+([A-Za-z_]\\w*)\\s+(\\S+)\\s*//(.*)$");
	/** A value list in a comment: {@code [a b c]}, before or after the tooltip (the OptiFine doc's example puts it after). */
	private static final Pattern VALUE_LIST = Pattern.compile("\\[([^\\]]*)\\]");
	private static final Pattern BOOL_DECL = Pattern.compile(
		"^(\\s*)(//\\s*)?#\\s*define\\s+([A-Za-z_]\\w*)\\s*(//.*)?$");
	private static final Pattern TESTED = Pattern.compile(
		"#\\s*if(?:n)?def\\s+([A-Za-z_]\\w*)|defined\\s*\\(?\\s*([A-Za-z_]\\w*)");
	private static final Pattern INCLUDE = Pattern.compile("^\\s*#\\s*include\\s*\"([^\"]+)\"");
	private static final Pattern TOOLTIP = Pattern.compile("^\\s*(?://)?\\s*(.*?)\\s*$");

	/** Stage file extensions: the OptiFine ones plus Iris's tessellation stages. */
	public static boolean isStageFile(String name) {
		return name.endsWith(".vsh") || name.endsWith(".fsh") || name.endsWith(".gsh") || name.endsWith(".csh")
			|| name.endsWith(".tcs") || name.endsWith(".tes");
	}

	private record Raw(String name, OptionKind kind, PackOption.Declaration decl, List<String> values, boolean hasList,
		String type, String tooltip) {
	}

	private OptionDiscovery() {
	}

	/**
	 * Scans the stage files of the given folders ({@code ""} is the {@code shaders/} root) and every file they include.
	 */
	public static OptionCatalog scan(PackSource pack, List<String> folders) {
		Map<String, String> texts = new HashMap<>();
		List<String> roots = new ArrayList<>();
		for (String folder : folders) {
			for (String entry : pack.list(folder)) {
				if (entry.endsWith("/") || !isStageFile(entry)) continue;
				roots.add(folder.isEmpty() ? entry : folder + "/" + entry);
			}
		}
		Set<String> declared = new HashSet<>();
		Set<String> files = new LinkedHashSet<>();
		List<Raw> raws = new ArrayList<>();
		List<String> warnings = new ArrayList<>();
		for (String root : roots) {
			Map<String, String> reach = reach(pack, root, texts);
			Set<String> tested = new HashSet<>();
			for (String text : reach.values()) tested.addAll(testedNames(text));
			for (Map.Entry<String, String> file : reach.entrySet()) {
				files.add(file.getKey());
				scanFile(file.getKey(), file.getValue(), tested, declared, raws);
			}
		}
		return merge(raws, warnings, new ArrayList<>(files));
	}

	/** The root and every file it reaches through includes, each once, in the order they are first reached. */
	private static Map<String, String> reach(PackSource pack, String root, Map<String, String> texts) {
		Map<String, String> out = new LinkedHashMap<>();
		Deque<String> queue = new ArrayDeque<>();
		queue.add(root);
		while (!queue.isEmpty()) {
			String path = queue.poll();
			if (out.containsKey(path)) continue;
			String text = texts.computeIfAbsent(path, p -> pack.readText(p).orElse(null));
			if (text == null) continue;
			out.put(path, text);
			for (String line : text.split("\n", -1)) {
				Matcher m = INCLUDE.matcher(line);
				if (m.find()) PackPaths.resolve(path, m.group(1)).ifPresent(queue::add);
			}
		}
		return out;
	}

	private static Set<String> testedNames(String text) {
		Set<String> out = new HashSet<>();
		Matcher t = TESTED.matcher(text);
		while (t.find()) out.add(t.group(1) != null ? t.group(1) : t.group(2));
		return out;
	}

	private static void scanFile(String path, String text, Set<String> tested, Set<String> declared, List<Raw> out) {
		String[] lines = text.split("\n", -1);
		for (int i = 0; i < lines.length; i++) {
			String line = lines[i];
			// A const option needs the word const, a define option a '#': other lines run no pattern (E9, load time).
			boolean constLine = line.indexOf("const") >= 0;
			boolean hashLine = line.indexOf('#') >= 0;
			if (!constLine && !hashLine) {
				continue;
			}
			int lineNo = i + 1;
			String key = path + ":" + lineNo;
			if (constLine) {
				Matcher c = CONST_DECL.matcher(line);
				if (c.matches()) {
					String name = c.group(2);
					Comment note = Comment.of(c.group(4));
					if ((DOCUMENTED_CONSTS.contains(name) || note.hasList()) && declared.add(key)) {
						out.add(new Raw(name, OptionKind.CONST,
							new PackOption.Declaration(path, lineNo, OptionKind.CONST, false, c.group(3)),
							note.values(), note.hasList(), c.group(1), note.tooltip()));
					}
					continue;
				}
			}
			if (!hashLine) {
				continue;
			}
			Matcher v = VALUE_DECL.matcher(line);
			if (v.matches() && VALUE_LIST.matcher(v.group(3)).find()) {
				if (declared.add(key)) {
					Comment note = Comment.of(v.group(3));
					out.add(new Raw(v.group(1), OptionKind.VALUE,
						new PackOption.Declaration(path, lineNo, OptionKind.VALUE, false, v.group(2)),
						note.values(), true, "", note.tooltip()));
				}
				continue;
			}
			Matcher b = BOOL_DECL.matcher(line);
			if (b.matches()) {
				String name = b.group(3);
				boolean commented = b.group(2) != null;
				if (tested.contains(name) && declared.add(key)) {
					out.add(new Raw(name, OptionKind.BOOLEAN,
						new PackOption.Declaration(path, lineNo, OptionKind.BOOLEAN, commented, commented ? "false" : "true"),
						List.of(), false, "", tooltipOf(b.group(4))));
				}
			}
		}
	}

	private static String tooltipOf(String rest) {
		if (rest == null) return "";
		Matcher m = TOOLTIP.matcher(rest);
		return m.matches() ? m.group(1) : "";
	}

	/**
	 * A comment's value list, and its text with the list removed: {@code tip [a b]} and {@code [a b] tip} both give the tip
	 * {@code tip}. An absent comment gives no list and an empty tip.
	 */
	private record Comment(List<String> values, boolean hasList, String tooltip) {
		static Comment of(String text) {
			if (text == null) return new Comment(List.of(), false, "");
			Matcher m = VALUE_LIST.matcher(text);
			if (!m.find()) return new Comment(List.of(), false, tooltipOf(text));
			String rest = text.substring(0, m.start()) + " " + text.substring(m.end());
			return new Comment(splitList(m.group(1)), true, tooltipOf(rest));
		}
	}

	private static List<String> splitList(String text) {
		List<String> out = new ArrayList<>();
		for (String v : text.strip().split("\\s+")) {
			if (!v.isEmpty()) out.add(v);
		}
		return out;
	}

	/**
	 * Groups the declarations by name. Declarations that disagree in kind or in default make the option ambiguous: it keeps
	 * the first default and cannot be changed (shaders.txt, ambiguity rule).
	 */
	private static OptionCatalog merge(List<Raw> raws, List<String> warnings, List<String> files) {
		Map<String, List<Raw>> byName = new LinkedHashMap<>();
		for (Raw r : raws) byName.computeIfAbsent(r.name(), k -> new ArrayList<>()).add(r);
		Map<String, PackOption> options = new LinkedHashMap<>();
		for (Map.Entry<String, List<Raw>> e : byName.entrySet()) {
			List<Raw> rs = e.getValue();
			Raw first = rs.get(0);
			Set<OptionKind> kinds = new LinkedHashSet<>();
			Set<String> defaults = new LinkedHashSet<>();
			Set<String> values = new LinkedHashSet<>();
			boolean hasList = false;
			String tooltip = "";
			List<PackOption.Declaration> decls = new ArrayList<>();
			for (Raw r : rs) {
				kinds.add(r.kind());
				defaults.add(r.decl().defaultValue());
				values.addAll(r.values());
				hasList |= r.hasList();
				if (tooltip.isEmpty()) tooltip = r.tooltip();
				decls.add(r.decl());
			}
			boolean ambiguous = kinds.size() > 1 || defaults.size() > 1;
			if (ambiguous) {
				warnings.add("option " + e.getKey() + ": declarations disagree (kinds " + kinds + ", defaults " + defaults
					+ "); the option is disabled");
			}
			if (hasList && !values.contains(first.decl().defaultValue())) values.add(first.decl().defaultValue());
			options.put(e.getKey(), new PackOption(e.getKey(), first.kind(), first.decl().defaultValue(),
				hasList ? new ArrayList<>(values) : List.of(), hasList, first.type(), tooltip, decls, ambiguous));
		}
		return new OptionCatalog(options, warnings, files);
	}
}
