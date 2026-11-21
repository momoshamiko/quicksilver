// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.types;

/**
 * The type of an expression the analyzer rejected. It is assignable to everything and convertible to
 * everything, so one error does not cascade; a program whose result holds it has diagnostics and is never
 * handed to the emitter.
 */
public record ErrorType() implements GlslType {
	public static final ErrorType ERROR = new ErrorType();

	@Override
	public String toString() {
		return "<error>";
	}
}
