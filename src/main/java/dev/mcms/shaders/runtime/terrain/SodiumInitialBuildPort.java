// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.terrain;

import dev.mcms.metal.mixin.SodiumEngineRenderSectionManagerAccessor;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.estimation.LimitedResourceBudget;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.estimation.UploadResourceBudget;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.executor.ChunkJobCollector;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.DeferredTaskList;

/**
 * Sodium's live state for the light's first builds ({@link LightInitialBuilds.Port}; E6-B2). It reads the camera's deferred
 * tasks and the average frame time through {@link SodiumEngineRenderSectionManagerAccessor}, and sizes its collector and
 * budget from Sodium's own deferred path ({@code RenderSectionManager.updateChunks}, javap 2026-11-14 and 2026-11-15; the
 * bytecode offsets below are the same on beta.1 and beta.5):
 * <ul>
 * <li>collector: {@code new ChunkJobCollector(builder.getTotalRemainingDuration(averageFrameDuration), buildResults::add)}
 * (the duration at 85-96, the collector at 162-182);</li>
 * <li>budget: half of the camera's {@code LimitedResourceBudget(max(averageFrameDuration * 0.3f, 10 ms),
 * staging.getUploadSizeLimit(averageFrameDuration))} (bytecode 98-134 of {@code updateChunks}; see
 * {@link #firstBuildBudget} and the class comment of {@link LightInitialBuilds});</li>
 * <li>submission: the private {@code submitSectionTask(collector, section, budget)} through the invoker (bytecode 0-22).</li>
 * </ul>
 * One instance serves one render-section manager: {@code SodiumEngineShadowTerrainMixin} keeps it while the manager is the
 * same object, and makes a new one after a world reload. Each build makes its collector and budget (Sodium's deferred path
 * makes the same two). It lives outside {@code dev.mcms.metal.mixin}: Mixin refuses to load any class of a mixin package
 * that is not a mixin (the 2026-11-15 preview: {@code IllegalClassLoadError}, and the light's list was skipped for the whole
 * run).
 */
public final class SodiumInitialBuildPort implements LightInitialBuilds.Port {
	/** The camera's floor for its deferred upload duration, nanoseconds (Sodium's {@code 10000000l}). */
	private static final long MIN_UPLOAD_DURATION = 10_000_000L;

	private final RenderSectionManager manager;
	private final SodiumEngineRenderSectionManagerAccessor sections;

	/**
	 * @param manager  the render-section manager this port serves
	 * @param sections the same manager, through its accessor
	 */
	public SodiumInitialBuildPort(RenderSectionManager manager, SodiumEngineRenderSectionManagerAccessor sections) {
		this.manager = manager;
		this.sections = sections;
	}

	/** Whether this port serves {@code other}: the mixin reuses a port while the render-section manager is the same object. */
	public boolean isFor(RenderSectionManager other) {
		return this.manager == other;
	}

	@Override
	public boolean cameraIdle() {
		DeferredTaskList deferred = this.sections.mcmsMetal$taskLists();
		return deferred == null || deferred.isEmpty();
	}

	@Override
	public ChunkJobCollector newCollector() {
		long averageFrame = this.sections.mcmsMetal$averageFrameDuration();
		return new ChunkJobCollector(this.manager.getBuilder().getTotalRemainingDuration(averageFrame),
			this.sections.mcmsMetal$buildResults()::add);
	}

	@Override
	public UploadResourceBudget newBudget() {
		long averageFrame = this.sections.mcmsMetal$averageFrameDuration();
		return firstBuildBudget(averageFrame, this.manager.regions.getStagingBuffer().getUploadSizeLimit(averageFrame));
	}

	/**
	 * The light's upload budget for one build: half of the camera's budget in duration and in size (E6 review item 7). The
	 * camera's duration is {@code max(averageFrameDuration * 0.3f, 10 ms)}, as Sodium's {@code updateChunks} computes it. With the
	 * camera's own budget, a frame spends at most one and a half times the camera's budget on uploads.
	 *
	 * @param averageFrameDuration the average frame duration, nanoseconds
	 * @param uploadSizeLimit      the staging buffer's upload size limit for that frame duration, bytes
	 */
	static LimitedResourceBudget firstBuildBudget(long averageFrameDuration, long uploadSizeLimit) {
		long cameraDuration = Math.max((long) ((float) averageFrameDuration * 0.3f), MIN_UPLOAD_DURATION);
		return new LimitedResourceBudget(cameraDuration / 2L, uploadSizeLimit / 2L);
	}

	@Override
	public void submit(ChunkJobCollector collector, RenderSection section, UploadResourceBudget budget) {
		this.sections.mcmsMetal$submitSectionTask(collector, section, budget);
	}
}
