// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime;

/**
 * What a pack program's sampler name reads in a gbuffers program (engine-pack-format.md section 2, the samplers table of
 * section 10). One table serves the translator's texture requests ({@link ProgramRequests}), the delegate's binds and its
 * unbound-name log, so the three cannot disagree about a name.
 *
 * <p>The atlas names are {@code texture}, its documented gbuffers alias {@code gtexture}, and {@code tex} (OptiFine's
 * shaders.txt sampler 0; Complementary r5.9.3's gbuffers_terrain declares {@code tex}). The shadow names ({@code shadow},
 * {@code watershadow}, {@code shadowtex0/1}, their {@code HW} forms, and {@code shadowcolor}, {@code shadowcolor0} to
 * {@code shadowcolor7}) read the set's shadow targets ({@link dev.mcms.shaders.runtime.shadow.ShadowTargets}, E5); which
 * depth map {@code shadow} is depends on the set ({@code watershadow} declared anywhere). The Distant Horizons depth names
 * ({@code dhDepthTex0}, {@code dhDepthTex1}; engine-pack-format.md section 17) are always {@link Source#DISTANT_DEPTH}: the
 * engine binds the DH depth targets while Distant Horizons draws through the pack, and otherwise binds nothing to them, so the
 * sampler reads zero texels as an UNBOUND name does (plan.md E7).
 */
public final class SamplerSources {

	/** The engine's source of one sampler name. */
	public enum Source {
		/** The game's block atlas, bound at the game's Sampler0 (or Sodium's u_BlockTex): {@code texture}, {@code gtexture}, {@code tex}. */
		ATLAS,
		/** The game's lightmap, bound at Sampler2 (or Sodium's u_LightTex): {@code lightmap}. */
		LIGHTMAP,
		/** The engine's 1x1 default normal map, the documented RGBA (127,127,255,255) ({@link dev.mcms.shaders.pack.textures.TextureImage#DEFAULT_NORMAL}): {@code normals} (no PBR pack is loaded). */
		NORMALS,
		/** The engine's 1x1 default specular map, RGBA (0,0,0,0): {@code specular} (no PBR pack is loaded). */
		SPECULAR,
		/** The engine's noise texture: {@code noisetex}. */
		NOISE,
		/** The engine's depth targets: {@code depthtex0}, {@code depthtex1}, {@code depthtex2}. */
		DEPTH,
		/** The engine's Distant Horizons depth targets: {@code dhDepthTex0} (all DH depth), {@code dhDepthTex1} (the opaque DH depth). */
		DISTANT_DEPTH,
		/** A colour target: {@code colortexN}, {@code gcolor}, {@code gdepth}, {@code gnormal}, {@code composite}, {@code gaux1-4}. */
		TARGET,
		/** The set's shadow targets: the shadow depth maps and shadow colour buffers (E5). */
		SHADOW,
		/** No engine source: the sampler reads zero texels (see {@link #unboundReason}). */
		UNBOUND
	}

	/** The atlas names a gbuffers program may declare, in the order the delegate prefers them as the primary binding. */
	public static final String[] ATLAS_NAMES = {"texture", "gtexture", "tex"};

	private SamplerSources() {
	}

	/**
	 * The source of a sampler name in a gbuffers program. The binds call this per sampler of every draw, so it allocates
	 * nothing: a string switch (RIL-567: a regex Matcher per call was most of the render thread's allocation).
	 */
	public static Source of(String name) {
		switch (name) {
			case "texture", "gtexture", "tex":
				return Source.ATLAS;
			case "lightmap":
				return Source.LIGHTMAP;
			case "normals":
				return Source.NORMALS;
			case "specular":
				return Source.SPECULAR;
			case "noisetex":
				return Source.NOISE;
			case "depthtex0", "depthtex1", "depthtex2":
				return Source.DEPTH;
			case "dhDepthTex0", "dhDepthTex1", "dhDepthTex":
				return Source.DISTANT_DEPTH;
			// The shadow names: the depth maps (with the aliases and the hardware-compared forms) and the colour buffers.
			case "shadow", "watershadow", "shadowtex0", "shadowtex1", "shadowtex0HW", "shadowtex1HW", "shadowcolor",
				"shadowcolor0", "shadowcolor1", "shadowcolor2", "shadowcolor3", "shadowcolor4", "shadowcolor5", "shadowcolor6",
				"shadowcolor7":
				return Source.SHADOW;
			default:
				return ProgramRequests.targetIndex(name) >= 0 ? Source.TARGET : Source.UNBOUND;
		}
	}

	/** The colour target index a TARGET name reads (colortex4 and gaux1 are 4), or -1 for any other name. */
	public static int targetOf(String name) {
		return ProgramRequests.targetIndex(name);
	}

	/**
	 * Which Distant Horizons depth target a DH depth name reads: 0 for {@code dhDepthTex0} and the unsuffixed {@code dhDepthTex}
	 * (Complementary, Unbound, Bliss and Photon declare it; it is the same depth), 1 for {@code dhDepthTex1}, -1 for any other name.
	 */
	public static int distantDepthOf(String name) {
		return switch (name) {
			case "dhDepthTex0", "dhDepthTex" -> 0;
			case "dhDepthTex1" -> 1;
			default -> -1;
		};
	}

	/** Why an UNBOUND name reads zero texels: the engine has no source for it, or (a DH depth name) Distant Horizons is not drawing. */
	public static String unboundReason(String name) {
		if (distantDepthOf(name) >= 0) {
			return "Distant Horizons does not draw through the pack in this session, so the engine binds no DH depth and the"
				+ " sampler reads zero texels";
		}
		return "the engine has no source for this name, so the sampler reads zero texels";
	}
}
