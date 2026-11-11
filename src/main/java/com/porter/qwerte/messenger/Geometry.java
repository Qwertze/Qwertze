package com.porter.qwerte.messenger;

/**
 * Where the Messenger window is, in GUI pixels. Saved with the module as {@code v1:x,y,w,h}. No Minecraft classes.
 */
public record Geometry(int x, int y, int w, int h) {
    /** The smallest window: only so that the title bar and the resize grip can still be grabbed and the window grown back. */
    public static final int MIN_W = 24;
    public static final int MIN_H = 12;
    /** At least this much of the title bar stays on screen, so the window can always be grabbed. */
    private static final int KEEP = 40;
    private static final String VERSION = "v1:";
    private static final int LIMIT = 100_000;

    public static Geometry initial(int screenW, int screenH) {
        return new Geometry(6, 6, 330, 190).clamp(screenW, screenH);
    }

    /** Size within the (tiny) minimum and the screen, position so that the title bar can be reached. */
    public Geometry clamp(int screenW, int screenH) {
        int sw = Math.max(1, screenW);
        int sh = Math.max(1, screenH);
        int nw = Math.max(Math.min(MIN_W, sw), Math.min(w, sw));
        int nh = Math.max(Math.min(MIN_H, sh), Math.min(h, sh));
        int keep = Math.min(KEEP, nw);
        int nx = Math.max(keep - nw, Math.min(x, sw - keep));
        int ny = Math.max(0, Math.min(y, sh - Math.min(14, nh)));
        return new Geometry(nx, ny, nw, nh);
    }

    public boolean contains(double px, double py) {
        return px >= x && px < x + w && py >= y && py < y + h;
    }

    public String encode() {
        return VERSION + x + "," + y + "," + w + "," + h;
    }

    /** The saved geometry, or null for anything that is not a well-formed {@link #encode()} string. */
    public static Geometry decode(String s) {
        if (s == null || !s.startsWith(VERSION)) return null;
        String[] p = s.substring(VERSION.length()).split(",", -1);
        if (p.length != 4) return null;
        try {
            int x = Integer.parseInt(p[0].trim());
            int y = Integer.parseInt(p[1].trim());
            int w = Integer.parseInt(p[2].trim());
            int h = Integer.parseInt(p[3].trim());
            if (w <= 0 || h <= 0 || w > LIMIT || h > LIMIT || Math.abs(x) > LIMIT || Math.abs(y) > LIMIT) return null;
            return new Geometry(x, y, w, h);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
