// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.ast;

/** Memory qualifier words (GLSL 4.60 specification, section 4.3.8). */
public enum MemoryKind {
	COHERENT("coherent"),
	VOLATILE("volatile"),
	RESTRICT("restrict"),
	READONLY("readonly"),
	WRITEONLY("writeonly");

	private final String word;

	MemoryKind(String word) {
		this.word = word;
	}

	public String word() {
		return word;
	}
}
