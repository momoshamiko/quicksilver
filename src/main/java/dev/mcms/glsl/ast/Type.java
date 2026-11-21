// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.ast;

import java.util.List;

/**
 * A qualified type with optional array dimensions on the type itself ({@code float[3] x;}). The qualifiers keep their
 * source order, so every order packs use round-trips. Dimensions on the declarator are separate
 * ({@link Declarator#dims}).
 */
public record Type(Pos pos, List<Qualifier> qualifiers, TypeSpec spec, List<ArrayDim> dims) implements GlslNode {
}
