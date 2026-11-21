// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.ui;

import java.util.List;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/** Header text of the shader screens: a status or error line wrapped to the screen's width, so a long reason stays readable. */
final class ScreenText {
	/** The height of one header line. */
	static final int LINE = 13;
	/** The status lines a header keeps room for; a longer text shows its first two lines (the log and the toast hold all). */
	static final int STATUS_LINES = 2;

	private ScreenText() {
	}

	/** Draws {@code text} centred from {@code y}, wrapped to the screen's width less a margin, at most {@link #STATUS_LINES}. */
	static void status(GuiGraphicsExtractor graphics, Font font, Component text, int screenWidth, int y, int color) {
		List<FormattedCharSequence> lines = font.split(text, screenWidth - 16);
		for (int i = 0; i < Math.min(lines.size(), STATUS_LINES); i++) {
			graphics.centeredText(font, lines.get(i), screenWidth / 2, y + i * LINE, color);
		}
	}
}
