// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.expr;

/**
 * The value types of the custom-uniform expression language: {@code uniform.<float|int|bool|vec2|vec3|vec4>} and
 * {@code variable.<type>} (OptiFine doc shaders.properties, "Custom uniforms"; engine-pack-format.md §13). Matrices exist
 * only as scope-supplied names read through element access ({@code matrix.0.1}); an expression itself never produces one.
 */
public enum ExprType {
	FLOAT, INT, BOOL, VEC2, VEC3, VEC4, MAT3, MAT4;

	/** Component count of a vector type; 0 for scalars and matrices. */
	public int components() {
		return switch (this) {
			case VEC2 -> 2;
			case VEC3 -> 3;
			case VEC4 -> 4;
			default -> 0;
		};
	}

	public boolean isNumeric() {
		return this == FLOAT || this == INT;
	}

	public boolean isVector() {
		return components() > 0;
	}

	public boolean isMatrix() {
		return this == MAT3 || this == MAT4;
	}

	/** Columns of a matrix type (matrices are square); 0 otherwise. */
	public int columns() {
		return this == MAT4 ? 4 : this == MAT3 ? 3 : 0;
	}
}
