/*
 * New in MC Metal Shaders, 2026-10-18 (ADR 0002, docs/decisions/0002-settings-page.md): Quicksilver's page in Sodium's Video
 * Settings, through Sodium's config API (entrypoint sodium:config_api_user; Sodium calls it only when Sodium is loaded). Apache
 * License 2.0.
 */
package dev.mcms.metal;

import java.util.Locale;
import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.StorageEventHandler;
import net.caffeinemc.mods.sodium.api.config.option.OptionFlag;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.minecraft.client.PreferredGraphicsApi;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * One page, "Settings", with Use Metal, Colors and Metal HUD (QuicksilverSettings holds the values and writes the file), and an
 * overlay that greys out Sodium's own Graphics API row while Use Metal is on: Quicksilver puts Metal first whatever that row says
 * (PreferredGraphicsApiMixin). Sodium builds the page at the head of Minecraft.onGameLoadFinished, after the backend started, so
 * MetalDevice.current tells whether the game runs on Metal.
 */
public final class QuicksilverSodiumPage implements ConfigEntryPoint {
	private static final Identifier METAL = Identifier.fromNamespaceAndPath("quicksilver", "metal");
	private static final Identifier COLORS = Identifier.fromNamespaceAndPath("quicksilver", "colors");
	private static final Identifier HUD = Identifier.fromNamespaceAndPath("quicksilver", "hud");
	/** Sodium's General page row for the game's Options.preferredGraphicsBackend() (SodiumConfigBuilder.buildGeneralPage). */
	private static final Identifier GRAPHICS_API = Identifier.fromNamespaceAndPath("sodium", "general.graphics_api");
	private static final Identifier ICON = Identifier.fromNamespaceAndPath("quicksilver", "icon.png");
	/** The silver of the chrome icon (RGB; Sodium adds the alpha). */
	private static final int THEME = 0xC9CED6;

	@Override
	public void registerConfigLate(ConfigBuilder builder) {
		boolean onMetal = MetalDevice.current != null;
		// One handler object, so Sodium writes the file once per Apply, not once per changed row.
		StorageEventHandler storage = QuicksilverSettings::save;

		var metal = builder.createBooleanOption(METAL)
			.setName(Component.translatable("quicksilver.options.metal"))
			.setTooltip(tooltip("quicksilver.options.metal.tooltip", QuicksilverSettings.metalFlag(), QuicksilverSettings.METAL_PROPERTY, true))
			.setDefaultValue(true)
			.setBinding(QuicksilverSettings::setMetal, QuicksilverSettings::metalShown)
			.setStorageHandler(storage)
			.setFlags(OptionFlag.REQUIRES_GAME_RESTART)
			.setEnabled(QuicksilverSettings.metalFlag() == null);

		var colors = builder.createEnumOption(COLORS, QuicksilverSettings.Colors.class)
			.setName(Component.translatable("quicksilver.options.colors"))
			.setTooltip(tooltip("quicksilver.options.colors.tooltip", QuicksilverSettings.colorspaceFlag(), QuicksilverSettings.COLORSPACE_PROPERTY, onMetal))
			.setElementNameProvider(value -> Component.translatable("quicksilver.options.colors." + value.name().toLowerCase(Locale.ROOT)))
			.setDefaultValue(QuicksilverSettings.Colors.AUTOMATIC)
			.setBinding(QuicksilverSettings::setColors, QuicksilverSettings::colorsShown)
			.setStorageHandler(storage)
			.setEnabled(onMetal && QuicksilverSettings.colorspaceFlag() == null);

		// A launcher's MTL_HUD_ENABLED other than 1 holds the HUD off (setenv never overwrites it), so the row is held like a -D flag
		String hudFlag = QuicksilverSettings.hudFlag();
		MutableComponent hudTooltip = Component.translatable("quicksilver.options.hud.tooltip").append(Component.literal("\n"));
		if (hudFlag != null) {
			hudTooltip.append(Component.translatable("quicksilver.options.locked_launcher", "MTL_HUD_ENABLED=" + hudFlag));
		} else {
			hudTooltip.append(Component.translatable(QuicksilverSettings.hudAtLaunch() ? "quicksilver.options.hud.live" : "quicksilver.options.hud.restart"));
		}
		if (!onMetal) hudTooltip.append(Component.literal("\n\n")).append(Component.translatable("quicksilver.options.not_on_metal"));
		var hud = builder.createBooleanOption(HUD)
			.setName(Component.translatable("quicksilver.options.hud"))
			.setTooltip(hudTooltip)
			.setDefaultValue(false)
			.setBinding(QuicksilverSettings::setHud, QuicksilverSettings::hud)
			.setStorageHandler(storage)
			.setEnabled(onMetal && hudFlag == null);
		// Option flags are fixed when the page is built: without MTL_HUD_ENABLED at launch, turning the HUD on needs a restart.
		if (!QuicksilverSettings.hudAtLaunch()) hud.setFlags(OptionFlag.REQUIRES_GAME_RESTART);

		builder.registerOwnModOptions()
			.setNonTintedIcon(ICON)
			.setColorTheme(builder.createColorTheme().setBaseThemeRGB(THEME))
			.addPage(builder.createOptionPage()
				.setName(Component.translatable("quicksilver.options.page"))
				.addOptionGroup(builder.createOptionGroup().addOption(metal).addOption(colors).addOption(hud)))
			// Sodium merges an overlay field by field (OptionBuilderImpl.buildWithBaseOption): the row keeps Sodium's binding, values,
			// restart flag and tooltip text; only the enabled state and the added line come from here.
			.registerOptionOverlay(GRAPHICS_API, builder.createEnumOption(GRAPHICS_API, PreferredGraphicsApi.class)
				.setEnabledProvider(state -> !state.readBooleanOption(METAL), METAL)
				.setTooltip(value -> Component.translatable(value == PreferredGraphicsApi.VULKAN ? "options.graphicsApi.tooltip.vulkan" : "options.graphicsApi.tooltip")
					.append(Component.literal("\n\n")).append(Component.translatable("quicksilver.options.graphics_api_note"))));
	}

	/** The row's own text, then why it is grey: a launch flag that holds it, or no Metal backend running. */
	private static Component tooltip(String key, @Nullable String flag, String property, boolean onMetal) {
		MutableComponent text = Component.translatable(key);
		if (flag != null) text.append(Component.literal("\n\n")).append(Component.translatable("quicksilver.options.locked", "-D" + property + "=" + flag));
		if (!onMetal) text.append(Component.literal("\n\n")).append(Component.translatable("quicksilver.options.not_on_metal"));
		return text;
	}
}
