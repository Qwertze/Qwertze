package com.porter.qwerte.messenger;

/**
 * Snapping the Messenger window to the screen edges while it is dragged or resized. Pure: no Minecraft classes.
 *
 * <p>The rule is the same for every edge and every drag step, and it looks only at where the mouse would put the window
 * (the "raw" position), never at where the window is now: an edge within {@code dist} of the screen edge is locked
 * flush to it, and it stays locked while the raw position is still within {@code dist}, so the window slides freely
 * along that edge (the other axis is separate). Once the raw position is more than {@code dist} away, it is free again.
 * Two snapped edges at right angles make a corner.
 */
public final class EdgeSnap {
    private EdgeSnap() {
    }

    /**
     * A moved window.
     *
     * @param g       the window (its size and its list side are kept)
     * @param rawX    where the mouse would put its left edge
     * @param rawY    where the mouse would put its top edge
     * @param enabled snap-to-edges; off: no anchors, only the usual clamp
     */
    public static Geometry move(Geometry g, int rawX, int rawY, int sw, int sh, boolean enabled, int dist) {
        if (!enabled) return new Geometry(rawX, rawY, g.w(), g.h(), 0, g.side()).clamp(sw, sh);
        int[] ax = axis(rawX, g.w(), sw, dist);
        int[] ay = axis(rawY, g.h(), sh, dist);
        int anchors = (ax[1] != 0 ? Geometry.LEFT : 0) | (ax[2] != 0 ? Geometry.RIGHT : 0)
            | (ay[1] != 0 ? Geometry.TOP : 0) | (ay[2] != 0 ? Geometry.BOTTOM : 0);
        return new Geometry(ax[0], ay[0], g.w(), g.h(), anchors, g.side()).fit(sw, sh);
    }

    /**
     * A resized window (the grip is the bottom right corner): the right and bottom edges snap, the left and top ones stay.
     *
     * @param rawRight  where the mouse would put the right edge
     * @param rawBottom where the mouse would put the bottom edge
     */
    public static Geometry resize(Geometry g, int rawRight, int rawBottom, int sw, int sh, boolean enabled, int dist) {
        int nw = Math.max(Geometry.MIN_W, rawRight - g.x());
        int nh = Math.max(Geometry.MIN_H, rawBottom - g.y());
        int anchors = enabled ? g.anchors() & (Geometry.LEFT | Geometry.TOP) : 0;
        int d = Math.max(0, dist);
        if (enabled) {
            if (Math.abs(rawRight - sw) <= d && sw - g.x() >= Geometry.MIN_W) {
                nw = sw - g.x();
                anchors |= Geometry.RIGHT;
            }
            if (Math.abs(rawBottom - sh) <= d && sh - g.y() >= Geometry.MIN_H) {
                nh = sh - g.y();
                anchors |= Geometry.BOTTOM;
            }
        }
        return new Geometry(g.x(), g.y(), nw, nh, anchors, g.side()).fit(sw, sh);
    }

    /** One axis: {position, low edge snapped (0/1), high edge snapped (0/1)}. */
    private static int[] axis(int raw, int size, int screen, int dist) {
        int d = Math.max(0, dist);
        int lowGap = Math.abs(raw);
        int highGap = Math.abs(raw + size - screen);
        boolean low = lowGap <= d;
        boolean high = highGap <= d;
        if (low && high && size < screen) { // both edges are close, which only happens in a window almost as wide as the screen: the nearer one
            if (lowGap <= highGap) high = false;
            else low = false;
        }
        if (low) return new int[] {0, 1, high ? 1 : 0};
        if (high) return new int[] {screen - size, 0, 1};
        return new int[] {raw, 0, 0};
    }
}
