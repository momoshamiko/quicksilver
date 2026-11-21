// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.preprocess;

/** Input mode of the preprocessor: GLSL program text or the conditionals of an OptiFine/Iris {@code .properties} file. */
public enum Mode {
	/** Full C-style preprocessing: directives, macros, includes (engine-pack-format.md §3). */
	GLSL,
	/**
	 * Conditionals only, evaluated with the predefined macros; every other line starting with {@code #} is a comment and
	 * produces no output. No macro expansion, no line splicing (engine-pack-format.md §5).
	 */
	PROPERTIES
}
