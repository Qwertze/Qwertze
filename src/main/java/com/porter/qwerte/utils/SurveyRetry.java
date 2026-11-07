package com.porter.qwerte.utils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * When a container the scan could not read is tried again. Pure bookkeeping on a tick counter, no Minecraft types: the
 * first failure is retried after a minute, the second after two, every later one after five.
 */
public final class SurveyRetry<K> {
    /** Ticks to wait after the 1st, 2nd and later failures. */
    public static final int[] DELAYS = {20 * 60, 20 * 120, 20 * 300};

    private static final class Entry {
        int fails;
        int due;
        boolean waiting;
    }

    private final Map<K, Entry> entries = new HashMap<>();

    public static int delayFor(int fails) {
        if (fails < 1) return 0;
        return DELAYS[Math.min(fails, DELAYS.length) - 1];
    }

    /**
     * Records a failed read at tick {@code now}.
     * @return the ticks until it is tried again
     */
    public int fail(K key, int now) {
        Entry entry = entries.computeIfAbsent(key, k -> new Entry());
        entry.fails++;
        int delay = delayFor(entry.fails);
        entry.due = now + delay;
        entry.waiting = true;
        return delay;
    }

    /**
     * Like {@link #fail}, but a key that is already waiting is left alone: one failure per read, however many paths
     * report it.
     * @return the ticks until it is tried again, or -1 when it was already waiting
     */
    public int failOnce(K key, int now) {
        if (waiting(key, now)) return -1;
        return fail(key, now);
    }

    /** Whether the key failed and its time has not come. */
    public boolean waiting(K key, int now) {
        Entry entry = entries.get(key);
        return entry != null && entry.waiting && now < entry.due;
    }

    /** Ticks left before the key is tried again, 0 when it is not waiting. */
    public int remaining(K key, int now) {
        Entry entry = entries.get(key);
        return entry == null || !entry.waiting ? 0 : Math.max(0, entry.due - now);
    }

    public int failures(K key) {
        Entry entry = entries.get(key);
        return entry == null ? 0 : entry.fails;
    }

    /**
     * The keys whose time has come at tick {@code now}. Each is returned once; its failure count stays, so the next
     * failure waits longer.
     */
    public List<K> due(int now) {
        List<K> out = null;
        for (Map.Entry<K, Entry> e : entries.entrySet()) {
            Entry entry = e.getValue();
            if (entry.waiting && now >= entry.due) {
                entry.waiting = false;
                if (out == null) out = new ArrayList<>();
                out.add(e.getKey());
            }
        }
        return out == null ? List.of() : out;
    }

    /** The key was read, or something changed: forgotten, so the next failure starts at a minute again. */
    public void succeed(K key) {
        entries.remove(key);
    }

    public void clear() {
        entries.clear();
    }

    /** "30 s", "1 min", "2 min": for messages. */
    public static String describe(int ticks) {
        int seconds = (ticks + 19) / 20;
        if (seconds < 60) return seconds + " s";
        int minutes = (seconds + 30) / 60;
        return minutes + " min";
    }
}
