// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.ast;

/** Storage and parameter qualifier words (GLSL 4.60 specification, section 4.3). {@code centroid}, {@code sample} and
 * {@code patch} modify {@code in}/{@code out} and stand as their own qualifiers so the source order is preserved. */
public enum StorageKind {
	CONST("const"),
	ATTRIBUTE("attribute"),
	VARYING("varying"),
	UNIFORM("uniform"),
	BUFFER("buffer"),
	SHARED("shared"),
	IN("in"),
	OUT("out"),
	INOUT("inout"),
	CENTROID("centroid"),
	SAMPLE("sample"),
	PATCH("patch");

	private final String word;

	StorageKind(String word) {
		this.word = word;
	}

	public String word() {
		return word;
	}
}
