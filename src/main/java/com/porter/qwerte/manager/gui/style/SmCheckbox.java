package com.porter.qwerte.manager.gui.style;

import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import meteordevelopment.meteorclient.gui.themes.meteor.widgets.pressable.WMeteorCheckbox;
import meteordevelopment.meteorclient.gui.widgets.WWidget;

/**
 * A checkbox on an inner tile with the website's border; the tick is Meteor's (an accent-coloured square that grows).
 */
public class SmCheckbox extends WMeteorCheckbox {
    public SmCheckbox(boolean checked) {
        super(checked);
    }

    @Override
    public void renderBackground(GuiRenderer renderer, WWidget widget, boolean pressed, boolean mouseOver) {
        SmGuiTheme t = (SmGuiTheme) theme;
        Palette p = t.palette();
        double u = t.unit();
        boolean hot = (pressed || mouseOver) && !t.hoverBlocked();
        int fill = hot ? Palette.mix(p.surface2(), p.text(), 0.10) : p.surface2();
        Paint.panel(renderer, t, x, y, width, height, p.radius * u, p.borderWidth * u, fill, hot ? p.accent() : p.border());
    }
}
