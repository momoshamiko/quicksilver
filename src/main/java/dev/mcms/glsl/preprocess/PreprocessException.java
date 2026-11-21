// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.preprocess;

import java.util.List;

/** Thrown by {@link Preprocessor#process} when the source has a preprocessor error (an active {@code #error}, and so on). */
public final class PreprocessException extends Exception {
	private final List<Diagnostic> diagnostics;

	public PreprocessException(Diagnostic diagnostic) {
		this(List.of(diagnostic));
	}

	public PreprocessException(List<Diagnostic> diagnostics) {
		super(diagnostics.isEmpty() ? "preprocessing failed" : diagnostics.get(0).toString());
		this.diagnostics = List.copyOf(diagnostics);
	}

	/** The errors that caused the failure, the first one primary. */
	public List<Diagnostic> diagnostics() {
		return diagnostics;
	}
}
