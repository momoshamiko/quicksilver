// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.ast;

/** The specifier of a type: a built-in word, a name, or an inline {@code struct} definition. */
public sealed interface TypeSpec extends GlslNode permits BuiltinTypeSpec, NameTypeSpec, StructDefSpec {
}
