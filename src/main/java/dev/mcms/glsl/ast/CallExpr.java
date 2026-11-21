// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.ast;

import java.util.List;

/** A call: a function call, a built-in or struct constructor, or an array constructor. The callee decides which. */
public record CallExpr(Pos pos, Callee callee, List<Expr> args) implements Expr {
}
