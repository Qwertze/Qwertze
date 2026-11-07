package com.porter.qwerte.manager.gui.frame;

/**
 * The arithmetic of moving and resizing the window, in GUI pixels. Pure: no Minecraft or Meteor classes.
 */
public final class FrameMath {
    private FrameMath() {
    }

    /** Which edge or corner of the window a point is on. */
    public enum Edge {
        NONE, LEFT, RIGHT, TOP, BOTTOM, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT;

        public boolean left() {
            return this == LEFT || this == TOP_LEFT || this == BOTTOM_LEFT;
        }

        public boolean right() {
            return this == RIGHT || this == TOP_RIGHT || this == BOTTOM_RIGHT;
        }

        public boolean top() {
            return this == TOP || this == TOP_LEFT || this == TOP_RIGHT;
        }

        public boolean bottom() {
            return this == BOTTOM || this == BOTTOM_LEFT || this == BOTTOM_RIGHT;
        }
    }

    /** The grab ring: this wide on the edges, and this big at the corners. */
    public static final double RING = 3;
    public static final double CORNER = 8;
    /** The bottom-right corner is a whole square this big, where the visible grip is drawn. */
    public static final double GRIP = 10;

    /** The edge or corner under ({@code px, py}) for a window at {@code g}, or NONE (also when outside the window). */
    public static Edge edgeAt(WindowGeometry g, double px, double py) {
        double x0 = g.x();
        double y0 = g.y();
        double x1 = x0 + g.w();
        double y1 = y0 + g.h();
        if (px < x0 || px >= x1 || py < y0 || py >= y1) return Edge.NONE;
        // The grip in the bottom-right corner is a solid square: it is drawn there, so all of it can be grabbed
        if (px >= x1 - GRIP && py >= y1 - GRIP) return Edge.BOTTOM_RIGHT;
        boolean l = px < x0 + RING;
        boolean r = px >= x1 - RING;
        boolean t = py < y0 + RING;
        boolean b = py >= y1 - RING;
        boolean cl = px < x0 + CORNER;
        boolean cr = px >= x1 - CORNER;
        boolean ct = py < y0 + CORNER;
        boolean cb = py >= y1 - CORNER;
        // A corner is the 8x8 square, but only where the pointer is on the ring of one of its two edges, so the
        // corner doesn't eat the window's content
        if ((l || t) && cl && ct) return Edge.TOP_LEFT;
        if ((r || t) && cr && ct) return Edge.TOP_RIGHT;
        if ((l || b) && cl && cb) return Edge.BOTTOM_LEFT;
        if ((r || b) && cr && cb) return Edge.BOTTOM_RIGHT;
        if (l) return Edge.LEFT;
        if (r) return Edge.RIGHT;
        if (t) return Edge.TOP;
        if (b) return Edge.BOTTOM;
        return Edge.NONE;
    }

    /** The smallest size allowed on this screen: the minimum, or the whole screen where that is smaller. */
    public static int minW(int screenW) {
        return Math.min(WindowGeometry.MIN_W, Math.max(1, screenW));
    }

    public static int minH(int screenH) {
        return Math.min(WindowGeometry.MIN_H, Math.max(1, screenH));
    }

    /** Brings a rectangle onto the screen: not smaller than the minimum, not bigger than the screen, inside it. */
    public static WindowGeometry clamp(WindowGeometry g, int screenW, int screenH) {
        int sw = Math.max(1, screenW);
        int sh = Math.max(1, screenH);
        int w = Math.max(minW(sw), Math.min(g.w(), sw));
        int h = Math.max(minH(sh), Math.min(g.h(), sh));
        int x = Math.max(0, Math.min(g.x(), sw - w));
        int y = Math.max(0, Math.min(g.y(), sh - h));
        return new WindowGeometry(x, y, w, h, g.maximized());
    }

    /**
     * The rectangle after dragging {@code edge} of {@code start} by ({@code dx, dy}) GUI pixels. The opposite edges stay
     * where they are; the result is not smaller than the minimum and stays on the screen.
     */
    public static WindowGeometry resize(WindowGeometry start, Edge edge, int dx, int dy, int screenW, int screenH) {
        int sw = Math.max(1, screenW);
        int sh = Math.max(1, screenH);
        int minW = minW(sw);
        int minH = minH(sh);
        int left = start.x();
        int top = start.y();
        int right = start.x() + start.w();
        int bottom = start.y() + start.h();
        if (edge.left()) left = Math.max(0, Math.min(left + dx, right - minW));
        if (edge.right()) right = Math.min(sw, Math.max(right + dx, left + minW));
        if (edge.top()) top = Math.max(0, Math.min(top + dy, bottom - minH));
        if (edge.bottom()) bottom = Math.min(sh, Math.max(bottom + dy, top + minH));
        return new WindowGeometry(left, top, right - left, bottom - top, false);
    }

    /** The window moved by ({@code dx, dy}); it can't leave the screen, so its header is always reachable. */
    public static WindowGeometry move(WindowGeometry g, int dx, int dy, int screenW, int screenH) {
        return clamp(new WindowGeometry(g.x() + dx, g.y() + dy, g.w(), g.h(), g.maximized()), screenW, screenH);
    }

    /** Maximized becomes restored and the other way round; the restored rectangle is kept. */
    public static WindowGeometry toggleMaximized(WindowGeometry g) {
        return g.withMaximized(!g.maximized());
    }

    /**
     * Turns the pointer's movement in physical pixels into whole GUI pixels without drift: the total is converted with
     * rounding, and only the change of that is returned.
     */
    public static final class Accumulator {
        private final int unit;
        private long total;
        private long given;

        public Accumulator(int unit) {
            this.unit = Math.max(1, unit);
        }

        /** Adds a movement of {@code physical} pixels; returns the GUI pixels to apply now. */
        public int feed(double physical) {
            total += Math.round(physical);
            long gui = Math.round((double) total / unit);
            int out = (int) (gui - given);
            given = gui;
            return out;
        }
    }
}
