// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.sema;

import dev.mcms.glsl.ast.Pos;
import dev.mcms.glsl.types.GlslType;
import dev.mcms.glsl.types.StructType;

import java.util.List;

/**
 * One name of a scope: a variable (with its qualifiers), a struct type, or a group of function overloads
 * under one name (GLSL has a single namespace, so a name is exactly one of these kinds).
 */
public sealed interface Symbol permits VariableSymbol, StructSymbol, FunctionGroupSymbol {
	/** The name as written. */
	String name();

	/** Where it was declared (null for built-ins). */
	Pos pos();
}
