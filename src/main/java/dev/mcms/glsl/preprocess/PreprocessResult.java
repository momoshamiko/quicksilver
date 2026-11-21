// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.preprocess;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The result of one {@link Preprocessor#process} run.
 *
 * @param text               active code only; comments kept; one output line per entry of {@code lineMap}, joined with LF
 * @param lineMap            entry {@code i} is the origin of output line {@code i + 1}
 * @param version            the root file's {@code #version} argument as written ("120", "400 compatibility"), or null
 * @param extensions         the arguments of active {@code #extension} lines, in order
 * @param files              every file read, the root first (paths as given to the resolver)
 * @param objectMacrosAtEnd  the object-like macros defined by {@code #define} directives during the run that are still
 *                           defined at the end (name to body text); predefined macros are not included
 * @param warnings           non-fatal diagnostics (unknown directives, case-only name matches, and so on)
 */
public record PreprocessResult(
	String text,
	List<LineOrigin> lineMap,
	String version,
	List<String> extensions,
	Set<String> files,
	Map<String, String> objectMacrosAtEnd,
	List<Diagnostic> warnings) {
	public PreprocessResult {
		text = text == null ? "" : text;
		lineMap = List.copyOf(lineMap);
		extensions = List.copyOf(extensions);
		files = java.util.Collections.unmodifiableSet(new java.util.LinkedHashSet<>(files));
		objectMacrosAtEnd = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(objectMacrosAtEnd));
		warnings = List.copyOf(warnings);
	}
}
