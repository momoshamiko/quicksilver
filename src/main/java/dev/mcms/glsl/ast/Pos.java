// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.ast;

/** A 1-based line and column in the preprocessed GLSL text. */
public record Pos(int line, int column) {
	@Override
	public String toString() {
		return line + ":" + column;
	}
}
