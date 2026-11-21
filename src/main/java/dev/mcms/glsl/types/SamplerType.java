// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.types;

/**
 * A sampler type: dimension, shadow-ness and the component type of the texels it returns ({@code float} for
 * {@code sampler*}, {@code int} for {@code isampler*}, {@code uint} for {@code usampler*}). GLSL 4.60
 * specification, section 4.1.7.
 */
public record SamplerType(SamplerDim dim, boolean shadow, ScalarKind texel) implements GlslType {
	public SamplerType {
		if (shadow && (texel != ScalarKind.FLOAT || dim.multisample() || dim == SamplerDim.BUFFER)) {
			throw new IllegalArgumentException("no shadow sampler " + dim + " " + texel);
		}
	}

	@Override
	public String toString() {
		return switch (texel) {
			case INT -> "isampler" + dim.word();
			case UINT -> "usampler" + dim.word();
			default -> "sampler" + dim.word() + (shadow ? "Shadow" : "");
		};
	}
}
