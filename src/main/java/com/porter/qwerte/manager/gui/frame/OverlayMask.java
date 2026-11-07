package com.porter.qwerte.manager.gui.frame;

/**
 * The rectangles the window's overlay (an open list, the hover card) covers. Item icons are drawn above everything
 * Meteor draws, so every icon asks {@link #blocks} and is skipped when the overlay is on top of it. Pure.
 */
public final class OverlayMask {
    public static final int POPUP = 0;
    public static final int HOVER = 1;
    /** A Meteor tooltip (from the previous frame: it is drawn after the icons). */
    public static final int TOOLTIP = 2;
    private static final int SLOTS = 3;

    private final double[][] rects = new double[SLOTS][];

    /** Sets the rectangle of one slot. */
    public void set(int slot, double x, double y, double w, double h) {
        rects[slot] = new double[] {x, y, w, h};
    }

    public void clear(int slot) {
        rects[slot] = null;
    }

    public void clearAll() {
        for (int i = 0; i < SLOTS; i++) rects[i] = null;
    }

    public boolean isEmpty() {
        for (double[] r : rects) if (r != null) return false;
        return true;
    }

    /** True when any covered rectangle overlaps {@code (x, y, w, h)}; rectangles that only touch don't block. */
    public boolean blocks(double x, double y, double w, double h) {
        for (double[] r : rects) {
            if (r == null) continue;
            if (x < r[0] + r[2] && x + w > r[0] && y < r[1] + r[3] && y + h > r[1]) return true;
        }
        return false;
    }

    /** True when the point is inside a covered rectangle. */
    public boolean covers(double px, double py) {
        for (double[] r : rects) {
            if (r != null && px >= r[0] && px < r[0] + r[2] && py >= r[1] && py < r[1] + r[3]) return true;
        }
        return false;
    }
}
