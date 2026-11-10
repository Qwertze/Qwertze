package com.porter.qwerte.pearl;

/**
 * The state of one pearl throw that waits for its rotation. The rotation is queued with Meteor's Rotations, whose
 * callback runs only after the rotation packet went out (inside the movement packet for the first queued rotation of
 * a tick, right after its own packet for the others). The use-item packet is therefore sent behind the rotation, but
 * only if the callback belongs to the request that is still current: a callback of a request that was cancelled or
 * replaced (sequence aborted, restarted) must not throw.
 */
public final class ThrowGate {
    /** Rotation priority of the throw: ahead of the aura-style modules, which Meteor sorts behind it. */
    public static final int ROTATION_PRIORITY = 100;

    /** Ticks after which a request whose rotation callback never ran is dropped (5 ticks = 250 ms). */
    public static final int EXPIRY_TICKS = 5;

    private long generation;
    private boolean queued;
    private long requestedAt;

    /** @return a token for the rotation callback, or -1 if a throw is already waiting for its rotation */
    public long request(long nowTick) {
        if (queued) return -1;
        queued = true;
        requestedAt = nowTick;
        return ++generation;
    }

    /**
     * The rotation callback ran (the rotation packet is out).
     * @return whether the use-item may be sent now: this is the current request and it was not used yet
     */
    public boolean rotationSent(long token) {
        return queued && token == generation;
    }

    /** The use-item was tried: it is over, whatever the answer. A refused use may be requested again. */
    public void finished() {
        queued = false;
    }

    /** Drops the waiting request; its callback will find a different token and do nothing. */
    public void cancel() {
        queued = false;
        generation++;
    }

    /**
     * Drops a request whose callback has not run within {@link #EXPIRY_TICKS}, so a lost callback cannot stall the
     * throw phase. Its token is dead afterwards.
     * @return whether a request was dropped
     */
    public boolean expire(long nowTick) {
        if (!queued || nowTick - requestedAt < EXPIRY_TICKS) return false;
        cancel();
        return true;
    }

    public boolean pending() {
        return queued;
    }
}
