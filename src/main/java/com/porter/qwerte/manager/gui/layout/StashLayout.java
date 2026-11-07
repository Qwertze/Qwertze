package com.porter.qwerte.manager.gui.layout;

/**
 * Where the parts of the Stash page go, in GUI pixels relative to the page: the creative tabs, the inventory panel with
 * its label, grid, toolbar, summary and hint. Like the website, the grid takes the panel's whole width; the details of a
 * clicked item are a drawer laid over the right side of the grid (it never changes the grid's size). The numbers are the
 * website's CSS pixels halved (a GUI pixel is two CSS pixels). Pure.
 */
public final class StashLayout {
    public static final int TAB_W = 26;
    public static final int TAB_H = 24;
    /** The selected tab is taller and joins the panel. */
    public static final int TAB_SELECTED_H = 29;
    public static final int PANEL_Y = 27;
    /** The panel's side and bottom padding (12 CSS px), and its top padding (16 CSS px). */
    public static final int PAD = 6;
    public static final int TOP_PAD = 8;
    /** The gap between the panel's rows (8 CSS px). */
    public static final int ROW_GAP = 4;
    /** The drawer is wide enough for a shulker box's nine-wide slot grid and its scroll bar. */
    public static final int DRAWER_W = 186;
    public static final int GRID_MIN_W = GridLayout.SLOT * 9 + 4;
    /** The least height the grid gets. */
    public static final int GRID_MIN_H = GridLayout.SLOT + 4;

    private StashLayout() {
    }

    public record R(int x, int y, int w, int h) {
        public boolean isEmpty() {
            return w <= 0 || h <= 0;
        }

        public boolean contains(double px, double py) {
            return px >= x && px < x + w && py >= y && py < y + h;
        }
    }

    /**
     * @param drawer the details drawer: the right side of the grid's rectangle, over it
     * @param totalH the height the page really needs: the room it was given, or more when the grid, toolbar, summary and
     *               hint can't all fit; the outer page scrolls then
     */
    public record Layout(R tabs, R panel, R label, R grid, R search, R sort, R summary, R hint, R drawer, int totalH) {
    }

    /**
     * @param textH     height of a line of normal text (the panel's label)
     * @param smallH    height of a line of small text (the summary and the hint)
     * @param toolbarH  height of the search box
     * @param sortW     width of the sort select
     * @param hintLines lines the hint under the grid takes (0 for none)
     */
    public static Layout compute(int pageW, int pageH, int textH, int smallH, int toolbarH, int sortW, int hintLines) {
        int w = Math.max(1, pageW);
        int cw = Math.max(1, w - 2 * PAD);
        int hintH = hintLines * smallH;
        int below = ROW_GAP + toolbarH + ROW_GAP + smallH + (hintLines > 0 ? ROW_GAP + hintH : 0);
        int needed = textH + ROW_GAP + GRID_MIN_H + below;
        int h = Math.max(Math.max(1, pageH), PANEL_Y + TOP_PAD + PAD + needed);
        R tabs = new R(0, 0, w, PANEL_Y + 2);
        R panel = new R(0, PANEL_Y, w, h - PANEL_Y);
        int cx = PAD;
        int cy = PANEL_Y + TOP_PAD;
        int ch = h - PANEL_Y - TOP_PAD - PAD;

        int gridH = ch - textH - ROW_GAP - below;
        int gridY = cy + textH + ROW_GAP;
        R label = new R(cx, cy, cw, textH);
        R grid = new R(cx, gridY, cw, gridH);
        int toolY = gridY + gridH + ROW_GAP;
        int sw = Math.min(sortW, Math.max(0, cw / 3));
        R sort = new R(cx + cw - sw, toolY, sw, toolbarH);
        R search = new R(cx, toolY, Math.max(1, cw - sw - (sw > 0 ? 4 : 0)), toolbarH);
        R summary = new R(cx, toolY + toolbarH + ROW_GAP, cw, smallH);
        R hint = new R(cx, summary.y() + smallH + ROW_GAP, cw, hintH);
        int dw = Math.min(DRAWER_W, Math.max(1, cw * 3 / 4));
        R drawer = new R(cx + cw - dw, gridY, dw, gridH);
        return new Layout(tabs, panel, label, grid, search, sort, summary, hint, drawer, h);
    }
}
