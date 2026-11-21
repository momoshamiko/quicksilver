// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.ast;

/** A postfix {@code ++} or {@code --}. */
public record PostfixExpr(Pos pos, Expr operand, PostfixOp op) implements Expr {
}
