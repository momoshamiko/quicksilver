// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.preprocess;

/**
 * Applied to every raw physical line of every file the preprocessor reads, before tokenizing. The pack model applies
 * chosen option values here (for example uncommenting {@code "// #define OPT"}). Returning null is treated as "".
 */
@FunctionalInterface
public interface LineRewriter {
	String rewrite(String path, int line, String rawLine);
}
