// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.ast;

/** Precision qualifier words (GLSL 4.60 specification, section 4.5). */
public enum PrecisionKind {
	HIGH("highp"),
	MEDIUM("mediump"),
	LOW("lowp");

	private final String word;

	PrecisionKind(String word) {
		this.word = word;
	}

	public String word() {
		return word;
	}
}
