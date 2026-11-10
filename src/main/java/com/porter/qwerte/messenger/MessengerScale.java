package com.porter.qwerte.messenger;

/**
 * The text scale of the Messenger window. The whole window is drawn at this scale (a pose scale), so the layout, the
 * rows, badges, dots and emoji grow and shrink with the text. Pure: no Minecraft classes.
 */
public final class MessengerScale {
    public static final float MIN = 0.75f;
    public static final float MAX = 2.0f;
    /** The window size at which the scale is 1 (the default window). */
    public static final int DEFAULT_W = 330;
    public static final int DEFAULT_H = 190;

    private MessengerScale() {
    }

    public static float clamp(float s) {
        if (Float.isNaN(s)) return 1f;
        return Math.max(MIN, Math.min(MAX, s));
    }

    /** The scale that follows the window: its size against the default size (the tighter side), clamped. */
    public static float auto(int w, int h) {
        if (w <= 0 || h <= 0) return 1f;
        return clamp(Math.min(w / (float) DEFAULT_W, h / (float) DEFAULT_H));
    }

    /**
     * The scale to draw with, in steps of 0.05 (so a drag does not remake the wrapping at every pixel). Never so large
     * that the window's contents would have less room than the smallest window allows.
     *
     * @param auto  follow the window size
     * @param fixed the scale to use when {@code auto} is off
     */
    public static float effective(boolean auto, float fixed, int w, int h) {
        float s = auto ? auto(w, h) : clamp(fixed);
        float room = Math.min(w / (float) Geometry.MIN_W, h / (float) Geometry.MIN_H);
        s = Math.min(s, Math.max(1f, room));
        return Math.round(s * 20f) / 20f;
    }

    /** The size of the window's contents in logical (unscaled) pixels. */
    public static int logical(int real, float scale) {
        return Math.max(1, Math.round(real / scale));
    }
}
