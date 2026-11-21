// Quicksilver: original code (ADR 0006), Apache-2.0.
/**
 * Semantic analysis of GLSL: scopes, symbols, the typed result, and the built-in functions and variables.
 *
 * <p>The analyzer is strict by default: every rule of the specification is an error. In lenient mode (the corpus and
 * the packs) a few rules that drivers commonly accept become warnings with a code, and the code names the leniency.
 * This is the complete list; each code is emitted where its rule is checked:
 * <ul>
 * <li>{@code array-constructor}: an array constructor below GLSL 1.20 (the specification adds them in 1.20).</li>
 * <li>{@code initializer-list}: a brace-enclosed initializer list below GLSL 4.20.</li>
 * <li>{@code const-nonconstant}: a {@code const} with a run-time initializer (the variable is treated as a variable).</li>
 * <li>{@code interface-initializer}: an initializer on a uniform or attribute (the game sets its value).</li>
 * <li>{@code interp-qualifier}: {@code smooth}, {@code flat} or {@code noperspective} below GLSL 1.30.</li>
 * <li>{@code legacy-storage}: a storage word of an older version.</li>
 * <li>{@code extension-function}: a built-in that needs an extension the unit does not enable.</li>
 * <li>{@code extension-type}: an image or atomic counter type without its extension.</li>
 * <li>{@code compat-builtin} and {@code compat-function}: a compatibility-profile built-in in a core unit.</li>
 * <li>{@code later-builtin}: a built-in variable or function of a later version. In lenient mode the overloads of later
 * versions join the candidates, and normal resolution (exact matches first, then conversions) decides; the warning
 * names a later winner. For example {@code gl_VertexID} in a 1.20 unit, {@code min(int, int)} in a 1.20 unit (the int
 * overload wins for int arguments), {@code inverse} in a 1.30 unit. Strict mode has no later overloads: those are
 * errors.</li>
 * <li>{@code undeclared-game-name}: one of the four game names a pack uses without declaring it
 * ({@code projectionMatrix}, {@code modelViewMatrix}, {@code vaPosition}, {@code vaNormal}).</li>
 * <li>{@code variable-hides-builtin}: a pack variable named like a built-in function (the call is the built-in).</li>
 * </ul>
 *
 * <p>Symbols for W3-B: {@link VariableSymbol} is a record, so equal symbols compare equal; each use of a built-in
 * variable creates a new equal instance. Key a symbol by its {@code equals} or by its name, never by identity. A
 * prototype and its definition resolve to the same {@link FunctionOverload}, whose {@code declaration()} is the
 * definition's {@code FunctionPrototype}.
 */
package dev.mcms.glsl.sema;
