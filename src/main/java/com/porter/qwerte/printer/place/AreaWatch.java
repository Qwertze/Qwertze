package com.porter.qwerte.printer.place;

import java.util.List;

/**
 * Whether the player is away from the schematic: farther than the reach from every region's box. Pure. The Printer warns
 * once per exit and keeps running; being away only means there is nothing in reach to place.
 */
public final class AreaWatch {
    private boolean outside;

    /** Distance from a point to a region's world box {minX, minY, minZ, maxX, maxY, maxZ} (cell coordinates, both corners included). */
    public static double distance(int[] box, double px, double py, double pz) {
        double dx = Math.max(Math.max(box[0] - px, 0), px - (box[3] + 1));
        double dy = Math.max(Math.max(box[1] - py, 0), py - (box[4] + 1));
        double dz = Math.max(Math.max(box[2] - pz, 0), pz - (box[5] + 1));
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** True when the point is farther than {@code reach} from every box. No boxes: never outside. */
    public static boolean outside(List<int[]> boxes, double px, double py, double pz, double reach) {
        if (boxes.isEmpty()) return false;
        for (int[] b : boxes) if (distance(b, px, py, pz) <= reach) return false;
        return true;
    }

    /** Feeds one observation; true exactly once per exit (on the tick the player goes from in to out). */
    public boolean update(boolean nowOutside) {
        boolean exited = nowOutside && !outside;
        outside = nowOutside;
        return exited;
    }

    public boolean isOutside() {
        return outside;
    }

    public void reset() {
        outside = false;
    }
}
