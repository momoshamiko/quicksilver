// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.metal.mixin;

import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The render-section manager of the world renderer (E5-B2: the light's list is built from its sections, tree, regions and
 * frame). Field {@code SodiumWorldRenderer.renderSectionManager}, private, javap 2026-11-14 on both allowed jars:
 * {@code Lnet/caffeinemc/mods/sodium/client/render/chunk/RenderSectionManager;}, identical on beta.1 and beta.5 (the
 * member is in the class's unchanged field table). Applied only on the ADR 0007 allow-list, as every {@code SodiumEngine*}
 * mixin.
 */
@Mixin(SodiumWorldRenderer.class)
public interface SodiumEngineWorldRendererAccessor {
	@Accessor("renderSectionManager")
	RenderSectionManager mcmsMetal$renderSectionManager();

	/** Sodium's fog of the last camera frame ({@code lastFogParameters}; null before the first). Javap 2026-11-14, beta.1 and beta.5. */
	@Accessor("lastFogParameters")
	net.caffeinemc.mods.sodium.client.util.FogParameters mcmsMetal$lastFogParameters();

	/** Sodium's uniform buffers (globals and section time), updated per draw group ({@code uniformBufferManager}). */
	@Accessor("uniformBufferManager")
	net.caffeinemc.mods.sodium.client.render.chunk.UniformBufferManager mcmsMetal$uniformBufferManager();

	/**
	 * Whether the camera's chunk renderer draws translucent terrain sorted ({@code useTranslucencySorting}, set in
	 * {@code initRenderer}: always true outside a development environment). The mesher stores translucent sections with
	 * their own index data while it is on, so the shadow pass must draw them with the same flag. Javap 2026-11-14, private
	 * boolean field on beta.1 and beta.5.
	 */
	@Accessor("useTranslucencySorting")
	boolean mcmsMetal$useTranslucencySorting();
}
