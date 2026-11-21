// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.ast;

import java.util.List;

/**
 * A variable declaration: one type (with qualifiers, possibly an inline struct definition and dimensions on the type)
 * and several declarators. The declarator list is empty only for a bare struct definition.
 */
public record VarDecl(Pos pos, Type type, List<Declarator> declarators) implements Decl {
}
