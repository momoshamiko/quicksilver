// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.preprocess;

import java.util.Optional;

/**
 * Resolves an {@code #include} for the preprocessor. {@code includeName} is the name as written (quotes or angle brackets
 * removed); {@code fromPath} is the path of the file containing the directive. A name starting with {@code /} is absolute
 * from the {@code shaders/} root, anything else is relative to {@code fromPath}'s folder.
 */
@FunctionalInterface
public interface IncludeResolver {
	Optional<SourceText> resolve(String fromPath, String includeName);
}
