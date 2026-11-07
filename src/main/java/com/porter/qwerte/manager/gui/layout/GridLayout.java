package com.porter.qwerte.manager.gui.layout;

/**
 * Columns, rows, centring, scrolling and hit-testing of the item grid, in GUI pixels (one slot is 18). Pure.
 */
public final class GridLayout {
    public static final int SLOT = 18;

    private GridLayout() {
    }

    /** How many whole slots fit across {@code width} with {@code pad} on both sides; at least 1. */
    public static int cols(int width, int pad) {
        return Math.max(1, (width - 2 * pad) / SLOT);
    }

    public static int rows(int height, int pad) {
        return Math.max(1, (height - 2 * pad) / SLOT);
    }

    /** Left edge of the first slot: the slots are centred in the space, like the website's justify-content: center. */
    public static int offsetX(int width, int pad, int cols) {
        int free = width - 2 * pad - cols * SLOT;
        return pad + Math.max(0, free / 2);
    }

    public static int totalRows(int count, int cols) {
        return (Math.max(0, count) + cols - 1) / Math.max(1, cols);
    }

    public static int maxScroll(int count, int cols, int rows) {
        return Math.max(0, totalRows(count, cols) - rows);
    }

    public static int clampScroll(int scroll, int count, int cols, int rows) {
        return Math.max(0, Math.min(scroll, maxScroll(count, cols, rows)));
    }

    /**
     * The cell index under a point given relative to the grid's top-left corner, or -1 (outside the slots, on a gap or
     * past the last cell).
     */
    public static int hit(int count, int cols, int rows, int scroll, int offX, int offY, double lx, double ly) {
        double sx = lx - offX;
        double sy = ly - offY;
        if (sx < 0 || sy < 0) return -1;
        int c = (int) (sx / SLOT);
        int r = (int) (sy / SLOT);
        if (c >= cols || r >= rows) return -1;
        int index = (scroll + r) * cols + c;
        return index >= 0 && index < count ? index : -1;
    }

    /**
     * The scroll to use after the number of columns changed: the first visible item stays visible (it is at the top row
     * of the new layout when the scroll allows).
     */
    public static int scrollAfterReflow(int oldCols, int oldScroll, int newCols, int count, int newRows) {
        long first = (long) oldScroll * Math.max(1, oldCols);
        int scroll = (int) (first / Math.max(1, newCols));
        return clampScroll(scroll, count, newCols, newRows);
    }

    // ---------- the scroll bar (physical pixels) ----------

    /** Height of the thumb: the share of the rows that are visible, never less than {@code minH}, never more than the track. */
    public static double thumbH(double trackH, int rows, int totalRows, double minH) {
        double h = trackH * rows / Math.max(1, totalRows);
        return Math.min(trackH, Math.max(Math.min(minH, trackH), h));
    }

    /** Top of the thumb for a scroll (in rows) out of {@code maxScroll}. */
    public static double thumbY(double trackY, double trackH, double thumbH, int scroll, int maxScroll) {
        return trackY + (trackH - thumbH) * (maxScroll <= 0 ? 0 : (double) scroll / maxScroll);
    }

    /** The scroll (in rows) that puts the thumb's centre at {@code pointerY}. */
    public static int scrollFromPointer(double pointerY, double trackY, double trackH, double thumbH, int maxScroll) {
        double free = trackH - thumbH;
        if (free <= 0 || maxScroll <= 0) return 0;
        double frac = Math.max(0, Math.min(1, (pointerY - trackY - thumbH / 2) / free));
        return (int) Math.round(frac * maxScroll);
    }

    /** True when the point is in the bar's grab zone: {@code barX} to {@code rightEdge} across, the track's height down. */
    public static boolean inBar(double px, double py, double barX, double rightEdge, double trackY, double trackH) {
        return px >= barX && px <= rightEdge && py >= trackY && py <= trackY + trackH;
    }
}
