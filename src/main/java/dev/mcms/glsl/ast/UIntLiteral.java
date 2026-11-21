// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.ast;

/**
 * An unsigned integer literal ({@code 42u}, {@code 0xFFu}); {@code value} holds the parsed value.
 *
 * <p>{@code exceedsUInt32} is informational only: it is true when {@code value} is greater than {@code 0xFFFFFFFF}.
 * Semantic analysis decides what to do with it.
 */
public record UIntLiteral(Pos pos, String text, long value, boolean exceedsUInt32) implements Expr {
}
