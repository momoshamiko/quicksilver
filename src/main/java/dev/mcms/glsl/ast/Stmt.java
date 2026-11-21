// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.ast;

/**
 * Every statement form. A switch body keeps its {@link CaseStmt} and {@link DefaultStmt} labels as statements of the
 * body in source order, so fall-through is preserved exactly.
 */
public sealed interface Stmt extends GlslNode permits BlockStmt, DeclStmt, ExprStmt, IfStmt, ForStmt, WhileStmt,
		DoWhileStmt, SwitchStmt, CaseStmt, DefaultStmt, BreakStmt, ContinueStmt, ReturnStmt, DiscardStmt, EmptyStmt {
}
