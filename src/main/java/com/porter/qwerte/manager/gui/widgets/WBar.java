package com.porter.qwerte.manager.gui.widgets;

import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.utils.render.color.Color;

/** A small bar with a number on it (health, food). Pure drawing: the owner sets the value. */
public final class WBar extends WWidget {
    private static final Color BACK = new Color(20, 20, 24, 235);
    private static final Color TEXT = new Color(255, 255, 255, 255);

    private final Color fill;
    private double max;
    private double value;
    private String text = "-";

    public WBar(Color fill, double max) {
        this.fill = fill;
        this.max = max;
    }

    public void setMax(double max) {
        this.max = Math.max(1, max);
    }

    /** NaN draws an empty bar with a dash. */
    public void set(double value, String text) {
        this.value = Double.isNaN(value) ? 0 : Math.max(0, Math.min(max, value));
        this.text = text == null ? "" : text;
    }

    @Override
    protected void onCalculateSize() {
        width = theme.scale(150);
        height = theme.textHeight() + theme.scale(4);
    }

    @Override
    protected void onRender(GuiRenderer renderer, double mouseX, double mouseY, double delta) {
        renderer.quad(x, y, width, height, BACK);
        if (value > 0) renderer.quad(x, y, width * (value / max), height, fill);
        double w = theme.textWidth(text);
        renderer.text(text, x + (width - w) / 2, y + (height - theme.textHeight()) / 2, TEXT, false);
    }
}
