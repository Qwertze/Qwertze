package com.porter.qwerte.pearl;

import com.porter.qwerte.pearl.ShaftScan.Kind;

/**
 * The walk from the rim block back to the edge of the shaft. "Along" is the player's middle measured from the shaft's
 * middle in the direction of the rim (dx, dz): 0.5 is the edge, the hang spot is {@link #TARGET_ALONG}. The player
 * faces the spot on that axis every tick and presses only forward, so there is no sideways drift, and stops there.
 */
public final class EdgeApproach {
    /** Where the middle of a sneaking player hangs over the edge, measured from the shaft's middle towards the rim. */
    public static final double TARGET_ALONG = 0.5 - Hang.INSET;
    /** Further than this from the shaft's middle the player's box is wholly on the rim block: safe to walk, not sneak. */
    public static final double BRISK_ALONG = 0.85;
    /** How close to the target counts as there, in blocks (one sneak step is about 0.065). */
    public static final double TOLERANCE = 0.02;

    private EdgeApproach() {}

    /** The hang spot's (x, z) for a rim in direction (dx, dz) of the shaft whose middle is (cx, cz). */
    public static double[] target(double cx, double cz, int dx, int dz) {
        return new double[] { cx + dx * TARGET_ALONG, cz + dz * TARGET_ALONG };
    }

    /** How far from the shaft's middle towards the rim the player's middle is (negative: past the middle). */
    public static double along(double px, double pz, double cx, double cz, int dx, int dz) {
        return (px - cx) * dx + (pz - cz) * dz;
    }

    /** Keep walking while half a tick on would still be beyond the target. {@code vAlong}: speed towards the rim. */
    public static boolean shouldWalk(double along, double vAlong, double targetAlong) {
        return along + vAlong * 0.5 > targetAlong + TOLERANCE;
    }

    public enum Surface { OK, CLOSED_COVER, BLOCKED }

    /**
     * Whether the player may walk towards the shaft: the block under the shaft cell at the rim's level and the one at
     * the feet must not be something to walk on. A closed trapdoor is a floor (the sneak edge stop would not hold on
     * it); an open one is only a thin plate beside the opening.
     */
    public static Surface checkAhead(Kind below, Kind atFeet) {
        if (below == Kind.TRAPDOOR_CLOSED || atFeet == Kind.TRAPDOOR_CLOSED) return Surface.CLOSED_COVER;
        if (below == Kind.SOLID || below == Kind.OTHER || atFeet == Kind.SOLID || atFeet == Kind.OTHER) return Surface.BLOCKED;
        return Surface.OK;
    }
}
