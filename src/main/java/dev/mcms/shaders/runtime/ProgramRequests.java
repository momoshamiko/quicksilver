// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime;

import dev.mcms.glsl.Translator;
import dev.mcms.glsl.msl.ProgramRequest;
import dev.mcms.glsl.msl.ProgramRequest.ColorTarget;
import dev.mcms.glsl.msl.ProgramRequest.SamplerState;
import dev.mcms.glsl.msl.ProgramRequest.StageSource;
import dev.mcms.glsl.msl.ProgramRequest.TextureBinding;
import dev.mcms.glsl.msl.ProgramRequest.TextureKind;
import dev.mcms.glsl.msl.ProgramRequest.UniformBlock;
import dev.mcms.glsl.msl.ProgramRequest.UniformField;
import dev.mcms.glsl.msl.ProgramRequest.UniformSchema;
import dev.mcms.glsl.msl.ProgramTranslation;
import dev.mcms.glsl.sema.SourceLine;
import dev.mcms.glsl.sema.Stage;
import dev.mcms.shaders.pack.PackModel;
import dev.mcms.shaders.pack.ProgramDirectives;
import dev.mcms.shaders.pack.ResolvedProgram;
import dev.mcms.shaders.pack.ShaderProperties;
import dev.mcms.shaders.pack.StageFile;
import dev.mcms.shaders.runtime.distant.DistantUniforms;
import dev.mcms.shaders.runtime.uniforms.CustomUniforms;
import dev.mcms.shaders.runtime.uniforms.FrameUniforms;
import dev.mcms.shaders.runtime.uniforms.Std140;
import dev.mcms.shaders.runtime.shadow.ShadowTargets;
import dev.mcms.shaders.pack.textures.CustomTextureTable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The runtime's {@link ProgramRequest}s for one program of the pack (plan.md E3): the full-screen family with the
 * full-screen quad vertex input, the colour targets the program's {@code DRAWBUFFERS}/{@code RENDERTARGETS} names (in
 * output order, engine-pack-format.md section 9), every sampler and image the stages declare at a slot of its own, the
 * pack's own uniform and buffer blocks, and the engine's schema: the frame block ({@link FrameUniforms}), the custom
 * block ({@link CustomUniforms}) and the full-screen draw block ({@link #PASS_BLOCK}) whose values the translator's
 * contract fixes (identity model-view, the orthographic quad projection mapping [0,1]^2, identity texture and normal
 * matrices and the declared inverses; the emitter itself substitutes the quad's {@code projectionMatrix} and
 * {@code modelViewMatrix} for the compatibility built-ins and the pack's 1.17+ names, the block serves
 * {@code gl_TextureMatrix[0]}, {@code gl_NormalMatrix} and the declared inverse uniforms a full-screen program still
 * reads).
 *
 * <p>The buffer slots follow {@link EngineSlots}: the engine's blocks at 23 to 25, pack blocks from 0 (at most 20). Pack
 * samplers and images take texture slots from 0. {@code alphaTestRef} lives in the frame block and is set per pass
 * (E3-B's contract).
 */
public final class ProgramRequests {

	/** The engine's uniform block slots (buffer indices), above the pack's own blocks (0..). */
	public static final int FRAME_SLOT = EngineSlots.FRAME, DRAW_SLOT = EngineSlots.DRAW, CUSTOM_SLOT = EngineSlots.CUSTOM;

	private static final Pattern COMMENTS = Pattern.compile("/\\*.*?\\*/|//[^\\n]*", Pattern.DOTALL);
	private static final Pattern RESOURCE_DECL = Pattern.compile(
		"(?:(?:layout\\s*\\([^)]*\\)|readonly|writeonly|coherent|volatile|restrict)\\s+)*"
			+ "uniform\\s+(?:(?:layout\\s*\\([^)]*\\)|readonly|writeonly|coherent|volatile|restrict|highp|mediump|lowp)\\s+)*"
			+ "((?:i|u)?sampler\\w+|(?:i|u)?image\\w+)\\s+([\\w\\s,]+?)\\s*[;\\[]");
	private static final Logger LOG = LoggerFactory.getLogger("Quicksilver");
	/** The QsDraw member that flags a draw with no per-vertex id records (ProgramRequest.PerVertexIds). */
	public static final String DRAW_IDS_DEFAULT = "qsIdsDefault";
	/** The per-entity id names a gbuffers program declares; such a program reads E4-D's side buffer at {@code [[vertex_id]]}. */
	private static final Pattern PER_VERTEX_ID_NAMES = Pattern.compile("\\b(entityId|blockEntityId|currentRenderedItemId)\\b");
	/** The entity overlay name a gbuffers program declares; such a program reads the overlay texture (ProgramRequest.OverlayColor). */
	private static final Pattern OVERLAY_NAME = Pattern.compile("\\bentityColor\\b");
	private static final Pattern TARGET_NAME = Pattern.compile("colortex(\\d+)|gcolor|gdepth|gnormal|composite|gaux([1-4])");

	/**
	 * The per-draw block {@code QsDraw}: the full-screen quad's matrices and inverses (the class comment) and, since
	 * E4, the draw's values: the render stage the mapping resolved, the pack's alpha-test reference and the draw's
	 * colour modulator (the game's {@code DynamicTransforms} {@code ColorModulator}, white when the draw has none), so
	 * the frame block stays one upload per frame. The gbuffers schema lists this block first, so its {@code renderStage},
	 * {@code alphaTestRef} and {@code colorModulator} shadow the frame block's (a per-draw value must not read a
	 * per-frame field).
	 */
	public static final UniformBlock DRAW_BLOCK = Std140.block("QsDraw", DRAW_SLOT, List.of(
		new Std140.Member("modelViewMatrix", "mat4"),
		new Std140.Member("projectionMatrix", "mat4"),
		new Std140.Member("modelViewMatrixInverse", "mat4"),
		new Std140.Member("projectionMatrixInverse", "mat4"),
		new Std140.Member("textureMatrix", "mat4"),
		new Std140.Member("normalMatrix", "mat3"),
		new Std140.Member("renderStage", "int"),
		new Std140.Member("alphaTestRef", "float"),
		new Std140.Member("colorModulator", "vec4"),
		// E6-atlassize: the size in pixels of the atlas the draw binds as its main texture, (0, 0) when none (Iris, OptiFine).
		new Std140.Member("atlasSize", "ivec2"),
		// 2026-11-14 (E4-D): non-zero for a draw with no per-vertex id records; the vertex prologue then reads record 0.
		new Std140.Member(DRAW_IDS_DEFAULT, "int")));

	/**
	 * {@link #PASS_BLOCK} as it was before E4, plus {@code atlasSize} (E6-atlassize). The full-screen family keeps it: its
	 * values are the quad's, and {@code atlasSize} stays (0, 0) because no atlas is bound.
	 */
	public static final UniformBlock PASS_BLOCK = Std140.block("QsDraw", DRAW_SLOT, List.of(
		new Std140.Member("modelViewMatrix", "mat4"),
		new Std140.Member("projectionMatrix", "mat4"),
		new Std140.Member("modelViewMatrixInverse", "mat4"),
		new Std140.Member("projectionMatrixInverse", "mat4"),
		new Std140.Member("textureMatrix", "mat4"),
		new Std140.Member("normalMatrix", "mat3"),
		// E6-atlassize: zero in a full-screen draw, which binds no atlas (EngineFrame's block holds zeros there).
		new Std140.Member("atlasSize", "ivec2")));

	/**
	 * The colour targets a program declares {@code <target>MipmapEnabled} for (engine-pack-format.md section 7): the program's
	 * own const. The chain of a target exists when the dimension's union enables it (TargetUnion.mipmaps), and every program
	 * samples that chain with a mip filter for explicit-level and gradient lookups (Iris's rule, measured 2026-11-15). Only the
	 * implicit-LOD lookups of a program that does not declare the target read level 0 ({@link #implicitSamplerOf}).
	 */
	public static Map<Integer, Boolean> mipmapsOf(ResolvedProgram program) {
		return program.directives().map(ProgramDirectives::mipmaps).orElse(Map.of());
	}

	/**
	 * The sampler of a program's implicit-LOD lookups of a colour target, or null when they take the binding's own sampler.
	 * Hybrid rule (E6, 2026-11-15): when the layout mips the target and this program does not declare it, the lookups without
	 * an explicit level or gradient take a sampler with no mip filter, so they read level 0 as a program did before the chains
	 * were shared; explicit-level and gradient lookups keep the chain.
	 */
	static SamplerState implicitSamplerOf(ResolvedProgram program, int target, SamplerState state,
			Map<Integer, Boolean> layoutMipmaps) {
		boolean layoutMips = target >= 0 && Boolean.TRUE.equals(layoutMipmaps.get(target));
		boolean declares = target >= 0 && Boolean.TRUE.equals(mipmapsOf(program).get(target));
		if (!layoutMips || declares || state.mip() == null) {
			return null;
		}
		return new SamplerState(state.filter(), null, state.wrap(), state.compare(), state.pixel());
	}

	/** The sampler slots the game's own textures take in a gbuffers program (the atlas and the lightmap). */
	public static final int GAME_SAMPLER_BASE = 0;

	private ProgramRequests() {
	}

	/**
	 * The sampler state of a shadow read (E5): the map's or colour buffer's filter and mip from the set's shadow constants
	 * (the docs' shadowtexNearest and shadowtexMipmap, and their colour spellings), and the comparison of its declared
	 * type (a sampler2DShadow, after the declaration rewrite of hardware filtering).
	 */
	private static SamplerState shadowState(String name, String type, ShadowTargets shadowLayout) {
		int depth = shadowLayout.depthIndexOf(name);
		int color = shadowLayout.colorIndexOf(name);
		boolean nearest = depth >= 0 ? shadowLayout.nearestDepth(depth) : shadowLayout.nearestColor(color);
		boolean mipped = depth >= 0 ? shadowLayout.mipmappedDepth(depth) : shadowLayout.mipmappedColor(color);
		return new SamplerState(nearest ? ProgramRequest.Filter.NEAREST : ProgramRequest.Filter.LINEAR,
			mipped ? ProgramRequest.Filter.LINEAR : null, ProgramRequest.Wrap.CLAMP_TO_EDGE, type.endsWith("Shadow"),
			false);
	}

	/**
	 * The sampler state of an engine texture that is not a shadow map or a custom texture: the colour targets and the
	 * noise texture filter linearly (mipmapped targets also between levels, noisetex repeats), the depth targets
	 * ({@code depthtex0-2}, the Distant Horizons depths) filter nearest. Iris samples depthtex0 and depthtex1 nearest and
	 * colortex0 linearly in a full-screen pass (measured with the mcms-filter pack, plan.md E7); before, the engine
	 * filtered depth linearly, which blends the depths of two surfaces at every edge an effect samples between texels
	 * (BSL's and Complementary's ambient occlusion do).
	 */
	static SamplerState targetState(String name, String type, boolean mipmapped, boolean shadow) {
		SamplerSources.Source source = SamplerSources.of(name);
		boolean depth = source == SamplerSources.Source.DEPTH || source == SamplerSources.Source.DISTANT_DEPTH;
		return new SamplerState(depth ? ProgramRequest.Filter.NEAREST : ProgramRequest.Filter.LINEAR,
			mipmapped && !depth ? ProgramRequest.Filter.LINEAR : null,
			name.equals("noisetex") ? ProgramRequest.Wrap.REPEAT : ProgramRequest.Wrap.CLAMP_TO_EDGE, shadow,
			type.contains("2DRect"));
	}

	/**
	 * The texture kind of a gbuffers sampler (E6-B, review item 14): the kind comes from the GLSL type, except that a
	 * {@code depthtex} name without a custom texture is the depth map. A custom texture on a depthtex name is an ordinary
	 * texture of its type.
	 */
	static TextureKind gbuffersKind(String name, String type, boolean shadow, boolean custom) {
		return shadow ? TextureKind.COMPARE : !custom && name.startsWith("depthtex") ? TextureKind.DEPTH
			: type.contains("3D") ? TextureKind.THREE_D
			: type.startsWith("isampler") || type.startsWith("usampler") ? TextureKind.INTEGER
			: type.contains("Buffer") ? TextureKind.BUFFER : type.contains("Cube") ? TextureKind.CUBE
			: type.contains("Array") ? TextureKind.ARRAY : TextureKind.COLOUR;
	}

	/**
	 * The sampler state of a custom texture (E6-B): its filter and wrap, pixel addressing for a rectangle. The game's atlas
	 * has mip levels that a program reads with texture2DLod (Complementary's worldSpaceRef does), so an atlas takes a linear
	 * mip filter; the other custom textures have no mip chain.
	 */
	private static SamplerState customState(CustomTextureTable.Entry custom, boolean compare) {
		ProgramRequest.Filter mip = custom.source() == CustomTextureTable.Source.ATLAS ? ProgramRequest.Filter.LINEAR : null;
		return new SamplerState(custom.filter(), mip, custom.wrap(), compare, custom.dim() == CustomTextureTable.Dim.RECT);
	}

	/**
	 * One program as the engine asks the translator to build it, or null when its stage files are not stages the
	 * translator takes (the program counts as absent; the reason goes into the load line). {@code writtenBefore} names the
	 * targets earlier programs of its stage wrote (DimensionSet#writtenBefore): a stage's 2D binding on such a target's
	 * sampler gives way to the target ({@link CustomTextureTable#targetReplaces}).
	 */
	public static @Nullable ProgramRequest fullScreen(ResolvedProgram program, PackModel model, CustomUniforms custom,
			Map<Integer, String> colorFormats, Map<Integer, Boolean> mipmaps, ShadowTargets shadowLayout,
			Set<String> writtenBefore) {
		return fullScreen(program, renderFiles(program.stages()), ProgramRequest.Family.FULL_SCREEN, model, custom,
			colorFormats, mipmaps, shadowLayout, writtenBefore);
	}

	/**
	 * Declares the Distant Horizons block ({@code QsDh}, plan.md E7) in a program's blocks. Every program may read it, with
	 * Distant Horizons or without: Complementary reads dhRenderDistance in all of its programs, and Bliss 2.1.2 reads
	 * dhNearPlane, dhFarPlane and dhProjection in its deferred and composite passes without DH, where Iris gives them values
	 * (FrameUniforms: both planes 0.01, the gbuffer projection; a zero would divide zero by zero in Bliss's depth helpers).
	 */
	private static void addDistantBlock(List<UniformBlock> blocks) {
		blocks.add(DistantUniforms.block(EngineSlots.DISTANT_HORIZONS));
	}

	/**
	 * One compute kernel of a program (E6): its {@code .csh} file as the only stage, with the resources and the engine's
	 * schema of a full-screen pass (the same samplers, images, buffers and blocks, and the same {@code writtenBefore}). A
	 * kernel writes no colour target; its work groups and local size are its own (see {@code ComputeKernel}).
	 */
	public static @Nullable ProgramRequest compute(ResolvedProgram program, StageFile kernel, PackModel model,
			CustomUniforms custom, Map<Integer, String> colorFormats, Map<Integer, Boolean> mipmaps,
			ShadowTargets shadowLayout, Set<String> writtenBefore) {
		return fullScreen(program, List.of(kernel), ProgramRequest.Family.COMPUTE, model, custom, colorFormats, mipmaps,
			shadowLayout, writtenBefore);
	}

	/** The stages the full-screen family runs: vertex and fragment (a program's compute kernels are {@link #kernelFiles}). */
	static List<StageFile> renderFiles(List<StageFile> stages) {
		return stages.stream().filter(f -> f.stage() == 'v' || f.stage() == 'f').toList();
	}

	/** The compute kernels of a program, in dispatch order: its {@code .csh} stages (the pack catalog sorts them by suffix). */
	public static List<StageFile> kernelFiles(ResolvedProgram program) {
		return program.stages().stream().filter(f -> f.stage() == 'c').toList();
	}

	/** Whether a program has its own vertex or fragment stage (so it has a render pipeline). */
	public static boolean hasRenderStages(ResolvedProgram program) {
		return !renderFiles(program.stages()).isEmpty();
	}

	private static @Nullable ProgramRequest fullScreen(ResolvedProgram program, List<StageFile> files,
			ProgramRequest.Family family, PackModel model, CustomUniforms custom, Map<Integer, String> colorFormats,
			Map<Integer, Boolean> mipmaps, ShadowTargets shadowLayout, Set<String> writtenBefore) {
		List<StageSource> stages = new ArrayList<>();
		String all = "";
		for (StageFile file : files) {
			Stage stage = switch (file.stage()) {
				case 'v' -> family == ProgramRequest.Family.COMPUTE ? null : Stage.VERTEX;
				case 'f' -> family == ProgramRequest.Family.COMPUTE ? null : Stage.FRAGMENT;
				case 'c' -> family == ProgramRequest.Family.COMPUTE ? Stage.COMPUTE : null;
				default -> null;
			};
			if (stage == null) {
				return null;
			}
			String text = shadowLayout.rewriteDeclarations(file.text());
			all += "\n" + text;
			stages.add(new StageSource(stage, text, linesOf(file.path(), text)));
		}
		if (stages.isEmpty()) {
			return null;
		}
		List<Integer> writes = program.directives().map(ProgramDirectives::writes).orElse(List.of());
		List<ColorTarget> outputs = new ArrayList<>();
		if (family == ProgramRequest.Family.COMPUTE) {
			// A compute kernel writes no colour target: its images and buffers are its outputs.
		} else if (program.definition().phase() == dev.mcms.shaders.pack.ProgramCatalog.Phase.FINAL) {
			// final renders to the screen: one output, the main colour (attachment 0 redirects there; EngineFrame).
			// Even without a DRAWBUFFERS comment it never writes the pack's intermediate targets, or the GL 1.20
			// gl_FragColor-to-every-draw-buffer broadcast would smear the picture into them and a Clear=false target
			// would lose what an earlier frame wrote (the documented final behavior: it draws the screen).
			// The main colour target is RGBA: four components, whatever format colortex0 itself carries (the E3
			// review: the emitter truncated the screen value to colortex0's component count).
			outputs.add(new ColorTarget(0, 0, 4));
		} else {
			for (int i = 0; i < writes.size(); i++) {
				int target = writes.get(i);
				outputs.add(new ColorTarget(i, target,
					TargetFormats.of(colorFormats.get(target), target).outputComponents()));
			}
		}
		Matcher sm = RESOURCE_DECL.matcher(COMMENTS.matcher(all).replaceAll(" "));
		List<TextureBinding> textures = new ArrayList<>();
		List<ProgramRequest.ImageBinding> images = new ArrayList<>();
		int slot = 0;
		Set<String> seen = new HashSet<>();
		while (sm.find()) {
			String type = sm.group(1);
			for (String declarator : sm.group(2).split(",")) {
				String name = declarator.trim();
				if (name.isEmpty() || !seen.add(name)) {
					continue;
				}
				if (type.contains("image")) {
					images.add(new ProgramRequest.ImageBinding(name, slot++));
					continue;
				}
				boolean shadow = type.endsWith("Shadow");
				TextureKind kind = shadow ? TextureKind.COMPARE : type.contains("3D") ? TextureKind.THREE_D
					: type.startsWith("isampler") || type.startsWith("usampler") ? TextureKind.INTEGER
					: type.contains("Buffer") ? TextureKind.BUFFER : type.contains("Cube") ? TextureKind.CUBE
					: type.contains("Array") ? TextureKind.ARRAY : TextureKind.COLOUR;
				int target = targetIndex(name);
				// The engine's sampler state for a target: nearest when the pack reads it as texelFetch only is not
				// knowable here; linear/clamp matches the corpus requests (E2's contract). A target whose chain the dimension enables
				// (<target>MipmapEnabled in any program) is sampled with a linear mip filter by every program, so texture2DLod and
				// textureLod reach its levels (OptiFine shaders.txt: mipmapped buffers; without a mip filter Metal reads level 0
				// only). A program that does not declare it gets its implicit-LOD lookups from implicitSamplerOf.
				boolean mipmapped = target >= 0 && Boolean.TRUE.equals(mipmaps.get(target));
				// E6-B: a custom texture bound to this sampler in this program sets its filter and wrap (CustomTextureTable),
				// unless the binding gives way to a target an earlier program of the stage wrote (E7, mcms-texover).
				CustomTextureTable.Entry texture = model.textures().resolve(program.name(), name, CustomTextureTable.dimOf(type));
				if (texture != null && CustomTextureTable.targetReplaces(texture, name, writtenBefore)) {
					texture = null;
				}
				SamplerState state = texture != null ? customState(texture, shadow)
					: SamplerSources.of(name) == SamplerSources.Source.SHADOW
					? shadowState(name, type, shadowLayout)
					: targetState(name, type, mipmapped, shadow);
				textures.add(new TextureBinding(name, slot++, kind, target, state, -1,
					texture == null ? implicitSamplerOf(program, target, state, mipmaps) : null));
			}
		}
		BufferSlots.Assignment slots = BufferSlots.assign(all, EngineSlots.PACK_BLOCKS);
		if (slots.error() != null) {
			LOG.warn("[Quicksilver] program {} is refused: {}", program.name(), slots.error());
			return null;
		}
		List<ProgramRequest.BufferBinding> buffers = slots.buffers();
		List<UniformBlock> blocks = new ArrayList<>();
		blocks.add(FrameUniforms.schema(FRAME_SLOT));
		if (custom != null && custom.size() > 0) {
			blocks.add(custom.schema(CUSTOM_SLOT));
		}
		addDistantBlock(blocks);
		blocks.add(PASS_BLOCK);
		ShaderProperties.AlphaTest configured = model.properties().alphaTests().get(program.name());
		return new ProgramRequest(program.name(), family, stages,
			family == ProgramRequest.Family.COMPUTE ? null : ProgramRequest.VertexLayout.fullScreenQuad(), outputs, textures,
			images, buffers,
			new UniformSchema(List.copyOf(blocks)), null, false);
	}

	/**
	 * One gbuffers program as the engine asks the translator to build it (plan.md E4): the geometry's vertex layout
	 * (a game format through {@link dev.mcms.shaders.runtime.gbuffers.VertexLayoutRegistry}, or Sodium's extended
	 * terrain format with its prologue), the colour attachments of its outputs at the phase layout's attachment
	 * indexes, and the engine's schema with the per-draw block first (the class comment of {@link #DRAW_BLOCK}). The
	 * game's own samplers ({@code texture}, its gbuffers aliases {@code gtexture} and {@code tex}, and {@code lightmap})
	 * take sampler slots from {@link #GAME_SAMPLER_BASE} (SamplerSources); the pack's targets and noisetex keep constexpr samplers as in the
	 * full-screen family.
	 *
	 * @param attachmentOf the phase layout's attachment index of each written target
	 * @param drawsPoints  the pipeline draws points ({@code gl_PointSize})
	 */
	public static @Nullable ProgramRequest gbuffers(ResolvedProgram program, PackModel model, CustomUniforms custom,
			Map<Integer, String> colorFormats, Map<Integer, Boolean> mipmaps, ProgramRequest.VertexLayout layout,
			java.util.function.IntUnaryOperator attachmentOf, boolean drawsPoints, ShadowTargets shadowLayout) {
		return gbuffers(program, model, custom, colorFormats, mipmaps, layout, attachmentOf, drawsPoints, null,
			shadowLayout);
	}

	/** The clouds spelling: {@code clouds} is null for geometry with a vertex format, and the prologue pulls the
	 * game's own clouds vertex data for a layout without attributes (CloudsLayout). */
	public static @Nullable ProgramRequest gbuffers(ResolvedProgram program, PackModel model, CustomUniforms custom,
			Map<Integer, String> colorFormats, Map<Integer, Boolean> mipmaps, ProgramRequest.VertexLayout layout,
			java.util.function.IntUnaryOperator attachmentOf, boolean drawsPoints,
			ProgramRequest.CloudsPrologue clouds, ShadowTargets shadowLayout) {
		return request(ProgramRequest.Family.GBUFFERS, program, model, custom, colorFormats, mipmaps, layout, attachmentOf,
			drawsPoints, clouds, shadowLayout);
	}

	/**
	 * One shadow program ({@code shadow}, {@code shadow_solid}, ... E5) as the engine asks the translator to build it: the
	 * family {@link ProgramRequest.Family#SHADOW}, its outputs at the shadow layout's attachments with the formats of the
	 * {@code shadowcolor} targets ({@code shadowFormats}, the union's by index), and the same samplers and blocks as a
	 * gbuffers program. Shadow outputs never read the colortex formats: their indices are {@code shadowcolor} indices.
	 */
	public static @Nullable ProgramRequest shadow(ResolvedProgram program, PackModel model, CustomUniforms custom,
			Map<Integer, String> shadowFormats, ProgramRequest.VertexLayout layout,
			java.util.function.IntUnaryOperator attachmentOf, boolean drawsPoints, ShadowTargets shadowLayout) {
		return request(ProgramRequest.Family.SHADOW, program, model, custom, shadowFormats, Map.of(), layout, attachmentOf,
			drawsPoints, null, shadowLayout);
	}

	/**
	 * The request with its texture indices fitted to Metal's table (ProgramRequest.MAX_TEXTURE_INDICES; a clouds program keeps the
	 * top one for its CloudFaces, EngineSlots.CLOUD_FACES). A program declares every resource name its stages mention, and a
	 * program that reads only some of them can declare more than the table holds (Complementary's shadow programs declare 34
	 * names and read 10). Such a request is translated once to learn which resources the code uses; the unused ones take no
	 * index, and the used ones are renumbered in the request's order, the overlay after them. A request still past the limit is
	 * refused, logged with its name. A request within the limit is returned as it is.
	 */
	public static @Nullable ProgramRequest fitTextureIndices(@Nullable ProgramRequest request) {
		if (request == null) {
			return null;
		}
		int limit = request.cloudsPrologue() != null ? EngineSlots.CLOUD_FACES : ProgramRequest.MAX_TEXTURE_INDICES;
		int declared = request.textures().size() + request.images().size() + (request.overlayColor() == null ? 0 : 1);
		if (declared <= limit) {
			return request;
		}
		ProgramTranslation translation = Translator.translate(request);
		if (!translation.ok()) {
			return request;  // the caller reports the translation's diagnostics
		}
		Set<String> used = new HashSet<>();
		for (ProgramTranslation.Reflection.ResourceUse use : translation.reflection().textures()) {
			used.add(use.name());
		}
		for (ProgramTranslation.Reflection.ResourceUse use : translation.reflection().images()) {
			used.add(use.name());
		}
		List<TextureBinding> textures = new ArrayList<>();
		List<ProgramRequest.ImageBinding> images = new ArrayList<>();
		int slot = 0;
		for (TextureBinding texture : request.textures()) {
			if (used.contains(texture.name())) {
				textures.add(new TextureBinding(texture.name(), slot++, texture.kind(), texture.target(), texture.sampler(),
					texture.samplerSlot(), texture.implicitSampler(), texture.lodBias()));
			}
		}
		for (ProgramRequest.ImageBinding image : request.images()) {
			if (used.contains(image.name())) {
				images.add(new ProgramRequest.ImageBinding(image.name(), slot++));
			}
		}
		ProgramRequest.OverlayColor overlay = request.overlayColor() == null ? null
			: new ProgramRequest.OverlayColor(slot++);
		if (slot > limit) {
			LOG.warn("[Quicksilver] program {} is refused: it reads {} textures, images and the entity overlay, and this draw binds at most"
				+ " {} texture indices (0 to {})", request.name(), slot, limit, limit - 1);
			return null;
		}
		return new ProgramRequest(request.name(), request.family(), request.stages(), request.vertexLayout(), request.outputs(),
			textures, images, request.buffers(), request.uniforms(), request.alphaTest(), request.drawsPoints(),
			request.perVertexIds(), overlay, request.cloudsPrologue());
	}
	/**
	 * The request of a gbuffers or shadow program: {@code family} names which, and {@code colorFormats} its outputs'
	 * targets. The shadow layout gives the sampler states of the shadow maps a program reads (E5).
	 */
	private static @Nullable ProgramRequest request(ProgramRequest.Family family, ResolvedProgram program, PackModel model,
			CustomUniforms custom, Map<Integer, String> colorFormats, Map<Integer, Boolean> mipmaps,
			ProgramRequest.VertexLayout layout, java.util.function.IntUnaryOperator attachmentOf, boolean drawsPoints,
			ProgramRequest.CloudsPrologue clouds, ShadowTargets shadowLayout) {
		List<StageSource> stages = new ArrayList<>();
		StringBuilder all = new StringBuilder();
		for (StageFile file : program.stages()) {
			Stage stage = switch (file.stage()) {
				case 'v' -> Stage.VERTEX;
				case 'f' -> Stage.FRAGMENT;
				default -> null;
			};
			if (stage == null) {
				return null;
			}
			String text = shadowLayout.rewriteDeclarations(file.text());
			all.append('\n').append(text);
			stages.add(new StageSource(stage, text, linesOf(file.path(), text)));
		}
		if (stages.isEmpty()) {
			return null;
		}
		List<Integer> writes = program.directives().map(ProgramDirectives::writes).orElse(List.of());
		List<ColorTarget> outputs = new ArrayList<>();
		if (writes.isEmpty()) {
			// No DRAWBUFFERS: gl_FragColor's documented target, attachment 0 (colortex0 is there in every layout).
			outputs.add(new ColorTarget(0, 0, TargetFormats.of(colorFormats.get(0), 0).outputComponents()));
		} else {
			for (int target : writes) {
				outputs.add(new ColorTarget(attachmentOf.applyAsInt(target), target,
					TargetFormats.of(colorFormats.get(target), target).outputComponents()));
			}
		}
		Matcher sm = RESOURCE_DECL.matcher(COMMENTS.matcher(all).replaceAll(" "));
		List<TextureBinding> textures = new ArrayList<>();
		List<ProgramRequest.ImageBinding> images = new ArrayList<>();
		int slot = 0, samplerSlot = GAME_SAMPLER_BASE;
		Set<String> seen = new HashSet<>();
		while (sm.find()) {
			String type = sm.group(1);
			for (String declarator : sm.group(2).split(",")) {
				String name = declarator.trim();
				if (name.isEmpty() || !seen.add(name)) {
					continue;
				}
				if (type.contains("image")) {
					images.add(new ProgramRequest.ImageBinding(name, slot++));
					continue;
				}
				int target = targetIndex(name);
				SamplerSources.Source source = SamplerSources.of(name);
				boolean game = source == SamplerSources.Source.ATLAS || source == SamplerSources.Source.LIGHTMAP;
				if (game) {
					// The game's atlas and lightmap: the engine binds the views the game bound (Sampler0/Sampler2). The atlas's
					// gbuffers names are texture, gtexture and tex (SamplerSources): the delegate binds the game's atlas to
					// the program's first atlas name and mirrors it to the others. E6-B: a custom texture that names the
					// sampler replaces the game's binding here (no game sampler slot; the delegate binds the custom texture).
					CustomTextureTable.Entry over = model.textures().resolve(program.name(), name, CustomTextureTable.dimOf(type));
					if (over != null) {
						textures.add(new TextureBinding(name, slot++, TextureKind.COLOUR, -1, customState(over, false), -1));
					} else {
						// ADR 0008: the atlas's implicit-LOD lookups in the world's programs take the render scale's LOD bias (the
						// lightmap has no mips; a shadow program draws at the shadow map's own resolution, which no scale touches).
						TextureBinding atlas = new TextureBinding(name, slot++, TextureKind.COLOUR, -1, null, samplerSlot++);
						boolean biased = family == ProgramRequest.Family.GBUFFERS && source == SamplerSources.Source.ATLAS;
						textures.add(biased ? atlas.withLodBias() : atlas);
					}
					continue;
				}
				boolean shadow = type.endsWith("Shadow");
				// E6-B: a custom texture bound to this sampler in this program sets its state, and the kind then comes from the GLSL
				// type: a custom texture on a depthtex name is an ordinary texture of its type, not the depth map.
				CustomTextureTable.Entry texture = model.textures().resolve(program.name(), name, CustomTextureTable.dimOf(type));
				TextureKind kind = gbuffersKind(name, type, shadow, texture != null);
				boolean mipmapped = target >= 0 && Boolean.TRUE.equals(mipmaps.get(target));
				SamplerState state = texture != null ? customState(texture, shadow)
					: SamplerSources.of(name) == SamplerSources.Source.SHADOW
					? shadowState(name, type, shadowLayout)
					: targetState(name, type, mipmapped, shadow);
				TextureBinding binding = new TextureBinding(name, slot++, kind, target, state, -1,
					texture == null ? implicitSamplerOf(program, target, state, mipmaps) : null);
				// ADR 0008: the atlas-aligned normal and specular maps take the render scale's LOD bias with the atlas.
				boolean atlasAligned = family == ProgramRequest.Family.GBUFFERS && texture == null
					&& (source == SamplerSources.Source.NORMALS || source == SamplerSources.Source.SPECULAR);
				textures.add(atlasAligned ? binding.withLodBias() : binding);
			}
		}
		BufferSlots.Assignment slots = BufferSlots.assign(all, EngineSlots.PACK_BLOCKS);
		if (slots.error() != null) {
			LOG.warn("[Quicksilver] program {} is refused: {}", program.name(), slots.error());
			return null;
		}
		List<ProgramRequest.BufferBinding> buffers = slots.buffers();
		List<UniformBlock> blocks = new ArrayList<>();
		blocks.add(DRAW_BLOCK);  // first: its renderStage and alphaTestRef shadow the frame block's
		blocks.add(FrameUniforms.schema(FRAME_SLOT));
		if (custom != null && custom.size() > 0) {
			blocks.add(custom.schema(CUSTOM_SLOT));
		}
		addDistantBlock(blocks);
		ShaderProperties.AlphaTest configured = model.properties().alphaTestOf(program).orElse(null);
		boolean legacy = all.indexOf("gl_FragColor") >= 0 || all.indexOf("gl_FragData") >= 0;
		ProgramRequest.AlphaTest alphaTest = null;
		if (legacy && (configured == null || !configured.off())) {
			// engine-pack-format.md section 12: the compatibility profile applies the alpha test itself; modern syntax
			// gets only alphaTestRef and discards on its own. The default without a configured alphaTest.<program> is
			// the documented check itself (OptiFine shaders.txt: "the check is \"if (color.a < alphaTestRef) discard;\""):
			// alpha passes at or above the reference — GEQUAL, never LESS (LESS passes alpha below the reference and
			// discarded every ordinary fragment: the all-zero colortex4 of the 2026-11-14 classes preview).
			alphaTest = new ProgramRequest.AlphaTest(configured != null ? configured.func() : "GEQUAL", "alphaTestRef");
		}
		ProgramRequest.PerVertexIds perVertexIds = PER_VERTEX_ID_NAMES.matcher(all).find()
			? new ProgramRequest.PerVertexIds(EngineSlots.PER_VERTEX_IDS, DRAW_BLOCK.name(), DRAW_IDS_DEFAULT) : null;
		// The overlay texture takes the next free texture index: the translator binds arg_ovl there (OverlayColor).
		ProgramRequest.OverlayColor overlayColor = OVERLAY_NAME.matcher(all).find()
			? new ProgramRequest.OverlayColor(slot) : null;
		return new ProgramRequest(program.name(), family, stages, layout, outputs, textures,
			images, buffers, new UniformSchema(List.copyOf(blocks)), alphaTest, drawsPoints, perVertexIds, overlayColor,
			clouds);
	}

	/** The colortex index a sampler name reads, or -1 (engine-pack-format.md section 7's aliases). */
	public static int targetIndex(String name) {
		// Digits read in place (ColourNames): the binds call this per sampler, and a substring or parseInt would allocate.
		return dev.mcms.shaders.pack.ColourNames.index(name);
	}

	/** Line numbers of a preprocessed stage: the pack file and the preprocessed line (the corpus harness's rule). */
	static List<SourceLine> linesOf(String path, String text) {
		int n = text.split("\n", -1).length;
		return IntStream.rangeClosed(1, n).mapToObj(i -> new SourceLine(path, i)).toList();
	}
}
