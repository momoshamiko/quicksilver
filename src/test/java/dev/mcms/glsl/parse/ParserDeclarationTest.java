// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.parse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import dev.mcms.glsl.ast.BuiltinTypeSpec;
import dev.mcms.glsl.ast.ExtensionDirective;
import dev.mcms.glsl.ast.FunctionDefinition;
import dev.mcms.glsl.ast.FunctionPrototype;
import dev.mcms.glsl.ast.InterfaceBlock;
import dev.mcms.glsl.ast.IntLiteral;
import dev.mcms.glsl.ast.LayoutItem;
import dev.mcms.glsl.ast.LayoutQual;
import dev.mcms.glsl.ast.MemoryKind;
import dev.mcms.glsl.ast.MemoryQual;
import dev.mcms.glsl.ast.PragmaDirective;
import dev.mcms.glsl.ast.PrecisionDecl;
import dev.mcms.glsl.ast.PrecisionKind;
import dev.mcms.glsl.ast.QualifierDecl;
import dev.mcms.glsl.ast.StorageKind;
import dev.mcms.glsl.ast.StorageQual;
import dev.mcms.glsl.ast.StructDefSpec;
import dev.mcms.glsl.ast.StructMember;
import dev.mcms.glsl.ast.Unit;
import dev.mcms.glsl.ast.VarDecl;
import dev.mcms.glsl.GlslSyntaxException;

/** Every declaration form: qualifiers and their orders, structs, interface blocks, prototypes, precision, errors. */
class ParserDeclarationTest {
	@Test
	void variableDeclarationWithQualifiers() {
		VarDecl v = TestParse.varDecl("uniform vec4 color;");
		assertEquals(1, v.type().qualifiers().size());
		StorageQual s = (StorageQual) v.type().qualifiers().getFirst();
		assertEquals(StorageKind.UNIFORM, s.kind());
		assertEquals("vec4", ((BuiltinTypeSpec) v.type().spec()).name());
		assertEquals("color", v.declarators().getFirst().name());
		assertNull(v.declarators().getFirst().initializer());
	}

	@Test
	void qualifierOrdersPacksUseArePreserved() {
		VarDecl v = TestParse.varDecl("flat in vec3 normal;");
		assertEquals(2, v.type().qualifiers().size());
		assertEquals("flat", ((dev.mcms.glsl.ast.InterpQual) v.type().qualifiers().get(0)).kind().word());
		assertEquals(StorageKind.IN, ((StorageQual) v.type().qualifiers().get(1)).kind());

		VarDecl reversed = TestParse.varDecl("in flat vec3 normal;");
		assertEquals(StorageKind.IN, ((StorageQual) reversed.type().qualifiers().get(0)).kind());

		QualifierDecl bothQualifiers = (QualifierDecl) TestParse.decl("invariant precise position0;");
		assertEquals(2, bothQualifiers.qualifiers().size());
		assertEquals("position0", bothQualifiers.names().getFirst());
	}

	@Test
	void layoutQualifierWithAndWithoutValues() {
		VarDecl v = TestParse.varDecl("layout(binding = 2) uniform sampler2D tex;");
		LayoutQual l = (LayoutQual) v.type().qualifiers().getFirst();
		assertEquals(1, l.items().size());
		LayoutItem item = l.items().getFirst();
		assertEquals("binding", item.name());
		assertEquals(2L, ((IntLiteral) item.value()).value());

		VarDecl named = TestParse.varDecl("layout(rgba16f, binding = 1) uniform image2D img;");
		LayoutQual l2 = (LayoutQual) named.type().qualifiers().getFirst();
		assertEquals("rgba16f", l2.items().get(0).name());
		assertNull(l2.items().get(0).value());
		assertEquals(1L, ((IntLiteral) l2.items().get(1).value()).value());
	}

	@Test
	void severalDeclaratorsWithInitializers() {
		VarDecl v = TestParse.varDecl("float a = 1.0, b[2], c;");
		assertEquals(3, v.declarators().size());
		assertEquals("a", v.declarators().get(0).name());
		assertTrue(v.declarators().get(0).initializer() != null, "expected an initializer");
		assertEquals(1, v.declarators().get(1).dims().size());
		assertNull(v.declarators().get(2).initializer());
	}

	@Test
	void dimensionLengthIsAnExpression() {
		VarDecl v = TestParse.varDecl("shared vec4 tile[8 * 8];");
		dev.mcms.glsl.ast.BinaryExpr len = (dev.mcms.glsl.ast.BinaryExpr) v.declarators().getFirst().dims()
				.getFirst().length();
		assertEquals(dev.mcms.glsl.ast.BinaryOp.MUL, len.op());
		assertEquals(8L, ((IntLiteral) len.left()).value());
		assertEquals(8L, ((IntLiteral) len.right()).value());
	}

	@Test
	void arrayDimensionsOnTypeAndDeclarator() {
		VarDecl onType = TestParse.varDecl("float[3] x;");
		assertEquals(1, onType.type().dims().size());
		assertEquals(0, onType.declarators().getFirst().dims().size());

		VarDecl onName = TestParse.varDecl("float x[3];");
		assertEquals(0, onName.type().dims().size());
		assertEquals(1, onName.declarators().getFirst().dims().size());

		VarDecl both = TestParse.varDecl("vec2[2][2] m[4];");
		assertEquals(2, both.type().dims().size());
		assertEquals(1, both.declarators().getFirst().dims().size());

		VarDecl unsized = TestParse.varDecl("uniform float a[];");
		assertNull(unsized.declarators().getFirst().dims().getFirst().length());
	}


	@Test
	void structStandaloneInlineAnonymousAndNested() {
		VarDecl standalone = TestParse.varDecl("struct Light { vec3 position; float power; };");
		StructDefSpec spec = (StructDefSpec) standalone.type().spec();
		assertEquals("Light", spec.name());
		assertEquals(2, spec.members().size());
		assertEquals(0, standalone.declarators().size());

		VarDecl inline = TestParse.varDecl("struct Light { vec3 p; } sun, lights[4];");
		assertEquals("Light", ((StructDefSpec) inline.type().spec()).name());
		assertEquals(2, inline.declarators().size());
		assertEquals(1, inline.declarators().get(1).dims().size());

		VarDecl anonymous = TestParse.varDecl("struct { int x; } a;");
		assertNull(((StructDefSpec) anonymous.type().spec()).name());

		VarDecl nested = TestParse.varDecl("struct A { struct B { int y; } b; };");
		StructMember member = ((StructDefSpec) nested.type().spec()).members().getFirst();
		assertEquals("B", ((StructDefSpec) member.type().spec()).name());
	}

	@Test
	void structReferencesByName() {
		Unit u = TestParse.unit("""
				struct Light { vec3 p; };
				Light sun;
				void main() { Light copy = sun; }
				""");
		VarDecl sun = (VarDecl) u.decls().get(1);
		assertEquals("Light", ((dev.mcms.glsl.ast.NameTypeSpec) sun.type().spec()).name());
	}

	@Test
	void structMembersCannotHaveInitializers() {
		GlslSyntaxException e = assertThrows(GlslSyntaxException.class,
				() -> TestParse.decl("struct S { float x = 1.0; };"));
		assertTrue(e.getMessage().contains("initializers"));
	}

	@Test
	void functionPrototypesWithAndWithoutParameterNames() {
		FunctionPrototype p = (FunctionPrototype) TestParse.decl("vec4 f(vec4, in float, out vec3 d[2]);");
		assertEquals("f", p.name());
		assertEquals(3, p.params().size());
		assertNull(p.params().get(0).name());
		assertEquals(StorageKind.OUT, ((StorageQual) p.params().get(2).type().qualifiers().getFirst()).kind());
		assertEquals(1, p.params().get(2).dims().size());
		assertEquals("vec4", ((BuiltinTypeSpec) p.returnType().spec()).name());
	}

	@Test
	void voidParameterListIsEmpty() {
		FunctionPrototype p = (FunctionPrototype) TestParse.decl("void f(void);");
		assertEquals(0, p.params().size());
		FunctionPrototype empty = (FunctionPrototype) TestParse.decl("void f();");
		assertEquals(0, empty.params().size());
	}

	@Test
	void functionDefinitionAndReturnTypeQualifiers() {
		FunctionPrototype p = (FunctionPrototype) TestParse.decl("layout(location = 0) out vec4 frag();");
		assertEquals(2, p.returnType().qualifiers().size());

		FunctionDefinition def = (FunctionDefinition) TestParse.decl("void main() { }");
		assertEquals(0, def.body().statements().size());
	}

	@Test
	void precisionStatements() {
		PrecisionDecl p = (PrecisionDecl) TestParse.decl("precision highp float;");
		assertEquals(PrecisionKind.HIGH, p.precision());
		assertEquals("float", ((BuiltinTypeSpec) p.type()).name());
		PrecisionDecl q = (PrecisionDecl) TestParse.decl("precision mediump sampler3D;");
		assertEquals(PrecisionKind.MEDIUM, q.precision());
	}

	@Test
	void qualifierOnlyDeclarations() {
		QualifierDecl localSize = (QualifierDecl) TestParse.decl("layout(local_size_x = 8) in;");
		assertEquals(2, localSize.qualifiers().size());
		assertEquals(0, localSize.names().size());

		QualifierDecl invariant = (QualifierDecl) TestParse.decl("invariant gl_Position;");
		assertEquals(1, invariant.names().size());
		assertEquals("gl_Position", invariant.names().getFirst());

		QualifierDecl several = (QualifierDecl) TestParse.decl("invariant a, b;");
		assertEquals(2, several.names().size());
	}

	@Test
	void interfaceBlocks() {
		InterfaceBlock b = (InterfaceBlock) TestParse.decl(
				"layout(std430, binding = 1) buffer B { float data[]; } reduction;");
		assertEquals("B", b.name());
		assertEquals("reduction", b.instance());
		assertEquals(0, b.instanceDims().size());
		assertEquals(2, b.qualifiers().size());
		assertInstanceOf(LayoutQual.class, b.qualifiers().get(0));
		assertEquals(StorageKind.BUFFER, ((StorageQual) b.qualifiers().get(1)).kind());
		StructMember member = b.members().getFirst();
		assertEquals("data", member.declarators().getFirst().name());
		assertNull(member.declarators().getFirst().dims().getFirst().length());

		InterfaceBlock noInstance = (InterfaceBlock) TestParse.decl(
				"layout(std140) uniform Matrices { mat4 view; };");
		assertNull(noInstance.instance());
		assertEquals(StorageKind.UNIFORM, ((StorageQual) noInstance.qualifiers().get(1)).kind());

		InterfaceBlock withDims = (InterfaceBlock) TestParse.decl(
				"out V { flat vec3 n; } v[4];");
		assertEquals(1, withDims.instanceDims().size());
		StructMember m = withDims.members().getFirst();
		assertEquals("flat", ((dev.mcms.glsl.ast.InterpQual) m.type().qualifiers().getFirst()).kind().word());

		InterfaceBlock memory = (InterfaceBlock) TestParse.decl(
				"coherent readonly buffer C { int x; };");
		assertEquals(MemoryKind.COHERENT, ((MemoryQual) memory.qualifiers().get(0)).kind());
		assertEquals(MemoryKind.READONLY, ((MemoryQual) memory.qualifiers().get(1)).kind());
	}

	@Test
	void versionLineIsReadWithProfileAndDefaultsTo110() {
		Unit u = TestParse.unit("#version 400 compatibility\nuniform float x;");
		assertEquals(400, u.version().number());
		assertEquals("compatibility", u.version().profile());

		Unit plain = TestParse.unit("#version 430\nvoid main() { }");
		assertEquals(430, plain.version().number());
		assertNull(plain.version().profile());

		Unit none = TestParse.unit("uniform float x;");
		assertNull(none.version());
	}

	@Test
	void extensionAndPragmaLines() {
		Unit u = TestParse.unit("""
				#version 130
				#extension GL_ARB_shader_texture_lod : enable
				#extension all : warn
				#pragma optimize(off)
				uniform float x;
				""");
		assertEquals(3, u.directives().size());
		ExtensionDirective e = (ExtensionDirective) u.directives().get(0);
		assertEquals("GL_ARB_shader_texture_lod", e.name());
		assertEquals("enable", e.behavior());
		assertEquals("all", ((ExtensionDirective) u.directives().get(1)).name());
		PragmaDirective p = (PragmaDirective) u.directives().get(2);
		assertEquals("optimize(off)", p.text());
	}

	@Test
	void directivesAfterTheFirstLineAndUnknownDirectivesThrow() {
		GlslSyntaxException e1 = assertThrows(GlslSyntaxException.class,
				() -> TestParse.unit("uniform float x;\n#version 330\n"));
		assertTrue(e1.getMessage().contains("#version must be the first line"));

		GlslSyntaxException e2 = assertThrows(GlslSyntaxException.class,
				() -> TestParse.unit("#include \"lib/common.glsl\"\n"));
		assertTrue(e2.getMessage().contains("unexpected directive"));

		GlslSyntaxException e3 = assertThrows(GlslSyntaxException.class,
				() -> TestParse.unit("#version 150 garbage here\n"));
		assertTrue(e3.getMessage().contains("malformed #version"));
	}

	@Test
	void missingPiecesAreReportedWithExpectations() {
		GlslSyntaxException e = assertThrows(GlslSyntaxException.class, () -> TestParse.decl("uniform vec4 5;"));
		assertTrue(e.getMessage().contains("expected an identifier"));
		assertEquals(2, e.line()); // line 1 is the #version line TestParse adds
		assertEquals(14, e.column());

		assertThrows(GlslSyntaxException.class, () -> TestParse.decl("float x"));
		assertThrows(GlslSyntaxException.class, () -> TestParse.decl("struct S { int x }"));
		assertThrows(GlslSyntaxException.class, () -> TestParse.decl("void f( { }"));
	}

	@Test
	void positionOfDeclarationIsItsFirstToken() {
		VarDecl v = TestParse.varDecl("uniform vec4 color;");
		assertEquals(new dev.mcms.glsl.ast.Pos(2, 1), v.pos()); // after the added #version line
		Unit u = TestParse.unit("\n\nfloat x;");
		VarDecl second = (VarDecl) u.decls().getFirst();
		assertEquals(3, second.pos().line());
	}

	@Test
	void version120AllowsLaterReservedWordsAsNames() {
		Unit u = TestParse.unit("""
				#version 120
				float filter = 1.0;
				float active = 2.0;
				float superp = 3.0;
				void main() { }
				""");
		assertEquals(4, u.decls().size());
		assertEquals("filter", ((VarDecl) u.decls().get(0)).declarators().getFirst().name());
	}

	@Test
	void version110AllowsPrecisionAsAName() {
		Unit u = TestParse.unit("float precision = 0.5;\n");
		assertNull(u.version());
		assertEquals("precision", ((VarDecl) u.decls().getFirst()).declarators().getFirst().name());
	}

	@Test
	void loneSemicolonAtFileScopeIsAccepted() {
		Unit u = TestParse.unit("void f() { };\nuniform float x;;\n");
		assertEquals(2, u.decls().size());
	}

	@Test
	void preciseQualifierDeclaration() {
		QualifierDecl q = (QualifierDecl) TestParse.decl("precise gl_Position;");
		assertEquals(1, q.qualifiers().size());
		assertEquals(java.util.List.of("gl_Position"), q.names());
		assertEquals(1, q.namePositions().size());
	}

	@Test
	void braceInitializerLists() {
		VarDecl v = TestParse.varDecl("float a[2] = {1.0, 2.0};");
		dev.mcms.glsl.ast.InitializerList list = (dev.mcms.glsl.ast.InitializerList) v.declarators().getFirst()
				.initializer();
		assertEquals(2, list.items().size());

		VarDecl nested = TestParse.varDecl("float b[2][2] = {{1.0, 2.0}, {3.0, 4.0}};");
		dev.mcms.glsl.ast.InitializerList outer = (dev.mcms.glsl.ast.InitializerList) nested.declarators().getFirst()
				.initializer();
		dev.mcms.glsl.ast.InitializerList first = (dev.mcms.glsl.ast.InitializerList) outer.items().getFirst();
		assertEquals(2, first.items().size());

		VarDecl mixed = TestParse.varDecl("float c[3] = {1.0, 2.0, 3.0};");
		assertEquals("{1.0, 2.0, 3.0}",
				dev.mcms.glsl.ast.GlslPrinter.print(mixed.declarators().getFirst().initializer()));
	}

	@Test
	void qualifierListsAreImmutable() {
		VarDecl v = TestParse.varDecl("flat in vec3 normal;");
		assertThrows(UnsupportedOperationException.class, () -> v.type().qualifiers().add(null));
		QualifierDecl q = (QualifierDecl) TestParse.decl("invariant a, b;");
		assertThrows(UnsupportedOperationException.class, () -> q.names().add("c"));
	}
}
