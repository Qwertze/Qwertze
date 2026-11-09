package com.porter.qwerte.printer.place;

import com.porter.qwerte.printer.place.BuildOrderer.Cell;
import com.porter.qwerte.printer.place.BuildOrderer.Role;
import com.porter.qwerte.printer.place.PlaceRecipe.Dir;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Scaffolding for a cell nothing solid is next to: a short chain of throw-away blocks from a block that exists to a face of
 * the cell. Pure; the world is passed in.
 *
 * <p>A chain block goes in a position that is air, that the schematic does not want a block in, and that is within
 * the limit's margin of the schematic's bounds (no margin without a limit). The chain is the shortest one (at most the limit); it is
 * searched from the cell outward and ends at the first position that has an existing solid block next to it, so the blocks
 * can be placed one after another starting there. Only a SOLID cell is helped: a block that hangs on something (a torch, a
 * lantern) or falls would lose or need its support when the scaffold goes.
 *
 * <p>Removal: a scaffold block is needed while an open cell would lose its only support without it (or a needed scaffold
 * block would). Everything else can go.
 */
public final class ScaffoldPlanner {
    private ScaffoldPlanner() {}

    /** Hard caps when there is no limit: the longest straight probe (about the world height) and the BFS node count. */
    public static final int MAX_PROBE = 384, MAX_NODES = 40_000;
    /** The search time for one cell, which grows when the search keeps running out of it (up to {@link #MAX_BUDGET_NANOS}). */
    public static final long BASE_BUDGET_NANOS = 400_000L, MAX_BUDGET_NANOS = 2_000_000L;

    /** The time one cell's search may take after it ran out of time {@code hits} times before: 0.4, 0.8, 1.6, then 2 ms. */
    public static long budgetNanos(int hits) {
        return Math.min(MAX_BUDGET_NANOS, BASE_BUDGET_NANOS << Math.max(0, Math.min(hits, 3)));
    }

    public interface World {
        /** Loaded, and nothing there: a scaffold block can be placed here. */
        boolean air(int x, int y, int z);

        /** A block a click can target (in the world, or placed and not yet seen). Scaffold blocks count. */
        boolean solid(int x, int y, int z);

        /** The height is inside the world's build height: nothing can be placed outside it. */
        boolean inHeight(int y);

        /** The schematic wants a block (not air) in this cell, built or not. */
        boolean wanted(int x, int y, int z);

        /** The cell still to build here, or null. */
        Cell open(int x, int y, int z);
    }

    /** A box of cells, inclusive. */
    public record Box(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        boolean near(int x, int y, int z, int margin) {
            return x >= minX - margin && x <= maxX + margin && y >= minY - margin && y <= maxY + margin && z >= minZ - margin && z <= maxZ + margin;
        }
    }

    /** The blocks to place, in the order to place them. */
    public record Chain(List<int[]> blocks) {
        public int size() {
            return blocks.size();
        }
    }

    /**
     * {@code chain} is null when there is none. {@code budgetHit}: the deadline passed before any chain was found. {@code stopped}:
     * the search ended early (deadline or node cap) and may have missed a shorter chain; with a null chain that is not "none
     * exists", and a chain returned that way is a valid one that is just not known to be the shortest.
     */
    public record Result(Chain chain, boolean budgetHit, boolean stopped) {
        public Result(Chain chain, boolean budgetHit) {
            this(chain, budgetHit, budgetHit);
        }
    }

    /** The longest arm of the L probe (kept small: it runs in open air, where nothing ends it early). */
    public static final int MAX_ARM = 48;

    private static boolean inReach(List<Box> areas, int margin, int x, int y, int z) {
        if (margin < 0) return true;
        for (Box b : areas) if (b.near(x, y, z, margin)) return true;
        return false;
    }

    private static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    private static boolean allowed(World w, List<Box> areas, int margin, int x, int y, int z) {
        return w.inHeight(y) && w.air(x, y, z) && !w.wanted(x, y, z) && inReach(areas, margin, x, y, z);
    }

    private static boolean touchesSolid(World w, int x, int y, int z) {
        for (Dir d : Dir.values()) if (w.solid(x + d.dx, y + d.dy, z + d.dz)) return true;
        return false;
    }

    /** A set of longs without boxing (open addressing). */
    private static final class LongSet {
        private static final long EMPTY = Long.MIN_VALUE;
        private long[] table = new long[1024];
        private int size;

        LongSet() {
            Arrays.fill(table, EMPTY);
        }

        /** True when the value was not in the set. */
        boolean add(long v) {
            if (v == EMPTY) v++;
            if (size * 2 >= table.length) grow();
            int mask = table.length - 1;
            int i = (int) ((v * 0x9E3779B97F4A7C15L) >>> 40) & mask;
            while (table[i] != EMPTY) {
                if (table[i] == v) return false;
                i = (i + 1) & mask;
            }
            table[i] = v;
            size++;
            return true;
        }

        private void grow() {
            long[] old = table;
            table = new long[old.length * 2];
            Arrays.fill(table, EMPTY);
            size = 0;
            for (long v : old) if (v != EMPTY) add(v);
        }
    }

    /**
     * The shortest scaffold chain that gives the cell at (tx, ty, tz) a solid neighbour, or none. Among chains of the same
     * length the one whose first block is nearest to the point (px, py, pz) wins, then the lowest position.
     *
     * <p>First the straight columns from the cell's six faces are probed (cheap, any length), then an L (one cell next to the
     * cell and a straight column from it); then a BFS looks for a strictly shorter chain within a node cap. A straight chain
     * wins a tie with a bent one. When the BFS runs out of time or nodes, the straight or L chain is used ({@code stopped}).
     *
     * @param limit longest chain and margin outside the {@code areas}; 0: neither is limited
     * @param maxChain the most blocks the chain may have (the room left)
     * @param deadlineNanos {@code System.nanoTime()} value after which the search gives up (budgetHit)
     */
    public static Result plan(World w, List<Box> areas, int tx, int ty, int tz, int limit, int maxChain, double px, double py, double pz, long deadlineNanos) {
        int margin = limit > 0 ? limit : -1;
        int cap = Math.min(maxChain, limit > 0 ? limit : MAX_PROBE);
        if (cap < 1) return new Result(null, false, false);

        // Straight columns, one per direction
        List<int[]> straight = null;
        double straightD = 0;
        for (Dir d : Dir.values()) {
            for (int k = 1; k <= cap; k++) {
                if (System.nanoTime() > deadlineNanos) return new Result(null, true);
                int x = tx + d.dx * k, y = ty + d.dy * k, z = tz + d.dz * k;
                if (!allowed(w, areas, margin, x, y, z)) break;
                if (!touchesSolid(w, x, y, z)) continue;
                double dx = x + 0.5 - px, dy = y + 0.5 - py, dz = z + 0.5 - pz;
                double dd = dx * dx + dy * dy + dz * dz;
                if (straight == null || k < straight.size() || k == straight.size() && (dd < straightD || dd == straightD && before(new int[] { x, y, z }, straight.get(0)))) {
                    List<int[]> col = new ArrayList<>();
                    for (int j = k; j >= 1; j--) col.add(new int[] { tx + d.dx * j, ty + d.dy * j, tz + d.dz * j });
                    straight = col;
                    straightD = dd;
                }
                break;
            }
        }
        Chain fallback = straight == null ? null : new Chain(straight);
        if (fallback == null) {
            // No straight one: a column from a cell next to the target (an L), also cheap
            Result bent = bentProbe(w, areas, margin, tx, ty, tz, cap, px, py, pz, deadlineNanos);
            if (bent.budgetHit()) return bent;
            fallback = bent.chain();
        }
        int maxDepth = fallback == null ? cap : fallback.size() - 1;
        if (maxDepth < 1) return new Result(fallback, false, false);

        // A shorter bent chain, if there is one
        List<int[]> nodes = new ArrayList<>(); // x, y, z, parent index (toward the cell, -1 at the first ring), depth
        LongSet seen = new LongSet();
        seen.add(key(tx, ty, tz));
        for (Dir d : Dir.values()) {
            int x = tx + d.dx, y = ty + d.dy, z = tz + d.dz;
            if (seen.add(key(x, y, z)) && allowed(w, areas, margin, x, y, z)) nodes.add(new int[] { x, y, z, -1, 1 });
        }
        int from = 0;
        for (int depth = 1; depth <= maxDepth && from < nodes.size(); depth++) {
            int to = nodes.size();
            int bestIdx = -1;
            double bestD = Double.MAX_VALUE;
            for (int i = from; i < to; i++) {
                if (System.nanoTime() > deadlineNanos) return fallback != null ? new Result(fallback, false, true) : new Result(null, true);
                int[] n = nodes.get(i);
                if (!touchesSolid(w, n[0], n[1], n[2])) continue;
                double dx = n[0] + 0.5 - px, dy = n[1] + 0.5 - py, dz = n[2] + 0.5 - pz;
                double dd = dx * dx + dy * dy + dz * dz;
                if (bestIdx < 0 || dd < bestD || dd == bestD && before(n, nodes.get(bestIdx))) {
                    bestIdx = i;
                    bestD = dd;
                }
            }
            if (bestIdx >= 0) return new Result(chainOf(nodes, bestIdx), false, false);
            if (depth == maxDepth) break;
            for (int i = from; i < to; i++) {
                if (System.nanoTime() > deadlineNanos) return fallback != null ? new Result(fallback, false, true) : new Result(null, true);
                int[] n = nodes.get(i);
                for (Dir d : Dir.values()) {
                    int x = n[0] + d.dx, y = n[1] + d.dy, z = n[2] + d.dz;
                    if (seen.add(key(x, y, z)) && allowed(w, areas, margin, x, y, z)) nodes.add(new int[] { x, y, z, i, depth + 1 });
                }
                if (nodes.size() > MAX_NODES) return new Result(fallback, false, true);
            }
            from = to;
        }
        return new Result(fallback, false, false);
    }

    /**
     * No straight chain: one cell next to the target, then a straight column from it (an L). Cheap (arms of at most
     * {@link #MAX_ARM}), finds a detour around a wall of wanted cells; the shortest, then nearest first block, then lowest.
     */
    private static Result bentProbe(World w, List<Box> areas, int margin, int tx, int ty, int tz, int cap, double px, double py, double pz, long deadlineNanos) {
        List<int[]> best = null;
        double bestD = 0;
        int arm = Math.min(cap - 1, MAX_ARM);
        for (Dir f : Dir.values()) {
            int fx = tx + f.dx, fy = ty + f.dy, fz = tz + f.dz;
            if (!allowed(w, areas, margin, fx, fy, fz)) continue;
            for (Dir d : Dir.values()) {
                if (d.dx == -f.dx && d.dy == -f.dy && d.dz == -f.dz || d == f) continue;
                for (int k = 1; k <= arm; k++) {
                    if (System.nanoTime() > deadlineNanos) return new Result(null, true);
                    int x = fx + d.dx * k, y = fy + d.dy * k, z = fz + d.dz * k;
                    if (!allowed(w, areas, margin, x, y, z)) break;
                    if (!touchesSolid(w, x, y, z)) continue;
                    double dx = x + 0.5 - px, dy = y + 0.5 - py, dz = z + 0.5 - pz;
                    double dd = dx * dx + dy * dy + dz * dz;
                    if (best == null || k + 1 < best.size() || k + 1 == best.size() && (dd < bestD || dd == bestD && before(new int[] { x, y, z }, best.get(0)))) {
                        List<int[]> col = new ArrayList<>();
                        for (int j = k; j >= 1; j--) col.add(new int[] { fx + d.dx * j, fy + d.dy * j, fz + d.dz * j });
                        col.add(new int[] { fx, fy, fz });
                        best = col;
                        bestD = dd;
                    }
                    break;
                }
            }
        }
        return new Result(best == null ? null : new Chain(best), false, false);
    }

    private static boolean before(int[] a, int[] b) {
        if (a[1] != b[1]) return a[1] < b[1];
        if (a[0] != b[0]) return a[0] < b[0];
        return a[2] < b[2];
    }

    private static Chain chainOf(List<int[]> nodes, int last) {
        List<int[]> out = new ArrayList<>();
        for (int i = last; i >= 0; i = nodes.get(i)[3]) {
            int[] n = nodes.get(i);
            out.add(new int[] { n[0], n[1], n[2] });
        }
        return new Chain(out); // the first block (the one next to the existing solid) first
    }

    // Removal

    /**
     * The scaffold blocks that no open cell needs any more. A block is needed when an open SOLID cell next to it has no other
     * support than scaffold (nothing placed or natural next to it) and air-placing is off; when an open cell hangs on it or
     * stands on it; and, repeatedly, when a needed scaffold block has no support but scaffold and this one is next to it.
     *
     * @param scaffold every scaffold position (x, y, z), placed or only planned
     */
    public static List<int[]> removable(World w, List<int[]> scaffold, boolean airplace) {
        Map<Long, int[]> all = new HashMap<>();
        for (int[] p : scaffold) all.put(key(p[0], p[1], p[2]), p);
        Set<Long> needed = new HashSet<>();
        ArrayDeque<int[]> work = new ArrayDeque<>();
        for (int[] b : scaffold) {
            for (Dir d : Dir.values()) {
                Cell c = w.open(b[0] + d.dx, b[1] + d.dy, b[2] + d.dz);
                if (c == null || !dependsOn(w, all, c, b, airplace)) continue;
                if (needed.add(key(b[0], b[1], b[2]))) work.add(b);
                break;
            }
        }
        while (!work.isEmpty()) {
            int[] a = work.poll();
            // A needed block that rests on scaffold only keeps all the scaffold around it
            if (hasNaturalSupport(w, all, a[0], a[1], a[2])) continue;
            for (Dir d : Dir.values()) {
                int[] b = all.get(key(a[0] + d.dx, a[1] + d.dy, a[2] + d.dz));
                if (b != null && needed.add(key(b[0], b[1], b[2]))) work.add(b);
            }
        }
        List<int[]> out = new ArrayList<>();
        for (int[] p : scaffold) if (!needed.contains(key(p[0], p[1], p[2]))) out.add(p);
        return out;
    }

    private static boolean hasNaturalSupport(World w, Map<Long, int[]> scaffold, int x, int y, int z) {
        for (Dir d : Dir.values()) {
            int nx = x + d.dx, ny = y + d.dy, nz = z + d.dz;
            if (!scaffold.containsKey(key(nx, ny, nz)) && w.solid(nx, ny, nz)) return true;
        }
        return false;
    }

    private static boolean dependsOn(World w, Map<Long, int[]> scaffold, Cell c, int[] b, boolean airplace) {
        switch (c.role()) {
            case SOLID:
                return !airplace && !hasNaturalSupport(w, scaffold, c.x(), c.y(), c.z());
            case ATTACHABLE:
                return c.support() != null && c.x() + c.support().dx == b[0] && c.y() + c.support().dy == b[1] && c.z() + c.support().dz == b[2];
            case GRAVITY:
                return c.x() == b[0] && c.y() - 1 == b[1] && c.z() == b[2];
            default:
                return false;
        }
    }

    /** Only a SOLID cell is helped by scaffolding. */
    public static boolean helps(Cell c) {
        return c.role() == Role.SOLID;
    }
}
