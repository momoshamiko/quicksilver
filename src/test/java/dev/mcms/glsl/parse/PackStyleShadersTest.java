// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.parse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.mcms.glsl.ast.AssignExpr;
import dev.mcms.glsl.ast.BinaryExpr;
import dev.mcms.glsl.ast.BinaryOp;
import dev.mcms.glsl.ast.BlockStmt;
import dev.mcms.glsl.ast.BuiltinTypeSpec;
import dev.mcms.glsl.ast.CallExpr;
import dev.mcms.glsl.ast.Decl;
import dev.mcms.glsl.ast.Declarator;
import dev.mcms.glsl.ast.CaseStmt;
import dev.mcms.glsl.ast.DeclStmt;
import dev.mcms.glsl.ast.ExprStmt;
import dev.mcms.glsl.ast.FieldExpr;
import dev.mcms.glsl.ast.ForStmt;
import dev.mcms.glsl.ast.FunctionDefinition;
import dev.mcms.glsl.ast.Ident;
import dev.mcms.glsl.ast.IndexExpr;
import dev.mcms.glsl.ast.InterfaceBlock;
import dev.mcms.glsl.ast.IntLiteral;
import dev.mcms.glsl.ast.NameCallee;
import dev.mcms.glsl.ast.NameTypeSpec;
import dev.mcms.glsl.ast.Param;
import dev.mcms.glsl.ast.PragmaDirective;
import dev.mcms.glsl.ast.QualifierDecl;
import dev.mcms.glsl.ast.StorageKind;
import dev.mcms.glsl.ast.StorageQual;
import dev.mcms.glsl.ast.StructDefSpec;
import dev.mcms.glsl.ast.Stmt;
import dev.mcms.glsl.ast.SwitchStmt;
import dev.mcms.glsl.ast.TypeCallee;
import dev.mcms.glsl.ast.UIntLiteral;
import dev.mcms.glsl.ast.Unit;
import dev.mcms.glsl.ast.VarDecl;

/**
 * The pack-style shaders of {@link TestShaders} parse with the structure the translator will rely on: legacy built-ins
 * stay plain identifiers, gl_FragData is an index, shared arrays, image uniforms, std430 blocks, switch fall-through,
 * const arrays with array constructors, out/inout parameters, structs with instance declarators (corpus §2-§3, §11).
 */
class PackStyleShadersTest {
	private static Unit source(String name) {
		for (TestShaders.Source s : TestShaders.ALL) {
			if (s.name().equals(name)) {
				return Parser.parse(s.text());
			}
		}
		throw new AssertionError("no source " + name);
	}

	private static VarDecl var(Unit u, String name) {
		for (Decl d : u.decls()) {
			if (d instanceof VarDecl v && v.declarators().size() == 1
					&& v.declarators().getFirst().name().equals(name)) {
				return v;
			}
		}
		throw new AssertionError("no variable " + name);
	}

	private static FunctionDefinition main(Unit u) {
		return (FunctionDefinition) u.decls().getLast();
	}

	@Test
	void legacy120VertexUsesCompatibilityBuiltinsAsIdentifiers() {
		Unit u = source("legacy120Vertex");
		assertEquals(120, u.version().number());
		assertNull(u.version().profile());

		VarDecl entity = var(u, "mc_Entity");
		assertEquals(StorageKind.ATTRIBUTE, ((StorageQual) entity.type().qualifiers().getFirst()).kind());

		VarDecl tex = var(u, "texture");
		assertEquals("sampler2D", ((BuiltinTypeSpec) tex.type().spec()).name());

		List<Stmt> body = main(u).body().statements();
		AssignExpr a = (AssignExpr) ((ExprStmt) body.get(0)).expr();
		FieldExpr value = (FieldExpr) a.value();
		assertEquals("xy", value.name());
		BinaryExpr mul = (BinaryExpr) value.base();
		assertEquals("gl_TextureMatrix", ((Ident) ((IndexExpr) mul.left()).base()).name());
		assertEquals("gl_MultiTexCoord0", ((Ident) mul.right()).name());
		assertEquals("ftransform", ((NameCallee) ((CallExpr) ((AssignExpr) ((ExprStmt) body.get(3)).expr())
				.value()).callee()).name());
	}

	@Test
	void legacy120FragmentWritesGlFragData() {
		Unit u = source("legacy120Fragment");
		List<Stmt> body = main(u).body().statements();
		IndexExpr target = (IndexExpr) ((AssignExpr) ((ExprStmt) body.get(2)).expr()).target();
		assertEquals("gl_FragData", ((Ident) target.base()).name());
		assertEquals(0L, ((IntLiteral) target.index()).value());
		dev.mcms.glsl.ast.DeclStmt albedoDecl = (dev.mcms.glsl.ast.DeclStmt) body.get(1);
		BinaryExpr mul = (BinaryExpr) ((VarDecl) albedoDecl.decl()).declarators().getFirst().initializer();
		CallExpr texture2D = (CallExpr) mul.left();
		assertEquals("texture2D", ((NameCallee) texture2D.callee()).name());
	}
	@Test
	void compat130DeclaresASamplerNamedTexture() {
		Unit u = source("compat130TextureName");
		assertEquals(130, u.version().number());
		VarDecl tex = var(u, "texture");
		assertEquals("texture", tex.declarators().getFirst().name());
		VarDecl material = var(u, "material");
		assertEquals("flat", ((dev.mcms.glsl.ast.InterpQual) material.type().qualifiers().get(0)).kind().word());
		assertEquals(StorageKind.VARYING, ((StorageQual) material.type().qualifiers().get(1)).kind());
	}

	@Test
	void compat400RecordsProfile() {
		Unit u = source("compat400Mixed");
		assertEquals(400, u.version().number());
		assertEquals("compatibility", u.version().profile());
		VarDecl screenPos = var(u, "screenPos");
		assertEquals("noperspective", ((dev.mcms.glsl.ast.InterpQual) screenPos.type().qualifiers().get(0)).kind()
				.word());
		assertEquals(StorageKind.OUT, ((StorageQual) screenPos.type().qualifiers().get(1)).kind());
	}

	@Test
	void compute430HasWorkGroupLayoutSharedArraysImageAndStd430Block() {
		Unit u = source("compute430");
		assertEquals(430, u.version().number());
		assertEquals(1, u.directives().size());
		assertEquals("optimize(off)", ((PragmaDirective) u.directives().getFirst()).text());

		QualifierDecl localSize = (QualifierDecl) u.decls().get(0);
		assertEquals(2, localSize.qualifiers().size());
		assertEquals(0, localSize.names().size());

		VarDecl noise = var(u, "noiseVolume");
		assertEquals("image3D", ((BuiltinTypeSpec) noise.type().spec()).name());

		VarDecl tile = var(u, "tile");
		assertEquals(StorageKind.SHARED, ((StorageQual) tile.type().qualifiers().getFirst()).kind());
		BinaryExpr len = (BinaryExpr) tile.declarators().getFirst().dims().getFirst().length();
		assertEquals(BinaryOp.MUL, len.op());
		assertEquals(8L, ((IntLiteral) len.left()).value());
		assertEquals(8L, ((IntLiteral) len.right()).value());

		InterfaceBlock reduction = (InterfaceBlock) u.decls().stream()
				.filter(d -> d instanceof InterfaceBlock).findFirst().orElseThrow();
		assertEquals("Reduction", reduction.name());
		assertEquals("reduction", reduction.instance());
		assertNull(reduction.members().getFirst().declarators().getFirst().dims().getFirst().length());
	}

	@Test
	void compute430ForSwitchStoresAndCompoundAssignments() {
		Unit u = source("compute430");
		List<Stmt> body = main(u).body().statements();
		assertEquals(9, body.size());

		ExprStmt barrier = (ExprStmt) body.get(4);
		assertEquals("barrier", ((NameCallee) ((CallExpr) barrier.expr()).callee()).name());

		ForStmt loop = (ForStmt) body.get(5);
		assertInstanceOf(DeclStmt.class, loop.init());
		AssignExpr compound = (AssignExpr) ((ExprStmt) ((BlockStmt) loop.body()).statements().getFirst()).expr();
		assertEquals("+=", compound.op().word());

		SwitchStmt sw = (SwitchStmt) body.get(6);
		BinaryExpr head = (BinaryExpr) sw.head();
		assertEquals("&", head.op().word());
		List<Stmt> cases = ((BlockStmt) sw.body()).statements();
		assertEquals(8, cases.size());
		assertEquals(0L, ((UIntLiteral) ((CaseStmt) cases.get(0)).value()).value());
		// case 0u falls through: no break before case 1u.
		assertInstanceOf(CaseStmt.class, cases.get(2));
		assertInstanceOf(dev.mcms.glsl.ast.BreakStmt.class, cases.get(4));
		assertInstanceOf(dev.mcms.glsl.ast.DefaultStmt.class, cases.get(5));
		assertInstanceOf(ExprStmt.class, cases.get(6));
		assertInstanceOf(dev.mcms.glsl.ast.BreakStmt.class, cases.get(7));

		ExprStmt store = (ExprStmt) body.get(7);
		assertEquals("imageStore", ((NameCallee) ((CallExpr) store.expr()).callee()).name());

		IndexExpr weights = (IndexExpr) ((AssignExpr) ((ExprStmt) body.get(8)).expr()).target();
		assertEquals("weights", ((FieldExpr) weights.base()).name());
		assertEquals("reduction", ((Ident) ((FieldExpr) weights.base()).base()).name());
	}

	@Test
	void constArraysUseArrayConstructors() {
		Unit u = source("constArrays330");
		Declarator d = var(u, "kernel").declarators().getFirst();
		assertEquals(5L, ((IntLiteral) d.dims().getFirst().length()).value());
		CallExpr init = (CallExpr) d.initializer();
		TypeCallee callee = (TypeCallee) init.callee();
		assertEquals("float", ((BuiltinTypeSpec) callee.spec()).name());
		assertEquals(5, init.args().size());

		FunctionDefinition weight = (FunctionDefinition) u.decls().get(3);
		Param scaled = weight.proto().params().getLast();
		assertEquals(StorageKind.OUT, ((StorageQual) scaled.type().qualifiers().getFirst()).kind());
		Param w = weight.proto().params().getFirst();
		assertEquals(2, w.type().qualifiers().size());
		assertEquals(1, w.dims().size());
	}

	@Test
	void structs460HasInstanceDeclaratorAndStructTypedParameters() {
		Unit u = source("structs460");
		VarDecl scene = var(u, "scene");
		StructDefSpec spec = (StructDefSpec) scene.type().spec();
		assertEquals("Scene", spec.name());
		assertEquals("lights", spec.members().getFirst().declarators().getFirst().name());

		VarDecl sun = var(u, "sun");
		assertEquals("Light", ((NameTypeSpec) sun.type().spec()).name());

		FunctionDefinition stronger = (FunctionDefinition) u.decls().stream()
				.filter(d -> d instanceof FunctionDefinition && ((FunctionDefinition) d).proto().name()
						.equals("stronger")).findFirst().orElseThrow();
		Param b = stronger.proto().params().get(1);
		assertEquals(StorageKind.INOUT, ((StorageQual) b.type().qualifiers().getFirst()).kind());
		assertEquals("Light", ((NameTypeSpec) b.type().spec()).name());
	}

	@Test
	void interfaceBlocks430InAndOutForms() {
		Unit u = source("interfaceBlocks430");
		assertEquals(430, u.version().number());
		InterfaceBlock matrices = (InterfaceBlock) u.decls().get(0);
		assertEquals(StorageKind.UNIFORM, ((StorageQual) matrices.qualifiers().get(1)).kind());
		assertNull(matrices.instance());
		InterfaceBlock out = (InterfaceBlock) u.decls().get(1);
		assertEquals(StorageKind.OUT, ((StorageQual) out.qualifiers().getFirst()).kind());
		assertEquals("Vertex", out.name());
		assertEquals("vertexOut", out.instance());
		assertEquals("flat", ((dev.mcms.glsl.ast.InterpQual) out.members().getFirst().type().qualifiers().getFirst())
				.kind().word());
	}

	@Test
	void sourceWithoutVersionParsesAs110() {
		Unit u = source("noVersion110");
		assertNull(u.version());
		VarDecl timer = var(u, "timer");
		assertEquals("float", ((BuiltinTypeSpec) timer.type().spec()).name());
		List<Stmt> body = main(u).body().statements();
		FieldExpr y = (FieldExpr) ((AssignExpr) ((ExprStmt) body.get(1)).expr()).target();
		assertEquals("y", y.name());
		assertEquals("gl_Position", ((Ident) y.base()).name());
	}

	@Test
	void everyTestSourceParses() {
		for (TestShaders.Source s : TestShaders.ALL) {
			Unit u = Parser.parse(s.text());
			assertTrue(u.decls().size() > 0, s.name() + " should have declarations");
		}
	}
}
