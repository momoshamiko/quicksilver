// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.sema;

/** The shader stage a program is analyzed for (the pack format has no geometry or tessellation programs). */
public enum Stage {
	VERTEX("vertex"),
	FRAGMENT("fragment"),
	COMPUTE("compute");

	private final String word;

	Stage(String word) {
		this.word = word;
	}

	public String word() {
		return word;
	}
}
