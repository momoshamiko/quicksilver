// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.ast;

/** An assignment, simple or compound (right-associative). */
public record AssignExpr(Pos pos, AssignOp op, Expr target, Expr value) implements Expr {
}
