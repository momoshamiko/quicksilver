/*
 * New in MC Metal Shaders, 2026-10-18 (ADR 0002, docs/decisions/0002-settings-page.md): Quicksilver's page in Sodium's Video
 * Settings, through Sodium's config API (entrypoint sodium:config_api_user; Sodium calls it only when Sodium is loaded). Apache
 * License 2.0.
 */
package dev.mcms.metal;

import dev.mcms.shaders.runtime.Engine;
import dev.mcms.shaders.ui.PackScreen;
import java.util.Locale;
import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.StorageEventHandler;
import net.caffeinemc.mods.sodium.api.config.option.OptionFlag;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.caffeinemc.mods.sodium.client.config.ConfigManager;
import net.caffeinemc.mods.sodium.client.config.structure.Config;
import net.minecraft.client.Minecraft;
import net.minecraft.client.PreferredGraphicsApi;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * One page, "Settings", with Use Metal, Colors and Metal HUD, and since E8 a Shaders group: the shader engine's on/off row and a
 * Shader packs button that opens the engine's pack screen (QuicksilverSettings holds the values and writes the file). An overlay
 * greys out Sodium's own Graphics API row while Use Metal is on: Quicksilver puts Metal first whatever that row says
 * (PreferredGraphicsApiMixin). Where Metal cannot run (MetalSupport, RIL-497), Use Metal is grey with the reason and the overlay
 * is left out, so Sodium's row chooses the backend. Sodium builds the page at the head of Minecraft.onGameLoadFinished, after the
 * backend started, so MetalDevice.current tells whether the game runs on Metal, and Engine.cannotRunReason whether the shader
 * engine can (on Metal).
 */
public final class QuicksilverSodiumPage implements ConfigEntryPoint {
	private static final Identifier METAL = Identifier.fromNamespaceAndPath("quicksilver", "metal");
	private static final Identifier COLORS = Identifier.fromNamespaceAndPath("quicksilver", "colors");
	private static final Identifier HUD = Identifier.fromNamespaceAndPath("quicksilver", "hud");
	/** E8: the shader engine's on/off row and the button that opens its pack screen. */
	private static final Identifier SHADERS = Identifier.fromNamespaceAndPath("quicksilver", "shaders");
	private static final Identifier PACKS = Identifier.fromNamespaceAndPath("quicksilver", "shader_packs");
	/** ADR 0008: the engine's render scale with MetalFX's spatial scaler. */
	private static final Identifier UPSCALE = Identifier.fromNamespaceAndPath("quicksilver", "upscale");
	/** Sodium's General page row for the game's Options.preferredGraphicsBackend() (SodiumConfigBuilder.buildGeneralPage). */
	private static final Identifier GRAPHICS_API = Identifier.fromNamespaceAndPath("sodium", "general.graphics_api");
	private static final Identifier ICON = Identifier.fromNamespaceAndPath("quicksilver", "icon.png");
	/** Silver, after the name (RGB; Sodium adds the alpha). */
	private static final int THEME = 0xC9CED6;

	@Override
	public void registerConfigLate(ConfigBuilder builder) {
		boolean onMetal = MetalDevice.current != null;
		MetalSupport.Blocker blocker = MetalSupport.blocker();
		// One handler object, so Sodium writes the file once per Apply, not once per changed row.
		StorageEventHandler storage = QuicksilverSettings::save;

		MutableComponent metalTooltip = tooltip("quicksilver.options.metal.tooltip", QuicksilverSettings.metalFlag(), QuicksilverSettings.METAL_PROPERTY, true);
		if (blocker != null) metalTooltip.append(Component.literal("\n\n")).append(Component.translatable(blocker.langKey));
		var metal = builder.createBooleanOption(METAL)
			.setName(Component.translatable("quicksilver.options.metal"))
			.setTooltip(metalTooltip)
			.setDefaultValue(true)
			.setBinding(QuicksilverSettings::setMetal, QuicksilverSettings::metalShown)
			.setControlHiddenWhenDisabled(false)
			.setStorageHandler(storage)
			.setFlags(OptionFlag.REQUIRES_GAME_RESTART)
			.setEnabled(blocker == null && QuicksilverSettings.metalFlag() == null);

		var colors = builder.createEnumOption(COLORS, QuicksilverSettings.Colors.class)
			.setName(Component.translatable("quicksilver.options.colors"))
			.setTooltip(tooltip("quicksilver.options.colors.tooltip", QuicksilverSettings.colorspaceFlag(), QuicksilverSettings.COLORSPACE_PROPERTY, onMetal))
			.setElementNameProvider(value -> Component.translatable("quicksilver.options.colors." + value.name().toLowerCase(Locale.ROOT)))
			.setDefaultValue(QuicksilverSettings.Colors.AUTOMATIC)
			.setBinding(QuicksilverSettings::setColors, QuicksilverSettings::colorsShown)
			.setControlHiddenWhenDisabled(false)
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
			.setBinding(QuicksilverSettings::setHud, QuicksilverSettings::hudShown)
			.setControlHiddenWhenDisabled(false)
			.setStorageHandler(storage)
			.setEnabled(onMetal && hudFlag == null);
		// Option flags are fixed when the page is built: without MTL_HUD_ENABLED at launch, turning the HUD on needs a restart.
		if (!QuicksilverSettings.hudAtLaunch()) hud.setFlags(OptionFlag.REQUIRES_GAME_RESTART);

		// E8 (plan.md, "Quicksilver's page"): the shader engine's row and the button to its pack screen. Sodium builds the page
		// once, so whether the engine can run here (on Metal) is read now; a launch flag holds the row (ADR 0002).
		String cannotRun = Engine.cannotRunReason();
		MutableComponent shadersTooltip = Component.translatable("quicksilver.options.shaders.tooltip");
		if (QuicksilverSettings.shadersLocked()) {
			shadersTooltip.append(Component.literal("\n\n"))
				.append(Component.translatable("quicksilver.options.locked", "-Dmcms.shaders=" + QuicksilverSettings.shadersFlag()));
		}
		if (cannotRun != null) {
			shadersTooltip.append(Component.literal("\n\n")).append(Component.translatable("quicksilver.shaders.status.cannot", cannotRun));
		}
		var shaders = builder.createBooleanOption(SHADERS)
			.setName(Component.translatable("quicksilver.options.shaders"))
			.setTooltip(shadersTooltip)
			.setDefaultValue(false)
			.setBinding(QuicksilverSettings::setShaders, QuicksilverSettings::shadersOn)
			.setControlHiddenWhenDisabled(false)
			.setStorageHandler(storage)
			.setEnabled(cannotRun == null && !QuicksilverSettings.shadersLocked());
		MutableComponent packsTooltip = Component.translatable("quicksilver.options.packs.tooltip");
		if (cannotRun != null) {
			packsTooltip.append(Component.literal("\n\n")).append(Component.translatable("quicksilver.shaders.status.cannot", cannotRun));
		}
		var packs = builder.createExternalButtonOption(PACKS)
			.setName(Component.translatable("quicksilver.options.packs"))
			.setTooltip(packsTooltip)
			.setEnabled(cannotRun == null)
			.setScreenConsumer(page -> Minecraft.getInstance().gui.setScreen(PackScreen.create(page, QuicksilverSodiumPage::rowsFromSettings)));
		// ADR 0008: live from the next frame (the engine resizes its targets; nothing reloads). The property holds it like a flag.
		String upscaleFlag = QuicksilverSettings.upscaleLocked() ? System.getProperty(QuicksilverSettings.UPSCALE_PROPERTY) : null;
		MutableComponent upscaleTooltip = tooltip("quicksilver.options.upscale.tooltip", upscaleFlag,
			QuicksilverSettings.UPSCALE_PROPERTY, true);
		if (cannotRun != null) {
			upscaleTooltip.append(Component.literal("\n\n")).append(Component.translatable("quicksilver.shaders.status.cannot", cannotRun));
		}
		var upscale = builder.createEnumOption(UPSCALE, QuicksilverSettings.Upscale.class)
			.setName(Component.translatable("quicksilver.options.upscale"))
			.setTooltip(upscaleTooltip)
			.setElementNameProvider(value -> Component.translatable("quicksilver.options.upscale." + value.key()))
			.setDefaultValue(QuicksilverSettings.Upscale.OFF)
			.setBinding(QuicksilverSettings::setUpscale, QuicksilverSettings::upscale)
			.setControlHiddenWhenDisabled(false)
			.setStorageHandler(storage)
			.setEnabled(cannotRun == null && !QuicksilverSettings.upscaleLocked());

		var options = builder.registerOwnModOptions()
			.setNonTintedIcon(ICON)
			.setColorTheme(builder.createColorTheme().setBaseThemeRGB(THEME))
			.addPage(builder.createOptionPage()
				.setName(Component.translatable("quicksilver.options.page"))
				.addOptionGroup(builder.createOptionGroup().addOption(metal).addOption(colors).addOption(hud))
				.addOptionGroup(builder.createOptionGroup().setName(Component.translatable("quicksilver.options.shaders_group"))
					.addOption(shaders).addOption(packs).addOption(upscale)));
		// Where Metal cannot run, Sodium's Graphics API row decides the backend, so it stays free.
		if (blocker != null) return;
		// Sodium merges an overlay field by field (OptionBuilderImpl.buildWithBaseOption): the row keeps Sodium's binding, values,
		// restart flag and tooltip text; only the enabled state and the added line come from here.
		options.registerOptionOverlay(GRAPHICS_API, builder.createEnumOption(GRAPHICS_API, PreferredGraphicsApi.class)
			.setEnabledProvider(state -> !state.readBooleanOption(METAL), METAL)
			.setTooltip(value -> Component.translatable(value == PreferredGraphicsApi.VULKAN ? "options.graphicsApi.tooltip.vulkan" : "options.graphicsApi.tooltip")
				.append(Component.literal("\n\n")).append(Component.translatable("quicksilver.options.graphics_api_note"))));
	}

	/**
	 * After the pack screen's Apply: Sodium reads the rows' bindings only when its screen opens, so "Use shaders" would show
	 * the value from before. Sodium's own reset from the bindings refreshes the rows (internal API of the pinned Sodium, javap
	 * of 0.9.3-beta.1: {@code ConfigManager.CONFIG}, {@code Config.anyOptionChanged} and {@code resetAllOptionsFromBindings},
	 * all public). It is skipped while the page holds changes not yet applied, which the reset would discard; the rows then
	 * show their old values until Video Settings opens again.
	 */
	private static void rowsFromSettings() {
		try {
			Config config = ConfigManager.CONFIG;
			if (config != null && !config.anyOptionChanged()) config.resetAllOptionsFromBindings();
		} catch (LinkageError | RuntimeException e) {
			// another Sodium: the rows show their old values until Video Settings opens again
		}
	}

	/** The row's own text, then why it is grey: a launch flag that holds it, or no Metal backend running. */
	private static MutableComponent tooltip(String key, @Nullable String flag, String property, boolean onMetal) {
		MutableComponent text = Component.translatable(key);
		if (flag != null) text.append(Component.literal("\n\n")).append(Component.translatable("quicksilver.options.locked", "-D" + property + "=" + flag));
		if (!onMetal) text.append(Component.literal("\n\n")).append(Component.translatable("quicksilver.options.not_on_metal"));
		return text;
	}
}
