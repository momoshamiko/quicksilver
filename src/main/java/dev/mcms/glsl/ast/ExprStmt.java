// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.ast;

/** An expression used as a statement. */
public record ExprStmt(Pos pos, Expr expr) implements Stmt {
}
