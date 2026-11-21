// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.types;

/** The {@code void} type: function return types only. */
public record VoidType() implements GlslType {
	public static final VoidType VOID = new VoidType();

	@Override
	public String toString() {
		return "void";
	}
}
