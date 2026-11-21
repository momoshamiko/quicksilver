// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.terrain;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkUpdateTypes;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.estimation.UploadResourceBudget;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.executor.ChunkJobCollector;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.CoordinateSectionVisitor;
import net.caffeinemc.mods.sodium.client.render.chunk.storage.SectionStorage;
import net.minecraft.core.SectionPos;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The first builds of the sections the light's list reaches (E6-B2; plan.md E6 "shadow builds"; the light's list is
 * {@link ShadowTerrain}). Without this, a section that only the light sees never gets geometry, so its casters are missing
 * from the shadow pass and from the voxel volume (the dense-scene mismatch of 2026-11-14: same highest voxel, fewer voxels in
 * a column; region A agreed 96.2 %).
 *
 * <p>Mechanism, javap 2026-11-14 on Sodium 0.9.3-beta.1 and 0.9.3-beta.5 (both jars ADR 0007 allows; bytecode offsets are the
 * same in both unless a jar is named):
 * <ol>
 * <li>An unbuilt section is in the light's tree. {@code RenderSectionManager.onSectionAdded} adds every section with a block
 * other than air to {@code renderableSectionTree} (bytecode 117-123), then sets its pending update to
 * {@code ChunkUpdateTypes.INITIAL_BUILD} (8) (126-134). Only an air-only section becomes built-empty, through
 * {@code updateSectionInfo(EMPTY)} (98-110). So {@code ShadowTerrain.build}'s traversal lists sections that have no
 * geometry yet; their {@code RenderRegion} draws nothing until a build finishes.</li>
 * <li>The camera's cull is the only source of first builds. {@code CullTask.runTask} collects pending sections with
 * {@code TaskCollectingTree}, built with {@code CullType.WIDE}: {@code bfsWidth} 1, {@code isFrustumTested} false,
 * {@code isFogCulled} false (the {@code CullType} constructor, beta.1 and beta.5). Its {@code visit} adds each visited section
 * with a pending update ({@code TaskCollectingTree.visit}, 6-19). {@code consumeCullTaskResults} copies the result into
 * {@code taskLists} (149-154), which {@code submitDeferredSectionTasks} drains. The reach is therefore the render distance
 * ({@code getSearchDistanceForCullType} returns {@code getRenderDistance} for a type that is not fog-culled): neither the
 * camera frustum nor the fog distance limits it.</li>
 * <li>The camera's walk does not pass an unbuilt or closed section. {@code OcclusionCuller.processQueue} visits the dequeued
 * section ({@code visitorWide.visit}, 79-85), reads {@code getVisibilityData()} (98), and skips the neighbours when it is null,
 * that is, the section is unbuilt (105-108). A built section that has no open connection (solid rock, [inference: its
 * visibility data holds no connections]) also yields no neighbours. A section in a cave that no open air connects to the
 * camera is therefore never visited, so it never receives its first build.</li>
 * <li>INITIAL_BUILD never reaches {@code importantTasks}. {@code upgradePendingUpdate} adds a section there only when
 * {@code ChunkUpdateTypes.isImportant} holds (IMPORTANT = 4; the gate at 38-41; the add at 45). The light's sections are
 * outside that path.</li>
 * <li>Order inside {@code SodiumWorldRenderer.setupTerrain}: {@code prepareRenderTrees} (408), {@code cleanupAndFlip} (429),
 * {@code updateChunks} (439), {@code processChunkBuilds} (461), {@code finalizeRenderLists} (502), then the engine's TAIL.
 * The light's submissions therefore come after the camera's own in the frame.</li>
 * </ol>
 *
 * <p>So the light's list holds sections that nothing in Sodium will ever build while the camera stays put. This class submits
 * them through Sodium's own path, {@code RenderSectionManager.submitSectionTask} (javap: the 3-argument private method,
 * bytecode 0-22 on both jars; it calls the 5-argument one, which runs {@code createRebuildTask} (37), {@code builder.scheduleTask}
 * (157) and {@code clearPendingUpdate} (192), the same offsets in beta.1 and beta.5). That path creates the rebuild task,
 * schedules it on the builder and clears the pending update. Only the candidates the light's traversal reaches are submitted.
 * A build is started only while the camera has no deferred task waiting ({@code taskLists} empty after the camera's own
 * updates), and only within a budget shaped as Sodium's deferred path shapes its own: the builder's remaining time and half
 * of the staging buffer's upload limit (see the next paragraph). The camera's lists, its importance and its order are untouched.
 *
 * <p>Upload budget (E6 review item 7; javap 2026-11-15, the same bytecode on beta.1 and beta.5 at every offset cited). The
 * camera's {@code RenderSectionManager.updateChunks} makes one {@code LimitedResourceBudget} (bytecode 98-134: duration
 * {@code max(averageFrameDuration * 0.3f, 10 ms)}, size {@code staging.getUploadSizeLimit}). It passes that same object to
 * {@code submitSectionTasks} (187-196), which drains the three {@code importantTasks} modes ZERO_FRAMES, ONE_FRAME and ALWAYS
 * through {@code submitImportantSectionTasks}, then the {@code taskLists} through {@code submitDeferredSectionTasks}
 * (0-43). The light's tail runs after {@code updateChunks} in the same frame and makes a second budget of the same size. So a
 * frame could carry two full budgets. The light's budget is half of the camera's, in duration and in size
 * ({@code SodiumInitialBuildPort.firstBuildBudget}): the two together spend at most one and a half of the camera's budget.
 *
 * <p>The rule not chosen: skip the light's first builds while {@code importantTasks} holds any section. It would starve the
 * light. {@code submitImportantSectionTasks} removes a section from its set only after it submits it or drops it (the iterator
 * remove at 138-140). A section that the occlusion tree does not show ({@code isSectionVisible} false, bytecode 102-113: {@code
 * ifeq 16} jumps back to the loop head without the remove) stays in the set. A hidden section near the camera with a pending
 * important update therefore keeps the set non-empty for as long as it stays hidden, even while the camera stands still. The
 * cost of the chosen rule: the light's first builds get half the upload time and size on every frame, idle camera or not, so
 * the light builds fewer sections per frame. The candidates stay unbuilt and the next frame's traversal finds them again, so
 * the list fills over more frames [inference: the rate is about half; not measured].
 *
 * <p>Allocations: the set's {@link LightInitialBuilds} holds one {@link Collect} per section storage and one candidate list,
 * reused across frames; nothing is allocated per section. A build with candidates makes one job collector and one budget
 * through the port; Sodium's deferred path makes the same two. The traversal's {@code VisibleChunkCollector} and viewport are
 * made per build in {@link ShadowTerrain#build}.
 *
 * <p>Failures (E6 review item 13): a throw from the submission is logged once. The light's list is already built when the
 * submission runs, so it stays; the first builds are then off for the rest of the set ({@link #reset} starts a new set).
 */
public final class LightInitialBuilds {

	/**
	 * What {@link #end} needs from Sodium's live state: the camera's pending work, a collector and an upload budget for one
	 * build, and Sodium's own submission. {@link SodiumInitialBuildPort} implements it from the private fields and invoker of
	 * {@code RenderSectionManager} that the mixin layer's accessor exposes.
	 */
	public interface Port {
		/** Whether the camera has no deferred build waiting (its {@code taskLists} is empty or absent). */
		boolean cameraIdle();

		/** A collector for one build: the builder's remaining time as Sodium's deferred path gives it, results to the manager. */
		ChunkJobCollector newCollector();

		/** An upload budget for one build: half of the camera's budget (see the class comment). */
		UploadResourceBudget newBudget();

		/** Sodium's submission of one section ({@code RenderSectionManager.submitSectionTask}); clears its pending update. */
		void submit(ChunkJobCollector collector, RenderSection section, UploadResourceBudget budget);
	}

	private static final Logger LOG = LoggerFactory.getLogger("Quicksilver");

	/** The candidates of the current build, in traversal order; cleared by {@link #begin} and {@link #end}. */
	private final List<RenderSection> found = new ArrayList<>();
	/** The traversal's visitor, one per section storage; reused across builds. */
	private @Nullable Collect collect;
	/** Whether the current build collects candidates: set by {@link #begin}, read and cleared by {@link #end}. */
	private boolean collecting;
	/** Set by a throw from the submission: the first builds are off for the rest of the set. */
	private boolean failed;

	/**
	 * Whether the section still waits for its first build: its pending update includes {@code INITIAL_BUILD}, it is not
	 * disposed, and no build has finished for it. A section Sodium already submitted has no pending update, so it is never a
	 * candidate twice. A rebuild of a built section is the camera's business and is not a candidate.
	 */
	public static boolean wantsInitialBuild(RenderSection section) {
		return ChunkUpdateTypes.isInitialBuild(section.getPendingUpdate()) && !section.isDisposed() && !section.isBuilt();
	}

	/**
	 * Starts one build's traversal. Asks the port once, before the traversal, whether the camera is idle. When it is not, or
	 * the set's first builds are off, the traversal gets {@code lists} unchanged: no candidate is looked up and nothing is
	 * submitted by {@link #end}.
	 *
	 * @param lists    the light's collector ({@code VisibleChunkCollector}); it receives every visit
	 * @param sections {@code RenderSectionManager.renderSections}
	 * @param port     Sodium's state for this build
	 * @return the visitor the traversal must use
	 */
	public CoordinateSectionVisitor begin(CoordinateSectionVisitor lists, SectionStorage sections, Port port) {
		this.found.clear();
		this.collecting = !this.failed && port.cameraIdle();
		if (!this.collecting) {
			return lists;
		}
		if (this.collect == null || this.collect.sections() != sections) {
			this.collect = new Collect(sections, this.found);
		}
		return this.collect.begin(lists);
	}

	/**
	 * Submits the candidates of the build that {@link #begin} started: through Sodium's own path, in order, while the budget
	 * has room. A candidate the budget does not reach stays unbuilt; the next build's traversal finds it again. A throw is
	 * logged once and turns the first builds off for the rest of the set; the light's list is not affected.
	 *
	 * @param port Sodium's state and submission for this build
	 */
	public void end(Port port) {
		if (!this.collecting) {
			return;
		}
		this.collecting = false;
		try {
			if (!this.found.isEmpty()) {
				submitInOrder(this.found, port);
			}
		} catch (Throwable t) {
			this.failed = true;
			LOG.error("[mcms-shaders] the light's first builds failed; they stay off for the rest of the set", t);
		} finally {
			this.found.clear();
		}
	}

	/** Starts a new set: the first builds are on again, and the candidates and the visitor of the old set are dropped. */
	public void reset() {
		this.failed = false;
		this.collecting = false;
		this.collect = null;
		this.found.clear();
	}

	private static void submitInOrder(List<RenderSection> found, Port port) {
		ChunkJobCollector collector = port.newCollector();
		UploadResourceBudget budget = port.newBudget();
		for (int i = 0; i < found.size() && collector.hasBudgetRemaining() && budget.isAvailable(); i++) {
			port.submit(collector, found.get(i), budget);
		}
	}

	/**
	 * The light's traversal visitor: it forwards every visit to the light's own collector (which builds the light's list), then
	 * records the visited section when it waits for its first build. The lookup is {@link SectionStorage#getConsistent} on the
	 * section's key, the same lookup the camera's deferred path makes, so a section created this frame is seen. One instance
	 * serves every build on its storage; {@link #begin} sets the collector of the build.
	 */
	public static final class Collect implements CoordinateSectionVisitor {
		private final SectionStorage sections;
		private final List<RenderSection> found;
		private @Nullable CoordinateSectionVisitor lists;

		/**
		 * @param sections {@code RenderSectionManager.renderSections}
		 * @param found    the candidates, in traversal order (the traversal is nearest region first)
		 */
		public Collect(SectionStorage sections, List<RenderSection> found) {
			this.sections = sections;
			this.found = found;
		}

		/** The storage this visitor looks the sections up in. */
		public SectionStorage sections() {
			return this.sections;
		}

		/** Starts one build: every later visit forwards to {@code lists} first. Returns this visitor. */
		public Collect begin(CoordinateSectionVisitor lists) {
			this.lists = Objects.requireNonNull(lists, "lists");
			return this;
		}

		@Override
		public void visit(int x, int y, int z) {
			CoordinateSectionVisitor target = this.lists;
			if (target == null) {
				throw new IllegalStateException("the light's traversal visits a section before its build begins");
			}
			target.visit(x, y, z);
			RenderSection section = this.sections.getConsistent(SectionPos.asLong(x, y, z));
			if (section != null && wantsInitialBuild(section)) {
				this.found.add(section);
			}
		}
	}
}
