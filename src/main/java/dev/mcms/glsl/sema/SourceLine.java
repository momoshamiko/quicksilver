// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.sema;

/**
 * One line of the preprocessed text traced to its original file: the caller turns the preprocessor's line map into
 * these (the preprocessor's own origin record is not a dependency of this package).
 */
public record SourceLine(String path, int line) {
}
