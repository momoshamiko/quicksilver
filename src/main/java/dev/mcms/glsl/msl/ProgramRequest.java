// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.msl;
import dev.mcms.glsl.sema.SourceLine;
import dev.mcms.glsl.sema.Stage;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One program as the engine asks the translator to build it (ADR 0006 section 2): the stages' preprocessed text with
 * the preprocessor's line maps, the program family, the vertex input layout the draws provide, the colour attachments
 * of the program's outputs, the texture, image and buffer slots, the uniform schema and the compatibility options.
 *
 * <p>Every slot is decided by the caller. The translator never assigns one; a name without a slot is a diagnostic.
 *
 * @param name the program's name for diagnostics ({@code gbuffers_terrain}); it names no MSL symbol
 * @param family the program family; a compute family takes exactly one compute stage
 * @param stages the vertex and fragment stages, or one compute stage
 * @param vertexLayout the attributes the draw provides (null for compute and for fragment-only use)
 * @param outputs one entry per fragment output in output order (DRAWBUFFERS order): where its value goes
 * @param textures every sampler and texel buffer the program may use, by GLSL name
 * @param images every image the program may use, by GLSL name
 * @param buffers every uniform or buffer block the program declares that the engine binds itself, by block name
 * @param uniforms the engine's uniform blocks and the fields they serve, by GLSL uniform name
 * @param alphaTest the compatibility alpha test of the program, or null when the program has none
 * @param drawsPoints true when the pipeline draws points, so {@code gl_PointSize} is written
 * @param perVertexIds the per-vertex id input, or null when the ids come from the uniform schema
 * @param overlayColor the entity overlay input, or null when {@code entityColor} comes from the uniform schema
 * @param cloudsPrologue the game's clouds vertex pulling, or null for every program that is not the clouds
 */
public record ProgramRequest(String name, Family family, List<StageSource> stages, VertexLayout vertexLayout,
		List<ColorTarget> outputs, List<TextureBinding> textures, List<ImageBinding> images,
		List<BufferBinding> buffers, UniformSchema uniforms, AlphaTest alphaTest, boolean drawsPoints,
		PerVertexIds perVertexIds, OverlayColor overlayColor, CloudsPrologue cloudsPrologue) {

	/**
	 * The eleven-argument form every request without the per-vertex id and overlay inputs uses (both fields are null
	 * and nothing in the translation changes).
	 */
	public ProgramRequest(String name, Family family, List<StageSource> stages, VertexLayout vertexLayout,
			List<ColorTarget> outputs, List<TextureBinding> textures, List<ImageBinding> images,
			List<BufferBinding> buffers, UniformSchema uniforms, AlphaTest alphaTest, boolean drawsPoints) {
		this(name, family, stages, vertexLayout, outputs, textures, images, buffers, uniforms, alphaTest, drawsPoints,
			null, null);
	}

	/**
	 * The twelve-argument form every request without the overlay input uses (the field is null and nothing in the
	 * translation changes).
	 */
	public ProgramRequest(String name, Family family, List<StageSource> stages, VertexLayout vertexLayout,
			List<ColorTarget> outputs, List<TextureBinding> textures, List<ImageBinding> images,
			List<BufferBinding> buffers, UniformSchema uniforms, AlphaTest alphaTest, boolean drawsPoints,
			PerVertexIds perVertexIds) {
		this(name, family, stages, vertexLayout, outputs, textures, images, buffers, uniforms, alphaTest, drawsPoints,
			perVertexIds, null);
	}

	/**
	 * The thirteen-argument form every request without the clouds prologue uses (the field is null and nothing in the
	 * translation changes).
	 */
	public ProgramRequest(String name, Family family, List<StageSource> stages, VertexLayout vertexLayout,
			List<ColorTarget> outputs, List<TextureBinding> textures, List<ImageBinding> images,
			List<BufferBinding> buffers, UniformSchema uniforms, AlphaTest alphaTest, boolean drawsPoints,
			PerVertexIds perVertexIds, OverlayColor overlayColor) {
		this(name, family, stages, vertexLayout, outputs, textures, images, buffers, uniforms, alphaTest, drawsPoints,
			perVertexIds, overlayColor, null);
	}


	/** Which kind of program this is (ADR 0006 section 2). */
	public enum Family { GBUFFERS, SHADOW, FULL_SCREEN, COMPUTE, DISTANT_HORIZONS }

	public ProgramRequest {
		stages = List.copyOf(stages);
		outputs = List.copyOf(outputs);
		textures = List.copyOf(textures);
		images = List.copyOf(images);
		buffers = List.copyOf(buffers);
	}

	/** One stage of the program: its preprocessed text and where each of its lines came from. */
	public record StageSource(Stage stage, String text, List<SourceLine> lineMap) {
		public StageSource {
			lineMap = List.copyOf(lineMap);
		}
	}

	/**
	 * The attributes the draw provides. Each attribute names the semantic it carries (see {@link Semantic}), the Metal
	 * vertex format (for example {@code float3}, {@code uchar4Normalized}, {@code short2}), its buffer and its byte
	 * offset in that buffer. The attribute's index is its position in the list ({@code [[attribute(n)]]}).
	 * {@code fullScreen} replaces the attributes with the full-screen quad (two triangles) that vertex ids generate. 2026-11-14
	 * (E4, ADR 0006): an attribute may name its own MSL decode expression (it sees {@code qs_a<n>}, the stage-in
	 * value; Sodium's packed terrain decodes per attribute) — null, the default, keeps the standard conversion the
	 * format's type names.
	 */
	public record VertexLayout(List<VertexAttribute> attributes, boolean fullScreen, @Nullable VertexPrologue prologue) {
		public VertexLayout(List<VertexAttribute> attributes, boolean fullScreen) {
			this(attributes, fullScreen, null);
		}

		public VertexLayout {
			attributes = List.copyOf(attributes);
		}

		public static VertexLayout of(VertexAttribute... attributes) {
			return new VertexLayout(List.of(attributes), false, null);
		}

		public static VertexLayout fullScreenQuad() {
			return new VertexLayout(List.of(), true, null);
		}
	}

	/**
	 * 2026-11-14 (E4, ADR 0007): what the vertex prologue adds around the decoded position of a layout whose geometry
	 * arrives relative to a region (Sodium's terrain): the region offset read from the block at {@code regionSlot}
	 * (byte {@code regionOffset}, a {@code vec3}) is added and the {@code vec3} at {@code cameraOffsetAt} of the block
	 * at {@code globalsSlot} is subtracted, each only while its slot is not -1. The engine converts its
	 * runtime-side facts into this record; the buffers at the slots are the engine's to bind.
	 */
	public record VertexPrologue(int regionSlot, int regionOffset, int globalsSlot, long cameraOffsetAt) {
	}

	/**
	 * One attribute of the vertex input layout. 2026-11-14 (E4, ADR 0006): {@code decode} is an MSL expression over
	 * {@code qs_a<n>} that produces the semantic's GLSL-typed value, for attributes whose packed encoding needs more
	 * than the format's standard conversion (E4-B's Sodium terrain); null decodes the standard way.
	 */
	public record VertexAttribute(Semantic semantic, String format, int buffer, int offset,
			@Nullable String decode) {
		public VertexAttribute(Semantic semantic, String format, int buffer, int offset) {
			this(semantic, format, buffer, offset, null);
		}
	}

	/**
	 * What an attribute carries. The GLSL names it serves are listed by the translator's vertex prologue: the legacy
	 * names (gl_Vertex, gl_Normal, gl_Color, gl_MultiTexCoord0, gl_MultiTexCoord1, gl_MultiTexCoord2) and the 1.17+ names
	 * (vaPosition, vaColor, vaUV0, vaUV1, vaUV2, vaNormal) plus the extension attributes (mc_Entity, mc_midTexCoord,
	 * at_tangent, at_midBlock, at_velocity). Distant Horizons' dhMaterialId is DH_MATERIAL_ID (engine-pack-format.md 17).
	 * 2026-11-14 (E4): LINE_WIDTH names the outline formats' line-width float (engine-vanilla-frame.md section 2),
	 * which the engine's line-widening epilogue reads.
	 */
	public enum Semantic {
		POSITION, COLOR, NORMAL, TEXCOORD0, TEXCOORD2, OVERLAY, LIGHTMAP, ENTITY, MID_TEXCOORD, TANGENT, MID_BLOCK, VELOCITY,
		DH_MATERIAL_ID, LINE_WIDTH
	}

	/**
	 * One fragment output: the colour attachment it writes ({@code [[color(attachment)]]}), the colour texture index it
	 * is (colortex number, or -1 for none), and how many components the attachment's pixel format has. The output is
	 * padded or truncated to those components.
	 */
	public record ColorTarget(int attachment, int target, int components) {
		public ColorTarget {
			if (components < 1 || components > 4) {
				throw new IllegalArgumentException("components " + components);
			}
		}
	}

	/** The texture kinds the engine plans for a sampler (ADR 0006 section 3). */
	public enum TextureKind { COLOUR, DEPTH, COMPARE, INTEGER, THREE_D, ARRAY, CUBE, BUFFER }

	/** How a constexpr sampler filters and wraps. */
	public enum Filter { NEAREST, LINEAR }

	/** Address mode of a constexpr sampler. */
	public enum Wrap { REPEAT, CLAMP_TO_EDGE, MIRROR_REPEAT }

	/**
	 * A constexpr sampler the program's pack texture uses. {@code mip} is null for no mip filtering. {@code compare}
	 * makes it a comparison sampler ({@code compare_func::less_equal}) for shadow textures. {@code pixel} takes
	 * texel coordinates (coord::pixel) for a rectangle texture: no mipmaps, and clamp-to-edge addressing only.
	 */
	public record SamplerState(Filter filter, Filter mip, Wrap wrap, boolean compare, boolean pixel) {
		public static SamplerState linear() {
			return new SamplerState(Filter.LINEAR, null, Wrap.CLAMP_TO_EDGE, false, false);
		}
	}

	/**
	 * One sampler name of the program. It is either a constexpr sampler ({@code sampler} set) for a pack texture, or the
	 * game's own sampler (block atlas, lightmap), marked by a {@code samplerSlot} of 0 or more: that ordinal counts the game
	 * samplers of the program and only says which are game samplers. The index a stage's MSL declares for the sampler is the
	 * reflection's per stage ({@code ProgramTranslation.Reflection.samplerSlot}), which may differ between the stages.
	 * {@code target} is the colortex index it reads when the texture is a render target (or -1): the translator reports the
	 * targets a program both reads and writes. {@code implicitSampler} is the sampler of the lookups with no explicit level
	 * or gradient (texture, textureProj, textureOffset) when it differs from {@code sampler}: the hybrid mip rule of E6 gives
	 * a program that does not declare a mipped target's chain a sampler with no mip filter for them (they read level 0),
	 * while its explicit-level and gradient lookups keep {@code sampler}. Null: every lookup takes {@code sampler}.
	 * {@code lodBias} (ADR 0008): the fragment stage's implicit-LOD lookups of this texture through the uniform add the
	 * engine's LOD bias, the block field {@link #LOD_BIAS_FIELD}, to their own (the render scale's sharper mip choice).
	 */
	public record TextureBinding(String name, int slot, TextureKind kind, int target, SamplerState sampler,
			int samplerSlot, @Nullable SamplerState implicitSampler, boolean lodBias) {
		/** A binding whose every lookup takes {@code sampler}. */
		public TextureBinding(String name, int slot, TextureKind kind, int target, SamplerState sampler, int samplerSlot) {
			this(name, slot, kind, target, sampler, samplerSlot, null, false);
		}

		/** A binding without the engine's LOD bias. */
		public TextureBinding(String name, int slot, TextureKind kind, int target, SamplerState sampler, int samplerSlot,
				@Nullable SamplerState implicitSampler) {
			this(name, slot, kind, target, sampler, samplerSlot, implicitSampler, false);
		}

		public static TextureBinding constexpr(String name, int slot, TextureKind kind, SamplerState state) {
			return new TextureBinding(name, slot, kind, -1, state, -1);
		}

		/** The same binding with the engine's LOD bias on its implicit-LOD lookups (ADR 0008). */
		public TextureBinding withLodBias() {
			return new TextureBinding(this.name, this.slot, this.kind, this.target, this.sampler, this.samplerSlot,
				this.implicitSampler, true);
		}
	}

	/**
	 * The engine block field that holds the LOD bias of the render scale (ADR 0008): a float, log2(1 / scale), 0 at the window
	 * size. A reserved name: no pack uniform takes it.
	 */
	public static final String LOD_BIAS_FIELD = "qs_lodBias";

	/** One image name of the program, bound at {@code [[texture(slot)]]}. */
	public record ImageBinding(String name, int slot) {
	}

	/**
	 * The most buffer bindings (pack uniform and buffer blocks) a request may carry: indices 0 to this minus one, that is 0 to 18. The
	 * runtime's EngineSlots.PACK_BLOCKS is the same number, and the engine's Distant Horizons block takes index 19 (EngineSlots).
	 */
	public static final int MAX_BUFFER_BINDINGS = 19;
	/**
	 * The texture indices a program's textures, images and overlay take: 0 to this minus one, Metal's texture argument table
	 * (TextureIndexRangeGpuTest: index 30 binds, 31 is refused). A request that needs more is refused at load.
	 */
	public static final int MAX_TEXTURE_INDICES = 31;

	/**
	 * The buffer index a compute kernel's spilled shared arrays bind at (plan.md E6): one fixed index that no pack block can take,
	 * above the pack's indices and the Distant Horizons block. It is also the per-vertex id slot of the render stages (EngineSlots.PER_VERTEX_IDS),
	 * which a compute kernel never binds, so the two never meet in one pipeline.
	 */
	public static final int SHARED_SPILL_SLOT = 20;

	/**
	 * One block the engine binds (a uniform or a buffer block of the pack), at {@code [[buffer(slot)]]}. {@code bufferObject} is
	 * the index of the pack's {@code bufferObject.N} a shader storage buffer block binds (E6-B): its layout binding N, or 0 for
	 * a buffer block with no binding (the GL default); -1 for a uniform block, which the engine serves by name. The Metal slot
	 * and the bufferObject index are separate numbers (BufferSlots assigns the slot).
	 */
	public record BufferBinding(String name, int slot, int bufferObject) {
		/** A uniform block (no storage buffer). */
		public BufferBinding(String name, int slot) {
			this(name, slot, -1);
		}

		/** Whether the block is a shader storage buffer (the engine binds its bufferObject). */
		public boolean storage() {
			return this.bufferObject >= 0;
		}
	}

	/**
	 * The engine's uniform blocks. Each block is bound at its slot; the uniforms it serves are its fields, read by the
	 * GLSL name of the field. A uniform no block serves reads as zero and is listed by name in the reflection.
	 */
	public record UniformSchema(List<UniformBlock> blocks) {
		public UniformSchema {
			blocks = List.copyOf(blocks);
		}

		public static UniformSchema none() {
			return new UniformSchema(List.of());
		}
	}

	/** One engine block: its name, slot and the byte offset of each field (the engine owns the layout). */
	public record UniformBlock(String name, int slot, List<UniformField> fields) {
		public UniformBlock {
			fields = List.copyOf(fields);
		}
	}

	/** One field of an engine block: GLSL name, GLSL type word (for example {@code vec4}) and byte offset. */
	public record UniformField(String name, String type, int offset) {
	}

	/**
	 * The compatibility alpha test: fragment output 0's alpha compared with the uniform {@code refUniform} by the GLSL
	 * test word ({@code NEVER}, {@code LESS}, {@code EQUAL}, {@code LEQUAL}, {@code GREATER}, {@code NOTEQUAL},
	 * {@code GEQUAL}, {@code ALWAYS}). A failing fragment is discarded.
	 */
	public record AlphaTest(String func, String refUniform) {
	}

	/**
	 * The per-vertex id input (plan.md E4): one {@link PerVertexIds} record per vertex of the prepared frame at the
	 * vertex-stage {@code [[buffer(slot)]]}, twelve bytes a vertex (three ints). While the request carries it, the
	 * uniforms {@code entityId}, {@code blockEntityId} and {@code currentRenderedItemId} (engine-pack-format.md 10)
	 * are served from the record the draw's {@code [[vertex_id]]} selects instead of an engine block, and reach the
	 * fragment stage as flat varyings. For an indexed draw Metal's {@code vertex_id} runs from the draw's base vertex
	 * through base vertex + index count - 1 (Apple's drawIndexedPrimitives doc, baseVertex), which over the game's
	 * shared sequential index buffer is the vertex's own index plus base vertex (engine-entity-draws.md 1); null (the
	 * default) leaves the three uniforms to the uniform schema.
	 *
	 * <p>A draw with no records binds a one-record defaults buffer instead: the vertex stage then reads record 0. The
	 * engine block {@code defaultsBlock} carries the flag {@code defaultsMember}, non-zero for such a draw, and the
	 * prologue selects index 0 while it is. Both are null when the program reads its records unconditionally.
	 */
	public record PerVertexIds(int slot, @Nullable String defaultsBlock, @Nullable String defaultsMember) {
	}
	/**
	 * The entity overlay input (plan.md E4, "Per-entity values"): while the request carries it, the uniform
	 * {@code entityColor} (engine-pack-format.md section 10) is served per vertex instead of from an engine block.
	 * The vertex prologue fetches the overlay texture at the request's {@code textureSlot} with the overlay
	 * attribute's {@code vaUV1} coordinates (the vanilla {@code entity.vsh} does {@code texelFetch(Sampler1, UV1, 0)};
	 * engine-entity-draws.md section 2) and passes {@code (overlay.rgb, 1 - overlay.a)} to the fragment stage as a
	 * flat varying: Iris's {@code entityColor} carries the tint in {@code rgb} and the blend factor in {@code a}
	 * ({@code color.rgb = mix(color.rgb, entityColor.rgb, entityColor.a)}; Iris rendering-uniforms page), which is
	 * the vanilla mix {@code mix(overlay.rgb, color.rgb, overlay.a)} rearranged. The engine binds the overlay texture
	 * itself at {@code textureSlot} (a texture index: the overlay is fetched, so it takes no sampler); a pipeline whose layout carries no overlay attribute (semantic {@code OVERLAY})
	 * fetches no texture and reads {@code (0, 0, 0, 0)} — a zero blend factor, so the pack's mix leaves the colour
	 * unchanged. A full-screen or compute request that sets it is refused with diagnostic code {@code overlay-color}.
	 * null (the default) leaves the uniform to the uniform schema, exactly as before this field existed.
	 */
	public record OverlayColor(int textureSlot) {
	}

	/**
	 * The clouds vertex prologue (plan.md E4): the game's clouds pipelines bind no vertex format; its 26.3
	 * {@code clouds.vsh} pulls three texels per quad from the {@code isamplerBuffer} CloudFaces and builds position and
	 * colour from the vertex index (packed bit constants, the CloudInfo block's {@code CellSize} and
	 * {@code CloudOffset}, the six face greys). While the request carries this record, the vertex prologue reproduces
	 * that pulling for a layout with no attributes, so a pack's {@code gbuffers_clouds} reads {@code gl_Vertex},
	 * {@code gl_Color} and {@code gl_Normal} (the face's direction) like any geometry program; the game's clouds carry
	 * no texture coordinates (measured: {@code clouds.vsh} has none), so the texcoord built-ins keep their GL defaults
	 * (engine-pack-format.md section 2). {@code infoSlot} is the buffer index of the game's CloudInfo block (std140:
	 * {@code vec4 CloudColor} at 0, {@code vec3 CloudOffset} at 16, {@code vec3 CellSize} at 32, the layout
	 * Std140Builder writes); {@code facesSlot} is the texture index of the CloudFaces texel buffer, bound as a Metal
	 * {@code texture_buffer<int>} (one R8_SINT texel per byte: the game packs three bytes per face). A request that sets it needs a vertex stage whose
	 * layout has no attributes, is not full-screen and carries no region prologue, or translation fails with
	 * diagnostic code {@code clouds-prologue}.
	 */
	public record CloudsPrologue(int infoSlot, int facesSlot) {
	}
}
