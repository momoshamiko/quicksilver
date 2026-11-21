// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.types;

/**
 * A matrix of 2 to 4 columns and 2 to 4 rows, column-major ({@code matCxR}: {@code cols} columns of {@code rows}
 * components). {@code mat4} and {@code mat4x4} are the same type; the GLSL spelling of the square form is
 * {@code matN}. Float matrices exist since GLSL 1.10, double matrices since 4.00.
 */
public record MatrixType(ScalarKind component, int cols, int rows) implements GlslType {
	public static final MatrixType MAT2 = new MatrixType(ScalarKind.FLOAT, 2, 2);
	public static final MatrixType MAT3 = new MatrixType(ScalarKind.FLOAT, 3, 3);
	public static final MatrixType MAT4 = new MatrixType(ScalarKind.FLOAT, 4, 4);

	public MatrixType {
		if (cols < 2 || cols > 4 || rows < 2 || rows > 4) {
			throw new IllegalArgumentException("matrix shape " + cols + "x" + rows);
		}
	}

	public static MatrixType of(ScalarKind kind, int cols, int rows) {
		return new MatrixType(kind, cols, rows);
	}

	/** The type of one column ({@code mat4x3} columns are {@code vec3}). */
	public VectorType columnType() {
		return VectorType.of(component, rows);
	}

	@Override
	public String toString() {
		String base = component.prefix() + "mat";
		return cols == rows ? base + cols : base + cols + "x" + rows;
	}

	@Override
	public ScalarKind componentKind() {
		return component;
	}
}
