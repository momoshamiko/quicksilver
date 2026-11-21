// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.sema;

import dev.mcms.glsl.ast.Pos;

/**
 * One semantic diagnostic: severity, a stable code (for example {@code unknown-identifier}, {@code swizzle-mixed-sets}),
 * the position in the preprocessed text, and, once a line map was given, the original pack file and line.
 */
public record SemaDiagnostic(Severity severity, Pos pos, SourceLine origin, String code, String message) {
	public enum Severity {
		ERROR, WARNING
	}

	static SemaDiagnostic error(Pos pos, String code, String message) {
		return new SemaDiagnostic(Severity.ERROR, pos, null, code, message);
	}

	static SemaDiagnostic warning(Pos pos, String code, String message) {
		return new SemaDiagnostic(Severity.WARNING, pos, null, code, message);
	}

	SemaDiagnostic withOrigin(SourceLine line) {
		return new SemaDiagnostic(severity, pos, line, code, message);
	}

	@Override
	public String toString() {
		String where = origin != null ? origin.path() + ":" + origin.line() : String.valueOf(pos);
		return (severity == Severity.ERROR ? "error" : "warning") + "[" + code + "] " + where + ": " + message;
	}
}
