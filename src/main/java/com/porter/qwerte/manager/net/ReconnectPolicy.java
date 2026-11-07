package com.porter.qwerte.manager.net;

/**
 * How long the viewer's stream waits before it connects again. Pure. Rules: at least one second between two connects, no
 * more than three immediate reconnects in a row without a snapshot (then it backs off, 5 s doubling up to 60 s), a failure waits
 * for its backoff (or for the server's Retry-After if that is longer), and a snapshot resets the backoff.
 */
public final class ReconnectPolicy {
    public static final long MIN_GAP_MS = 1_000;
    public static final long BACKOFF_START_MS = 5_000;
    public static final long BACKOFF_MAX_MS = 60_000;
    public static final int FREE_RECONNECTS = 3;

    private long backoff = BACKOFF_START_MS;
    private int zeroRun;
    private long lastStart = Long.MIN_VALUE / 2;

    /** A connect is being started now. */
    public void started(long now) {
        lastStart = now;
    }

    /**
     * @param requestedMs 0 to reconnect at once (a rotation), or the wait the server asked for
     * @param gotSnapshot the connection that just ended delivered a snapshot
     * @param failed      it ended in an error (no answer, a refusal other than the final ones, a drop before any snapshot)
     * @return milliseconds to wait before the next connect
     */
    public long next(long now, long requestedMs, boolean gotSnapshot, boolean failed) {
        if (gotSnapshot) {
            zeroRun = 0;
            backoff = BACKOFF_START_MS;
        }
        long wait = Math.max(0, requestedMs);
        if (failed) {
            wait = Math.max(wait, backoff);
            backoff = Math.min(BACKOFF_MAX_MS, backoff * 2);
        } else if (wait == 0 && !gotSnapshot) {
            zeroRun++;
            if (zeroRun > FREE_RECONNECTS) {
                wait = backoff;
                backoff = Math.min(BACKOFF_MAX_MS, backoff * 2);
            }
        }
        return Math.max(wait, lastStart + MIN_GAP_MS - now);
    }
}
