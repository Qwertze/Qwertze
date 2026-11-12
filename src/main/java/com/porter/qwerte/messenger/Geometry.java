package com.porter.qwerte.messenger;

/**
 * Where the Messenger window is, in GUI pixels, which screen edges it is snapped to and which side its list is on.
 * Saved with the module as {@code v2:x,y,w,h,anchors,side} (anchors: a number, the sum of {@link #LEFT}, {@link #TOP},
 * {@link #RIGHT} and {@link #BOTTOM}; side: L or R). The older {@code v1:x,y,w,h} still reads (no anchors, list on the
 * left). No Minecraft classes.
 */
public record Geometry(int x, int y, int w, int h, int anchors, ListSide side) {
    /** The smallest window: only so that the title bar and the resize grip can still be grabbed and the window grown back. */
    public static final int MIN_W = 24;
    public static final int MIN_H = 12;
    public static final int LEFT = 1;
    public static final int TOP = 2;
    public static final int RIGHT = 4;
    public static final int BOTTOM = 8;
    /** At least this much of the title bar stays on screen, so the window can always be grabbed. */
    private static final int KEEP = 40;
    private static final String V1 = "v1:";
    private static final String V2 = "v2:";
    private static final int LIMIT = 100_000;

    public Geometry {
        anchors &= LEFT | TOP | RIGHT | BOTTOM;
        if (side == null) side = ListSide.LEFT;
    }

    /** A window that is not snapped to anything, with its list on the left. */
    public Geometry(int x, int y, int w, int h) {
        this(x, y, w, h, 0, ListSide.LEFT);
    }

    public static Geometry initial(int screenW, int screenH) {
        return new Geometry(6, 6, 330, 190).clamp(screenW, screenH);
    }

    public boolean anchored(int edge) {
        return (anchors & edge) != 0;
    }

    public Geometry withSide(ListSide s) {
        return new Geometry(x, y, w, h, anchors, s);
    }

    public Geometry withAnchors(int a) {
        return new Geometry(x, y, w, h, a, side);
    }

    /** Size within the (tiny) minimum and the screen, position so that the title bar can be reached. Anchors are kept as they are. */
    public Geometry clamp(int screenW, int screenH) {
        int sw = Math.max(1, screenW);
        int sh = Math.max(1, screenH);
        int nw = Math.max(Math.min(MIN_W, sw), Math.min(w, sw));
        int nh = Math.max(Math.min(MIN_H, sh), Math.min(h, sh));
        int keep = Math.min(KEEP, nw);
        int nx = Math.max(keep - nw, Math.min(x, sw - keep));
        int ny = Math.max(0, Math.min(y, sh - Math.min(14, nh)));
        return new Geometry(nx, ny, nw, nh, anchors, side);
    }

    /**
     * For a new screen size or GUI scale: the size is clamped first, then every snapped edge stays on its screen edge
     * (right: x = screenW - w, bottom: y = screenH - h, left and top: 0). A window that is not snapped is clamped as it
     * always was. An anchor that cannot hold any more (left and right at once, but the window is no longer as wide as
     * the screen) is dropped.
     */
    public Geometry fit(int screenW, int screenH) {
        Geometry c = clamp(screenW, screenH);
        int sw = Math.max(1, screenW);
        int sh = Math.max(1, screenH);
        int a = c.anchors;
        if (both(a, LEFT, RIGHT) && c.w != sw) a &= ~RIGHT;
        if (both(a, TOP, BOTTOM) && c.h != sh) a &= ~BOTTOM;
        int nx = c.x;
        int ny = c.y;
        if ((a & LEFT) != 0) nx = 0;
        else if ((a & RIGHT) != 0) nx = sw - c.w;
        if ((a & TOP) != 0) ny = 0;
        else if ((a & BOTTOM) != 0) ny = sh - c.h;
        return new Geometry(nx, ny, c.w, c.h, a, c.side);
    }

    private static boolean both(int a, int e1, int e2) {
        return (a & e1) != 0 && (a & e2) != 0;
    }

    public boolean contains(double px, double py) {
        return px >= x && px < x + w && py >= y && py < y + h;
    }

    public String encode() {
        return V2 + x + "," + y + "," + w + "," + h + "," + anchors + "," + side.code();
    }

    /** The saved geometry, or null for anything that is not a well-formed {@link #encode()} (or version 1) string. */
    public static Geometry decode(String s) {
        if (s == null) return null;
        boolean v2 = s.startsWith(V2);
        if (!v2 && !s.startsWith(V1)) return null;
        String[] p = s.substring(3).split(",", -1);
        if (p.length != (v2 ? 6 : 4)) return null;
        try {
            int x = Integer.parseInt(p[0].trim());
            int y = Integer.parseInt(p[1].trim());
            int w = Integer.parseInt(p[2].trim());
            int h = Integer.parseInt(p[3].trim());
            if (w <= 0 || h <= 0 || w > LIMIT || h > LIMIT || Math.abs(x) > LIMIT || Math.abs(y) > LIMIT) return null;
            if (!v2) return new Geometry(x, y, w, h);
            int anchors = Integer.parseInt(p[4].trim());
            if (anchors < 0 || anchors > (LEFT | TOP | RIGHT | BOTTOM)) return null;
            ListSide side = ListSide.fromCode(p[5].trim());
            if (side == null) return null;
            return new Geometry(x, y, w, h, anchors, side);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
