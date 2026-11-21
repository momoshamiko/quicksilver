// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.ast;

/** One item inside {@code layout(...)}: a name ({@code std430}, {@code rgba16f}) or a name with a constant expression
 * ({@code binding = 1}, {@code local_size_x = 8}). */
public record LayoutItem(Pos pos, String name, Expr value) implements GlslNode {
}
