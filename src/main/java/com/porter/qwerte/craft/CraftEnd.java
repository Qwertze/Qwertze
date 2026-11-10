package com.porter.qwerte.craft;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The end of a craft order (CRAFT-CONTRACT.md "Bot execution", point 7): what the bot still carries of crafted items and unused ingredients is
 * put back by the normal put-back, and the final report waits for that, at most {@link #RETURN_WAIT_MS}, so {@code result.craft.returned} says
 * what really went back. Pure; the client thread.
 */
public final class CraftEnd {
    public static final long RETURN_WAIT_MS = 90_000;

    private final long since;
    private final Map<String, Long> atEnd = new LinkedHashMap<>();

    /** @param carriedAtEnd what is carried when the order ends, by item id */
    public CraftEnd(long now, Map<String, Long> carriedAtEnd) {
        this.since = now;
        for (var e : carriedAtEnd.entrySet()) if (e.getValue() != null && e.getValue() > 0) atEnd.put(e.getKey(), e.getValue());
    }

    /** Nothing more to wait for: all of it went back, or the time is up. */
    public boolean done(long now, Map<String, Long> stillCarried) {
        if (now - since >= RETURN_WAIT_MS) return true;
        for (var e : atEnd.entrySet()) if (stillCarried.getOrDefault(e.getKey(), 0L) > 0) return false;
        return true;
    }

    /** What went back: what was carried at the end less what still is. */
    public Map<String, Long> returned(Map<String, Long> stillCarried) {
        Map<String, Long> out = new LinkedHashMap<>();
        for (var e : atEnd.entrySet()) {
            long n = e.getValue() - Math.max(0, Math.min(e.getValue(), stillCarried.getOrDefault(e.getKey(), 0L)));
            if (n > 0) out.put(e.getKey(), n);
        }
        return out;
    }

    public boolean nothingCarried() {
        return atEnd.isEmpty();
    }

    /** The wait is over by time alone (the crafter was still busy, say). */
    public boolean timeUp(long now) {
        return now - since >= RETURN_WAIT_MS;
    }

    /** More was carried than first thought (a chunk ended after the order did): the baseline grows to it. */
    public void raise(Map<String, Long> carried) {
        for (var e : carried.entrySet()) if (e.getValue() != null && e.getValue() > atEnd.getOrDefault(e.getKey(), 0L)) atEnd.put(e.getKey(), e.getValue());
    }
}
