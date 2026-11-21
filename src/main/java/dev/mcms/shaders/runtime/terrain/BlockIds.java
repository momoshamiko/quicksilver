// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.terrain;

import dev.mcms.shaders.pack.IdMapFile;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * The game arm of the {@code block.properties} matching (plan.md E4): {@link BlockIdMatcher} decides, this class
 * answers the mesh builder's worker threads. A block state is a stable interned object, so the answer is computed once
 * per state and cached ({@code block.properties} holds tens of lines, the world thousands of states; the pack's
 * substitute ids are the values the pack's {@code mc_Entity.x} reads, engine-pack-format.md section 6).
 *
 * <p>Property values are named with {@link Property#getName(Comparable)}, the vanilla state name ({@code floor},
 * {@code left}), never {@code toString()}: eight vanilla enums have no {@code toString} override, so their constant
 * names ({@code FLOOR}) would never match a {@code block.properties} predicate.
 *
 * <p>Unlisted blocks get -1, and so does every quad while no set is active.
 */
public final class BlockIds {

	private final BlockIdMatcher matcher;
	private final Map<BlockState, Integer> cache = new ConcurrentHashMap<>();

	private BlockIds(BlockIdMatcher matcher) {
		this.matcher = matcher;
	}

	/** The empty table, used while no set is active. */
	public static final BlockIds EMPTY = new BlockIds(BlockIdMatcher.of(
		new IdMapFile("block", Map.of(), Map.of(), java.util.List.of())));

	/** Builds the table from the active pack's {@code block.properties}. */
	public static BlockIds of(IdMapFile blocks) {
		return new BlockIds(BlockIdMatcher.of(blocks));
	}

	/** The pack's id of the state, or -1 when the pack lists it nowhere. */
	public int idOf(BlockState state) {
		return this.cache.computeIfAbsent(state, s -> {
			var key = BuiltInRegistries.BLOCK.getKey(s.getBlock());
			Map<String, String> properties = new LinkedHashMap<>();
			for (Property<?> p : s.getProperties()) {
				putProperty(properties, s, p);
			}
			return this.matcher.id(key.getNamespace(), key.getPath(), properties).orElse(-1);
		});
	}

	/** The vanilla name of a property value ({@link Property#getName(Comparable)}), the text a predicate compares with. */
	static <T extends Comparable<T>> String valueName(Property<T> property, T value) {
		return property.getName(value);
	}

	private static <T extends Comparable<T>> void putProperty(Map<String, String> into, BlockState state, Property<T> p) {
		into.put(p.getName(), valueName(p, state.getValue(p)));
	}
}
