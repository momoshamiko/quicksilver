// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.msl.builtins;

import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * One resolved GLSL built-in call as the MSL emitter hands it to the table (W2-C, ADR 0006). The table knows nothing
 * of the AST: names, type strings and ready MSL text only.
 *
 * <p>Types are GLSL type strings ("float", "vec3", "ivec2", "uvec4", "bool", "bvec3", "mat3", "mat4x3", "sampler2D",
 * "sampler2DShadow", "usampler2D", "sampler3D", "samplerCube", "sampler2DArray", "image2D(rgba16f)", "image3D",
 * "uimage2D(r32ui)"; image types carry their GLSL format qualifier in parentheses). Args are the already-emitted MSL
 * text of each argument, in order; for the sampler or image argument of a texture call that text is the MSL name of
 * the texture object.
 *
 * <p>Texture and image calls carry {@link Resource}: the MSL name of the texture object and of its sampler state
 * (null for images, which have no sampler). Which texture object a GLSL sampler names, its access mode and whether
 * the sampler state is a {@code constexpr} sampler are the emitter's decisions.
 *
 * <p>Arguments that GLSL passes by reference ({@code out} parameters: modf, frexp, uaddCarry, umulExtended, ...) are
 * lvalue expressions in {@code args}; the mapped MSL keeps them lvalues.
 */
public record BuiltinCall(String name, List<String> argTypes, String resultType, List<String> args,
						  @Nullable Resource resource) {

	public BuiltinCall {
		if (argTypes.size() != args.size())
			throw new IllegalArgumentException("argTypes and args must be parallel");
	}

	/**
	 * The MSL texture and sampler names a texture call uses. {@code implicitSampler} is the sampler of the lookups with no
	 * explicit level or gradient (texture, textureProj, textureOffset), or null when they take {@code sampler}.
	 * {@code lodBias} (ADR 0008) is an MSL expression those lookups add to their bias, or null.
	 */
	public record Resource(String texture, @Nullable String sampler, @Nullable String implicitSampler, @Nullable String lodBias) {
		public Resource(String texture, @Nullable String sampler) {
			this(texture, sampler, null, null);
		}

		public Resource(String texture, @Nullable String sampler, @Nullable String implicitSampler) {
			this(texture, sampler, implicitSampler, null);
		}
	}

	/** A call with no resource (plain numeric/integer/packing functions). */
	public static BuiltinCall of(String name, String resultType, String... argTypeAndMsl) {
		if (argTypeAndMsl.length % 2 != 0) throw new IllegalArgumentException("pairs of (type, msl)");
		String[] types = new String[argTypeAndMsl.length / 2];
		String[] msl = new String[types.length];
		for (int i = 0; i < types.length; i++) {
			types[i] = argTypeAndMsl[2 * i];
			msl[i] = argTypeAndMsl[2 * i + 1];
		}
		return new BuiltinCall(name, List.of(types), resultType, List.of(msl), null);
	}

	/** Parses argTypes[i]; throws with the function name if it is not a known type string. */
	public GlslTypes.Type type(int i) {
		return GlslTypes.parse(argTypes.get(i));
	}
}
