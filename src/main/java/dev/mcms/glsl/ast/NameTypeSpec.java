// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.ast;

/**
 * A type spelled with an identifier: a struct name declared in this unit, or a type word that the unit's GLSL version
 * does not treat as a keyword (for example {@code image3D} in a {@code #version 400} unit that enables
 * {@code GL_ARB_shader_image_load_store}). The parser accepts the name; semantic analysis decides whether it is a
 * known struct or type.
 */
public record NameTypeSpec(Pos pos, String name) implements TypeSpec {
}
