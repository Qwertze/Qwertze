package com.porter.qwerte.manager.gui.frame;

/**
 * Where the Stash Window is, in GUI pixels (one GUI pixel is the window's unit: the Minecraft GUI scale, or the
 * window-scale setting). {@code x, y, w, h} are the restored rectangle; {@code maximized} fills the screen instead.
 * Pure: no Minecraft or Meteor classes.
 */
public record WindowGeometry(int x, int y, int w, int h, boolean maximized) {
    public static final int MIN_W = 240;
    /**
     * A safe constant, not computed: the header (about three wrapped rows) plus the smallest stash page. When a window is
     * still too short for a page (a very wrapped header), the page scrolls.
     */
    public static final int MIN_H = 230;
    /** The website's .layout max-width of 1400 CSS px, in GUI pixels. */
    public static final int DEFAULT_MAX_W = 700;
    private static final String VERSION = "v1:";
    private static final int LIMIT = 100_000;

    /** 80% of the screen, at most {@link #DEFAULT_MAX_W} wide, centred. */
    public static WindowGeometry initial(int screenW, int screenH) {
        int sw = Math.max(1, screenW);
        int sh = Math.max(1, screenH);
        int w = Math.min(DEFAULT_MAX_W, (int) Math.round(sw * 0.8));
        int h = (int) Math.round(sh * 0.8);
        return FrameMath.clamp(new WindowGeometry((sw - w) / 2, (sh - h) / 2, w, h, false), sw, sh);
    }

    /** The rectangle to use now: the whole screen when maximized. */
    public WindowGeometry effective(int screenW, int screenH) {
        if (maximized) return new WindowGeometry(0, 0, Math.max(1, screenW), Math.max(1, screenH), true);
        return this;
    }

    public WindowGeometry withMaximized(boolean max) {
        return new WindowGeometry(x, y, w, h, max);
    }

    /** {@code v1:x,y,w,h,max}. */
    public String encode() {
        return VERSION + x + "," + y + "," + w + "," + h + "," + (maximized ? 1 : 0);
    }

    /** The saved geometry, or null for anything that isn't a well-formed {@link #encode()} string. */
    public static WindowGeometry decode(String s) {
        if (s == null || !s.startsWith(VERSION)) return null;
        String[] p = s.substring(VERSION.length()).split(",", -1);
        if (p.length != 5) return null;
        try {
            int x = Integer.parseInt(p[0].trim());
            int y = Integer.parseInt(p[1].trim());
            int w = Integer.parseInt(p[2].trim());
            int h = Integer.parseInt(p[3].trim());
            int m = Integer.parseInt(p[4].trim());
            if (w <= 0 || h <= 0 || w > LIMIT || h > LIMIT || Math.abs(x) > LIMIT || Math.abs(y) > LIMIT || (m != 0 && m != 1)) return null;
            return new WindowGeometry(x, y, w, h, m == 1);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
