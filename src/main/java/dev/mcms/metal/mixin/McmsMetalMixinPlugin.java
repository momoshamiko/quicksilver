package dev.mcms.metal.mixin;

import java.util.List;
import java.util.Set;

import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.jspecify.annotations.Nullable;

/**
 * Applies the Sodium and Distant Horizons mixins (simple names starting with "Sodium" or "DistantHorizons") only when
 * Fabric says the target mod is loaded, the Distant Horizons ones only for DH 3.3.4, the version they are written for; the
 * shader engine's own Sodium mixins ({@code SodiumEngine*}) only on a Sodium release of ADR 0007's allow-list. The engine's
 * other mixins ({@code Engine*}) and PreferredGraphicsApiMixin (the backend's entry) always apply; -Dmcms.metal=false is
 * the backend's opt-out. Vitrail cannot load beside Quicksilver: fabric.mod.json breaks it (E10).
 *
 * <p>Mixin calls this plugin while it prepares the configs, before the game's classes load. So the plugin touches no game,
 * Sodium or renderpearl class: it asks Fabric and the ADR 0007 gate only. The engine's terrain registration runs later,
 * from {@code SodiumEngineStartMixin} (E4-B review item 1).
 */
public final class McmsMetalMixinPlugin implements IMixinConfigPlugin {
	/** Whether the "release not on the allow-list" ERROR was printed (once per game, not once per mixin). */
	private static boolean sodiumReleaseErrorPrinted;

	@Override
	public void onLoad(String mixinPackage) {
	}

	@Override
	public String getRefMapperConfig() {
		return null;
	}

	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		boolean apply;
		String simpleName = mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1);
		if (simpleName.startsWith("Engine") || simpleName.startsWith("SodiumEngine")) {
			// The shader engine's own mixins (plan.md E4; ADR 0007). The Sodium ones: only on the allow-list's checked
			// releases. On any other release the engine stays off with one ERROR naming the release, and the game runs as
			// without a pack.
			String sodium = dev.mcms.shaders.runtime.terrain.SodiumAdapter.release();
			apply = engineMixinApplies(simpleName, sodium);
			if (simpleName.startsWith("SodiumEngine") && sodium != null
				&& !dev.mcms.shaders.runtime.terrain.SodiumAdapter.allowedRelease(sodium) && !sodiumReleaseErrorPrinted) {
				sodiumReleaseErrorPrinted = true;
				System.out.println("[mcms-metal] [ERROR] Sodium " + sodium + " is not on the engine's allow-list "
					+ "(ADR 0007), the engine's Sodium mixins stay off; the game runs without the shader engine");
			}
		} else if (simpleName.startsWith("Sodium")) {
			apply = FabricLoader.getInstance().isModLoaded("sodium");
		} else if (simpleName.startsWith("DistantHorizons")) {
			// DH is no project pin. With defaultRequire 1, a version whose hooked call moved would stop the game at start, so
			// these mixins apply to the version they are written for only (bench/mods.lock.json).
			String version = FabricLoader.getInstance().getModContainer("distanthorizons")
				.map(mod -> mod.getMetadata().getVersion().getFriendlyString()).orElse(null);
			apply = "3.3.4".equals(version);
			if (version != null && !apply) {
				System.out.println("[mcms-metal] Distant Horizons " + version + " is not 3.3.4, the version " + simpleName + " is written for");
			}
		} else {
			apply = true; // PreferredGraphicsApiMixin: checks -Dmcms.metal=false itself
		}
		System.out.println("[mcms-metal] " + simpleName + (apply ? " applied" : " off"));
		return apply;
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
