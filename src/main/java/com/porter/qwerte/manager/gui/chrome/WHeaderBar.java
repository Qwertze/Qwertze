package com.porter.qwerte.manager.gui.chrome;

import com.porter.qwerte.manager.gui.style.Paint;
import com.porter.qwerte.manager.gui.style.Palette;
import com.porter.qwerte.manager.gui.style.SmGuiTheme;
import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import meteordevelopment.meteorclient.gui.utils.Cell;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.WVerticalList;

/**
 * The website's header: a dark bar holding rows of {@link WFlow}s (the title row, the index info row, the status line and
 * the page buttons). The frame gives it its width; each row wraps to fit.
 */
public class WHeaderBar extends WVerticalList {
    /** The bar's width in physical pixels, set by the frame before the layout runs. */
    public double innerWidth;
    /** Top corners are rounded by this many physical pixels, to follow the window's corners. */
    public double cornerRadius;

    public WHeaderBar() {
        spacing = 8;
    }

    @Override
    public void calculateSize() {
        double side = theme.scale(12);
        int n = cells.size();
        for (int i = 0; i < n; i++) {
            Cell<?> cell = cells.get(i);
            cell.padHorizontal(12);
            cell.padTop(i == 0 ? 10 : 0);
            cell.padBottom(i == n - 1 ? 10 : 0);
            WWidget w = cell.widget();
            if (w instanceof WFlow flow) flow.maxWidth = Math.max(1, innerWidth - 2 * side);
        }
        super.calculateSize();
        width = innerWidth;
    }

    @Override
    protected void onRender(GuiRenderer renderer, double mouseX, double mouseY, double delta) {
        if (!(theme instanceof SmGuiTheme t)) return;
        Palette p = t.palette();
        double u = t.unit();
        Paint.roundRect(renderer, t, x, y, width, height, cornerRadius, p.bar());
        // Square off the bottom corners
        Paint.rect(renderer, t, x, y + Math.min(height, cornerRadius), width, Math.max(0, height - cornerRadius), p.bar());
        Paint.rect(renderer, t, x, y + height - p.borderWidth * u, width, p.borderWidth * u, p.border());
    }
}
