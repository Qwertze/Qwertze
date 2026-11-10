package com.porter.qwerte.craft;

/**
 * A circuit breaker for a craft order: after {@link #MAX_ZERO} chunks in a row that made nothing, the rest of the craft lines are given up on
 * instead of walking to the table over and over. Pure.
 */
public final class CraftBreaker {
    public static final int MAX_ZERO = 3;

    private int zeros;

    /** A chunk ended having made {@code made}. @return true when the breaker trips (the caller fails every craft line still to make) */
    public boolean record(int made) {
        if (made > 0) {
            zeros = 0;
            return false;
        }
        if (++zeros >= MAX_ZERO) {
            zeros = 0;
            return true;
        }
        return false;
    }

    public void reset() {
        zeros = 0;
    }
}
