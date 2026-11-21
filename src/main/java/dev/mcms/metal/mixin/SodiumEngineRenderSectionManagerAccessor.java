// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.metal.mixin;

import java.util.concurrent.ConcurrentLinkedDeque;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.BuilderTaskOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.estimation.UploadResourceBudget;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.executor.ChunkJobCollector;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.executor.ChunkJobResult;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.DeferredTaskList;
import net.caffeinemc.mods.sodium.client.render.chunk.storage.SectionStorage;
import net.caffeinemc.mods.sodium.client.render.chunk.tree.RemovableMultiForest;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * The private state the light's terrain list and its first builds are made from (E5-B2; {@code ShadowTerrain.build};
 * E6-B2; {@link dev.mcms.shaders.runtime.terrain.LightInitialBuilds}). javap 2026-11-14, identical on beta.1 and beta.5 (the
 * fields and the invoked method are the class's own, not hooked members):
 * <ul>
 * <li>{@code renderableSectionTree}: {@code Lnet/caffeinemc/mods/sodium/client/render/chunk/tree/RemovableMultiForest;}</li>
 * <li>{@code frame}: {@code I}</li>
 * <li>{@code renderDistance}: {@code I} (chunks; Sodium's {@code getRenderDistance} is this times 16, in blocks)</li>
 * <li>{@code renderSections}: {@code Lnet/caffeinemc/mods/sodium/client/render/chunk/storage/SectionStorage;}</li>
 * <li>{@code taskLists}: {@code Lnet/caffeinemc/mods/sodium/client/render/chunk/lists/DeferredTaskList;} (the camera's deferred
 * builds; null before the first cull)</li>
 * <li>{@code averageFrameDuration}: {@code J} (nanoseconds)</li>
 * <li>{@code buildResults}: {@code Ljava/util/concurrent/ConcurrentLinkedDeque;} (the results the builder's jobs post)</li>
 * <li>{@code submitSectionTask(ChunkJobCollector, RenderSection, UploadResourceBudget)V}: the private 3-argument method, bytecode
 * 0-22 on both jars (it returns at once when the section has no pending update)</li>
 * </ul>
 * {@code RenderSectionManager.regions} and {@code getBuilder()} are public and read directly. Applied only on the ADR 0007
 * allow-list.
 */
@Mixin(RenderSectionManager.class)
public interface SodiumEngineRenderSectionManagerAccessor {
	@Accessor("renderableSectionTree")
	RemovableMultiForest mcmsMetal$renderableSectionTree();

	@Accessor("frame")
	int mcmsMetal$frame();

	@Accessor("renderDistance")
	int mcmsMetal$renderDistance();

	@Accessor("renderSections")
	SectionStorage mcmsMetal$renderSections();

	@Accessor("taskLists")
	DeferredTaskList mcmsMetal$taskLists();

	@Accessor("averageFrameDuration")
	long mcmsMetal$averageFrameDuration();

	@Accessor("buildResults")
	ConcurrentLinkedDeque<ChunkJobResult<? extends BuilderTaskOutput>> mcmsMetal$buildResults();

	@Invoker("submitSectionTask")
	void mcmsMetal$submitSectionTask(ChunkJobCollector collector, RenderSection section, UploadResourceBudget budget);
}
