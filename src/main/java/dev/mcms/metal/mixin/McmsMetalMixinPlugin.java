// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.metal.mixin;

import dev.mcms.metal.MetalSupport;
import java.util.List;
import java.util.Set;

import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.jspecify.annotations.Nullable;

/**
 * Applies Quicksilver's mixins only when Metal can run here ({@link MetalSupport#blocker()} is null and
 * {@link MetalSupport#metalEnabled()} is true), so Quicksilver stays completely inert beside Iris, on unsupported platforms,
 * or when Metal is turned off. Sodium and Distant Horizons mixins are further gated on the target mod being loaded and on
 * the checked releases where internal fields/methods are hooked.
 *
 * <p>Mixin calls this plugin while it prepares the configs, before the game's classes load. So the plugin touches no game,
 * Sodium or renderpearl class: it asks Fabric, {@link MetalSupport}, and the ADR 0007 gate only. The engine's terrain
 * registration runs later, from {@code SodiumEngineStartMixin} (E4-B review item 1).
 */
public final class McmsMetalMixinPlugin implements IMixinConfigPlugin {
	/** Whether the "release not on the allow-list" ERROR was printed (once per game, not once per mixin). */
	private static boolean sodiumReleaseErrorPrinted;
	/** Whether the "mixins skipped because Metal is off/blocked" line was printed once. */
	private static boolean metalOffPrinted;
	/** Cached once per mixin-config prepare so quicksilver.properties is read at most once. */
	private @Nullable Boolean metalAvailable;

	@Override
	public void onLoad(String mixinPackage) {
	}

	@Override
	public String getRefMapperConfig() {
		return null;
	}

	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		String simpleName = mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1);
		if (this.metalAvailable == null) {
			this.metalAvailable = MetalSupport.blocker() == null && MetalSupport.metalEnabledForMixins();
		}
		if (!this.metalAvailable) {
			if (!metalOffPrinted) {
				metalOffPrinted = true;
				System.out.println("[mcms-metal] Metal is off or unavailable; skipping all Quicksilver mixins");
			}
			return false;
		}
		String sodium = dev.mcms.shaders.runtime.terrain.SodiumAdapter.release();
		boolean sodiumLoaded = FabricLoader.getInstance().isModLoaded("sodium");
		String dhVersion = FabricLoader.getInstance().getModContainer("distanthorizons")
			.map(mod -> mod.getMetadata().getVersion().getFriendlyString()).orElse(null);
		boolean apply = mixinApplies(simpleName, sodium, dhVersion, sodiumLoaded, dhVersion != null, true);
		if (simpleName.startsWith("SodiumEngine") && sodium != null
				&& !dev.mcms.shaders.runtime.terrain.SodiumAdapter.allowedRelease(sodium) && !sodiumReleaseErrorPrinted) {
			sodiumReleaseErrorPrinted = true;
			System.out.println("[mcms-metal] [ERROR] Sodium " + sodium + " is not on the engine's allow-list "
				+ "(ADR 0007), the engine's Sodium mixins stay off; the game runs without the shader engine");
		}
		if (simpleName.startsWith("DistantHorizons") && !"DistantHorizonsRenderApiMixin".equals(simpleName)
				&& dhVersion != null && !apply) {
			System.out.println("[mcms-metal] Distant Horizons " + dhVersion + " is not 3.3.4, the version " + simpleName + " is written for");
		}
		System.out.println("[mcms-metal] " + simpleName + (apply ? " applied" : " off"));
		return apply;
	}

	/**
	 * Pure mixin-application decision for tests and {@link #shouldApplyMixin}.
	 */
	static boolean mixinApplies(String simpleName, @Nullable String sodiumRelease, @Nullable String dhVersion,
			boolean sodiumLoaded, boolean dhLoaded, boolean metalAvailable) {
		if (!metalAvailable) {
			return false;
		}
		if (simpleName.startsWith("Engine") || simpleName.startsWith("SodiumEngine")) {
			return engineMixinApplies(simpleName, sodiumRelease);
		}
		if (simpleName.equals("SodiumBufferArenaAccessor")
				|| simpleName.equals("SodiumSharedArenaMixin")
				|| simpleName.equals("SodiumSingleOwnerArenaMixin")) {
			// Internal BufferArena.textureId leak workaround: only apply on beta.1/beta.5 (fixed upstream in Sodium 0.9.3).
			return sodiumLoaded && dev.mcms.shaders.runtime.terrain.SodiumAdapter.needsArenaLeakWorkaround(sodiumRelease);
		}
		if (simpleName.startsWith("Sodium")) {
			return sodiumLoaded;
		}
		if (simpleName.equals("DistantHorizonsRenderApiMixin")) {
			// Metal-as-Vulkan API detection applies across DH versions (guarded by require = 0).
			return dhLoaded;
		}
		if (simpleName.startsWith("DistantHorizons")) {
			if (!dhLoaded || dhVersion == null) return false;
			int plus = dhVersion.indexOf('+');
			String base = plus >= 0 ? dhVersion.substring(0, plus) : dhVersion;
			return "3.3.4".equals(base);
		}
		return true; // PreferredGraphicsApiMixin
	}

	/**
	 * Whether one of the shader engine's own mixins applies (plan.md E4; ADR 0007 decision 2): every {@code Engine*} hook
	 * (the level, hand, submit and frame hooks), and the {@code SodiumEngine*} ones only on Sodium's allow-list
	 * ({@code sodiumRelease}: the release word with its build part stripped, null without Sodium). Pure: the caller reads
	 * Fabric. Any other name is not the engine's and gets false.
	 *
	 * @param simpleName    the mixin class's simple name
	 * @param sodiumRelease Sodium's release word, or null without Sodium
	 */
	static boolean engineMixinApplies(String simpleName, @Nullable String sodiumRelease) {
		if (!simpleName.startsWith("Engine") && !simpleName.startsWith("SodiumEngine")) {
			return false;
		}
		return !simpleName.startsWith("SodiumEngine")
			|| dev.mcms.shaders.runtime.terrain.SodiumAdapter.allowedRelease(sodiumRelease);
	}

	@Override
	public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
	}

	@Override
	public List<String> getMixins() {
		return null;
	}

	@Override
	public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
	}

	@Override
	public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
	}
}
