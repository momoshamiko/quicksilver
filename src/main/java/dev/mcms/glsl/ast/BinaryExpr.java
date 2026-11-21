// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.ast;

/** A binary expression; the operator carries the precedence (left-associative). */
public record BinaryExpr(Pos pos, BinaryOp op, Expr left, Expr right) implements Expr {
}
