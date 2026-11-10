package com.porter.qwerte.printer.render;

/**
 * Which still-to-place cells get a phantom block: the scope (the active layer, or everything), the range around the
 * player and the cap on how many are drawn, nearest first. Pure integer maths, no Minecraft classes.
 *
 * <p>A cell is in when it is in the scope and its squared distance to the player's block is at most {@code limit2}. The
 * limit is the range squared, or smaller when more cells than the cap would fall inside: then it is the distance at which
 * the cap is reached (see {@link #pickLimit}). Cells at exactly the limit distance are all taken, so the count can pass the
 * cap by the cells of one distance shell (a few dozen to a few hundred).
 */
public final class PhantomSelect {
    private PhantomSelect() {
    }

    /** How much of a 16x16x16 section is inside the limit. */
    public enum Cover { NONE, PARTIAL, ALL }

    /** Is a cell at this y in the scope: {@code layer} null means every layer. */
    public static boolean inScope(Integer layer, int y) {
        return layer == null || layer == y;
    }

    public static long dist2(int x, int y, int z, int ox, int oy, int oz) {
        long dx = x - ox, dy = y - oy, dz = z - oz;
        return dx * dx + dy * dy + dz * dz;
    }

    /** Is the cell in: in the scope and within the limit. */
    public static boolean included(int x, int y, int z, int ox, int oy, int oz, long limit2, Integer layer) {
        return inScope(layer, y) && dist2(x, y, z, ox, oy, oz) <= limit2;
    }

    /** Squared distance from the origin to the nearest cell of the section that is in the scope (Long.MAX_VALUE: none). */
    public static long nearest2(int sx, int sy, int sz, int ox, int oy, int oz, Integer layer) {
        int y0 = sy << 4, y1 = y0 + 15;
        if (layer != null) {
            if (layer < y0 || layer > y1) return Long.MAX_VALUE;
            y0 = y1 = layer;
        }
        long dx = gap(ox, sx << 4, (sx << 4) + 15), dy = gap(oy, y0, y1), dz = gap(oz, sz << 4, (sz << 4) + 15);
        return dx * dx + dy * dy + dz * dz;
    }

    private static long gap(int o, int lo, int hi) {
        return o < lo ? lo - o : Math.max(o - hi, 0);
    }

    private static long reach(int o, int lo, int hi) {
        return Math.max(Math.abs(o - lo), Math.abs(o - hi));
    }

    /** How much of the section's in-scope cells are within the limit. */
    public static Cover cover(int sx, int sy, int sz, int ox, int oy, int oz, long limit2, Integer layer) {
        long near = nearest2(sx, sy, sz, ox, oy, oz, layer);
        if (near == Long.MAX_VALUE || near > limit2) return Cover.NONE;
        int y0 = sy << 4, y1 = y0 + 15;
        if (layer != null) y0 = y1 = layer;
        long dx = reach(ox, sx << 4, (sx << 4) + 15), dy = reach(oy, y0, y1), dz = reach(oz, sz << 4, (sz << 4) + 15);
        return dx * dx + dy * dy + dz * dz <= limit2 ? Cover.ALL : Cover.PARTIAL;
    }

    /**
     * The distance limit for the cap. {@code hist[d]} is how many in-scope cells are at squared distance d from the player,
     * for d from 0 to {@code range2}. Everything inside the range when that is no more than the cap, else the smallest
     * distance at which the running total reaches the cap.
     */
    public static long pickLimit(int[] hist, long range2, int cap) {
        long total = 0;
        int top = (int) Math.min(range2, hist.length - 1L);
        for (int d = 0; d <= top; d++) total += hist[d];
        if (total <= cap) return range2;
        long run = 0;
        for (int d = 0; d <= top; d++) {
            run += hist[d];
            if (run >= cap) return d;
        }
        return range2;
    }
}
