// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.ast;

/** Interpolation qualifier words (GLSL 4.60 specification, section 4.3.9). */
public enum InterpKind {
	FLAT("flat"),
	SMOOTH("smooth"),
	NOPERSPECTIVE("noperspective");

	private final String word;

	InterpKind(String word) {
		this.word = word;
	}

	public String word() {
		return word;
	}
}
