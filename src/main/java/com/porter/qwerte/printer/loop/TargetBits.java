package com.porter.qwerte.printer.loop;

import java.util.concurrent.ConcurrentHashMap;

/**
 * The cells that are still to be built, as one bit each in 16x16x16 sections. Written on the client thread, read from
 * Baritone's path finding threads (to keep the player's feet out of unbuilt cells): the section map is concurrent and a
 * section's words are plain longs, so a read sees an old or a new value of a bit, never a broken one. Pure.
 */
public final class TargetBits {
    private final ConcurrentHashMap<Long, long[]> sections = new ConcurrentHashMap<>();
    private int count;

    private static long key(int x, int y, int z) {
        return ((long) ((x >> 4) & 0x3FFFFFF) << 38) | ((long) ((z >> 4) & 0x3FFFFFF) << 12) | ((y >> 4) & 0xFFF);
    }

    private static int bit(int x, int y, int z) {
        return ((y & 15) << 8) | ((z & 15) << 4) | (x & 15);
    }

    public boolean contains(int x, int y, int z) {
        long[] s = sections.get(key(x, y, z));
        if (s == null) return false;
        int b = bit(x, y, z);
        return (s[b >> 6] & (1L << (b & 63))) != 0;
    }

    /** Returns whether the cell was not in the set yet. */
    public boolean add(int x, int y, int z) {
        long[] s = sections.computeIfAbsent(key(x, y, z), k -> new long[64]);
        int b = bit(x, y, z);
        long m = 1L << (b & 63);
        if ((s[b >> 6] & m) != 0) return false;
        s[b >> 6] |= m;
        count++;
        return true;
    }

    /** Returns whether the cell was in the set. */
    public boolean remove(int x, int y, int z) {
        long k = key(x, y, z);
        long[] s = sections.get(k);
        if (s == null) return false;
        int b = bit(x, y, z);
        long m = 1L << (b & 63);
        if ((s[b >> 6] & m) == 0) return false;
        s[b >> 6] &= ~m;
        count--;
        return true;
    }

    public int size() {
        return count;
    }

    public void clear() {
        sections.clear();
        count = 0;
    }
}
