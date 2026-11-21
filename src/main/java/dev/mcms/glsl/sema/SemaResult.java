// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.sema;

import dev.mcms.glsl.ast.CallExpr;
import dev.mcms.glsl.ast.Declarator;
import dev.mcms.glsl.ast.Expr;
import dev.mcms.glsl.ast.FunctionPrototype;
import dev.mcms.glsl.ast.Ident;
import dev.mcms.glsl.ast.Param;
import dev.mcms.glsl.ast.StructDefSpec;
import dev.mcms.glsl.ast.InterfaceBlock;
import dev.mcms.glsl.types.ErrorType;
import dev.mcms.glsl.types.GlslType;
import dev.mcms.glsl.types.StructType;

import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The typed program: every expression with its type, l-value-ness and folded constant; every call with its resolved
 * overload and argument types; every implicit conversion with its site; the symbols; and the diagnostics. A program
 * with errors ({@link #ok()} false) is never handed to the emitter; the query methods then may return the error type.
 */
public final class SemaResult {
	private final int version;
	private final String profile;
	private final Stage stage;
	private final List<SemaDiagnostic> diagnostics;
	private final IdentityHashMap<Expr, SemanticAnalyzer.ExprInfo> exprs;
	private final IdentityHashMap<CallExpr, CallResolution> calls;
	private final IdentityHashMap<Expr, Symbol> identSymbols;
	private final IdentityHashMap<Object, Object> declarations;
	private final List<Conversion> conversions;
	private final List<VariableSymbol> globals;
	/** The globals as a set: the emitters ask for each variable reference (a list scan was a tenth of a translation; E9). */
	private final Set<VariableSymbol> globalSet;
	private final List<FunctionOverload> functions;
	private final Map<String, VariableSymbol> usedBuiltins;
	private final List<StructType> structs;
	private final Map<String, ConstValue> localSize;
	private final Set<VariableSymbol> written;

	SemaResult(int version, String profile, Stage stage, List<SemaDiagnostic> diagnostics,
			IdentityHashMap<Expr, SemanticAnalyzer.ExprInfo> exprs, IdentityHashMap<CallExpr, CallResolution> calls,
			IdentityHashMap<Expr, Symbol> identSymbols, IdentityHashMap<Object, Object> declarations,
			List<Conversion> conversions, List<VariableSymbol> globals, List<FunctionOverload> functions,
			Map<String, VariableSymbol> usedBuiltins, List<StructType> structs, Map<String, ConstValue> localSize,
			Set<VariableSymbol> written) {
		this.version = version;
		this.profile = profile;
		this.stage = stage;
		this.diagnostics = List.copyOf(diagnostics);
		this.exprs = exprs;
		this.calls = calls;
		this.identSymbols = identSymbols;
		this.declarations = declarations;
		this.conversions = List.copyOf(conversions);
		this.globals = List.copyOf(globals);
		this.globalSet = new HashSet<>(this.globals);
		this.functions = List.copyOf(functions);
		// First-use order, kept: Map.copyOf's iteration order changes with each JVM start, and the emitters declare and copy
		// the built-ins in this map's order, so the MSL text (Metal's shader cache key) changed with every game start (E9).
		this.usedBuiltins = Collections.unmodifiableMap(new LinkedHashMap<>(usedBuiltins));
		this.structs = List.copyOf(structs);
		this.localSize = Collections.unmodifiableMap(new LinkedHashMap<>(localSize));
		this.written = written;
	}

	/** True when no error was reported (warnings and accepted leniencies do not count). */
	public boolean ok() {
		return diagnostics.stream().noneMatch(d -> d.severity() == SemaDiagnostic.Severity.ERROR);
	}

	public List<SemaDiagnostic> diagnostics() {
		return diagnostics;
	}

	public List<SemaDiagnostic> errors() {
		return diagnostics.stream().filter(d -> d.severity() == SemaDiagnostic.Severity.ERROR).toList();
	}

	public List<SemaDiagnostic> warnings() {
		return diagnostics.stream().filter(d -> d.severity() == SemaDiagnostic.Severity.WARNING).toList();
	}

	/** The type of an expression, or the error type when the analyzer rejected it (or never saw it). */
	public GlslType typeOf(Expr e) {
		SemanticAnalyzer.ExprInfo info = exprs.get(e);
		return info == null ? ErrorType.ERROR : info.type();
	}

	/** True when the expression is a writable l-value. */
	public boolean isLValue(Expr e) {
		SemanticAnalyzer.ExprInfo info = exprs.get(e);
		return info != null && info.lvalue();
	}

	/** The folded constant of an expression, or null when it is not a compile-time constant. */
	public ConstValue constOf(Expr e) {
		SemanticAnalyzer.ExprInfo info = exprs.get(e);
		return info == null ? null : info.value();
	}

	/** The resolved call, or null when the call did not resolve. */
	public CallResolution callOf(CallExpr e) {
		return calls.get(e);
	}

	/** The symbol an identifier resolved to (variable, struct, function group), or null. */
	public Symbol symbolOf(Ident i) {
		return identSymbols.get(i);
	}

	/** The variable a declarator, parameter or identifier names, or null. */
	public VariableSymbol variableOf(Object declaredOrIdent) {
		Object o = declaredOrIdent instanceof Ident i ? identSymbols.get(i) : declarations.get(declaredOrIdent);
		return o instanceof VariableSymbol v ? v : null;
	}

	/**
	 * Whether a statement writes the variable: an assignment, an increment or decrement, or an out or inout argument
	 * whose root it is (an element, field or swizzle of it counts). Functions no one calls count too.
	 */
	public boolean isWritten(VariableSymbol v) {
		return written.contains(v);
	}

	/** The overload a function prototype declared, or null. */
	public FunctionOverload functionOf(FunctionPrototype proto) {
		return declarations.get(proto) instanceof FunctionOverload f ? f : null;
	}

	/** The struct type a struct definition or an interface block declared, or null. */
	public StructType structOf(Object definition) {
		return declarations.get(definition) instanceof StructType s ? s : null;
	}

	/** Every implicit conversion, in the order the analyzer applied them. */
	public List<Conversion> conversions() {
		return conversions;
	}

	/** The global variables and interface block instances in declaration order. */
	public List<VariableSymbol> globals() {
		return globals;
	}

	/** True when the variable is one of {@link #globals()}. */
	public boolean isGlobal(VariableSymbol v) {
		return globalSet.contains(v);
	}

	/** The user function overloads (prototypes and definitions) in declaration order. */
	public List<FunctionOverload> functions() {
		return functions;
	}

	/** The built-in variables the program reads or writes, in first-use order. */
	public Map<String, VariableSymbol> usedBuiltins() {
		return usedBuiltins;
	}

	/** Every struct type of the unit (named and anonymous, including interface blocks). */
	public List<StructType> structs() {
		return structs;
	}

	/** The compute shader's {@code local_size_x/y/z} layout values, folded. */
	public Map<String, ConstValue> localSize() {
		return localSize;
	}

	public int version() {
		return version;
	}

	public String profile() {
		return profile;
	}

	public Stage stage() {
		return stage;
	}
}
