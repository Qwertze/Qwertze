package com.porter.qwerte.utils;

/**
 * Whether a walk is getting anywhere: it makes progress when it gets at least {@link #MIN_GAIN} blocks closer than it has
 * been so far. Pure, on a tick counter.
 */
public final class WalkProgress {
    public static final double MIN_GAIN = 1.0;

    private double best = Double.MAX_VALUE;
    private int since;

    public void reset(int now, double distance) {
        best = distance;
        since = now;
    }

    /** Feeds the current distance to the goal. */
    public void update(int now, double distance) {
        if (distance <= best - MIN_GAIN) {
            best = distance;
            since = now;
        }
    }

    /** No progress for more than {@code limit} ticks. */
    public boolean stalled(int now, int limit) {
        return now - since > limit;
    }

    public int idleTicks(int now) {
        return now - since;
    }
}
