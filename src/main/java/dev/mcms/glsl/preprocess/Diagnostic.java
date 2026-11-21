// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.preprocess;

/** One preprocessor message: its severity, the file (path relative to {@code shaders/}) and line it came from, and text. */
public record Diagnostic(Severity severity, String path, int line, String message) {
	public enum Severity {
		WARNING, ERROR
	}

	public Diagnostic {
	}

	@Override
	public String toString() {
		return path + ":" + line + ": " + severity.name().toLowerCase() + ": " + message;
	}
}
