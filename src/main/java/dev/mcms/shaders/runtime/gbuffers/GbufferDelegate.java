// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.gbuffers;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.util.TextureViewAndSampler;
import dev.mcms.glsl.msl.ProgramRequest;
import dev.mcms.glsl.sema.Stage;
import dev.mcms.metal.MetalBridge;
import dev.mcms.metal.MetalHooks;
import dev.mcms.metal.PassDelegate;
import dev.mcms.shaders.runtime.EngineSlots;
import dev.mcms.shaders.runtime.ProgramRequests;
import dev.mcms.shaders.runtime.SamplerSources;
import dev.mcms.shaders.runtime.distant.DistantFrame;
import dev.mcms.shaders.runtime.entities.EntityCapture;
import dev.mcms.shaders.runtime.terrain.TerrainEngine;
import dev.mcms.shaders.runtime.terrain.TerrainVertexType;
import dev.mcms.shaders.runtime.uniforms.Std140;
import dev.mcms.shaders.runtime.uniforms.FrameUniforms;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The delegate that takes over every redirected game pass's pipelines (plan.md E4, "Pass takeover" and "Pipeline
 * mapping"; a {@link PassDelegate} installed by {@link EngineRedirector} while a set is active). {@link #begin} says
 * whether the pass has a depth attachment; {@link #reversedDepth} says which depth convention the pass uses. Per
 * {@link #setPipeline} it resolves the game pipeline's plan ({@link GbufferPlans#planOf}) and the (kind, phase) variant,
 * binds the variant's pipeline state (with or without depth) and depth state and the engine's blocks and textures; per
 * draw ({@link #beforeDraw}) it writes the draw's {@code QsDraw} block from the game's {@code DynamicTransforms} and the
 * projection in force, and binds the per-vertex id side buffer and the constant bytes.
 *
 * <h2>Pack programs</h2>
 * {@code modelViewMatrix} is the game's {@code ModelViewMat} of the draw, read in place (Apple silicon's shared
 * memory: the game's uniform upload is the bytes the GPU reads). A draw with no {@code DynamicTransforms} and no
 * camera-relative vertices gets the identity. Sodium's terrain is the other case: its vertices are camera-relative
 * (the region offset, and on beta.5 the camera offset, are added by the prologue), so its model-view is Sodium's own
 * {@code u_ModelViewMatrix} from the globals mirror, the view rotation alone ({@link PrologueFacts#modelViewAt}).
 * {@code projectionMatrix} is the frame's GL-convention projection ({@link Bindings#projection}), or the hand
 * projection while the kind is {@code HAND}; under the game's reversed convention its z row is negated, so the
 * pack's depth is {@code 1 - d}. The inverses and the normal matrix
 * derive from it. {@code renderStage} and {@code alphaTestRef} come from the variant. {@code colorModulator} is the
 * draw's {@code ColorModulator} (the game's {@code DynamicTransforms} at offset 128, white when the draw has no
 * transforms); the translated {@code gl_Color} carries it (StageEmitter), as the game's own colour shaders do. The bytes
 * go to this delegate's native scratch ({@link MetalBridge#renderBytesAt}): a steady-state draw allocates nothing.
 *
 * <h2>Game-pipeline variants</h2>
 * The game's own shader draws. Its {@code Projection} block (the uniform the game names {@code Projection}) receives the
 * frame's projection converted to Metal's zero-to-one depth range (reversed under the game's convention), per draw, from
 * the same native scratch; its {@code DynamicTransforms} the backend binds as the game set them.
 *
 * <h2>The API the other E4 tasks call</h2>
 * <ul>
 * <li>{@link #passKind(PassKind, Runnable)}: wraps draws in a pass-kind window: E4-C wraps its hand draws (the solid
 * hand at the phase hook, the translucent hand at {@code render3dHud} bytecode 141), E5 the shadow pass. Windows
 * nest; the previous kind returns when one closes. Inside a window every pass the game opens on the main colour is
 * redirected with that kind ({@link EngineRedirector}).
 * <li>{@link VertexLayoutRegistry} ({@link #vertexLayoutFor}): E4-B registers Sodium's extended terrain format there.
 * <li>{@link #publishVertexIds(GpuBuffer, EntityCapture.Ranges, int[])}: E4-D publishes each staged upload's per-vertex ids;
 * a draw binds the records its vertex buffer and stride were uploaded with, or the defaults record ({@link VertexIdBuffers}).
 * <li>{@link #constantBytes(int, MemorySegment)}: E4-B's Sodium {@code u_Globals} mirror (slot 21), copied per draw.
 * <li>{@link #setHandDraws(HandDraws)}: E4-C registers the hand draws the phase hook calls: {@code drawSolidHand}
 * runs between the {@code depthtex2} and {@code depthtex1} copies with the kind already {@code HAND} and the hand
 * projection current, on the reopened game pass; {@code closeHandFrame} closes the hand's frame at the engine's
 * after-level hook when the translucent draw did not run (guards, early exits).
 * <li>{@link #setHandProjection(float[])}: E4-C's hand projection ({@code render3dHud}'s
 * {@code setupPerspective(0.05, depthFar, hudFov, w, h)} scaled by {@code MC_HAND_DEPTH}'s clip-z factor,
 * engine-pack-format.md section 3), column-major in OpenGL's convention. {@code QsDraw.projectionMatrix} serves it
 * while the kind is {@code HAND}.
 * <li>{@link #close()}: releases the native scratch; the engine calls it when the set's delegate is released.
 * </ul>
 */
public final class GbufferDelegate implements PassDelegate {

	private static final Logger LOG = LoggerFactory.getLogger("Quicksilver");

	/** The hand draws E4-C registers; both run only while a set is active. */
	public interface HandDraws {
		/**
		 * Draws the solid hand at the phase hook, on the reopened game pass (its pipelines go through this delegate
		 * with kind {@code HAND}); the hand projection set last is current.
		 *
		 * @param renderPass the game's reopened "Solid" pass object (a {@code RenderPass})
		 */
		void drawSolidHand(Object renderPass);

		/** Closes the hand's prepared frame and buffers (at the after-level hook when the translucent draw did not run). */
		void closeHandFrame();
	}

	/**
	 * What the delegate reads from the engine's current frame (E4Frame's {@code Engine} implements it). Native handles
	 * are Metal's; the holders are the {handle, offset} pairs of transient uniform memory valid for this frame.
	 */
	public interface Bindings {
		/** The frame block ({@code QsFrame}) of a program as {handle, offset}, with the program's alpha-test reference; null while none. */
		long @Nullable [] frameBlock(String program);

		/** The custom block ({@code QsCustom}) as {handle, offset}; null when the pack declares none. */
		long @Nullable [] customBlock();

		/**
		 * The pack's own uniform or buffer block of that name ({@code buffer Foo {...}} declares "Foo") as {handle, offset};
		 * null when the engine holds none (the block then reads as zero). Bound at the block's slot in 0 to 19.
		 */
		long @Nullable [] block(String name);

		/** The native texture of a colour target by name ({@code colortex0}, {@code gbuffer} aliases), read or write view; 0 when none. */
		long texture(String colortexName, boolean write);

		/** The native depth texture by name ({@code depthtex0}, {@code depthtex1}, {@code depthtex2}, {@code dhDepthTex0/1}); 0 when none. */
		long depth(String name);

		/** Whether the pack runs with Distant Horizons (a draw without the QsDh block is then dropped). */
		default boolean distantDefined() {
			return false;
		}

		/**
		 * The Distant Horizons block ({@code QsDh}, plan.md E7) as {handle, offset}, uploaded for this frame: the last DH values
		 * (the identity before DH's first draw), or Iris's values without DH. Every program declares it; bound at
		 * {@link EngineSlots#DISTANT_HORIZONS} whenever present.
		 */
		default long @Nullable [] distantBlock() {
			return null;
		}

		/**
		 * The Distant Horizons frame state (projection, view rotation, the draw's offset; plan.md E7), or null when the engine
		 * does not serve DH this frame. The dh_ programs' draws take their model-view and projection from it.
		 */
		default @Nullable DistantFrame distantFrame() {
			return null;
		}

		/** The native shadow texture by name ({@code shadow}, {@code shadowtex0/1}, {@code shadowcolor0-7} and their aliases); 0 when none. */
		default long shadow(String name) {
			return 0L;
		}

		/** The native noise texture; 0 when none. */
		long noise();

		/**
		 * The write handles of one attachment group of a phase, in attachment order, as the engine holds them now (2026-11-15: a
		 * group switch splits the open pass onto them, read at once). The engine returns its own array, sized to the group and
		 * refilled per call; null when it holds no targets.
		 */
		default long @Nullable [] phaseColours(GbufferPhase phase, int group) {
			return null;
		}

		/**
		 * The custom texture id of a program's sampler (CustomTextureSet.id; E6-B), or -1 when the pack binds none to it.
		 * The default binds nothing, so a delegate without custom textures keeps the engine's own sources.
		 */
		default int customId(String program, String sampler) {
			return -1;
		}

		/** The storage buffer of a bufferObject index (StorageBuffers.handle; E6-B), or 0 when the pack declares none there. */
		default long storage(int index) {
			return 0L;
		}

		/** The native texture of a custom texture id (CustomTextureSet.handle), or 0. */
		default long customHandle(int id) {
			return 0L;
		}


		/**
		 * The engine's 1x1 default texture of a gbuffers sampler no PBR pack supplies: {@code normals} (the documented RGBA 127,127,255,255)
		 * or {@code specular} (zeros). 0 for any other name.
		 */
		long defaultTexture(String name);

		/**
		 * The 1x1 white texture a program's atlas or lightmap name reads in a game pipeline that binds none of its own (the
		 * game's sky and clouds in 26.3): Iris reads white there (measured, mcms-untextured, 2026-11-15). 0 when the engine has
		 * none (the tests' bindings): the name is then left unbound, as before.
		 */
		default long untexturedTexture() {
			return 0L;
		}

		/** The sampler bound with {@link #untexturedTexture} (nearest, clamp to edge), or 0. */
		default long untexturedSampler() {
			return 0L;
		}

		/**
		 * The frame's projection in OpenGL's convention (clip z from -1 to 1), column-major, written into
		 * {@code out[0..15]}. Called once per draw: it must not allocate. The delegate converts it for game variants.
		 */
		void projection(float[] out);

		/**
		 * The size of the atlas {@code view} shows, for the {@code atlasSize} uniform (Iris and OptiFine: the size in pixels
		 * of the texture atlas the draw binds, 0 when it binds none): {@code out[0]} is the width and {@code out[1]} the
		 * height, both 0 when {@code view} is null or is not an atlas. Called once per draw: it must not allocate.
		 */
		default void atlasSize(@Nullable GpuTextureView view, int[] out) {
			out[0] = 0;
			out[1] = 0;
		}
	}

	/** The delegate of the active set; null while none is installed (the engine is inert then). */
	private static volatile @Nullable GbufferDelegate active;

	/**
	 * Runnables the engine runs whenever it releases everything (engine off, pack switch, resource reload, device
	 * close): E4-C closes its hand dispatcher and staged buffer there, E4-D its side buffers. The listeners outlive
	 * sets; {@link #removeReleaseListener} takes one back.
	 */
	private static final java.util.concurrent.CopyOnWriteArrayList<Runnable> RELEASE_LISTENERS =
		new java.util.concurrent.CopyOnWriteArrayList<>();

	/** Registers a release listener (idempotent; the engine's release path is its only caller of the run). */
	public static void addReleaseListener(Runnable release) {
		RELEASE_LISTENERS.addIfAbsent(release);
	}

	/** Forgets a release listener. */
	public static void removeReleaseListener(Runnable release) {
		RELEASE_LISTENERS.remove(release);
	}

	/** Runs the release listeners; the engine's {@code turnOff} (and so its shutdown) calls this. */
	public static void runReleaseListeners() {
		for (Runnable release : RELEASE_LISTENERS) {
			release.run();
		}
	}

	/** The kind outside every window (the world's passes). */
	private static final PassKind DEFAULT_KIND = PassKind.GBUFFERS;
	/**
	 * The drop reasons a {@link Resolved} logs once each: a pipeline with no plan, with no copy, with no attachment group the
	 * open pass can switch to, and per variant cell.
	 */
	private static final int DROP_NO_PLAN = 0, DROP_COPY = 1, DROP_GROUP = 2, DROP_VARIANT = 3;
	/** The vertex stride of Sodium's terrain meshes while the engine latches its terrain type (the plans' layout key). */
	private static final int[] TERRAIN_STRIDES = {TerrainVertexType.STRIDE};
	private static final int PHASES = GbufferPhase.values().length, DOMAINS = GbufferMapping.Domain.values().length;

	/** The game's own uniform names of its atlas and lightmap samplers, and the pack names they serve. */
	private static final String GAME_ATLAS = "Sampler0", GAME_LIGHTMAP = "Sampler2";
	private static final String PACK_LIGHTMAP = "lightmap";
	/** Sodium's names for the same two samplers ({@code DefaultChunkRenderer.render} sets both: u_BlockTex the atlas
	 * pass.getAtlas() carries, u_LightTex the lightmap; engine-sodium-hooks.md "Bind groups and sampler names"). */
	private static final String SODIUM_ATLAS = "u_BlockTex", SODIUM_LIGHTMAP = "u_LightTex";
	/** The game's overlay sampler (the entity overlay texture), served to a program's {@code entityColor} input. */
	private static final String GAME_OVERLAY = "Sampler1";
	/** The game's transform block (ModelViewMat is its first member, at offset 0). */
	private static final String TRANSFORMS = "DynamicTransforms";
	/** The game's projection block, which a game-pipeline variant is served the engine's projection through. */
	private static final String PROJECTION = "Projection";
	/** The game's clouds blocks it sets by name (RenderPass.setUniform, javap 2026-11-14: CloudRenderer sets both). */
	private static final String CLOUD_INFO = "CloudInfo", CLOUD_FACES = "CloudFaces";

	/** The bytes of {@code QsDraw}: the schema's padded size, so the one block write covers every member. */
	private static final int DRAW_BYTES = Std140.paddedSize(ProgramRequests.DRAW_BLOCK);
	/**
	 * The offset of the game's {@code ColorModulator} in {@code DynamicTransforms} (26.3 assets/minecraft/shaders/include/
	 * dynamictransforms.glsl: {@code mat4 ModelViewMat; mat4 TextureMat; vec4 ColorModulator; vec3 ModelOffset;}, std140).
	 */
	private static final int TRANSFORMS_COLOR_MODULATOR = 128;
	/** The bytes of a std140 vec4 (the game's ColorModulator and the draw's colorModulator). */
	private static final int VEC4_BYTES = 16;
	/** The bytes of a std140 mat4 (the {@code Projection} block of a game pipeline). */
	private static final int MATRIX_BYTES = 64;
	private static final int OFF_MODEL_VIEW = offset("modelViewMatrix");
	private static final int OFF_PROJECTION = offset("projectionMatrix");
	private static final int OFF_MODEL_VIEW_INVERSE = offset("modelViewMatrixInverse");
	private static final int OFF_PROJECTION_INVERSE = offset("projectionMatrixInverse");
	private static final int OFF_TEXTURE = offset("textureMatrix");
	private static final int OFF_NORMAL = offset("normalMatrix");
	private static final int OFF_RENDER_STAGE = offset("renderStage");
	private static final int OFF_ALPHA_TEST_REF = offset("alphaTestRef");
	private static final int OFF_COLOR_MODULATOR = offset("colorModulator");
	private static final int OFF_ATLAS_SIZE = offset("atlasSize");
	private static final int OFF_IDS_DEFAULT = offset(ProgramRequests.DRAW_IDS_DEFAULT);

	/** The offset of a QsDraw member (the request's block layout, read once). */
	static int drawOffset(String member) {
		return offset(member);
	}

	private static int offset(String member) {
		for (ProgramRequest.UniformField field : ProgramRequests.DRAW_BLOCK.fields()) {
			if (field.name().equals(member)) {
				return field.offset();
			}
		}
		throw new IllegalStateException("QsDraw has no member " + member);
	}

	private final GbufferPlans plans;
	private final Bindings bindings;

	private PassKind kind = DEFAULT_KIND;
	/** Whether a {@link #beginKind} window is open, and the kind it replaces (restored by {@link #endKind}). */
	private boolean kindOpen;
	private PassKind kindBeforeWindow = DEFAULT_KIND;
	private GbufferPhase phase = GbufferPhase.OPAQUE;
	/** The attachment group the open pass draws on: 0 when a pass begins, changed by a split (see {@link #switchGroup}). */
	private int openGroup;
	/** The split of the open pass the backend gave the pass it began (null for a pass with none). */
	private @Nullable PassSplitter splitter;
	/** The domain the next draws belong to (the world's entity domain outside every window). */
	private GbufferMapping.Domain domain = GbufferMapping.Domain.ENTITY;
	/** Whether the pass being drawn has a depth attachment (set by {@link #begin}). */
	private boolean hasDepth = true;
	/** Whether the pass uses the game's own depth convention (set per pass by the redirector, {@link #reversedDepth}). */
	private boolean reversed;
	private @Nullable HandDraws handDraws;
	/** Column-major OpenGL-convention hand projection; null while E4-C set none (the frame's projection serves). */
	private float @Nullable [] handProjection;
	/**
	 * E4-D's per-vertex id side buffers, one per (staged vertex buffer, stride) the latest upload packed (plan.md E4).
	 * The draw binds the one its vertex buffer and stride name, or the defaults record.
	 */
	private final VertexIdBuffers vertexIds = new VertexIdBuffers();
	/** Whether a pack program of the plans reads per-vertex ids: the uploads publish their ids only then. */
	private final boolean readsVertexIds;
	/** The vertex buffer handle the game bound at slot 0 for the next draws (0 until one is bound). */
	private long vertexHandle;
	/** The stride of the pipeline's vertex binding 0, from {@link #select} (-1 while unknown). */
	private int drawStride = -1;

	/** The pack program the next draws use; null while a game variant or no pipeline is current. */
	private GbufferPlans.@Nullable PackVariant pack;
	/** The depth binding of the current pack pipeline (its state and bias per the pass's convention). */
	private GbufferPlans.@Nullable DepthBinding packDepth;
	/** The game-pipeline variant the next draws use; null while a pack program or no pipeline is current. */
	private GbufferPlans.@Nullable GameVariant game;
	/** The depth binding of the current game pipeline. */
	private GbufferPlans.@Nullable DepthBinding gameDepth;
	/**
	 * Per game uniform index of the current pipeline: the slot it binds at, or -1; and its sampler's slot in each stage (-1: the stage
	 * does not sample through it). The sampler slots are the program's reflection's, per stage (ProgramTranslation.SamplerUse).
	 */
	private int[] slots = new int[0];
	private int[] vertexSamplerSlots = new int[0];
	private int[] fragmentSamplerSlots = new int[0];
	/** The program's atlas name the backend binds (the game's atlas), and whether mirrors follow it (SamplerSources). */
	private @Nullable String atlasPrimary;
	private boolean mirrored;
	/** The game's atlas as the last uniform bound it (texture and sampler handles; 0 until one is bound). */
	private long atlasTexture, atlasSampler;
	/** The game's atlas view as the last uniform bound it (null until one is bound); the draw's atlasSize reads its size. */
	private @Nullable GpuTextureView atlasView;
	/** The atlasSize a draw writes (width, height), filled by {@link Bindings#atlasSize} once per draw. */
	private final int[] atlasSizeScratch = new int[2];
	/** The custom texture ids of each pack variant's samplers, read once (customIds). */
	private final java.util.IdentityHashMap<GbufferPlans.PackVariant, int[]> customIdCache = new java.util.IdentityHashMap<>();
	/** The game names whose fallback default was logged (E6-B; once per name). */
	private final java.util.Set<String> fallbackNames = new java.util.HashSet<>();
	/** The sampler names this set already logged as bound to nothing (each logs once). */
	private final java.util.Set<String> unboundNames = new java.util.HashSet<>();
	/** The pack variants whose sampler names were checked (noteUnbound runs its lookups once per variant, not per bind). */
	private final java.util.Set<GbufferPlans.PackVariant> unboundChecked =
		java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
	/** The pack programs whose draws were dropped for a storage block with no buffer (each logs once per set). */
	private final java.util.Set<String> storageDrops = new java.util.HashSet<>();
	/** The game variant's {@code Projection} uniform index (its slot too), or -1 when the pipeline has none. */
	private int projectionIndex = -1;
	/** The game's ModelViewMat address for the current draw (the DynamicTransforms slice); valid when transformsSeen. */
	private long modelViewAddress;
	private boolean transformsSeen;
	/** The clouds draws' game bindings, read by the clouds prologue (null while the pass set none): the buffers and the
	 * offsets the pass bound them at (the frontend always hands the backend a slice: a whole {@code GpuBuffer} becomes
	 * {@code GpuBuffer.slice()} before the backend sees it, javap 2026-11-14: FrontendRenderPass.setUniform). */
	private @Nullable GpuBuffer cloudInfo, cloudFaces;
	private long cloudInfoOffset, cloudFacesOffset;
	/** The R8_SINT texel view of the faces buffer (CloudsLayout) and the buffer and length it was made of; freed by {@link #close}. */
	private long cloudFacesView, cloudFacesBuffer, cloudFacesLength;

	/** The constant bytes registered by slot (null = none); {@link #constantBytes} fills it. */
	private final MemorySegment[] constantSegments = new MemorySegment[34];

	/** Native scratch the draw's {@code QsDraw} bytes are written to; freed by {@link #close}. */
	private final long drawScratch = MemoryUtil.nmemCalloc(1, DRAW_BYTES);
	/** Native scratch of a game variant's {@code Projection} bytes; freed by {@link #close}. */
	private final long projectionScratch = MemoryUtil.nmemCalloc(1, MATRIX_BYTES);
	private final float[] frameScratch = new float[16];
	/** The projection a draw uses under the reversed convention (a copy, the z row negated or reversed). */
	private final float[] reversedScratch = new float[16];
	/** Reused by {@link #readMatrix}: the game's matrices are read through it. */
	private final float[] matrixScratch = new float[16];
	/** The engine's shadow view for the shadow window (E5), column-major; copied by {@link #shadowView}. */
	private final float[] shadowModelView = new float[16];
	private final float[] shadowProjection = new float[16];
	/** Whether {@link #shadowView} has run: a draw in a shadow window before that throws (never the captured view). */
	private boolean shadowViewBound;

	/**
	 * Binds the engine's shadow view (E5): {@code shadowModelView} (player space to shadow view space) and
	 * {@code shadowProjection} (OpenGL convention, converted per draw like the frame's). The shadow frame calls it once
	 * per shadow pass, before the first draw; the matrices are copied, so the caller may refill its own in place. Every
	 * draw of the shadow window reads these, never the game's captured model-view nor Sodium's camera view.
	 */
	public void shadowView(Matrix4f modelView, Matrix4f projection) {
		modelView.get(this.shadowModelView);
		projection.get(this.shadowProjection);
		this.shadowViewBound = true;
	}
	private final Matrix4f modelView = new Matrix4f();
	private final Matrix4f modelViewInverse = new Matrix4f();
	private final Matrix4f projection = new Matrix4f();
	private final Matrix4f projectionInverse = new Matrix4f();
	private final Matrix3f normal = new Matrix3f();
	private boolean closed;
	/** The game uniform names of the pipeline being set, reused by {@link #setPipeline} (a pipeline switch allocates nothing). */
	private final List<String> uniformNames = new ArrayList<>();
	/** The game pipelines resolved so far, by backend pipeline object (a switch looks its plan up without allocating). */
	private final IdentityHashMap<Object, Resolved> resolvedByPipeline = new IdentityHashMap<>();
	/** Every WARN this delegate logged ({@link #warn}); the tests read them through {@link #warnings()}. */
	private final List<String> warnings = new ArrayList<>();
	/** Whether the Sodium globals drop was logged (once per set). */
	private boolean globalsWarned;
	/** The ids of vertices no submit claimed (EntityCapture.defaultIds: 0, -1, -1); the last publish's, else read on first use. */
	private int @Nullable [] vertexIdDefaults;

	/** E4 diagnostics (the draw log): the pipeline the draws are for, its plan's location, and the pass the redirector noted. */
	private String logPipeline = "?", logLocation = "?";
	private @Nullable String logLabel;
	private MetalHooks.@Nullable Redirect logRedirect;
	/** E4 diagnostics (the draw log): the last bound vertex and index buffers (handle, offset; index type 1: 32-bit). */
	private long logVertexHandle;
	private long logVertexOffset = -1;
	private long logIndexHandle;
	private int logIndexType = -1;
	/** E4 diagnostics: the engine's buffer and texel slots (20..30) bound for the current pipeline, for the DRAW line
	 * (handle, offset, length; length -1 unknown); reset per pipeline select, filled only while the draw log is on. */
	private final long[] logSlotHandle = new long[EngineSlots.CLOUD_FACES + 1];
	private final long[] logSlotOffset = new long[EngineSlots.CLOUD_FACES + 1];
	private final long[] logSlotLength = new long[EngineSlots.CLOUD_FACES + 1];

	/** The buffer index the draws' vertex data binds at: the backend's first vertex buffer (EngineSlots.VERTEX_DATA, 27). */
	public static final int VERTEX_DATA_SLOT = EngineSlots.VERTEX_DATA;

	/** Binds the plans of the active set and the frame's bindings (the engine builds one per set). */
	public GbufferDelegate(GbufferPlans plans, Bindings bindings) {
		this.plans = plans;
		this.readsVertexIds = plans != null && plans.readsPerVertexIds();
		this.bindings = bindings;
		// The texture matrix is the identity for every draw (the game's TextureMat is not read in E4).
		putMatrix(this.drawScratch + OFF_TEXTURE, new Matrix4f());
	}

	/**
	 * Sets the delegate the active set installed (public since E4's wiring, 2026-11-14: the engine's install and
	 * release are its callers, {@code install(delegate)} at the set's activation and {@code install(null)} on every
	 * release path, beside {@code MetalHooks.setRedirector}).
	 */
	public static void install(@Nullable GbufferDelegate delegate) {
		active = delegate;
	}

	/** The delegate of the active set (null while none: the caller then draws the game's own way). */
	public static @Nullable GbufferDelegate active() {
		return active;
	}

	/**
	 * Wraps {@code draws} in a pass-kind window: every pass opened inside is redirected with this kind and its
	 * pipelines map through it ({@code HAND} maps the entity and item pipelines to the hand programs). The window
	 * closes when {@code draws} returns or throws. Windows do not nest (see {@link #beginKind}).
	 */
	public void passKind(PassKind window, Runnable draws) {
		beginKind(window);
		try {
			draws.run();
		} finally {
			endKind();
		}
	}

	/**
	 * Opens a pass-kind window that stays open until {@link #endKind}: for passes whose open and close are separate steps
	 * (E5's shadow pass opens its pass, draws terrain and entities, then closes it). The same redirect rules as
	 * {@link #passKind} apply to every render pass created between the two calls. Windows do not nest: a second
	 * {@code beginKind} while one is open throws.
	 *
	 * @throws IllegalStateException a window is already open
	 */
	public void beginKind(PassKind window) {
		if (this.kindOpen) {
			throw new IllegalStateException("a pass-kind window is already open (" + this.kind + "); windows do not nest");
		}
		this.kindBeforeWindow = this.kind;
		this.kind = window;
		this.kindOpen = true;
	}

	/**
	 * Closes the window {@link #beginKind} opened, restoring the kind it replaced.
	 *
	 * @throws IllegalStateException no window is open
	 */
	public void endKind() {
		if (!this.kindOpen) {
			throw new IllegalStateException("endKind without a pass-kind window open");
		}
		this.kind = this.kindBeforeWindow;
		this.kindOpen = false;
	}

	/** The kind the delegate maps the next pipeline with. */
	public PassKind kind() {
		return this.kind;
	}

	/** The phase whose attachment layout the redirected pass draws into (the redirector and the phase hook set it). */
	public void phase(GbufferPhase phase) {
		this.phase = phase;
		this.openGroup = 0;  // the next pass, or the phase hook's split, opens on group 0 of the phase
	}

	/** The current phase (the redirector reads it to lay a pass's colours out). */
	public GbufferPhase phase() {
		return this.phase;
	}

	/**
	 * Wraps {@code draws} in a domain window (the E4 routing rules, GbufferMapping): the block-entity frame draws
	 * inside {@link GbufferMapping.Domain#BLOCK_ENTITY}, so its pipelines map to the block programs even where an
	 * entity pipeline carries them (the chest's {@code entity_cutout} draws through {@code gbuffers_block}). The
	 * previous domain returns when the window closes, also on a throw.
	 */
	public void passDomain(GbufferMapping.Domain window, Runnable draws) {
		GbufferMapping.Domain previous = this.domain;
		this.domain = window;
		try {
			draws.run();
		} finally {
			this.domain = previous;
		}
	}

	/** The domain the delegate maps the next pipeline with. */
	public GbufferMapping.Domain domain() {
		return this.domain;
	}

	/**
	 * Sets the depth convention of the pass about to draw (the redirector calls it per pass): {@code true} for a pass that
	 * keeps its own depth attachment, cleared to the game's 0.0, which the game's reversed depth convention serves; false
	 * for the engine's depth target (cleared to 1.0, flipped compare).
	 */
	public void reversedDepth(boolean reversed) {
		this.reversed = reversed;
	}

	/** Registers E4-C's hand draws (null clears the registration; the phase hook then draws no hand). */
	public void setHandDraws(@Nullable HandDraws draws) {
		this.handDraws = draws;
	}

	/** The registered hand draws, or null when E4-C registered none. */
	public @Nullable HandDraws handDraws() {
		return this.handDraws;
	}

	/**
	 * Sets the hand projection E4-C computed (column-major, OpenGL's convention; the hand-depth scale applied).
	 * Served to {@code QsDraw.projectionMatrix} while the kind is {@code HAND}; null returns to the frame's
	 * projection. The values copy into this delegate's array (a steady-state frame allocates nothing; the E3 rule).
	 */
	public void setHandProjection(float @Nullable [] columnMajor) {
		if (columnMajor == null) {
			this.handProjection = null;
			return;
		}
		if (this.handProjection == null) {
			this.handProjection = new float[16];
		}
		System.arraycopy(columnMajor, 0, this.handProjection, 0, 16);
	}

	/** The hand projection set last (null when none); column-major, OpenGL's convention. */
	public float @Nullable [] handProjection() {
		return this.handProjection;
	}

	/**
	 * Publishes the per-vertex ids one staged vertex buffer's upload staged (E4-D, the upload's RETURN in
	 * {@code EngineSubmitStagedVertexBufferMixin}): {@code vertexBuffer} is the buffer the upload wrote and {@code ranges}
	 * the resolved ranges of that upload. Draws that read this buffer at a packed stride take the records; the others take
	 * the defaults ({@code defaults}: the ids of vertices no submit claimed, EntityCapture.defaultIds). Nothing is published
	 * while the delegate is closed, or while no pack program of the plans reads per-vertex ids.
	 */
	public void publishVertexIds(GpuBuffer vertexBuffer, EntityCapture.Ranges ranges, int[] defaults) {
		if (!this.closed && this.readsVertexIds) {
			this.vertexIdDefaults = defaults;
			this.vertexIds.publish(vertexBuffer, ranges);
		}
	}

	/** The defaults the defaults record is made from: the last publish's, else the active set's (EntityCapture). */
	private int[] vertexIdDefaults() {
		if (this.vertexIdDefaults == null) {
			this.vertexIdDefaults = EntityCapture.defaultIds();
		}
		return this.vertexIdDefaults;
	}

	/**
	 * Registers CPU staging bytes the delegate copies into every draw at {@code slot} (2026-11-14, E4Sodium3's contract:
	 * Sodium's {@code u_Globals} mirror at slot 21). The backend copies the bytes at each draw, so a later rewrite of
	 * them cannot race the GPU. {@code bytes} null forgets the slot.
	 */
	public void constantBytes(int slot, @Nullable MemorySegment bytes) {
		if (slot >= 0 && slot < this.constantSegments.length) {
			this.constantSegments[slot] = bytes;
		}
	}

	/** The bytes registered at {@code slot}, or null; {@link #constantBytes} sets them. */
	public @Nullable MemorySegment constantBytesAt(int slot) {
		return slot >= 0 && slot < this.constantSegments.length ? this.constantSegments[slot] : null;
	}

	/**
	 * A pass began: whether it has a depth attachment. The pipeline state a pack program binds is chosen from it (a
	 * pass without a depth attachment, such as "See through features", takes the state built without one).
	 */
	@Override
	public void begin(long enc, boolean hasDepth) {
		begin(enc, hasDepth, null);
	}

	/**
	 * A pass began with its split (2026-11-15, {@link PassDelegate#begin(long, boolean, PassSplitter)}). The pass opens on group
	 * 0 of its phase, and a draw whose program writes another group's targets splits it through {@code splitter}.
	 */
	@Override
	public void begin(long enc, boolean hasDepth, @Nullable PassSplitter splitter) {
		this.hasDepth = hasDepth;
		this.splitter = splitter;
		this.openGroup = 0;
		if (DrawLog.on()) {
			logPass(hasDepth);
		}
	}

	/**
	 * The game's pass continues after Distant Horizons' water was drawn in its place (PassDelegate#resume): the pass state comes
	 * back as {@link #begin(long, boolean, PassSplitter)} sets it, without a new pass line in the draw log (the pass is the one
	 * already logged, not a second DH pass).
	 */
	@Override
	public void resume(long enc, boolean hasDepth, @Nullable PassSplitter splitter) {
		this.hasDepth = hasDepth;
		this.splitter = splitter;
		this.openGroup = 0;
	}

	@Override
	public int @Nullable [] setPipeline(long enc, Object pipeline, List<BindGroupLayout.UniformDescription> uniforms) {
		Resolved resolved = this.resolvedByPipeline.get(pipeline);
		if (resolved == null || resolved.stale(TerrainEngine.active())) {
			resolved = resolve(MetalBridge.pipelineName(pipeline), MetalBridge.pipelineStrides(pipeline));
			this.resolvedByPipeline.put(pipeline, resolved);
		}
		return select(enc, resolved, pipeline, uniforms);
	}

	/**
	 * Resolves a game pipeline by its name and vertex strides (null when unknown): its plan under the strides its draws use.
	 * A pipeline switch resolves each backend pipeline once ({@link #setPipeline}), and the horizon resolves its sky once per
	 * set, so the draws look the plan up without building a string.
	 */
	Resolved resolve(@Nullable String name, int @Nullable [] strides) {
		return resolve(name, strides, TerrainEngine.active());
	}

	/**
	 * {@link #resolve(String, int[])} with the engine's terrain latch given. A Sodium terrain pipeline's meshes are the
	 * engine's terrain type while the latch is in (the latch's reload rebuilds every mesh in it), whatever vertex stride the
	 * pipeline object declares (Sodium's compact 20 bytes): its plan is the terrain layout's, under {@link TerrainVertexType#STRIDE}.
	 * Before the latch, the meshes are compact and the declared stride finds no plan, so those draws are skipped.
	 */
	Resolved resolve(@Nullable String name, int @Nullable [] strides, boolean terrainLatched) {
		boolean sodiumTerrain = name != null && GbufferPlans.isSodiumTerrain(name);
		boolean latched = sodiumTerrain && terrainLatched;
		int[] lookup = latched ? TERRAIN_STRIDES : strides;
		GbufferPlans.Plan plan = name == null ? null : this.plans.planOf(name, lookup);
		boolean unplanned = plan == null && name != null && lookup != null && this.plans.planOf(name) != null;
		return new Resolved(name, lookup, plan, unplanned, sodiumTerrain, latched);
	}

	/**
	 * Selects the variant of the game pipeline {@code name} for the current (kind, phase) and binds it, by name alone
	 * (the tests' entry: {@code backend} is the backend object the pass set, null when a test selects by name alone, and
	 * {@code strides} its vertex bindings' strides, null when unknown). Null when the pass draws nothing for it.
	 */
	int @Nullable [] select(long enc, @Nullable String name, @Nullable Object backend, int @Nullable [] strides,
			List<BindGroupLayout.UniformDescription> uniforms) {
		return select(enc, resolve(name, strides), backend, uniforms);
	}

	/**
	 * Binds the variant of a resolved game pipeline for the current (kind, phase, domain). {@code backend} is the backend
	 * object the pass set (null for the horizon's pack draw). Null when the pass draws nothing for it: no plan, or no variant
	 * in a phase the location draws in (each logged once per set).
	 */
	int @Nullable [] select(long enc, Resolved resolved, @Nullable Object backend,
			List<BindGroupLayout.UniformDescription> uniforms) {
		this.drawStride = resolved.strides != null && resolved.strides.length > 0 ? resolved.strides[0] : -1;
		GbufferPlans.Plan plan = resolved.plan;
		this.logPipeline = resolved.name == null ? "?" : resolved.name;
		this.logLocation = plan == null ? "?" : plan.location();
		this.uniformNames.clear();
		// A pipeline switch re-delivers every uniform of the pipeline at its next draw (MetalRenderPass.setPipeline), so the atlas
		// a draw reads is the one this pipeline's own uniform binds: a pipeline with no atlas uniform reads (0, 0), not the view
		// of the draw before it.
		this.atlasView = null;
		if (DrawLog.on()) {
			java.util.Arrays.fill(this.logSlotHandle, 0);
		}
		// Indexed, not for-each: a pipeline switch allocates no iterator, in the interpreter as well as compiled.
		for (int i = 0; i < uniforms.size(); i++) {
			this.uniformNames.add(uniforms.get(i).name());
		}
		if (plan == null) {
			return noPlan(resolved);
		}
		GbufferPlans.Variant variant = plan.variant(this.kind, this.phase, this.domain);
		if (variant instanceof GbufferPlans.PackVariant pv) {
			ProgramRequest.BufferBinding unbound = unboundStorage(pv);
			if (unbound != null) {
				return droppedStorage(pv, unbound);
			}
			if (pv.group() != this.openGroup && !switchGroup(enc, resolved, pv.group())) {
				return dropped();
			}
			// A group switch splits the pass first; bindPack then binds the pack once, on the new attachments.
			return bindPack(enc, pv, this.uniformNames);
		}
		if (variant instanceof GbufferPlans.GameVariant) {
			// A game-pipeline variant is compiled for each attachment group: the group the pass has open draws it.
			GbufferPlans.GameVariant gameOfGroup = plan.gameVariant(this.kind, this.phase, this.domain, this.openGroup);
			if (gameOfGroup != null) {
				return bindGame(enc, resolved, gameOfGroup, backend, this.uniformNames);
			}
		}
		// A pipeline a pass sets in a phase it does not draw in (the split's restore, a translucent pass) is not a drop.
		if (contains(GbufferMapping.phases(this.kind, plan.location()), this.phase)
				&& resolved.claim(dropVariantBit(this.kind, this.phase, this.domain))) {
			warn("no pipeline is built for " + resolved.name + " in " + this.kind + " " + this.phase + " " + this.domain);
		}
		return dropped();
	}

	/**
	 * A pipeline no plan serves: a quiet INFO when its name has a plan under other strides (the install frame's compact
	 * Sodium meshes), else a WARN. Each is logged once per set.
	 */
	private int @Nullable [] noPlan(Resolved resolved) {
		if (resolved.claim(DROP_NO_PLAN)) {
			if (resolved.unplannedStrides) {
				info(resolved.name + " has no plan for its vertex strides " + java.util.Arrays.toString(resolved.strides)
					+ ": its draws are skipped");
			} else {
				warn("no plan was built for game pipeline " + resolved.name
					+ (resolved.strides == null ? "" : " with vertex strides " + java.util.Arrays.toString(resolved.strides)));
			}
		}
		return dropped();
	}

	/** The bit of a variant drop reason: one per (kind, phase, domain) cell. */
	private static int dropVariantBit(PassKind kind, GbufferPhase phase, GbufferMapping.Domain domain) {
		return DROP_VARIANT + (kind.ordinal() * PHASES + phase.ordinal()) * DOMAINS + domain.ordinal();
	}

	/** Logs a dropped draw's reason at WARN and keeps it for {@link #warnings()}. */
	private void warn(String reason) {
		this.warnings.add(reason);
		LOG.warn("Quicksilver: dropping redirected draws: {}", reason);
	}

	/** Logs an expected skip at INFO (not a failure). */
	private static void info(String note) {
		LOG.info("Quicksilver: {}", note);
	}

	/** The WARN reasons this delegate logged, in order (each reason is logged once per set; the tests read them). */
	List<String> warnings() {
		return List.copyOf(this.warnings);
	}

	/** Gives up the draw: the pack and game bindings reset and nothing is encoded. */
	private int @Nullable [] dropped() {
		this.pack = null;
		this.game = null;
		return null;
	}

	/**
	 * A game pipeline resolved once: its name, its vertex strides, its plan under them, and which drop reasons were logged
	 * (one bit each, so a repeated drop builds no string). Kept per backend pipeline object, so a pipeline switch allocates
	 * nothing. Package-private: the horizon keeps the sky's and reads its plan.
	 */
	static final class Resolved {
		final @Nullable String name;
		final int @Nullable [] strides;
		final GbufferPlans.@Nullable Plan plan;
		/** True when the name has a plan, but not under these strides: its draws are skipped, logged at INFO. */
		final boolean unplannedStrides;
		/** Whether the name is a Sodium terrain pipeline, and whether the engine's terrain type was latched when resolved. */
		final boolean sodiumTerrain, terrainLatched;
		private long logged;

		Resolved(@Nullable String name, int @Nullable [] strides, GbufferPlans.@Nullable Plan plan,
				boolean unplannedStrides, boolean sodiumTerrain, boolean terrainLatched) {
			this.name = name;
			this.strides = strides;
			this.plan = plan;
			this.unplannedStrides = unplannedStrides;
			this.sodiumTerrain = sodiumTerrain;
			this.terrainLatched = terrainLatched;
		}

		/** Whether the engine's terrain latch moved since this was resolved: a Sodium terrain pipeline then resolves again. */
		boolean stale(boolean terrainLatchedNow) {
			return this.sodiumTerrain && this.terrainLatched != terrainLatchedNow;
		}

		/** Claims a drop reason's log slot: true the first time only. */
		boolean claim(int bit) {
			long mask = 1L << bit;
			if ((this.logged & mask) != 0) {
				return false;
			}
			this.logged |= mask;
			return true;
		}
	}

	private static boolean contains(GbufferPhase[] phases, GbufferPhase phase) {
		for (GbufferPhase candidate : phases) {
			if (candidate == phase) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Moves the open pass onto another attachment group of the phase it draws in (2026-11-15: a phase whose programs need more
	 * than the attachment limit draws in groups). The pass's colours load, and its depth and size stay. The group is recorded
	 * before the split, so the pipeline the caller binds after it (once) is the new group's; the split does not set it again.
	 * False when the pass cannot split or the engine holds no colours for the group; the draw is then dropped.
	 */
	private boolean switchGroup(long enc, Resolved resolved, int group) {
		PassSplitter split = this.splitter;
		long[] colours = this.bindings.phaseColours(this.phase, group);
		if (split == null || colours == null) {
			if (resolved.claim(DROP_GROUP)) {
				warn("cannot draw " + resolved.name + " on attachment group " + group + " of " + this.phase
					+ ": the open pass cannot be split");
			}
			return false;
		}
		int from = this.openGroup;
		this.openGroup = group;
		if (DrawLog.on()) {
			logGroup(from, group, colours.length);
		}
		split.split(colours);
		return true;
	}

	/**
	 * Records that the pass the caller opened draws on attachment group {@code group} of its phase (the horizon opens its own
	 * pass on the group its sky program writes, with that group's colours). The caller opens the pass; this only names the group.
	 */
	public void openedOnGroup(int group) {
		this.openGroup = group;
	}

	/** E4 diagnostics: a group switch, named in the draw log with the new group's attachment targets. */
	private void logGroup(int from, int to, int attachments) {
		StringBuilder sb = DrawLog.line();
		sb.append("GROUP f=").append(DrawLog.frameNumber()).append(" p=").append(DrawLog.passNumber())
			.append(" phase=").append(this.phase).append(" kind=").append(this.kind).append(" from=").append(from)
			.append(" to=").append(to).append(" attachments=").append(attachments).append(" targets=");
		int[] targets = this.plans.layout(this.phase, to);
		for (int i = 0; i < targets.length; i++) {
			sb.append(i == 0 ? "" : ",").append(targets[i]);
		}
		DrawLog.emit(sb);
	}

	/**
	 * Binds a pack program: its pipeline state (with or without depth, per {@link #begin}) and depth state, the pack's
	 * blocks, frame and custom blocks and textures. Returns the slots of the game's uniforms ({@link #setPipeline}'s contract), or null
	 * when the draw is dropped (no QsDh block while the pack runs with Distant Horizons).
	 */
	int @Nullable [] bindPack(long enc, GbufferPlans.PackVariant pv, List<String> uniforms) {
		this.pack = pv;
		this.atlasPrimary = atlasName(pv);
		this.mirrored = mirrorsAtlas(pv);
		noteUnbound(pv);
		this.game = null;
		this.transformsSeen = false;
		GbufferPlans.DepthBinding depth = pv.depth(this.reversed);
		this.packDepth = depth;
		// The translated program's epilogue flips y (ProgramEmitter's vertex epilogue), which mirrors GL's
		// counter-clockwise front faces into the encoder's clockwise ones (mcmetal.m's openRender sets that winding
		// for the same reason): the pack bind takes clockwise front faces (back-face culling then culls GL's back
		// faces), and the game bind keeps clockwise too — no bind leaves a foreign winding behind.
		MetalBridge.renderPipeline(enc, pv.pipeline(this.hasDepth), depth.state(), pv.cull(), pv.wireframe(),
			depth.biasConstant(), depth.biasSlope(), pv.primitive(), false);
		int count = uniforms.size();
		ensureSlots(count);
		boolean gameAtlas = false, gameLightmap = false, gameOverlay = false;
		for (int i = 0; i < count; i++) {
			String uniform = uniforms.get(i);
			boolean atlas = uniform.equals(GAME_ATLAS) || uniform.equals(SODIUM_ATLAS);
			boolean lightmap = uniform.equals(GAME_LIGHTMAP) || uniform.equals(SODIUM_LIGHTMAP);
			gameAtlas |= atlas;
			gameLightmap |= lightmap;
			// The atlas comes as texture, its documented alias gtexture, or tex (SamplerSources), and Sodium's pass names it
			// u_BlockTex (its lightmap u_LightTex): the program's first atlas name takes the game's sampler, the others mirror it.
			String packName = atlas ? atlasName(pv) : lightmap && gameBound(pv, PACK_LIGHTMAP) ? PACK_LIGHTMAP : null;
			// The game's own textures bind at the reflection's texture and sampler slots (texture() below); the other
			// game uniforms are the engine's to serve (DynamicTransforms into QsDraw, Projection replaced), not the pipeline's.
			if (uniform.equals(GAME_OVERLAY) && pv.overlaySlot() >= 0) {
				gameOverlay = true;
				// The entity overlay the program's entityColor reads: the game's Sampler1 binds at the request's texture index, and the
				// vertex prologue fetches it (no sampler). Both stages get none.
				this.slots[i] = pv.overlaySlot();
				this.vertexSamplerSlots[i] = -1;
				this.fragmentSamplerSlots[i] = -1;
				continue;
			}
			this.slots[i] = packName == null ? -1 : textureSlot(pv, packName);
			this.vertexSamplerSlots[i] = packName == null ? -1 : samplerSlot(pv, packName, Stage.VERTEX);
			this.fragmentSamplerSlots[i] = packName == null ? -1 : samplerSlot(pv, packName, Stage.FRAGMENT);
		}
		// A game pipeline with no atlas or lightmap of its own (26.3's sky and clouds): the program's names read white, as in
		// Iris (measured, mcms-untextured, 2026-11-15), with a sampler bound. Before, they read whatever an earlier draw had
		// bound, or nothing at a pass's first draw (Metal API validation: "missing Sampler binding"); Complementary's
		// gbuffers_clouds took a stale texture for its colour.
		if (!gameAtlas && this.atlasPrimary != null) {
			bindUntextured(enc, pv, this.atlasPrimary);
			atlas(this.bindings.untexturedTexture(), this.bindings.untexturedSampler());  // the program's other atlas names mirror it
		}
		if (!gameLightmap && gameBound(pv, PACK_LIGHTMAP)) {
			bindUntextured(enc, pv, PACK_LIGHTMAP);
		}
		// A game pipeline whose layout carries the overlay coordinates but binds no overlay texture: the program's entityColor
		// reads the white texel (no flash, entityColor 0, as Iris reads it; mcms-entity, 2026-11-16). Before, it read an unbound
		// texture (zero: entityColor (0, 0, 0, 1)), and Bliss blended such entity parts to black.
		if (!gameOverlay && pv.overlaySlot() >= 0 && this.bindings.untexturedTexture() != 0) {
			MetalBridge.renderTexture(enc, pv.overlaySlot(), this.bindings.untexturedTexture());
		}

		if (!bindEngineState(enc, pv)) {
			return dropped();
		}
		return this.slots;
	}

	/** Binds the engine's white texture and its sampler at a program name's texture and sampler slots (when the engine has them). */
	private void bindUntextured(long enc, GbufferPlans.PackVariant pv, String name) {
		int slot = textureSlot(pv, name);
		long texture = this.bindings.untexturedTexture(), sampler = this.bindings.untexturedSampler();
		if (slot >= 0 && texture != 0 && sampler != 0) {
			MetalBridge.renderTextureSlots(enc, slot, texture, sampler, samplerSlot(pv, name, Stage.VERTEX),
				samplerSlot(pv, name, Stage.FRAGMENT));
		}
	}

	/** The program's first atlas name (texture, gtexture or tex) with a slot; null when it declares none. */
	private static @Nullable String atlasName(GbufferPlans.PackVariant pv) {
		for (String name : SamplerSources.ATLAS_NAMES) {
			if (textureSlot(pv, name) >= 0 && gameBound(pv, name)) {
				return name;
			}
		}
		return null;
	}

	/**
	 * Binds a game-pipeline variant: the game's own pipeline (the backend object the pass set) or the copy writing colortex0,
	 * with the depth state of the pass's convention. Every game uniform binds at its own index, the backend's default
	 * binding, and the {@code Projection} is overwritten per draw ({@link #beforeDraw}). Null when the pass sets no pipeline
	 * to draw with, or the copy is still pending.
	 */
	int @Nullable [] bindGame(long enc, Resolved resolved, GbufferPlans.GameVariant gv, @Nullable Object backend,
			List<String> uniforms) {
		Object source = gv.ownPipeline() ? backend : gv.copy();
		long pso = source == null ? 0 : MetalBridge.pipelineVariant(source, this.hasDepth);
		this.pack = null;
		this.game = null;
		if (pso == 0) {
			if (resolved.claim(DROP_COPY)) {
				warn("the game pipeline " + (resolved.name == null ? "(unnamed)" : resolved.name)
					+ " has no compiled copy or variant for this pass");
			}
			return dropped();
		}
		this.game = gv;
		this.transformsSeen = false;
		this.projectionIndex = uniforms.indexOf(PROJECTION);
		GbufferPlans.DepthBinding depth = gv.depth(this.reversed);
		this.gameDepth = depth;
		// Every bind pins the encoder's clockwise front faces (see bindPack): no bind leaves a foreign winding behind.
		MetalBridge.renderPipeline(enc, pso, depth.state(), gv.cull(), gv.wireframe(), depth.biasConstant(),
			depth.biasSlope(), gv.primitive(), false);
		int count = uniforms.size();
		ensureSlots(count);
		// The game's shaders keep their own sampler slots (the copy has the same shaders): each sampler binds where the source
		// pipeline's reflection put it. Before (E6 samplers to the review of 2026-11-15) these were -1, so a game variant's draw
		// sampled with no sampler bound.
		for (int i = 0; i < count; i++) {
			this.slots[i] = i;
			this.vertexSamplerSlots[i] = MetalBridge.vertexSamplerSlot(source, i);
			this.fragmentSamplerSlots[i] = MetalBridge.fragmentSamplerSlot(source, i);
		}
		return this.slots;
	}

	private void ensureSlots(int count) {
		if (this.slots.length < count) {
			this.slots = new int[count];
			this.vertexSamplerSlots = new int[count];
			this.fragmentSamplerSlots = new int[count];
		}
	}

	/**
	 * The blocks and the pack's own textures of the pipeline, bound once per pipeline switch (textures keep across draws). Every
	 * program declares the QsDh block (ProgramRequests): it is bound whenever the engine holds one. Returns false when the pack
	 * runs with Distant Horizons and the engine holds none: the draw is then dropped.
	 */
	private boolean bindEngineState(long enc, GbufferPlans.PackVariant pv) {
		long[] distant = this.bindings.distantBlock();
		if (distant != null) {
			MetalBridge.renderBuffer(enc, EngineSlots.DISTANT_HORIZONS, distant[0], distant[1]);
		} else if (this.bindings.distantDefined()) {
			return false;
		}
		long[] frame = this.bindings.frameBlock(pv.program());
		if (frame != null) {
			MetalBridge.renderBuffer(enc, ProgramRequests.FRAME_SLOT, frame[0], frame[1]);
			if (DrawLog.on()) {
				noteEngineBuffer(EngineSlots.FRAME, frame[0], frame[1], FrameUniforms.size());
			}
		}
		long[] custom = this.bindings.customBlock();
		if (custom != null) {
			MetalBridge.renderBuffer(enc, ProgramRequests.CUSTOM_SLOT, custom[0], custom[1]);
			if (DrawLog.on()) {
				noteEngineBuffer(EngineSlots.CUSTOM, custom[0], custom[1], -1);
			}
		}
		for (int i = 0; i < pv.buffers().size(); i++) {
			ProgramRequest.BufferBinding buffer = pv.buffers().get(i);
			if (buffer.storage()) {
				// E6-B: a storage buffer at the Metal slot the block takes; its contents are the pack's bufferObject of that index.
				// select has checked that every storage block of the variant has its buffer (unboundStorage).
				MetalBridge.renderBuffer(enc, buffer.slot(), this.bindings.storage(buffer.bufferObject()), 0);
				continue;
			}
			long[] block = this.bindings.block(buffer.name());
			if (block != null) {
				MetalBridge.renderBuffer(enc, buffer.slot(), block[0], block[1]);
			}
		}
		int[] ids = customIds(pv);
		for (int i = 0; i < pv.textures().size(); i++) {
			ProgramRequest.TextureBinding texture = pv.textures().get(i);
			if (isGameName(texture.name()) && texture.samplerSlot() >= 0) {
				continue;  // the game's own atlas or lightmap, bound by the game's uniform
			}
			// E6-B: the pack's custom texture of this sampler in this program first, else the engine's own source.
			int id = ids[i];
			long handle = id < 0 ? 0L : this.bindings.customHandle(id);
			if (handle == 0) {
				handle = packTexture(texture.name(), false);
			}
			if (handle == 0 && isGameName(texture.name())) {
				handle = gameFallback(texture.name());
			}
			if (handle != 0) {
				MetalBridge.renderTexture(enc, texture.slot(), handle);
			}
		}
		for (int i = 0; i < pv.images().size(); i++) {
			ProgramRequest.ImageBinding image = pv.images().get(i);
			long handle = this.bindings.texture(image.name(), false);  // E6: the current (read-side) texture, as the kernels bind
			if (handle != 0) {
				MetalBridge.renderTexture(enc, image.slot(), handle);
			}
		}
		return true;
	}

	/** Whether the program's binding of this name takes the game's sampler slot (false when a custom texture replaces it). */
	private static boolean gameBound(GbufferPlans.PackVariant pv, String name) {
		for (int i = 0; i < pv.textures().size(); i++) {
			ProgramRequest.TextureBinding texture = pv.textures().get(i);
			if (texture.name().equals(name)) {
				return texture.samplerSlot() >= 0;
			}
		}
		return false;
	}

	/**
	 * The default of a game name a custom texture replaces while the game has not bound its own view yet (E6-B): the atlas
	 * reads a zero texel, the lightmap white (Engine.defaultTexture). Logged once per name.
	 */
	private long gameFallback(String name) {
		if (this.fallbackNames.add(name)) {
			LOG.warn("Quicksilver: a custom texture for {} binds the default until the game has bound its own {}", name,
				PACK_LIGHTMAP.equals(name) ? "lightmap" : "atlas");
		}
		return this.bindings.defaultTexture(name);
	}

	/**
	 * The custom texture ids of a pack program's samplers, one per texture binding (-1 for none). The set's bindings are fixed
	 * once the program's requests are built, so the ids are read once per variant and kept (E6-B).
	 */
	private int[] customIds(GbufferPlans.PackVariant pv) {
		int[] ids = this.customIdCache.get(pv);
		if (ids == null) {
			ids = new int[pv.textures().size()];
			for (int i = 0; i < ids.length; i++) {
				ids[i] = this.bindings.customId(pv.program(), pv.textures().get(i).name());
			}
			this.customIdCache.put(pv, ids);
		}
		return ids;
	}

	/**
	 * The depth texture a sampled name reads for a program. Iris runs {@code dh_water} before the game's translucents, so its
	 * {@code depthtex0} is the solid depth. Here {@code dh_water}'s {@code depthtex0} reads the solid copy ({@code depthtex1}) on both water
	 * paths: in the phase hook (Engine.drawTransparentInPhase) the copy is taken just before, and holds the same depth as the live depth
	 * there; on the after-level fallback the live depth already holds the game's translucent water (Complementary's dh_water discards where
	 * {@code depthtex0} is below 1.0), so only the copy holds the solid depth.
	 */
	static String depthNameFor(@Nullable String program, String name) {
		return "dh_water".equals(program) && name.equals("depthtex0") ? "depthtex1" : name;
	}

	/** The texture a pack program's name reads from an engine source (SamplerSources); 0 when the engine binds none. */
	private long packTexture(String name, boolean write) {
		return switch (SamplerSources.of(name)) {
			case NOISE -> this.bindings.noise();
			case DEPTH, DISTANT_DEPTH -> this.bindings.depth(depthNameFor(this.pack == null ? null : this.pack.program(), name));
			case NORMALS, SPECULAR -> this.bindings.defaultTexture(name);
			case TARGET -> this.bindings.texture(name, write);
			case SHADOW -> this.bindings.shadow(name);
			default -> this.bindings.texture(name, false);  // E6: a custom sampler or a colorimg/shadowcolorimg image's texture
		};
	}

	/** Whether the game binds the name's texture (the atlas or the lightmap), so the engine's own binds skip it. */
	private static boolean isGameName(String name) {
		SamplerSources.Source source = SamplerSources.of(name);
		return source == SamplerSources.Source.ATLAS || source == SamplerSources.Source.LIGHTMAP;
	}

	/** Whether the program declares more than one atlas name: the game's atlas is then mirrored to the others per draw. */
	private static boolean mirrorsAtlas(GbufferPlans.PackVariant pv) {
		int atlases = 0;
		for (int i = 0; i < pv.textures().size(); i++) {
			if (SamplerSources.of(pv.textures().get(i).name()) == SamplerSources.Source.ATLAS) {
				atlases++;
			}
		}
		return atlases > 1;
	}

	/**
	 * Logs once per set each sampler name the program declares that the engine binds to nothing. A name the static table does
	 * not know may still have a source: a custom texture (customTexture.* or texture.gbuffers.*) or a custom image's sampler
	 * (image.*), which packTexture binds through the bindings; those are not logged (2026-11-15: Complementary's
	 * floodfill_sampler, voxel_sampler, textureAtlas and the world-space reflection samplers were logged though bound).
	 */
	void noteUnbound(GbufferPlans.PackVariant pv) {
		if (!this.unboundChecked.add(pv)) {
			return;  // checked at this variant's first bind (its bindings do not change within a set)
		}
		for (int i = 0; i < pv.textures().size(); i++) {
			String name = pv.textures().get(i).name();
			// The name joins the set only when it is logged: a name a custom texture serves in one program must not hide a true
			// unbound case of the same name in another program (E6-fix-F, review item 6).
			SamplerSources.Source source = SamplerSources.of(name);
			boolean noSource = source == SamplerSources.Source.UNBOUND
				|| source == SamplerSources.Source.DISTANT_DEPTH && this.bindings.depth(name) == 0L;  // E7: no DH depth this session
			if (noSource && !this.unboundNames.contains(name)
					&& this.bindings.customId(pv.program(), name) < 0 && this.bindings.texture(name, false) == 0L) {
				this.unboundNames.add(name);
				LOG.warn("Quicksilver: {} declares sampler {}: {}", pv.program(), name, SamplerSources.unboundReason(name));
			}
		}
	}

	/** The first storage block of a pack variant whose bufferObject has no buffer, or null (E6, finding 3). */
	private ProgramRequest.@Nullable BufferBinding unboundStorage(GbufferPlans.PackVariant pv) {
		for (int i = 0; i < pv.buffers().size(); i++) {
			ProgramRequest.BufferBinding buffer = pv.buffers().get(i);
			if (buffer.storage() && this.bindings.storage(buffer.bufferObject()) == 0L) {
				return buffer;
			}
		}
		return null;
	}

	/**
	 * Drops a pack pipeline whose storage block has no buffer (E6, finding 3): its draws are not encoded, rather than run with
	 * an unbound slot. Logged once per program and set.
	 */
	private int @Nullable [] droppedStorage(GbufferPlans.PackVariant pv, ProgramRequest.BufferBinding buffer) {
		if (this.storageDrops.add(pv.program())) {
			warn(pv.program() + ": storage block " + buffer.name() + " binds bufferObject." + buffer.bufferObject()
				+ ", which has no buffer at this view size; its draws are dropped");
		}
		return dropped();
	}

	/**
	 * Records the game's atlas (its texture and sampler) that the last uniform bound. The draws mirror it to the
	 * program's other atlas names ({@link #bindMirrors}).
	 */
	public void atlas(long texture, long sampler) {
		this.atlasTexture = texture;
		this.atlasSampler = sampler;
	}

	/**
	 * Binds the game's atlas at each atlas name of the program but the primary one (the backend binds the primary at its
	 * sampler slot). A program that reads {@code texture} and {@code tex} samples the same atlas through both.
	 */
	private void bindMirrors(long enc, GbufferPlans.PackVariant pv) {
		if (this.atlasTexture == 0) {
			return;
		}
		for (int i = 0; i < pv.textures().size(); i++) {
			ProgramRequest.TextureBinding texture = pv.textures().get(i);
			if (SamplerSources.of(texture.name()) == SamplerSources.Source.ATLAS && !texture.name().equals(this.atlasPrimary)
				&& texture.samplerSlot() >= 0) {
				MetalBridge.renderTextureSlots(enc, texture.slot(), this.atlasTexture, this.atlasSampler,
					pv.reflection().samplerSlot(texture.name(), Stage.VERTEX), pv.reflection().samplerSlot(texture.name(), Stage.FRAGMENT));
			}
		}
	}

	/** The texture slot of the program's sampler name, or -1. Indexed: a bind allocates no iterator (PipelineSwitchAllocationTest). */
	private static int textureSlot(GbufferPlans.PackVariant pv, String name) {
		for (int i = 0; i < pv.textures().size(); i++) {
			ProgramRequest.TextureBinding texture = pv.textures().get(i);
			if (texture.name().equals(name)) {
				return texture.slot();
			}
		}
		return -1;
	}

	/**
	 * The sampler slot of the program's sampler name in one stage, as the program's reflection numbers it there; -1 when that stage
	 * does not sample through the name. Stage numbering is the stage's own, so the vertex and fragment slots may differ.
	 */
	private static int samplerSlot(GbufferPlans.PackVariant pv, String name, Stage stage) {
		return pv.reflection().samplerSlot(name, stage);
	}

	@Override
	public int vertexSamplerSlot(int uniformIndex) {
		return uniformIndex < this.vertexSamplerSlots.length ? this.vertexSamplerSlots[uniformIndex] : -1;
	}

	@Override
	public int fragmentSamplerSlot(int uniformIndex) {
		return uniformIndex < this.fragmentSamplerSlots.length ? this.fragmentSamplerSlots[uniformIndex] : -1;
	}

	@Override
	public void uniform(long enc, String name, Object value) {
		if (name.equals(TRANSFORMS) && value instanceof GpuBufferSlice slice) {
			this.modelViewAddress = MetalBridge.bufferAddress(slice.buffer()) + slice.offset();
			this.transformsSeen = true;
		} else if (name.equals(CLOUD_INFO)) {
			// 2026-11-14 (E4): the clouds prologue's block, bound from the game's own buffer. The value is a slice
			// (the frontend converts the game's whole-buffer setUniform before the backend: FrontendRenderPass calls
			// GpuBuffer.slice()), so the slice is the case the game runs and the bare buffer the tests' shorthand.
			this.cloudInfo = bufferOf(value);
			this.cloudInfoOffset = offsetOf(value);
		} else if (name.equals(CLOUD_FACES)) {
			this.cloudFaces = bufferOf(value);
			this.cloudFacesOffset = offsetOf(value);
		} else if ((name.equals(GAME_ATLAS) || name.equals(SODIUM_ATLAS)) && value instanceof TextureViewAndSampler ts) {
			atlas(MetalBridge.viewHandle(ts.view()), MetalBridge.samplerHandle(ts.sampler()));
			this.atlasView = ts.view();
		}
	}

	@Override
	public void vertexBuffer(int slot, long buffer, long offset) {
		// E4 diagnostics (the draw log): the buffer the next draws read their vertices from, with its offset — the
		// two-staged-buffer split's garbage-triangle hunt (2026-11-14, preview 10) needs it per draw.
		this.logVertexHandle = buffer;
		this.logVertexOffset = offset;
		if (slot == 0) {
			this.vertexHandle = buffer;
		}
	}

	@Override
	public void indexBuffer(long buffer, int indexType) {
		// E4 diagnostics (the draw log): the index buffer the next indexed draws read, and its element width.
		this.logIndexHandle = buffer;
		this.logIndexType = indexType;
	}

	/** The buffer a uniform value holds: the slice's buffer, or the buffer itself. */
	private static @Nullable GpuBuffer bufferOf(Object value) {
		if (value instanceof GpuBufferSlice slice) {
			return slice.buffer();
		}
		return value instanceof GpuBuffer buffer ? buffer : null;
	}

	/** The offset a uniform value binds at: the slice's offset, 0 for a whole buffer, -1 for none. */
	private static long offsetOf(Object value) {
		return value instanceof GpuBufferSlice slice ? slice.offset() : value instanceof GpuBuffer buffer ? 0 : -1;
	}

	@Override
	public boolean beforeDraw(long enc) {
		boolean drawn = drawThrough(enc);
		if (DrawLog.on()) {
			logDrawLine(drawn);
		}
		return drawn;
	}

	/** The draw's binds and per-draw bytes (beforeDraw's body); false drops the draw. */
	private boolean drawThrough(long enc) {
		if (this.game != null) {
			return drawGame(enc);
		}
		GbufferPlans.PackVariant pv = this.pack;
		if (pv == null) {
			return false;
		}
		if (this.mirrored) bindMirrors(enc, pv);
		boolean idsDefault = false;
		if (pv.perVertexIdSlot() >= 0) {
			// E4-D: the records the draw's vertex buffer and stride were uploaded with, or the defaults record for a draw
			// no upload covers (the prologue reads record 0 then: the QsDraw flag below). The defaults exist from the first
			// draw on, so no draw is dropped for lack of an upload.
			idsDefault = !this.vertexIds.bind(enc, pv.perVertexIdSlot(), this.vertexHandle, this.drawStride,
				this.vertexIdDefaults());
		}
		for (int slot = 0; slot < this.constantSegments.length; slot++) {
			MemorySegment bytes = this.constantSegments[slot];
			if (bytes != null) {
				MetalBridge.renderBytesAt(enc, slot, bytes.address(), (int) bytes.byteSize());
				if (DrawLog.on()) {
					noteEngineBuffer(slot, bytes.address(), 0, bytes.byteSize());
				}
			}
		}
		PrologueFacts prologue = pv.prologue();
		DistantFrame distant = DistantPipelines.isProgram(pv.program()) ? this.bindings.distantFrame() : null;
		if (distant == null && DistantPipelines.isProgram(pv.program())) {
			return false;  // no DH frame state this frame (DH has not projected yet): the draw is dropped, as a missing plan is
		}
		if (this.kind == PassKind.SHADOW) {
			if (!this.shadowViewBound) {
				throw new IllegalStateException("a shadow draw before the engine's shadow view is bound");
			}
			this.modelView.set(this.shadowModelView);
		} else if (distant != null) {
			// E7: the view rotation times the draw's offset (DH's terrain positions are section-relative, as its own shader places them).
			this.modelView.set(distant.drawModelView());
		} else if (prologue.servesModelView()) {
			// Sodium's terrain: the positions are camera-relative already (the region offset, and on beta.5 the camera
			// offset, are in the vertex), so the model-view is the view rotation alone: Sodium's own u_ModelViewMatrix
			// from the mirror, which its chunk shader multiplies the same way. The game binds no DynamicTransforms here.
			MemorySegment globals = this.constantSegments[prologue.globalsSlot()];
			if (globals == null) {
				if (!this.globalsWarned) {
					this.globalsWarned = true;
					warn("Sodium's u_Globals mirror is not bound for the terrain");
				}
				return false;
			}
			readMatrix(this.modelView, globals.address() + prologue.modelViewAt());
		} else if (this.transformsSeen) {
			readMatrix(this.modelView, this.modelViewAddress);
		} else {
			this.modelView.identity();
		}
		fromArray(packProjection(), this.projection);
		if (distant != null) {
			this.projection.set(distant.projection());  // E7: DH's projection, in OpenGL's depth convention
		}
		this.modelView.invert(this.modelViewInverse);
		this.projection.invert(this.projectionInverse);
		this.modelView.get3x3(this.normal).invert().transpose();

		long at = this.drawScratch;
		// atlasSize (E6-atlassize; Iris and OptiFine: the size of the atlas the pass binds, (0, 0) when none), per draw.
		this.bindings.atlasSize(this.atlasView, this.atlasSizeScratch);
		MemoryUtil.memPutInt(at + OFF_ATLAS_SIZE, this.atlasSizeScratch[0]);
		MemoryUtil.memPutInt(at + OFF_ATLAS_SIZE + 4, this.atlasSizeScratch[1]);
		if (this.transformsSeen) {
			MemoryUtil.memCopy(this.modelViewAddress + TRANSFORMS_COLOR_MODULATOR, at + OFF_COLOR_MODULATOR, VEC4_BYTES);
		} else {
			putWhite(at + OFF_COLOR_MODULATOR);
		}
		putMatrix(at + OFF_MODEL_VIEW, this.modelView);
		putMatrix(at + OFF_PROJECTION, this.projection);
		putMatrix(at + OFF_MODEL_VIEW_INVERSE, this.modelViewInverse);
		putMatrix(at + OFF_PROJECTION_INVERSE, this.projectionInverse);
		putNormal(at + OFF_NORMAL, this.normal);
		MemoryUtil.memPutInt(at + OFF_RENDER_STAGE, pv.renderStage());
		MemoryUtil.memPutFloat(at + OFF_ALPHA_TEST_REF, pv.alphaTestRef());
		MemoryUtil.memPutInt(at + OFF_IDS_DEFAULT, idsDefault ? 1 : 0);
		MetalBridge.renderBytesAt(enc, EngineSlots.DRAW, at, DRAW_BYTES);
		if (DrawLog.on()) {
			noteEngineBuffer(EngineSlots.DRAW, at, 0, DRAW_BYTES);
		}
		if (pv.clouds()) {
			bindClouds(enc);
		}
		return true;
	}

	/**
	 * The clouds draws' game bindings at the prologue's slots (CloudsLayout): the CloudInfo block as the engine's
	 * buffer, the CloudFaces texel buffer as an R8_SINT texture view — the game's {@code encodeFace} packs three
	 * BYTES per quad ({@code cellX >> 1}, {@code cellZ >> 1}, the direction and flags; javap 2026-11-14:
	 * CloudRenderer.encodeFace, and the pipeline's layout declares CloudFaces R8_SINT: BindGroupLayouts.CLOUD_INFO),
	 * so one byte is one texel; an R32 view would read four packed faces per texel and the pulling would decode
	 * garbage (the 2026-11-14 preview 9 failure). The view is remade only when the game binds a different buffer or
	 * its length changes. The game passes whole buffers, which the frontend converts to slices at offset 0
	 * (CloudRenderer.render: {@code setUniform(String, GpuBuffer)} then {@code GpuBuffer.slice()}).
	 */
	private void bindClouds(long enc) {
		if (this.cloudInfo != null) {
			MetalBridge.renderBuffer(enc, EngineSlots.CLOUD_INFO, MetalBridge.bufferHandle(this.cloudInfo),
				this.cloudInfoOffset);
			if (DrawLog.on()) {
				noteEngineBuffer(EngineSlots.CLOUD_INFO, MetalBridge.bufferHandle(this.cloudInfo), this.cloudInfoOffset,
					this.cloudInfo.size());
			}
		}
		if (this.cloudFaces != null) {
			long buffer = MetalBridge.bufferHandle(this.cloudFaces);
			long length = this.cloudFaces.size() - this.cloudFacesOffset;
			if (this.cloudFacesView == 0 || buffer != this.cloudFacesBuffer || length != this.cloudFacesLength) {
				if (this.cloudFacesView != 0) {
					MetalBridge.release(this.cloudFacesView);
				}
				this.cloudFacesView = MetalBridge.texelBufferView(this.plans.context(), buffer, this.cloudFacesOffset,
					length, 14, 1);
				this.cloudFacesBuffer = buffer;
				this.cloudFacesLength = length;
			}
			if (this.cloudFacesView != 0) {
				MetalBridge.renderTexture(enc, EngineSlots.CLOUD_FACES, this.cloudFacesView);
				if (DrawLog.on()) {
					noteEngineBuffer(EngineSlots.CLOUD_FACES, this.cloudFacesView, 0, length);
				}
			}
		}
	}

	/**
	 * A game variant's per-draw bytes: the projection in Metal's zero-to-one depth range (reversed under the game's
	 * convention), at its {@code Projection} index.
	 */
	private boolean drawGame(long enc) {
		if (this.projectionIndex >= 0) {
			float[] source = frameProjection();
			System.arraycopy(source, 0, this.reversedScratch, 0, 16);
			if (this.reversed) {
				toReversedZeroToOne(this.reversedScratch);
			} else {
				toZeroToOne(this.reversedScratch);
			}
			for (int i = 0; i < 16; i++) {
				MemoryUtil.memPutFloat(this.projectionScratch + 4L * i, this.reversedScratch[i]);
			}
			MetalBridge.renderBytesAt(enc, this.projectionIndex, this.projectionScratch, MATRIX_BYTES);
		}
		return true;
	}

	/**
	 * The projection a pack program's draw uses: the frame's (or the hand's) projection, its z row negated under the
	 * game's reversed convention (the pack's epilogue then yields {@code 1 - d}). The result is read at once.
	 */
	private float[] packProjection() {
		float[] source = frameProjection();
		if (!this.reversed) {
			return source;
		}
		System.arraycopy(source, 0, this.reversedScratch, 0, 16);
		for (int column = 0; column < 4; column++) {
			this.reversedScratch[4 * column + 2] = -this.reversedScratch[4 * column + 2];
		}
		return this.reversedScratch;
	}

	/**
	 * The projection the draw uses: the shadow projection in a shadow window, the hand projection while the kind is
	 * {@code HAND}, else the frame's (column-major).
	 */
	private float[] frameProjection() {
		if (this.kind == PassKind.SHADOW) {
			return this.shadowProjection;
		}
		if (this.kind == PassKind.HAND && this.handProjection != null) {
			return this.handProjection;
		}
		this.bindings.projection(this.frameScratch);
		return this.frameScratch;
	}

	/**
	 * Converts an OpenGL-convention projection (clip z from -1 to 1) to Metal's (z from 0 to 1) in place: each column's z
	 * row becomes (z + w) / 2, so a point at z = -w maps to 0 and one at z = w maps to w.
	 */
	static void toZeroToOne(float[] columnMajor) {
		for (int column = 0; column < 4; column++) {
			columnMajor[4 * column + 2] = 0.5f * (columnMajor[4 * column + 2] + columnMajor[4 * column + 3]);
		}
	}

	/**
	 * Converts an OpenGL-convention projection to the game's reversed Metal range in place: each column's z row becomes
	 * (w - z) / 2, so the near plane maps to 1 and the far plane to 0 (the game's convention; its depth clears to 0.0).
	 */
	static void toReversedZeroToOne(float[] columnMajor) {
		for (int column = 0; column < 4; column++) {
			columnMajor[4 * column + 2] = 0.5f * (columnMajor[4 * column + 3] - columnMajor[4 * column + 2]);
		}
	}

	@Override
	public void end(long enc) {
		this.pack = null;
		this.splitter = null;
		this.game = null;
		// The clouds' game bindings belong to the pass that set them: a later pass binds none, and a pass that sets none
		// keeps no buffer of a closed pass alive.
		this.cloudInfo = null;
		this.cloudFaces = null;
		// The atlas the pass's uniforms set belongs to the pass too: a later pass's pipeline with no atlas name binds none.
		this.atlasTexture = 0;
		this.atlasSampler = 0;
		this.atlasView = null;
		if (DrawLog.on()) {
			DrawLog.flush();
		}
	}

	@Override
	public void drawCall(int callKind, int callCount, int callInstances, int callFirst, int callBase, int callFirstInstance) {
		if (DrawLog.on()) {
			DrawLog.noteDraw(callKind, callCount, callInstances, callFirst, callBase, callFirstInstance);
			if (this.pack == null && this.game == null) {
				logDrawLine(false);
			}
		}
	}

	@Override
	public void multiDrawCall(int callKind, @Nullable PointerBuffer offsets, @Nullable IntBuffer a, @Nullable IntBuffer b,
			int drawCount, int callInstances, int callFirstInstance) {
		if (DrawLog.on()) {
			DrawLog.noteMulti(callKind, offsets, a, b, drawCount, callInstances, callFirstInstance);
			if (this.pack == null && this.game == null) {
				logDrawLine(false);
			}
		}
	}

	@Override
	public void pushConstants(ByteBuffer value) {
		if (DrawLog.on()) {
			DrawLog.notePush(value);
		}
	}

	/** E4 diagnostics: a pass the redirector took over, with its label and redirect (written as its PASS line at begin). */
	public void notePass(String label, MetalHooks.Redirect redirect) {
		this.logLabel = label;
		this.logRedirect = redirect;
	}

	/** Frees the native scratch. Idempotent; the delegate draws nothing after it. */
	public void close() {
		if (!this.closed) {
			this.closed = true;
			this.pack = null;
			this.game = null;
			MemoryUtil.nmemFree(this.drawScratch);
			MemoryUtil.nmemFree(this.projectionScratch);
			this.vertexIds.close();
			if (this.cloudFacesView != 0) {
				MetalBridge.release(this.cloudFacesView);
				this.cloudFacesView = 0;
			}
		}
	}

	/** Whether {@link #close} ran (the engine's release path closes the delegate of the set it drops). */
	public boolean isClosed() {
		return this.closed;
	}

	/** Reads a column-major mat4 from native memory into {@code m} (no allocation). */
	private void readMatrix(Matrix4f m, long address) {
		for (int i = 0; i < 16; i++) {
			this.matrixScratch[i] = MemoryUtil.memGetFloat(address + 4L * i);
		}
		m.set(this.matrixScratch, 0);
	}

	/** Copies a column-major float[16] into {@code m}. */
	private static void fromArray(float[] values, Matrix4f m) {
		m.set(values, 0);
	}

	/** Writes {@code m} at {@code at} as a column-major mat4. */
	private static void putMatrix(long at, Matrix4f m) {
		for (int column = 0; column < 4; column++) {
			for (int row = 0; row < 4; row++) {
				MemoryUtil.memPutFloat(at + 4L * (4 * column + row), m.get(column, row));
			}
		}
	}

	/** Writes the normal matrix at {@code at} as a std140 mat3 (three columns, each padded to a vec4). */
	private static void putNormal(long at, Matrix3f m) {
		for (int column = 0; column < 3; column++) {
			for (int row = 0; row < 3; row++) {
				MemoryUtil.memPutFloat(at + 16L * column + 4L * row, m.get(column, row));
			}
		}
	}

	/** Writes the white colour modulator (1, 1, 1, 1) at {@code at}: the value of a draw with no DynamicTransforms. */
	private static void putWhite(long at) {
		for (int i = 0; i < 4; i++) {
			MemoryUtil.memPutFloat(at + 4L * i, 1.0f);
		}
	}

	/** E4 diagnostics: the PASS line of a redirected pass (begin, while the draw log is on). */
	private void logPass(boolean passHasDepth) {
		MetalHooks.Redirect redirect = this.logRedirect;
		this.logRedirect = null;
		StringBuilder sb = DrawLog.line();
		sb.append("PASS f=").append(DrawLog.frameNumber()).append(" p=").append(DrawLog.nextPass())
			.append(" label=\"").append(this.logLabel == null ? "?" : this.logLabel).append("\" kind=").append(this.kind)
			.append(" phase=").append(this.phase).append(" hasDepth=").append(passHasDepth ? 1 : 0)
			.append(" reversed=").append(this.reversed ? 1 : 0);
		if (redirect != null) {
			sb.append(" size=").append(redirect.width()).append('x').append(redirect.height());
			int[] targets = this.plans.layout(this.phase);
			long[] colours = redirect.colors();
			for (int i = 0; i < colours.length; i++) {
				float[] clear = redirect.clears() == null ? null : redirect.clears()[i];
				String load = clear == null ? "load" : clear.length == 0 ? "dontCare" : "clear";
				sb.append(" att").append(i).append("=colortex").append(i < targets.length ? targets[i] : -1)
					.append(':').append(load).append(":store=backend");
			}
			sb.append(" depth=").append(redirect.depth() != 0 ? "engine:load" : "game-own");
		}
		DrawLog.emit(sb);
	}

	/** E4 diagnostics: the DRAW line of the draw the backend reported (drawn: whether the draw was encoded). */
	private void logDrawLine(boolean drawn) {
		GbufferPlans.PackVariant pv = this.pack;
		GbufferPlans.GameVariant gv = this.game;
		StringBuilder sb = DrawLog.line();
		sb.append("DRAW f=").append(DrawLog.frameNumber()).append(" p=").append(DrawLog.passNumber())
			.append(" n=").append(DrawLog.drawNumber()).append(drawn ? " drawn" : " DROPPED");
		DrawLog.appendCall(sb);
		sb.append(" vbuf=0x").append(Long.toHexString(this.logVertexHandle)).append('@').append(this.logVertexOffset)
			.append(" ibuf=0x").append(Long.toHexString(this.logIndexHandle)).append(':').append(this.logIndexType);
		sb.append(" pipe=").append(this.logPipeline).append(" loc=").append(this.logLocation)
			.append(" phase=").append(this.phase).append(" kind=").append(this.kind);
		if (this.domain == GbufferMapping.Domain.BLOCK_ENTITY) {
			sb.append(" domain=BLOCK_ENTITY");  // the block-entity frame's draws (the E4 routing rules)
		}
		if (pv != null) {
			sb.append(" variant=pack:").append(pv.program()).append(" renderStage=").append(pv.renderStage())
				.append(" alphaRef=").append(DrawLog.fmt(pv.alphaTestRef())).append(" idSlot=").append(pv.perVertexIdSlot());
		} else if (gv != null) {
			sb.append(" variant=game:").append(gv.ownPipeline() ? "own" : "copy");
		} else {
			sb.append(" variant=none");
		}
		GbufferPlans.DepthBinding depth = pv != null ? this.packDepth : this.gameDepth;
		if (depth != null) {
			sb.append(" depth=(").append(depth.description()).append(") reversed=").append(this.reversed ? 1 : 0)
				.append(" bias=(").append(DrawLog.fmt(depth.biasConstant())).append(',')
				.append(DrawLog.fmt(depth.biasSlope())).append(')');
		}
		if (pv != null || gv != null) {
			boolean cull = pv != null ? pv.cull() : gv.cull();
			boolean wire = pv != null ? pv.wireframe() : gv.wireframe();
			int prim = pv != null ? pv.primitive() : gv.primitive();
			sb.append(" cull=").append(cull ? 1 : 0).append(" wire=").append(wire ? 1 : 0).append(" prim=").append(prim);
		}
		sb.append(" hasDepth=").append(this.hasDepth ? 1 : 0).append(" slot26=");
		DrawLog.appendPush(sb);
		sb.append(" samplers=[");
		if (pv != null) {
			for (ProgramRequest.TextureBinding texture : pv.textures()) {
				sb.append(texture.name()).append('@').append(texture.slot()).append("/v")
					.append(pv.reflection().samplerSlot(texture.name(), Stage.VERTEX)).append("/f")
					.append(pv.reflection().samplerSlot(texture.name(), Stage.FRAGMENT)).append(' ');
			}
		} else if (gv != null) {
			for (int i = 0; i < this.uniformNames.size(); i++) {
				String name = this.uniformNames.get(i);
				if (name.startsWith("Sampler")) {
					sb.append(name).append('@').append(i).append(' ');
				}
			}
		}
		sb.append(']');
		if (pv != null) {
			appendEngineSlots(sb);
		}
		if (drawn && pv != null) {
			copyColumnMajor(this.modelView, DrawLog.MODEL_VIEW);
			copyColumnMajor(this.projection, DrawLog.PROJECTION);
			DrawLog.appendRows(sb, "mv", DrawLog.MODEL_VIEW);
			DrawLog.appendRows(sb, "qsProj", DrawLog.PROJECTION);
		} else if (drawn && gv != null && this.projectionIndex >= 0) {
			if (this.transformsSeen) {
				readMatrix(this.modelView, this.modelViewAddress);
				copyColumnMajor(this.modelView, DrawLog.MODEL_VIEW);
				DrawLog.appendRows(sb, "mv", DrawLog.MODEL_VIEW);
			}
			System.arraycopy(this.reversedScratch, 0, DrawLog.PROJECTION, 0, 16);
			DrawLog.appendRows(sb, "gameProj", DrawLog.PROJECTION);
		}
		MemorySegment globals = this.constantSegments[EngineSlots.SODIUM_GLOBALS];
		if (globals != null) {
			DrawLog.appendCamera(sb, globals);
		}
		DrawLog.emit(sb);
	}

	/** Copies a matrix into a column-major float[16] (no allocation). */
	private static void copyColumnMajor(Matrix4f m, float[] out) {
		for (int column = 0; column < 4; column++) {
			for (int row = 0; row < 4; row++) {
				out[4 * column + row] = m.get(column, row);
			}
		}
	}
	/** Records an engine-slot binding for the next DRAW line (the draw-log's engine= list); no work while the log is off. */
	private void noteEngineBuffer(int slot, long handle, long offset, long length) {
		if (slot >= EngineSlots.PER_VERTEX_IDS && slot <= EngineSlots.CLOUD_FACES) {
			this.logSlotHandle[slot] = handle;
			this.logSlotOffset[slot] = offset;
			this.logSlotLength[slot] = length;
		}
	}

	/**
	 * The engine's slots 20..30 bound for this draw (handle, offset, length), and for {@code CLOUD_INFO} the block's
	 * first 32 bytes as floats (CloudColor, CloudOffset and CellSize: what the clouds program reads).
	 */
	private void appendEngineSlots(StringBuilder sb) {
		sb.append(" engine=[");
		boolean any = false;
		for (int slot = EngineSlots.PER_VERTEX_IDS; slot <= EngineSlots.CLOUD_FACES; slot++) {
			long handle = this.logSlotHandle[slot];
			if (handle == 0) {
				continue;
			}
			any = true;
			sb.append(slot).append(":h=0x").append(Long.toHexString(handle)).append(" o=").append(this.logSlotOffset[slot])
				.append(" l=").append(this.logSlotLength[slot] < 0 ? "?" : this.logSlotLength[slot]).append(' ');
			if (slot == EngineSlots.CLOUD_INFO && this.cloudInfo != null) {
				long at = MetalBridge.bufferAddress(this.cloudInfo) + this.cloudInfoOffset;
				sb.append("cloudInfo=[");
				for (int i = 0; i < 8; i++) {
					sb.append(DrawLog.fmt(MemoryUtil.memGetFloat(at + 4L * i)));
					if (i < 7) {
						sb.append(' ');
					}
				}
				sb.append("] ");
			}
		}
		if (any) {
			sb.setLength(sb.length() - 1);
		}
		sb.append(']');
	}
}

