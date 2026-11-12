package com.porter.qwerte.messenger;

/**
 * The list-side setting. Auto picks the side from where the window sits, with a gap between the two thresholds so a
 * window near the middle does not flip back and forth. Pure: no Minecraft classes.
 */
public enum ListSideMode {
    AUTO("Auto"), LEFT("Left"), RIGHT("Right");

    /** Auto goes to the right only when the window's centre is further right than this share of the screen width... */
    public static final int RIGHT_ABOVE_PERCENT = 55;
    /** ...and back to the left only when it is further left than this one. In between the current side stays. */
    public static final int LEFT_BELOW_PERCENT = 45;

    private final String label;

    ListSideMode(String label) {
        this.label = label;
    }

    @Override
    public String toString() {
        return label;
    }

    /**
     * The side to use.
     *
     * @param current the side now (kept in the gap between the thresholds)
     * @param x       the window's left edge, in GUI pixels
     * @param w       the window's width
     * @param screenW the screen width
     */
    public ListSide resolve(ListSide current, int x, int w, int screenW) {
        if (this == LEFT) return ListSide.LEFT;
        if (this == RIGHT) return ListSide.RIGHT;
        ListSide cur = current == null ? ListSide.LEFT : current;
        if (screenW <= 0) return cur;
        long twiceCentre = 2L * x + w; // twice the centre, so no rounding
        if (twiceCentre * 100 > 2L * RIGHT_ABOVE_PERCENT * screenW) return ListSide.RIGHT;
        if (twiceCentre * 100 < 2L * LEFT_BELOW_PERCENT * screenW) return ListSide.LEFT;
        return cur;
    }
}
