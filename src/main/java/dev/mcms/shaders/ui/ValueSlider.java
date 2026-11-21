// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.ui;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

/**
 * A slider over a value option's allowed values, in list order (shaders.properties {@code sliders}). Each value sits at an
 * equal step, and the slider snaps to the nearest one as it moves. The arrow keys step one value (the game's slider moves
 * by one pixel's worth, which the snap would undo for any list shorter than the slider is wide).
 */
final class ValueSlider extends AbstractSliderButton {
	private final String name;
	private final List<String> values;
	private final Function<String, String> display;
	private final Consumer<String> onChange;
	private int index;

	/**
	 * @param name    the option's label, shown before the value
	 * @param values  the allowed values in list order; not empty
	 * @param current the current value (a member of {@code values})
	 * @param display the text a value shows (prefix, label, suffix)
	 */
	ValueSlider(int width, int height, String name, List<String> values, String current,
		Function<String, String> display, Consumer<String> onChange) {
		super(0, 0, width, height, Component.empty(), fraction(values.size(), Math.max(0, values.indexOf(current))));
		this.name = name;
		this.values = values;
		this.display = display;
		this.onChange = onChange;
		this.index = Math.max(0, values.indexOf(current));
		updateMessage();
	}

	private static double fraction(int count, int index) {
		return count <= 1 ? 0.0 : index / (double) (count - 1);
	}

	@Override
	protected void updateMessage() {
		if (values == null) return;  // the superclass constructor may call this before the fields are set
		setMessage(Component.literal(name + ": " + display.apply(values.get(index))));
	}

	@Override
	protected void applyValue() {
		int next = (int) Math.round(value * (values.size() - 1));
		value = fraction(values.size(), next);
		if (next != index) {
			index = next;
			updateMessage();
			onChange.accept(values.get(index));
		}
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (canChangeValue && (event.isLeft() || event.isRight())) {
			int next = Math.max(0, Math.min(values.size() - 1, index + (event.isLeft() ? -1 : 1)));
			if (next != index) {
				index = next;
				value = fraction(values.size(), index);
				updateMessage();
				onChange.accept(values.get(index));
			}
			return true;
		}
		return super.keyPressed(event);
	}
}
