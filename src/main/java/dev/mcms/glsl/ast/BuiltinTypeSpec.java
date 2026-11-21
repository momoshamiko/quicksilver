// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.ast;

/** A type spelled with a built-in type word for the unit's GLSL version: {@code float}, {@code vec4}, {@code mat4x2},
 * {@code sampler2D}, {@code image3D}, {@code atomic_uint}, ... (every type word of GLSL 1.10 to 4.60). */
public record BuiltinTypeSpec(Pos pos, String name) implements TypeSpec {
}
