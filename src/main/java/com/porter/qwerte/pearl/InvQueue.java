package com.porter.qwerte.pearl;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Inventory operations, one at a time. The server answers an inventory click with a state id, and a click that carries
 * a stale one is rejected and the whole inventory is sent again, which undoes the client's own guess. Clicks fired in
 * the next tick (or every tick) before the answer arrived therefore keep undoing each other. So an operation is issued
 * only when none is in flight, and the next one waits until the result of the last one shows, and has had the time a
 * rejection would take to come back.
 *
 * @param <K> the kind of operation
 */
public final class InvQueue<K extends Enum<K>> {
    public enum Step { IDLE, ISSUE, WAIT, DONE, RETRY, FAILED }

    /** Ticks an operation waits at least before it can count as done (or as undone). */
    public static final int MIN_WAIT = 2;
    /** Ticks past the settle time after which an operation that shows no result is tried again. */
    public static final int TIMEOUT_EXTRA = 6;

    private final int maxFailures;
    private final ArrayDeque<K> queue = new ArrayDeque<>();
    private final Set<K> failed = new java.util.HashSet<>();
    private K current;
    private int waited;
    private int failures;

    /** @param maxFailures real failed attempts (issued, waited, no result) before an operation is given up */
    public InvQueue(int maxFailures) {
        this.maxFailures = maxFailures;
    }

    /** Adds an operation, unless one of the same kind is queued or in flight. */
    public void enqueue(K kind) {
        if (!contains(kind)) queue.addLast(kind);
    }

    /** Queued or in flight. */
    public boolean contains(K kind) {
        return kind.equals(current) || queue.contains(kind);
    }

    /** Takes a queued operation back, if it has not been issued yet. */
    public void cancelQueued(K kind) {
        queue.remove(kind);
    }

    public boolean busy() {
        return current != null;
    }

    public boolean hasWork() {
        return current != null || !queue.isEmpty();
    }

    public K current() {
        return current;
    }

    public int waited() {
        return waited;
    }

    public int failures() {
        return failures;
    }

    public List<K> queued() {
        return new ArrayList<>(queue);
    }

    /** Whether this kind was given up since the last call (and forgets it). */
    public boolean takeFailed(K kind) {
        return failed.remove(kind);
    }

    /** The operation in flight turned out to need no click (nothing to do): done at once. */
    public void skip() {
        current = null;
        waited = 0;
        failures = 0;
    }

    public void clear() {
        queue.clear();
        failed.clear();
        current = null;
        waited = 0;
        failures = 0;
    }

    /**
     * Once a tick. IDLE: nothing to do. ISSUE (or RETRY): the caller issues {@link #current()} now, planned afresh from
     * the slots as they are. WAIT: it was issued, the result is awaited. DONE: it showed. FAILED: given up.
     *
     * @param resultVisible the client's slots show what the operation was meant to produce
     * @param stateChanged  the menu's state id has changed since the operation was issued (the server answered)
     * @param pingTicks     the connection's round trip in ticks
     */
    public Step tick(boolean resultVisible, boolean stateChanged, int pingTicks) {
        if (current == null) {
            current = queue.pollFirst();
            if (current == null) return Step.IDLE;
            waited = 0;
            failures = 0;
            return Step.ISSUE;
        }
        waited++;
        int settle = Math.max(MIN_WAIT, pingTicks + 1);
        if (resultVisible && (waited >= settle || (stateChanged && waited >= MIN_WAIT))) {
            current = null;
            waited = 0;
            failures = 0;
            return Step.DONE;
        }
        boolean reverted = !resultVisible && stateChanged && waited >= MIN_WAIT;
        if (reverted || waited >= settle + TIMEOUT_EXTRA) {
            if (++failures > maxFailures) {
                failed.add(current);
                current = null;
                waited = 0;
                failures = 0;
                return Step.FAILED;
            }
            waited = 0;
            return Step.RETRY;
        }
        return Step.WAIT;
    }
}
