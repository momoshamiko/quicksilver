// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.terrain;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.GpuSampler;
import it.unimi.dsi.fastutil.objects.Reference2ReferenceOpenHashMap;
import java.util.Collection;
import java.util.function.Consumer;
import net.caffeinemc.mods.sodium.client.SodiumClientMod;
import net.caffeinemc.mods.sodium.client.gui.SodiumOptions;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderList;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.CoordinateSectionVisitor;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.SortedRenderLists;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.VisibleChunkCollector;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.storage.SectionStorage;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.render.chunk.tree.RemovableMultiForest;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import net.caffeinemc.mods.sodium.client.render.viewport.frustum.Frustum;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import net.caffeinemc.mods.sodium.client.util.iterator.ByteIterator;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jspecify.annotations.Nullable;
import org.joml.Matrix4fc;
import org.joml.Vector3d;

import dev.mcms.shaders.pack.ShaderProperties;
import dev.mcms.shaders.runtime.shadow.CasterCull;
import dev.mcms.shaders.runtime.shadow.ShadowCamera;

/**
 * The light's terrain list (E5-B2; plan.md E5; engine-sodium-hooks.md section 4). The engine owns a second render list
 * per region, built at the tail of {@code SodiumWorldRenderer.setupTerrain} from the shadow camera's frustum. While the
 * list is built, {@code RenderRegion.getRenderList} answers the engine's list ({@link #answerRenderList}), so the camera's
 * own lists and their batches are never written by the light's build, except the shared face-culled batches, which
 * {@link #clearCachedBatches} resets before and after the shadow draws.
 *
 * <p>The traversal also finds the sections that still wait for their first build (the camera's occlusion walk never reaches
 * some of them, so Sodium never builds them on its own). They are submitted after the traversal through Sodium's own path
 * ({@link LightInitialBuilds}, javap 2026-11-14), within the camera's idle time and a budget: E6-B2.
 *
 * <p>The build is frustum and render distance only: no occlusion graph. The frustum is the light's, and with
 * {@code shadow.culling} on it also leaves out the casters that cannot shade the camera's view ({@link CasterFrustum}). It
 * uses Sodium's ordinary
 * {@link VisibleChunkCollector} (the camera's own build path, javap 26.3 beta.1 and beta.5: the collector's
 * {@code createRenderLists} is the {@code RenderListProvider} default). Sodium's {@code FallbackVisibleChunkCollector}
 * would add the pending-task tree of the out-of-graph path, which the light never reads, and allocates two section trees
 * per build. The traversal goes through {@link TerrainEngine#adapter()}, because beta.5 adds a boolean to
 * {@code RemovableMultiForest.traverse}; the light's list passes {@code false} (cylindrical search): beta.5's own boolean
 * is the {@code useSphericalRenderFog} option, which the light has no counterpart for [inference: the shadow list does not
 * depend on the fog option].
 *
 * <p>The light's matrices are one frame old when the list is built: {@code FrameUniforms} updates the shadow camera in the
 * frame-start hook at the head of {@code GameRenderer.render}. That render runs after the extraction, which holds
 * {@code setupTerrain} (called from {@code LevelExtractorMixin.cullTerrain}; javap 2026-11-14). {@code Minecraft.renderFrame}
 * calls {@code GameRenderer.extract} (bytecode 426) before {@code GameRenderer.render} (500).
 * {@link #LIST_MARGIN} widens the light's frustum by one section, so a camera move of up to that many blocks in one frame
 * stays inside the list.
 *
 * <p>Single owner: the render thread. {@link #get()} is the one instance. Allocations per build: the viewport and its
 * frustum (the caster frustum on top), Sodium's {@code VisibleChunkCollector}, and a region's list when a region is first
 * answered. A build with first
 * builds adds their job collector and budget ({@link LightInitialBuilds}; Sodium's deferred path makes the same two); no
 * per-section allocation.
 */
public final class ShadowTerrain {

	/**
	 * The light's frustum widening in blocks: one section. The light's matrices are one frame old at the build (see the class
	 * comment), so the list must cover one frame's camera move.
	 */
	public static final float LIST_MARGIN = 16f;

	private static final ShadowTerrain INSTANCE = new ShadowTerrain();

	/** The light's list of each region, keyed by the region object. */
	private Reference2ReferenceOpenHashMap<RenderRegion, ChunkRenderList> lists = new Reference2ReferenceOpenHashMap<>();
	/** The region set the lists belong to (an identity key: a new render section manager makes new regions). */
	private @Nullable Object listOwner;

	private @Nullable ShadowCamera camera;
	private boolean building;

	private @Nullable SortedRenderLists sorted;
	private @Nullable CameraTransform transform;
	private @Nullable RenderRegionManager regions;

	private @Nullable ChunkRenderer renderer;
	private @Nullable ChunkRenderMatrices matrices;
	private @Nullable FogParameters fog;
	private @Nullable GpuSampler sampler;
	private @Nullable GpuBufferSlice uniforms;
	private @Nullable GpuBuffer sectionTime;
	private boolean translucentSorted;
	/** Whether {@code prepare} has filled the batches of every pass since the last {@link #clearCachedBatches}. */
	private boolean prepared;
	/** The light's first builds: the set's candidates and failure state (see {@link LightInitialBuilds}). */
	private final LightInitialBuilds initialBuilds = new LightInitialBuilds();
	/** The cull of casters that cannot shade the view, set before each traversal that uses it (see {@link #build}). */
	private final CasterCull casters = new CasterCull();

	private ShadowTerrain() {
	}

	/** The engine's one instance. */
	public static ShadowTerrain get() {
		return INSTANCE;
	}

	/**
	 * Answers {@code RenderRegion.getRenderList}: while the light's list is being built, the region's engine list; the
	 * region's own list otherwise. Called from {@code SodiumEngineRenderRegionMixin} on the render thread.
	 *
	 * @param region the region asked
	 * @param main   the region's own list (the camera's)
	 */
	public static ChunkRenderList answerRenderList(RenderRegion region, ChunkRenderList main) {
		ShadowTerrain shadow = INSTANCE;
		return shadow.building ? shadow.listFor(region) : main;
	}

	/**
	 * The light's viewport with no widening: the light's frustum ({@link LightFrustum}, Sodium's frustum test with unit-normal
	 * planes) and the camera position for the camera-relative section boxes ({@code Viewport.isBoxVisible}).
	 *
	 * @param viewProjection the light's matrix ({@code ShadowCamera.viewProjection()})
	 * @param cameraX        the camera's world X (raw position)
	 * @param cameraY        the camera's world Y
	 * @param cameraZ        the camera's world Z
	 */
	public static Viewport lightViewport(Matrix4fc viewProjection, double cameraX, double cameraY, double cameraZ) {
		return lightViewport(viewProjection, 0f, cameraX, cameraY, cameraZ);
	}

	/**
	 * As {@link #lightViewport(Matrix4fc, double, double, double)}, with the frustum widened by {@code expansion} blocks on
	 * every side (the build uses {@link #LIST_MARGIN}).
	 */
	public static Viewport lightViewport(Matrix4fc viewProjection, float expansion, double cameraX, double cameraY, double cameraZ) {
		return new Viewport(new LightFrustum(viewProjection, expansion), new Vector3d(cameraX, cameraY, cameraZ));
	}

	/**
	 * Attaches the shadow camera of the active set (called by {@code ShadowLists.attach} at set activation). Without an
	 * attached camera {@link #build} does nothing: the engine is inert while no set with shadows is active.
	 */
	public void attach(ShadowCamera camera) {
		this.camera = camera;
		this.initialBuilds.reset();
	}

	/** Whether a camera is attached (the mixin reads nothing else while none is). */
	public boolean attached() {
		return this.camera != null;
	}

	/**
	 * Whether the light's list has been built for the attached camera: false until the first {@code setupTerrain} tail with
	 * the camera attached. The shadow frame's hook can run before that tail (the first frame of a set), so its terrain steps
	 * skip the frame while this is false (ShadowPassOps).
	 */
	public boolean built() {
		return this.sorted != null && this.transform != null;
	}

	/**
	 * The light's camera transform of the last build, or null before one. The shadow pass's Sodium globals take the light's
	 * camera offset from it (ShadowPassOps).
	 */
	public @Nullable CameraTransform lightTransform() {
		return this.transform;
	}

	/** Detaches the camera and drops the engine lists (set off, pack switch, reload, device close, a failed shadow step). */
	public void detach() {
		this.camera = null;
		this.lists.clear();
		this.listOwner = null;
		this.sorted = null;
		this.transform = null;
		this.regions = null;
		this.prepared = false;
		this.initialBuilds.reset();
		unbindDraw();
	}

	/**
	 * Builds the light's list. Called by {@code SodiumEngineShadowTerrainMixin} at the TAIL of
	 * {@code SodiumWorldRenderer.setupTerrain}, after the camera's lists are final. Uses the attached camera's matrices as they
	 * were last updated (one frame old; see the class comment). The sections the list reaches that still wait for their first
	 * build are submitted after the traversal, through Sodium's own path ({@link LightInitialBuilds}). The camera's idle state
	 * is read before the traversal, so a busy frame makes no candidate lookups.
	 *
	 * <p>With {@code shadow.culling} {@code true} or {@code reversed} and an orthographic light, the list also leaves out the
	 * sections that cannot cast a shadow into the camera's view ({@link CasterFrustum}, {@link CasterCull}; E9): the
	 * camera's clip matrix is this frame's, the light's direction one frame old, as the light's matrices.
	 *
	 * @param tree           {@code RenderSectionManager.renderableSectionTree}
	 * @param regions        {@code RenderSectionManager.regions}
	 * @param sections       {@code RenderSectionManager.renderSections} (looks the traversed sections up for their first build)
	 * @param frame          {@code RenderSectionManager.frame}
	 * @param searchDistance the render distance in blocks ({@code RenderSectionManager.getRenderDistance}, private: the
	 *                      mixin computes the same value); the light's distance is the lesser of it and the camera's
	 *                      {@code cullRadius} when that is set
	 * @param cameraX       the camera's world X (raw position)
	 * @param cameraY       the camera's world Y
	 * @param cameraZ       the camera's world Z
	 * @param cameraClip    the camera's clip matrix from camera-relative world space ({@code setupTerrain}'s position
	 *                      matrix: the game frustum's projection times its rotation-only model view)
	 * @param port          Sodium's camera state and submission for the first builds of this build
	 */
	public void build(RemovableMultiForest tree, RenderRegionManager regions, SectionStorage sections, int frame,
			float searchDistance, double cameraX, double cameraY, double cameraZ, Matrix4fc cameraClip,
			LightInitialBuilds.Port port) {
		ShadowCamera cam = this.camera;
		if (cam == null) {
			return;
		}
		Frustum frustum = new LightFrustum(cam.viewProjection(), LIST_MARGIN);
		ShadowCamera.Settings settings = cam.settings();
		if (settings.culling() != ShaderProperties.ShadowCulling.FALSE && settings.fovDegrees() <= 0f) {
			// The light's view Z axis (row 2 of its rotation) points toward the light.
			Matrix4fc light = cam.modelView();
			float keep = settings.culling() == ShaderProperties.ShadowCulling.REVERSED ? settings.voxelDistance() : -1f;
			if (this.casters.set(cameraClip, light.m02(), light.m12(), light.m22(), keep)) {
				frustum = new CasterFrustum(frustum, this.casters);
			}
		}
		Viewport viewport = new Viewport(frustum, new Vector3d(cameraX, cameraY, cameraZ));
		float distance = cam.cullRadius() >= 0f ? Math.min(cam.cullRadius(), searchDistance) : searchDistance;
		pruneRegions(regions, regions.getLoadedRegions());
		this.regions = regions;
		VisibleChunkCollector collector = new VisibleChunkCollector(regions, frame);
		CoordinateSectionVisitor visitor = this.initialBuilds.begin(collector, sections, port);
		setBuilding(true);
		try {
			tree.prepareForTraversal();
			TerrainEngine.adapter().traverseForest(tree, visitor, viewport, distance, false);
			this.sorted = collector.createRenderLists(viewport);
			this.transform = viewport.getTransform();
			this.prepared = false;
		} finally {
			setBuilding(false);
		}
		this.initialBuilds.end(port);
	}

	/**
	 * Sets whether {@link #answerRenderList} answers the light's lists. {@link #build} sets it around the traversal; the
	 * package's tests set it to exercise the answer without a Sodium traversal.
	 */
	static void setBuilding(boolean value) {
		INSTANCE.building = value;
	}

	/**
	 * Binds the GPU inputs of the shadow draws: the renderer, the shadow matrices, the fog, the block atlas sampler, the
	 * uniform buffers, and the camera's translucency-sorting flag. The shadow pass binds its own u_Globals, never the main
	 * frame's (ADR 0007 decision 3). The draws read them; calling this again with the same values is safe.
	 *
	 * @param translucentSorted the camera renderer's {@code useTranslucencySorting}: the mesher stores a translucent section
	 *                          with its own index data while sorting is on, and only the sorted path draws that data right
	 *                          (with the shared quad index buffer its triangles join the wrong vertices: long streaks)
	 */
	public void bindDraw(ChunkRenderer renderer, ChunkRenderMatrices matrices, FogParameters fog, GpuSampler sampler,
			GpuBufferSlice uniforms, GpuBuffer sectionTime, boolean translucentSorted) {
		this.renderer = renderer;
		this.matrices = matrices;
		this.fog = fog;
		this.sampler = sampler;
		this.uniforms = uniforms;
		this.sectionTime = sectionTime;
		this.translucentSorted = translucentSorted;
	}

	private void unbindDraw() {
		this.renderer = null;
		this.matrices = null;
		this.fog = null;
		this.sampler = null;
		this.uniforms = null;
		this.sectionTime = null;
	}

	/** Solid terrain of the light's list through {@code ChunkRenderer.prepare/render}, every face (see {@link #draw}). */
	public void drawSolid(RenderPass renderPass) {
		draw(DefaultTerrainRenderPasses.SOLID, renderPass);
	}

	/** Cutout terrain of the light's list; same path as {@link #drawSolid}. */
	public void drawCutout(RenderPass renderPass) {
		draw(DefaultTerrainRenderPasses.CUTOUT, renderPass);
	}

	/**
	 * Translucent terrain of the light's list; same path as {@link #drawSolid}, with the camera's sorting flag: the sections'
	 * own index data in the camera's order. Depth does not depend on the order; a blended shadow colour does, and it gets
	 * the camera's order [inference: no per-light sort is documented].
	 */
	public void drawTranslucent(RenderPass renderPass) {
		draw(DefaultTerrainRenderPasses.TRANSLUCENT, renderPass);
	}

	/**
	 * One pass of the light's list. {@code prepare} fills the batches of every pass, so it runs once per shadow pass (on the
	 * first draw after {@link #clearCachedBatches}); later draws only render. Sodium's block face culling keeps only the faces
	 * that look toward the camera ({@code DefaultChunkRenderer.fillCommandBuffer} reads {@code performance.useBlockFaceCulling}
	 * and {@code getVisibleFaces} from the camera position); the light sees faces the camera does not, so the option reads
	 * false while the light's batches are filled (with it on, faces turned away from the camera left one-texel holes in
	 * shadowcolor0) and is restored at once, on the render thread that owns it.
	 */
	private void draw(TerrainRenderPass pass, RenderPass renderPass) {
		SortedRenderLists lists = this.sorted;
		CameraTransform cameraTransform = this.transform;
		ChunkRenderer chunkRenderer = this.renderer;
		ChunkRenderMatrices shadowMatrices = this.matrices;
		FogParameters fogParameters = this.fog;
		GpuSampler textureSampler = this.sampler;
		GpuBufferSlice globals = this.uniforms;
		GpuBuffer timeInfo = this.sectionTime;
		if (lists == null || cameraTransform == null || chunkRenderer == null || shadowMatrices == null
			|| fogParameters == null || textureSampler == null || globals == null || timeInfo == null) {
			throw new IllegalStateException("the light's terrain drawn before its build or its bindDraw");
		}
		if (!this.prepared) {
			prepareBatches(lists, cameraTransform, chunkRenderer);
			this.prepared = true;
		}
		chunkRenderer.render(shadowMatrices, lists, pass, cameraTransform, fogParameters, this.translucentSorted, renderPass,
			textureSampler, globals, timeInfo, null);
	}

	/** Fills the light's batches of every pass, with the block face culling option off (see {@link #draw}). */
	private void prepareBatches(SortedRenderLists lists, CameraTransform cameraTransform, ChunkRenderer chunkRenderer) {
		SodiumOptions.PerformanceSettings performance = SodiumClientMod.options().performance;
		boolean faceCulling = performance.useBlockFaceCulling;
		performance.useBlockFaceCulling = false;
		try {
			chunkRenderer.prepare(lists, cameraTransform, this.translucentSorted);
		} finally {
			performance.useBlockFaceCulling = faceCulling;
		}
	}

	/**
	 * Clears every loaded region's cached face-culled batches, and makes the next draw prepare again. The cache is one per
	 * region and pass, shared with the camera, and {@code prepare} returns a filled batch as it stands; so the shadow pass
	 * calls this before its first draw (so no camera batch of the previous frame is reused) and after its last one (before the
	 * camera's prepare).
	 */
	public void clearCachedBatches() {
		this.prepared = false;
		RenderRegionManager current = this.regions;
		if (current == null) {
			return;
		}
		for (RenderRegion region : current.getLoadedRegions()) {
			region.clearAllCachedBatches();
		}
	}

	/** The region's engine list, or null when none exists yet (tests and diagnostics). */
	public @Nullable ChunkRenderList listOf(RenderRegion region) {
		return this.lists.get(region);
	}

	/**
	 * The block entities of the light's culled sections, one call each: Sodium's walk of the camera's lists
	 * ({@code SodiumWorldRenderer.iterateVisibleBlockEntities}, javap 26.3 beta.1), over the light's own sorted lists: each
	 * list's sections with entities, then the region's culled block entities of each section. Global block entities are
	 * not in the lists and are not included (the gap is listed in the E5 plan). Allocates the two list iterators per call.
	 */
	public void forEachBlockEntity(Consumer<BlockEntity> consumer) {
		SortedRenderLists lists = this.sorted;
		if (lists == null) {
			return;
		}
		for (var it = lists.iterator(); it.hasNext();) {
			ChunkRenderList list = it.next();
			RenderRegion region = list.getRegion();
			ByteIterator sections = list.sectionsWithEntitiesIterator();
			if (sections == null) {
				continue;
			}
			while (sections.hasNext()) {
				BlockEntity[] culled = region.getCulledBlockEntities(sections.nextByteAsInt());
				if (culled == null) {
					continue;
				}
				for (BlockEntity entity : culled) {
					if (entity != null) {
						consumer.accept(entity);
					}
				}
			}
		}
	}

	private ChunkRenderList listFor(RenderRegion region) {
		return this.lists.computeIfAbsent(region, ChunkRenderList::new);
	}

	/**
	 * Drops the lists of regions that are no longer loaded. A new owner (a new render section manager: a world reload) drops
	 * every list. Within one owner the map is rebuilt from the loaded regions only when it holds more lists than there are
	 * loaded regions (a region unloaded since the last build), so a steady frame does no work here.
	 */
	void pruneRegions(Object owner, Collection<RenderRegion> loaded) {
		if (owner != this.listOwner) {
			this.lists.clear();
			this.listOwner = owner;
			return;
		}
		if (this.lists.size() > loaded.size()) {
			Reference2ReferenceOpenHashMap<RenderRegion, ChunkRenderList> kept = new Reference2ReferenceOpenHashMap<>(loaded.size());
			for (RenderRegion region : loaded) {
				ChunkRenderList list = this.lists.get(region);
				if (list != null) {
					kept.put(region, list);
				}
			}
			this.lists = kept;
		}
	}

	/** The number of region lists held (tests: the prune's effect). */
	int listCount() {
		return this.lists.size();
	}
}
