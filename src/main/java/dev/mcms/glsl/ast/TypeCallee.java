// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.ast;

import java.util.List;

/** A call on a type word the parser saw as a keyword: {@code vec4(...)} or an array constructor {@code float[3](...)}
 * (a null-length {@link ArrayDim} is the unsized form {@code vec3[](...)}). */
public record TypeCallee(Pos pos, TypeSpec spec, List<ArrayDim> dims) implements Callee {
}
