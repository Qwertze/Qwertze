package com.porter.qwerte.printer.place;

import java.util.ArrayDeque;

/**
 * A sliding 7 second packet allowance. The limit is {@code perSecond * 7} packets in any 7 second window, which fits
 * servers that count packets over a 7 second window (Paper-style limiters). 0 per second means no limit. Pure: the
 * caller passes the clock in milliseconds. Not thread safe (client thread only).
 */
public final class PacketBudget {
    public static final long WINDOW_MS = 7000;

    private int perSecond;
    private final ArrayDeque<long[]> spent = new ArrayDeque<>(); // {timeMs, packets}, oldest first
    private long total;

    public PacketBudget(int perSecond) {
        setPerSecond(perSecond);
    }

    public void setPerSecond(int perSecond) {
        this.perSecond = Math.max(0, perSecond);
    }

    public int perSecond() {
        return perSecond;
    }

    public boolean unlimited() {
        return perSecond <= 0;
    }

    /** Packets allowed in one window. */
    public long capacity() {
        return (long) perSecond * (WINDOW_MS / 1000);
    }

    private void prune(long nowMs) {
        while (!spent.isEmpty() && spent.peekFirst()[0] <= nowMs - WINDOW_MS) total -= spent.pollFirst()[1];
    }

    /** Packets spent in the window that ends now. */
    public long used(long nowMs) {
        prune(nowMs);
        return total;
    }

    /** Packets that may still be sent now; Integer.MAX_VALUE when unlimited. */
    public int remaining(long nowMs) {
        if (unlimited()) return Integer.MAX_VALUE;
        prune(nowMs);
        return (int) Math.max(0, Math.min(Integer.MAX_VALUE, capacity() - total));
    }

    public boolean canSend(long nowMs, int packets) {
        return unlimited() || remaining(nowMs) >= packets;
    }

    /** Spends the packets if the window has room; returns whether it did. Always succeeds when unlimited. */
    public boolean trySpend(long nowMs, int packets) {
        if (packets <= 0) return true;
        if (unlimited()) return true;
        if (!canSend(nowMs, packets)) return false;
        add(nowMs, packets);
        return true;
    }

    /** Records packets that were sent anyway (movement, other modules) so they count against the window. */
    public void record(long nowMs, int packets) {
        if (packets <= 0 || unlimited()) return;
        prune(nowMs);
        add(nowMs, packets);
    }

    private void add(long nowMs, int packets) {
        long[] last = spent.peekLast();
        if (last != null && last[0] == nowMs) last[1] += packets;
        else spent.addLast(new long[] { nowMs, packets });
        total += packets;
    }

    /** Milliseconds until {@code packets} fit, 0 when they fit now, -1 when they can never fit (more than the capacity). */
    public long waitMs(long nowMs, int packets) {
        if (unlimited() || packets <= 0) return 0;
        if (packets > capacity()) return -1;
        prune(nowMs);
        long free = capacity() - total;
        if (free >= packets) return 0;
        for (long[] e : spent) {
            free += e[1];
            if (free >= packets) return Math.max(0, e[0] + WINDOW_MS - nowMs);
        }
        return WINDOW_MS;
    }

    public void reset() {
        spent.clear();
        total = 0;
    }
}
