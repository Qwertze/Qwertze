package com.porter.qwerte.printer.loop;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * The slow look at cells that were finished (placed, or found right): a block can go missing, be changed or dry out later.
 * Pure. Cells wait in a ring; each tick a few are examined, and one that is due and in reach is handed out. A cell is handed
 * out at most {@link #MAX_CHECKS} times per run, so a cell that keeps changing does not cost for ever.
 */
public final class RecheckQueue {
    public static final int MAX_CHECKS = 3;
    /** Records kept at most; past that the oldest are dropped (a huge build must not grow the queue without limit). */
    public static final int MAX_RECORDS = 50_000;

    public record Cell(int x, int y, int z, long due) {}

    private final ArrayDeque<Cell> ring = new ArrayDeque<>();
    private final Map<Long, Integer> used = new HashMap<>();

    private static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    public int size() {
        return ring.size();
    }

    public void clear() {
        ring.clear();
        used.clear();
    }

    /** A cell was finished at {@code now}: it is looked at again after {@code delay} ticks (unless it used up its checks). */
    public void add(int x, int y, int z, long now, long delay) {
        if (used.getOrDefault(key(x, y, z), 0) >= MAX_CHECKS) return;
        while (ring.size() >= MAX_RECORDS) ring.pollFirst();
        ring.addLast(new Cell(x, y, z, now + delay));
    }

    /**
     * Examines up to {@code examine} cells from the front. Cells not yet due, or out of reach, go to the back; up to
     * {@code max} due cells in reach are returned, and each counts as a check.
     */
    public List<Cell> poll(long now, int examine, int max, Predicate<Cell> inReach) {
        List<Cell> out = new ArrayList<>();
        for (int i = 0; i < examine && !ring.isEmpty() && out.size() < max; i++) {
            Cell c = ring.pollFirst();
            if (c.due() > now || !inReach.test(c)) {
                ring.addLast(c);
                continue;
            }
            used.merge(key(c.x(), c.y(), c.z()), 1, Integer::sum);
            out.add(c);
        }
        return out;
    }

    /** The cell checked out right: look again after {@code interval} ticks, if it has checks left. */
    public void again(Cell c, long now, long interval) {
        add(c.x(), c.y(), c.z(), now, interval);
    }

    public int checksOf(int x, int y, int z) {
        return used.getOrDefault(key(x, y, z), 0);
    }
}
