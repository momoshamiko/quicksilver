// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.entities;

import java.util.Arrays;

/**
 * A table of ints indexed by a registry id (plan.md E4, per-entity values). {@link EntityCapture} fills one per id map
 * when a set loads, so a lookup on the frame path is one array read: no key string, no hash, no allocation. An index
 * outside the table (an object the registry does not hold) reads the table's absent value.
 */
final class DenseIds {

	private final int[] values;
	private final int absent;

	DenseIds(int size, int absent) {
		this.values = new int[size];
		this.absent = absent;
		Arrays.fill(values, absent);
	}

	/** The value at {@code index}, or the absent value when the index is outside the table. */
	int get(int index) {
		return index >= 0 && index < values.length ? values[index] : absent;
	}

	/** Sets the value at {@code index}; an index outside the table is ignored. */
	void set(int index, int value) {
		if (index >= 0 && index < values.length) {
			values[index] = value;
		}
	}

	/** The number of slots. */
	int size() {
		return values.length;
	}
}
