// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.ast;

/** A double literal with the {@code lf}/{@code LF} suffix (GLSL 4.00). */
public record DoubleLiteral(Pos pos, String text, double value) implements Expr {
}
