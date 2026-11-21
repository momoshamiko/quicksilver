// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.ast;

/** The condition of an {@code if}, {@code while} or {@code for}: an expression or a declaration with initializer. */
public sealed interface Cond extends GlslNode permits ExprCond, DeclCond {
}
