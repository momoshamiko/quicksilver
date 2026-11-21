// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.ast;

/** One array dimension; {@code length} is null for an unsized dimension ({@code []}). */
public record ArrayDim(Pos pos, Expr length) implements GlslNode {
}
