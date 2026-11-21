// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.pack;

/** The three kinds of shader option (engine-pack-format.md section 4). */
public enum OptionKind {
	/** {@code #define X} (on) or {@code // #define X} (off), recognized only when the file also tests {@code X}. */
	BOOLEAN,
	/** {@code #define X v // [a b c]}: a value with an allowed list. */
	VALUE,
	/** {@code const <type> X = v; // [a b]} for one of the documented const names. */
	CONST
}
