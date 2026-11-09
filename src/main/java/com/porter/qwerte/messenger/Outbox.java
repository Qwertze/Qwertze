package com.porter.qwerte.messenger;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Messages waiting to be sent, one per interval, at most {@link #MAX_QUEUE} waiting. Time is passed in (milliseconds), so
 * it is testable. Pure: no Minecraft classes.
 */
public final class Outbox<T> {
    public static final int MAX_QUEUE = 3;
    public static final int MIN_INTERVAL = 500;
    public static final int MAX_INTERVAL = 10_000;

    private final ArrayDeque<T> queue = new ArrayDeque<>();
    private long lastSent;
    private boolean everSent;

    public static int clampInterval(int ms) {
        return Math.max(MIN_INTERVAL, Math.min(MAX_INTERVAL, ms));
    }

    /** Queues an item; false when the queue is full (the caller tells the user to wait). */
    public boolean offer(T item) {
        if (queue.size() >= MAX_QUEUE) return false;
        queue.addLast(item);
        return true;
    }

    /** The next item if one is waiting and the interval since the last send has passed; it is removed. Else null. */
    public T next(long now, int intervalMs) {
        if (queue.isEmpty()) return null;
        if (everSent && now - lastSent < clampInterval(intervalMs) && now >= lastSent) return null;
        everSent = true;
        lastSent = now;
        return queue.pollFirst();
    }

    public int size() {
        return queue.size();
    }

    /** Empties the queue and returns what was waiting. */
    public List<T> clear() {
        List<T> dropped = new ArrayList<>(queue);
        queue.clear();
        return dropped;
    }
}
