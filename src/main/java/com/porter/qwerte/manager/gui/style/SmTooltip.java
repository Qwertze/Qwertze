package com.porter.qwerte.manager.gui.style;

import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import meteordevelopment.meteorclient.gui.themes.meteor.widgets.WMeteorTooltip;

/** Meteor's hover text, on a solid panel instead of a see-through one. */
public class SmTooltip extends WMeteorTooltip {
    public SmTooltip(String text) {
        super(text);
    }

    @Override
    protected void onRender(GuiRenderer renderer, double mouseX, double mouseY, double delta) {
        SmGuiTheme t = (SmGuiTheme) theme;
        Palette p = t.palette();
        double u = t.unit();
        if (t.overlay != null) t.overlay.tooltipShown(x, y, width, height);
        Paint.panel(renderer, t, x, y, width, height, Math.min(p.radius, 3) * u, u, p.surface(), p.border());
    }
}
