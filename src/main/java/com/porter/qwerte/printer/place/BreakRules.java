package com.porter.qwerte.printer.place;

/**
 * Whether a wrong block next to a fluid may be broken. Breaking opens the cell, and water beside it flows in. Pure.
 *
 * <p>A real fluid block next to the cell (a source or flowing water or lava) always makes the break risky. Water that sits in
 * a waterlogged block (a slab, a stair, kelp, seagrass; or the wrong block itself) is a source too, but it only matters while
 * the cell stays open: the block is broken when its replacement can be placed straight after (the wanted block is not air,
 * its item is carried and a placement recipe exists from where the player stands), which also pushes the water out again.
 */
public final class BreakRules {
    private BreakRules() {}

    public enum Verdict {
        OK,
        /** A fluid block (not waterlogging) touches the cell. */
        FLUID,
        /** Waterlogged water touches the cell (or the wrong block holds it) and the replacement can't follow at once. */
        NEXT_TO_WATER
    }

    /**
     * @param liquidBlockNearby   a real liquid block is one of the six neighbours
     * @param waterloggedNearby   a waterlogged block is a neighbour, or the wrong block itself is waterlogged
     * @param wantedAir           the build wants air in the cell
     * @param itemCarried         the item (or the raw block) of the wanted state is carried
     * @param recipeFromHere      a placement recipe exists from where the player stands (a clickable neighbour in reach, or an air-place)
     */
    public static Verdict fluidVerdict(boolean liquidBlockNearby, boolean waterloggedNearby, boolean wantedAir, boolean itemCarried, boolean recipeFromHere) {
        if (liquidBlockNearby) return Verdict.FLUID;
        if (!waterloggedNearby) return Verdict.OK;
        return !wantedAir && itemCarried && recipeFromHere ? Verdict.OK : Verdict.NEXT_TO_WATER;
    }
}
