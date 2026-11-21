// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.sema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcms.glsl.ast.BinaryExpr;
import dev.mcms.glsl.ast.CallExpr;
import dev.mcms.glsl.ast.Decl;
import dev.mcms.glsl.ast.DeclStmt;
import dev.mcms.glsl.ast.Expr;
import dev.mcms.glsl.ast.FunctionDefinition;
import dev.mcms.glsl.ast.FunctionPrototype;
import dev.mcms.glsl.ast.Unit;
import dev.mcms.glsl.ast.VarDecl;
import dev.mcms.glsl.parse.Parser;
import dev.mcms.glsl.types.ArrayType;
import dev.mcms.glsl.types.GlslType;
import dev.mcms.glsl.types.ScalarKind;
import dev.mcms.glsl.types.ScalarType;
import dev.mcms.glsl.types.VectorType;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class SemanticAnalyzerTest {

	private static SemaResult strict(String source, Stage stage) {
		return SemanticAnalyzer.analyze(Parser.parse(source), SemaOptions.strict(stage));
	}

	private static SemaResult lenient(String source, Stage stage) {
		return SemanticAnalyzer.analyze(Parser.parse(source), SemaOptions.lenient(stage, List.of()));
	}

	private static Set<String> codes(SemaResult r) {
		return r.errors().stream().map(SemaDiagnostic::code).collect(Collectors.toSet());
	}

	private static Set<String> warningCodes(SemaResult r) {
		return r.warnings().stream().map(SemaDiagnostic::code).collect(Collectors.toSet());
	}

	/** The initializer of the n-th top-level declaration, which must be a variable declaration. */
	private static Expr globalInit(Unit unit, int n) {
		return ((VarDecl) unit.decls().get(n)).declarators().getFirst().initializer();
	}

	/** The initializer of the local declared by the n-th statement of {@code main}, which is the last declaration. */
	private static Expr localInit(Unit unit, int n) {
		FunctionDefinition main = (FunctionDefinition) unit.decls().getLast();
		DeclStmt s = (DeclStmt) main.body().statements().get(n);
		return ((VarDecl) s.decl()).declarators().getFirst().initializer();
	}

	// --- implicit conversions and overload resolution per version ---

	@Test
	void maxOfFloatAndIntResolvesToFloatOverloadIn120() {
		Unit unit = Parser.parse("""
				#version 120
				const float g = max(1.0, 2);
				void main() {}
				""");
		SemaResult r = SemanticAnalyzer.analyze(unit, SemaOptions.strict(Stage.FRAGMENT));
		assertTrue(r.ok(), r.errors().toString());
		CallExpr call = (CallExpr) globalInit(unit, 0);
		CallResolution resolved = r.callOf(call);
		assertEquals(CallResolution.Kind.BUILTIN_FUNCTION, resolved.kind());
		assertEquals(List.of(ScalarType.FLOAT, ScalarType.FLOAT), resolved.paramTypes());
		assertEquals(ScalarType.FLOAT, resolved.resultType());
		assertTrue(r.conversions().stream().anyMatch(c -> c.from().equals(ScalarType.INT)
				&& c.to().equals(ScalarType.FLOAT) && c.site() == Conversion.Site.ARGUMENT));
		assertEquals(2.0, r.constOf(call).floatValue());
	}

	@Test
	void maxOfFloatAndIntTakesTheFloatOverloadIn110() {
		// the int to float conversion holds at every version (Conversions), so max(float, int) resolves in a 1.10 unit
		SemaResult r = strict("""
				#version 110
				const float g = max(1.0, 2);
				void main() {}
				""", Stage.FRAGMENT);
		assertTrue(r.ok(), r.errors().toString());
	}

	@Test
	void uintAndFloatOverloadsTakeTheFloatOverloadForAnIntArgumentAt330() {
		// 3.30 has int to float but no int to uint, so f(float) is the only overload of the unit's own version; the 4.00
		// ranking of f(uint) and f(float) for an int argument is unranked, which reports an ambiguous call
		Unit unit = Parser.parse("""
				#version 330
				float f(uint a) { return 1.0; }
				float f(float a) { return a; }
				void main() { float g = f(1); }
				""");
		SemaResult r = SemanticAnalyzer.analyze(unit, SemaOptions.strict(Stage.FRAGMENT));
		assertTrue(r.ok(), r.errors().toString());
		CallResolution resolved = r.callOf((CallExpr) localInit(unit, 0));
		assertEquals(CallResolution.Kind.USER_FUNCTION, resolved.kind());
		assertEquals(List.of(ScalarType.FLOAT), resolved.paramTypes());
	}

	@Test
	void maxOfUintAndIntKeepsTheFloatOverloadAt130() {
		// 1.30 has uint to float but no int to uint: max(float, float) is the only overload of the unit's own version
		Unit unit = Parser.parse("""
				#version 130
				void main() {
					uint u = 1u;
					float m = max(u, 1);
				}
				""");
		SemaResult r = SemanticAnalyzer.analyze(unit, SemaOptions.strict(Stage.FRAGMENT));
		assertTrue(r.ok(), r.errors().toString());
		CallResolution resolved = r.callOf((CallExpr) localInit(unit, 1));
		assertEquals(List.of(ScalarType.FLOAT, ScalarType.FLOAT), resolved.paramTypes());
		assertEquals(ScalarType.FLOAT, resolved.resultType());
	}

	@Test
	void clampOfUintKeepsTheFloatOverloadAt330() {
		Unit unit = Parser.parse("""
				#version 330
				void main() {
					uint u = 1u;
					float c = clamp(u, 0, 3);
				}
				""");
		SemaResult r = SemanticAnalyzer.analyze(unit, SemaOptions.strict(Stage.FRAGMENT));
		assertTrue(r.ok(), r.errors().toString());
		CallResolution resolved = r.callOf((CallExpr) localInit(unit, 1));
		assertEquals(List.of(ScalarType.FLOAT, ScalarType.FLOAT, ScalarType.FLOAT), resolved.paramTypes());
		assertEquals(ScalarType.FLOAT, resolved.resultType());
	}

	@Test
	void callWithNoOverloadOfItsVersionTakesTheFourZeroConversions() {
		// 1.30 has no int to uint, so h(uint) matches only through the 4.00 conversions (the a081cf97 compatibility rule)
		Unit unit = Parser.parse("""
				#version 130
				float h(uint x) { return float(x); }
				void main() { float g = h(1); }
				""");
		SemaResult r = SemanticAnalyzer.analyze(unit, SemaOptions.strict(Stage.FRAGMENT));
		assertTrue(r.ok(), r.errors().toString());
		CallResolution resolved = r.callOf((CallExpr) localInit(unit, 0));
		assertEquals(List.of(ScalarType.UINT), resolved.paramTypes());
		assertTrue(r.conversions().stream().anyMatch(c -> c.from().equals(ScalarType.INT)
				&& c.to().equals(ScalarType.UINT) && c.site() == Conversion.Site.ARGUMENT));
	}

	@Test
	void uintComparedWithIntIsAcceptedAt130() {
		// composite (a 1.30 unit) compares a uint with an int: the 4.00 conversion holds at every version (a081cf97)
		assertTrue(strict("#version 130\nvoid main() { uint u = 1u; bool b = u == 1; }", Stage.FRAGMENT).ok());
	}

	@Test
	void intToFloatInInitializerIsAcceptedAtEveryVersion() {
		assertTrue(strict("#version 110\nvoid main() { float f = 1; }", Stage.FRAGMENT).ok());
		assertTrue(strict("#version 120\nvoid main() { float f = 1; }", Stage.FRAGMENT).ok());
	}

	@Test
	void intToUintIsImplicitAtEveryVersion() {
		// the engine runs the 4.60 feature set at every #version (ADR 0006 E6); Complementary's composite compares a uint
		// with an int in a 1.30 unit
		assertTrue(strict("#version 130\nvoid main() { uint u = 1; }", Stage.FRAGMENT).ok());
		assertTrue(strict("#version 400\nvoid main() { uint u = 1; }", Stage.FRAGMENT).ok());
	}

	@Test
	void floatToDoubleIsImplicitOnlyFrom400() {
		assertTrue(strict("#version 400\nvoid main() { float x = 1.0; double d = x; }", Stage.FRAGMENT).ok());
	}

	@Test
	void mixWithBoolScalarHasNoOverload() {
		SemaResult r = strict("""
				#version 400
				void main() { vec3 v = mix(vec3(0.0), vec3(1.0), true); }
				""", Stage.FRAGMENT);
		assertTrue(codes(r).contains("no-overload"), codes(r).toString());
	}

	@Test
	void mixWithBoolVectorSelectsTheBoolVectorOverload() {
		Unit unit = Parser.parse("""
				#version 400
				void main() { vec3 v = mix(vec3(0.0), vec3(1.0), bvec3(true)); }
				""");
		SemaResult r = SemanticAnalyzer.analyze(unit, SemaOptions.strict(Stage.FRAGMENT));
		assertTrue(r.ok(), r.errors().toString());
		CallResolution resolved = r.callOf((CallExpr) localInit(unit, 0));
		assertEquals(VectorType.BVEC3, resolved.builtin().params().get(2).type());
		assertEquals(VectorType.VEC3, resolved.resultType());
	}

	@Test
	void userFunctionNextToBuiltinsTakesAnIntArgumentWithConversion() {
		Unit unit = Parser.parse("""
				#version 120
				float saturate(float x) { return clamp(x, 0.0, 1.0); }
				float s = saturate(2);
				void main() {}
				""");
		SemaResult r = SemanticAnalyzer.analyze(unit, SemaOptions.strict(Stage.FRAGMENT));
		assertTrue(r.ok(), r.errors().toString());
		CallResolution resolved = r.callOf((CallExpr) globalInit(unit, 1));
		assertEquals(CallResolution.Kind.USER_FUNCTION, resolved.kind());
		assertNotNull(resolved.overload());
		assertEquals(List.of(ScalarType.FLOAT), resolved.paramTypes());
		assertTrue(r.conversions().stream().anyMatch(c -> c.from().equals(ScalarType.INT)
				&& c.to().equals(ScalarType.FLOAT) && c.site() == Conversion.Site.ARGUMENT));
	}

	@Test
	void ambiguousCallIsAnErrorIn400() {
		SemaResult r = strict("""
				#version 400
				float f(float a, int b) { return a; }
				float f(int a, float b) { return b; }
				void main() { float g = f(1, 1); }
				""", Stage.FRAGMENT);
		assertTrue(codes(r).contains("ambiguous-call"), codes(r).toString());
	}

	@Test
	void exactOverloadBeatsConversionIn400() {
		Unit unit = Parser.parse("""
				#version 400
				float pick(float a) { return a; }
				float pick(int a) { return float(a); }
				void main() { float g = pick(1); }
				""");
		SemaResult r = SemanticAnalyzer.analyze(unit, SemaOptions.strict(Stage.FRAGMENT));
		assertTrue(r.ok(), r.errors().toString());
		CallResolution resolved = r.callOf((CallExpr) localInit(unit, 0));
		assertEquals(List.of(ScalarType.INT), resolved.paramTypes());
		assertTrue(r.conversions().isEmpty(), r.conversions().toString());
	}

	@Test
	void returnedIntConvertsToFloatIn120AndIsRecorded() {
		Unit unit = Parser.parse("""
				#version 120
				float one() { return 1; }
				void main() {}
				""");
		SemaResult r = SemanticAnalyzer.analyze(unit, SemaOptions.strict(Stage.FRAGMENT));
		assertTrue(r.ok(), r.errors().toString());
		assertTrue(r.conversions().stream().anyMatch(c -> c.site() == Conversion.Site.RETURN
				&& c.from().equals(ScalarType.INT)));
	}

	@Test
	void assignedIntIsAcceptedIn110() {
		assertTrue(strict("#version 110\nvoid main() { float f; f = 1; }", Stage.FRAGMENT).ok());
	}

	@Test
	void unknownFunctionIsRejected() {
		SemaResult r = strict("#version 120\nvoid main() { foo(1.0); }", Stage.FRAGMENT);
		assertTrue(r.errors().stream().anyMatch(d -> d.message().contains("unknown function")), codes(r).toString());
	}

	// --- swizzles and l-values ---

	@Test
	void swizzleSetsCannotMix() {
		SemaResult r = strict("""
				#version 120
				void main() { vec4 v = vec4(0.0); vec2 w = v.xr; }
				""", Stage.FRAGMENT);
		assertTrue(codes(r).contains("swizzle-mixed-sets"), codes(r).toString());
	}

	@Test
	void swizzleComponentMustExistInTheVector() {
		SemaResult r = strict("""
				#version 120
				void main() { vec2 v = vec2(0.0); float z = v.z; }
				""", Stage.FRAGMENT);
		assertTrue(codes(r).contains("swizzle-range"), codes(r).toString());
	}

	@Test
	void repeatedSwizzleReadsButCannotBeAssigned() {
		assertTrue(strict("""
				#version 120
				void main() { vec4 v = vec4(0.0); vec4 w = v.xxyy; }
				""", Stage.FRAGMENT).ok());
		SemaResult r = strict("""
				#version 120
				void main() { vec4 v = vec4(0.0); v.xx = vec2(1.0); }
				""", Stage.FRAGMENT);
		assertTrue(codes(r).contains("not-lvalue"), codes(r).toString());
	}

	@Test
	void constantCannotBeWritten() {
		SemaResult r = strict("""
				#version 120
				const float k = 1.0;
				void main() { k = 2.0; }
				""", Stage.FRAGMENT);
		assertTrue(codes(r).contains("not-lvalue"), codes(r).toString());
	}

	@Test
	void uniformAndInputCannotBeWritten() {
		SemaResult uniform = strict("""
				#version 120
				uniform float u;
				void main() { u = 1.0; }
				""", Stage.FRAGMENT);
		assertTrue(codes(uniform).contains("not-lvalue"), codes(uniform).toString());
		SemaResult input = strict("""
				#version 150
				in vec4 c;
				void main() { c = vec4(0.0); }
				""", Stage.FRAGMENT);
		assertTrue(codes(input).contains("not-lvalue"), codes(input).toString());
	}

	@Test
	void outParameterNeedsAWritableArgument() {
		SemaResult r = strict("""
				#version 120
				void set(out float x) { x = 1.0; }
				void main() { set(1.0); }
				""", Stage.FRAGMENT);
		assertTrue(codes(r).contains("not-lvalue"), codes(r).toString());
	}

	// --- constant folding ---

	@Test
	void arraySizeFromConstIntExpression() {
		Unit unit = Parser.parse("""
				#version 120
				const int n = 4;
				float a[n * 2];
				void main() {}
				""");
		SemaResult r = SemanticAnalyzer.analyze(unit, SemaOptions.strict(Stage.FRAGMENT));
		assertTrue(r.ok(), r.errors().toString());
		assertEquals(new ArrayType(ScalarType.FLOAT, 8), r.globals().get(1).type());
	}

	@Test
	void arraySizeFromBuiltinCallFoldsIn120() {
		SemaResult r = strict("""
				#version 120
				float a[int(max(2.0, 5.0))];
				void main() {}
				""", Stage.FRAGMENT);
		assertTrue(r.ok(), r.errors().toString());
		assertEquals(5, ((ArrayType) r.globals().getFirst().type()).size());
	}

	@Test
	void textureCallIsNotConstant() {
		SemaResult r = strict("""
				#version 120
				uniform sampler2D t;
				float a[int(texture2D(t, vec2(0.0)).x)];
				void main() {}
				""", Stage.FRAGMENT);
		assertTrue(codes(r).contains("array-size-constant"), codes(r).toString());
	}

	@Test
	void userFunctionCallIsNotConstant() {
		SemaResult r = strict("""
				#version 400
				float saturate(float x) { return clamp(x, 0.0, 1.0); }
				const float s = saturate(2.0);
				void main() {}
				""", Stage.FRAGMENT);
		assertTrue(codes(r).contains("const-nonconstant"), codes(r).toString());
	}

	@Test
	void intLiteralWithSignBitSetIsLegalAndWraps() {
		// GLSL 4.60 section 4.1.3: a literal is legal when its bit pattern fits 32 bits.
		Unit unit = Parser.parse("""
				#version 400
				const int big = 3000000000;
				const int all = 0xFFFFFFFF;
				void main() {}
				""");
		SemaResult r = SemanticAnalyzer.analyze(unit, SemaOptions.strict(Stage.FRAGMENT));
		assertTrue(r.ok(), r.errors().toString());
		assertEquals(-1294967296L, r.constOf(globalInit(unit, 0)).intValue());
		assertEquals(-1L, r.constOf(globalInit(unit, 1)).intValue());
	}

	@Test
	void intLiteralNeedingMoreThan32BitsIsAnError() {
		SemaResult r = strict("#version 400\nint x = 0x100000000;\nvoid main() {}", Stage.FRAGMENT);
		assertTrue(codes(r).contains("literal-range"), codes(r).toString());
		SemaResult u = strict("#version 400\nuint y = 0x100000000u;\nvoid main() {}", Stage.FRAGMENT);
		assertTrue(codes(u).contains("literal-range"), codes(u).toString());
	}

	@Test
	void integerArithmeticWrapsTo32Bits() {
		Unit unit = Parser.parse("""
				#version 400
				const int wrapped = 2147483647 + 1;
				const int shifted = 1 << 31;
				void main() {}
				""");
		SemaResult r = SemanticAnalyzer.analyze(unit, SemaOptions.strict(Stage.FRAGMENT));
		assertTrue(r.ok(), r.errors().toString());
		assertEquals(Integer.MIN_VALUE, r.constOf(globalInit(unit, 0)).intValue());
		assertEquals(Integer.MIN_VALUE, r.constOf(globalInit(unit, 1)).intValue());
	}

	@Test
	void arrayConstructorFoldsItsElements() {
		Unit unit = Parser.parse("""
				#version 420
				const float a[2] = float[2](1.0, 2);
				void main() {}
				""");
		SemaResult r = SemanticAnalyzer.analyze(unit, SemaOptions.strict(Stage.FRAGMENT));
		assertTrue(r.ok(), r.errors().toString());
		assertEquals(2.0, r.constOf(globalInit(unit, 0)).parts()[1].floatValue());
	}

	// --- built-in variables per stage and profile ---

	@Test
	void fragmentOnlyBuiltinIsAnErrorInVertexStage() {
		SemaResult r = strict("""
				#version 150
				void main() { float d = gl_FragCoord.x; }
				""", Stage.VERTEX);
		assertTrue(codes(r).contains("builtin-stage"), codes(r).toString());
	}

	@Test
	void computeBuiltinIsAnErrorOutsideCompute() {
		SemaResult r = strict("""
				#version 430
				void main() { uvec3 g = gl_WorkGroupID; }
				""", Stage.FRAGMENT);
		assertTrue(codes(r).contains("builtin-stage"), codes(r).toString());
	}

	@Test
	void builtinTypesAreTheSpecifiedOnes() {
		Unit unit = Parser.parse("""
				#version 150
				void main() { vec4 p = gl_Position; float s = gl_PointSize; }
				""");
		SemaResult r = SemanticAnalyzer.analyze(unit, SemaOptions.strict(Stage.VERTEX));
		assertTrue(r.ok(), r.errors().toString());
		assertEquals(VectorType.VEC4, r.typeOf(localInit(unit, 0)));
		assertEquals(ScalarType.FLOAT, r.usedBuiltins().get("gl_PointSize").type());
		assertTrue(r.usedBuiltins().containsKey("gl_Position"));
	}

	@Test
	void compatibilityBuiltinIsAnErrorInCoreProfileUnlessLenient() {
		String src = """
				#version 150 core
				void main() { vec4 v = gl_Vertex; }
				""";
		assertTrue(codes(strict(src, Stage.VERTEX)).contains("compat-builtin"));
		SemaResult accepted = lenient(src, Stage.VERTEX);
		assertTrue(accepted.ok(), accepted.errors().toString());
		assertTrue(warningCodes(accepted).contains("compat-builtin"), warningCodes(accepted).toString());
	}

	@Test
	void compatibilityBuiltinIsAcceptedIn120AndInCompatibilityProfile() {
		assertTrue(strict("#version 120\nvoid main() { vec4 v = gl_Vertex; }", Stage.VERTEX).ok());
		assertTrue(strict("#version 150 compatibility\nvoid main() { vec4 v = gl_Vertex; }", Stage.VERTEX).ok());
	}

	@Test
	void legacyTextureNameIsAcceptedIn120AndRejectedInCoreAbove() {
		assertTrue(strict("""
				#version 120
				uniform sampler2D t;
				void main() { vec4 c = texture2D(t, vec2(0.0)); }
				""", Stage.FRAGMENT).ok());
		SemaResult core = lenient("""
				#version 150 core
				uniform sampler2D t;
				void main() { vec4 c = texture2D(t, vec2(0.0)); }
				""", Stage.FRAGMENT);
		assertTrue(warningCodes(core).contains("compat-function"), warningCodes(core).toString());
	}

	// --- constructors, initializers, arrays ---

	@Test
	void structConstructorConvertsItsArguments() {
		Unit unit = Parser.parse("""
				#version 420
				struct Light { vec3 p; float k; };
				const Light l = Light(vec3(1.0), 2);
				void main() {}
				""");
		SemaResult r = SemanticAnalyzer.analyze(unit, SemaOptions.strict(Stage.FRAGMENT));
		assertTrue(r.ok(), r.errors().toString());
		CallResolution resolved = r.callOf((CallExpr) globalInit(unit, 1));
		assertEquals(CallResolution.Kind.STRUCT_CONSTRUCTOR, resolved.kind());
		assertTrue(r.conversions().stream().anyMatch(c -> c.from().equals(ScalarType.INT)
				&& c.to().equals(ScalarType.FLOAT) && c.site() == Conversion.Site.ARGUMENT));
	}

	@Test
	void vectorConstructorRejectsExtraArguments() {
		SemaResult r = strict("""
				#version 120
				void main() { vec3 v = vec3(vec4(1.0), 2.0); }
				""", Stage.FRAGMENT);
		assertTrue(codes(r).contains("constructor-args"), codes(r).toString());
	}

	@Test
	void vectorConstructorRejectsTooFewComponents() {
		SemaResult r = strict("#version 120\nvoid main() { vec3 v = vec3(1.0, 2.0); }", Stage.FRAGMENT);
		assertTrue(codes(r).contains("constructor-args"), codes(r).toString());
	}

	@Test
	void vectorConstructorFlattensMatrixComponents() {
		assertTrue(strict("#version 120\nvoid main() { vec4 v = vec4(mat2(1.0)); }", Stage.FRAGMENT).ok());
		SemaResult r = strict("#version 120\nvoid main() { vec4 v = vec4(mat2(1.0), 1.0); }", Stage.FRAGMENT);
		assertTrue(codes(r).contains("constructor-args"), codes(r).toString());
	}

	@Test
	void braceInitializerChecksItsCountAndTheDeclaredType() {
		assertTrue(strict("#version 420\nvoid main() { vec3 v = {1.0, 2.0, 3.0}; }", Stage.FRAGMENT).ok());
		SemaResult few = strict("#version 420\nvoid main() { vec3 v = {1.0, 2.0}; }", Stage.FRAGMENT);
		assertTrue(codes(few).contains("initializer-count"), codes(few).toString());
		SemaResult scalar = strict("#version 420\nvoid main() { int x = {1}; }", Stage.FRAGMENT);
		assertTrue(codes(scalar).contains("initializer-list-type"), codes(scalar).toString());
	}

	@Test
	void braceInitializerAppliesToNestedArrays() {
		assertTrue(strict("""
				#version 420
				void main() { float a[2][2] = {{1.0, 2.0}, {3.0, 4.0}}; }
				""", Stage.FRAGMENT).ok());
		SemaResult bad = strict("""
				#version 420
				void main() { float a[2][2] = {{1.0, 2.0}, {3.0}}; }
				""", Stage.FRAGMENT);
		assertTrue(codes(bad).contains("initializer-count"), codes(bad).toString());
	}

	@Test
	void braceInitializerOutsideADeclarationIsRejected() {
		org.junit.jupiter.api.Assertions.assertThrows(dev.mcms.glsl.GlslSyntaxException.class,
				() -> strict("#version 420\nvoid main() { float f; f = {1.0}; }", Stage.FRAGMENT));
	}

	@Test
	void arrayConstructorRecordsItsElementConversions() {
		Unit unit = Parser.parse("""
				#version 420
				void main() { float[2] a = float[2](1.0, 2); }
				""");
		SemaResult r = SemanticAnalyzer.analyze(unit, SemaOptions.strict(Stage.FRAGMENT));
		assertTrue(r.ok(), r.errors().toString());
		CallResolution resolved = r.callOf((CallExpr) localInit(unit, 0));
		assertEquals(CallResolution.Kind.ARRAY_CONSTRUCTOR, resolved.kind());
		assertEquals(new ArrayType(ScalarType.FLOAT, 2), resolved.resultType());
		assertTrue(r.conversions().stream().anyMatch(c -> c.from().equals(ScalarType.INT)
				&& c.site() == Conversion.Site.ARGUMENT));
	}

	@Test
	void arrayConstructorIsNoLeniencyFrom120() {
		String src = "#version 400\nvoid main() { float[2] a = float[2](1.0, 2.0); }";
		assertFalse(warningCodes(lenient(src, Stage.FRAGMENT)).contains("array-constructor"));
		assertTrue(strict(src, Stage.FRAGMENT).ok());
	}

	@Test
	void lengthIsConstantForSizedArraysAndRuntimeForBufferMembers() {
		Unit unit = Parser.parse("""
				#version 430
				layout(std430, binding = 1) buffer B { float d[]; } b;
				float s[4];
				void main() { int m = s.length(); int n = b.d.length(); }
				""");
		SemaResult r = SemanticAnalyzer.analyze(unit, SemaOptions.strict(Stage.COMPUTE));
		assertTrue(r.ok(), r.errors().toString());
		Expr sized = localInit(unit, 0);
		Expr runtime = localInit(unit, 1);
		assertEquals(4, r.constOf(sized).intValue());
		assertNull(r.constOf(runtime));
		assertEquals(CallResolution.Kind.ARRAY_LENGTH, r.callOf((CallExpr) runtime).kind());
	}

	@Test
	void lengthTakesNoArguments() {
		SemaResult r = strict("#version 430\nfloat s[4];\nvoid main() { int m = s.length(1); }", Stage.COMPUTE);
		assertFalse(r.ok());
	}

	// --- operators and control flow ---

	@Test
	void conditionalMixesIntAndFloatIn400() {
		Unit unit = Parser.parse("""
				#version 400
				const float t = true ? 1 : 2.0;
				void main() {}
				""");
		SemaResult r = SemanticAnalyzer.analyze(unit, SemaOptions.strict(Stage.FRAGMENT));
		assertTrue(r.ok(), r.errors().toString());
		assertEquals(1.0, r.constOf(globalInit(unit, 0)).floatValue());
		assertTrue(r.conversions().stream().anyMatch(c -> c.site() == Conversion.Site.OPERAND
				&& c.from().equals(ScalarType.INT)));
	}

	@Test
	void nestedFunctionIsRejected() {
		SemaResult r = strict("""
				#version 400
				void main() { void inner() { } }
				""", Stage.FRAGMENT);
		assertTrue(codes(r).contains("nested-function"), codes(r).toString());
	}

	@Test
	void directRecursionIsRejected() {
		SemaResult r = strict("""
				#version 400
				float f(float x) { return f(x); }
				void main() {}
				""", Stage.FRAGMENT);
		assertTrue(codes(r).contains("recursion"), codes(r).toString());
	}

	@Test
	void discardOnlyInFragment() {
		SemaResult r = strict("#version 400\nvoid main() { discard; }", Stage.VERTEX);
		assertTrue(codes(r).contains("discard-stage"), codes(r).toString());
	}

	@Test
	void breakOutsideLoopIsRejected() {
		SemaResult r = strict("#version 400\nvoid main() { break; }", Stage.FRAGMENT);
		assertTrue(codes(r).contains("break-outside"), codes(r).toString());
	}

	@Test
	void ifConditionMustBeBool() {
		SemaResult r = strict("#version 400\nvoid main() { if (1.0) { } }", Stage.FRAGMENT);
		assertFalse(r.ok());
	}

	@Test
	void duplicateGlobalIsRejected() {
		SemaResult r = strict("#version 400\nfloat a;\nfloat a;\nvoid main() {}", Stage.FRAGMENT);
		assertFalse(r.ok());
	}

	@Test
	void unsizedGlobalArrayNeedsBufferBlock() {
		SemaResult r = strict("#version 430\nuniform float u[];\nvoid main() {}", Stage.FRAGMENT);
		assertTrue(codes(r).contains("array-size-missing"), codes(r).toString());
	}

	// --- interface blocks ---

	@Test
	void interfaceBlockMembersAreTyped() {
		Unit unit = Parser.parse("""
				#version 150
				layout(std140) uniform U { vec4 c; } u;
				void main() { vec4 v = u.c; }
				""");
		SemaResult r = SemanticAnalyzer.analyze(unit, SemaOptions.strict(Stage.FRAGMENT));
		assertTrue(r.ok(), r.errors().toString());
		assertEquals(VectorType.VEC4, r.typeOf(localInit(unit, 0)));
	}

	// --- diagnostics positions and results ---

	@Test
	void errorsReportTheOriginalFileThroughTheLineMap() {
		Unit unit = Parser.parse("#version 120\nvoid main() {\n  gl_Nope = 1.0;\n}\n");
		SemaResult r = SemanticAnalyzer.analyze(unit, new SemaOptions(Stage.FRAGMENT, false, List.of(
				new SourceLine("world0/composite.fsh", 10), new SourceLine("world0/composite.fsh", 11),
				new SourceLine("common.glsl", 4), new SourceLine("world0/composite.fsh", 12))));
		SemaDiagnostic d = r.errors().getFirst();
		assertEquals("unknown-identifier", d.code());
		assertEquals(3, d.pos().line());
		assertEquals("common.glsl", d.origin().path());
		assertEquals(4, d.origin().line());
	}

	@Test
	void errorsWithoutALineMapHaveNoOrigin() {
		SemaResult r = strict("#version 120\nvoid main() {\n  gl_Nope = 1.0;\n}\n", Stage.FRAGMENT);
		assertNull(r.errors().getFirst().origin());
	}

	@Test
	void everyExpressionGetsItsType() {
		Unit unit = Parser.parse("""
				#version 120
				void main() { vec3 v = vec3(1.0); float x = v.x * 2.0; }
				""");
		SemaResult r = SemanticAnalyzer.analyze(unit, SemaOptions.strict(Stage.FRAGMENT));
		assertTrue(r.ok(), r.errors().toString());
		BinaryExpr product = (BinaryExpr) localInit(unit, 1);
		assertEquals(ScalarType.FLOAT, r.typeOf(product));
		assertEquals(ScalarType.FLOAT, r.typeOf(product.left()));
		assertEquals(ScalarType.FLOAT, r.typeOf(product.right()));
		assertEquals(VectorType.VEC3, r.typeOf(((dev.mcms.glsl.ast.FieldExpr) product.left()).base()));
		assertInstanceOf(GlslType.class, r.typeOf(product));
		assertEquals(ScalarKind.FLOAT, ((ScalarType) r.typeOf(product)).kind());
	}

	// --- arithmetic result types, l-values, game-provided names, leniencies ---

	@Test
	void intWithFloatIsFloatNotDouble() {
		Unit unit = Parser.parse("""
				#version 400
				void main() { float x = 0.5; float y = 1 - x; }
				""");
		SemaResult r = SemanticAnalyzer.analyze(unit, SemaOptions.strict(Stage.FRAGMENT));
		assertTrue(r.ok(), r.errors().toString());
		assertEquals(ScalarType.FLOAT, r.typeOf(localInit(unit, 1)));
	}

	@Test
	void intWithUintIsUintIn400() {
		Unit unit = Parser.parse("""
				#version 400
				void main() { uint u = 1u; uint v = 2 + u; }
				""");
		SemaResult r = SemanticAnalyzer.analyze(unit, SemaOptions.strict(Stage.FRAGMENT));
		assertTrue(r.ok(), r.errors().toString());
		assertEquals(ScalarType.UINT, r.typeOf(localInit(unit, 1)));
	}

	@Test
	void vectorTimesIntegerExpressionStaysAVector() {
		Unit unit = Parser.parse("""
				#version 400
				void main() { vec2 p = vec2(0.5); vec2 q = p * 2 - 1; }
				""");
		SemaResult r = SemanticAnalyzer.analyze(unit, SemaOptions.strict(Stage.FRAGMENT));
		assertTrue(r.ok(), r.errors().toString());
		assertEquals(VectorType.VEC2, r.typeOf(localInit(unit, 1)));
	}

	@Test
	void outputBuiltinElementIsWritableAndInputIsNot() {
		assertTrue(strict("#version 120\nvoid main() { gl_FragData[0] = vec4(1.0); }", Stage.FRAGMENT).ok());
		SemaResult input = strict("#version 150\nvoid main() { gl_FragCoord = vec4(0.0); }", Stage.FRAGMENT);
		assertTrue(codes(input).contains("not-lvalue"), codes(input).toString());
	}

	@Test
	void undeclaredGameUniformIsALeniencyAndHasTheGameType() {
		Unit unit = Parser.parse("""
				#version 120
				void main() { mat4 m = projectionMatrix; }
				""");
		SemaResult r = SemanticAnalyzer.analyze(unit, SemaOptions.lenient(Stage.FRAGMENT, List.of()));
		assertTrue(r.ok(), r.errors().toString());
		assertTrue(warningCodes(r).contains("undeclared-game-name"));
		assertEquals(dev.mcms.glsl.types.MatrixType.of(dev.mcms.glsl.types.ScalarKind.FLOAT, 4, 4),
				r.typeOf(localInit(unit, 0)));
	}

	@Test
	void undeclaredGameAttributeExistsOnlyInTheVertexStage() {
		String src = "#version 120\nvoid main() { vec3 p = vaPosition; }";
		assertTrue(lenient(src, Stage.VERTEX).ok());
		assertTrue(codes(lenient(src, Stage.FRAGMENT)).contains("builtin-stage"));
	}

	@Test
	void aPackDeclarationWinsOverAGameName() {
		Unit unit = Parser.parse("""
				#version 120
				uniform float projectionMatrix;
				void main() { float f = projectionMatrix; }
				""");
		SemaResult r = SemanticAnalyzer.analyze(unit, SemaOptions.strict(Stage.FRAGMENT));
		assertTrue(r.ok(), r.errors().toString());
		assertEquals(ScalarType.FLOAT, r.typeOf(localInit(unit, 0)));
	}

	@Test
	void runTimeConstInitializerIsALeniency() {
		String src = "#version 120\nuniform float u;\nconst float c = u;\nvoid main() {}";
		assertTrue(codes(strict(src, Stage.FRAGMENT)).contains("const-nonconstant"));
		SemaResult accepted = lenient(src, Stage.FRAGMENT);
		assertTrue(accepted.ok(), accepted.errors().toString());
		assertTrue(warningCodes(accepted).contains("const-nonconstant"));
	}

	@Test
	void uniformInitializerIsALeniency() {
		String src = "#version 120\nuniform float u = 1.0;\nvoid main() {}";
		assertTrue(codes(strict(src, Stage.FRAGMENT)).contains("interface-initializer"));
		SemaResult accepted = lenient(src, Stage.FRAGMENT);
		assertTrue(accepted.ok(), accepted.errors().toString());
		assertTrue(warningCodes(accepted).contains("interface-initializer"));
	}

	@Test
	void legacyLodInFragmentNeedsTheArbExtensionIn120() {
		String body = "uniform sampler2D t;\nvoid main() { vec4 c = texture2DLod(t, vec2(0.0), 0.0); }";
		assertTrue(codes(strict("#version 120\n" + body, Stage.FRAGMENT)).contains("extension-function"));
		assertTrue(strict("#version 120\n#extension GL_ARB_shader_texture_lod : enable\n" + body,
				Stage.FRAGMENT).ok());
	}

	@Test
	void interpolationQualifierBefore130IsALeniency() {
		String src = "#version 120\nflat varying vec4 color;\nvoid main() {}";
		assertTrue(codes(strict(src, Stage.FRAGMENT)).contains("interp-qualifier"));
		SemaResult accepted = lenient(src, Stage.FRAGMENT);
		assertTrue(accepted.ok(), accepted.errors().toString());
		assertTrue(warningCodes(accepted).contains("interp-qualifier"));
	}

	@Test
	void texture2DLodAt130IsResolvedToOneCandidate() {
		// the core entry and the GL_ARB_shader_texture_lod entry share a signature: no ambiguity at 1.30
		SemaResult r = lenient("#version 130\nuniform sampler2D t;\nvoid main() { vec4 c = texture2DLod(t, vec2(0.0), 0.0); }",
				Stage.FRAGMENT);
		assertTrue(r.ok(), r.errors().toString());
		assertFalse(codes(r).contains("ambiguous-call"));
	}

	@Test
	void legacyTextureNamesTakeIntegerSamplersWithTheirResultType() {
		// the legacy names resolve against the modern overloads of each element type: an isampler gives ivec4, a usampler
		// uvec4 (Complementary's gbuffers_terrain samples its own usampler2D with texture2D; ADR 0006 E6)
		String src = "#version 130\nuniform isampler2D a;\nuniform usampler2D b;\nuniform usampler3D c;\nout vec4 o;\n"
			+ "void main() { ivec4 x = texture2D(a, vec2(0.0)); uvec4 y = texture2DLod(b, vec2(0.0), 0.0);"
			+ " uvec4 z = texture3D(c, vec3(0.0)); o = vec4(x) + vec4(y) + vec4(z); }";
		assertTrue(strict(src, Stage.FRAGMENT).ok(), codes(strict(src, Stage.FRAGMENT)).toString());
		String wrong = "#version 130\nuniform usampler2D b;\nout vec4 o;\nvoid main() { ivec4 x = texture2D(b, vec2(0.0));"
			+ " o = vec4(x); }";
		assertFalse(strict(wrong, Stage.FRAGMENT).ok(), "a uvec4 result does not convert to ivec4");
	}

	@Test
	void isnanNeedsGlsl130() {
		// strict: isnan(float) is GLSL 1.30, so a 1.20 unit has no overload; lenient: the later overload wins
		assertFalse(strict("#version 120\nvoid main() { bool b = isnan(1.0); }", Stage.FRAGMENT).ok());
		SemaResult lenient = lenient("#version 120\nvoid main() { bool b = isnan(1.0); }", Stage.FRAGMENT);
		assertTrue(lenient.ok(), lenient.errors().toString());
		assertTrue(warningCodes(lenient).contains("later-builtin"));
		assertTrue(strict("#version 130\nvoid main() { bool b = isnan(1.0); }", Stage.FRAGMENT).ok());
	}

	@Test
	void mutualRecursionThroughTwoFunctionsIsRejected() {
		SemaResult r = strict("""
				#version 400
				float g(float x);
				float f(float x) { return g(x); }
				float g(float x) { return f(x); }
				void main() {}
				""", Stage.FRAGMENT);
		assertTrue(codes(r).contains("recursion"), codes(r).toString());
	}

	@Test
	void acyclicCallChainIsAccepted() {
		SemaResult r = strict("""
				#version 400
				float h(float x) { return x; }
				float g(float x) { return h(x); }
				float f(float x) { return g(x); }
				void main() {}
				""", Stage.FRAGMENT);
		assertTrue(r.ok(), r.errors().toString());
	}

	@Test
	void laterBuiltinVariableIsALeniencyInOlderUnits() {
		String src = "#version 120\nvoid main() { int i = gl_VertexID; gl_Position = vec4(float(i)); }";
		assertTrue(codes(strict(src, Stage.VERTEX)).contains("later-builtin"));
		SemaResult accepted = lenient(src, Stage.VERTEX);
		assertTrue(accepted.ok(), accepted.errors().toString());
		assertTrue(warningCodes(accepted).contains("later-builtin"));
	}

	@Test
	void laterConstructorIsALeniencyInOlderUnits() {
		String src = "#version 120\nvoid main() { float f = float(uint(3)); gl_FragColor = vec4(f); }";
		SemaResult accepted = lenient(src, Stage.FRAGMENT);
		assertTrue(accepted.ok(), accepted.errors().toString());
		assertTrue(warningCodes(accepted).contains("later-builtin"));
	}


	@Test
	void mixedMinStaysAFloatOverloadIn120() {
		// no later overload matches min(float, int) exactly, so the conversion rule of 1.20 applies and no
		// leniency is needed
		String src = "#version 120\nvoid main() { float a = 1.0; float m = min(a, 2); gl_FragColor = vec4(m); }";
		SemaResult r = lenient(src, Stage.FRAGMENT);
		assertTrue(r.ok(), r.errors().toString());
		assertFalse(warningCodes(r).contains("later-builtin"));
	}

	// --- round 4: spec rules (failing first) ---

	@Test
	void uintTimesFloatIsImplicitFrom130() {
		String src = "#version 330\nout vec4 o;\nvoid main() { uint n = 3u; float f = n * (1.0 / 4294967296.0); o = vec4(f); }";
		assertTrue(strict(src, Stage.FRAGMENT).ok(), strict(src, Stage.FRAGMENT).errors().toString());
	}

	@Test
	void signedUnsignedPairsWithEqualRanksAreAmbiguousIn400() {
		// g(uint, float) needs int to uint on the first argument; g(float, float) needs int to float. Section 6.1.1:
		// those two conversions are not ranked against each other, so neither function is the better match.
		String src = "#version 400\nvoid g(uint a, float b) {}\nvoid g(float a, float b) {}\n"
				+ "void main() { g(1, 1.0); }";
		assertTrue(codes(strict(src, Stage.FRAGMENT)).contains("ambiguous-call"));
	}

	@Test
	void aPrototypeAndItsDefinitionAreOneFunctionForRecursion() {
		String src = "#version 120\nfloat f(float x);\nfloat f(float x) { return f(x); }\n"
				+ "void main() { gl_FragColor = vec4(f(1.0)); }";
		assertTrue(codes(strict(src, Stage.FRAGMENT)).contains("recursion"));
	}

	@Test
	void aCalledPrototypeWithoutADefinitionIsAnError() {
		String src = "#version 120\nfloat f(float x);\nvoid main() { gl_FragColor = vec4(f(1.0)); }";
		assertTrue(codes(strict(src, Stage.FRAGMENT)).contains("undefined-function"));
		assertTrue(codes(lenient(src, Stage.FRAGMENT)).contains("undefined-function"));
	}

	@Test
	void aNonIntegerCaseLabelIsADiagnosticNotACrash() {
		String src = "#version 130\nvoid main() { int x = 1; switch (x) { case 1.0: break; default: break; } }";
		assertFalse(strict(src, Stage.FRAGMENT).ok());
	}

	@Test
	void aNonIntegerConstantIndexIsADiagnosticNotACrash() {
		String src = "#version 120\nvoid main() { float a[2]; float v = a[1.0]; gl_FragColor = vec4(v); }";
		assertFalse(strict(src, Stage.FRAGMENT).ok());
	}

	@Test
	void arrayConstructorIsGlsl120NotLeniencyAt120To419() {
		String src = "#version 120\nvoid main() { float a[2] = float[2](1.0, 2.0); gl_FragColor = vec4(a[0]); }";
		SemaResult r = lenient(src, Stage.FRAGMENT);
		assertTrue(r.ok(), r.errors().toString());
		assertFalse(warningCodes(r).contains("array-constructor"));
	}

	@Test
	void braceInitializerListsAreAnInitializerLeniencyBefore420() {
		String src = "#version 120\nvoid main() { float a[2] = {1.0, 2.0}; gl_FragColor = vec4(a[0]); }";
		assertTrue(codes(strict(src, Stage.FRAGMENT)).contains("initializer-list"));
		assertTrue(warningCodes(lenient(src, Stage.FRAGMENT)).contains("initializer-list"));
	}

	@Test
	void braceInitializerListsAreAllowedFrom420() {
		String src = "#version 420\nout vec4 o;\nvoid main() { float a[2] = {1.0, 2.0}; o = vec4(a[0]); }";
		assertTrue(strict(src, Stage.FRAGMENT).ok(), strict(src, Stage.FRAGMENT).errors().toString());
	}

	@Test
	void anUndeclaredGameNameIsNotLicensedUnlessListed() {
		String src = "#version 120\nvoid main() { vec3 c = mc_Entity; gl_FragColor = vec4(c, 1.0); }";
		assertTrue(codes(strict(src, Stage.FRAGMENT)).contains("unknown-identifier"));
	}

	@Test
	void theLisrtedGameNamesAreALeniencyWhenUndeclared() {
		String src = "#version 120\nvoid main() { vec4 p = projectionMatrix[0]; gl_FragColor = p; }";
		assertTrue(codes(strict(src, Stage.FRAGMENT)).contains("undeclared-game-name"));
		SemaResult r = lenient(src, Stage.FRAGMENT);
		assertTrue(r.ok(), r.errors().toString());
		assertTrue(warningCodes(r).contains("undeclared-game-name"));
	}

	@Test
	void constantBuiltinCallsFoldForArraySizesAndConstants() {
		String src = "#version 130\nout vec4 o;\nconst int N = int(log2(8.0));\nfloat a[N];\nconst float e = exp(1.0);\n"
				+ "void main() { gl_FragColor = vec4(a[0] + e); }";
		SemaResult r = strict(src, Stage.FRAGMENT);
		assertTrue(r.ok(), r.errors().toString());
	}

	@Test
	void textureSizeOfAShadowSamplerIsIvec2() {
		String src = "#version 330\nuniform sampler2DShadow s;\nout vec4 o;\nvoid main() { ivec2 z = textureSize(s, 0); "
				+ "o = vec4(z, 0, 0); }";
		assertTrue(strict(src, Stage.FRAGMENT).ok(), strict(src, Stage.FRAGMENT).errors().toString());
	}

	@Test
	void textureProjOffsetTakesAnIvec2Offset() {
		String src = "#version 330\nuniform sampler2D s;\nout vec4 o;\nvoid main() { vec4 c = textureProjOffset(s, vec4(0.5), "
				+ "ivec2(1, 0)); o = c; }";
		assertTrue(strict(src, Stage.FRAGMENT).ok(), strict(src, Stage.FRAGMENT).errors().toString());
	}

	/**
	 * The array {@code float a[int(expr)]} has exactly n elements: a[n - 1] is in bounds and a[n] is out of bounds. The
	 * program must also be clean, so a size that failed to fold cannot pass.
	 */
	private static void assertArraySizeIs(String defs, String expr, int n) {
		String head = "#version 330\n" + defs + "\nout vec4 o;\nfloat a[int(" + expr + ")];\n";
		SemaResult inBounds = strict(head + "void main() { o = vec4(a[" + (n - 1) + "]); }", Stage.FRAGMENT);
		assertTrue(inBounds.ok(), inBounds.errors().toString());
		assertTrue(codes(strict(head + "void main() { o = vec4(a[" + n + "]); }", Stage.FRAGMENT))
				.contains("index-out-of-bounds"));
	}

	@Test
	void matrixTimesVectorFoldsToTheColumnMajorProduct() {
		// M = [[1, 3], [2, 4]] (columns (1, 2) and (3, 4)); M * (2, 3) = (1*2 + 3*3, 2*2 + 4*3) = (11, 16)
		String defs = "const mat2 m = mat2(1.0, 2.0, 3.0, 4.0);\nconst vec2 v = m * vec2(2.0, 3.0);";
		assertArraySizeIs(defs, "v.x", 11);
		assertArraySizeIs(defs, "v.y", 16);
	}

	@Test
	void vectorTimesMatrixFoldsWithTheVectorAsARow() {
		// (2, 3) * M = (2*1 + 3*2, 2*3 + 3*4) = (8, 18)
		String defs = "const mat2 m = mat2(1.0, 2.0, 3.0, 4.0);\nconst vec2 w = vec2(2.0, 3.0) * m;";
		assertArraySizeIs(defs, "w.x", 8);
		assertArraySizeIs(defs, "w.y", 18);
	}

	@Test
	void matrixTimesMatrixFoldsColumnByColumn() {
		// M * M = [[7, 15], [10, 22]]: column 0 is (7, 10), column 1 is (15, 22)
		String defs = "const mat2 m = mat2(1.0, 2.0, 3.0, 4.0);\nconst mat2 n = m * m;";
		assertArraySizeIs(defs, "n[0].x", 7);
		assertArraySizeIs(defs, "n[0].y", 10);
		assertArraySizeIs(defs, "n[1].x", 15);
		assertArraySizeIs(defs, "n[1].y", 22);
	}

	@Test
	void mixedSignedUnsignedBuiltinCallsResolveWithoutAmbiguityIn400() {
		// Section 6.1.1: max(1, u) matches max(uint, uint) exactly in its second argument and the float overloads only
		// by conversion, so the uint overload is the best match. clamp(u, 0, 7) likewise takes the uint overload.
		String src = "#version 400\nuniform uint u;\nout vec4 o;\nvoid main() { uint a = max(1, u); "
				+ "uint b = clamp(u, 0, 7); o = vec4(float(a + b)); }";
		SemaResult r = strict(src, Stage.FRAGMENT);
		assertFalse(codes(r).contains("ambiguous-call"), r.errors().toString());
		assertTrue(r.ok(), r.errors().toString());
	}
	/** A 1.20 unit: the int overload of a 1.30 built-in is taken in lenient mode (with the later-builtin warning) and
	 * the unit is an error in strict mode, where the float overload is the only one. */
	private static void assertLaterIntOverloadProbe(String body, boolean strictFails) {
		String src = "#version 120\nvoid main() { int a = -3; " + body + " }";
		SemaResult accepted = lenient(src, Stage.FRAGMENT);
		assertTrue(accepted.ok(), accepted.errors().toString());
		assertTrue(warningCodes(accepted).contains("later-builtin"), body);
		if (strictFails) {
			assertFalse(strict(src, Stage.FRAGMENT).ok(), body);
		}
	}

	@Test
	void intAbsInA120UsesTheIntOverloadInLenientModeOnly() {
		assertLaterIntOverloadProbe("int h = abs(a) / 2; gl_FragColor = vec4(h);", true);
	}

	@Test
	void intMinInA120UsesTheIntOverloadInLenientModeOnly() {
		assertLaterIntOverloadProbe("int x = min(a, 3); gl_FragColor = vec4(x);", true);
	}

	@Test
	void intMaxDividedInA120IsIntegerDivisionInLenientMode() {
		// max(a, 1) / 2 is int division when the int overload is taken; a float result would not be assignable to int
		assertLaterIntOverloadProbe("int q = max(a, 1) / 2; gl_FragColor = vec4(q);", true);
	}

	@Test
	void intRemainderOfAbsInA120IsALenientIntegerOperation() {
		assertLaterIntOverloadProbe("int y = abs(a) % 2; gl_FragColor = vec4(y);", true);
	}

	@Test
	void intMinAsAnArrayIndexInA120IsALenientIntegerIndex() {
		assertLaterIntOverloadProbe("float arr[3]; float z = arr[min(a, 3)]; gl_FragColor = vec4(z);", true);
	}

	@Test
	void intMinAsAnAssignmentInA120IsALenientIntegerValue() {
		assertLaterIntOverloadProbe("int x = 0; x = min(a, 3); gl_FragColor = vec4(x);", true);
	}

	@Test
	void floatParameterAcceptsTheFloatOverloadOfMaxInBothModes() {
		// max(a, 1) as a float argument: the float overload is valid in strict mode; lenient mode takes the int one
		String src = "#version 120\nvoid g(float v) {}\nvoid main() { int a = -3; g(max(a, 1)); }";
		assertTrue(strict(src, Stage.FRAGMENT).ok(), strict(src, Stage.FRAGMENT).errors().toString());
		SemaResult accepted = lenient(src, Stage.FRAGMENT);
		assertTrue(accepted.ok(), accepted.errors().toString());
		assertTrue(warningCodes(accepted).contains("later-builtin"));
	}

	@Test
	void aDefinitionCompletesThePrototypeAndTheMergedOverloadIsTheDefinitions() {
		String src = "#version 120\nfloat f(float x);\nfloat f(float x) { return x; }\n"
				+ "void main() { gl_FragColor = vec4(f(1.0)); }";
		Unit unit = Parser.parse(src);
		SemaResult r = SemanticAnalyzer.analyze(unit, SemaOptions.strict(Stage.FRAGMENT));
		assertTrue(r.ok(), r.errors().toString());
		// the declaration list holds the bare prototype, then the definition, then main()
		FunctionPrototype proto = (FunctionPrototype) unit.decls().get(0);
		FunctionDefinition def = (FunctionDefinition) unit.decls().get(1);
		assertTrue(def.body() != null);
		FunctionOverload fromPrototype = r.functionOf(proto);
		assertTrue(fromPrototype != null && fromPrototype.defined());
		assertTrue(fromPrototype == r.functionOf(def.proto()));
		assertTrue(fromPrototype.declaration() == def.proto());
		assertTrue(r.functions().contains(fromPrototype));
	}

	/**
	 * E9: every write form marks the global it roots in (the emitter keeps a written global per invocation; one nothing
	 * writes becomes a program constant). A write only in a function no one calls counts too.
	 */
	@Test
	void everyWriteFormMarksItsRootGlobalAndReadsDoNot() {
		String src = """
			#version 130
			struct S { float a; float b; };
			float assigned = 1.0; float compound = 1.0; float pre = 1.0; float post = 1.0;
			vec2 swizzled = vec2(1.0); S field = S(1.0, 2.0); float element[2] = float[2](1.0, 2.0);
			float userOut = 1.0; float builtinOut = 1.0; float uncalled = 1.0; float onlyRead = 1.0;
			void setTo(out float x) { x = 2.0; }
			void never() { uncalled = 3.0; }
			out vec4 color;
			void main() {
				assigned = 2.0; compound *= 2.0; --pre; post++;
				swizzled.y = 3.0; field.a = 4.0; element[1] = 5.0;
				setTo(userOut);
				float f = modf(1.5, builtinOut);
				color = vec4(onlyRead + f);
			}
			""";
		SemaResult r = strict(src, Stage.FRAGMENT);
		assertTrue(r.ok(), r.errors().toString());
		Set<String> written = r.globals().stream().filter(r::isWritten).map(VariableSymbol::name).collect(Collectors.toSet());
		assertEquals(Set.of("assigned", "compound", "pre", "post", "swizzled", "field", "element", "userOut", "builtinOut",
			"uncalled", "color"), written);
	}
}
