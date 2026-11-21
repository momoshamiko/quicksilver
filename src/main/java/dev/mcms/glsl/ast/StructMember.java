// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.ast;

import java.util.List;

/** One member of a struct definition or interface block. */
public record StructMember(Pos pos, Type type, List<Declarator> declarators) implements GlslNode {
}
