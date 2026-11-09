package com.porter.qwerte.manager.gui.chrome;

import com.porter.qwerte.manager.gui.style.Paint;
import com.porter.qwerte.manager.gui.style.Palette;
import com.porter.qwerte.manager.gui.style.SmGuiTheme;
import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import meteordevelopment.meteorclient.gui.widgets.pressable.WPressable;

/** The header's palette button (the website's theme button): a small round button with a painter palette that opens Settings. */
public class WPaletteButton extends WPressable {
    @Override
    protected void onCalculateSize() {
        height = theme.textHeight() + 2 * theme.scale(4);
        width = height + theme.scale(10);
    }

    @Override
    protected void onRender(GuiRenderer renderer, double mouseX, double mouseY, double delta) {
        SmGuiTheme t = (SmGuiTheme) theme;
        Palette p = t.palette();
        double u = t.unit();
        boolean hot = (mouseOver || pressed) && !t.hoverBlocked();
        int fill = hot ? Palette.mix(p.surface2(), p.text(), pressed ? 0.22 : 0.12) : p.surface2();
        double radius = p.radius == 0 ? 0 : height / 2;
        Paint.panel(renderer, t, x, y, width, height, radius, p.borderWidth * u, fill, p.border());
        double cx = x + width / 2;
        double cy = y + height / 2;
        double r = height * 0.30;
        Paint.dot(renderer, t, cx, cy, r, p.text());
        double d = r * 0.26;
        Paint.dot(renderer, t, cx - r * 0.45, cy - r * 0.30, d, p.errFg());
        Paint.dot(renderer, t, cx + r * 0.10, cy - r * 0.55, d, p.warnFg());
        Paint.dot(renderer, t, cx + r * 0.55, cy - r * 0.05, d, p.okFg());
        Paint.dot(renderer, t, cx - r * 0.10, cy + r * 0.45, d, p.accent());
    }
}
