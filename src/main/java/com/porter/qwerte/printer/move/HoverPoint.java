package com.porter.qwerte.printer.move;

import java.util.List;

/**
 * Where to hover when flying to cells that are out of reach. Pure.
 *
 * <p>A hover point is a feet cell: the feet cell and the cell above it are free (not blocked), and the eyes, 1.62 above
 * the feet at the cell's centre, are within reach of the centre of the target cell. The point is above or beside the
 * target and never in a blocked cell (a solid block, a fluid, or a cell still to be built). Cells the caller would rather
 * not hover in (discouraged: the rest of the schematic) cost a little extra. Among the points that reach the first target
 * that has any, the one that reaches the most other targets and is nearest to the player wins.
 */
public final class HoverPoint {
    public static final double EYES = 1.62;
    /** Extra cost per discouraged cell (feet or head), in blocks of travel. */
    public static final double DISCOURAGED = 1.5;
    /** Cost taken off per further target the point reaches. */
    public static final double EXTRA_TARGET = 0.4;

    public interface Cells {
        boolean test(int x, int y, int z);
    }

    public record Point(int x, int y, int z) {}

    private HoverPoint() {}

    /**
     * @param targets     cells to reach, nearest first (x, y, z each)
     * @param px          player position (feet)
     * @param reach       how far the eyes may be from a target's centre
     * @param blocked     a cell the body can't be in
     * @param discouraged a cell better not hovered in (may be null)
     * @return the point, or null when no free cell within reach of any target exists
     */
    public static Point choose(List<int[]> targets, double px, double py, double pz, double reach, Cells blocked, Cells discouraged) {
        int span = (int) Math.ceil(reach);
        for (int[] t : targets) {
            Point best = null;
            double bestCost = Double.MAX_VALUE;
            // Above the target first, then level with it and below
            for (int pass = 0; pass < 2 && best == null; pass++) {
                int lo = pass == 0 ? 1 : -span, hi = pass == 0 ? span : 0;
                for (int dy = lo; dy <= hi; dy++) {
                    for (int dx = -span; dx <= span; dx++) {
                        for (int dz = -span; dz <= span; dz++) {
                            int x = t[0] + dx, y = t[1] + dy, z = t[2] + dz;
                            if (!within(x, y, z, t, reach)) continue;
                            if (blocked.test(x, y, z) || blocked.test(x, y + 1, z)) continue;
                            double cost = Math.sqrt(sq(x + 0.5 - px) + sq(y - py) + sq(z + 0.5 - pz));
                            if (discouraged != null) {
                                if (discouraged.test(x, y, z)) cost += DISCOURAGED;
                                if (discouraged.test(x, y + 1, z)) cost += DISCOURAGED;
                            }
                            for (int[] o : targets) if (o != t && within(x, y, z, o, reach)) cost -= EXTRA_TARGET;
                            if (cost < bestCost) {
                                bestCost = cost;
                                best = new Point(x, y, z);
                            }
                        }
                    }
                }
            }
            if (best != null) return best;
        }
        return null;
    }

    /** The eyes of a player standing in feet cell (x, y, z) are within reach of the centre of target t. */
    public static boolean within(int x, int y, int z, int[] t, double reach) {
        return sq(x + 0.5 - (t[0] + 0.5)) + sq(y + EYES - (t[1] + 0.5)) + sq(z + 0.5 - (t[2] + 0.5)) <= reach * reach;
    }

    private static double sq(double v) {
        return v * v;
    }
}
