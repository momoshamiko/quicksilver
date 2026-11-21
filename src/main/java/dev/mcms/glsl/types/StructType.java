// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.types;

import java.util.List;

/**
 * A struct type. Each struct <em>declaration</em> in a unit creates one instance: equality is identity of the
 * {@code id} (two structs with the same shape declared in different scopes are different types, GLSL 4.60
 * specification, section 4.1.8). {@code name} is null for the anonymous form {@code struct { ... }}.
 */
public record StructType(String name, List<StructField> fields, int id) implements GlslType {
	public StructType {
		fields = List.copyOf(fields);
	}

	/** The field with the given name, or null. */
	public StructField field(String fieldName) {
		for (StructField f : fields) {
			if (f.name().equals(fieldName)) {
				return f;
			}
		}
		return null;
	}

	@Override
	public boolean equals(Object o) {
		return o == this;
	}

	@Override
	public int hashCode() {
		return id;
	}

	@Override
	public String toString() {
		return name != null ? name : "struct#" + id;
	}
}
