package com.porter.qwerte.utils;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Where to put a block down (a shulker box, an anvil) when there is no room beside the bot: the nearest spot within
 * {@link #RADIUS} blocks that the bot can walk to and stand next to. Walkable cells are flooded out from the bot's feet (a
 * step up or down at a time, never out of the radius or the height), nearest first, and the first good spot next to one of
 * them wins. Pure: the world comes in through {@link View}, so the search is tested without the game. The same search as the
 * proxy plugin's stash/PlaceSearch.
 *
 * A good spot is open air with a solid floor under it and no solid block above it; next to a cell the bot can stand in and
 * that is inside the stash perimeter (if one is set); not guarded (on or next to the delivery container, the anvil or the
 * crafting table, or in a container's opening space); and passing the sorter's own rules (no hopper next to it, not on a
 * chest, room around it, nobody standing there).
 */
public final class PlaceSearch {
    private PlaceSearch() {}

    public static final int RADIUS = 5;
    /** How far up or down from the bot's feet a spot may be. */
    public static final int HEIGHT = 2;

    public record Pos(int x, int y, int z) {
        public Pos offset(int dx, int dy, int dz) {
            return new Pos(x + dx, y + dy, z + dz);
        }
    }

    public interface View {
        /** Empty air (a block can go here, or the bot's head). */
        boolean open(int x, int y, int z);

        /** A solid block to put something on, or to stand on. */
        boolean floor(int x, int y, int z);

        /** The bot can stand with its feet in this cell. */
        boolean standable(int x, int y, int z);

        /** Why it mustn't go here by the sorter's rules (hopper, on a chest, boxed in, someone there), or null. */
        String unsafe(Pos spot);

        /** Inside the stash perimeter (true when none is set). */
        boolean inArea(Pos p);

        /** On or next to the delivery container, the anvil or the crafting table. */
        boolean guarded(Pos spot);
    }

    /** The spot, the cell to stand in to place it, and how many steps that cell is from the bot. */
    public record Found(Pos spot, Pos stand, int steps) {}

    /** The spot found, or null and why there is none. */
    public record Result(Found found, String why) {}

    public static final String ALL_BLOCKED = "all blocked", OUTSIDE = "outside the perimeter", UNREACHABLE = "unreachable",
        GUARDED = "next to the delivery container, anvil or crafting table", FAILED = "placing there failed before";

    private static final int[][] DIRS = { { 0, -1 }, { 1, 0 }, { 0, 1 }, { -1, 0 } };

    public static Result search(Pos feet, View v, Set<Pos> skip) {
        // Walkable cells by steps from the bot
        Map<Pos, Integer> steps = new LinkedHashMap<>();
        ArrayDeque<Pos> todo = new ArrayDeque<>();
        steps.put(feet, 0);
        todo.add(feet);
        while (!todo.isEmpty()) {
            Pos at = todo.poll();
            int n = steps.get(at);
            for (int[] d : DIRS) {
                for (int dy = -1; dy <= 1; dy++) {
                    Pos next = new Pos(at.x() + d[0], at.y() + dy, at.z() + d[1]);
                    if (steps.containsKey(next) || !within(feet, next) || !v.standable(next.x(), next.y(), next.z())) continue;
                    // A step up needs head room above where the bot is; a step down head room above the next cell
                    if (dy > 0 && !v.open(at.x(), at.y() + 2, at.z())) continue;
                    if (dy < 0 && !v.open(next.x(), next.y() + 2, next.z())) continue;
                    steps.put(next, n + 1);
                    todo.add(next);
                }
            }
        }

        Map<String, Integer> why = new HashMap<>();
        Found best = null;
        for (var e : steps.entrySet()) {
            Pos stand = e.getKey();
            if (best != null && e.getValue() > best.steps()) break;
            if (!v.inArea(stand)) {
                why.merge(OUTSIDE, 1, Integer::sum);
                continue;
            }
            for (int[] d : DIRS) {
                Pos spot = new Pos(stand.x() + d[0], stand.y(), stand.z() + d[1]);
                String bad = reject(spot, feet, v, skip);
                if (bad != null) {
                    why.merge(bad, 1, Integer::sum);
                    continue;
                }
                if (best == null || e.getValue() < best.steps()) best = new Found(spot, stand, e.getValue());
                break;
            }
        }
        if (best != null) return new Result(best, "");

        // Spots that would do but no walkable cell reaches
        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                for (int dy = -HEIGHT; dy <= HEIGHT; dy++) {
                    Pos spot = feet.offset(dx, dy, dz);
                    if (within(feet, spot) && reject(spot, feet, v, skip) == null) why.merge(UNREACHABLE, 1, Integer::sum);
                }
            }
        }
        String main = ALL_BLOCKED;
        for (String w : why.keySet()) if (!w.equals(ALL_BLOCKED) && rank(w) < rank(main)) main = w;
        return new Result(null, main);
    }

    private static int rank(String why) {
        return why.equals(UNREACHABLE) ? 0 : why.equals(OUTSIDE) ? 1 : why.equals(GUARDED) ? 2 : why.equals(FAILED) ? 3 : why.equals(ALL_BLOCKED) ? 5 : 4;
    }

    private static String reject(Pos spot, Pos feet, View v, Set<Pos> skip) {
        if (!within(feet, spot) || spot.equals(feet)) return ALL_BLOCKED;
        if (!v.open(spot.x(), spot.y(), spot.z()) || !v.floor(spot.x(), spot.y() - 1, spot.z()) || v.floor(spot.x(), spot.y() + 1, spot.z())) return ALL_BLOCKED;
        if (skip.contains(spot)) return FAILED;
        if (!v.inArea(spot)) return OUTSIDE;
        if (v.guarded(spot)) return GUARDED;
        return v.unsafe(spot);
    }

    /** Within the radius across, and the height up or down, of the bot's feet. */
    static boolean within(Pos feet, Pos p) {
        int dx = p.x() - feet.x(), dz = p.z() - feet.z();
        return dx * dx + dz * dz <= RADIUS * RADIUS && Math.abs(p.y() - feet.y()) <= HEIGHT;
    }
}
