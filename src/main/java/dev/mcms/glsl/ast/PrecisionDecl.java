// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.ast;

/** A precision statement: {@code precision highp float;}. */
public record PrecisionDecl(Pos pos, PrecisionKind precision, TypeSpec type) implements Decl {
}
