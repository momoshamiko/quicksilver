// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.types;

/** The five scalar kinds of GLSL: {@code bool}, {@code int}, {@code uint}, {@code float}, {@code double}. */
public enum ScalarKind {
	BOOL("bool", "b"),
	INT("int", "i"),
	UINT("uint", "u"),
	FLOAT("float", ""),
	DOUBLE("double", "d");

	private final String word;
	private final String prefix;

	ScalarKind(String word, String prefix) {
		this.word = word;
		this.prefix = prefix;
	}

	/** The GLSL word of the scalar type. */
	public String word() {
		return word;
	}

	/** The prefix of vector and matrix words: none for {@code vec3}/{@code mat4}, {@code i} for {@code ivec3},
	 * {@code b}, {@code u}, {@code d}. */
	public String prefix() {
		return prefix;
	}

	/** True for {@code int} and {@code uint}. */
	public boolean isInteger() {
		return this == INT || this == UINT;
	}

	/** True for {@code float} and {@code double}. */
	public boolean isFloating() {
		return this == FLOAT || this == DOUBLE;
	}

	/** True for numeric kinds (not {@code bool}). */
	public boolean isNumeric() {
		return this != BOOL;
	}
}
