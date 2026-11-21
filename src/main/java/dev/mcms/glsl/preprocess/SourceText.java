// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.preprocess;

/** One source file handed to the preprocessor: a path relative to the pack's {@code shaders/} root and its text. */
public record SourceText(String path, String text) {
	public SourceText {
	}
}
