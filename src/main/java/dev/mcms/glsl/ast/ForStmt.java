// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.ast;

/**
 * A {@code for} statement. {@code init} is a {@link DeclStmt}, {@link ExprStmt} or {@link EmptyStmt} (never null);
 * {@code condition} and {@code step} are null when absent, and the condition may be a declaration.
 */
public record ForStmt(Pos pos, Stmt init, Cond condition, Expr step, Stmt body) implements Stmt {
}
