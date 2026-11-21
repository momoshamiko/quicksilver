// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.sema;

import dev.mcms.glsl.ast.Pos;

import java.util.ArrayList;
import java.util.List;

/**
 * All overloads of one function name. User overloads hide every built-in function of the same name once the
 * name is declared (GLSL 4.60 specification, chapter 8: built-ins sit in an outer scope than user code).
 */
public record FunctionGroupSymbol(String name, List<FunctionOverload> overloads, Pos pos) implements Symbol {
	public FunctionGroupSymbol {
		overloads = List.copyOf(overloads);
	}

	public FunctionGroupSymbol add(FunctionOverload overload) {
		List<FunctionOverload> all = new ArrayList<>(overloads);
		all.add(overload);
		return new FunctionGroupSymbol(name, all, pos);
	}
}
