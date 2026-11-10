package com.porter.qwerte.stash;

/**
 * Whether the bot counts as busy for an area change: leftover stacks with no destination ({@code stashHeld}) must not hold the area forever. After
 * {@value #HELD_ONLY_MS} ms of nothing but leftovers, a change made in game goes through; a website area still waits. Pure: the time is passed in.
 */
public final class AreaHeld {
    public static final long HELD_ONLY_MS = 10 * 60_000L;

    private long since;

    /**
     * @param hard    something other than leftovers is going on
     * @param held    leftovers are carried
     * @param website the waiting area comes from the website
     * @return true when the area has to keep waiting
     */
    public synchronized boolean busy(boolean hard, boolean held, boolean website, long now) {
        if (hard) {
            since = 0;
            return true;
        }
        if (!held) {
            since = 0;
            return false;
        }
        if (since == 0) since = now;
        return website || now - since < HELD_ONLY_MS;
    }

    public synchronized void reset() {
        since = 0;
    }
}
