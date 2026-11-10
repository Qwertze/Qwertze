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
    private Listener listener;

    /** Told (on the writing thread) about every cell that is added or removed; keeps the phantom block cache fresh. */
    public interface Listener {
        void changed(int x, int y, int z);
    }

    /** Receives the cells of one section, see {@link #forEach}. */
    public interface Visitor {
        void cell(int x, int y, int z);
    }

    /** Matches every y in {@link #forEach} and {@link #countIn}. */
    public static final int ANY_Y = Integer.MIN_VALUE;

    /** Sets (or clears with null) the change listener. */
    public void setListener(Listener l) {
        listener = l;
    }

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
        if (listener != null) listener.changed(x, y, z);
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
        if (listener != null) listener.changed(x, y, z);
        return true;
    }

    public int size() {
        return count;
    }

    /** Cells in the 16x16x16 section (section coordinates), or in its row at {@code y} ({@link #ANY_Y}: all of it). */
    public int countIn(int sx, int sy, int sz, int y) {
        long[] s = sections.get(key(sx << 4, sy << 4, sz << 4));
        if (s == null) return 0;
        int from = 0, to = 64;
        if (y != ANY_Y) {
            if ((y >> 4) != sy) return 0;
            from = (y & 15) * 4;
            to = from + 4;
        }
        int n = 0;
        for (int i = from; i < to; i++) n += Long.bitCount(s[i]);
        return n;
    }

    /** Hands every cell of the section (or of its row at {@code y}) to the visitor, x first. The visitor must not change this set. */
    public void forEach(int sx, int sy, int sz, int y, Visitor v) {
        long[] s = sections.get(key(sx << 4, sy << 4, sz << 4));
        if (s == null) return;
        int from = 0, to = 64;
        if (y != ANY_Y) {
            if ((y >> 4) != sy) return;
            from = (y & 15) * 4;
            to = from + 4;
        }
        int bx = sx << 4, by = sy << 4, bz = sz << 4;
        for (int w = from; w < to; w++) {
            long bits = s[w];
            while (bits != 0) {
                int b = (w << 6) | Long.numberOfTrailingZeros(bits);
                bits &= bits - 1;
                v.cell(bx + (b & 15), by + (b >> 8), bz + ((b >> 4) & 15));
            }
        }
    }

    /** Empties the set. Does NOT tell the listener: whoever listens (the phantom blocks) must be reset along with it. */
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
