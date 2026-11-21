// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.ast;

/** Precision qualifier: {@code highp}, {@code mediump} or {@code lowp}. */
public record PrecisionQual(Pos pos, PrecisionKind kind) implements Qualifier {
}
