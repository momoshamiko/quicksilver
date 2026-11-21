// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.distant;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.frontend.FrontendCommandEncoder;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The renderpearl frontend's in-pass flag ({@code FrontendCommandEncoder.isInRenderPass}), which its {@code createRenderPass} refuses to
 * open a pass over. While the phase hook draws Distant Horizons' transparent layer inside the game's open pass, the flag is cleared for
 * that pass and set back afterwards, so the game's pass keeps its state. Read through a private-field handle: the game's class is not
 * mixed in for this one field. When the field cannot be found, {@link #available} is false, the phase hook draws nothing, and the
 * after-level fallback draws the water (logged once).
 */
public final class FrontendPassFlag {

	private static final Logger LOG = LoggerFactory.getLogger("Quicksilver");

	/** The flag's saved state, a holder reused across frames: the frontend encoder it was read from (null when none) and its value. */
	public static final class Saved {
		@Nullable FrontendCommandEncoder encoder;
		boolean inPass;
	}

	private static final @Nullable VarHandle IN_PASS = lookup();

	private FrontendPassFlag() {
	}

	private static @Nullable VarHandle lookup() {
		try {
			return MethodHandles.privateLookupIn(FrontendCommandEncoder.class, MethodHandles.lookup())
				.findVarHandle(FrontendCommandEncoder.class, "isInRenderPass", boolean.class);
		} catch (ReflectiveOperationException | RuntimeException notFound) {
			LOG.warn("[mcms-shaders] the renderpearl frontend's isInRenderPass field was not found ({}); Distant Horizons water"
				+ " draws after the level instead of in the phase hook", notFound.toString());
			return null;
		}
	}

	/** Whether the flag's field handle resolved (the game version has the field). */
	public static boolean handleResolved() {
		return IN_PASS != null;
	}

	/** Whether the flag can be read and written: the handle resolved and the device's encoder is renderpearl's frontend. */
	public static boolean available() {
		return IN_PASS != null && frontend() != null;
	}

	/** The device's command encoder when it is renderpearl's frontend (the one whose flag this reads), else null. */
	static @Nullable FrontendCommandEncoder frontend() {
		CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
		return encoder instanceof FrontendCommandEncoder frontend ? frontend : null;
	}

	/** Clears the flag and saves its value into {@code into}; nothing changes (and {@code into} stays empty) when the flag is unavailable. */
	public static void suspend(Saved into) {
		FrontendCommandEncoder encoder = IN_PASS == null ? null : frontend();
		into.encoder = encoder;
		if (encoder == null) {
			return;
		}
		into.inPass = (boolean) IN_PASS.get(encoder);
		IN_PASS.set(encoder, false);
	}

	/** Puts back the flag {@link #suspend} saved. */
	public static void resume(Saved from) {
		if (from.encoder != null && IN_PASS != null) {
			IN_PASS.set(from.encoder, from.inPass);
		}
	}

	/** The flag's current value, or false when unavailable (DrawLog only). */
	public static boolean inPass() {
		FrontendCommandEncoder encoder = IN_PASS == null ? null : frontend();
		return encoder != null && (boolean) IN_PASS.get(encoder);
	}
}
