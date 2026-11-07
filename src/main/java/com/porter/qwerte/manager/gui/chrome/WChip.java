package com.porter.qwerte.manager.gui.chrome;

import com.porter.qwerte.manager.gui.style.Paint;
import com.porter.qwerte.manager.gui.style.Palette;
import com.porter.qwerte.manager.gui.style.SmGuiTheme;
import com.porter.qwerte.manager.gui.style.SmLabel;
import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;

/**
 * A short message on the website's warning badge: the warning background and text colours of the theme. Nothing is
 * drawn, and no room taken, while the text is empty.
 */
public class WChip extends SmLabel {
    public WChip(String text) {
        super(text, false);
    }

    private double padX() {
        return theme.scale(6);
    }

    private double padY() {
        return theme.scale(2);
    }

    @Override
    protected void onCalculateSize() {
        super.onCalculateSize();
        if (!text.isEmpty()) {
            width += 2 * padX();
            height += 2 * padY();
        }
    }

    @Override
    protected void onRender(GuiRenderer renderer, double mouseX, double mouseY, double delta) {
        if (text.isEmpty()) return;
        SmGuiTheme t = (SmGuiTheme) theme;
        Palette p = t.palette();
        double radius = p.radius == 0 ? 0 : height / 2;
        Paint.roundRect(renderer, t, x, y, width, height, radius, p.warnBg());
        renderer.text(text, x + padX(), y + padY(), t.color(p.warnFg()), false);
    }
}
