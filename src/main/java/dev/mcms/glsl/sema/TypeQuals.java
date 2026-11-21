// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.sema;

import dev.mcms.glsl.ast.InterpKind;
import dev.mcms.glsl.ast.MemoryKind;
import dev.mcms.glsl.ast.PrecisionKind;
import dev.mcms.glsl.ast.StorageKind;
import dev.mcms.glsl.types.ImageFormat;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The qualifiers of one declaration, normalized from their source order (GLSL 4.60 specification,
 * section 4.7). Layout values are folded constants; a layout item without a value maps to {@link Boolean#TRUE}.
 */
public record TypeQuals(Set<StorageKind> storage, InterpKind interpolation, Set<MemoryKind> memory,
		PrecisionKind precision, boolean invariant, boolean precise, Map<String, Object> layout) {
	public TypeQuals {
		storage = Set.copyOf(storage);
		memory = Set.copyOf(memory);
		layout = Map.copyOf(layout);
	}

	public static final TypeQuals NONE = new TypeQuals(EnumSet.noneOf(StorageKind.class), null,
			EnumSet.noneOf(MemoryKind.class), null, false, false, Map.of());

	/** The first storage qualifier of a kind family, or null. */
	public StorageKind storageKind() {
		for (StorageKind k : storage) {
			// centroid/sample/patch return their base if present
			return k;
		}
		return null;
	}

	public boolean isConst() {
		return storage.contains(StorageKind.CONST);
	}

	public boolean isUniform() {
		return storage.contains(StorageKind.UNIFORM);
	}

	public boolean isBuffer() {
		return storage.contains(StorageKind.BUFFER);
	}

	public boolean isShared() {
		return storage.contains(StorageKind.SHARED);
	}

	public boolean isInput() {
		return storage.contains(StorageKind.IN) || storage.contains(StorageKind.ATTRIBUTE)
				|| storage.contains(StorageKind.VARYING);
	}

	public boolean isOutput() {
		return storage.contains(StorageKind.OUT) || storage.contains(StorageKind.VARYING);
	}

	public boolean isVaryingLegacy() {
		return storage.contains(StorageKind.VARYING) || storage.contains(StorageKind.ATTRIBUTE);
	}

	/** True when this is an interface declaration (in/out/attribute/varying, uniform or buffer). */
	public boolean isInterface() {
		return !storage.isEmpty() && !(storage.size() == 1 && storage.contains(StorageKind.CONST));
	}

	/** The image format layout qualifier, or null. */
	public ImageFormat imageFormat() {
		for (Map.Entry<String, Object> e : layout.entrySet()) {
			ImageFormat f = ImageFormat.fromWord(e.getKey());
			if (f != null && Boolean.TRUE.equals(e.getValue())) {
				return f;
			}
		}
		return null;
	}

	/** Builder keeps the source order of layout items. */
	public static final class Builder {
		private final Set<StorageKind> storage = EnumSet.noneOf(StorageKind.class);
		private final Set<MemoryKind> memory = EnumSet.noneOf(MemoryKind.class);
		private final Map<String, Object> layout = new LinkedHashMap<>();
		private InterpKind interpolation;
		private PrecisionKind precision;
		private boolean invariant;
		private boolean precise;

		public void storage(StorageKind kind) {
			if (kind == StorageKind.IN || kind == StorageKind.OUT || kind == StorageKind.INOUT) {
				storage.remove(StorageKind.ATTRIBUTE);
				storage.remove(StorageKind.VARYING);
			}
			storage.add(kind);
		}

		public void memory(MemoryKind kind) {
			memory.add(kind);
		}

		public void interpolation(InterpKind kind) {
			interpolation = kind;
		}

		public void precision(PrecisionKind kind) {
			precision = kind;
		}

		public void invariant(boolean value) {
			invariant = value;
		}

		public void precise(boolean value) {
			precise = value;
		}

		public void layout(String name, Object value) {
			layout.put(name, value);
		}

		public TypeQuals build() {
			return new TypeQuals(storage, interpolation, memory, precision, invariant, precise, layout);
		}
	}
}
