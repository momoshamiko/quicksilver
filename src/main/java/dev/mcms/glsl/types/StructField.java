// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.types;

/**
 * One named field of a struct: its name and its type. Field order is the declaration order; struct layout
 * stays the emitter's business.
 */
public record StructField(String name, GlslType type) {
	@Override
	public String toString() {
		return name + ":" + type;
	}
}
