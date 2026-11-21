// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.ast;

import java.util.List;

/** A compound statement {@code { ... }}. */
public record BlockStmt(Pos pos, List<Stmt> statements) implements Stmt {
}
