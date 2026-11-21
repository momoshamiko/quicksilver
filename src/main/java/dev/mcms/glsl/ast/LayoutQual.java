// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.ast;

import java.util.List;

/** {@code layout(...)} with its items, for example {@code layout(std430, binding = 1)} or
 * {@code layout(local_size_x = 8)}. */
public record LayoutQual(Pos pos, List<LayoutItem> items) implements Qualifier {
}
