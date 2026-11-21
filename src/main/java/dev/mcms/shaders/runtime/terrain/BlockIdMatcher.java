// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.terrain;

import dev.mcms.shaders.pack.IdEntry;
import dev.mcms.shaders.pack.IdMapFile;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The matching of a {@code block.properties} line to a block state (plan.md E4; engine-pack-format.md section 6; the
 * OptiFine doc's grammar, properties_files.txt "Blocks"): a name part {@code <namespace:>name} (namespace defaults to
 * {@code minecraft}) and state predicates {@code :prop=v1,v2}, several properties separated by colons. An entry
 * without predicates matches every state of the named block; an entry with predicates matches a state whose every
 * named property holds one of the listed values. The first id in file order wins when entries overlap.
 *
 * <p>The matcher works on the plain triple (namespace, name, property values), so the matching itself is testable
 * without the game; {@link BlockIds} is the game arm that walks the registry and caches per state.
 */
public final class BlockIdMatcher {

	private record Line(int id, IdEntry entry) {
	}

	private final List<Line> lines;
	private final Map<String, List<Line>> byName = new LinkedHashMap<>();

	/** Builds the matcher from the pack's parsed {@code block.properties} (an empty file matches nothing). */
	public static BlockIdMatcher of(IdMapFile blocks) {
		List<Line> lines = new ArrayList<>();
		blocks.ids().forEach((id, entries) -> {
			for (IdEntry e : entries) {
				lines.add(new Line(id, e));
			}
		});
		return new BlockIdMatcher(lines);
	}

	private BlockIdMatcher(List<Line> lines) {
		this.lines = lines;
		for (Line line : lines) {
			this.byName.computeIfAbsent(line.entry().namespace() + ":" + line.entry().name(), k -> new ArrayList<>())
				.add(line);
		}
	}

	/**
	 * The id of the state, or empty when the pack lists no line that matches it. The state's property values are the
	 * state's own name-to-value map (a property the entry does not name is not a constraint).
	 */
	public Optional<Integer> id(String namespace, String name, Map<String, String> properties) {
		// The candidate list holds this block's lines in file order (the ids map keeps insertion order), so the
		// first matching line is the first matching line of the file too.
		List<Line> candidates = this.byName.get(namespace + ":" + name);
		if (candidates == null) {
			return Optional.empty();
		}
		for (Line line : candidates) {
			if (matches(line.entry(), properties)) {
				return Optional.of(line.id());
			}
		}
		return Optional.empty();
	}
	private static boolean matches(IdEntry entry, Map<String, String> properties) {
		if (entry.state().isEmpty()) {
			return true;
		}
		for (Map.Entry<String, List<String>> predicate : entry.state().entrySet()) {
			String value = properties.get(predicate.getKey());
			if (value == null || !predicate.getValue().contains(value)) {
				return false;
			}
		}
		return true;
	}
}
