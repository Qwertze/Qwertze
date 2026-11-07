package com.porter.qwerte.manager.gui.chrome;

import com.porter.qwerte.manager.gui.style.Paint;
import com.porter.qwerte.manager.gui.style.Palette;
import com.porter.qwerte.manager.gui.style.SmGuiTheme;
import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import meteordevelopment.meteorclient.gui.widgets.pressable.WPressable;

/**
 * One page button of the header's navigation (the website's .dash-nav): flat on the bar, a lighter pill for the page you
 * are on.
 */
public class WNavPill extends WPressable {
    private static final String BOLD = "§l";
    private final String text;
    private final boolean active;

    public WNavPill(String text, boolean active) {
        this.text = text;
        this.active = active;
    }

    private double padX() {
        return theme.scale(10);
    }

    private double padY() {
        return theme.scale(4);
    }

    @Override
    protected void onCalculateSize() {
        width = padX() + theme.textWidth(active ? BOLD + text : text) + padX();
        height = padY() + theme.textHeight() + padY();
    }

    @Override
    protected void onRender(GuiRenderer renderer, double mouseX, double mouseY, double delta) {
        SmGuiTheme t = (SmGuiTheme) theme;
        Palette p = t.palette();
        double u = t.unit();
        boolean hot = (mouseOver || pressed) && !t.hoverBlocked();
        int fill = active ? p.navActiveBg() : p.nav();
        if (hot) fill = Palette.alpha(fill) == 0 ? Palette.withAlpha(p.navText(), pressed ? 70 : 40) : Palette.mix(fill, p.navText(), pressed ? 0.30 : 0.15);
        double radius = p.radius == 0 ? 0 : height / 2;
        Paint.roundRect(renderer, t, x, y, width, height, radius, fill);
        if (p.bevel && active) Paint.bevel(renderer, t, x, y, width, height, u, 0xFF6B6B6B, 0xFF2A2A2A);
        int ink = active ? p.navActive() : p.navText();
        renderer.text(active ? BOLD + text : text, x + padX(), y + padY(), t.color(ink), false);
    }
}
