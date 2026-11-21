// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.ast;

/** A prefix unary expression, including prefix {@code ++} and {@code --}. */
public record UnaryExpr(Pos pos, UnaryOp op, Expr operand) implements Expr {
}
