/*
 * New in MC Metal Shaders, 2026-10-18 (ADR 0002, docs/decisions/0002-settings-page.md): the player settings behind Quicksilver's
 * page in Sodium's Video Settings (QuicksilverSodiumPage). Apache License 2.0.
 */
package dev.mcms.metal;

import java.io.IOException;
import java.io.Reader;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Locale;
import java.util.Properties;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * Three settings in {@code <config dir>/quicksilver.properties}: {@code metal} (-Dmcms.metal), {@code colors}
 * (-Dmcms.metal.colorspace) and {@code hud} (Apple's Metal Performance HUD). The Fabric preLaunch entrypoint reads the file before
 * Minecraft's classes load and sets each system property the launch command did not set, so the backend's read-once fields see
 * them; a launch flag wins over the file, and the page shows it without letting it change. Only the page writes the file
 * (save()), so a launch never creates one and the benchmark's instances run as before.
 */
public final class QuicksilverSettings implements PreLaunchEntrypoint {
	/** The page's Colors row; the property value each one sets (null: unset, Quicksilver's default). */
	public enum Colors {
		AUTOMATIC(null), VIVID("p3"), ACCURATE("srgb");

		final @Nullable String property;

		Colors(@Nullable String property) {
			this.property = property;
		}

		String key() {
			return this.name().toLowerCase(Locale.ROOT);
		}
	}

	static final String METAL_PROPERTY = "mcms.metal", COLORSPACE_PROPERTY = "mcms.metal.colorspace";
	private static final String HUD_ENV = "MTL_HUD_ENABLED";
	private static final Logger LOG = LoggerFactory.getLogger("Quicksilver");

	/** The file's values, which the page edits and save() writes; a launch flag only changes what the page shows. */
	private static boolean metal = true;
	private static Colors colors = Colors.AUTOMATIC;
	private static boolean hud;
	/** The -Dmcms.metal and -Dmcms.metal.colorspace values the launch command set, or null. */
	private static @Nullable String metalFlag, colorspaceFlag;
	/** MTL_HUD_ENABLED=1 at launch, from the launcher or from this class: only then can the layer show the HUD (ADR 0002). */
	private static boolean hudAtLaunch;
	/** The launcher's MTL_HUD_ENABLED when it is not 1: setenv never overwrites it, so the HUD cannot show and the page holds the row. */
	private static @Nullable String hudFlag;
	private static @Nullable Path file;

	/** Fabric makes a throwaway instance for this call; the settings live in static fields, which the page reads later. */
	@Override
	public void onPreLaunch() {
		load(FabricLoader.getInstance().getConfigDir().resolve("quicksilver.properties"));
	}

	/** Reads the file, applies it under the launch flags and records the HUD's launch state. Not thread-safe: preLaunch only. */
	static void load(Path path) {
		file = path;
		metalFlag = System.getProperty(METAL_PROPERTY);
		colorspaceFlag = System.getProperty(COLORSPACE_PROPERTY);
		Properties stored = new Properties();
		if (Files.isRegularFile(path)) {
			try (Reader in = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
				stored.load(in);
			} catch (IOException | IllegalArgumentException e) {
				LOG.warn("Quicksilver settings: could not read {} ({}); the defaults apply", path, e.toString());
				stored.clear();
			}
		}
		Boolean storedMetal = parseBoolean(stored, "metal", path);
		Colors storedColors = parseColors(stored, path);
		Boolean storedHud = parseBoolean(stored, "hud", path);
		if (storedMetal != null) metal = storedMetal;
		if (storedColors != null) colors = storedColors;
		String launchHud = System.getenv(HUD_ENV);  // the environment the launcher gave the JVM
		hudAtLaunch = "1".equals(launchHud);
		hudFlag = launchHud != null && !hudAtLaunch ? launchHud : null;
		// Without a hud key the setting starts as the launch state, so a HUD that the launcher enabled stays on
		hud = storedHud != null ? storedHud : hudAtLaunch;

		if (storedMetal != null) apply(METAL_PROPERTY, metalFlag, Boolean.toString(metal), "metal=" + metal, path);
		if (storedColors != null) {
			if (colorspaceFlag != null) {
				LOG.info("Quicksilver settings: -D{}={} from the launch command wins over colors={} in {}", COLORSPACE_PROPERTY, colorspaceFlag, colors.key(), path);
			} else {
				if (colors.property != null) System.setProperty(COLORSPACE_PROPERTY, colors.property);
				LOG.info("Quicksilver settings: colors={} from {}", colors.key(), path);
			}
		}

		if (hud && launchHud == null) {
			hudAtLaunch = setenv(HUD_ENV, "1");
			LOG.info("Quicksilver settings: hud=true from {}: {}", path, hudAtLaunch ? HUD_ENV + "=1 set before Metal starts" : "could not set " + HUD_ENV);
		} else if (hudFlag != null) {
			LOG.info("Quicksilver settings: the launcher set {}={}, so the Metal HUD stays off{}", HUD_ENV, hudFlag, storedHud != null ? " (hud=" + hud + " in " + path + ")" : "");
		} else if (storedHud != null) {
			LOG.info("Quicksilver settings: hud={} from {}{}", hud, path, hudAtLaunch ? " (" + HUD_ENV + "=1 came from the launcher)" : "");
		}
	}

	private static void apply(String property, @Nullable String flag, String value, String entry, Path path) {
		if (flag != null) {
			LOG.info("Quicksilver settings: -D{}={} from the launch command wins over {} in {}", property, flag, entry, path);
			return;
		}
		System.setProperty(property, value);
		LOG.info("Quicksilver settings: {} from {}", entry, path);
	}

	private static @Nullable Boolean parseBoolean(Properties stored, String key, Path path) {
		String value = stored.getProperty(key);
		if (value == null) return null;
		value = value.trim();
		if (value.equalsIgnoreCase("true")) return true;
		if (value.equalsIgnoreCase("false")) return false;
		LOG.warn("Quicksilver settings: {}={} in {} is neither true nor false; the default applies", key, value, path);
		return null;
	}

	private static @Nullable Colors parseColors(Properties stored, Path path) {
		String value = stored.getProperty("colors");
		if (value == null) return null;
		for (Colors c : Colors.values()) {
			if (c.key().equalsIgnoreCase(value.trim())) return c;
		}
		LOG.warn("Quicksilver settings: colors={} in {} is not automatic, vivid or accurate; the default applies", value.trim(), path);
		return null;
	}

	/** libc setenv(name, value, 0) through the platform linker: preLaunch must not touch Native, which loads the backend's library. */
	private static boolean setenv(String name, String value) {
		try {
			Linker linker = Linker.nativeLinker();
			MethodHandle setenv = linker.downcallHandle(linker.defaultLookup().find("setenv").orElseThrow(),
				FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT));
			try (Arena arena = Arena.ofConfined()) {
				return (int) setenv.invokeExact(arena.allocateFrom(name), arena.allocateFrom(value), 0) == 0;
			}
		} catch (Throwable t) {
			LOG.warn("Quicksilver settings: could not set {} ({})", name, t.toString());
			return false;
		}
	}

	/** Writes the file once per Apply on the page (its storage handler): a temporary file in the same directory, then an atomic move. */
	static synchronized void save() {
		Path path = file;
		if (path == null) {
			LOG.warn("Quicksilver settings: not saved, because the preLaunch entrypoint did not run");
			return;
		}
		String text = "# Quicksilver settings: Options > Video Settings > Quicksilver (docs/decisions/0002-settings-page.md).\n"
			+ "# A -Dmcms.metal or -Dmcms.metal.colorspace launch flag wins over this file.\n"
			+ "metal=" + metal + "\ncolors=" + colors.key() + "\nhud=" + hud + "\n";
		try {
			Path dir = path.toAbsolutePath().getParent();
			Files.createDirectories(dir);
			Path tmp = Files.createTempFile(dir, "quicksilver", ".tmp");
			try {
				// createTempFile makes the file readable by its owner only; a config file is readable by everyone, as the game's are
				Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-r--r--"));
				Files.writeString(tmp, text, StandardCharsets.UTF_8);
				Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			} finally {
				Files.deleteIfExists(tmp);
			}
		} catch (IOException e) {
			LOG.warn("Quicksilver settings: could not write {} ({})", path, e.toString());
		}
	}

	static @Nullable String metalFlag() {
		return metalFlag;
	}

	static @Nullable String colorspaceFlag() {
		return colorspaceFlag;
	}

	static boolean hudAtLaunch() {
		return hudAtLaunch;
	}

	/** The launcher's MTL_HUD_ENABLED when it holds the HUD off (not 1), or null. */
	static @Nullable String hudFlag() {
		return hudFlag;
	}

	/** What the Use Metal row shows: the launch flag's value when there is one. */
	static boolean metalShown() {
		return metalFlag != null ? Boolean.parseBoolean(metalFlag) : metal;
	}

	static void setMetal(boolean value) {
		if (metalFlag == null) metal = value;  // takes effect at the next launch
	}

	/** What the Colors row shows: an untagged layer (none) looks Accurate (results/2026-10-17-colors). */
	static Colors colorsShown() {
		if (colorspaceFlag == null) return colors;
		return switch (colorspaceFlag) {
			case "p3" -> Colors.VIVID;
			case "srgb", "none" -> Colors.ACCURATE;
			default -> colors;
		};
	}

	/** The Colors row: sets or clears -Dmcms.metal.colorspace and re-tags the live layer at once (render thread). */
	static void setColors(Colors value) {
		if (colorspaceFlag != null) return;
		colors = value;
		if (value.property != null) {
			System.setProperty(COLORSPACE_PROPERTY, value.property);
		} else {
			System.clearProperty(COLORSPACE_PROPERTY);
		}
		MetalSurface.retagCurrent();
	}

	static boolean hud() {
		return hud;
	}

	/** The Metal HUD row: shows or hides the HUD at once when it was enabled at launch; otherwise from the next launch on. */
	static void setHud(boolean value) {
		hud = value;
		if (hudAtLaunch) MetalSurface.hudCurrent(value);
	}
}
