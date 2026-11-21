// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.types;

/**
 * An array of a type. {@code size} is null for an unsized array ({@code float a[]}, the last member of a
 * shader storage block, an array constructor with inferred size).
 */
public record ArrayType(GlslType element, Integer size) implements GlslType {
	public ArrayType {
		if (size != null && size < 0) {
			throw new IllegalArgumentException("negative array size " + size);
		}
	}

	/** The GLSL spelling in declaration order: {@code float[2][3]} is an array of size 2 whose elements are float[3]. */
	@Override
	public String toString() {
		StringBuilder dims = new StringBuilder();
		GlslType base = this;
		while (base instanceof ArrayType a) {
			dims.append(a.size == null ? "[]" : "[" + a.size + "]");
			base = a.element;
		}
		return base + dims.toString();
	}
}
