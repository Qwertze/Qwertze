package com.porter.qwerte.messenger;

/**
 * A rounded rectangle as a few plain rectangles ("spans"), so it can be drawn with {@code fill} only. The spans never
 * overlap, so a translucent colour has the same alpha everywhere in the shape, and nothing is drawn outside it. Each
 * corner has its own radius (0 is square), and a shape can be clipped to a rectangle, so a panel or row inside a window
 * with rounded corners can be cut to the window's shape. Allocation free. Pure: no Minecraft classes. Also meant for
 * message bubbles later.
 */
public final class RoundRect {
    /** Receives one span: x1, y1 inclusive, x2, y2 exclusive. */
    public interface Sink {
        void fill(int x1, int y1, int x2, int y2);
    }

    private RoundRect() {
    }

    /** All four corners with the same radius. */
    public static void spans(int x1, int y1, int x2, int y2, int radius, Sink sink) {
        spans(x1, y1, x2, y2, radius, radius, radius, radius, sink);
    }

    /**
     * @param tl radius of the top left corner, then top right, bottom right, bottom left; each is cut to half of the
     *           shorter side
     */
    public static void spans(int x1, int y1, int x2, int y2, int tl, int tr, int br, int bl, Sink sink) {
        core(x1, y1, x2, y2, tl, tr, br, bl, x1, y1, x2, y2, sink);
    }

    /**
     * The part of a rounded rectangle (all corners with the same radius) that lies inside a clip rectangle: for a panel
     * or row of a window, so it fills only what the window's rounded shape has and never paints over the window's corners,
     * even when it is lower than the radius. The spans still never overlap.
     */
    public static void spansIn(int x1, int y1, int x2, int y2, int radius, int cx1, int cy1, int cx2, int cy2, Sink sink) {
        core(x1, y1, x2, y2, radius, radius, radius, radius, cx1, cy1, cx2, cy2, sink);
    }

    private static void core(int x1, int y1, int x2, int y2, int tl, int tr, int br, int bl, int cx1, int cy1, int cx2, int cy2, Sink sink) {
        int w = x2 - x1;
        int h = y2 - y1;
        if (w <= 0 || h <= 0) return;
        int cap = Math.min(w, h) / 2;
        tl = Math.max(0, Math.min(tl, cap));
        tr = Math.max(0, Math.min(tr, cap));
        br = Math.max(0, Math.min(br, cap));
        bl = Math.max(0, Math.min(bl, cap));
        int topEnd = y1 + Math.max(tl, tr); // rows above this are cut at the top corners
        int bottomStart = y2 - Math.max(bl, br); // rows from here on are cut at the bottom corners
        int ys = Math.max(y1, cy1);
        int ye = Math.min(y2, cy2);
        // The span being built: rows are joined while they are cut in by the same amount
        int px1 = 0;
        int px2 = 0;
        int py1 = 0;
        int py2 = -1;
        for (int y = ys; y < ye; ) {
            int l;
            int r;
            int next;
            if (y < topEnd) {
                l = inset(tl, y - y1);
                r = inset(tr, y - y1);
                next = y + 1;
            } else if (y >= bottomStart) {
                l = inset(bl, y2 - 1 - y);
                r = inset(br, y2 - 1 - y);
                next = y + 1;
            } else {
                l = 0;
                r = 0;
                next = Math.min(ye, bottomStart); // all the straight rows at once
            }
            int a = Math.max(cx1, x1 + l);
            int b = Math.min(cx2, x2 - r);
            if (b > a) {
                if (py2 == y && px1 == a && px2 == b) {
                    py2 = next;
                } else {
                    if (py2 > py1) sink.fill(px1, py1, px2, py2);
                    px1 = a;
                    px2 = b;
                    py1 = y;
                    py2 = next;
                }
            }
            y = next;
        }
        if (py2 > py1) sink.fill(px1, py1, px2, py2);
    }

    /** How far a row is cut in at each end: row {@code i} counted from the edge of a corner with this radius. */
    static int inset(int radius, int i) {
        if (i >= radius) return 0;
        int d = radius - i;
        return radius - (int) Math.floor(Math.sqrt((double) radius * radius - (double) d * d));
    }
}
