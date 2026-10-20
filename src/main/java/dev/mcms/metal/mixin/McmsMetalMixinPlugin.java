package dev.mcms.metal.mixin;

import java.util.List;
import java.util.Set;

import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * Applies the Sodium, Vitrail and Distant Horizons mixins (simple names starting with "Sodium", "Vitrail" or
 * "DistantHorizons") only when Fabric says the target mod is loaded; the Distant Horizons ones only for DH 3.3.4, the version
 * they are written for. PreferredGraphicsApiMixin (the backend's entry) always applies; -Dmcms.metal=false is its opt-out.
 */
public final class McmsMetalMixinPlugin implements IMixinConfigPlugin {
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
		if (simpleName.startsWith("Sodium")) {
			apply = FabricLoader.getInstance().isModLoaded("sodium");
		} else if (simpleName.startsWith("Vitrail")) {
			apply = FabricLoader.getInstance().isModLoaded("vitrail");
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
