// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.preprocess;

/** The file and physical line an output line of {@link PreprocessResult} came from. */
public record LineOrigin(String path, int line) {
	public LineOrigin {
	}
}
