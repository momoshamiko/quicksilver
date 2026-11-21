// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.distant;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Whether Distant Horizons 3.3.4 runs and draws its terrain in this session (plan.md E7, "Decisions" item 8): the mod is
 * loaded at the version the engine is written for, and its "render LODs" option is on. The pack's macros
 * ({@code DISTANT_HORIZONS}, {@code DH_BLOCK_*}) follow this, so a pack sees the define exactly when DH draws.
 *
 * <p>DH's classes are touched only after the mod is confirmed loaded: the config entry lives in a nested class that
 * is loaded on first use, so the engine runs without DH.
 */
public final class DistantHorizonsRuntime {

	/** The mod id and the one version the engine's DH code is written for (bench/mods.lock.json). */
	static final String MOD_ID = "distanthorizons";
	static final String VERSION = "3.3.4";

	private DistantHorizonsRuntime() {
	}

	/** Whether DH 3.3.4 is loaded and its rendering is enabled. False without DH. */
	public static boolean active() {
		String version = FabricLoader.getInstance().getModContainer(MOD_ID)
			.map(mod -> mod.getMetadata().getVersion().getFriendlyString()).orElse(null);
		return VERSION.equals(version) && Settings.renderingEnabled();
	}

	/** DH's config, read only once DH is known to be loaded. */
	private static final class Settings {
		private Settings() {
		}

		static boolean renderingEnabled() {
			return Boolean.TRUE.equals(com.seibel.distanthorizons.core.config.Config.Client.quickEnableRendering.get());
		}
	}
}
