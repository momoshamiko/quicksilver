// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.pack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Which shader folder each dimension uses (engine-pack-format.md section 2; OptiFine shaders.properties, dimension section;
 * Iris dimension.properties page). The rule, in order: a folder that names the dimension in {@code dimension.<folder>=...};
 * else the wildcard folder ({@code *}); else the legacy {@code worldN} folder of that dimension when the pack has it; else the
 * base folder ({@code shaders/} itself, {@code ""}).
 *
 * <p>The base folder serves a dimension with no folder of its own. OptiFine: "When the world folder is present the shaders
 * will be loaded only from there ignoring the default folder", so a folder replaces the base folder only for its own
 * dimension. The Iris page reads more strictly ("if any worldN folders are present, shaders will only be loaded from worldN
 * folders"). We follow the per-dimension reading: the reference (Vitrail) loads the base folder for the overworld of Solas,
 * which has world-1 and world1 and no world0 (results/2026-10-26-pins-dims, metal-vitrail-solas run1 log). The E1 report
 * records this as difference 6.
 *
 * @param folderDimensions folder name to the dimension ids it lists, with {@code *} kept
 * @param problems         a declared folder that the pack does not have, a dimension listed twice
 */
public record DimensionRules(Map<String, List<String>> folderDimensions, List<String> problems) {
	/** A row of the report: one dimension (or the wildcard's other dimensions) and the folder that serves it. */
	public record Row(String label, Optional<Dimension> dimension, String folder, String reason) {
	}

	public DimensionRules {
		folderDimensions = Collections.unmodifiableMap(new LinkedHashMap<>(folderDimensions));
		problems = List.copyOf(problems);
	}

	/** Reads the entries of a preprocessed dimension.properties; a dimension id without a namespace gets {@code minecraft}. */
	public static DimensionRules parse(PropertiesText.Parsed parsed) {
		List<String> problems = new ArrayList<>(parsed.problems());
		Map<String, List<String>> out = new LinkedHashMap<>();
		for (PropertiesText.Entry e : parsed.entries()) {
			if (!e.key().startsWith("dimension.")) {
				problems.add("line " + e.line() + ": key \"" + e.key() + "\" is not dimension.<folder>");
				continue;
			}
			String folder = e.key().substring("dimension.".length());
			List<String> dims = new ArrayList<>();
			for (String token : e.value().strip().split("\\s+")) {
				if (token.isEmpty()) continue;
				if (token.equals("*")) {
					dims.add(token);
					continue;
				}
				String id = token.contains(":") ? token : "minecraft:" + token;
				for (var pair : out.entrySet()) {
					if (!pair.getKey().equals(folder) && pair.getValue().contains(id)) {
						problems.add("dimension \"" + id + "\" is listed for both " + pair.getKey() + " and " + folder);
					}
				}
				dims.add(id);
			}
			out.put(folder, dims);
		}
		return new DimensionRules(out, problems);
	}

	/** The folder that names the dimension, if one does. */
	public Optional<String> declaredFolder(Dimension d) {
		for (var e : folderDimensions.entrySet()) {
			for (String name : d.names) {
				if (e.getValue().contains(name)) return Optional.of(e.getKey());
			}
		}
		return Optional.empty();
	}

	/** The folder with the wildcard, if one has it. */
	public Optional<String> wildcardFolder() {
		for (var e : folderDimensions.entrySet()) {
			if (e.getValue().contains("*")) return Optional.of(e.getKey());
		}
		return Optional.empty();
	}

	/**
	 * The folder that serves a vanilla dimension: a folder name, {@code ""} for the base folder ({@code shaders/} itself), or
	 * null when the folder that dimension.properties names is missing. A dimension with no folder of its own gets the base
	 * folder, whether or not other dimensions have worldN folders.
	 *
	 * @param topFolders the folder names directly under {@code shaders/}
	 */
	public String folderFor(Dimension d, Set<String> topFolders) {
		Optional<String> declared = declaredFolder(d);
		if (declared.isPresent()) return topFolders.contains(declared.get()) ? declared.get() : null;
		Optional<String> wildcard = wildcardFolder();
		if (wildcard.isPresent()) return topFolders.contains(wildcard.get()) ? wildcard.get() : null;
		return topFolders.contains(d.legacyFolder) ? d.legacyFolder : "";
	}

	/**
	 * The rows of the report: the three vanilla dimensions, and a row for the wildcard folder when it serves no vanilla
	 * dimension (modded dimensions).
	 */
	public List<Row> rows(Set<String> topFolders) {
		List<Row> out = new ArrayList<>();
		Set<String> served = new HashSet<>();
		for (Dimension d : Dimension.values()) {
			String folder = folderFor(d, topFolders);
			String reason = reason(d, folder);
			out.add(new Row(d.name().charAt(0) + d.name().substring(1).toLowerCase(Locale.ROOT), Optional.of(d), folder, reason));
			if (folder != null) served.add(folder);
		}
		Optional<String> wildcard = wildcardFolder();
		if (wildcard.isPresent() && !served.contains(wildcard.get())) {
			out.add(new Row("Other dimensions (" + wildcard.get() + ")", Optional.empty(),
				topFolders.contains(wildcard.get()) ? wildcard.get() : null, "wildcard *"));
		}
		return out;
	}

	private String reason(Dimension d, String folder) {
		if (declaredFolder(d).isPresent()) {
			return folder == null ? "dimension.properties names " + d.id + ", and the pack has no such folder"
				: "dimension.properties names " + d.id;
		}
		if (wildcardFolder().isPresent()) {
			return folder == null ? "dimension.properties wildcard folder is missing" : "dimension.properties wildcard";
		}
		return folder.isEmpty() ? "no " + d.legacyFolder + " folder: base folder" : "legacy " + d.legacyFolder + " folder";
	}
}
