package com.porter.qwerte.manager.gui.chrome;

import com.porter.qwerte.manager.gui.style.Paint;
import com.porter.qwerte.manager.gui.style.Palette;
import com.porter.qwerte.manager.gui.style.SmGuiTheme;
import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import meteordevelopment.meteorclient.gui.widgets.WWidget;

/**
 * One module of the Activity page's module grid: a small tile with an on/off dot, the module's name and, under it, its status
 * line. The status is cut to the tile's width once per width, not on every frame. The grid gives the tile its width.
 */
public class WModuleChip extends WWidget {
    private final String name;
    private String status;
    private final boolean on;
    private double cutFor = -1;
    private String cut = "";

    public WModuleChip(String name, String status, boolean on) {
        this.name = name;
        this.status = status == null ? "" : status;
        this.on = on;
    }

    /** A new status line, drawn from the next frame (the tile's height does not change: only non-empty statuses are updated). */
    public void setStatus(String text) {
        String t = text == null ? "" : text;
        if (t.equals(status) || status.isEmpty() || t.isEmpty()) return;
        status = t;
        cutFor = -1;
    }

    private double padX() {
        return theme.scale(6);
    }

    private double padY() {
        return theme.scale(3);
    }

    @Override
    protected void onCalculateSize() {
        SmGuiTheme t = (SmGuiTheme) theme;
        double h = 2 * padY() + theme.textHeight();
        if (!status.isEmpty()) h += t.smallHeight();
        height = h;
        width = 2 * padX() + theme.scale(12) + theme.textWidth(name);
    }

    private String fit(SmGuiTheme t, double room) {
        if (t.smallWidth(status) <= room) return status;
        int lo = 0;
        int hi = status.length();
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (t.smallWidth(status.substring(0, mid) + "..") <= room) lo = mid;
            else hi = mid - 1;
        }
        return status.substring(0, lo) + "..";
    }

    @Override
    protected void onRender(GuiRenderer renderer, double mouseX, double mouseY, double delta) {
        SmGuiTheme t = (SmGuiTheme) theme;
        Palette p = t.palette();
        double u = t.unit();
        Paint.panel(renderer, t, x, y, width, height, Math.min(p.radius, 6) * u, p.borderWidth * u, p.surface2(), on ? p.accent() : p.border());
        Paint.dot(renderer, t, x + padX() + theme.scale(3), y + padY() + theme.textHeight() / 2, theme.scale(3), on ? p.okFg() : p.errFg());
        double tx = x + padX() + theme.scale(12);
        renderer.text(name, tx, y + padY(), t.color(p.text()), false);
        if (!status.isEmpty()) {
            double room = width - 2 * padX();
            if (cutFor != width) {
                cut = fit(t, room);
                cutFor = width;
            }
            renderer.text(cut, x + padX(), y + padY() + theme.textHeight(), t.color(p.muted()), true);
        }
    }
}
