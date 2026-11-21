// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.ui;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import org.jspecify.annotations.Nullable;

import com.mojang.blaze3d.Blaze3D;

import dev.mcms.metal.QuicksilverSettings;
import dev.mcms.shaders.pack.PackListing;
import dev.mcms.shaders.runtime.Engine;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * The shader pack screen: the packs in {@code shaderpacks/} and a "Shaders off" row, the state of the selected pack, and
 * the buttons Apply (saves the choice; the engine picks it up on the next frame), Options (the selected pack's options),
 * Open folder and Done. A launch flag ({@code -Dmcms.shaders}, {@code -Dmcms.shaders.pack}) locks the list and Apply.
 */
public final class PackScreen extends Screen {
	/** The title, then room for the status ({@link ScreenText#STATUS_LINES} lines: a failure reason can be long). */
	private static final int HEADER = 22 + ScreenText.STATUS_LINES * ScreenText.LINE + 1;
	private static final int FOOTER = 36;
	private static final int ROW = 20;
	private static final int WHITE = 0xFFFFFFFF;
	private static final int RED = 0xFFFF5555;

	private final @Nullable Screen parent;
	/** Runs after Apply saved, before the parent shows again (the page refreshes its rows); may be null. */
	private final @Nullable Runnable onApplied;
	private List<String> packs = List.of();
	private boolean listLoaded;
	private @Nullable CompletableFuture<List<String>> listingTask;
	private final boolean shadersLocked;
	private final boolean packLocked;
	private final boolean shadersForcedOn;
	/** The pack the player chose; null for "Shaders off". */
	private @Nullable String chosenPack;
	/** Replaces the status line while set (a folder that could not be opened). */
	private @Nullable Component note;
	/** Why the folder could not be listed at the last read, or null; the next good read clears it. */
	private @Nullable Component listingError;
	/** Whether the game window had focus at the last tick: the list is read again when it comes back (Open folder, then a drop). */
	private boolean windowWasActive = true;

	private PackList list;
	private HeaderAndFooterLayout layout;
	private Button apply;
	private Button options;

	private PackScreen(@Nullable Screen parent, @Nullable Runnable onApplied) {
		super(Component.translatable("quicksilver.shaders.packs.title"));
		this.parent = parent;
		this.onApplied = onApplied;
		this.shadersLocked = QuicksilverSettings.shadersLocked();
		this.packLocked = QuicksilverSettings.packLocked();
		this.shadersForcedOn = this.shadersLocked && QuicksilverSettings.shadersOn();
		this.chosenPack = QuicksilverSettings.shadersOn() ? QuicksilverSettings.shaderPackName() : null;
		startListing();
	}

	/** Starts scanning the shaderpacks folder off the render thread so opening the screen never hitches. */
	private void startListing() {
		if (this.listingTask != null && !this.listingTask.isDone()) {
			return;
		}
		Path dir = folder();
		this.listingTask = CompletableFuture.supplyAsync(() -> {
			try {
				return PackListing.list(dir);
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		});
	}

	/** Whether switching packs in the list is completely blocked (pack flag set, or shaders forced off). */
	private boolean fullyLocked() {
		return packLocked || (shadersLocked && !shadersForcedOn);
	}

	/** Whether selecting {@code pack} (null = "Shaders off") is blocked by launch flags. */
	private boolean entryBlocked(@Nullable String pack) {
		if (fullyLocked()) return true;
		return shadersForcedOn && pack == null;
	}

	/** The pack screen; {@code parent} is shown again on Done and after Apply. */
	public static PackScreen create(@Nullable Screen parent) {
		return new PackScreen(parent, null);
	}

	/** The pack screen; {@code onApplied} runs after Apply saved the choice, before {@code parent} shows again. */
	public static PackScreen create(@Nullable Screen parent, Runnable onApplied) {
		return new PackScreen(parent, onApplied);
	}

	/** The folder the engine reads packs from. */
	static Path folder() {
		return FabricLoader.getInstance().getGameDir().resolve("shaderpacks");
	}

	@Override
	protected void init() {
		boolean showLockBanner = shadersLocked || packLocked;
		// A launch flag adds a header line that names it (a tooltip on the list would cover the title).
		int header = showLockBanner ? HEADER + ScreenText.LINE : HEADER;
		layout = new HeaderAndFooterLayout(this, header, FOOTER);
		list = layout.addToContents(new PackList(minecraft, width, height - header - FOOTER, header, ROW));
		list.active = !fullyLocked();

		fillList();

		int btnW = Math.min(100, Math.max(60, (width - 40) / 4));
		LinearLayout row = layout.addToFooter(LinearLayout.horizontal().spacing(8));
		apply = row.addChild(Button.builder(Component.translatable("quicksilver.shaders.button.apply"), b -> apply())
			.width(btnW).build());
		options = row.addChild(Button.builder(Component.translatable("quicksilver.shaders.button.options"), b -> openOptions())
			.width(btnW).build());
		row.addChild(Button.builder(Component.translatable("quicksilver.shaders.button.open_folder"), b -> openFolder())
			.width(btnW).build());
		row.addChild(Button.builder(Component.translatable("gui.done"), b -> onClose()).width(btnW).build());

		String blocked = applyBlocked();
		if (blocked != null) apply.setTooltip(Tooltip.create(Component.literal(blocked)));
		layout.visitWidgets(this::addRenderableWidget);
		repositionElements();
	}

	@Override
	protected void repositionElements() {
		list.updateSize(width, layout);
		layout.arrangeElements();
	}

	/** The list's rows from {@link #packs}, the chosen pack selected (also when its file is gone from the folder). */
	private void fillList() {
		List<String> names = new ArrayList<>(packs);
		if (chosenPack != null && !names.contains(chosenPack)) names.add(chosenPack);
		names.sort(PackListing.ORDER);
		List<PackEntry> entries = new ArrayList<>();
		entries.add(new PackEntry(null));
		for (String name : names) entries.add(new PackEntry(name));
		list.replaceEntries(entries);
		for (PackEntry entry : entries) {
			if (Objects.equals(entry.pack, chosenPack)) list.setSelected(entry);
		}
	}

	/** Polls the background folder scan and triggers a fresh scan when the window regains focus. */
	@Override
	public void tick() {
		if (this.listingTask != null && this.listingTask.isDone()) {
			CompletableFuture<List<String>> done = this.listingTask;
			this.listingTask = null;
			this.listLoaded = true;
			try {
				List<String> found = done.join();
				this.listingError = null;
				if (!found.equals(this.packs)) {
					this.packs = found;
					if (this.list != null) fillList();
				}
			} catch (CompletionException | CancellationException e) {
				Throwable cause = e.getCause() instanceof UncheckedIOException io ? io.getCause() : e.getCause();
				this.listingError = Component.translatable("quicksilver.shaders.status.list_failed",
					String.valueOf(cause != null ? cause : e));
			}
		}
		boolean active = minecraft.isWindowActive();
		if (active && !windowWasActive) {
			startListing();
		}
		windowWasActive = active;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		graphics.centeredText(font, title, width / 2, 9, WHITE);
		Component problem = note != null ? note : listingError;
		boolean bad = problem != null || Engine.cannotRunReason() != null || engineFailed();
		Component line = problem != null ? problem
			: (!listLoaded ? Component.translatable("quicksilver.shaders.packs.loading_list") : status());
		ScreenText.status(graphics, font, line, width, 22, bad ? RED : WHITE);
		if (shadersLocked || packLocked) {
			graphics.centeredText(font, lockedBy(), width / 2, 22 + ScreenText.STATUS_LINES * ScreenText.LINE, WHITE);
		}
		String blocked = applyBlocked();
		apply.active = blocked == null;
		apply.setTooltip(blocked != null ? Tooltip.create(Component.literal(blocked)) : null);
		options.active = chosenPack != null;
	}

	/** Why Apply cannot be used, or null when it can. */
	private @Nullable String applyBlocked() {
		if (entryBlocked(chosenPack)) return lockedBy().getString();
		return Engine.cannotRunReason();
	}

	private Component lockedBy() {
		String flags = shadersLocked && packLocked ? "-Dmcms.shaders and -Dmcms.shaders.pack"
			: shadersLocked ? "-Dmcms.shaders" : "-Dmcms.shaders.pack";
		return Component.translatable("quicksilver.shaders.locked", flags);
	}

	private boolean engineFailed() {
		return chosenPack != null && chosenPack.equals(Engine.get().packName())
			&& Engine.get().state() == Engine.State.FAILED;
	}

	/** The state of the selected pack, as the engine reports it. */
	private Component status() {
		String cannot = Engine.cannotRunReason();
		if (cannot != null) return Component.translatable("quicksilver.shaders.status.cannot", cannot);
		if (chosenPack == null) return Component.translatable("quicksilver.shaders.status.off");
		Engine engine = Engine.get();
		if (!chosenPack.equals(engine.packName())) return Component.translatable("quicksilver.shaders.status.not_in_use");
		return switch (engine.state()) {
			case OFF -> Component.translatable("quicksilver.shaders.status.not_in_use");
			case LOADING -> Component.translatable("quicksilver.shaders.status.loading");
			case ACTIVE -> Component.translatable("quicksilver.shaders.status.active");
			case FAILED -> Component.translatable("quicksilver.shaders.status.failed",
				Objects.requireNonNullElse(engine.failureReason(), "unknown"));
		};
	}

	/** The pack the engine serves or is loading right now, or null (a failed pack is not in use). */
	private static @Nullable String inUse() {
		Engine engine = Engine.get();
		Engine.State state = engine.state();
		return state == Engine.State.ACTIVE || state == Engine.State.LOADING ? engine.packName() : null;
	}

	private void apply() {
		if (applyBlocked() != null) return;
		boolean retryFailed = engineFailed();
		if (!shadersLocked) {
			QuicksilverSettings.setShaders(chosenPack != null);
		}
		if (chosenPack != null) QuicksilverSettings.setShaderPack(chosenPack);
		QuicksilverSettings.save();
		if (retryFailed) {
			Engine.get().reloadPack();
		}
		if (onApplied != null) onApplied.run();
		onClose();
	}

	private void openOptions() {
		if (chosenPack == null) return;
		minecraft.gui.setScreen(new OptionsScreen(this, chosenPack));
	}

	private void openFolder() {
		Path dir = folder();
		try {
			Files.createDirectories(dir);
			Blaze3D.openPath(dir);
			note = null;
		} catch (IOException | RuntimeException e) {
			note = Component.translatable("quicksilver.shaders.status.open_failed", e.toString());
		}
	}

	@Override
	public void onClose() {
		if (this.listingTask != null) {
			this.listingTask.cancel(true);
			this.listingTask = null;
		}
		if (parent == null) {
			super.onClose();
		} else {
			minecraft.gui.setScreen(parent);
		}
	}

	private final class PackList extends ObjectSelectionList<PackEntry> {
		PackList(Minecraft minecraft, int width, int height, int y, int entryHeight) {
			super(minecraft, width, height, y, entryHeight);
		}

		/** The chosen pack follows the selection, so the arrow keys choose as a click does; a launch flag holds it. */
		@Override
		public void setSelected(@Nullable PackEntry entry) {
			if (entry != null && entryBlocked(entry.pack) && !Objects.equals(entry.pack, chosenPack)) {
				return;
			}
			super.setSelected(entry);
			if (entry != null) {
				chosenPack = entry.pack;
			}
		}
	}

	/** One row: a pack's file name, or "Shaders off" (pack null). */
	private final class PackEntry extends ObjectSelectionList.Entry<PackEntry> {
		private final @Nullable String pack;

		PackEntry(@Nullable String pack) {
			this.pack = pack;
		}

		private Component label() {
			if (pack == null) return Component.translatable("quicksilver.shaders.packs.off");
			if (pack.equals(inUse())) return Component.translatable("quicksilver.shaders.packs.in_use", pack);
			return Component.literal(pack);
		}

		@Override
		public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float partialTick) {
			Component text = label();
			int maxW = Math.max(20, getContentWidth() - 4);
			if (font.width(text) > maxW) {
				int ellipsis = font.width("...");
				String clipped = font.plainSubstrByWidth(text.getString(), Math.max(0, maxW - ellipsis)) + "...";
				text = Component.literal(clipped);
			}
			int color = entryBlocked(pack) && !Objects.equals(pack, chosenPack) ? 0xFF888888 : WHITE;
			graphics.text(font, text, getContentX() + 2, getContentY() + (getContentHeight() - 9) / 2, color);
		}

		@Override
		public Component getNarration() {
			return label();
		}

		@Override
		public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
			if (entryBlocked(pack)) return false;
			list.setSelected(this);
			return true;
		}
	}
}
