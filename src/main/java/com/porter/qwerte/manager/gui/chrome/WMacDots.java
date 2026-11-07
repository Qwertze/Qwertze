package com.porter.qwerte.manager.gui.chrome;

import com.porter.qwerte.manager.gui.style.Paint;
import com.porter.qwerte.manager.gui.style.Palette;
import com.porter.qwerte.manager.gui.style.SmGuiTheme;
import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import meteordevelopment.meteorclient.gui.widgets.pressable.WPressable;

/**
 * The Mac theme's three window dots: red closes the window, green maximizes or restores it, yellow does nothing.
 */
public class WMacDots extends WPressable {
    private final Runnable close;
    private final Runnable maximize;
    private int over = -1;

    public WMacDots(Runnable close, Runnable maximize) {
        this.close = close;
        this.maximize = maximize;
    }

    @Override
    protected void onCalculateSize() {
        int u = SmGuiTheme.unitOf(theme);
        width = 3 * 8 * u + 2 * 2 * u;
        height = 8 * u;
    }

    private int dotAt(double mx, double my) {
        int u = SmGuiTheme.unitOf(theme);
        if (my < y || my > y + height) return -1;
        double rel = mx - x;
        for (int i = 0; i < 3; i++) {
            double left = i * 10 * u;
            if (rel >= left && rel <= left + 8 * u) return i;
        }
        return -1;
    }

    @Override
    protected void onPressed(int button) {
        if (over == 0) close.run();
        else if (over == 2) maximize.run();
    }

    @Override
    protected void onRender(GuiRenderer renderer, double mouseX, double mouseY, double delta) {
        SmGuiTheme t = (SmGuiTheme) theme;
        Palette p = t.palette();
        int u = t.unit();
        over = isOver(mouseX, mouseY) ? dotAt(mouseX, mouseY) : -1;
        int[] colors = {p.dotRed, p.dotYellow, p.dotGreen};
        for (int i = 0; i < 3; i++) {
            int c = colors[i];
            if (i == over && i != 1) c = Palette.mix(c, 0xFFFFFFFF, 0.25);
            Paint.dot(renderer, t, x + (i * 10 + 4) * u, y + height / 2, 4 * u, c);
        }
    }
}
