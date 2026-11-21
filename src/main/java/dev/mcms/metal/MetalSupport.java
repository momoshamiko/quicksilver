/*
 * New in MC Metal Shaders, 2026-10-20 (RIL-497). Apache License 2.0.
 */
package dev.mcms.metal;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;
import net.fabricmc.loader.api.FabricLoader;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Whether Metal can draw the game here. Minecraft tries its next backend only when one throws a BackendCreationException
 * (Minecraft.<init>, javap of the 26.3 client); anything else ends the start. So Quicksilver offers Metal only where it can run:
 * on macOS 15 or newer on Apple silicon (libmcmsmetal.dylib is arm64 and built for macOS 15), and not next to Iris, which draws
 * only through OpenGL and casts the device's backend to OpenGL's (IrisRenderSystem.getGlDevice in Iris 1.11.7). Elsewhere
 * PreferredGraphicsApiMixin leaves the game's backend list as it is, and the game starts as without Quicksilver.
 */
public final class MetalSupport {
	/** Why Metal cannot draw the game here; the settings page names it under Use Metal. */
	public enum Blocker {
		NOT_MACOS("quicksilver.options.blocked.platform"),
		NOT_APPLE_SILICON("quicksilver.options.blocked.intel"),
		MACOS_TOO_OLD("quicksilver.options.blocked.platform"),
		IRIS("quicksilver.options.blocked.iris");

		public final String langKey;

		Blocker(String langKey) {
			this.langKey = langKey;
		}
	}

	private static final Logger LOG = LoggerFactory.getLogger("Quicksilver");

	private MetalSupport() {
	}

	/** Decided once, at the first call, which logs the reason; QuicksilverSettings makes that call at preLaunch. Null: Metal can run. */
	public static @Nullable Blocker blocker() {
		return Decided.BLOCKER;
	}

	/** Parses a {@code -Dmcms.metal} / {@code metal=} word: "off" or "false" disables Metal, everything else enables it. */
	public static boolean parseMetalWord(@Nullable String value) {
		if (value == null) return true;
		String normalized = value.trim().toLowerCase(Locale.ROOT);
		return !normalized.equals("false") && !normalized.equals("off");
	}

	/** Whether {@code -Dmcms.metal} allows Metal to run (true by default; false only for "false" or "off"). */
	public static boolean metalEnabled() {
		return parseMetalWord(System.getProperty("mcms.metal"));
	}

	/**
	 * Whether Metal is enabled at mixin-preparation time, before {@link QuicksilverSettings#onPreLaunch()} has loaded
	 * {@code quicksilver.properties} into system properties: a {@code -Dmcms.metal} launch flag wins; otherwise the
	 * file's {@code metal=} entry decides (missing or unreadable defaults to true). Touches no Minecraft classes.
	 */
	public static boolean metalEnabledForMixins() {
		String flag = System.getProperty("mcms.metal");
		if (flag != null) {
			return parseMetalWord(flag);
		}
		return readMetalFromConfig(FabricLoader.getInstance().getConfigDir().resolve("quicksilver.properties"));
	}

	/** Reads {@code metal=} from {@code quicksilver.properties}; returns false only for {@code false} (matching QuicksilverSettings). */
	static boolean readMetalFromConfig(Path path) {
		if (!Files.isRegularFile(path)) return true;
		Properties stored = new Properties();
		try (Reader in = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
			stored.load(in);
		} catch (IOException | IllegalArgumentException e) {
			return true;
		}
		String raw = stored.getProperty("metal");
		return raw == null || !raw.trim().equalsIgnoreCase("false");
	}

	/** Plain-English reason Metal is unavailable on this machine/modpack, or null if supported. */
	public static @Nullable String blockerMessage() {
		Blocker b = blocker();
		if (b == null) return null;
		return switch (b) {
			case NOT_MACOS -> "Quicksilver requires macOS on Apple Silicon";
			case NOT_APPLE_SILICON -> "Quicksilver requires Apple Silicon (ARM64 Java)";
			case MACOS_TOO_OLD -> "Quicksilver requires macOS 15 or newer";
			case IRIS -> "Remove Iris to use Quicksilver's Metal renderer and shaders";
		};
	}

	/** The decision itself, from the JVM's os.name, os.arch and os.version and whether Iris is loaded. */
	static @Nullable Blocker check(String osName, String osArch, String osVersion, boolean irisLoaded) {
		if (!osName.toLowerCase(Locale.ROOT).startsWith("mac")) return Blocker.NOT_MACOS;
		if (!osArch.equals("aarch64") && !osArch.equals("arm64")) return Blocker.NOT_APPLE_SILICON;
		if (majorVersion(osVersion) < 15) return Blocker.MACOS_TOO_OLD;
		if (irisLoaded) return Blocker.IRIS;
		return null;
	}

	/** The number before the first dot; a version it cannot read counts as new enough, and the device's own start decides. */
	private static int majorVersion(String version) {
		int end = 0;
		while (end < version.length() && Character.isDigit(version.charAt(end))) end++;
		try {
			return Integer.parseInt(version.substring(0, end));
		} catch (NumberFormatException e) {
			return Integer.MAX_VALUE;
		}
	}

	/** The class holder idiom: decided and logged once, on first use, from any thread. */
	private static final class Decided {
		static final @Nullable Blocker BLOCKER = decide();

		private static @Nullable Blocker decide() {
			String name = System.getProperty("os.name", ""), arch = System.getProperty("os.arch", ""), version = System.getProperty("os.version", "");
			Blocker blocker = check(name, arch, version, FabricLoader.getInstance().isModLoaded("iris"));
			if (blocker != null) {
				String why = switch (blocker) {
					case NOT_MACOS -> "Metal runs only on macOS, and this is " + name;
					case NOT_APPLE_SILICON -> "its Metal library is for Apple silicon, and this Java runs on " + arch;
					case MACOS_TOO_OLD -> "it needs macOS 15 or newer, and this is macOS " + version;
					case IRIS -> "Iris is installed, and Iris draws only through OpenGL (for shader packs on Metal, remove Iris: Quicksilver draws them itself)";
				};
				LOG.info("Quicksilver: Metal stays off, so the game starts with its own graphics backends: {}", why);
			}
			return blocker;
		}
	}
}
