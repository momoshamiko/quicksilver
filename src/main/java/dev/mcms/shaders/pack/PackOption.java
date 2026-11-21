// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.pack;

import java.util.List;

/**
 * One option of a pack, merged over every declaration of its name.
 *
 * @param name           the option's name as written in the shader files
 * @param kind           boolean, value or const
 * @param defaultValue   the default: "true" or "false" for booleans, the value text otherwise (the first declaration's)
 * @param values         the allowed values in list order, the default added when the list lacks it; empty without a list
 * @param hasValueList   whether some declaration gives an allowed-value list (a const without one is hidden unless a profile
 *                       or screen uses it, shaders.properties)
 * @param type           the declared GLSL type of a const ("int", "float", "bool"); empty otherwise
 * @param tooltip        the first non-empty comment text after the declaration
 * @param declarations   every declaration line, in discovery order
 * @param ambiguous      true when declarations disagree on kind or default: such an option is disabled (shaders.txt)
 */
public record PackOption(
	String name,
	OptionKind kind,
	String defaultValue,
	List<String> values,
	boolean hasValueList,
	String type,
	String tooltip,
	List<Declaration> declarations,
	boolean ambiguous) {

	/**
	 * One declaration line.
	 *
	 * @param path      the shader file (relative to {@code shaders/})
	 * @param line      the 1-based line
	 * @param commented true for a boolean written {@code // #define X} (default off)
	 */
	public record Declaration(String path, int line, OptionKind kind, boolean commented, String defaultValue) {
	}

	public PackOption {
		values = List.copyOf(values);
		declarations = List.copyOf(declarations);
	}

	/** A const option without an allowed-value list: hidden unless a profile or screen names it. */
	public boolean hiddenConst() {
		return kind == OptionKind.CONST && !hasValueList;
	}
}
