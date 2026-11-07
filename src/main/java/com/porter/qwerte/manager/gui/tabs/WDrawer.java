package com.porter.qwerte.manager.gui.tabs;

import com.porter.qwerte.manager.gui.style.Paint;
import com.porter.qwerte.manager.gui.style.Palette;
import com.porter.qwerte.manager.gui.style.SmGuiTheme;
import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import meteordevelopment.meteorclient.gui.widgets.WWidget;

/**
 * The background of the details drawer: a solid panel with a title, drawn in the page's child order just after the grid
 * it lies over and just before the details that go on it. It takes no events of its own.
 */
public class WDrawer extends WWidget {
    public String title = "Details";

    @Override
    protected void onRender(GuiRenderer renderer, double mouseX, double mouseY, double delta) {
        if (!(theme instanceof SmGuiTheme t)) return;
        Palette p = t.palette();
        double u = t.unit();
        double bw = p.borderWidth * u;
        Paint.panel(renderer, t, x, y, width, height, p.radius * u, bw, p.surface(), p.accent());
        renderer.text(title, x + 4 * u, y + 4 * u, t.color(p.muted()), true);
    }
}
