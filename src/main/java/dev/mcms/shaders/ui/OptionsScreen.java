// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.ui;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;

import dev.mcms.metal.QuicksilverSettings;
import dev.mcms.shaders.pack.Lang;
import dev.mcms.shaders.pack.OptionCatalog;
import dev.mcms.shaders.pack.OptionFile;
import dev.mcms.shaders.pack.OptionKind;
import dev.mcms.shaders.pack.OptionLabels;
import dev.mcms.shaders.pack.OptionValues;
import dev.mcms.shaders.pack.PackModel;
import dev.mcms.shaders.pack.PackOption;
import dev.mcms.shaders.pack.PackSource;
import dev.mcms.shaders.pack.ProfileSet;
import dev.mcms.shaders.pack.ScreenTree;
import dev.mcms.shaders.pack.StandardMacros;
import dev.mcms.shaders.runtime.Engine;
import dev.mcms.shaders.runtime.distant.DistantHorizonsRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * The options of one shader pack, built from the pack's screen tree: the main screen and its sub-screens (links), columns,
 * the profile button, the hand depth row (a global setting, on the main screen), and one widget per option: a toggle for a
 * boolean, a value cycle for a value, a slider for a value named in {@code sliders}. Apply writes the changed values to
 * {@code shaderpacks/<pack file name>.txt} and reloads the pack when it is the selected one; Cancel discards the changes.
 * Escape on a sub-screen goes back one screen and keeps the changes; on the main screen it cancels.
 */
public final class OptionsScreen extends Screen {
	/** The title, then room for an error ({@link ScreenText#STATUS_LINES} lines: a read or save error can be long). */
	private static final int HEADER = 22 + ScreenText.STATUS_LINES * ScreenText.LINE + 1;
	private static final int FOOTER = 36;
	private static final int ROW = 24;
	private static final int BUTTON_H = 20;
	private static final int GAP = 4;
	/** The widest row: two columns of 208, so most "Name: value" labels show whole (the list's own row is 220). */
	private static final int MAX_ROW_WIDTH = 420;
	private static final int WHITE = 0xFFFFFFFF;
	private static final int RED = 0xFFFF5555;

	/**
	 * What a loaded pack gives the screen.
	 *
	 * @param valuesError why the values file could not be read (the defaults then show, as the engine loads them); null
	 */
	private record Loaded(PackModel model, OptionLabels labels, ScreenTree.Screen main, @Nullable String valuesError) {
	}

	private final Screen parent;
	private final String packName;
	private final Path valuesFile;
	/**
	 * The pack's read, off the render thread: it takes 0.6 s (BSL) to 3.3 s (Photon), so the screen opens at once and fills
	 * its rows when the read lands ({@link #tick}).
	 */
	private final CompletableFuture<Loaded> reading;
	private @Nullable Loaded loaded;
	private @Nullable Component loadError;

	/** The values as the file had them, and the hand depth as settings had it: compared on Apply. */
	private @Nullable OptionValues original;
	private final float originalHandDepth;
	private @Nullable OptionValues current;
	private float handDepth;

	/** The screen shown: {@link ScreenTree#MAIN} or a sub-screen's name; the way back. */
	private String screen = ScreenTree.MAIN;
	private final Deque<String> history = new ArrayDeque<>();
	/** Replaces the error line while set (a save that failed). */
	private @Nullable Component note;
	private @Nullable Button profileButton;

	private OptionsList list;
	private HeaderAndFooterLayout layout;
	private Button back;
	private Button reset;
	private Button apply;

	/**
	 * @param parent   the screen shown again on Apply and Cancel
	 * @param packName the pack's file name in {@code shaderpacks}
	 */
	public OptionsScreen(Screen parent, String packName) {
		super(Component.translatable("quicksilver.shaders.options.title", packName));
		this.parent = parent;
		this.packName = packName;
		Path dir = PackScreen.folder();
		this.valuesFile = OptionValues.fileFor(dir, packName);
		Path packFile = dir.resolve(packName);
		Path values = this.valuesFile;
		// Read here, on the render thread; the read itself touches no game state.
		String language = Minecraft.getInstance().options.languageCode;
		boolean distantSession = DistantHorizonsRuntime.active();
		this.reading = CompletableFuture.supplyAsync(() -> read(packFile, values, language, distantSession));
		this.originalHandDepth = QuicksilverSettings.handDepth();
		this.handDepth = originalHandDepth;
	}

	/** Reads the pack with the values file (a background thread). */
	private static Loaded read(Path packFile, Path valuesFile, String language, boolean distantSession) {
		try (PackSource source = PackSource.open(packFile)) {
			boolean distant = distantSession && PackModel.declaresDistantPrograms(source);
			Properties saved;
			String valuesError = null;
			try {
				saved = OptionValues.readFile(valuesFile);
			} catch (IOException | IllegalArgumentException e) {
				// The engine reads the pack with the defaults then too (Engine.readPack), so the screen shows what draws.
				saved = new Properties();
				valuesError = e.toString();
			}
			PackModel model = PackModel.load(source, StandardMacros.engine(distant), language, saved);
			OptionLabels labels = new OptionLabels(model.lang(), Lang.select(source, Lang.FALLBACK));
			return new Loaded(model, labels, mainScreen(model), valuesError);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** Takes the read when it lands: the rows fill, or the error line shows. */
	@Override
	public void tick() {
		if (loaded != null || loadError != null || !reading.isDone()) return;
		try {
			loaded = reading.join();
			original = loaded.model().values();
			current = original;
			if (loaded.valuesError() != null) {
				note = Component.translatable("quicksilver.shaders.options.values_unreadable", loaded.valuesError());
			}
		} catch (CompletionException | CancellationException e) {
			Throwable cause = e.getCause() instanceof UncheckedIOException io ? io.getCause() : e.getCause();
			loadError = Component.translatable("quicksilver.shaders.options.load_failed",
				String.valueOf(cause != null ? cause : e));
		}
		rebuildRows();
	}

	/** The main screen; a pack without a {@code screen} directive shows every option (the documented default). */
	private static ScreenTree.Screen mainScreen(PackModel model) {
		ScreenTree.Screen main = model.screens().screens().get(ScreenTree.MAIN);
		if (main == null) {
			main = ScreenTree.parse(Map.of("screen", "*"), model.options()).screens().get(ScreenTree.MAIN);
		}
		return main;
	}

	@Override
	protected void init() {
		layout = new HeaderAndFooterLayout(this, HEADER, FOOTER);
		list = layout.addToContents(new OptionsList(minecraft, width, height - HEADER - FOOTER, HEADER, ROW));
		LinearLayout row = layout.addToFooter(LinearLayout.horizontal().spacing(6));
		back = row.addChild(Button.builder(Component.translatable("gui.back"), b -> goBack()).width(90).build());
		reset = row.addChild(Button.builder(Component.translatable("controls.reset"), b -> reset()).width(90).build());
		row.addChild(Button.builder(Component.translatable("gui.cancel"), b -> onClose()).width(90).build());
		apply = row.addChild(Button.builder(Component.translatable("quicksilver.shaders.button.apply"), b -> apply())
			.width(90).build());
		layout.visitWidgets(this::addRenderableWidget);
		rebuildRows();
		repositionElements();
	}

	@Override
	protected void repositionElements() {
		list.updateSize(width, layout);
		layout.arrangeElements();
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		graphics.centeredText(font, title, width / 2, 9, WHITE);
		Component line = note != null ? note : loadError;
		if (line != null) {
			ScreenText.status(graphics, font, line, width, 22, RED);
		} else if (loaded == null) {
			graphics.centeredText(font, Component.translatable("quicksilver.shaders.options.reading"), width / 2, 22, WHITE);
		}
		back.visible = !history.isEmpty();
		reset.active = loaded != null;
		apply.active = loaded != null;
	}

	/** Rebuilds the rows of the screen being shown; called on every change of screen, profile or reset. */
	private void rebuildRows() {
		profileButton = null;
		List<OptionsRow> rows = new ArrayList<>();
		if (loaded != null) {
			ScreenTree.Screen shown = screenNamed(screen);
			if (shown != null) {
				int columns = Math.max(1, shown.columns());
				// Hand depth is Quicksilver's row, not the pack's: on a row of its own above the pack's grid, so the pack's
				// cells keep the columns its screen line gives them.
				if (screen.equals(ScreenTree.MAIN)) rows.add(new OptionsRow(columns, cellsRow(handDepthWidget(), columns)));
				List<AbstractWidget> cells = new ArrayList<>();
				for (ScreenTree.Item item : shown.items()) {
					switch (item) {
						case ScreenTree.OptionItem o -> cells.add(optionWidget(o.option()));
						case ScreenTree.LinkItem l -> cells.add(linkWidget(l.screen()));
						case ScreenTree.ProfileSelector p -> cells.add(profileWidget());
						case ScreenTree.EmptySlot e -> cells.add(null);
					}
				}
				for (int i = 0; i < cells.size(); i += columns) {
					List<AbstractWidget> rowCells = new ArrayList<>();
					for (int c = 0; c < columns; c++) {
						rowCells.add(i + c < cells.size() ? cells.get(i + c) : null);
					}
					rows.add(new OptionsRow(columns, rowCells));
				}
			}
		}
		list.replaceEntries(rows);
	}

	/** One widget in the first cell of a row of {@code columns} cells. */
	private static List<@Nullable AbstractWidget> cellsRow(AbstractWidget first, int columns) {
		List<@Nullable AbstractWidget> row = new ArrayList<>();
		row.add(first);
		for (int c = 1; c < columns; c++) row.add(null);
		return row;
	}

	private ScreenTree.@Nullable Screen screenNamed(String name) {
		if (loaded == null) return null;
		if (name.equals(ScreenTree.MAIN)) return loaded.main();
		return loaded.model().screens().screens().get(name);
	}

	private void navigate(String target) {
		history.push(screen);
		screen = target;
		rebuildRows();
	}

	private void goBack() {
		if (history.isEmpty()) return;
		screen = history.pop();
		rebuildRows();
	}

	private AbstractWidget handDepthWidget() {
		CycleButton<Float> button = CycleButton.<Float>builder(v -> Component.literal(Float.toString(v)), handDepth)
			.withValues(QuicksilverSettings.HAND_DEPTHS)
			.create(0, 0, 150, BUTTON_H, Component.translatable("quicksilver.shaders.hand_depth"),
				(btn, v) -> handDepth = v);
		return withTip(button, Component.translatable("quicksilver.shaders.hand_depth.tooltip"));
	}

	private @Nullable AbstractWidget optionWidget(String name) {
		if (loaded == null) return null;
		PackOption option = loaded.model().options().get(name).orElse(null);
		if (option == null) return null;
		String label = loaded.labels().optionName(name);
		String value = current.get(name);
		if (option.ambiguous() || option.hiddenConst() || (option.kind() != OptionKind.BOOLEAN && option.values().isEmpty())) {
			Component reason = Component.translatable(option.ambiguous() ? "quicksilver.shaders.option.ambiguous"
				: "quicksilver.shaders.option.no_list");
			Button disabled = Button.builder(Component.literal(label + ": " + displayed(name, value)), b -> { })
				.width(150).build();
			disabled.active = false;
			return withTip(disabled, reason);
		}
		String comment = loaded.labels().optionComment(option);
		Component tip = comment.isEmpty() ? null : Component.literal(comment);
		Component caption = Component.literal(label);
		if (option.kind() == OptionKind.BOOLEAN) {
			CycleButton<Boolean> toggle = CycleButton.onOffBuilder(current.isOn(name))
				.create(0, 0, 150, BUTTON_H, caption, (btn, v) -> change(name, v.toString()));
			return withTip(toggle, tip);
		}
		if (loaded.model().properties().sliders().contains(name)) {
			ValueSlider slider = new ValueSlider(150, BUTTON_H, label, option.values(), value,
				v -> displayed(name, v), v -> change(name, v));
			return withTip(slider, tip);
		}
		CycleButton<String> cycle = CycleButton.<String>builder(v -> Component.literal(displayed(name, v)), value)
			.withValues(option.values())
			.create(0, 0, 150, BUTTON_H, caption, (btn, v) -> change(name, v));
		return withTip(cycle, tip);
	}

	private String displayed(String option, String value) {
		return loaded == null ? value : loaded.labels().displayValue(option, value);
	}

	private AbstractWidget linkWidget(String target) {
		String comment = loaded == null ? "" : loaded.labels().screenComment(target);
		Button link = Button.builder(Component.literal(loaded == null ? target : loaded.labels().screenTitle(target)),
			b -> navigate(target)).width(150).build();
		return withTip(link, comment.isEmpty() ? null : Component.literal(comment));
	}

	private AbstractWidget profileWidget() {
		profileButton = Button.builder(profileLabel(), b -> cycleProfile()).width(150).build();
		List<String> names = profiles().names();
		if (names.isEmpty()) profileButton.active = false;
		String comment = loaded == null ? "" : loaded.labels().profileComment();
		return withTip(profileButton, comment.isEmpty() ? null : Component.literal(comment));
	}

	private ProfileSet profiles() {
		return loaded.model().profiles();
	}

	/** The profile button's text: the profile the values match, or "Custom". */
	private Component profileLabel() {
		Optional<ProfileSet.Resolved> now = profiles().detect(current);
		Component name = now.isPresent()
			? Component.literal(loaded.labels().profileName(now.get().name()))
			: Component.translatable("quicksilver.shaders.options.custom");
		return Component.translatable("quicksilver.shaders.options.profile", name);
	}

	private void refreshProfileLabel() {
		if (profileButton != null && loaded != null) profileButton.setMessage(profileLabel());
	}

	/** Moves to the next profile in the file's order; a Custom setting starts at the first. */
	private void cycleProfile() {
		List<String> names = profiles().names();
		if (names.isEmpty()) return;
		Optional<ProfileSet.Resolved> now = profiles().detect(current);
		int next = now.map(r -> names.indexOf(r.name()) + 1).orElse(0) % names.size();
		current = current.with(loaded.model().options(), profiles().resolve(names.get(next)).settings());
		rebuildRows();
	}

	private void change(String name, String value) {
		current = current.with(loaded.model().options(), Map.of(name, value));
		refreshProfileLabel();
	}

	private void reset() {
		if (loaded == null) return;
		current = OptionValues.defaults(loaded.model().options());
		handDepth = StandardMacros.DEFAULT_HAND_DEPTH;
		rebuildRows();
	}

	/**
	 * Writes the values file, saves the hand depth, and reloads the pack when it is the selected one and anything changed.
	 * Closes the screen on success; a failed write stays open with the reason.
	 */
	private void apply() {
		if (loaded == null) return;
		OptionCatalog catalog = loaded.model().options();
		try {
			// The file's names the pack does not declare stay as they were (OptionFile): Reset and edits never touch them.
			OptionFile.write(valuesFile, OptionValues.defaults(catalog).values(), current.values(), original.unknown());
		} catch (IOException | RuntimeException e) {
			note = Component.translatable("quicksilver.shaders.options.save_failed", e.toString());
			return;
		}
		QuicksilverSettings.setHandDepth(handDepth);
		QuicksilverSettings.save();
		boolean changed = !original.values().equals(current.values()) || handDepth != originalHandDepth;
		if (changed && QuicksilverSettings.shadersOn() && packName.equals(QuicksilverSettings.shaderPackName())) {
			Engine.get().reloadPack();
		}
		onClose();
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (event.isEscape() && !history.isEmpty()) {
			goBack();
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}

	private static <W extends AbstractWidget> W withTip(W widget, @Nullable Component tip) {
		if (tip != null) widget.setTooltip(Tooltip.create(tip));
		return widget;
	}

	private final class OptionsList extends ContainerObjectSelectionList<OptionsRow> {
		OptionsList(Minecraft minecraft, int width, int height, int y, int entryHeight) {
			super(minecraft, width, height, y, entryHeight);
		}

		@Override
		public int getRowWidth() {
			return Math.min(MAX_ROW_WIDTH, getWidth() - 40);
		}
	}

	/** A row of up to {@code columns} widgets; a null cell is an empty spot. */
	private static final class OptionsRow extends ContainerObjectSelectionList.Entry<OptionsRow> {
		private final int columns;
		private final List<@Nullable AbstractWidget> cells;
		private final List<AbstractWidget> present;

		OptionsRow(int columns, List<@Nullable AbstractWidget> cells) {
			this.columns = columns;
			this.cells = cells;
			this.present = cells.stream().filter(c -> c != null).toList();
		}

		@Override
		public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float partialTick) {
			int cellWidth = (getContentWidth() - GAP * (columns - 1)) / columns;
			for (int i = 0; i < cells.size(); i++) {
				AbstractWidget cell = cells.get(i);
				if (cell == null) continue;
				cell.setX(getContentX() + i * (cellWidth + GAP));
				cell.setY(getContentY());
				cell.setWidth(cellWidth);
				cell.setHeight(BUTTON_H);
				cell.extractRenderState(graphics, mouseX, mouseY, partialTick);
			}
		}

		@Override
		public List<? extends GuiEventListener> children() {
			return present;
		}

		@Override
		public List<? extends NarratableEntry> narratables() {
			return present;
		}

		@Override
		public void visitWidgets(Consumer<AbstractWidget> consumer) {
			present.forEach(consumer);
		}
	}
}
