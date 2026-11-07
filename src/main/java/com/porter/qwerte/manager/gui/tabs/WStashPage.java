package com.porter.qwerte.manager.gui.tabs;

import com.porter.qwerte.manager.gui.chrome.WCreativeTabs;
import com.porter.qwerte.manager.gui.frame.WFrame;
import com.porter.qwerte.manager.gui.layout.StashLayout;
import com.porter.qwerte.manager.gui.layout.StashLayout.Layout;
import com.porter.qwerte.manager.gui.layout.StashLayout.R;
import com.porter.qwerte.manager.gui.style.Paint;
import com.porter.qwerte.manager.gui.style.Palette;
import com.porter.qwerte.manager.gui.style.SmGuiTheme;
import com.porter.qwerte.manager.gui.widgets.TextWrap;
import com.porter.qwerte.manager.gui.widgets.WItemGrid;
import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.WContainer;
import meteordevelopment.meteorclient.gui.widgets.containers.WView;
import meteordevelopment.meteorclient.gui.widgets.input.WDropdown;
import meteordevelopment.meteorclient.gui.widgets.input.WTextBox;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;

import java.util.List;

/**
 * The stash page, laid out like the website's: the creative tabs on top, then the inventory panel with its label, the
 * item grid across the whole panel, the search box and sort select, the summary and a hint (both in small text). The
 * details of a clicked item are a drawer laid over the right side of the grid; closed, it takes no room and the grid is
 * the same size either way. It takes all the room the window's page has, and gives the grid whatever is left, so the
 * columns and rows follow the window as it is resized. {@link StashLayout} does the arithmetic.
 */
public class WStashPage extends WContainer {
    public WCreativeTabs tabs;
    public WItemGrid grid;
    public WTextBox search;
    public WDropdown<?> sort;
    public WView details;
    public WDrawer drawer;
    public WButton closeButton;

    /** The panel's label, a summary (a long and a short form), and the hint under the toolbar. */
    public String label = "";
    public String summaryLong = "";
    public String summaryShort = "";
    public String hint = "";

    private Layout lay;
    private String summary = "";
    private List<String> hintRows = List.of();
    private boolean drawerOpen;

    private WFrame frame() {
        for (WWidget w = parent; w != null; w = w.parent) if (w instanceof WFrame f) return f;
        return null;
    }

    private int u() {
        return SmGuiTheme.unitOf(theme);
    }

    /** True once every child the layout needs is set (BrowseTab fills them in as it builds the page). */
    private boolean ready() {
        return tabs != null && grid != null && search != null && sort != null && details != null && drawer != null && closeButton != null
            && theme instanceof SmGuiTheme;
    }

    /** Shows or hides the details drawer. */
    public void setDrawerOpen(boolean open) {
        if (open == drawerOpen) return;
        drawerOpen = open;
        invalidate();
    }

    public boolean drawerOpen() {
        return drawerOpen;
    }

    @Override
    public void calculateSize() {
        if (!ready()) {
            // Not built yet: take no room and draw nothing until it is
            lay = null;
            width = 0;
            height = 0;
            return;
        }
        SmGuiTheme t = (SmGuiTheme) theme;
        int u = t.unit();
        WFrame f = frame();
        double pw = f != null ? f.pageW : 400 * u;
        double ph = f != null ? f.pageH : 240 * u;
        int wGui = Math.max(1, (int) (pw / u));
        int hGui = Math.max(1, (int) (ph / u));

        search.calculateSize();
        sort.calculateSize();
        int textH = (int) Math.round(t.textHeight() / u);
        int smallH = (int) Math.round(t.smallHeight() / u);
        int toolbarH = (int) Math.ceil(Math.max(search.height, sort.height) / u);
        int sortW = (int) Math.ceil(sort.width / u);

        lay = StashLayout.compute(wGui, hGui, textH, smallH, toolbarH, sortW, hint.isEmpty() ? 0 : 1);
        hintRows = hint.isEmpty() ? List.of() : TextWrap.wrap(hint, lay.hint().w() * u, t::smallWidth);
        if (hintRows.size() != 1 && !hint.isEmpty()) {
            lay = StashLayout.compute(wGui, hGui, textH, smallH, toolbarH, sortW, hintRows.size());
        }
        summary = t.smallWidth(summaryLong) <= lay.summary().w() * u ? summaryLong : summaryShort;

        tabs.setWidth(wGui * u);
        grid.setViewport(lay.grid().w(), lay.grid().h());

        tabs.calculateSize();
        grid.calculateSize();
        // the toolbar's widgets take the sizes the layout gave them
        search.calculateSize();
        search.width = lay.search().w() * u;
        search.height = lay.search().h() * u;
        sort.calculateSize();
        sort.width = lay.sort().w() * u;
        sort.height = lay.sort().h() * u;

        // the drawer: a title row with the close button, then the details
        R d = lay.drawer();
        closeButton.calculateSize();
        drawer.width = d.w() * u;
        drawer.height = d.h() * u;
        details.maxHeight = Math.max(u, d.h() * u - topRow(t) - 4 * u);
        details.calculateSize();
        details.width = Math.max(u, d.w() * u - 8 * u);

        width = wGui * u;
        // When the room is too short for everything, report the true height: the outer page then scrolls
        height = Math.max(hGui, lay.totalH()) * u;
    }

    private double topRow(SmGuiTheme t) {
        return Math.max(closeButton.height, t.smallHeight()) + 6 * t.unit();
    }

    private void place(WWidget w, R r, int u) {
        w.x = x + r.x() * u;
        w.y = y + r.y() * u;
    }

    @Override
    protected void onCalculateWidgetPositions() {
        if (lay == null || !ready()) return;
        int u = u();
        tabs.x = x;
        tabs.y = y;
        place(grid, lay.grid(), u);
        place(search, lay.search(), u);
        place(sort, lay.sort(), u);
        R d = lay.drawer();
        if (drawerOpen) {
            place(drawer, d, u);
            closeButton.x = x + (d.x() + d.w()) * u - closeButton.width - 3 * u;
            closeButton.y = y + d.y() * u + 3 * u;
            details.x = x + d.x() * u + 4 * u;
            details.y = y + d.y() * u + topRow((SmGuiTheme) theme);
            grid.setCover(new double[] {drawer.x, drawer.y, drawer.width, drawer.height});
        } else {
            // Closed: out of reach of the pointer, and drawn by nobody
            drawer.x = -100_000;
            drawer.y = -100_000;
            closeButton.x = -100_000;
            closeButton.y = -100_000;
            details.x = -100_000;
            details.y = -100_000;
            grid.setCover(null);
        }
        drawer.visible = drawerOpen;
        closeButton.visible = drawerOpen;
        details.visible = drawerOpen;
    }

    @Override
    protected void onRender(GuiRenderer renderer, double mouseX, double mouseY, double delta) {
        if (lay == null || !(theme instanceof SmGuiTheme t)) return;
        Palette p = t.palette();
        int u = t.unit();
        double bw = p.borderWidth * u;
        R panel = lay.panel();
        double px = x + panel.x() * u;
        double py = y + panel.y() * u;
        double pw = panel.w() * u;
        double ph = panel.h() * u;
        Paint.panel(renderer, t, px, py, pw, ph, p.radius * u, bw, p.surface(), p.border());
        if (p.bevel) Paint.bevel(renderer, t, px + bw, py + bw, pw - 2 * bw, ph - 2 * bw, u, 0xFFFFFFFF, 0xFF555555);

        R l = lay.label();
        renderer.text(label, x + l.x() * u, y + l.y() * u, t.color(p.text()), false);

        R s = lay.summary();
        renderer.text(summary, x + s.x() * u, y + s.y() * u, t.color(p.muted()), true);
        R h = lay.hint();
        double hy = y + h.y() * u;
        for (String row : hintRows) {
            renderer.text(row, x + h.x() * u, hy, t.color(p.muted()), true);
            hy += t.smallHeight();
        }
    }
}
