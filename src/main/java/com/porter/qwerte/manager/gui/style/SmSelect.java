package com.porter.qwerte.manager.gui.style;

import com.porter.qwerte.manager.gui.frame.WOverlayLayer;
import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import meteordevelopment.meteorclient.gui.widgets.input.WDropdown;

import java.util.ArrayList;
import java.util.List;

/**
 * A select like the website's .mc-select. It never opens Meteor's own list: a click opens a solid list on the window's
 * overlay layer, which sits above everything (item icons included), takes the next click wherever it lands, and closes
 * on a click outside or Esc. It keeps Meteor's {@code get}, {@code set} and {@code action}, so every tab that used a
 * dropdown works unchanged.
 */
public class SmSelect<T> extends WDropdown<T> {
    public SmSelect(T[] values, T value) {
        super(values, value);
    }

    @Override
    public void init() {
        // No list of value widgets: the list is drawn by the overlay. The (empty) root is only there because the
        // base class positions it.
        root = createRootWidget();
        root.theme = theme;
    }

    @Override
    protected WDropdownRoot createRootWidget() {
        return new WDropdownRoot() {
        };
    }

    @Override
    protected WDropdownValue createValueWidget() {
        return new WDropdownValue() {
        };
    }

    @Override
    protected void onPressed(int button) {
        SmGuiTheme t = (SmGuiTheme) theme;
        WOverlayLayer overlay = t.overlay;
        if (overlay == null) return;
        List<String> rows = new ArrayList<>(values.length);
        int selected = -1;
        for (int i = 0; i < values.length; i++) {
            rows.add(String.valueOf(values[i]));
            if (values[i].equals(value)) selected = i;
        }
        overlay.open(new WOverlayLayer.Popup(x, y, width, height, rows, selected, index -> {
            T picked = values[index];
            boolean changed = !picked.equals(value);
            value = picked;
            if (changed && action != null) action.run();
        }));
    }

    @Override
    protected void onRender(GuiRenderer renderer, double mouseX, double mouseY, double delta) {
        SmGuiTheme t = (SmGuiTheme) theme;
        Palette p = t.palette();
        double u = t.unit();
        double pad = pad();
        boolean hot = (mouseOver || pressed) && !t.hoverBlocked();
        boolean open = t.overlay != null && t.overlay.anchoredAt(x, y);
        int fill = hot ? Palette.mix(p.surface2(), p.text(), 0.10) : p.surface2();
        Paint.panel(renderer, t, x, y, width, height, p.radius * u, p.borderWidth * u, fill, open || hot ? p.accent() : p.border());
        renderer.text(String.valueOf(get()), x + pad, y + pad, t.color(p.text()), false);
        double s = t.textHeight() * 0.55;
        Paint.chevronDown(renderer, t, x + width - pad - s, y + height / 2 - s / 4, s, p.text());
    }
}
