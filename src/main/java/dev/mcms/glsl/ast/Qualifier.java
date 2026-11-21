// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.ast;

import java.util.List;

/** A qualifier on a type, declaration, member or parameter, kept in source order. */
public sealed interface Qualifier extends GlslNode permits StorageQual, InterpQual, MemoryQual, PrecisionQual,
		LayoutQual, InvariantQual, PreciseQual {
}
