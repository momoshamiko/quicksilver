// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.entities;

import dev.mcms.shaders.pack.IdEntry;
import dev.mcms.shaders.pack.IdMapFile;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The pack's id maps matched to game names (plan.md E4, engine-pack-format.md section 6; the Iris docs of
 * {@code entity.properties}, {@code block.properties} and the id uniforms): {@code entity.<id>} for
 * {@code entityId}, {@code block.<id>} for {@code blockEntityId} (the block the block entity is), {@code item.<id>}
 * for {@code currentRenderedItemId}. A name matches when namespace and name are equal (a short name is
 * {@code minecraft}'s), and for a block every property the entry lists matches: a listed property matches any of its
 * comma-separated values, an unlisted property any value. Two entries that both match one object are the author's
 * error (the docs allow one id an object); the first line in file order wins. An object no entry lists reads
 * {@link #UNLISTED} once the file held entries and {@link #NO_FILE} when it held none (the id uniforms' documented
 * values: 65535 for "present but not listed", 0 for "no mapping").
 */
public final class EntityIds {

	/** The uniform value for a file that is present but lists no matching entry (Iris id uniforms doc). */
	public static final int UNLISTED = 65535;
	/** The uniform value when the pack ships no entries for the kind at all (Iris id uniforms doc). */
	public static final int NO_FILE = 0;

	private final Map<String, Integer> entities;
	private final Map<String, List<BlockRule>> blocks;
	private final Map<String, Integer> items;

	private EntityIds(Map<String, Integer> entities, Map<String, List<BlockRule>> blocks,
			Map<String, Integer> items) {
		this.entities = entities;
		this.blocks = blocks;
		this.items = items;
	}

	/** The three maps of a loaded set ({@link IdMapFile#kind()} decides which is which). */
	public static EntityIds of(IdMapFile entityMap, IdMapFile blockMap, IdMapFile itemMap) {
		Map<String, Integer> entities = new LinkedHashMap<>();
		collect(entityMap, "entity", entities);
		Map<String, List<BlockRule>> blocks = new LinkedHashMap<>();
		for (Map.Entry<Integer, List<IdEntry>> e : blockMap.ids().entrySet()) {
			for (IdEntry entry : e.getValue()) {
				blocks.computeIfAbsent(key(entry.namespace(), entry.name()), k -> new ArrayList<>())
					.add(new BlockRule(e.getKey(), entry.state()));
			}
		}
		Map<String, Integer> items = new LinkedHashMap<>();
		collect(itemMap, "item", items);
		return new EntityIds(entities, blocks, items);
	}

	private static void collect(IdMapFile map, String kind, Map<String, Integer> into) {
		for (Map.Entry<Integer, List<IdEntry>> e : map.ids().entrySet()) {
			for (IdEntry entry : e.getValue()) {
				if (!entry.state().isEmpty()) {
					continue;  // only blocks carry state; an entity or item entry with state parts is the grammar's reject
				}
				into.putIfAbsent(key(entry.namespace(), entry.name()), e.getKey());
			}
		}
	}

	/** {@code entityId}: the id of the entity type ({@code minecraft:sheep}), or the doc's unlisted/absent value. */
	public int entity(String namespace, String name) {
		return entities.getOrDefault(key(namespace, name), entities.isEmpty() ? NO_FILE : UNLISTED);
	}

	/**
	 * {@code blockEntityId}: the id of the block the block entity is, matched against the block's state (each property
	 * the entry lists must hold one of its listed values). The state may be null when only the name is known; then
	 * only entries without property constraints match.
	 */
	public int blockEntity(String namespace, String name, Map<String, String> state) {
		return block(namespace, name, state);
	}

	/** The value an entity no entry lists reads: unlisted once the file held entries, absent otherwise. */
	public int defaultEntity() {
		return entities.isEmpty() ? NO_FILE : UNLISTED;
	}

	/** The value a block entity no entry lists reads. */
	public int defaultBlockEntity() {
		return blocks.isEmpty() ? NO_FILE : UNLISTED;
	}

	/** The value an item no entry lists reads. */
	public int defaultItem() {
		return items.isEmpty() ? NO_FILE : UNLISTED;
	}

	/** The same block matching for {@code mc_Entity} (E4-B's terrain side reads it too). */
	public int block(String namespace, String name, Map<String, String> state) {
		List<BlockRule> rules = blocks.get(key(namespace, name));
		if (rules == null) {
			return blocks.isEmpty() ? NO_FILE : UNLISTED;
		}
		for (BlockRule rule : rules) {
			if (rule.matches(state)) {
				return rule.id;
			}
		}
		return UNLISTED;
	}

	/** {@code currentRenderedItemId}: the id of the item, or the doc's unlisted/absent value. */
	public int item(String namespace, String name) {
		return items.getOrDefault(key(namespace, name), items.isEmpty() ? NO_FILE : UNLISTED);
	}

	/**
	 * {@code heldItemId} for a held item's lookup value ({@link #item}): a listed item keeps its id, and an item the
	 * file does not list reads -1 (Iris "ID Uniforms": -1 when the file is present and the item is not in it). Without a
	 * file the lookup gives 0, which stays 0.
	 */
	public static int held(int itemValue) {
		return itemValue == UNLISTED ? -1 : itemValue;
	}

	/** {@code heldItemId} of an empty hand: 0 without an item file, -1 with one (the hand holds nothing it lists). */
	public int heldEmpty() {
		return items.isEmpty() ? NO_FILE : -1;
	}

	private static String key(String namespace, String name) {
		return namespace + ':' + name;
	}

	/** One {@code block.<id>} entry: the id and the property constraints the state must satisfy. */
	private record BlockRule(int id, Map<String, List<String>> state) {
		boolean matches(Map<String, String> blockState) {
			for (Map.Entry<String, List<String>> constraint : state.entrySet()) {
				String value = blockState == null ? null : blockState.get(constraint.getKey());
				if (value == null || !constraint.getValue().contains(value)) {
					return false;
				}
			}
			return true;
		}
	}
}
