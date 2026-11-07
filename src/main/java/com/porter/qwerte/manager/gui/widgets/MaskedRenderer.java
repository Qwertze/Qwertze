package com.porter.qwerte.manager.gui.widgets;

import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import meteordevelopment.meteorclient.gui.utils.CharFilter;
import meteordevelopment.meteorclient.gui.widgets.input.WTextBox;
import meteordevelopment.meteorclient.utils.render.color.Color;

/**
 * Draws a star where each typed character is, at the position of that character, so the cursor and the selection
 * (which the text box lays out for the real text) line up. Password boxes must not have a placeholder: it would be
 * drawn through this renderer too. Meteor creates it by reflection, hence the public constructor.
 */
public class MaskedRenderer implements WTextBox.Renderer {
    /**
     * The character filter for password boxes: every character is allowed. Never pass null instead: WTextBox calls
     * the filter for every typed character, so a null filter throws on each key and nothing can be typed.
     */
    public static final CharFilter ANY_CHAR = (text, c) -> true;

    public MaskedRenderer() {
    }

    @Override
    public void render(GuiRenderer renderer, double x, double y, String text, Color color) {
        int length = Math.min(text.length(), 256);
        for (int i = 0; i < length; i++) {
            renderer.text("*", x + renderer.theme.textWidth(text.substring(0, i)), y, color, false);
        }
    }
}
