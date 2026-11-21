// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.ast;

/**
 * An external declaration (and, wrapped in {@link DeclStmt}, a local declaration). Struct definitions are a
 * {@link VarDecl} whose type specifier is a {@link StructDefSpec}; a definition without declarators
 * ({@code struct S { ... };}) has an empty declarator list.
 */
public sealed interface Decl extends GlslNode permits FunctionPrototype, FunctionDefinition, VarDecl, InterfaceBlock,
		PrecisionDecl, QualifierDecl {
}
