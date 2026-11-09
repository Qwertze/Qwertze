package com.porter.qwerte.manager.gui.style;

import com.porter.qwerte.manager.gui.widgets.TextWrap;
import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import meteordevelopment.meteorclient.gui.widgets.WLabel;

import java.util.ArrayList;
import java.util.List;

/**
 * Text that wraps to a width in physical pixels. Unlike Meteor's multi-line label it measures and draws in the same size (the
 * website's small text when {@code small}), breaks words longer than the width (ids, paths) instead of letting them run on,
 * and wraps again when {@link #set} gives it new text. {@code limit} is the room the label has, from PageRoom or the card.
 */
public class WWrapLabel extends WLabel {
    private final boolean small;
    private double limit;
    private final List<String> lines = new ArrayList<>(2);
    private boolean dirty = true;

    public WWrapLabel(String text, boolean small, double limit) {
        super(text == null ? "" : text, small);
        this.small = small;
        this.limit = limit;
    }

    @Override
    public void set(String text) {
        String t = text == null ? "" : text;
        if (t.equals(this.text)) return;
        this.text = t;
        dirty = true;
        invalidate();
    }

    @Override
    protected void onCalculateSize() {
        if (dirty) {
            lines.clear();
            for (String part : text.split("\n", -1)) {
                lines.addAll(TextWrap.wrap(part, limit, s -> theme.textWidth(s, s.length(), small)));
            }
            dirty = false;
        }
        double w = 0;
        for (String l : lines) w = Math.max(w, theme.textWidth(l, l.length(), small));
        width = w;
        height = theme.textHeight(small) * Math.max(1, lines.size());
    }

    @Override
    protected void onRender(GuiRenderer renderer, double mouseX, double mouseY, double delta) {
        SmGuiTheme t = (SmGuiTheme) theme;
        double h = theme.textHeight(small);
        var c = color != null ? color : t.color(t.palette().text());
        for (int i = 0; i < lines.size(); i++) {
            if (!lines.get(i).isEmpty()) renderer.text(lines.get(i), x, y + h * i, c, small);
        }
    }
}
