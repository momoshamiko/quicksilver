// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.metal.mixin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Checks the mixins of {@code dev.mcms.metal.mixin} without starting the game. The start smoke of the merged E4 branches
 * (2026-11-14) failed at apply time: three handlers named fewer parameters than their target, and Mixin refused them
 * ({@code Invalid descriptor on @Inject}). This test reads each mixin and its target as bytecode, from the test classpath
 * (Minecraft 26.3, Sodium 0.9.3-beta.1 and beta.5, Distant Horizons, and the mod's own classes), and applies Mixin's rules:
 * <ul>
 * <li>the config lists every mixin class of the package, or the class is in {@link #UNREGISTERED} with a reason;</li>
 * <li>each method string of {@code @Inject}, {@code @Redirect}, {@code @ModifyArg}, {@code @ModifyReturnValue},
 * {@code @ModifyVariable}, {@code @ModifyConstant} and {@code @WrapOperation} names exactly one method of the target;</li>
 * <li>an {@code @Inject} handler takes all of the target's arguments, in order, or none; then {@code CallbackInfo} for a
 * void target or {@code CallbackInfoReturnable} for any other; captured locals follow only with {@code locals} or as
 * MixinExtras' {@code @Local} parameters;</li>
 * <li>{@code @Redirect} and {@code @WrapOperation} take the receiver (instance calls) and the call's arguments, and
 * return the call's type; {@code @ModifyArg} takes and returns one call argument; {@code @ModifyReturnValue} takes the
 * target's return value, then none or all of the target's arguments, and returns its type; {@code @ModifyVariable} (only
 * with {@code argsOnly}) takes and returns the type of the target argument it selects, then none or all of the target's
 * arguments; {@code @ModifyConstant} takes and returns each constant's type, and every constant occurs in the target;</li>
 * <li>{@code @Accessor} and {@code @Invoker} names resolve to a field or method of the target with the matching type.</li>
 * </ul>
 * {@code @Coerce} relaxes the type of its parameter. An injector this audit does not check fails the test, so a new kind
 * of handler needs its rule here first. Run from the mod folder ({@code mod/mcms-metal}); the Gradle test task does.
 */
final class MixinAuditTest {

	private static final String PACKAGE = "dev/mcms/metal/mixin";
	private static final String PACKAGE_NAME = "dev.mcms.metal.mixin";
	private static final Path SOURCES = Path.of("src/main/java", PACKAGE);
	private static final String CONFIG = "mcms-metal.mixins.json";

	/** Mixin classes of the package that the config does not list, each with its reason. Empty: every class is registered. */
	private static final Map<String, String> UNREGISTERED = Map.of();

	private static final String MIXIN = "Lorg/spongepowered/asm/mixin/Mixin;";
	private static final String INJECT = "Lorg/spongepowered/asm/mixin/injection/Inject;";
	private static final String REDIRECT = "Lorg/spongepowered/asm/mixin/injection/Redirect;";
	private static final String MODIFY_ARG = "Lorg/spongepowered/asm/mixin/injection/ModifyArg;";
	private static final String WRAP_OPERATION = "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;";
	private static final String MODIFY_RETURN = "Lcom/llamalad7/mixinextras/injector/ModifyReturnValue;";
	private static final String MODIFY_VARIABLE = "Lorg/spongepowered/asm/mixin/injection/ModifyVariable;";
	private static final String MODIFY_CONSTANT = "Lorg/spongepowered/asm/mixin/injection/ModifyConstant;";
	private static final String LOCAL = "Lcom/llamalad7/mixinextras/sugar/Local;";
	private static final String ACCESSOR = "Lorg/spongepowered/asm/mixin/gen/Accessor;";
	private static final String INVOKER = "Lorg/spongepowered/asm/mixin/gen/Invoker;";
	private static final String COERCE = "Lorg/spongepowered/asm/mixin/injection/Coerce;";
	private static final String OVERWRITE = "Lorg/spongepowered/asm/mixin/Overwrite;";
	private static final String MIXIN_PREFIX = "Lorg/spongepowered/asm/mixin/";
	private static final String MIXINEXTRAS_PREFIX = "Lcom/llamalad7/mixinextras/";
	private static final String INJECTION_PREFIX = "Lorg/spongepowered/asm/mixin/injection/";
	private static final String GEN_PREFIX = "Lorg/spongepowered/asm/mixin/gen/";
	private static final String MIXINEXTRAS_INJECTOR_PREFIX = "Lcom/llamalad7/mixinextras/injector/";

	private static final Type CALLBACK_INFO = Type.getType("Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;");
	private static final Type CALLBACK_INFO_RETURNABLE =
		Type.getType("Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;");
	private static final Type OPERATION = Type.getType("Lcom/llamalad7/mixinextras/injector/wrapoperation/Operation;");

	/** {@code Owner;name(desc)ret} as an {@code @At(value = "INVOKE")} target names it. */
	private static final Pattern CALL = Pattern.compile("L([^;]+);([^(]+)(\\(.*)");

	@Test
	void everyMixinClassOfThePackageIsRegistered() throws IOException {
		Classes classes = new Classes(MixinAuditTest.class.getClassLoader());
		assertNoProblems(registrationProblems(mixinClasses(classes), registered(), UNREGISTERED));
	}

	@Test
	void aMixinClassMissingFromTheConfigIsReported() throws IOException {
		// Fixture: the config as it stood before EngineSubmitBlockEntityDispatcherMixin was registered (commit 37590f3).
		Classes classes = new Classes(MixinAuditTest.class.getClassLoader());
		Set<String> config = registered();
		config.remove("EngineSubmitBlockEntityDispatcherMixin");
		assertEquals(
			List.of("EngineSubmitBlockEntityDispatcherMixin: is a mixin of " + PACKAGE_NAME + " but is not in " + CONFIG
				+ "; register it, or list it in UNREGISTERED with its reason"),
			registrationProblems(mixinClasses(classes), config, UNREGISTERED));
	}

	@Test
	void everyClassOfThePackageIsAMixin() throws IOException {
		Classes classes = new Classes(MixinAuditTest.class.getClassLoader());
		Set<String> names = sourceClasses();
		String plugin = configPlugin();
		if (plugin != null) {
			names.remove(plugin);  // Mixin loads its config plugin itself, before it guards the package
		}
		assertNoProblems(nonMixinProblems(classes, names));
	}

	@Test
	void aClassThatIsNotAMixinIsReported() throws IOException {
		// Fixture: this test class is in the package and is not a mixin. Before 2026-11-15, SodiumEngineInitialBuildPort was such
		// a class in the main sources: Mixin refused to load it in the game (IllegalClassLoadError), and the build stayed green.
		Classes classes = new Classes(MixinAuditTest.class.getClassLoader());
		assertEquals(
			List.of("MixinAuditTest: is in " + PACKAGE_NAME + " but is not a mixin; Mixin refuses to load any other class of a"
				+ " mixin package (IllegalClassLoadError in the game), so move it to another package"),
			nonMixinProblems(classes, Set.of("MixinAuditTest", "SodiumEngineShadowTerrainMixin")));
	}

	@Test
	void everyRegisteredMixinFollowsTheInjectorRules() throws IOException {
		Classes classes = new Classes(MixinAuditTest.class.getClassLoader());
		List<String> problems = new ArrayList<>();
		for (String name : registered()) {
			problems.addAll(auditMixin(classes, name));
		}
		assertNoProblems(problems);
	}

	@Test
	void theSodiumMixinsFollowTheRulesAgainstSodiumBeta5() throws IOException {
		auditSodiumPass("mcms.audit.sodiumBeta5", "beta.5");
	}

	@Test
	void theSodiumMixinsFollowTheRulesAgainstSodiumBeta1() throws IOException {
		auditSodiumPass("mcms.audit.sodiumBeta1", "beta.1");
	}

	private void auditSodiumPass(String prop, String label) throws IOException {
		// Each secondary pass has its own loader: the target Sodium jar, Minecraft and the mod's classes.
		String paths = System.getProperty(prop);
		if (paths == null) {
			throw new IllegalStateException("the " + label + " classpath comes from build.gradle: run the audit with Gradle");
		}
		try (URLClassLoader loader = new URLClassLoader(urls(paths), ClassLoader.getPlatformClassLoader())) {
			Classes classes = new Classes(loader);
			List<String> problems = new ArrayList<>();
			for (String name : registered()) {
				if (name.startsWith("Sodium")) {
					problems.addAll(auditMixin(classes, name));
				}
			}
			assertNoProblems(problems);
		}
	}

	@Test
	void noInvokerCallsAMethodOurMixinsInjectInto() throws IOException {
		// The recursion class of bug the bytecode rules cannot see: a mixin @Invoker calls the transformed method, so
		// every injector on that method's body (an @Inject, a @WrapOperation, ...) runs again on each call and the
		// stack overflows when the handler routes back (EngineBlockEntitySubmitMixin's HEAD cancel plus the
		// EngineBlockEntityLevelRendererAccessor invoker, the 2026-11-14 preview 8 StackOverflowError). The wrap fixes
		// it at the call site; this guard keeps the pair from coming back.
		Classes classes = new Classes(MixinAuditTest.class.getClassLoader());
		List<InjectedBody> bodies = new ArrayList<>();
		List<InvokedMethod> invoked = new ArrayList<>();
		for (String name : registered()) {
			collectInvocationsAndBodies(classes, name, bodies, invoked);
		}
		assertNoProblems(invokerRecursionProblems(bodies, invoked));
	}

	@Test
	void anInvokerOfAMethodOurMixinsInjectIntoIsReported() {
		// Fixture: the preview 8 pair — the HEAD inject into LevelRenderer.submitBlockEntities and an @Invoker of the
		// same method (name and descriptor resolved as the audit resolves them).
		String desc = "(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/state/level/LevelRenderState;"
			+ "Lnet/minecraft/client/renderer/SubmitNodeCollector;)V";
		List<String> problems = invokerRecursionProblems(
			List.of(new InjectedBody("net/minecraft/client/renderer/LevelRenderer", "submitBlockEntities", desc)),
			List.of(new InvokedMethod("net/minecraft/client/renderer/LevelRenderer", "submitBlockEntities", desc)));
		assertTrue(problems.size() == 1 && problems.get(0).contains("submitBlockEntities")
			&& problems.get(0).contains("@Invoker"), problems::toString);
	}

	@Test
	void aCallbackHandlerThatNamesOnlySomeTargetArgumentsIsRefused() {
		// A target of four arguments (PoseStack, int, float, float)V, with a handler naming two of them.
		List<String> problems = injectRules("(Lcom/mojang/blaze3d/vertex/PoseStack;ILorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V",
			"(Lcom/mojang/blaze3d/vertex/PoseStack;IFF)V", false);
		assertTrue(problems.size() == 1 && problems.get(0).contains("but Mixin needs"), problems::toString);
	}

	@Test
	void aCallbackHandlerTakesAllTheTargetArgumentsOrNone() {
		assertTrue(injectRules("(IFFLorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V", "(IFF)V", false).isEmpty());
		assertTrue(injectRules("(Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V", "(IFF)V", false).isEmpty());
	}

	@Test
	void aModifyReturnValueHandlerTakesTheReturnValueAndReturnsItsType() {
		Type quads = Type.getType("[Ljava/lang/String;");
		Type[] none = new Type[0];
		assertTrue(modifyReturnProblems(new Type[] {quads}, new boolean[1], none, quads, quads).isEmpty());
		assertTrue(modifyReturnProblems(new Type[] {quads, Type.INT_TYPE}, new boolean[2], new Type[] {Type.INT_TYPE}, quads, quads)
			.isEmpty());
		assertEquals(1, modifyReturnProblems(new Type[] {Type.INT_TYPE}, new boolean[1], none, quads, quads).size());
		assertEquals(1, modifyReturnProblems(new Type[] {quads}, new boolean[1], none, quads, Type.INT_TYPE).size());
		assertEquals(1, modifyReturnProblems(new Type[] {quads, Type.LONG_TYPE}, new boolean[2], new Type[] {Type.INT_TYPE}, quads,
			quads).size());
	}

	@Test
	void aNonVoidTargetNeedsCallbackInfoReturnable() {
		assertTrue(injectRules("(Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V", "()I", false).size() == 1);
		assertTrue(injectRules("(Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;)V", "()I", false)
			.isEmpty());
	}

	@Test
	void capturedLocalsNeedLocalCapture() {
		String handler = "(IFFLorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;I)V";
		assertTrue(injectRules(handler, "(IFF)V", false).size() == 1);
		assertTrue(injectRules(handler, "(IFF)V", true).isEmpty());
	}

	@Test
	void coerceRelaxesOneParameterType() {
		Type[] handler = Type.getArgumentTypes("(Ljava/lang/Object;Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V");
		Type[] target = Type.getArgumentTypes("(I)V");
		assertTrue(callbackProblems(handler, new boolean[] {false, false}, target, Type.VOID_TYPE, false).size() == 1);
		assertTrue(callbackProblems(handler, new boolean[] {true, false}, target, Type.VOID_TYPE, false).isEmpty());
	}

	@Test
	void aRedirectOrWrapOperationTakesTheReceiverAndTheArguments() {
		Type[] withReceiver = Type.getArgumentTypes("(Ljava/lang/String;Ljava/lang/String;)Z");
		Type[] receiverOnly = Type.getArgumentTypes("(Ljava/lang/String;)Z");
		boolean[] none = new boolean[2];
		Type[] expected = Type.getArgumentTypes("(Ljava/lang/String;Ljava/lang/String;)Z");
		assertTrue(invocationProblems(withReceiver, none, expected, Type.BOOLEAN_TYPE, Type.BOOLEAN_TYPE).isEmpty());
		assertTrue(invocationProblems(receiverOnly, new boolean[1], expected, Type.BOOLEAN_TYPE, Type.BOOLEAN_TYPE).size() == 1);
	}

	@Test
	void aModifyConstantWhoseConstantTheTargetNoLongerLoadsIsReported() {
		// Fixture: a body loading 0.9f and 0.3f, as DH 3.3.4's getNearClipPlaneInBlocks does among others. A release that
		// drops one of DistantHorizonsNearClipMixin's four constants would not stop the mixin in the game (require 1 counts
		// all four): the other three would apply and the dropped one would pass unmodified, so this audit is the only guard.
		MethodNode body = new MethodNode(Opcodes.ACC_STATIC, "nearClip", "()F", null, null);
		body.instructions.add(new LdcInsnNode(0.9f));
		body.instructions.add(new LdcInsnNode(0.3f));
		body.instructions.add(new InsnNode(Opcodes.FRETURN));
		Type[] takesFloat = { Type.FLOAT_TYPE };
		assertTrue(modifyConstantProblems(takesFloat, Type.FLOAT_TYPE, List.of(0.9f, 0.3f), body).isEmpty());
		assertEquals(List.of("nearClip()F loads no constant 0.8"),
			modifyConstantProblems(takesFloat, Type.FLOAT_TYPE, List.of(0.8f), body));
		assertEquals(1, modifyConstantProblems(new Type[] { Type.INT_TYPE }, Type.INT_TYPE, List.of(0.9f), body).size());
	}

	private static List<String> injectRules(String handlerDesc, String targetDesc, boolean capturesLocals) {
		Type[] handler = Type.getArgumentTypes(handlerDesc);
		return callbackProblems(handler, new boolean[handler.length], Type.getArgumentTypes(targetDesc),
			Type.getReturnType(targetDesc), capturesLocals);
	}

	// ---------------------------------------------------------------- rules (pure, on descriptors)

	/**
	 * Mixin's rule for {@code @Inject}: the handler takes all of the target's arguments in order, or none; then the
	 * callback ({@code CallbackInfo} for a void target, {@code CallbackInfoReturnable} otherwise). Parameters after the
	 * callback are captured locals, which need {@code locals} on the annotation.
	 */
	static List<String> callbackProblems(Type[] handler, boolean[] coerced, Type[] target, Type targetReturn,
			boolean capturesLocals) {
		Type callback = targetReturn.equals(Type.VOID_TYPE) ? CALLBACK_INFO : CALLBACK_INFO_RETURNABLE;
		boolean all = handler.length >= target.length;
		for (int i = 0; all && i < target.length; i++) {
			all = sameType(handler[i], coerced[i], target[i]);
		}
		int at = all ? target.length : 0;
		if (at >= handler.length || !handler[at].equals(callback)) {
			String needed = target.length == 0 ? show(new Type[] {callback})
				: show(concat(target, callback)) + " or " + show(new Type[] {callback});
			return List.of("takes " + show(handler) + ", but Mixin needs " + needed);
		}
		int extra = handler.length - at - 1;
		if (extra > 0 && !capturesLocals) {
			return List.of("takes " + extra + " parameter(s) after its callback, which are captured locals: set locals = LocalCapture on the annotation");
		}
		return List.of();
	}

	/**
	 * Mixin's rule for {@code @Redirect} and {@code @WrapOperation}: the handler takes {@code expected} (the receiver of an
	 * instance call, then the call's arguments; {@code WrapOperation} adds its {@code Operation}) and returns the call's type.
	 */
	static List<String> invocationProblems(Type[] handler, boolean[] coerced, Type[] expected, Type handlerReturn,
			Type callReturn) {
		List<String> out = new ArrayList<>();
		boolean fits = handler.length == expected.length;
		for (int i = 0; fits && i < expected.length; i++) {
			fits = sameType(handler[i], coerced[i], expected[i]);
		}
		if (!fits) {
			out.add("takes " + show(handler) + ", but the call needs " + show(expected));
		}
		if (!handlerReturn.equals(callReturn)) {
			out.add("returns " + simple(handlerReturn) + ", but the call returns " + simple(callReturn));
		}
		return out;
	}

	/** Mixin's rule for {@code @ModifyArg}: one parameter of the argument's type, returning that type. */
	static List<String> modifyArgProblems(Type[] handler, boolean[] coerced, Type[] callArgs, int index, Type handlerReturn) {
		if (index < 0 || index >= callArgs.length) {
			return List.of("names index " + index + " of a call with " + callArgs.length + " argument(s); an explicit index is needed");
		}
		Type arg = callArgs[index];
		boolean fits = handler.length == 1 && sameType(handler[0], coerced[0], arg) && handlerReturn.equals(arg);
		return fits ? List.of()
			: List.of("takes " + show(handler) + " and returns " + simple(handlerReturn) + ", but argument " + index + " is " + simple(arg));
	}

	/**
	 * Mixin's rule for {@code @ModifyReturnValue}: the handler takes the target's return value first, then none of the
	 * target's arguments or all of them in order, and returns the return value's type.
	 */
	static List<String> modifyReturnProblems(Type[] handler, boolean[] coerced, Type[] target, Type targetReturn, Type handlerReturn) {
		List<String> out = new ArrayList<>();
		boolean fits = handler.length >= 1 && sameType(handler[0], coerced[0], targetReturn);
		boolean argsFit = handler.length == 1 || handler.length == target.length + 1;
		for (int i = 0; fits && argsFit && handler.length > 1 && i < target.length; i++) {
			argsFit = sameType(handler[i + 1], coerced[i + 1], target[i]);
		}
		if (!fits || !argsFit) {
			out.add("takes " + show(handler) + ", but Mixin needs the return value " + simple(targetReturn)
				+ " first, then none or all of the target's arguments " + show(target));
		}
		if (!handlerReturn.equals(targetReturn)) {
			out.add("returns " + simple(handlerReturn) + ", but the target returns " + simple(targetReturn));
		}
		return out;
	}

	/**
	 * Mixin's rule for {@code @ModifyConstant} on one target body: the handler takes each constant's type first and returns
	 * it, then none of the target's arguments or all of them in order; and every constant occurs in the body, so a release
	 * that changes one is caught here instead of failing to apply in the game.
	 */
	static List<String> modifyConstantProblems(Type[] handler, Type handlerReturn, List<Object> constants, MethodNode body) {
		List<String> out = new ArrayList<>();
		Type[] target = argTypes(body);
		for (Object constant : constants) {
			Type type = constant instanceof Float ? Type.FLOAT_TYPE : constant instanceof Integer ? Type.INT_TYPE
				: constant instanceof Long ? Type.LONG_TYPE : constant instanceof Double ? Type.DOUBLE_TYPE
				: Type.getType(String.class);
			if (handler.length == 0 || !handler[0].equals(type) || !handlerReturn.equals(type)) {
				out.add("takes " + show(handler) + " and returns " + simple(handlerReturn) + ", but the constant " + constant
					+ " is " + simple(type));
			}
			if (!loads(body, constant)) {
				out.add(body.name + body.desc + " loads no constant " + constant);
			}
		}
		Type[] rest = handler.length == 0 ? handler : Arrays.copyOfRange(handler, 1, handler.length);
		if (rest.length != 0 && !Arrays.equals(rest, target)) {
			out.add("the parameters after the constant must be none or all of the target's arguments " + show(target));
		}
		return out;
	}

	/** Whether the body loads the constant: an {@code LDC} of it, or the short form javac emits for small values. */
	private static boolean loads(MethodNode body, Object constant) {
		for (AbstractInsnNode insn : body.instructions) {
			int op = insn.getOpcode();
			boolean found = insn instanceof LdcInsnNode ldc ? constant.equals(ldc.cst)
				: insn instanceof IntInsnNode push && (op == Opcodes.BIPUSH || op == Opcodes.SIPUSH) ? constant.equals(push.operand)
				: constant instanceof Float f ? op >= Opcodes.FCONST_0 && op <= Opcodes.FCONST_2 && f == op - Opcodes.FCONST_0
				: constant instanceof Integer i ? op >= Opcodes.ICONST_M1 && op <= Opcodes.ICONST_5 && i == op - Opcodes.ICONST_0
				: constant instanceof Long l ? op >= Opcodes.LCONST_0 && op <= Opcodes.LCONST_1 && l == op - Opcodes.LCONST_0
				: constant instanceof Double d && op >= Opcodes.DCONST_0 && op <= Opcodes.DCONST_1 && d == op - Opcodes.DCONST_0;
			if (found) {
				return true;
			}
		}
		return false;
	}

	private static boolean sameType(Type handler, boolean coerced, Type target) {
		return coerced || handler.equals(target);
	}

	// ---------------------------------------------------------------- the package and its config

	/** Every class of the package's main sources: one per {@code .java} file, {@code package-info} excepted. */
	static Set<String> sourceClasses() throws IOException {
		if (!Files.isDirectory(SOURCES)) {
			throw new IllegalStateException("run the audit from the mod folder: " + SOURCES + " is not here");
		}
		Set<String> out = new TreeSet<>();
		try (Stream<Path> files = Files.list(SOURCES)) {
			files.map(p -> p.getFileName().toString())
				.filter(n -> n.endsWith(".java"))
				.map(n -> n.substring(0, n.length() - ".java".length()))
				.filter(n -> !n.equals("package-info"))
				.forEach(out::add);
		}
		return out;
	}

	/** Every {@code .java} file of the package that Mixin reads as a mixin (annotated {@code @Mixin} in the class file). */
	static Set<String> mixinClasses(Classes classes) throws IOException {
		Set<String> out = new TreeSet<>();
		for (String name : sourceClasses()) {
			if (isMixin(classes, name)) {
				out.add(name);
			}
		}
		return out;
	}

	/**
	 * The classes among {@code names} that are not mixins. Mixin refuses to load any class of a package its config declares
	 * that is not one of its mixins ({@code IllegalClassLoadError}), so such a class compiles and passes every unit test, then
	 * fails in the game the first time it is referenced.
	 */
	static List<String> nonMixinProblems(Classes classes, Set<String> names) {
		List<String> out = new ArrayList<>();
		for (String name : new TreeSet<>(names)) {
			if (!isMixin(classes, name)) {
				out.add(name + ": is in " + PACKAGE_NAME + " but is not a mixin; Mixin refuses to load any other class of a"
					+ " mixin package (IllegalClassLoadError in the game), so move it to another package");
			}
		}
		return out;
	}

	// ---------------------------------------------------------------- invoker recursion guard

	/** One method body our mixins transform: an injector's host method (target class, name, descriptor). */
	record InjectedBody(String targetClass, String name, String desc) {
	}

	/** One method an {@code @Invoker} of ours calls (target class, name, descriptor). */
	record InvokedMethod(String targetClass, String name, String desc) {
	}

	/**
	 * Fails for every method that is both an injector's host and an {@code @Invoker}'s call: the invoker invokes the
	 * transformed method, so the injection re-runs on every call — an unbounded recursion when the handler's work
	 * leads back to the call (the preview 8 {@code StackOverflowError}), double work otherwise.
	 */
	static List<String> invokerRecursionProblems(List<InjectedBody> bodies, List<InvokedMethod> invoked) {
		List<String> out = new ArrayList<>();
		for (InvokedMethod call : invoked) {
			for (InjectedBody body : bodies) {
				if (body.targetClass().equals(call.targetClass()) && body.name().equals(call.name())
						&& body.desc().equals(call.desc())) {
					out.add(call.targetClass().replace('/', '.') + "#" + call.name() + call.desc()
						+ ": a mixin injects into this method and an @Invoker calls it; the invoker runs the"
						+ " transformed method, so the injection re-runs on every call — wrap the call site instead");
				}
			}
		}
		return out;
	}

	/** Collects every injector's host body and every {@code @Invoker}'s called method of one registered mixin. */
	private static void collectInvocationsAndBodies(Classes classes, String simpleName, List<InjectedBody> bodies,
			List<InvokedMethod> invoked) {
		ClassNode mixin = classes.get(PACKAGE + "/" + simpleName);
		if (mixin == null) {
			return;
		}
		AnnotationNode mixinAnnotation = find(all(mixin.visibleAnnotations, mixin.invisibleAnnotations), MIXIN);
		if (mixinAnnotation == null) {
			return;
		}
		List<String> targets = targets(mixinAnnotation);
		if (targets.size() != 1) {
			return;  // auditMixin reports it
		}
		ClassNode target = classes.get(targets.get(0));
		if (target == null) {
			return;  // auditMixin reports it
		}
		for (MethodNode handler : mixin.methods) {
			for (AnnotationNode a : annotations(handler)) {
				if (INVOKER.equals(a.desc)) {
					List<String> named = strings(value(a, "value"));
					String name = named.isEmpty() || named.get(0).isEmpty()
						? derived(handler.name, "invoke", "call") : named.get(0);
					if (name != null) {
						invoked.add(new InvokedMethod(target.name, name, handler.desc));
					}
				} else if (List.of(INJECT, REDIRECT, WRAP_OPERATION, MODIFY_ARG, MODIFY_RETURN, MODIFY_VARIABLE, MODIFY_CONSTANT,
						OVERWRITE).contains(a.desc)) {
					// The host list's own problems (a method string matching none or several) belong to the
					// injector-rules test; here a resolved host is all the guard needs.
					for (MethodNode host : hosts(simpleName + "#" + handler.name, a, target, new ArrayList<>())) {
						bodies.add(new InjectedBody(target.name, host.name, host.desc));
					}
				}
			}
		}
	}

	/** The mixin classes the config lists under {@code mixins}, {@code client} and {@code server}. */
	static Set<String> registered() throws IOException {
		JsonObject root = config();
		Set<String> out = new TreeSet<>();
		for (String key : List.of("mixins", "client", "server")) {
			if (root.has(key)) {
				for (JsonElement e : root.getAsJsonArray(key)) {
					out.add(e.getAsString());
				}
			}
		}
		return out;
	}

	/** The simple name of the config's {@code plugin} when it is in the package, else null. Mixin loads it itself. */
	static String configPlugin() throws IOException {
		JsonObject root = config();
		if (!root.has("plugin")) {
			return null;
		}
		String plugin = root.get("plugin").getAsString();
		return plugin.startsWith(PACKAGE_NAME + ".") ? plugin.substring(PACKAGE_NAME.length() + 1) : null;
	}

	private static JsonObject config() throws IOException {
		try (InputStream in = MixinAuditTest.class.getClassLoader().getResourceAsStream(CONFIG)) {
			if (in == null) {
				throw new IllegalStateException(CONFIG + " is not on the test classpath");
			}
			return JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
		}
	}

	static List<String> registrationProblems(Set<String> mixins, Set<String> registered, Map<String, String> unregistered) {
		List<String> out = new ArrayList<>();
		for (String m : mixins) {
			if (!registered.contains(m) && !unregistered.containsKey(m)) {
				out.add(m + ": is a mixin of " + PACKAGE_NAME + " but is not in " + CONFIG
					+ "; register it, or list it in UNREGISTERED with its reason");
			}
		}
		for (String r : registered) {
			if (!mixins.contains(r)) {
				out.add(r + ": is in " + CONFIG + " but is not a mixin class of " + PACKAGE_NAME);
			}
		}
		for (String u : unregistered.keySet()) {
			if (registered.contains(u)) {
				out.add(u + ": is listed in UNREGISTERED and also in " + CONFIG);
			}
		}
		return out;
	}

	private static boolean isMixin(Classes classes, String simpleName) {
		ClassNode node = classes.get(PACKAGE + "/" + simpleName);
		return node != null && find(all(node.visibleAnnotations, node.invisibleAnnotations), MIXIN) != null;
	}

	// ---------------------------------------------------------------- one mixin against its target

	/** The problems of one registered mixin, each as {@code Class#method: why}. Empty when it follows the rules. */
	static List<String> auditMixin(Classes classes, String simpleName) {
		ClassNode mixin = classes.get(PACKAGE + "/" + simpleName);
		if (mixin == null) {
			return List.of(simpleName + ": the class file is missing");
		}
		AnnotationNode mixinAnnotation = find(all(mixin.visibleAnnotations, mixin.invisibleAnnotations), MIXIN);
		if (mixinAnnotation == null) {
			return List.of(simpleName + ": is not annotated @Mixin");
		}
		List<String> targets = targets(mixinAnnotation);
		if (targets.size() != 1) {
			return List.of(simpleName + ": has " + targets.size() + " targets; the audit checks one");
		}
		ClassNode target = classes.get(targets.get(0));
		if (target == null) {
			return List.of(simpleName + ": target " + targets.get(0).replace('/', '.') + " is not on the test classpath");
		}
		List<String> out = new ArrayList<>();
		for (MethodNode handler : mixin.methods) {
			String where = simpleName + "#" + handler.name;
			for (AnnotationNode a : annotations(handler)) {
				switch (a.desc) {
					case INJECT -> checkInject(where, handler, a, target, out);
					case REDIRECT -> checkRedirect(where, handler, a, target, classes, out);
					case MODIFY_ARG -> checkModifyArg(where, handler, a, target, classes, out);
					case WRAP_OPERATION -> checkWrapOperation(where, handler, a, target, classes, out);
					case MODIFY_RETURN -> checkModifyReturn(where, handler, a, target, out);
					case MODIFY_VARIABLE -> checkModifyVariable(where, handler, a, target, out);
					case MODIFY_CONSTANT -> checkModifyConstant(where, handler, a, target, classes, out);
					case ACCESSOR -> checkAccessor(where, handler, a, target, out);
					case INVOKER -> checkInvoker(where, handler, a, target, out);
					default -> {
						if (isInjector(a.desc)) {
							out.add(where + ": " + a.desc + " is not checked by MixinAuditTest; add its rule");
						}
					}
				}
			}
			int params = Type.getArgumentTypes(handler.desc).length;
			for (int i = 0; i < params; i++) {
				for (AnnotationNode p : paramAnnotations(handler, i)) {
					// @Local parameters of an @Inject handler are checked by checkInject (their position); any other is unchecked.
					boolean sugaredInject = LOCAL.equals(p.desc) && find(annotations(handler), INJECT) != null;
					if (!COERCE.equals(p.desc) && !sugaredInject
							&& (p.desc.startsWith(MIXIN_PREFIX) || p.desc.startsWith(MIXINEXTRAS_PREFIX))) {
						out.add(where + ": parameter " + i + " has " + p.desc + ", which is not checked by MixinAuditTest");
					}
				}
			}
		}
		return out;
	}

	private static void checkInject(String where, MethodNode handler, AnnotationNode inject, ClassNode target, List<String> out) {
		if (!Type.getReturnType(handler.desc).equals(Type.VOID_TYPE)) {
			out.add(where + ": an @Inject handler must return void");
		}
		for (MethodNode host : hosts(where, inject, target, out)) {
			Type targetReturn = Type.getReturnType(host.desc);
			boolean captures = capturesLocals(inject) || localsAfterCallback(handler, targetReturn);
			add(out, where, callbackProblems(argTypes(handler), coerced(handler), argTypes(host), targetReturn, captures));
		}
	}

	/**
	 * Whether the handler's parameters after its callback are all MixinExtras' {@code @Local} (captured locals that need no
	 * {@code locals} attribute). The parameters' types are the game's to check: Mixin reads them when the class applies.
	 */
	private static boolean localsAfterCallback(MethodNode handler, Type targetReturn) {
		Type callback = targetReturn.equals(Type.VOID_TYPE) ? CALLBACK_INFO : CALLBACK_INFO_RETURNABLE;
		Type[] params = Type.getArgumentTypes(handler.desc);
		int at = Arrays.asList(params).indexOf(callback);
		if (at < 0 || at == params.length - 1) {
			return false;
		}
		for (int i = at + 1; i < params.length; i++) {
			if (find(paramAnnotations(handler, i), LOCAL) == null) {
				return false;
			}
		}
		return true;
	}

	private static void checkRedirect(String where, MethodNode handler, AnnotationNode redirect, ClassNode target, Classes classes,
			List<String> out) {
		hosts(where, redirect, target, out);
		Call call = resolveCall(where, at(redirect), classes, out);
		if (call != null) {
			add(out, where, invocationProblems(argTypes(handler), coerced(handler), withReceiver(call),
				Type.getReturnType(handler.desc), call.ret()));
		}
	}

	/** The {@code @At} of an injector: a single annotation ({@code @Redirect}, {@code @ModifyArg}) or a one-element array ({@code @WrapOperation}). */
	private static AnnotationNode at(AnnotationNode a) {
		Object v = value(a, "at");
		if (v instanceof AnnotationNode node) {
			return node;
		}
		return v instanceof List<?> list && list.size() == 1 ? (AnnotationNode) list.get(0) : null;
	}

	private static void checkWrapOperation(String where, MethodNode handler, AnnotationNode wrap, ClassNode target,
			Classes classes, List<String> out) {
		hosts(where, wrap, target, out);
		Call call = resolveCall(where, at(wrap), classes, out);
		if (call != null) {
			Type[] expected = concat(withReceiver(call), OPERATION);
			add(out, where, invocationProblems(argTypes(handler), coerced(handler), expected, Type.getReturnType(handler.desc),
				call.ret()));
		}
	}

	private static void checkModifyArg(String where, MethodNode handler, AnnotationNode modify, ClassNode target, Classes classes,
			List<String> out) {
		hosts(where, modify, target, out);
		Call call = resolveCall(where, at(modify), classes, out);
		if (call != null) {
			Object index = value(modify, "index");
			add(out, where, modifyArgProblems(argTypes(handler), coerced(handler), call.args(),
				index == null ? -1 : (Integer) index, Type.getReturnType(handler.desc)));
		}
	}

	private static void checkModifyReturn(String where, MethodNode handler, AnnotationNode modify, ClassNode target, List<String> out) {
		for (MethodNode host : hosts(where, modify, target, out)) {
			add(out, where, modifyReturnProblems(argTypes(handler), coerced(handler), argTypes(host), Type.getReturnType(host.desc),
				Type.getReturnType(handler.desc)));
		}
	}

	/**
	 * Mixin's rule for {@code @ModifyConstant} in the form the mod uses: each {@code @Constant} names a float, int, long, double
	 * or string value with no other attribute, and the value occurs in the target's body (an {@code LDC}, or the short
	 * float, int, long and double forms); the handler takes that type, then none or all of the target's arguments, and
	 * returns it. Other discriminators are not checked.
	 */
	private static void checkModifyConstant(String where, MethodNode handler, AnnotationNode modify, ClassNode target,
			Classes classes, List<String> out) {
		Object constants = value(modify, "constant");
		if (!(constants instanceof List<?> list) || list.isEmpty()) {
			out.add(where + ": @ModifyConstant is checked only with explicit @Constant values; add its rule");
			return;
		}
		List<Object> values = new ArrayList<>();
		for (Object c : list) {
			AnnotationNode constant = (AnnotationNode) c;
			if (constant.values == null || constant.values.size() != 2 || !List.of("floatValue", "intValue", "longValue",
					"doubleValue", "stringValue").contains(constant.values.get(0))) {
				out.add(where + ": a @Constant is checked only with one float, int, long, double or string value; add its rule");
				return;
			}
			values.add(constant.values.get(1));
		}
		ClassNode bodies = classes.withCode(target.name);
		for (MethodNode host : hosts(where, modify, target, out)) {
			MethodNode body = bodies.methods.stream().filter(m -> m.name.equals(host.name) && m.desc.equals(host.desc))
				.findFirst().orElseThrow();
			add(out, where, modifyConstantProblems(argTypes(handler), Type.getReturnType(handler.desc), values, body));
		}
	}

	/**
	 * Mixin's rule for {@code @ModifyVariable} in the form the mod uses: {@code argsOnly = true} picks among the target's
	 * arguments of the handler's type ({@code ordinal}: the n-th of them; without it exactly one must exist), and the handler
	 * takes that type, then none or all of the target's arguments, and returns it. Other discriminators are not checked.
	 */
	private static void checkModifyVariable(String where, MethodNode handler, AnnotationNode modify, ClassNode target,
			List<String> out) {
		if (!Boolean.TRUE.equals(value(modify, "argsOnly")) || value(modify, "index") != null
				|| value(modify, "name") != null) {
			out.add(where + ": @ModifyVariable is checked only with argsOnly = true and no index or name; add its rule");
			return;
		}
		Type[] params = argTypes(handler);
		Type type = Type.getReturnType(handler.desc);
		if (params.length == 0 || !params[0].equals(type)) {
			out.add(where + ": a @ModifyVariable handler takes the variable's type first and returns it");
			return;
		}
		Object ordinal = value(modify, "ordinal");
		int wanted = ordinal == null ? -1 : (Integer) ordinal;
		for (MethodNode host : hosts(where, modify, target, out)) {
			Type[] hostArgs = argTypes(host);
			long candidates = Arrays.stream(hostArgs).filter(type::equals).count();
			if (wanted < 0 ? candidates != 1 : candidates <= wanted) {
				out.add(where + ": " + host.name + host.desc + " has " + candidates + " arguments of " + type
					+ "; ordinal " + wanted + " selects none or several");
			}
			Type[] rest = Arrays.copyOfRange(params, 1, params.length);
			if (rest.length != 0 && !Arrays.equals(rest, hostArgs)) {
				out.add(where + ": the parameters after the variable must be none or all of " + host.name + "'s arguments");
			}
		}
	}

	private static void checkAccessor(String where, MethodNode handler, AnnotationNode accessor, ClassNode target, List<String> out) {
		List<String> named = strings(value(accessor, "value"));
		String name = named.isEmpty() || named.get(0).isEmpty() ? derived(handler.name, "get", "is", "set") : named.get(0);
		if (name == null) {
			out.add(where + ": names no field, and its name does not start with get, is or set");
			return;
		}
		FieldNode field = null;
		for (FieldNode f : target.fields) {
			if (f.name.equals(name)) {
				field = f;
			}
		}
		if (field == null) {
			out.add(where + ": field '" + name + "' is not declared in " + target.name);
			return;
		}
		Type type = Type.getType(field.desc);
		Type[] args = argTypes(handler);
		Type ret = Type.getReturnType(handler.desc);
		boolean getter = args.length == 0 && ret.equals(type);
		boolean setter = args.length == 1 && args[0].equals(type) && ret.equals(Type.VOID_TYPE);
		if (!getter && !setter) {
			out.add(where + ": an accessor of " + simple(type) + " returns it, or takes it and returns void");
		}
	}

	private static void checkInvoker(String where, MethodNode handler, AnnotationNode invoker, ClassNode target, List<String> out) {
		List<String> named = strings(value(invoker, "value"));
		String name = named.isEmpty() || named.get(0).isEmpty() ? derived(handler.name, "invoke", "call") : named.get(0);
		if (name == null) {
			out.add(where + ": names no method, and its name does not start with invoke or call");
			return;
		}
		for (MethodNode m : target.methods) {
			if (m.name.equals(name) && m.desc.equals(handler.desc)) {
				return;
			}
		}
		out.add(where + ": " + target.name + " has no method " + name + handler.desc);
	}

	/** The target methods a handler's {@code method} strings name; each must name exactly one. */
	private static List<MethodNode> hosts(String where, AnnotationNode a, ClassNode target, List<String> out) {
		List<MethodNode> found = new ArrayList<>();
		List<String> refs = strings(value(a, "method"));
		if (refs.isEmpty()) {
			out.add(where + ": names no method");
		}
		for (String ref : refs) {
			int paren = ref.indexOf('(');
			String name = paren < 0 ? ref : ref.substring(0, paren);
			String desc = paren < 0 ? null : ref.substring(paren);
			List<MethodNode> hits = matches(target, name, desc);
			if (hits.size() == 1) {
				found.add(hits.get(0));
			} else {
				out.add(where + ": method '" + ref + "' matches " + hits.size() + " methods of " + target.name + " (needs one)");
			}
		}
		return found;
	}

	/** Methods named {@code name} with {@code desc} (null: any; {@code (args)} alone: any return type). */
	private static List<MethodNode> matches(ClassNode owner, String name, String desc) {
		List<MethodNode> out = new ArrayList<>();
		for (MethodNode m : owner.methods) {
			if (m.name.equals(name) && (desc == null || (desc.endsWith(")") ? m.desc.startsWith(desc) : m.desc.equals(desc)))) {
				out.add(m);
			}
		}
		return out;
	}

	/** The call an {@code @At(value = "INVOKE", target = "Owner;name(desc)ret")} names, with its receiver type when instance. */
	private static Call resolveCall(String where, AnnotationNode at, Classes classes, List<String> out) {
		if (at == null) {
			out.add(where + ": has no @At");
			return null;
		}
		String kind = String.valueOf(value(at, "value"));
		if (!"INVOKE".equals(kind)) {
			out.add(where + ": @At(" + kind + ") is not checked by MixinAuditTest; add its rule");
			return null;
		}
		List<String> targets = strings(value(at, "target"));
		Matcher m = targets.size() == 1 ? CALL.matcher(targets.get(0)) : null;
		if (m == null || !m.matches()) {
			out.add(where + ": @At target " + targets + " needs an owner, a name and a descriptor");
			return null;
		}
		String owner = m.group(1);
		String name = m.group(2);
		String desc = m.group(3);
		if (name.startsWith("<")) {
			out.add(where + ": constructor calls are not checked by MixinAuditTest");
			return null;
		}
		MethodNode method = findMethod(classes, owner, name, desc);
		if (method == null) {
			out.add(where + ": " + owner.replace('/', '.') + "." + name + desc + " is not found in the owner or its supertypes");
			return null;
		}
		Type receiver = (method.access & Opcodes.ACC_STATIC) != 0 ? null : Type.getObjectType(owner);
		return new Call(receiver, Type.getArgumentTypes(desc), Type.getReturnType(desc));
	}

	private static MethodNode findMethod(Classes classes, String owner, String name, String desc) {
		ClassNode c = classes.get(owner);
		if (c == null) {
			return null;
		}
		for (MethodNode m : c.methods) {
			if (m.name.equals(name) && m.desc.equals(desc)) {
				return m;
			}
		}
		MethodNode up = c.superName == null ? null : findMethod(classes, c.superName, name, desc);
		if (up != null) {
			return up;
		}
		for (String i : c.interfaces) {
			MethodNode inherited = findMethod(classes, i, name, desc);
			if (inherited != null) {
				return inherited;
			}
		}
		return null;
	}

	private record Call(Type receiver, Type[] args, Type ret) {
	}

	private static Type[] withReceiver(Call call) {
		return call.receiver() == null ? call.args() : concat(new Type[] {call.receiver()}, call.args());
	}

	private static boolean isInjector(String desc) {
		return desc.startsWith(INJECTION_PREFIX) || desc.startsWith(MIXINEXTRAS_PREFIX) || desc.startsWith(GEN_PREFIX)
			|| desc.startsWith(MIXINEXTRAS_INJECTOR_PREFIX) || OVERWRITE.equals(desc);
	}

	private static boolean capturesLocals(AnnotationNode inject) {
		List<String> locals = strings(value(inject, "locals"));
		return !locals.isEmpty() && !"NO_CAPTURE".equals(locals.get(locals.size() - 1));
	}

	/** The mixin's target classes: {@code value} (class literals) and {@code targets} (dotted names). */
	private static List<String> targets(AnnotationNode mixin) {
		List<String> out = new ArrayList<>();
		for (Type t : types(value(mixin, "value"))) {
			out.add(t.getInternalName());
		}
		for (String s : strings(value(mixin, "targets"))) {
			out.add(s.replace('.', '/'));
		}
		return out;
	}

	/** An explicit accessor/invoker name, or the name derived from the handler's prefix. */
	private static String derived(String method, String... prefixes) {
		for (String p : prefixes) {
			if (method.startsWith(p) && method.length() > p.length()) {
				return Character.toLowerCase(method.charAt(p.length())) + method.substring(p.length() + 1);
			}
		}
		return null;
	}

	private static Type[] argTypes(MethodNode m) {
		return Type.getArgumentTypes(m.desc);
	}

	private static boolean[] coerced(MethodNode m) {
		int n = Type.getArgumentTypes(m.desc).length;
		boolean[] out = new boolean[n];
		for (int i = 0; i < n; i++) {
			for (AnnotationNode p : paramAnnotations(m, i)) {
				if (COERCE.equals(p.desc)) {
					out[i] = true;
				}
			}
		}
		return out;
	}

	private static void add(List<String> out, String where, List<String> problems) {
		for (String p : problems) {
			out.add(where + ": " + p);
		}
	}

	private static void assertNoProblems(List<String> problems) {
		assertTrue(problems.isEmpty(), () -> problems.size() + " problem(s):\n  " + String.join("\n  ", problems));
	}

	// ---------------------------------------------------------------- annotations and types

	private static List<AnnotationNode> all(List<AnnotationNode> visible, List<AnnotationNode> invisible) {
		List<AnnotationNode> out = new ArrayList<>();
		if (visible != null) {
			out.addAll(visible);
		}
		if (invisible != null) {
			out.addAll(invisible);
		}
		return out;
	}

	private static List<AnnotationNode> annotations(MethodNode m) {
		return all(m.visibleAnnotations, m.invisibleAnnotations);
	}

	private static List<AnnotationNode> paramAnnotations(MethodNode m, int i) {
		List<AnnotationNode> out = new ArrayList<>();
		if (m.visibleParameterAnnotations != null && i < m.visibleParameterAnnotations.length
				&& m.visibleParameterAnnotations[i] != null) {
			out.addAll(m.visibleParameterAnnotations[i]);
		}
		if (m.invisibleParameterAnnotations != null && i < m.invisibleParameterAnnotations.length
				&& m.invisibleParameterAnnotations[i] != null) {
			out.addAll(m.invisibleParameterAnnotations[i]);
		}
		return out;
	}

	private static AnnotationNode find(List<AnnotationNode> annotations, String desc) {
		for (AnnotationNode a : annotations) {
			if (a.desc.equals(desc)) {
				return a;
			}
		}
		return null;
	}

	/** An annotation element's value; ASM stores an array as a list, an enum as {descriptor, name}. */
	private static Object value(AnnotationNode a, String key) {
		if (a == null || a.values == null) {
			return null;
		}
		for (int i = 0; i + 1 < a.values.size(); i += 2) {
			if (key.equals(a.values.get(i))) {
				return a.values.get(i + 1);
			}
		}
		return null;
	}

	private static List<String> strings(Object v) {
		if (v == null) {
			return List.of();
		}
		if (v instanceof String s) {
			return List.of(s);
		}
		if (v instanceof String[] arr) {
			return List.of(arr);
		}
		List<String> out = new ArrayList<>();
		for (Object o : (List<?>) v) {
			out.add((String) o);
		}
		return out;
	}

	private static List<Type> types(Object v) {
		List<Type> out = new ArrayList<>();
		if (v instanceof Type t) {
			out.add(t);
		} else if (v instanceof List<?> list) {
			for (Object o : list) {
				out.add((Type) o);
			}
		}
		return out;
	}

	private static Type[] concat(Type[] a, Type... b) {
		return Stream.concat(Arrays.stream(a), Arrays.stream(b)).toArray(Type[]::new);
	}

	private static String simple(Type t) {
		if (t.getSort() == Type.OBJECT) {
			String n = t.getInternalName();
			return n.substring(n.lastIndexOf('/') + 1);
		}
		return t.getClassName();
	}

	private static String show(Type[] types) {
		List<String> names = new ArrayList<>();
		for (Type t : types) {
			names.add(simple(t));
		}
		return "(" + String.join(", ", names) + ")";
	}

	/** Class files of one classloader, read as bytecode: never loaded, never initialised. Null when absent. */
	static final class Classes {

		private final ClassLoader loader;
		private final Map<String, ClassNode> cache = new HashMap<>();
		private final Map<String, ClassNode> withCode = new HashMap<>();

		Classes(ClassLoader loader) {
			this.loader = loader;
		}

		ClassNode get(String internalName) {
			if (!cache.containsKey(internalName)) {
				cache.put(internalName, read(internalName));
			}
			return cache.get(internalName);
		}

		/** The class with its method bodies (the {@code @ModifyConstant} rule reads them); null when absent. */
		ClassNode withCode(String internalName) {
			if (!withCode.containsKey(internalName)) {
				withCode.put(internalName, read(internalName, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES));
			}
			return withCode.get(internalName);
		}

		private ClassNode read(String internalName) {
			return read(internalName, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
		}

		private ClassNode read(String internalName, int flags) {
			try (InputStream in = loader.getResourceAsStream(internalName + ".class")) {
				if (in == null) {
					return null;
				}
				ClassNode node = new ClassNode();
				new ClassReader(in).accept(node, flags);
				return node;
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		}
	}

	/** The classpath of one pass, from the path list Gradle passes: class folders, resource folders and jars. */
	private static URL[] urls(String pathList) throws IOException {
		String[] paths = pathList.split(File.pathSeparator);
		URL[] out = new URL[paths.length];
		for (int i = 0; i < paths.length; i++) {
			out[i] = Path.of(paths[i]).toUri().toURL();
		}
		return out;
	}
}
