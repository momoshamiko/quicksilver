// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.ast;

/**
 * A signed integer literal (decimal, octal or hexadecimal); {@code value} is the parsed value and {@code text} the
 * original spelling (kept so printing stays byte-faithful).
 *
 * <p>{@code exceedsInt32} is informational only: it is true when {@code value} is greater than
 * {@link Integer#MAX_VALUE}. GLSL 4.x also accepts such spellings as int bit patterns (for example {@code 0xFFFFFFFF}
 * and {@code 2147483648}), so the flag is not an error. Semantic analysis decides whether to wrap, accept or reject it.
 */
public record IntLiteral(Pos pos, String text, long value, boolean exceedsInt32) implements Expr {
}
