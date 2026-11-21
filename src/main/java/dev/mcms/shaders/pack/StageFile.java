// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.pack;

/**
 * One stage of a program after preprocessing: its stage letter ({@code v} vertex, {@code g} geometry, {@code f} fragment,
 * {@code c} compute, {@code t} tessellation control, {@code e} tessellation evaluation), the file it came from, and the
 * preprocessed text (active code and comments, with options and standard macros applied).
 */
public record StageFile(char stage, String path, String text) {
}
