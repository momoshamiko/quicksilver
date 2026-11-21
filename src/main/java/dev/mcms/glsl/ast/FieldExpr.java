// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.ast;

/** Field selection: a struct member or a vector swizzle ({@code .x}, {@code .rgba}); the parser does not decide.
 * {@code namePos} is the position of the name after the dot. */
public record FieldExpr(Pos pos, Expr base, String name, Pos namePos) implements Expr {
}
