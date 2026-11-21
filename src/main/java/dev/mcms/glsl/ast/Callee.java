// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.ast;

import java.util.List;

/** What a {@link CallExpr} calls: a name, a type with array dimensions (an array constructor such as
 * {@code float[3](...)} or {@code vec3[](...)}; empty dimensions mean a plain constructor like {@code vec4(...)}), or
 * a method on an expression ({@code a.length()}). */
public sealed interface Callee extends GlslNode permits NameCallee, TypeCallee, MethodCallee {
}
