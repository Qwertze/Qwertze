package com.porter.qwerte.manager.gui.chrome;

import com.porter.qwerte.manager.gui.style.Paint;
import com.porter.qwerte.manager.gui.style.Palette;
import com.porter.qwerte.manager.gui.style.SmGuiTheme;
import com.porter.qwerte.manager.gui.style.WindowTheme;
import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import meteordevelopment.meteorclient.gui.widgets.pressable.WPressable;

/** One theme of the Appearance card: three swatches of the theme's own colours, its name and a line about it. The current one has the accent border and a dot. */
public class WThemeCard extends WPressable {
    private static final String BOLD = "§l";
    private final WindowTheme which;
    private final String blurb;
    private final boolean current;
    private final String boldName;
    private final int[] swatch = new int[3];

    public WThemeCard(WindowTheme which, String blurb, boolean current) {
        this.which = which;
        this.blurb = blurb;
        this.current = current;
        this.boldName = BOLD + which;
        Palette own = which.palette();
        swatch[0] = own.bg();
        swatch[1] = own.surface();
        swatch[2] = own.accent();
    }

    public WindowTheme which() {
        return which;
    }

    private double swatchW() {
        return theme.scale(48);
    }

    @Override
    protected void onCalculateSize() {
        SmGuiTheme t = (SmGuiTheme) theme;
        double pad = theme.scale(8);
        height = 2 * pad + theme.textHeight() + theme.scale(2) + t.smallHeight();
        width = 2 * pad + swatchW() + theme.textWidth(boldName) + theme.scale(20);
    }

    @Override
    protected void onRender(GuiRenderer renderer, double mouseX, double mouseY, double delta) {
        SmGuiTheme t = (SmGuiTheme) theme;
        Palette p = t.palette();
        double u = t.unit();
        boolean hot = mouseOver && !t.hoverBlocked();
        double bw = (current ? Math.max(2, p.borderWidth * 2) : p.borderWidth) * u;
        int fill = hot ? Palette.mix(p.surface2(), p.text(), 0.08) : p.surface2();
        Paint.panel(renderer, t, x, y, width, height, p.radius * u, bw, fill, current ? p.accent() : hot ? Palette.mix(p.border(), p.accent(), 0.5) : p.border());
        double pad = theme.scale(8);
        double d = theme.scale(14);
        double sy = y + (height - d) / 2;
        int[] colors = swatch;
        for (int i = 0; i < 3; i++) {
            double cx = x + pad + i * d * 0.85 + d / 2;
            Paint.dot(renderer, t, cx, sy + d / 2, d / 2 + u, p.border());
            Paint.dot(renderer, t, cx, sy + d / 2, d / 2, colors[i]);
        }
        double tx = x + pad + swatchW() + theme.scale(4);
        renderer.text(boldName, tx, y + pad, t.color(p.text()), false);
        if (current) {
            Paint.dot(renderer, t, tx + theme.textWidth(boldName) + theme.scale(8), y + pad + theme.textHeight() / 2, theme.scale(3), p.accent());
        }
        renderer.text(blurb, tx, y + pad + theme.textHeight() + theme.scale(2), t.color(p.muted()), true);
    }
}
