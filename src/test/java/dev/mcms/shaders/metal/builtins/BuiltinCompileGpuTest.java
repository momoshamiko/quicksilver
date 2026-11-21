// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.metal.builtins;

import dev.mcms.glsl.msl.builtins.BuiltinCall;
import dev.mcms.glsl.msl.builtins.BuiltinCatalog;
import dev.mcms.glsl.msl.builtins.GlslTypes;
import dev.mcms.glsl.msl.builtins.Mapping;
import dev.mcms.glsl.msl.builtins.Prelude;
import dev.mcms.shaders.metal.HeadlessGpu;
import dev.mcms.shaders.metal.ShaderNative;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Metal compiles the table's MSL for every mapped signature of GLSL 4.60 chapter 8 (W2-C, ADR 0006), under fast math
 * (the engine's choice). Non-resource signatures become compute kernels with zero-valued arguments; resource
 * signatures (texture lookups, queries, gathers, image functions) become one function per signature that takes the
 * texture as its first parameter, a compute kernel unless the call needs implicit derivatives (a lookup with no level
 * or gradient, and textureQueryLod), which become fragment functions because Metal allows those only in fragment
 * shaders. Signatures are compiled in batches; a batch that fails is compiled one signature at a time, so every
 * failing signature is named with Metal's diagnostics. Derivatives (dFdx and kin) and atomic memory functions are
 * not compute functions in GLSL, and barriers need compute; they are left to the other tests.
 */
@Tag("gpu")
final class BuiltinCompileGpuTest {

	/** Functions per library: a batch that fails is compiled again one function at a time. */
	private static final int BATCH = 48;

	/** One mapped signature, its function index in the library and its stage (fragment or compute). */
	private record Unit(int index, BuiltinCatalog.Signature sig, Mapping.Mapped mapped, boolean fragment,
						String texture) {
	}

	@Test
	void everyMappedSignatureCompilesInMetal() {
		List<String> failures = new ArrayList<>();
		int compiled = 0, skippedResource = 0, skippedStage = 0, unsupported = 0, mapped = 0;
		try (HeadlessGpu gpu = new HeadlessGpu().with(ShaderNative.MSL_3_1, ShaderNative.MATH_PACKS, true)) {
			for (BuiltinCatalog.Signature s : BuiltinCatalog.of(BuiltinKernel.TABLE).signatures()) {
				if (!s.supported()) {
					unsupported++;
					continue;
				}
				mapped++;
				if (isResource(s)) {
					skippedResource++;
					continue;
				}
				if (s.name().startsWith("dFd") || s.name().startsWith("fwidth") || s.name().startsWith("atomic")
					|| s.name().startsWith("memoryBarrier") || s.name().startsWith("groupMemoryBarrier")) {
					skippedStage++;
					continue;
				}
				BuiltinKernel.Case c = zeroCase(s);
				dev.mcms.glsl.msl.builtins.Mapping m = BuiltinKernel.TABLE.map(BuiltinKernel.call(c));
				if (!(m instanceof Mapping.Mapped mapping)) continue;
				String msl = BuiltinKernel.kernel(c, mapping);
				HeadlessGpu.CompileResult r = gpu.compile(msl);
				if (r.ok()) compiled++;
				else failures.add(s.name() + s.argTypes() + " -> " + mapping.msl() + ": " + firstLine(r.diagnostics()));
			}
		}
		System.out.println("builtin signatures: mapped " + mapped + " (compiled " + compiled + ", resource calls "
			+ skippedResource + " skipped here, stage-only " + skippedStage + "), unsupported " + unsupported);
		assertTrue(failures.isEmpty(), () -> String.join("\n", failures));
	}

	/**
	 * Every mapped resource signature (its first GLSL argument is a sampler or an image) compiles in the stage it is
	 * legal in, under fast math. Prints the counts for the report.
	 */
	@Test
	void everyMappedResourceSignatureCompilesInMetal() {
		List<Unit> units = new ArrayList<>();
		List<String> failures = new ArrayList<>();
		int unsupported = 0, resource = 0;
		for (BuiltinCatalog.Signature s : BuiltinCatalog.of(BuiltinKernel.TABLE).signatures()) {
			if (!s.supported()) {
				unsupported++;
				continue;
			}
			if (!isResource(s)) continue;
			resource++;
			BuiltinCall call = resourceCall(s);
			Mapping mapping = BuiltinKernel.TABLE.map(call);
			if (!(mapping instanceof Mapping.Mapped m)) {
				failures.add(s.name() + s.argTypes() + ": catalog says mapped, table says "
					+ ((Mapping.Unsupported) mapping).reason());
				continue;
			}
			String texture;
			try {
				texture = textureType(s.argTypes().getFirst());
			} catch (IllegalArgumentException e) {
				failures.add(describe(s, m) + ": " + e.getMessage());
				continue;
			}
			units.add(new Unit(units.size(), s, m, implicitDerivatives(s.name(), m.msl()), texture));
		}
		int compiled = 0, fragment = (int) units.stream().filter(Unit::fragment).count();
		try (HeadlessGpu gpu = new HeadlessGpu().with(ShaderNative.MSL_3_1, ShaderNative.MATH_PACKS, true)) {
			for (int from = 0; from < units.size(); from += BATCH) {
				List<Unit> batch = units.subList(from, Math.min(units.size(), from + BATCH));
				if (gpu.compile(library(batch)).ok()) {
					compiled += batch.size();
					continue;
				}
				for (Unit u : batch) {
					HeadlessGpu.CompileResult one = gpu.compile(library(List.of(u)));
					if (one.ok()) compiled++;
					else failures.add(describe(u) + ": " + firstLine(one.diagnostics()));
				}
			}
		}
		System.out.println("builtin resource signatures: " + resource + " mapped (" + (units.size() - fragment)
			+ " compute, " + fragment + " fragment), compiled " + compiled + ", failing " + failures.size()
			+ "; unsupported " + unsupported + " (all signatures)");
		assertTrue(failures.isEmpty(), () -> String.join("\n", failures));
	}

	private static boolean isResource(BuiltinCatalog.Signature s) {
		return !s.argTypes().isEmpty() && (s.argTypes().getFirst().contains("sampler")
			|| s.argTypes().getFirst().contains("image"));
	}

	/**
	 * Implicit derivatives: a filtered lookup with no explicit level or gradient (texture, textureProj, bias forms,
	 * shadow forms) and textureQueryLod. Metal allows implicit lod only in fragment functions.
	 */
	static boolean implicitDerivatives(String name, String msl) {
		if (name.equals("textureQueryLod")) return true;
		return (msl.contains(".sample(") || msl.contains(".sample_compare(")) && !msl.contains("level(")
			&& !msl.contains("gradient");
	}

	/** The mapping call for a resource signature: the texture is named tex, its sampler smp, arguments a1..an. */
	private static BuiltinCall resourceCall(BuiltinCatalog.Signature s) {
		List<String> names = new ArrayList<>();
		names.add("tex");
		for (int i = 1; i < s.argTypes().size(); i++) names.add("a" + i);
		return new BuiltinCall(s.name(), s.argTypes(), s.resultType(), names, new BuiltinCall.Resource("tex", "smp"));
	}

	private static String describe(Unit u) {
		return describe(u.sig(), u.mapped()) + (u.fragment() ? " [fragment]" : "");
	}

	private static String describe(BuiltinCatalog.Signature s, Mapping.Mapped m) {
		return s.name() + s.argTypes() + " -> " + m.msl();
	}

	/** The Metal texture class of a sampler or image type; Metal has no read-write multisample texture. */
	private static String textureType(String glsl) {
		GlslTypes.Type t = GlslTypes.parse(glsl);
		return t.kind() == GlslTypes.Kind.SAMPLER ? GlslTypes.mslTexture(t.sampler()) : GlslTypes.mslImage(t.image());
	}

	/** One library: the header, the helpers the batch names, then one function per unit. */
	private static String library(List<Unit> batch) {
		Set<String> helpers = new LinkedHashSet<>();
		StringBuilder functions = new StringBuilder();
		for (Unit u : batch) {
			helpers.addAll(u.mapped().helpers());
			functions.append(function(u));
		}
		return "#include <metal_stdlib>\nusing namespace metal;\n" + Prelude.render(helpers) + functions;
	}

	/** One function: the texture parameter, the arguments as zero values, and the table's expression. */
	private static String function(Unit u) {
		StringBuilder sb = new StringBuilder();
		sb.append(u.fragment() ? "fragment float4 f" : "kernel void k").append(u.index()).append("(").append(u.texture())
			.append(" tex [[texture(0)]]) {\n");
		sb.append("\tconstexpr sampler smp(filter::nearest, address::clamp_to_edge, coord::normalized, "
			+ "compare_func::less_equal);\n");
		for (int i = 1; i < u.sig().argTypes().size(); i++)
			sb.append('\t').append(declare(u.sig().argTypes().get(i), "a" + i)).append('\n');
		if (u.sig().resultType().equals("void")) {
			sb.append('\t').append(u.mapped().msl()).append(";\n");
		} else {
			sb.append("\tauto r = ").append(u.mapped().msl()).append(";\n\t(void)r;\n");
		}
		if (u.fragment()) sb.append("\treturn float4(0.0f);\n");
		sb.append("}\n");
		return sb.toString();
	}

	/**
	 * A value-initialised declaration; GLSL's ivec2[4] is a metal::array of four int2. A scalar int is a constant
	 * expression, as GLSL requires of a gather component (Metal's gather takes it as a compile-time argument).
	 */
	private static String declare(String glslType, String name) {
		if (glslType.equals("int")) return "constexpr int " + name + " = 0;";
		if (glslType.endsWith("[4]"))
			return "array<" + BuiltinKernel.mslType(glslType.substring(0, glslType.length() - 3)) + ", 4> " + name
				+ "{};";
		return BuiltinKernel.mslType(glslType) + " " + name + "{};";
	}

	private static BuiltinKernel.Case zeroCase(BuiltinCatalog.Signature s) {
		List<String> types = s.argTypes();
		Object[] args = new Object[types.size()];
		for (int i = 0; i < types.size(); i++) {
			GlslTypes.Type t = GlslTypes.parse(types.get(i));
			int n = BuiltinKernel.words(types.get(i));
			boolean floats = t.kind() == GlslTypes.Kind.FLOAT || t.kind() == GlslTypes.Kind.MATRIX
				|| (t.kind() == GlslTypes.Kind.VECTOR && t.scalar() == GlslTypes.Scalar.FLOAT);
			args[i] = floats ? new float[n] : new int[n];
		}
		List<String> outTypes = s.resultType().equals("void") ? List.of() : List.of(s.resultType());
		return new BuiltinKernel.Case(s.name(), s.resultType(), types, args, outTypes, a -> new Object[0], 0f);
	}

	private static String firstLine(String d) {
		if (d == null) return "";
		String s = d.replace('\n', ' ');
		return s.length() > 300 ? s.substring(0, 300) : s;
	}
}
