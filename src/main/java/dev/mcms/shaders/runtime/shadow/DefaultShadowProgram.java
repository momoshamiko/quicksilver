// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.shadow;

import java.util.List;
import java.util.Optional;

import dev.mcms.shaders.pack.ProgramCatalog;
import dev.mcms.shaders.pack.ProgramDirectives;
import dev.mcms.shaders.pack.ResolvedProgram;
import dev.mcms.shaders.pack.StageFile;

/**
 * The built-in shadow program (E5 review item 17). The shadow program page says that when a pack has no {@code shadow}
 * program, "a default shader will be used". This is that shader: the caster's position through the shadow view, the texture
 * alpha-tested, and {@code shadowcolor0} written with texture times colour. Its depth is the rasteriser's, so a caster
 * writes {@code shadowtex0} as any pack's shadow program does.
 *
 * <p>The text goes through the same translator as a pack's shadow program ({@code ProgramRequests.shadow}), so it is built
 * and compiled like one; nothing in it is special-cased on the device.
 */
public final class DefaultShadowProgram {

	/** The name the program answers to: the base shadow program, which every shadow geometry falls back to. */
	public static final String NAME = "shadow";

	/** The vertex stage: the position through the shadow view and projection, and the attributes the fragment stage reads. */
	static final String VERTEX = """
		#version 120
		uniform mat4 shadowModelView;
		uniform mat4 shadowProjection;
		varying vec2 mcmsTexcoord;
		varying vec4 mcmsColour;
		void main() {
			gl_Position = shadowProjection * (shadowModelView * gl_Vertex);
			mcmsTexcoord = gl_MultiTexCoord0.xy;
			mcmsColour = gl_Color;
		}
		""";

	/** The fragment stage: texture times colour into shadowcolor0, with an alpha test at 0.1 (the documented default). */
	static final String FRAGMENT = """
		#version 120
		/* DRAWBUFFERS:0 */
		uniform sampler2D gtexture;
		varying vec2 mcmsTexcoord;
		varying vec4 mcmsColour;
		void main() {
			vec4 albedo = texture2D(gtexture, mcmsTexcoord) * mcmsColour;
			if (albedo.a < 0.1) {
				discard;
			}
			gl_FragData[0] = albedo;
		}
		""";

	private DefaultShadowProgram() {
	}

	/**
	 * The built-in program as a resolved program: its two stages and the directives their text gives ({@code DRAWBUFFERS:0},
	 * so the program writes {@code shadowcolor0} only).
	 */
	public static ResolvedProgram create() {
		List<StageFile> stages = List.of(
			new StageFile('v', "builtin/shadow.vsh", VERTEX),
			new StageFile('f', "builtin/shadow.fsh", FRAGMENT));
		ProgramCatalog.Program definition = ProgramCatalog.lookup(NAME).orElseThrow(
			() -> new IllegalStateException("the program catalog has no shadow program"));
		return new ResolvedProgram(NAME, definition, ResolvedProgram.Source.FALLBACK, Optional.of(NAME),
			Optional.empty(), stages, List.of(), Optional.of(ProgramDirectives.of(stages)));
	}
}
