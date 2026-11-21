// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * The toast of a shader pack that failed to load (E8; E3 deferred it): the pack's name and the engine's reason, once per
 * failed load ({@link Engine}). The game shows its own resource-pack failures the same way: {@code Gui.toastManager()} and
 * {@code SystemToast.addOrUpdate} (javap of 26.3, {@code Minecraft.addResourcePackLoadFailToast}).
 */
final class FailureToast {
	/** One id for the engine's toasts, shown 10 seconds: a newer failure replaces one still showing (addOrUpdate). */
	private static final SystemToast.SystemToastId ID = new SystemToast.SystemToastId(10_000L);

	private FailureToast() {
	}

	/** The longest reason a toast shows; the pack screen and the log hold the rest. */
	private static final int MAX_REASON = 160;

	/** Render thread (the frame-start hook). */
	static void show(Minecraft mc, @Nullable String pack, @Nullable String reason) {
		Component title = Component.translatable("quicksilver.shaders.toast.failed", pack != null ? pack : "");
		Component body = reason != null ? Component.literal(shortened(reason))
			: Component.translatable("quicksilver.shaders.toast.see_log");
		SystemToast.addOrUpdate(mc.gui.toastManager(), ID, title, body);
	}

	/** The reason's first line, cut at {@link #MAX_REASON} characters: a compile log can run to many lines, which a toast wraps. */
	static String shortened(String reason) {
		int newline = reason.indexOf('\n');
		String line = (newline < 0 ? reason : reason.substring(0, newline)).strip();
		return line.length() <= MAX_REASON ? line : line.substring(0, MAX_REASON - 3) + "...";
	}
}
