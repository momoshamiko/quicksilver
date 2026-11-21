// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.ast;

/** A {@code switch} statement; the body holds statements and {@link CaseStmt}/{@link DefaultStmt} labels in order. */
public record SwitchStmt(Pos pos, Expr head, Stmt body) implements Stmt {
}
