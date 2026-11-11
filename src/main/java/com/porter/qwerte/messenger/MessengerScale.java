package com.porter.qwerte.messenger;

/**
 * The text scale of the Messenger window. The whole window is drawn at this scale (a pose scale), so the layout, the
 * rows, badges, dots and emoji grow and shrink with the text. Pure: no Minecraft classes.
 *
 * <p>The window has no minimum size (see {@link Geometry}) and the scale has only a very low floor, so the text can
 * shrink with the window as far as the user wants; {@link MessengerLayout} drops parts of the window that no longer fit.
 */
public final class MessengerScale {
    public static final float MIN = 0.1f;
    public static final float MAX = 2.0f;
    /** The window size at which the scale is 1 (the default window). */
    public static final int DEFAULT_W = 330;
    public static final int DEFAULT_H = 190;
    /** The least room (logical pixels) the contents are drawn in: a header and a grip. */
    public static final int LOGICAL_MIN_W = 24;
    public static final int LOGICAL_MIN_H = 16;

    private MessengerScale() {
    }

    public static float clamp(float s) {
        if (Float.isNaN(s)) return 1f;
        return Math.max(MIN, Math.min(MAX, s));
    }

    /**
     * The scale that follows the window: its size against the default size (the geometric mean of the width and
     * height ratios, so a tall narrow window keeps readable text and the list collapses instead), clamped.
     */
    public static float auto(int w, int h) {
        if (w <= 0 || h <= 0) return 1f;
        double ratio = Math.sqrt((w / (double) DEFAULT_W) * (h / (double) DEFAULT_H));
        return clamp((float) ratio);
    }

    /**
     * The scale to draw with, in steps of 0.05 (0.01 below 0.5, where a step is a large part of the scale), so a drag
     * does not remake the wrapping at every pixel.
     *
     * @param auto  follow the window size
     * @param fixed the scale to use when {@code auto} is off
     */
    public static float effective(boolean auto, float fixed, int w, int h) {
        float s = auto ? auto(w, h) : clamp(fixed);
        // The contents keep at least a header and a grip of room, so a large fixed scale can not push them out of a small window
        float room = Math.max(MIN, Math.min(w / (float) LOGICAL_MIN_W, h / (float) LOGICAL_MIN_H));
        s = Math.min(s, room);
        float steps = s < 0.5f ? 100f : 20f;
        return clamp(Math.round(s * steps) / steps);
    }

    /** The size of the window's contents in logical (unscaled) pixels. */
    public static int logical(int real, float scale) {
        return Math.max(1, Math.round(real / scale));
    }
}
