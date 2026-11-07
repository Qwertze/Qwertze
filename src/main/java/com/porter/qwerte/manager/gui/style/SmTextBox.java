package com.porter.qwerte.manager.gui.style;

import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import meteordevelopment.meteorclient.gui.themes.meteor.widgets.input.WMeteorTextBox;
import meteordevelopment.meteorclient.gui.utils.CharFilter;
import meteordevelopment.meteorclient.gui.widgets.WWidget;

/**
 * A text box like the website's .mc-input: an inner tile with a border that turns accent-coloured while it has focus.
 * Only the background is drawn here; typing, the cursor and the text are Meteor's.
 */
public class SmTextBox extends WMeteorTextBox {
    public SmTextBox(String text, String placeholder, CharFilter filter, Class<? extends Renderer> renderer) {
        super(text, placeholder, filter, renderer);
    }

    @Override
    public void renderBackground(GuiRenderer renderer, WWidget widget, boolean pressed, boolean mouseOver) {
        SmGuiTheme t = (SmGuiTheme) theme;
        Palette p = t.palette();
        double u = t.unit();
        boolean focus = isFocused();
        double bw = p.borderWidth * u;
        Paint.panel(renderer, t, x, y, width, height, p.radius * u, bw, p.surface2(), focus ? p.accent() : p.border());
        // The website's focus ring is a second 1 px accent line outside the border; here it is the border's inner edge
        if (focus) Paint.outline(renderer, t, x + bw, y + bw, width - 2 * bw, height - 2 * bw, u, p.accent());
    }
}
