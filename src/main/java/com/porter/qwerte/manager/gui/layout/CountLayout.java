package com.porter.qwerte.manager.gui.layout;

/**
 * How big a stack count is drawn inside an item slot: small, like the game's own stack numbers, always a whole
 * multiple of the font's pixel size, and never wider than the slot. Pure.
 */
public final class CountLayout {
    /** The widest count Text.compact makes in practice ("999M", "999k"), in font units. */
    public static final int W_MAX = 24;

    private CountLayout() {
    }

    /**
     * The integer scale (physical pixels per font pixel) used for every count at a unit {@code u} (physical pixels per
     * GUI pixel), for a slot whose inner width is {@code innerPx} physical pixels.
     */
    public static int scale(int u, int innerPx) {
        int byUnit = (int) Math.floor(0.75 * Math.max(1, u));
        int byWidth = innerPx / W_MAX;
        return Math.max(1, Math.min(byUnit, byWidth));
    }

    /**
     * The scale to draw one count at: {@code scale}, or smaller (fractional) only when that string would still be
     * wider than the slot.
     */
    public static double fit(int scale, int textUnits, int innerPx) {
        if (textUnits <= 0) return scale;
        if ((long) textUnits * scale <= innerPx) return scale;
        return (double) innerPx / textUnits;
    }

    /** The width of a count in the game's default font, in font units (digits 6, the dot 2, k 5, M and B 6). */
    public static int textUnits(String s) {
        int w = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') w += 6;
            else if (c == '.' || c == ',') w += 2;
            else if (c == 'k') w += 5;
            else w += 6;
        }
        return w;
    }
}
