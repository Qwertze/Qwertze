package com.porter.qwerte.manager.local;

/**
 * When a save may be tried again after it failed, so a locked file or a full disk is not rewritten every tick. After a
 * failure the next try waits 30 seconds, doubling to 10 minutes. A file that is too big is not tried again until the
 * store has fewer containers. Counted in client ticks. Pure.
 */
public final class SaveBackoff {
    public static final long FIRST_DELAY = 600;
    public static final long MAX_DELAY = 12_000;

    private long next;
    private long delay;
    private int streak;
    private int tooBigAt = -1;

    /** Whether a save may start now. {@code entries} is the number of containers in the store. */
    public boolean allowed(long tick, int entries) {
        if (tooBigAt >= 0) return entries < tooBigAt;
        return tick >= next;
    }

    /** Records a failure; true for the first of a streak (the one to log). */
    public boolean failed(long tick, int entries, boolean tooBig) {
        streak++;
        if (tooBig) {
            tooBigAt = Math.max(1, entries);
        } else {
            delay = delay == 0 ? FIRST_DELAY : Math.min(MAX_DELAY, delay * 2);
            next = tick + delay;
        }
        return streak == 1;
    }

    public void succeeded() {
        next = 0;
        delay = 0;
        streak = 0;
        tooBigAt = -1;
    }

    public boolean blockedAsTooBig() {
        return tooBigAt >= 0;
    }
}
