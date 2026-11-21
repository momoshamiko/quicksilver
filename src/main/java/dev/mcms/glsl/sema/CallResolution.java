// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.sema;

import dev.mcms.glsl.ast.CallExpr;
import dev.mcms.glsl.types.GlslType;

import java.util.List;

/**
 * The resolution of one call. {@code argTypes} are the types the arguments have; {@code paramTypes} are the types
 * each argument is passed as (after implicit conversion for user and built-in functions; the component type for
 * constructor arguments, whose conversions are explicit). {@code overload} is set for user functions, {@code builtin}
 * for built-in functions; constructors and {@code .length()} set neither.
 */
public record CallResolution(CallExpr call, Kind kind, String name, FunctionOverload overload,
		BuiltinFunction builtin, List<GlslType> argTypes, List<GlslType> paramTypes, GlslType resultType) {

	/** What was called. */
	public enum Kind {
		USER_FUNCTION, BUILTIN_FUNCTION, TYPE_CONSTRUCTOR, STRUCT_CONSTRUCTOR, ARRAY_CONSTRUCTOR, ARRAY_LENGTH
	}

	public CallResolution {
		argTypes = List.copyOf(argTypes);
		paramTypes = List.copyOf(paramTypes);
	}

	@Override
	public String toString() {
		return name + " -> " + resultType + " (" + kind + ")";
	}
}
