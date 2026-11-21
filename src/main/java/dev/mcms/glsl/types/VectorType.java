// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.types;

/**
 * A vector of 2 to 4 components ({@code vec2} to {@code dvec4}, {@code bvec4}). Instances are shared: {@link #of}
 * returns the same object per kind and size.
 */
public record VectorType(ScalarKind component, int size) implements GlslType {
	public static final VectorType VEC2 = new VectorType(ScalarKind.FLOAT, 2);
	public static final VectorType VEC3 = new VectorType(ScalarKind.FLOAT, 3);
	public static final VectorType VEC4 = new VectorType(ScalarKind.FLOAT, 4);
	public static final VectorType IVEC2 = new VectorType(ScalarKind.INT, 2);
	public static final VectorType IVEC3 = new VectorType(ScalarKind.INT, 3);
	public static final VectorType IVEC4 = new VectorType(ScalarKind.INT, 4);
	public static final VectorType UVEC2 = new VectorType(ScalarKind.UINT, 2);
	public static final VectorType UVEC3 = new VectorType(ScalarKind.UINT, 3);
	public static final VectorType UVEC4 = new VectorType(ScalarKind.UINT, 4);
	public static final VectorType DVEC2 = new VectorType(ScalarKind.DOUBLE, 2);
	public static final VectorType DVEC3 = new VectorType(ScalarKind.DOUBLE, 3);
	public static final VectorType DVEC4 = new VectorType(ScalarKind.DOUBLE, 4);
	public static final VectorType BVEC2 = new VectorType(ScalarKind.BOOL, 2);
	public static final VectorType BVEC3 = new VectorType(ScalarKind.BOOL, 3);
	public static final VectorType BVEC4 = new VectorType(ScalarKind.BOOL, 4);

	public static VectorType of(ScalarKind kind, int size) {
		if (size < 2 || size > 4) {
			throw new IllegalArgumentException("vector size " + size);
		}
		return switch (kind) {
			case FLOAT -> new VectorType[] {VEC2, VEC3, VEC4}[size - 2];
			case INT -> new VectorType[] {IVEC2, IVEC3, IVEC4}[size - 2];
			case UINT -> new VectorType[] {UVEC2, UVEC3, UVEC4}[size - 2];
			case DOUBLE -> new VectorType[] {DVEC2, DVEC3, DVEC4}[size - 2];
			case BOOL -> new VectorType[] {BVEC2, BVEC3, BVEC4}[size - 2];
		};
	}

	@Override
	public String toString() {
		return component.prefix() + "vec" + size;
	}

	@Override
	public ScalarKind componentKind() {
		return component;
	}
}
