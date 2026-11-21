// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.metal.mixin;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.io.InputStream;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * Mixin asks {@link McmsMetalMixinPlugin} whether a mixin applies while it prepares the configs, before any game or
 * Sodium class may load (E4-B review item 1: loading the renderpearl {@code VertexFormat} there fails the game at
 * start). This test reads the plugin's bytecode and the ADR 0007 gate class it calls, and checks that they reference no
 * game, Sodium, renderpearl or LWJGL class and none of the engine's terrain runtime classes, so the failure cannot come
 * back through a new call.
 */
class PluginIsolationTest {

	private static final String[] FORBIDDEN_PREFIXES = { "net/minecraft/", "net/caffeinemc/", "com/mojang/", "org/lwjgl/" };
	private static final Set<String> FORBIDDEN_ENGINE_CLASSES = Set.of(
		"dev/mcms/shaders/runtime/terrain/TerrainEngine",
		"dev/mcms/shaders/runtime/terrain/TerrainUniforms",
		"dev/mcms/shaders/runtime/terrain/TerrainVertexType",
		"dev/mcms/shaders/runtime/gbuffers/VertexLayoutRegistry");

	@Test
	void thePluginReferencesNoGameSodiumOrRegistrationClass() throws IOException {
		Set<String> referenced = referencedClasses("dev/mcms/metal/mixin/McmsMetalMixinPlugin.class");
		assertFalse(referenced.isEmpty(), "the plugin's references were not read");
		for (String name : referenced) {
			for (String prefix : FORBIDDEN_PREFIXES) {
				assertFalse(name.startsWith(prefix), "the plugin must not touch " + name + " while configs prepare");
			}
			assertFalse(FORBIDDEN_ENGINE_CLASSES.contains(name), "the plugin must not load " + name + " while configs prepare");
		}
	}

	@Test
	void theGateItCallsReferencesNoSodiumOrGameClass() throws IOException {
		Set<String> referenced = referencedClasses("dev/mcms/shaders/runtime/terrain/SodiumAdapter.class");
		assertFalse(referenced.isEmpty(), "the gate's references were not read");
		for (String name : referenced) {
			for (String prefix : FORBIDDEN_PREFIXES) {
				assertFalse(name.startsWith(prefix), "the gate must not load " + name);
			}
		}
	}

	/** The internal names of every class the given class's bytecode names (owners, types, class constants). */
	private static Set<String> referencedClasses(String resource) throws IOException {
		Set<String> names = new TreeSet<>();
		try (InputStream in = PluginIsolationTest.class.getClassLoader().getResourceAsStream(resource)) {
			if (in == null) {
				return names;
			}
			new ClassReader(in.readAllBytes()).accept(new ClassVisitor(Opcodes.ASM9) {
				@Override
				public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
						String[] exceptions) {
					return new MethodVisitor(Opcodes.ASM9) {
						@Override
						public void visitMethodInsn(int opcode, String owner, String methodName, String methodDescriptor,
								boolean isInterface) {
							names.add(owner);
						}

						@Override
						public void visitFieldInsn(int opcode, String owner, String fieldName, String fieldDescriptor) {
							names.add(owner);
						}

						@Override
						public void visitTypeInsn(int opcode, String type) {
							names.add(type);
						}

						@Override
						public void visitLdcInsn(Object value) {
							if (value instanceof Type type && type.getSort() == Type.OBJECT) {
								names.add(type.getInternalName());
							}
						}
					};
				}
			}, 0);
		}
		return names;
	}
}
