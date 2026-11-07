package com.porter.qwerte.manager.gui.style;

import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import meteordevelopment.meteorclient.gui.renderer.packer.GuiTexture;
import meteordevelopment.meteorclient.gui.themes.meteor.widgets.pressable.WMeteorButton;

/**
 * A button in the website's style: an inner tile with a border (Classic adds the vanilla bevel), or in the accent colour
 * when {@link #primary}.
 */
public class SmButton extends WMeteorButton {
    public boolean primary;
    /** The warning colours (the streamer button while streamer mode is on). */
    public boolean danger;
    /** Fully rounded ends (the website's small pill buttons) in themes that round their corners. */
    public boolean pill;

    public SmButton(String text, GuiTexture texture) {
        super(text, texture);
    }

    /** The website's button padding: 4 px above and below the text, 10 px at the sides (Meteor pixels are CSS pixels here). */
    @Override
    protected void onCalculateSize() {
        if (text != null) {
            textWidth = theme.textWidth(text);
            width = textWidth + 2 * theme.scale(10);
            height = theme.textHeight() + 2 * theme.scale(4);
        } else {
            super.onCalculateSize();
        }
    }

    @Override
    protected void onRender(GuiRenderer renderer, double mouseX, double mouseY, double delta) {
        SmGuiTheme t = (SmGuiTheme) theme;
        Palette p = t.palette();
        double u = t.unit();
        boolean hot = (mouseOver || pressed) && !t.hoverBlocked();
        int fill = danger ? p.errBg() : primary ? p.accent() : p.surface2();
        if (hot) fill = Palette.mix(fill, primary ? p.onAccent() : danger ? p.errFg() : p.text(), pressed ? 0.22 : 0.12);
        int border = primary ? Palette.mix(p.accent(), p.border(), 0.4) : p.border();
        double bw = p.borderWidth * u;
        double radius = pill && p.radius > 0 ? height / 2 : p.radius * u;
        Paint.panel(renderer, t, x, y, width, height, radius, bw, fill, border);
        if (p.bevel) {
            int light = primary ? Palette.mix(fill, 0xFFFFFFFF, 0.55) : 0xFFFFFFFF;
            int dark = primary ? Palette.mix(fill, 0xFF000000, 0.45) : 0xFF555555;
            if (pressed) Paint.bevel(renderer, t, x + bw, y + bw, width - 2 * bw, height - 2 * bw, u, dark, light);
            else Paint.bevel(renderer, t, x + bw, y + bw, width - 2 * bw, height - 2 * bw, u, light, dark);
        }
        int ink = danger ? p.errFg() : primary ? p.onAccent() : p.text();
        double pad = pad();
        if (text != null) {
            renderer.text(text, x + width / 2 - textWidth / 2, y + (height - t.textHeight()) / 2, t.color(ink), false);
        } else {
            double ts = t.textHeight();
            renderer.quad(x + width / 2 - ts / 2, y + pad, ts, ts, texture, t.color(ink));
        }
    }
}
