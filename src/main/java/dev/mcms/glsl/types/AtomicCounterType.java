// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.types;

/** The type of atomic counter variables: {@code atomic_uint} (GLSL 4.20). */
public record AtomicCounterType() implements GlslType {
	public static final AtomicCounterType ATOMIC_UINT = new AtomicCounterType();

	@Override
	public String toString() {
		return "atomic_uint";
	}
}
