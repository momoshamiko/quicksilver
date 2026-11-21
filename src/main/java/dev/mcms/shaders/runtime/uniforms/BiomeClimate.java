// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.uniforms;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import net.minecraft.world.level.biome.Biome;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A biome's downfall, the {@code rainfall} uniform ("the rainfall property of the current biome as defined by Minecraft
 * internally", Iris biome uniforms page). The 26.3 game keeps it in {@code Biome.climateSettings}, a private field of the
 * package-private record {@code Biome$ClimateSettings} whose public {@code downfall()} returns it (javap 2026-11-16); no
 * public method reads it, so the handles below are opened once by reflection on those names. When they cannot be (a game
 * that renamed them), {@code rainfall} reads 0 and one WARN says so.
 */
final class BiomeClimate {
	private static final Logger LOG = LoggerFactory.getLogger("Quicksilver");
	private static final @Nullable MethodHandle CLIMATE;
	private static final @Nullable MethodHandle DOWNFALL;

	static {
		MethodHandle climate = null, downfall = null;
		try {
			Field field = Biome.class.getDeclaredField("climateSettings");
			field.setAccessible(true);
			Method method = field.getType().getDeclaredMethod("downfall");
			method.setAccessible(true);
			climate = MethodHandles.lookup().unreflectGetter(field);
			downfall = MethodHandles.lookup().unreflect(method);
		} catch (ReflectiveOperationException | RuntimeException e) {
			LOG.warn("[mcms-shaders] the biome's downfall is not readable ({}); the rainfall uniform reads 0", e.toString());
		}
		CLIMATE = climate;
		DOWNFALL = downfall;
	}

	private BiomeClimate() {
	}

	/** Whether the game's fields resolved (a renamed field makes {@code rainfall} read 0). */
	static boolean readable() {
		return CLIMATE != null && DOWNFALL != null;
	}

	/** The biome's downfall (plains 0.4, desert 0), or 0 when the game's fields are not readable. */
	static float downfall(Biome biome) {
		if (CLIMATE == null || DOWNFALL == null) {
			return 0f;
		}
		try {
			return (float) DOWNFALL.invoke(CLIMATE.invoke(biome));
		} catch (Throwable t) {
			return 0f;
		}
	}
}
