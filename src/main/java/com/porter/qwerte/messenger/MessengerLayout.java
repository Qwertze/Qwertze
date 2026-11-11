package com.porter.qwerte.messenger;

/**
 * Which parts of the Messenger window fit, from its size in logical pixels (the real size divided by the text scale).
 * Pure: no Minecraft classes. The window never refuses to get small; instead parts drop out, in this order:
 * <ol>
 *   <li>the header buttons (New, Emoji, Delete) below {@link #BUTTONS_MIN_W};</li>
 *   <li>the conversation list on the left collapses below {@link #SIDEBAR_MIN_W}; a toggle in the header then switches
 *       the content between the messages and the list;</li>
 *   <li>below {@link #BOTH_MIN_H} only one of the messages and the input line is shown: the input while the window is
 *       focused (typing), the messages otherwise.</li>
 * </ol>
 * The header (title, grab area) and the resize grip are always there.
 */
public final class MessengerLayout {
    /** Narrower than this, the list is not drawn beside the messages. */
    public static final int SIDEBAR_MIN_W = 190;
    /** Narrower than this, the header has no buttons. */
    public static final int BUTTONS_MIN_W = 140;
    /** Lower than this, messages and input line do not both fit. */
    public static final int BOTH_MIN_H = 60;

    /** The least size, in real GUI pixels, of what the mouse must be able to hit (the title bar, the resize grip). */
    public static final int MIN_HIT_PX = 8;

    private MessengerLayout() {
    }

    /** Above this scale the title bar is hit only where it is drawn (plus a small margin); below it, over {@link #hitSize}. */
    public static final float GRAB_SCALE = 0.4f;
    private static final int TITLE_MARGIN = 2;

    /**
     * The height, in logical pixels, of the title bar as the mouse sees it: the drawn height plus a small margin above
     * {@link #GRAB_SCALE} (so a click on the first list row or message is not a drag), else grown to 8 real pixels.
     */
    public static int titleHitHeight(int titleH, float scale, int limit) {
        if (scale > GRAB_SCALE) return Math.max(1, Math.min(titleH + TITLE_MARGIN, Math.max(1, limit)));
        return hitSize(titleH, scale, limit);
    }

    /**
     * The size, in logical pixels, of a part that is {@code size} logical pixels when drawn at {@code scale}, grown so
     * that it is at least {@link #MIN_HIT_PX} real pixels (at a scale of 0.1 a 13 pixel title bar is 1 real pixel) and
     * never larger than {@code limit} (the window).
     */
    public static int hitSize(int size, float scale, int limit) {
        int need = (int) Math.ceil(MIN_HIT_PX / (double) Math.max(scale, 0.01f));
        return Math.max(1, Math.min(Math.max(size, need), Math.max(1, limit)));
    }

    /**
     * @param sidebar  the list beside the messages
     * @param toggle   the header button that switches between the messages and the list (only when the list is collapsed)
     * @param listView the list fills the content area instead of the messages (collapsed and asked for)
     * @param buttons  the header buttons
     * @param history  the messages (or the contact picker)
     * @param input    the input line
     */
    public record Plan(boolean sidebar, boolean toggle, boolean listView, boolean buttons, boolean history, boolean input) {
    }

    /**
     * @param focused         the window has the keyboard (the input line is in use)
     * @param listViewWanted  the user switched to the list while it is collapsed
     */
    public static Plan plan(int logicalW, int logicalH, boolean focused, boolean listViewWanted) {
        boolean narrow = logicalW < SIDEBAR_MIN_W;
        boolean listView = narrow && listViewWanted;
        boolean room = logicalH >= BOTH_MIN_H;
        boolean history;
        boolean input;
        if (room) {
            history = !listView;
            input = true;
        } else if (focused && !listView) {
            history = false;
            input = true;
        } else {
            history = !listView;
            input = false;
        }
        return new Plan(!narrow, narrow, listView, logicalW >= BUTTONS_MIN_W, history, input);
    }
}
