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
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import dev.mcms.shaders.pack.StandardMacros;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * Seven settings in {@code <config dir>/quicksilver.properties}: {@code metal} (-Dmcms.metal), {@code colors}
 * (-Dmcms.metal.colorspace), {@code hud} (Apple's Metal Performance HUD), and the shader engine's {@code shaders}
 * (-Dmcms.shaders), {@code shaderPack} (-Dmcms.shaders.pack), {@code handDepth} and {@code upscale} (-Dmcms.shaders.upscale).
 * The Fabric preLaunch entrypoint reads the file before
 * Minecraft's classes load and sets each backend property the launch command did not set, so the backend's read-once fields
 * see them; a launch flag wins over the file, and the screens show it without letting it change. The page in Sodium's Video
 * Settings, the shader pack screen, the pack options screen (hand depth) and the shaders key write the file (save()); a launch
 * never creates one, so the benchmark's instances run as before (ADR 0002 section 4, amended for E8). After preLaunch the
 * fields are read and written on the render thread only (the page, the screens, the keys and the engine's frame hooks).
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

	/**
	 * The shader engine's render scale with MetalFX's spatial scaler (ADR 0008): the world draws at the window size divided by
	 * the scale, and the pack's final image is upscaled to the window. Off is the default and draws as before.
	 */
	public enum Upscale {
		OFF(1f), QUALITY(1.5f), PERFORMANCE(2f);

		/** The window size divided by this is the render size. */
		public final float scale;

		Upscale(float scale) {
			this.scale = scale;
		}

		public String key() {
			return this.name().toLowerCase(Locale.ROOT);
		}

		private static final Upscale[] ALL = values();

		/**
		 * The value a word names ({@code off}, {@code quality}, {@code performance}, any case), or null. Read every frame while
		 * the property is set, so it allocates nothing (the names compare as they are; trim returns the word itself).
		 */
		public static @Nullable Upscale of(String word) {
			String w = word.trim();
			for (Upscale u : ALL) {
				if (u.name().equalsIgnoreCase(w)) {
					return u;
				}
			}
			return null;
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

	/** 2026-11-13 (E3, ADR 0006 section 5): the shader engine's keys, read by dev.mcms.shaders.runtime.Engine. */
	private static boolean shaders;
	private static @Nullable String shaderPack;
	private static final String SHADERS_PROPERTY = "mcms.shaders", PACK_PROPERTY = "mcms.shaders.pack";
	// The -Dmcms.shaders and -Dmcms.shaders.pack launch flags are read live (shadersOn, shaderPackName, the locks): since E8 the
	// file's values stay in the fields and set no property, so a property present means a launch flag (or a test), which wins
	// over the file and holds the screens; every -D path keeps working without preLaunch (ADR 0002, "benchmark configs").
	/**
	 * The shader engine's hand depth, {@code MC_HAND_DEPTH} (E8): OptiFine's documented values 0.0625, 0.125 and 0.25
	 * (engine-pack-format.md section 3). A global setting, as OptiFine keeps its "Hand Depth" with its global shader
	 * settings: a key in a pack's own options file would be an unknown option to another loader reading that file.
	 */
	public static final List<Float> HAND_DEPTHS = List.of(0.0625f, 0.125f, 0.25f);
	private static float handDepth = StandardMacros.DEFAULT_HAND_DEPTH;
	/** The file's {@code upscale}; {@code -Dmcms.shaders.upscale}, read at each call, wins while it names a value (ADR 0008). */
	private static Upscale upscale = Upscale.OFF;
	static final String UPSCALE_PROPERTY = "mcms.shaders.upscale";
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

		// 2026-11-13 (E3, ADR 0006 section 5): the shader engine's keys; the launch flags win over the file (ADR 0002's rule).
		// Since E8 the file's values stay in the fields (shadersOn and shaderPackName read them under the live flags), so a
		// screen's change is live; no property is set from the file.
		Boolean storedShaders = parseOnOff(stored, "shaders", path);
		String storedPack = stored.getProperty("shaderPack");
		if (storedPack != null && storedPack.isBlank()) {
			storedPack = null;
		}
		if (storedShaders != null) shaders = storedShaders;
		if (storedPack != null) shaderPack = storedPack.trim();
		if (storedShaders != null) {
			logFileValue(SHADERS_PROPERTY, System.getProperty(SHADERS_PROPERTY), "shaders=" + (shaders ? "on" : "off"), path);
		}
		if (storedPack != null) {
			logFileValue(PACK_PROPERTY, System.getProperty(PACK_PROPERTY), "shaderPack=" + shaderPack, path);
		}
		String storedHandDepth = stored.getProperty("handDepth");
		if (storedHandDepth != null) {
			Float value = parseHandDepth(storedHandDepth);
			if (value != null) {
				handDepth = value;
				LOG.info("Quicksilver settings: handDepth={} from {}", handDepth, path);
			} else {
				LOG.warn("Quicksilver settings: handDepth={} in {} is not 0.0625, 0.125 or 0.25; the default applies",
					storedHandDepth.trim(), path);
			}
		}
		String storedUpscale = stored.getProperty("upscale");
		if (storedUpscale != null) {
			Upscale value = Upscale.of(storedUpscale);
			if (value != null) {
				upscale = value;
				logFileValue(UPSCALE_PROPERTY, System.getProperty(UPSCALE_PROPERTY), "upscale=" + value.key(), path);
			} else {
				LOG.warn("Quicksilver settings: upscale={} in {} is not off, quality or performance; the default applies",
					storedUpscale.trim(), path);
			}
		}
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

		// RIL-497: whether Metal can run here is decided (and a blocker logged) now, at preLaunch, whatever Use Metal says.
		// MTL_HUD_ENABLED=1 asks for Apple's HUD on any Metal layer, and MoltenVK presents through one too (the HUD probably shows
		// on Vulkan then [inference, not seen]). So it is set only when the game will draw with Quicksilver: Use Metal on (a launch
		// flag's value, else the file's) and Metal possible here.
		MetalSupport.Blocker blocker = MetalSupport.blocker();
		boolean metalRuns = Boolean.parseBoolean(System.getProperty(METAL_PROPERTY, "true")) && blocker == null;
		if (hud && launchHud == null && metalRuns) {
			hudAtLaunch = setenv(HUD_ENV, "1");
			LOG.info("Quicksilver settings: hud=true from {}: {}", path, hudAtLaunch ? HUD_ENV + "=1 set before Metal starts" : "could not set " + HUD_ENV);
		} else if (hud && launchHud == null) {
			LOG.info("Quicksilver settings: hud=true in {}, but the game will not draw with Metal ({}), so {} stays unset", path,
				blocker != null ? "Metal cannot run here" : "Use Metal is off", HUD_ENV);
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

	/** The log line of a file value that stays in its field: which source wins, as apply() logs it for the backend's keys. */
	private static void logFileValue(String property, @Nullable String flag, String entry, Path path) {
		if (flag != null) {
			LOG.info("Quicksilver settings: -D{}={} from the launch command wins over {} in {}", property, flag, entry, path);
		} else {
			LOG.info("Quicksilver settings: {} from {}", entry, path);
		}
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

	/**
	 * Writes the file once per Apply (the page's storage handler, the pack screen, the options screen, the shaders key): a
	 * temporary file in the same directory, then an atomic move. Every key is written, so one writer never drops another's
	 * (E8). Render thread, as every writer.
	 */
	public static synchronized void save() {
		Path path = file;
		if (path == null) {
			LOG.warn("Quicksilver settings: not saved, because the preLaunch entrypoint did not run");
			return;
		}
		String text = "# Quicksilver settings: Options > Video Settings > Quicksilver, and the shader pack screen\n"
			+ "# (docs/decisions/0002-settings-page.md). A -Dmcms.metal, -Dmcms.metal.colorspace, -Dmcms.shaders,\n"
			+ "# -Dmcms.shaders.pack or -Dmcms.shaders.upscale launch flag wins over this file.\n"
			+ "metal=" + metal + "\ncolors=" + colors.key() + "\nhud=" + hud + "\nshaders=" + (shaders ? "on" : "off") + "\n"
			+ (shaderPack != null ? "shaderPack=" + escapeValue(shaderPack) + "\n" : "") + "handDepth=" + handDepth + "\n"
			+ "upscale=" + upscale.key() + "\n";
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

	/** What the Metal HUD row shows: off while the launcher's MTL_HUD_ENABLED (not 1) holds the HUD off, as a flag's value. */
	static boolean hudShown() {
		return hudFlag == null && hud;
	}

	/** The Metal HUD row: shows or hides the HUD at once when it was enabled at launch; otherwise from the next launch on. */
	static void setHud(boolean value) {
		hud = value;
		if (hudAtLaunch) MetalSurface.hudCurrent(value);
	}

	// ---- 2026-11-13 (E3, ADR 0006 section 5): the shader engine's two keys (the launch flags win over the file) ----

	/** Whether the shader engine runs ({@code shaders}, or {@code -Dmcms.shaders}, which wins); off by default. */
	public static boolean shadersOn() {
		String flag = System.getProperty(SHADERS_PROPERTY);
		return flag != null ? Boolean.TRUE.equals(parseOnOffWord(flag)) : shaders;
	}

	/** The selected pack's file or folder name under {@code shaderpacks/} ({@code shaderPack}, or {@code -Dmcms.shaders.pack}). */
	public static @Nullable String shaderPackName() {
		String flag = System.getProperty(PACK_PROPERTY);
		return flag != null && !flag.isBlank() ? flag.trim() : shaderPack;
	}

	/** Turns the engine on or off from the next frame (E8: the page, the pack screen, the key); a launch flag holds it. No save. */
	public static synchronized void setShaders(boolean on) {
		if (!shadersLocked()) shaders = on;
	}

	/** Selects the pack under {@code shaderpacks/} (null: none) from the next frame; a launch flag holds it. No save. */
	public static synchronized void setShaderPack(@Nullable String name) {
		if (!packLocked()) shaderPack = name == null || name.isBlank() ? null : name.trim();
	}

	/** Whether {@code -Dmcms.shaders} is set (a launch flag): the screens show its value, disabled. */
	public static boolean shadersLocked() {
		return shadersFlag() != null;
	}

	/** The {@code -Dmcms.shaders} launch flag as written, or null when the launch command does not set it. */
	public static @Nullable String shadersFlag() {
		return System.getProperty(SHADERS_PROPERTY);
	}

	/** Whether {@code -Dmcms.shaders.pack} is set (a launch flag): the screens show its pack, disabled. */
	public static boolean packLocked() {
		String flag = System.getProperty(PACK_PROPERTY);
		return flag != null && !flag.isBlank();
	}

	/** The shader engine's {@code MC_HAND_DEPTH}: 0.0625, 0.125 (the default) or 0.25. Read at each pack load. */
	public static float handDepth() {
		return handDepth;
	}

	/** Sets the hand depth for the next pack load; a value that is none of the three documented ones is ignored. No save. */
	public static synchronized void setHandDepth(float value) {
		for (float allowed : HAND_DEPTHS) {
			if (allowed == value) {
				handDepth = value;
				return;
			}
		}
	}

	/**
	 * The shader engine's upscaling (ADR 0008): {@code -Dmcms.shaders.upscale} while it names a value (a launch flag, or the
	 * bench harness per scene), else the file's {@code upscale}. Read each frame: a change applies from the next frame.
	 */
	public static Upscale upscale() {
		String flag = System.getProperty(UPSCALE_PROPERTY);
		if (flag != null) {
			Upscale value = Upscale.of(flag);
			if (value != null) {
				return value;
			}
		}
		return upscale;
	}

	/** Whether {@code -Dmcms.shaders.upscale} names a value: the page shows it, disabled. */
	static boolean upscaleLocked() {
		String flag = System.getProperty(UPSCALE_PROPERTY);
		return flag != null && Upscale.of(flag) != null;
	}

	/** The page's Upscaling row: applies from the next frame; a launch flag holds it. No save. */
	static synchronized void setUpscale(Upscale value) {
		if (!upscaleLocked()) upscale = value;
	}

	private static @Nullable Float parseHandDepth(String text) {
		try {
			float value = Float.parseFloat(text.trim());
			for (float allowed : HAND_DEPTHS) {
				if (allowed == value) {
					return value;
				}
			}
		} catch (NumberFormatException e) {
			// falls through: not a number
		}
		return null;
	}

	/** A pack name as a properties value: {@link Properties#load} reads backslashes and leading blanks as escapes. */
	private static String escapeValue(String value) {
		StringBuilder out = new StringBuilder(value.length());
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			if (c == '\\' || (i == 0 && c == ' ')) {
				out.append('\\');
			}
			out.append(c);
		}
		return out.toString();
	}

	/** The value of an on/off word ("on"/"off"/"true"/"false"), or null when it is neither. */
	private static @Nullable Boolean parseOnOffWord(String word) {
		String w = word.trim().toLowerCase(Locale.ROOT);
		if (w.equals("on") || w.equals("true")) return true;
		if (w.equals("off") || w.equals("false")) return false;
		return null;
	}

	/**
	 * Reads one on/off key of the file. The on/off word comes first — {@code shaders=on} went through
	 * {@link #parseBoolean} before it and logged a false "neither true nor false" warning (the E3 review) — and a
	 * value that is neither form warns once.
	 */
	private static @Nullable Boolean parseOnOff(Properties stored, String key, Path path) {
		String raw = stored.getProperty(key);
		if (raw == null) {
			return null;
		}
		Boolean value = parseOnOffWord(raw);
		if (value == null) {
			value = parseBoolean(stored, key, path);
		}
		return value;
	}
}
