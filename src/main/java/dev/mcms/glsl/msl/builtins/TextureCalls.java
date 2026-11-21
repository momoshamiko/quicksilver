// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.msl.builtins;

import dev.mcms.glsl.msl.builtins.GlslTypes.Dim;
import dev.mcms.glsl.msl.builtins.GlslTypes.SamplerShape;

/**
 * The texture lookup functions of GLSL 4.60 chapter 8.9 (and the compatibility names of 8.9.5 packs still use) as MSL
 * texture member calls (W2-C, ADR 0006). The MSL argument order measured on this device (MSL 3.1): layer after the
 * coordinates, then the sampling options, the texel offset last; shadow calls take the reference right after the
 * layer.
 *
 * <p>1D textures are 2D textures of height 1 (the runtime contract written in {@link BuiltinTable}): sampler1D maps to
 * texture2d, sampler1DArray to texture2d_array, and the shadow forms to depth2d and depth2d_array. A 1D coordinate x
 * becomes (x, 0.5) for normalized lookups and (x, 0) for texel lookups, so every 2D option applies to them. The 1D
 * offset becomes (offset, 0), and the 1D size is the width (the 1D array size is (width, layers)).
 *
 * <p>Coordinate conventions: array layers use the GLSL selection rule floor(layer + 0.5) (the hardware clamps);
 * projected coordinates divide by the last component, and the shadow reference of a projected lookup divides too
 * (classic GL projective shadow semantics); explicit gradients are gradient2d/3d/cube by dimension, and a 1D gradient
 * is the 2D gradient of (dx, 0) and (dy, 0).
 */
final class TextureCalls {
	/** What the call does, independent of the sampler kind; name-to-form resolution lives in BuiltinTable. */
	enum Form {
		SAMPLE, SAMPLE_LOD, SAMPLE_OFFSET, SAMPLE_LOD_OFFSET, SAMPLE_PROJ, SAMPLE_PROJ_LOD, SAMPLE_PROJ_OFFSET,
		SAMPLE_PROJ_LOD_OFFSET, SAMPLE_GRAD, SAMPLE_GRAD_OFFSET, SAMPLE_PROJ_GRAD, SAMPLE_PROJ_GRAD_OFFSET,
		FETCH, FETCH_OFFSET, SIZE, LEVELS, SAMPLES, GATHER, GATHER_OFFSET, GATHER_OFFSETS, QUERY_LOD
	}

	static Mapping map(BuiltinCall call, Form form) {
		SamplerShape shape = call.type(0).sampler();
		String tex = call.args().get(0);
		Dim d = shape.dim();
		if (d == Dim.BUFFER) return buffer(call, form, tex);
		String samp = call.resource() == null ? null : call.resource().sampler();
		if (samp == null)
			return new Mapping.Unsupported("texture call without a sampler state name (emitter bug)");
		// Lookups with no explicit level or gradient take the implicit sampler when the binding has one (E6's hybrid mip rule).
		String implicit = call.resource().implicitSampler() != null ? call.resource().implicitSampler() : samp;
		boolean ms = d == Dim.D2_MS || d == Dim.D2_MS_ARRAY;
		boolean cube = d == Dim.CUBE || d == Dim.CUBE_ARRAY;
		boolean rect = d == Dim.D2_RECT;
		if (ms && form != Form.FETCH && form != Form.SIZE && form != Form.SAMPLES)
			return new Mapping.Unsupported("multisample textures have no filtered lookups in GLSL (texelFetch only)");
		if (form == Form.SAMPLES && !ms)
			return new Mapping.Unsupported("textureSamples takes multisample textures only (GLSL 4.60 8.9.1)");
		if (rect && (form == Form.SAMPLE_LOD || form == Form.SAMPLE_PROJ_LOD || form == Form.SAMPLE_LOD_OFFSET
			|| form == Form.SAMPLE_PROJ_LOD_OFFSET || form == Form.LEVELS || form == Form.QUERY_LOD))
			return new Mapping.Unsupported("rectangle textures have no mipmaps (no lod or level count; GLSL 4.60 "
				+ "8.9.1 and 8.9.2)");
		if (cube && (form == Form.SAMPLE_OFFSET || form == Form.SAMPLE_LOD_OFFSET || form == Form.SAMPLE_PROJ_OFFSET
			|| form == Form.SAMPLE_PROJ_LOD_OFFSET || form == Form.SAMPLE_GRAD_OFFSET
			|| form == Form.SAMPLE_PROJ_GRAD_OFFSET || form == Form.FETCH_OFFSET))
			return new Mapping.Unsupported("cube textures have no offset lookups (GLSL 4.60 8.9.2 and 8.9.1)");
		if (form == Form.GATHER || form == Form.GATHER_OFFSET || form == Form.GATHER_OFFSETS) {
			if (d != Dim.D2 && d != Dim.D2_ARRAY && d != Dim.D2_RECT && !cube)
				return new Mapping.Unsupported("textureGather takes 2D, 2D array, cube, cube array and rectangle "
					+ "textures (GLSL 4.60 8.9.4); " + d + " has no gather");
			if (cube && form != Form.GATHER)
				return new Mapping.Unsupported("textureGatherOffset takes no cube textures (GLSL 4.60 8.9.4)");
		}

		return switch (form) {
			case SAMPLE -> sample(call, shape, tex, implicit, false,
				implicitBias(call, explicitCompare(call) || call.args().size() < 3 ? null : call.args().get(2)), null);
			case SAMPLE_PROJ -> sample(call, shape, tex, implicit, true,
				implicitBias(call, call.args().size() > 2 ? call.args().get(2) : null), null);
			case SAMPLE_LOD -> sample(call, shape, tex, samp, false,
				"level(" + call.args().get(2) + ")", null);
			case SAMPLE_PROJ_LOD -> sample(call, shape, tex, samp, true,
				"level(" + call.args().get(2) + ")", null);
			case SAMPLE_LOD_OFFSET -> sample(call, shape, tex, samp, false,
				"level(" + call.args().get(2) + ")", offsetArg(call, shape, 3));
			case SAMPLE_PROJ_LOD_OFFSET -> sample(call, shape, tex, samp, true,
				"level(" + call.args().get(2) + ")", offsetArg(call, shape, 3));
			case SAMPLE_OFFSET -> sample(call, shape, tex, implicit, false,
				implicitBias(call, call.args().size() > 3 ? call.args().get(3) : null),
				offsetArg(call, shape, 2));
			case SAMPLE_PROJ_OFFSET -> sample(call, shape, tex, implicit, true,
				implicitBias(call, call.args().size() > 3 ? call.args().get(3) : null),
				offsetArg(call, shape, 2));
			case SAMPLE_GRAD -> sample(call, shape, tex, samp, false, gradient(shape, call, 2, 3), null);
			case SAMPLE_PROJ_GRAD -> sample(call, shape, tex, samp, true, gradient(shape, call, 2, 3), null);
			case SAMPLE_GRAD_OFFSET -> sample(call, shape, tex, samp, false, gradient(shape, call, 2, 3),
				offsetArg(call, shape, 4));
			case SAMPLE_PROJ_GRAD_OFFSET -> sample(call, shape, tex, samp, true, gradient(shape, call, 2, 3),
				offsetArg(call, shape, 4));
			case FETCH -> fetch(call, shape, tex, false);
			case FETCH_OFFSET -> fetch(call, shape, tex, true);
			case SIZE -> size(shape, tex, call.args().size() > 1 ? call.args().get(1) : null);
			case LEVELS -> Mapping.Mapped.of("int(" + tex + ".get_num_mip_levels())");
			case SAMPLES -> Mapping.Mapped.of("int(" + tex + ".get_num_samples())");
			case GATHER -> gather(call, shape, tex, samp);
			case GATHER_OFFSET -> gather(call, shape, tex, samp);
			case GATHER_OFFSETS -> gatherOffsets(call, shape, tex, samp);
			case QUERY_LOD -> queryLod(call, shape, tex, samp);
		};
	}

	/**
	 * The bias option of an implicit-LOD lookup: the pack's own bias argument and the engine's LOD bias (ADR 0008,
	 * {@code Resource.lodBias}), summed; null with neither. A comparison lookup takes no engine bias.
	 */
	private static String implicitBias(BuiltinCall call, String packBias) {
		String engine = call.resource() == null || explicitCompare(call) ? null : call.resource().lodBias();
		if (engine == null) {
			return packBias == null ? null : "bias(" + packBias + ")";
		}
		return "bias(" + (packBias == null ? engine : "(" + packBias + ") + " + engine) + ")";
	}

	/**
	 * Whether the lookup can read a mip level other than 0 (E9; BuiltinTable.readsLevels). An implicit-LOD lookup does when
	 * the binding has no implicit sampler: it takes the mip-filtered sampler (a program that declares the target's mipmaps,
	 * E6's rule). An implicit-LOD lookup on a sampler parameter has the parameter's implicit sampler, which can be any
	 * passed uniform's mip-filtered one, so the stage counts it apart ({@link #implicitLookup}). An explicit level does
	 * unless it is the literal 0; a gradient always does; a fetch does unless its level is the literal 0 (a rectangle or
	 * multisample fetch has no level). A gather reads level 0; size, level count, sample count and the LOD query read no
	 * texel.
	 */
	static boolean readsLevels(BuiltinCall call, Form form) {
		Dim d = call.type(0).sampler().dim();
		return switch (form) {
			case SAMPLE, SAMPLE_PROJ, SAMPLE_OFFSET, SAMPLE_PROJ_OFFSET ->
				call.resource() == null || call.resource().implicitSampler() == null;
			case SAMPLE_LOD, SAMPLE_PROJ_LOD, SAMPLE_LOD_OFFSET, SAMPLE_PROJ_LOD_OFFSET -> !isZero(call.args().get(2));
			case SAMPLE_GRAD, SAMPLE_GRAD_OFFSET, SAMPLE_PROJ_GRAD, SAMPLE_PROJ_GRAD_OFFSET -> true;
			case FETCH, FETCH_OFFSET -> d != Dim.D2_RECT && d != Dim.D2_MS && d != Dim.D2_MS_ARRAY && d != Dim.BUFFER
				&& !isZero(call.args().get(2));
			case GATHER, GATHER_OFFSET, GATHER_OFFSETS, SIZE, LEVELS, SAMPLES, QUERY_LOD -> false;
		};
	}

	/** Whether the lookup takes the implicit level of detail (the forms {@link #readsLevels} decides by the implicit sampler). */
	static boolean implicitLookup(Form form) {
		return form == Form.SAMPLE || form == Form.SAMPLE_PROJ || form == Form.SAMPLE_OFFSET || form == Form.SAMPLE_PROJ_OFFSET;
	}

	/** Whether an emitted argument is a literal zero ({@code 0}, {@code 0.0f}, {@code 0u}, with parentheses or a sign). */
	private static boolean isZero(String msl) {
		return msl.strip().matches("\\(*\\s*[-+]?0*\\.?0*(?:[eE][-+]?\\d+)?[fFuUhH]?\\s*\\)*") && msl.contains("0");
	}

	/**
	 * samplerBuffer (GLSL 4.60 8.9.1): texelFetch and textureSize only. Metal's texture_buffer has read(uint) and
	 * get_width(), no sampler and no level, so the element is read by index with no sampler state.
	 */
	private static Mapping buffer(BuiltinCall call, Form form, String tex) {
		return switch (form) {
			case FETCH -> Mapping.Mapped.of(tex + ".read(uint(" + call.args().get(1) + "))");
			case SIZE -> Mapping.Mapped.of("int(" + tex + ".get_width())");
			default -> new Mapping.Unsupported("samplerBuffer takes texelFetch and textureSize only (GLSL 4.60 8.9.1); "
				+ "Metal's texture_buffer has no sampler, filter or level");
		};
	}

	/**
	 * textureQueryLod (GLSL 4.60 8.9.1, fragment shaders only): x is the level the lookup accesses, clamped to the
	 * texture's levels, and y the level computed before clamping, both relative to the base level. Metal's
	 * calculate_clamped_lod and calculate_unclamped_lod give the two (measured: a footprint of 64 texels per pixel on 5
	 * levels gives 4 and 6). x is the specification's value for a mip-linear sampler; a nearest mip filter makes the
	 * specification round the level (GLSL 4.60 8.9.1), which this mapping does not model. The 1D form uses the
	 * height-1 texture's (x, 0.5) coordinate.
	 */
	private static Mapping queryLod(BuiltinCall call, SamplerShape shape, String tex, String samp) {
		String p = call.args().get(1);
		String coord = shape.dim() == Dim.D1 || shape.dim() == Dim.D1_ARRAY ? "float2(" + p + ", 0.5f)" : p;
		return Mapping.Mapped.of("float2(" + tex + ".calculate_clamped_lod(" + samp + ", " + coord + "), " + tex
			+ ".calculate_unclamped_lod(" + samp + ", " + coord + "))");
	}

	// --- filtered lookups: texture, textureProj, textureLod..., textureOffset..., textureGrad... ---

	private static Mapping sample(BuiltinCall call, SamplerShape shape, String tex, String samp, boolean projected,
								  String option, String offset) {
		Dim d = shape.dim();
		String p = call.args().get(1);
		String coord, layer = null, ref = null;
		if (shape.shadow()) {
			// Reference: the explicit compare argument (CubeArrayShadow only), else the last component of P; array
			// shadows take the layer from the second-to-last component. Projected shadow lookups divide the reference.
			switch (d) {
				case D1 -> {
					coord = line(projected ? proj1(call, p) : p + ".x");
					ref = projected ? "(" + p + ".z / " + p + ".w)" : p + ".z";
				}
				case D1_ARRAY -> {
					coord = line(p + ".x");
					layer = layerOf(p + ".y");
					ref = p + ".z";
				}
				case D2, D2_RECT -> {
					coord = projected ? div(p, ".xy") : p + ".xy";
					ref = projected ? "(" + p + ".z / " + p + ".w)" : p + ".z";
				}
				case CUBE -> {
					coord = projected ? div(p, ".xyz") : p + ".xyz";
					ref = p + ".w";
				}
				case D2_ARRAY -> {
					coord = projected ? div(p, ".xy") : p + ".xy";
					layer = layerOf(p + ".z");
					ref = p + ".w";
				}
				case CUBE_ARRAY -> {
					coord = p + ".xyz";
					layer = layerOf(p + ".w");
					ref = explicitCompare(call) ? call.args().get(2) : null;
					if (ref == null) return new Mapping.Unsupported(
						"samplerCubeArrayShadow needs the explicit compare argument of texture()");
				}
				default -> throw new IllegalStateException("shadow sampler " + shape);
			}
		} else {
			switch (d) {
				case D1 -> coord = line(projected ? proj1(call, p) : p);
				case D2, D2_RECT -> coord = projected ? (call.argTypes().get(1).equals("vec3")
					? "(" + p + ".xy / " + p + ".z)" : div(p, ".xy")) : p;
				case D3, CUBE -> coord = projected ? div(p, ".xyz") : p;
				case D1_ARRAY -> {
					coord = line(p + ".x");
					layer = layerOf(p + ".y");
				}
				case D2_ARRAY -> {
					coord = projected ? div(p, ".xy") : p + ".xy";
					layer = layerOf(p + ".z");
				}
				case CUBE_ARRAY -> {
					coord = p + ".xyz";
					layer = layerOf(p + ".w");
				}
				default -> throw new IllegalStateException("sampler " + shape);
			}
		}
		StringBuilder msl = new StringBuilder(tex).append(shape.shadow() ? ".sample_compare(" : ".sample(")
			.append(samp).append(", ").append(coord);
		if (layer != null) msl.append(", ").append(layer);
		if (ref != null) msl.append(", ").append(ref);
		if (option != null) msl.append(", ").append(option);
		if (offset != null) msl.append(", ").append(offset);
		msl.append(')');
		return legacyShadowVec4(call, msl.toString());
	}

	/** texture(samplerCubeArrayShadow, P, compare) is the only form with an explicit compare argument. */
	private static boolean explicitCompare(BuiltinCall call) {
		return call.args().size() == 3 && call.type(0).sampler().dim() == Dim.CUBE_ARRAY
			&& call.type(0).sampler().shadow() && call.argTypes().get(2).equals("float");
	}

	/** Legacy shadow1D/shadow2D return vec4 with the comparison splatted; the modern forms return the float. */
	private static Mapping legacyShadowVec4(BuiltinCall call, String msl) {
		if (call.name().startsWith("shadow")) return Mapping.Mapped.of("float4(" + msl + ")");
		return Mapping.Mapped.of(msl);
	}

	// --- texelFetch / texelFetchOffset ---

	/**
	 * texelFetch(P, lod) and texelFetchOffset(P, lod, offset) (multisample: texelFetch(P, sample)). The offset moves the
	 * x (1D), xy (2D, 2D array) or xyz (3D) part of P only; an array layer is never offset.
	 */
	private static Mapping fetch(BuiltinCall call, SamplerShape shape, String tex, boolean offset) {
		Dim d = shape.dim();
		String p = call.args().get(1);
		String part = switch (d) {
			case D1, D2, D2_RECT, D2_MS, D3 -> p;
			case D1_ARRAY -> p + ".x";
			case D2_ARRAY, D2_MS_ARRAY -> p + ".xy";
			default -> throw new IllegalStateException("fetch " + shape);
		};
		// texelFetchOffset(P, lod, offset) takes the offset at argument 3; the 2D rectangle form (P, offset) at 2.
		String at = offset ? "(" + part + " + " + call.args().get(d == Dim.D2_RECT ? 2 : 3) + ")" : part;
		StringBuilder msl = new StringBuilder(tex).append(".read(");
		switch (d) {
			case D1, D1_ARRAY -> msl.append("uint2(uint(").append(at).append("), 0u)");
			case D2, D2_RECT, D2_MS, D2_ARRAY, D2_MS_ARRAY -> msl.append("uint2(").append(at).append(')');
			case D3 -> msl.append("uint3(").append(at).append(')');
			default -> throw new IllegalStateException("fetch " + shape);
		}
		switch (d) {
			case D1_ARRAY -> msl.append(", uint(").append(p).append(".y)");
			case D2_ARRAY, D2_MS_ARRAY -> msl.append(", uint(").append(p).append(".z)");
			default -> {
			}
		}
		if (d != Dim.D2_RECT) msl.append(", uint(").append(call.args().get(2)).append(')');
		msl.append(')');
		// A 2D shadow sampler (depth2d) reads one float, the stored depth: GL's depth read is (d, 0, 0, 1) (BuiltinCatalog).
		return Mapping.Mapped.of(shape.shadow() ? "float4(" + msl + ", 0.0, 0.0, 1.0)" : msl.toString());
	}

	/**
	 * textureGatherOffsets (GLSL 4.60 8.9.4): result component i is the i0j0 texel of the footprint at P + offsets[i].
	 * Metal gathers one footprint per call, in the order i0j1, i1j1, i1j0, i0j0 (measured), so this is four
	 * single-offset gathers, each contributing its .w component.
	 */
	private static Mapping gatherOffsets(BuiltinCall call, SamplerShape shape, String tex, String samp) {
		Dim d = shape.dim();
		String p = call.args().get(1);
		String offsets = null, ref = null, comp = null;
		for (int i = 2; i < call.args().size(); i++) {
			String t = call.argTypes().get(i);
			if (t.endsWith("[4]")) offsets = call.args().get(i);
			else if (t.equals("float")) ref = call.args().get(i);
			else if (t.equals("int")) comp = call.args().get(i);
		}
		if (offsets == null)
			return new Mapping.Unsupported("textureGatherOffsets without its offsets[4] argument (emitter bug)");
		String coord = d == Dim.D2_ARRAY ? p + ".xy" : p;
		String layer = d == Dim.D2_ARRAY ? layerOf(p + ".z") : null;
		String compArg = comp == null ? "" : ", component(int(" + comp + "))";
		String result = GlslTypes.mslVector(GlslTypes.parse(call.resultType()).scalar(), 4);
		StringBuilder msl = new StringBuilder(result).append('(');
		for (int i = 0; i < 4; i++) {
			if (i > 0) msl.append(", ");
			msl.append(tex).append(shape.shadow() ? ".gather_compare(" : ".gather(").append(samp).append(", ")
				.append(coord);
			if (layer != null) msl.append(", ").append(layer);
			if (shape.shadow()) msl.append(", ").append(ref);
			msl.append(", (").append(offsets).append(")[").append(i).append(']');
			if (!shape.shadow()) msl.append(compArg);
			msl.append(").w");
		}
		msl.append(')');
		return Mapping.Mapped.of(msl.toString());
	}

	// --- textureGather / textureGatherOffset ---

	private static Mapping gather(BuiltinCall call, SamplerShape shape, String tex, String samp) {
		Dim d = shape.dim();
		String p = call.args().get(1);
		if (shape.shadow()) {
			// (samplerKind, P [, refZ] [, offset]): refZ is the float argument, offset the ivecN one.
			String ref = null, off = null;
			for (int i = 2; i < call.args().size(); i++) {
				if (call.argTypes().get(i).equals("float")) ref = call.args().get(i);
				if (call.argTypes().get(i).startsWith("ivec")) off = call.args().get(i);
			}
			StringBuilder msl = new StringBuilder(tex).append(".gather_compare(").append(samp).append(", ");
			switch (d) {
				case D2, D2_RECT -> msl.append(p + ".xy").append(", ").append(ref);
				case CUBE -> msl.append(p + ".xyz").append(", ").append(ref);
				case D2_ARRAY -> msl.append(p + ".xy").append(", ").append(layerOf(p + ".z")).append(", ").append(ref);
				case CUBE_ARRAY -> msl.append(p + ".xyz").append(", ").append(layerOf(p + ".w")).append(", ")
					.append(ref);
				default -> throw new IllegalStateException("shadow gather " + shape);
			}
			if (off != null && (d == Dim.D2 || d == Dim.D2_RECT || d == Dim.D2_ARRAY))
				msl.append(", int2(").append(off).append(')');
			msl.append(')');
			return Mapping.Mapped.of(msl.toString());
		}
		// Non-shadow: (P [, offset] [, comp]); comp is the int argument, offset the ivecN one.
		String comp = null, off = null;
		for (int i = 2; i < call.args().size(); i++) {
			if (call.argTypes().get(i).equals("int")) comp = call.args().get(i);
			if (call.argTypes().get(i).startsWith("ivec")) off = call.args().get(i);
		}
		String compArg = comp == null ? "" : ", component(int(" + comp + "))";
		StringBuilder msl = new StringBuilder(tex).append(".gather(").append(samp).append(", ");
		switch (d) {
			case D2, D2_RECT -> {
				msl.append(p);
				if (off != null) msl.append(", int2(").append(off).append(')');
				else if (!compArg.isEmpty()) msl.append(", int2(0)");
				msl.append(compArg);
			}
			case CUBE -> msl.append(p).append(compArg);
			case D2_ARRAY -> {
				msl.append(p + ".xy").append(", ").append(layerOf(p + ".z"));
				if (off != null) msl.append(", int2(").append(off).append(')');
				else if (!compArg.isEmpty()) msl.append(", int2(0)");
				msl.append(compArg);
			}
			case CUBE_ARRAY -> msl.append(p + ".xyz").append(", ").append(layerOf(p + ".w")).append(compArg);
			default -> throw new IllegalStateException("gather " + shape);
		}
		msl.append(')');
		return Mapping.Mapped.of(msl.toString());
	}

	// --- textureSize ---

	/** textureSize of a 1D texture is its width (the 1D array's is (width, layers)): the 2D helper does not apply. */
	private static Mapping size(SamplerShape shape, String tex, String lod) {
		String level = lod == null ? "0u" : "uint(" + lod + ")";
		if (shape.dim() == Dim.D1) return Mapping.Mapped.of("int(" + tex + ".get_width(" + level + "))");
		if (shape.dim() == Dim.D1_ARRAY)
			return Mapping.Mapped.of("int2(int(" + tex + ".get_width(" + level + ")), int(" + tex + ".get_array_size()))");
		if (lod == null) return Mapping.Mapped.of("qs::textureSize(" + tex + ")", "textureSize");
		return Mapping.Mapped.of("qs::textureSize(" + tex + ", " + lod + ")", "textureSize");
	}

	// --- text helpers ---

	private static String div(String p, String comps) {
		return "(" + p + comps + " / " + p + ".w)";
	}

	/** textureProj(gsampler1D, vec2|vec4): divide x by the last component (vec2: y, vec4: w). */
	private static String proj1(BuiltinCall call, String p) {
		String last = call.argTypes().get(1).equals("vec4") ? "w" : "y";
		return "(" + p + ".x / " + p + "." + last + ")";
	}

	/** The 2D coordinate of a 1D lookup: the 1D coordinate x and the fixed height coordinate of the height-1 texture. */
	private static String line(String x) {
		return "float2(" + x + ", 0.5f)";
	}

	private static String layerOf(String layerExpr) {
		return "uint(floor(" + layerExpr + " + 0.5f))";
	}

	private static String offsetArg(BuiltinCall call, SamplerShape shape, int index) {
		String o = call.args().get(index);
		return switch (shape.dim()) {
			case D1, D1_ARRAY -> "int2(" + o + ", 0)";
			case D2, D2_RECT, D2_ARRAY -> "int2(" + o + ")";
			case D3 -> "int3(" + o + ")";
			default -> "int(" + o + ")";
		};
	}

	private static String gradient(SamplerShape shape, BuiltinCall call, int dx, int dy) {
		String gx = call.args().get(dx), gy = call.args().get(dy);
		return switch (shape.dim()) {
			case D1, D1_ARRAY -> "gradient2d(float2(" + gx + ", 0.0f), float2(" + gy + ", 0.0f))";
			case D2, D2_RECT, D2_ARRAY -> "gradient2d(" + gx + ", " + gy + ")";
			case D3 -> "gradient3d(" + gx + ", " + gy + ")";
			case CUBE, CUBE_ARRAY -> "gradientcube(" + gx + ", " + gy + ")";
			default -> throw new IllegalStateException("gradient " + shape);
		};
	}

	private TextureCalls() {
	}
}
