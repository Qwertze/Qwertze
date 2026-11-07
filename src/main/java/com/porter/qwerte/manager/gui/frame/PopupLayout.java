package com.porter.qwerte.manager.gui.frame;

/**
 * Where an open list goes and which row is under the pointer. Pure: all numbers are in one unit (pixels).
 */
public final class PopupLayout {
    public static final int MAX_ROWS = 12;

    private PopupLayout() {
    }

    /**
     * @param x       left edge
     * @param y       top edge
     * @param w       width
     * @param h       height of the visible rows
     * @param visible how many rows are shown at once
     * @param flipped true when the list opens above its anchor
     */
    public record Placement(double x, double y, double w, double h, int visible, boolean flipped) {
        public boolean inside(double px, double py) {
            return px >= x && px < x + w && py >= y && py < y + h;
        }
    }

    /**
     * The list for an anchor (the select that was clicked): below it, or above it when there is no room, never wider
     * than the screen, shifted left when it would leave the screen, at most {@link #MAX_ROWS} rows (it scrolls past that).
     */
    public static Placement place(double ax, double ay, double aw, double ah, double rowW, double rowH, int rows,
                                  double screenW, double screenH) {
        double w = Math.min(Math.max(aw, rowW), Math.max(1, screenW));
        int want = Math.max(1, Math.min(rows, MAX_ROWS));
        double below = screenH - (ay + ah);
        double above = ay;
        boolean flipped;
        int visible = want;
        if (want * rowH <= below) {
            flipped = false;
        } else if (want * rowH <= above) {
            flipped = true;
        } else {
            flipped = above > below;
            double room = flipped ? above : below;
            visible = Math.max(1, Math.min(want, (int) Math.floor(room / rowH)));
        }
        double h = visible * rowH;
        double x = Math.max(0, Math.min(ax, screenW - w));
        double y = flipped ? ay - h : ay + ah;
        return new Placement(x, y, w, h, visible, flipped);
    }

    public static int maxScroll(int rows, int visible) {
        return Math.max(0, rows - visible);
    }

    public static int clampScroll(int scroll, int rows, int visible) {
        return Math.max(0, Math.min(scroll, maxScroll(rows, visible)));
    }

    /** The row index under the pointer for a list scrolled by {@code scroll} rows, or -1 outside the list. */
    public static int rowAt(Placement p, double rowH, int scroll, int rows, double px, double py) {
        if (!p.inside(px, py)) return -1;
        int row = scroll + (int) Math.floor((py - p.y()) / rowH);
        return row >= 0 && row < rows ? row : -1;
    }

    /** A scroll that brings row {@code row} into view. */
    public static int scrollTo(int row, int scroll, int visible) {
        if (row < scroll) return row;
        if (row >= scroll + visible) return row - visible + 1;
        return scroll;
    }
}
