package com.porter.qwerte.printer.move;

/**
 * Which spare blocks Baritone climbed with may be broken again. Pure.
 *
 * <p>A block is shielded while the player could be standing on it or climbing it: it is within {@link #RADIUS} blocks
 * horizontally of the player's feet column and at or below the feet. A shielded block is never broken. Of the rest the
 * highest goes first (a tower comes down from the top), so nothing is taken from under a block that is still needed.
 */
public final class StandShield {
    private StandShield() {}

    public static final int RADIUS = 2;
    /** Ticks between two walks away from the stand blocks, and how many failed walks are allowed before they are left standing. */
    public static final int RETRY_TICKS = 40, MAX_FAILS = 3;

    /** The block (bx, by, bz) is within {@link #RADIUS} of the feet block (fx, fy, fz) horizontally and not above it. */
    public static boolean shielded(int bx, int by, int bz, int fx, int fy, int fz) {
        return Math.abs(bx - fx) <= RADIUS && Math.abs(bz - fz) <= RADIUS && by <= fy;
    }

    /**
     * True when the feet cell (x, y, z) is no place to end a walk to take the blocks down: a block of the list would be
     * shielded from there. {@code blocks} are x, y, z triples.
     */
    public static boolean badFeet(int x, int y, int z, int[][] blocks) {
        for (int[] b : blocks) if (shielded(b[0], b[1], b[2], x, y, z)) return true;
        return false;
    }

    /**
     * A walk away from the blocks ended (the goal is gone or timed out) and the feet are still next to one of them: it failed.
     * {@code blocks} are x, y, z triples; false for an empty list.
     */
    public static boolean walkFailed(int fx, int fy, int fz, int[][] blocks) {
        for (int[] b : blocks) if (shielded(b[0], b[1], b[2], fx, fy, fz)) return true;
        return false;
    }

    /**
     * The index of the block to break first among {@code blocks} (x, y, z): the highest, and among equals the nearest to
     * the point (ex, ey, ez). -1 when the list is empty.
     */
    public static int topDown(int[][] blocks, double ex, double ey, double ez) {
        int best = -1;
        double bestD = Double.MAX_VALUE;
        for (int i = 0; i < blocks.length; i++) {
            int[] b = blocks[i];
            double dx = b[0] + 0.5 - ex, dy = b[1] + 0.5 - ey, dz = b[2] + 0.5 - ez;
            double d = dx * dx + dy * dy + dz * dz;
            if (best < 0 || b[1] > blocks[best][1] || b[1] == blocks[best][1] && d < bestD) {
                best = i;
                bestD = d;
            }
        }
        return best;
    }
}
