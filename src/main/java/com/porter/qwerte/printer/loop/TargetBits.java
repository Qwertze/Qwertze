package com.porter.qwerte.printer.loop;

import com.porter.qwerte.printer.place.LayerCursor;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The cells that are still to be built, as one bit each in 16x16x16 sections. Written on the client thread, read from
 * Baritone's path finding threads (to keep the player's feet out of unbuilt cells): the section map is concurrent and a
 * section's words are plain longs, so a read sees an old or a new value of a bit, never a broken one. Pure.
 */
public final class TargetBits implements LayerCursor.Counts {
    private static final int Y_OFFSET = 2048, Y_RANGE = 4096;
    /** Open cells per y, kept in add and remove: O(1) each. A y outside the world's range is not counted per layer. */
    private final int[] perY = new int[Y_RANGE];
    private int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
    private boolean bounds = true;

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
        int yi = y + Y_OFFSET;
        if (yi >= 0 && yi < Y_RANGE) {
            perY[yi]++;
            if (y < lo) lo = y;
            if (y > hi) hi = y;
        }
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
        int yi = y + Y_OFFSET;
        if (yi >= 0 && yi < Y_RANGE && --perY[yi] == 0 && (y == lo || y == hi)) bounds = false;
        return true;
    }

    public int size() {
        return count;
    }

    public void clear() {
        sections.clear();
        count = 0;
        Arrays.fill(perY, 0);
        lo = Integer.MAX_VALUE;
        hi = Integer.MIN_VALUE;
        bounds = true;
    }

    /** Open cells at this y. */
    @Override
    public int open(int y) {
        int yi = y + Y_OFFSET;
        return yi >= 0 && yi < Y_RANGE ? perY[yi] : 0;
    }

    @Override
    public int total() {
        return count;
    }

    /** The lowest y with an open cell (greater than {@link #high()} when there is none). */
    @Override
    public int low() {
        fixBounds();
        return lo;
    }

    @Override
    public int high() {
        fixBounds();
        return hi;
    }

    /** After a layer emptied at an end of the range: found again by one pass over the counts (only when asked). */
    private void fixBounds() {
        if (bounds) return;
        int l = Integer.MAX_VALUE, h = Integer.MIN_VALUE;
        for (int i = 0; i < Y_RANGE; i++) {
            if (perY[i] == 0) continue;
            if (l == Integer.MAX_VALUE) l = i - Y_OFFSET;
            h = i - Y_OFFSET;
        }
        lo = l;
        hi = h;
        bounds = true;
    }
}
