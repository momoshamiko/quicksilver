// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.ast;

/** {@code return;} or {@code return value;}. */
public record ReturnStmt(Pos pos, Expr value) implements Stmt {
}
