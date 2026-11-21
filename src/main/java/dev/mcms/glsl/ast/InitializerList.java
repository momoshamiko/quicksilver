// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.ast;

import java.util.List;

/**
 * A brace initializer list (GLSL 4.20): {@code float a[2] = {1.0, 2.0};}. Items may be nested lists for arrays of
 * arrays. It is an {@link Expr} only so it can sit in a declarator's initializer slot; it is not a value, and semantic
 * analysis must reject it anywhere else.
 */
public record InitializerList(Pos pos, List<Expr> items) implements Expr {
}
