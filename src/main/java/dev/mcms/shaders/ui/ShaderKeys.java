// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.ui;

import dev.mcms.metal.QuicksilverSettings;
import dev.mcms.shaders.runtime.Engine;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * The shader keys (plan.md E8): toggle shaders, reload the pack, open the shader pack screen. Vanilla key bindings in a
 * Quicksilver category, so the player rebinds them in Controls and the game saves them in options.txt.
 * {@code EngineOptionsMixin} adds them to {@code Options.keyMappings} before the options load, and
 * {@code EngineKeybindsMixin} polls them where the game handles its own bindings ({@code Minecraft.handleKeybinds}, from
 * {@code tick}).
 *
 * <p>Default keys (26.3 binds keyboard keys by SDL scancode through {@code KeyMapping(String, int, Category)}, which uses
 * {@code InputConstants.Type.KEYBOARD}: javap of {@code Options.<init>} shows key.forward 26 (W), key.chat 23 (T),
 * key.friends 18 (O)): K (14) toggles, R (21) reloads and U (24) opens the screen; vanilla binds none of the three. Iris's
 * O for its pack screen is vanilla's Friends key in 26.3.
 */
public final class ShaderKeys {
	private static final KeyMapping.Category CATEGORY =
		KeyMapping.Category.register(Identifier.fromNamespaceAndPath("quicksilver", "shaders"));
	static final KeyMapping TOGGLE = new KeyMapping("key.quicksilver.shaders.toggle", 14, CATEGORY);
	static final KeyMapping RELOAD = new KeyMapping("key.quicksilver.shaders.reload", 21, CATEGORY);
	static final KeyMapping PACKS = new KeyMapping("key.quicksilver.shaders.packs", 24, CATEGORY);

	private ShaderKeys() {
	}

	/** The three bindings, for {@code Options.keyMappings}. */
	public static KeyMapping[] all() {
		return new KeyMapping[] { TOGGLE, RELOAD, PACKS };
	}

	/** One tick's presses (render thread, from {@code Minecraft.handleKeybinds}). */
	public static void handle(Minecraft mc) {
		while (TOGGLE.consumeClick()) {
			toggle(mc);
		}
		while (RELOAD.consumeClick()) {
			reload(mc);
		}
		while (PACKS.consumeClick()) {
			mc.gui.setScreen(PackScreen.create(null));
		}
	}

	private static void toggle(Minecraft mc) {
		if (QuicksilverSettings.shadersLocked()) {
			tell(mc, Component.translatable("quicksilver.shaders.locked", "-Dmcms.shaders"));
			return;
		}
		String reason = Engine.cannotRunReason();
		if (reason != null) {
			tell(mc, Component.translatable("quicksilver.shaders.status.cannot", reason));
			return;
		}
		boolean on = !QuicksilverSettings.shadersOn();
		QuicksilverSettings.setShaders(on);
		QuicksilverSettings.save();
		String pack = QuicksilverSettings.shaderPackName();
		if (on && pack == null) {
			tell(mc, Component.translatable("quicksilver.shaders.key.no_pack"));
		} else {
			tell(mc, on ? Component.translatable("quicksilver.shaders.key.on", pack) : Component.translatable("quicksilver.shaders.key.off"));
		}
	}

	private static void reload(Minecraft mc) {
		String reason = Engine.cannotRunReason();
		if (reason != null) {
			tell(mc, Component.translatable("quicksilver.shaders.status.cannot", reason));
			return;
		}
		String pack = QuicksilverSettings.shaderPackName();
		if (!QuicksilverSettings.shadersOn() || pack == null) {
			tell(mc, Component.translatable("quicksilver.shaders.status.off"));
			return;
		}
		// Settings first, not the engine's state: a second press in the same tick finds the engine off (turned off by the
		// first, loading from the next frame), and the pack still reloads once.
		Engine.get().reloadPack();
		tell(mc, Component.translatable("quicksilver.shaders.key.reloading", pack));
	}

	private static void tell(Minecraft mc, Component message) {
		var player = mc.player;
		if (player != null) {
			player.sendOverlayMessage(message);
		}
	}
}
