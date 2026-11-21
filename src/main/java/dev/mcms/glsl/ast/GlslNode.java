// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.ast;

/**
 * Root of the GLSL AST: every node is an immutable record whose first component is its {@link Pos} in the preprocessed
 * text. Lists in records are never null and never change after construction (the parser builds them with
 * {@link java.util.List#copyOf}). The tree is meant for semantic analysis, rewriting (build new records, or use
 * {@link Trees#transform}) and the two emitters: {@link GlslPrinter} writes it back as GLSL, the Metal emitter walks
 * it later.
 *
 * <p>Records compare structurally, positions included: two distinct nodes with the same shape and the same position
 * are {@code equals}. Anything that keys on nodes (side tables in later stages) must therefore use an
 * {@link java.util.IdentityHashMap}, not a HashMap, and synthesized nodes must get fresh positions if their positions
 * must stay unique. {@link Trees#equalsIgnorePos} compares shapes only.
 */
public sealed interface GlslNode permits Unit, Directive, Decl, Stmt, Expr, Type, TypeSpec, Qualifier, Callee, Cond,
		Declarator, Param, StructMember, ArrayDim, LayoutItem {
	Pos pos();
}
