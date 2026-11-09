package com.porter.qwerte.tweaks;

import com.porter.qwerte.utils.PlaceSearch.Pos;

/**
 * Finds the closest cell within reach to put a shulker box or an ender chest in: on the top of a solid block, with room
 * above for the lid. Pure, so it is tested without a game; the module answers the {@link View} questions from the world.
 */
public final class ReachSpots {
    private ReachSpots() {}

    /** What the world says about a cell. */
    public interface View {
        /** A block can be placed here (replaceable, nothing in the way). */
        boolean free(int x, int y, int z);

        /** A solid block with a sturdy top, no fluid, no block entity and nothing that opens when clicked. */
        boolean support(int x, int y, int z);

        /** The cell has an empty collision shape (room for the lid). */
        boolean headroom(int x, int y, int z);

        /** The cell overlaps the player. */
        boolean inPlayer(int x, int y, int z);

        /** A living entity stands in the cell. */
        boolean entityIn(int x, int y, int z);

        /** Nothing blocks the line from the eyes to the middle of the top face of the support at x, y, z. */
        boolean lineOfSight(int x, int y, int z, double hitX, double hitY, double hitZ);
    }

    /** The spot, or null and why there is none. */
    public record Result(Pos spot, String why) {}

    public static final String NO_CELL = "nothing within reach", NOT_FREE = "no free cell within reach", NO_SUPPORT = "no solid block to place it on",
        NO_ROOM = "no room above for the lid", IN_PLAYER = "the only cells are inside you", ENTITY = "something is standing there",
        ABOVE_EYES = "the top of every cell is above your eyes", NO_LINE = "no clear line to any cell";

    /** In the order the checks run, so the reason given is the furthest any cell got. */
    private static final String[] REASONS = { NO_CELL, NOT_FREE, NO_SUPPORT, NO_ROOM, IN_PLAYER, ENTITY, ABOVE_EYES, NO_LINE };

    /** Reach left unused so a click at the edge of it still lands. */
    public static final double MARGIN = 0.25;
    /** The eyes must be this far above the clicked top face, or the face cannot be seen. */
    public static final double EYE_EPSILON = 0.05;
    /** Score added per block of height difference from the feet, and for a diagonal. */
    private static final double HEIGHT_PENALTY = 0.5, DIAGONAL_PENALTY = 0.75;

    /**
     * @param feet  the cell the feet are in
     * @param reach the block interaction range; spots must be within reach minus {@link #MARGIN} of the eyes
     */
    public static Result find(double eyeX, double eyeY, double eyeZ, Pos feet, double reach, View view) {
        double max = Math.max(0, reach - MARGIN);
        int r = (int) Math.ceil(max) + 1;
        int ex = (int) Math.floor(eyeX), ey = (int) Math.floor(eyeY), ez = (int) Math.floor(eyeZ);

        Pos best = null;
        boolean bestOrtho = false;
        double bestScore = 0;
        int deepest = 0;

        for (int x = ex - r; x <= ex + r; x++) {
            for (int y = ey - r; y <= ey + r; y++) {
                for (int z = ez - r; z <= ez + r; z++) {
                    // The click lands on the middle of the support's top face, which is the bottom of this cell
                    double hx = x + 0.5, hy = y, hz = z + 0.5;
                    double dx = hx - eyeX, dy = hy - eyeY, dz = hz - eyeZ;
                    double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    if (dist > max) continue;

                    int fail = 0;
                    if (!view.free(x, y, z)) fail = 1;
                    else if (!view.support(x, y - 1, z)) fail = 2;
                    else if (!view.headroom(x, y + 1, z)) fail = 3;
                    else if (view.inPlayer(x, y, z)) fail = 4;
                    else if (view.entityIn(x, y, z)) fail = 5;
                    else if (eyeY <= hy + EYE_EPSILON) fail = 6;
                    else if (!view.lineOfSight(x, y - 1, z, hx, hy, hz)) fail = 7;
                    if (fail != 0) {
                        deepest = Math.max(deepest, fail);
                        continue;
                    }

                    int fx = x - feet.x(), fy = y - feet.y(), fz = z - feet.z();
                    boolean ortho = fy == 0 && Math.abs(fx) + Math.abs(fz) == 1;
                    boolean diagonal = fx != 0 && fz != 0;
                    double score = dist + HEIGHT_PENALTY * Math.abs(fy) + (diagonal ? DIAGONAL_PENALTY : 0);

                    boolean better;
                    if (best == null) better = true;
                    else if (ortho != bestOrtho) better = ortho;
                    else if (score != bestScore) better = score < bestScore;
                    else better = x < best.x() || x == best.x() && (y < best.y() || y == best.y() && z < best.z());

                    if (better) {
                        best = new Pos(x, y, z);
                        bestOrtho = ortho;
                        bestScore = score;
                    }
                }
            }
        }

        if (best != null) return new Result(best, null);
        return new Result(null, REASONS[deepest]);
    }
}
