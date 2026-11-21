// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.ast;

import java.util.List;

/**
 * An inline {@code struct} definition. {@code name} and {@code namePos} are null for the anonymous form
 * {@code struct { ... }}.
 */
public record StructDefSpec(Pos pos, String name, Pos namePos, List<StructMember> members) implements TypeSpec {
}
