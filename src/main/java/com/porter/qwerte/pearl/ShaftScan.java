package com.porter.qwerte.pearl;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads the geometry of a vertical 1x1 stasis shaft from the blocks around a position: the water the pearl rests in,
 * the trapdoors above it, and the spots beside the top of the shaft the player can stand on.
 */
public final class ShaftScan {
    public enum Kind { AIR, WATER, TRAPDOOR_OPEN, TRAPDOOR_CLOSED, SOLID, OTHER }

    public interface World {
        Kind kind(int x, int y, int z);
    }

    /** A spot beside the shaft: the neighbouring column is (x+dx, z+dz) and the player stands with their feet at floorY. */
    public record Rim(int dx, int dz, int floorY) {}

    /** trapYs ascending, all above waterTop. */
    public record Shaft(int x, int z, int waterTop, List<Integer> trapYs, List<Rim> rims) {
        public int topTrap() {
            return trapYs.get(trapYs.size() - 1);
        }
    }

    public record Result(Shaft shaft, String error) {}

    private static final int[][] DIRS = { { 0, -1 }, { 1, 0 }, { 0, 1 }, { -1, 0 } };

    private ShaftScan() {}

    /**
     * @param up   how many blocks above the highest water to look for trapdoors
     * @param down how far from {@code y} to look for the water (up and down)
     */
    public static Result scan(World w, int x, int y, int z, int up, int down) {
        int water = Integer.MIN_VALUE;
        for (int d = 0; d <= down && water == Integer.MIN_VALUE; d++) {
            if (w.kind(x, y - d, z) == Kind.WATER) water = y - d;
            else if (d > 0 && w.kind(x, y + d, z) == Kind.WATER) water = y + d;
        }
        if (water == Integer.MIN_VALUE) return new Result(null, "no water found in the shaft");

        int waterTop = water;
        while (w.kind(x, waterTop + 1, z) == Kind.WATER) waterTop++;

        List<Integer> traps = new ArrayList<>();
        for (int yy = waterTop + 1; yy <= waterTop + up; yy++) {
            Kind k = w.kind(x, yy, z);
            if (k == Kind.TRAPDOOR_OPEN || k == Kind.TRAPDOOR_CLOSED) traps.add(yy);
            else if (k == Kind.SOLID || k == Kind.OTHER) {
                if (traps.isEmpty()) return new Result(null, "the shaft is blocked above the water");
                break;
            }
        }
        if (traps.isEmpty()) return new Result(null, "no trapdoor above the water");

        int top = traps.get(traps.size() - 1);
        List<Rim> rims = new ArrayList<>();
        for (int f : new int[] { top + 1, top, top + 2 }) {
            for (int[] d : DIRS) {
                int nx = x + d[0], nz = z + d[1];
                if (w.kind(nx, f - 1, nz) != Kind.SOLID || w.kind(nx, f, nz) != Kind.AIR || w.kind(nx, f + 1, nz) != Kind.AIR) continue;
                if (!passable(w.kind(x, f, z)) || !passable(w.kind(x, f + 1, z))) continue;
                rims.add(new Rim(d[0], d[1], f));
            }
        }
        if (rims.isEmpty()) return new Result(null, "no standing spot beside the top of the shaft");
        return new Result(new Shaft(x, z, waterTop, List.copyOf(traps), List.copyOf(rims)), null);
    }

    /** A shaft found for saving: its column, the water surface, the trapdoors and how far below the player the water is. */
    public record Found(int x, int z, int waterTop, List<Integer> trapYs, int depth) {}

    /** What a save scan came to: a shaft, or an error; {@code log} says which columns were tried and why. */
    public record SaveResult(Found found, String error, List<String> log) {}

    /**
     * Finds the shaft to save from where the player stands: on the cover trapdoor (or in the water), or on the rim beside
     * the shaft. The player's own column is tried first, walking down through trapdoors and air to the water; if that is
     * no shaft, the four neighbouring columns are tried, and exactly one of them must be.
     * @param feetY   the y of the block the player's feet are in
     * @param maxDown how far below the feet the water may be
     */
    public static SaveResult saveScan(World w, int x, int feetY, int z, int maxDown) {
        List<String> log = new ArrayList<>();
        Found own = column(w, x, feetY, z, maxDown, "own column", log);
        if (own != null) return new SaveResult(own, null, log);
        List<Found> options = new ArrayList<>();
        for (int[] d : DIRS) {
            Found f = column(w, x + d[0], feetY, z + d[1], maxDown, "neighbour " + (x + d[0]) + "," + (z + d[1]), log);
            if (f != null) options.add(f);
        }
        if (options.size() == 1) return new SaveResult(options.get(0), null, log);
        if (options.size() > 1) {
            return new SaveResult(null, "more than one shaft is next to you, stand on the cover trapdoor of the one you mean", log);
        }
        return new SaveResult(null, "no shaft with water and a trapdoor found below you or beside you (within " + maxDown + " blocks)", log);
    }

    private static Found column(World w, int x, int feetY, int z, int maxDown, String label, List<String> log) {
        List<Integer> traps = new ArrayList<>();
        int waterTop = Integer.MIN_VALUE;
        if (w.kind(x, feetY, z) == Kind.WATER) {
            waterTop = feetY;
            while (waterTop < feetY + maxDown && w.kind(x, waterTop + 1, z) == Kind.WATER) waterTop++;
            log.add(label + ": in water, surface at y " + waterTop);
        }
        else {
            for (int d = 0; d <= maxDown; d++) {
                int y = feetY - d;
                Kind k = w.kind(x, y, z);
                if (k == Kind.WATER) {
                    waterTop = y;
                    break;
                }
                if (k == Kind.TRAPDOOR_OPEN || k == Kind.TRAPDOOR_CLOSED) traps.add(0, y);
                else if (k != Kind.AIR) {
                    log.add(label + ": rejected, " + k + " at y " + y + " before any water");
                    return null;
                }
            }
            if (waterTop == Integer.MIN_VALUE) {
                log.add(label + ": rejected, no water within " + maxDown + " blocks below y " + feetY);
                return null;
            }
        }
        // Trapdoors above the water (and above the feet, for a stack of covers)
        for (int y = waterTop + 1; y <= waterTop + 12 + Math.max(0, feetY - waterTop); y++) {
            Kind k = w.kind(x, y, z);
            if (k == Kind.TRAPDOOR_OPEN || k == Kind.TRAPDOOR_CLOSED) {
                if (!traps.contains(y)) traps.add(y);
            }
            else if (k == Kind.SOLID || k == Kind.OTHER) break;
        }
        traps.sort(null);
        if (traps.isEmpty()) {
            log.add(label + ": rejected, water at y " + waterTop + " but no trapdoor above it");
            return null;
        }
        log.add(label + ": accepted, water top y " + waterTop + ", trapdoors at " + traps);
        return new Found(x, z, waterTop, List.copyOf(traps), feetY - waterTop);
    }

    private static boolean passable(Kind k) {
        return k != Kind.SOLID && k != Kind.OTHER;
    }
}
