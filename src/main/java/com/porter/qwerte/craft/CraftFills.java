package com.porter.qwerte.craft;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Grid fills made for craft orders in the last hour (the cap {@code craft-order-max-per-hour}). Thread-safe. The stamps are kept in the stash
 * file ({@link #export}, {@link #load}), so a restart does not reset the cap; what is read back is clamped (no stamp from the future, no
 * negative or absurd count, at most {@link #MAX_STAMPS} of them).
 */
public final class CraftFills {
    private static final long HOUR_MS = 60 * 60 * 1000L;
    public static final int MAX_STAMPS = 4096;
    private static final long MAX_FILLS_PER_STAMP = 1_000_000L;

    private record Stamp(long at, long fills) {
    }

    private final ArrayDeque<Stamp> stamps = new ArrayDeque<>();
    private long total;

    public synchronized void add(long now, long fills) {
        if (fills <= 0) return;
        stamps.addLast(new Stamp(now, fills));
        total += fills;
        while (stamps.size() > MAX_STAMPS) total -= stamps.pollFirst().fills();
    }

    public synchronized long lastHour(long now) {
        while (!stamps.isEmpty() && now - stamps.peekFirst().at() > HOUR_MS) total -= stamps.pollFirst().fills();
        return total;
    }

    /** The stamps of the last hour as {at, fills}, oldest first. */
    public synchronized List<long[]> export(long now) {
        lastHour(now);
        List<long[]> out = new ArrayList<>();
        for (Stamp s : stamps) out.add(new long[] {s.at(), s.fills()});
        return out;
    }

    /** Replaces the stamps with saved ones (checked: a stamp that is not a sane pair is dropped, a future one is taken as now). */
    public synchronized void load(List<long[]> saved) {
        stamps.clear();
        total = 0;
        long now = System.currentTimeMillis();
        for (long[] e : saved) {
            if (e == null || e.length != 2 || e[1] <= 0 || e[1] > MAX_FILLS_PER_STAMP || stamps.size() >= MAX_STAMPS) continue;
            long at = Math.min(e[0], now);
            if (now - at > HOUR_MS) continue;
            stamps.addLast(new Stamp(at, e[1]));
            total += e[1];
        }
    }
}
