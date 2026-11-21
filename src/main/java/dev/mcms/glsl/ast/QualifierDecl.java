// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.ast;

import java.util.List;

/**
 * A qualifier-only declaration: {@code layout(local_size_x = 8) in;} (names empty) or
 * {@code invariant gl_Position;} (names list, with one position per name).
 */
public record QualifierDecl(Pos pos, List<Qualifier> qualifiers, List<String> names, List<Pos> namePositions)
		implements Decl {
}
