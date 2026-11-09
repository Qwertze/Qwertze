package com.porter.qwerte.pearl;

/**
 * Tells when a pearl has come to rest by its height alone. A pearl held in an upward bubble column keeps a speed above any
 * "resting" limit while it hardly changes height; so a pearl whose y stays within {@link #BAND} for {@link #STABLE_TICKS}
 * ticks counts as resting too. Pure: the caller passes the game tick and the pearl's y.
 */
public final class PearlRest {
    public static final double BAND = 0.05;
    public static final int STABLE_TICKS = 3;

    private double refY = Double.NaN;
    private long sinceTick, lastTick = Long.MIN_VALUE;

    public void reset() {
        refY = Double.NaN;
        lastTick = Long.MIN_VALUE;
    }

    /** True when the pearl's y has been within the band for the last {@link #STABLE_TICKS} ticks. Safe to call more than once per tick. */
    public boolean update(long tick, double y) {
        if (tick == lastTick) return tick - sinceTick >= STABLE_TICKS;
        boolean gap = lastTick != Long.MIN_VALUE && tick - lastTick > 1;
        if (Double.isNaN(refY) || gap || Math.abs(y - refY) > BAND) {
            refY = y;
            sinceTick = tick;
        }
        lastTick = tick;
        return tick - sinceTick >= STABLE_TICKS;
    }
}
