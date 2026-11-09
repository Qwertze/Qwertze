package com.porter.qwerte.printer.place;

import com.porter.qwerte.printer.place.PlaceRecipe.Dir;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;

/**
 * Decides what to place next. Pure and deterministic: the same cells and the same world give the same sequence,
 * whatever order the cells were added in.
 *
 * <ul>
 *   <li>Supports first: a SOLID cell is ready when a neighbour is solid (world or already placed), or at once when
 *       air-placing is allowed. An ATTACHABLE cell (torch, carpet, rail, button, plant, ...) waits for the cell it
 *       hangs on. A GRAVITY cell waits for a solid cell below.</li>
 *   <li>A two-part block needs both cells free.</li>
 *   <li>Never a cell (or partner cell) that overlaps the player's box.</li>
 *   <li>A cell that is not free in the world is not offered; the caller decides what a wrong or finished cell means.</li>
 *   <li>Cells live in 16x16x16 section buckets, each with 16 layer lists (no sorting anywhere: the comparator is a total
 *       order, so insertion order never shows); a pick only visits sections within reach of the player.</li>
 * </ul>
 * <p>Active layer: with {@link #setActiveLayer} set, only the cells of that y are offered and every other section is skipped
 * without being read. Scaffold cells ({@link #addUngated}) are the exception: they are prerequisites of the active layer,
 * wherever they are, and come first.
 *
 * Ties: layer (y ascending when bottom-up), then distance (in half-block steps), then look compatibility with the
 * previous pick, then position (y, x, z), so there is always one answer.
 */
public final class BuildOrderer {
    public enum Role { SOLID, ATTACHABLE, GRAVITY }

    public enum Mode { LAYER_THEN_DISTANCE, DISTANCE, LAYER }

    /**
     * One cell to build.
     *
     * @param support offset to the cell an ATTACHABLE needs solid; null for the others
     * @param partner offset to the second cell of a two-part block; null otherwise
     * @param look    the look the chosen recipe needs, or null; used for the look-compatibility tie break
     */
    public record Cell(int x, int y, int z, Role role, Dir support, Dir partner, LookWindow look) {
        public static Cell of(int x, int y, int z, StateSpec spec, LookWindow look) {
            return new Cell(x, y, z, spec.role(), spec.supportDir(), spec.partnerDir(), look);
        }

        public Cell withLook(LookWindow w) {
            return new Cell(x, y, z, role, support, partner, w);
        }
    }

    /** What the world has right now (including blocks the caller has already placed that have not been acknowledged). */
    public interface World {
        /** A block that can be clicked and that holds things up. */
        boolean solid(int x, int y, int z);

        /** Nothing here, or something a placement replaces. */
        boolean free(int x, int y, int z);

        /** The chunk with this block is loaded; a section that is not is skipped without reading its cells. */
        default boolean loaded(int x, int y, int z) {
            return true;
        }
    }

    /** The player's bounding box. */
    public record Box(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        public static final Box NONE = new Box(0, 0, 0, 0, 0, 0);

        boolean overlapsCell(int x, int y, int z) {
            return maxX > minX && x + 1 > minX && x < maxX && y + 1 > minY && y < maxY && z + 1 > minZ && z < maxZ;
        }
    }

    private final Mode mode;
    private final boolean bottomUp;
    private final boolean airplace;
    private final TreeMap<Long, Section> sorted = new TreeMap<>(); // section buckets, ordered by section y, x, z
    private final Set<Long> placedSolid = new HashSet<>();
    private int pending;
    private long clock;
    private Integer gate;
    private final Set<Long> ungatedKeys = new HashSet<>();
    private final Map<Long, Cell> ungated = new LinkedHashMap<>();
    private int idleTicks;
    private int workCap = Integer.MAX_VALUE;
    private boolean truncated;

    private static final Comparator<Cell> POSITION = Comparator.comparingInt(Cell::y).thenComparingInt(Cell::x).thenComparingInt(Cell::z);

    /** A 16x16x16 bucket: its cells in 16 lists by layer (y & 15), unsorted, and one bit per cell. */
    private static final class Section {
        final int sx, sy, sz;
        @SuppressWarnings("unchecked")
        final List<Cell>[] slices = new List[16];
        final long[] bits = new long[64];
        int count;
        long idleUntil;

        Section(int sx, int sy, int sz) {
            this.sx = sx;
            this.sy = sy;
            this.sz = sz;
        }

        static int bit(int x, int y, int z) {
            return ((y & 15) << 8) | ((z & 15) << 4) | (x & 15);
        }
    }

    public BuildOrderer(Mode mode, boolean bottomUp, boolean airplace) {
        this.mode = mode;
        this.bottomUp = bottomUp;
        this.airplace = airplace;
    }

    public int pending() {
        return pending;
    }

    /** Only the cells of this y are offered (null: every layer). */
    public void setActiveLayer(Integer y) {
        if (Objects.equals(gate, y)) return;
        gate = y;
        for (Section s : sorted.values()) s.idleUntil = 0;
    }

    public Integer activeLayer() {
        return gate;
    }

    /**
     * Adds a cell that is offered whatever the active layer is, and before the cells of the layer (a scaffold block). Later
     * {@link #add}, {@link #remove} and {@link #placed} calls for the same position act on it. Not counted by {@link #pending}.
     */
    public boolean addUngated(Cell c) {
        long k = pos(c.x, c.y, c.z);
        if (!ungatedKeys.add(k)) return false;
        ungated.put(k, c);
        return true;
    }

    /** The position is no longer a scaffold cell: it leaves the queue and the registration. */
    public void releaseUngated(int x, int y, int z) {
        long k = pos(x, y, z);
        ungatedKeys.remove(k);
        ungated.remove(k);
    }

    public boolean isUngated(int x, int y, int z) {
        return ungatedKeys.contains(pos(x, y, z));
    }

    public int ungatedQueued() {
        return ungated.size();
    }

    /** Advances the clock the idle cache counts in (one call per game tick). */
    public void advance() {
        clock++;
    }

    /**
     * Sections that were searched completely, without a reach limit, and had no ready cell are not searched again for this
     * many ticks (0 turns the cache off, the default).
     */
    public void setIdleCache(int ticks) {
        this.idleTicks = Math.max(0, ticks);
    }

    /** At most this many cells are looked at per search (the rest is left for a later call); unlimited by default. */
    public void setWorkCap(int cells) {
        this.workCap = cells <= 0 ? Integer.MAX_VALUE : cells;
    }

    /** The last search stopped at the work cap before it had looked everywhere. */
    public boolean truncated() {
        return truncated;
    }

    /** Adds a cell; a second cell at the same position is ignored. Returns whether it was added. */
    public boolean add(Cell c) {
        long uk = pos(c.x, c.y, c.z);
        if (ungatedKeys.contains(uk)) return ungated.put(uk, c) == null;
        long key = sectionKey(c.x >> 4, c.y >> 4, c.z >> 4);
        Section s = sorted.get(key);
        int b = Section.bit(c.x, c.y, c.z);
        long m = 1L << (b & 63);
        if (s != null && (s.bits[b >> 6] & m) != 0) return false;
        if (s == null) {
            s = new Section(c.x >> 4, c.y >> 4, c.z >> 4);
            sorted.put(key, s);
        }
        s.bits[b >> 6] |= m;
        List<Cell> slice = s.slices[c.y & 15];
        if (slice == null) s.slices[c.y & 15] = slice = new ArrayList<>();
        slice.add(c);
        s.count++;
        s.idleUntil = 0;
        pending++;
        return true;
    }

    /** The cell was placed: it leaves the queue, and if it is solid it now counts as a support. */
    public void placed(Cell c) {
        if (drop(c) && (c.role == Role.SOLID || c.role == Role.GRAVITY)) placedSolid.add(pos(c.x, c.y, c.z));
        wake(c.x, c.y, c.z);
    }

    /** A block appeared or went: the sections around it may have ready cells now, whatever the idle cache says. */
    public void wake(int x, int y, int z) {
        if (idleTicks == 0) return;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    Section s = sorted.get(sectionKey((x >> 4) + dx, (y >> 4) + dy, (z >> 4) + dz));
                    if (s != null) s.idleUntil = 0;
                }
            }
        }
    }

    /** A placement did not hold (failed, retried, or never happened): the cell no longer counts as a support. */
    public void unplaced(int x, int y, int z) {
        placedSolid.remove(pos(x, y, z));
    }

    /** The cell leaves the queue without having been built (skipped, failed). */
    public boolean remove(Cell c) {
        return drop(c);
    }

    public boolean isPlacedSolid(int x, int y, int z) {
        return placedSolid.contains(pos(x, y, z));
    }

    private boolean drop(Cell c) {
        long uk = pos(c.x, c.y, c.z);
        if (ungatedKeys.contains(uk)) return ungated.remove(uk) != null;
        long key = sectionKey(c.x >> 4, c.y >> 4, c.z >> 4);
        Section s = sorted.get(key);
        if (s == null) return false;
        int b = Section.bit(c.x, c.y, c.z);
        long m = 1L << (b & 63);
        if ((s.bits[b >> 6] & m) == 0) return false;
        List<Cell> slice = s.slices[c.y & 15];
        if (slice == null) return false;
        for (int i = 0; i < slice.size(); i++) {
            Cell o = slice.get(i);
            if (o.x == c.x && o.z == c.z) {
                int last = slice.size() - 1;
                slice.set(i, slice.get(last));
                slice.remove(last);
                s.bits[b >> 6] &= ~m;
                s.count--;
                pending--;
                if (s.count == 0) sorted.remove(key);
                return true;
            }
        }
        return false;
    }

    /** The best ready cell within reach of the player, or null. */
    public Cell pick(double px, double py, double pz, double reach, Box box, World world, LookWindow prev) {
        List<Cell> top = top(1, px, py, pz, reach, box, world, prev);
        return top.isEmpty() ? null : top.get(0);
    }

    /** The best ready cell anywhere (to decide where to walk), or null. */
    public Cell nearest(double px, double py, double pz, Box box, World world, LookWindow prev) {
        List<Cell> top = top(1, px, py, pz, Double.POSITIVE_INFINITY, box, world, prev);
        return top.isEmpty() ? null : top.get(0);
    }

    /** The best {@code n} ready cells within reach, best first. */
    public List<Cell> top(int n, double px, double py, double pz, double reach, Box box, World world, LookWindow prev) {
        return top(n, px, py, pz, reach, box, world, prev, null);
    }

    /**
     * Like the above, but a ready cell the {@code accept} test turns down does not take one of the {@code n} places (the
     * caller's test for what the orderer can't see, such as materials). Null accepts every cell.
     */
    public List<Cell> top(int n, double px, double py, double pz, double reach, Box box, World world, LookWindow prev, Predicate<Cell> accept) {
        truncated = false;
        List<Cell> best = new ArrayList<>(Math.max(1, n) + 1);
        if (n <= 0 || pending == 0 && ungated.isEmpty()) return best;
        if (!ungated.isEmpty()) {
            // Scaffold cells first: the layer's cells may be waiting for them
            Comparator<Cell> cmp = comparator(px, py, pz, prev);
            double reach2 = reach * reach;
            for (Cell c : ungated.values()) {
                if (reach != Double.POSITIVE_INFINITY) {
                    double dx = c.x + 0.5 - px, dy = c.y + 0.5 - py, dz = c.z + 0.5 - pz;
                    if (dx * dx + dy * dy + dz * dz > reach2) continue;
                }
                if (!world.loaded(c.x, c.y, c.z) || !ready(c, box, world)) continue;
                if (accept != null && !accept.test(c)) continue;
                insert(best, c, n, cmp);
            }
            if (best.size() >= n) return best;
        }
        if (pending == 0) return best;
        best.addAll(layered(n - best.size(), px, py, pz, reach, box, world, prev, accept));
        return best;
    }

    private List<Cell> layered(int n, double px, double py, double pz, double reach, Box box, World world, LookWindow prev, Predicate<Cell> accept) {
        List<Cell> best = new ArrayList<>(Math.max(1, n) + 1);
        Comparator<Cell> cmp = comparator(px, py, pz, prev);
        double reach2 = reach * reach;
        boolean limited = reach != Double.POSITIVE_INFINITY;
        boolean layerFirst = mode != Mode.DISTANCE;
        Integer g = gate;
        int work = 0;
        Collection<Section> visit = g == null ? sorted.values()
            : sorted.subMap(sectionKey(-2097152, g >> 4, -2097152), true, sectionKey(2097151, g >> 4, 2097151), true).values();
        for (Section s : visit) {
            if (limited && sectionDist2(s, px, py, pz) > reach2) continue;
            if (!limited && s.idleUntil > clock) continue;
            if (!world.loaded(s.sx << 4, s.sy << 4, s.sz << 4)) continue;
            // A section looked at through the gate was not looked at completely: it is never cached as idle
            boolean complete = g == null, found = false;
            for (int k = 0; k < 16; k++) {
                int idx = bottomUp ? k : 15 - k;
                if (g != null && idx != (g & 15)) continue;
                List<Cell> slice = s.slices[idx];
                if (slice == null || slice.isEmpty()) continue;
                if (layerFirst && best.size() >= n && layerKey(slice.get(0).y) > layerKey(best.get(best.size() - 1).y)) {
                    complete = false;
                    break; // the layers come in order: the rest of this section is worse too
                }
                for (Cell c : slice) {
                    if (++work > workCap) {
                        truncated = true;
                        return best;
                    }
                    if (limited) {
                        double dx = c.x + 0.5 - px, dy = c.y + 0.5 - py, dz = c.z + 0.5 - pz;
                        if (dx * dx + dy * dy + dz * dz > reach2) {
                            complete = false;
                            continue;
                        }
                    }
                    if (!ready(c, box, world)) continue;
                    found = true;
                    if (accept != null && !accept.test(c)) continue;
                    insert(best, c, n, cmp);
                }
            }
            if (!found && complete && !limited && idleTicks > 0) s.idleUntil = clock + idleTicks;
        }
        return best;
    }

    private Collection<Section> layerSections(int y) {
        return sorted.subMap(sectionKey(-2097152, y >> 4, -2097152), true, sectionKey(2097151, y >> 4, 2097151), true).values();
    }

    /**
     * Up to {@code max} cells of one layer that can't be placed yet because nothing solid is next to them (air-placing is off):
     * the cells that scaffolding could help. Solid cells only, free in the world, nearest to the point first. Looks at no more
     * than {@code cap} cells.
     */
    public List<Cell> stranded(int y, int max, int cap, double px, double py, double pz, Box box, World world) {
        return stranded(y, max, cap, px, py, pz, box, world, c -> false);
    }

    /** As above, but the cells {@code skip} says yes to are left out (they do not take one of the {@code max} places). */
    public List<Cell> stranded(int y, int max, int cap, double px, double py, double pz, Box box, World world, Predicate<Cell> skip) {
        List<Cell> out = new ArrayList<>();
        if (airplace || max <= 0) return out;
        int work = 0;
        Comparator<Cell> near = Comparator.<Cell>comparingDouble(c -> dist2(c, px, py, pz)).thenComparing(POSITION);
        for (Section sec : layerSections(y)) {
            List<Cell> slice = sec.slices[y & 15];
            if (slice == null) continue;
            for (Cell c : slice) {
                if (++work > cap) return out;
                if (c.role != Role.SOLID || !world.loaded(c.x, c.y, c.z) || !world.free(c.x, c.y, c.z) || ready(c, null, world) || skip.test(c)) continue;
                insert(out, c, max, near);
            }
        }
        return out;
    }

    private static double dist2(Cell c, double px, double py, double pz) {
        double dx = c.x + 0.5 - px, dy = c.y + 0.5 - py, dz = c.z + 0.5 - pz;
        return dx * dx + dy * dy + dz * dz;
    }

    /** How many cells of this layer are ready now (looks at no more than {@code cap} of them). */
    public int readyCount(int y, int cap, Box box, World world) {
        int n = 0, work = 0;
        for (Section sec : layerSections(y)) {
            List<Cell> slice = sec.slices[y & 15];
            if (slice == null || !world.loaded(sec.sx << 4, sec.sy << 4, sec.sz << 4)) continue;
            for (Cell c : slice) {
                if (++work > cap) return n;
                if (ready(c, box, world)) n++;
            }
        }
        return n;
    }

    private void insert(List<Cell> best, Cell c, int n, Comparator<Cell> cmp) {
        int i = best.size();
        while (i > 0 && cmp.compare(c, best.get(i - 1)) < 0) i--;
        if (i >= n) return;
        best.add(i, c);
        if (best.size() > n) best.remove(best.size() - 1);
    }

    private int layerKey(int y) {
        return bottomUp ? y : -y;
    }

    private Comparator<Cell> comparator(double px, double py, double pz, LookWindow prev) {
        Comparator<Cell> layer = Comparator.comparingInt(c -> layerKey(c.y));
        Comparator<Cell> dist = Comparator.comparingInt(c -> {
            double dx = c.x + 0.5 - px, dy = c.y + 0.5 - py, dz = c.z + 0.5 - pz;
            return (int) Math.floor(Math.sqrt(dx * dx + dy * dy + dz * dz) * 2);
        });
        Comparator<Cell> compat = Comparator.comparingInt(c -> compatible(c, prev) ? 0 : 1);
        return switch (mode) {
            case LAYER_THEN_DISTANCE -> layer.thenComparing(dist).thenComparing(compat).thenComparing(POSITION);
            case DISTANCE -> dist.thenComparing(compat).thenComparing(POSITION);
            case LAYER -> layer.thenComparing(compat).thenComparing(POSITION);
        };
    }

    private static boolean compatible(Cell c, LookWindow prev) {
        return prev == null || c.look == null || c.look.intersects(prev);
    }

    private static double sectionDist2(Section s, double px, double py, double pz) {
        double minX = s.sx << 4, minY = s.sy << 4, minZ = s.sz << 4;
        double dx = Math.max(Math.max(minX - px, 0), px - (minX + 16));
        double dy = Math.max(Math.max(minY - py, 0), py - (minY + 16));
        double dz = Math.max(Math.max(minZ - pz, 0), pz - (minZ + 16));
        return dx * dx + dy * dy + dz * dz;
    }

    /** True when the cell could be placed right now. */
    public boolean ready(Cell c, Box box, World world) {
        if (box != null) {
            if (box.overlapsCell(c.x, c.y, c.z)) return false;
            if (c.partner != null && box.overlapsCell(c.x + c.partner.dx, c.y + c.partner.dy, c.z + c.partner.dz)) return false;
        }
        if (!world.free(c.x, c.y, c.z)) return false;
        if (c.partner != null && !world.free(c.x + c.partner.dx, c.y + c.partner.dy, c.z + c.partner.dz)) return false;
        switch (c.role) {
            case SOLID:
                if (airplace) return true;
                for (Dir d : Dir.values()) if (solidAt(world, c.x + d.dx, c.y + d.dy, c.z + d.dz)) return true;
                return false;
            case ATTACHABLE:
                return c.support == null ? airplace : solidAt(world, c.x + c.support.dx, c.y + c.support.dy, c.z + c.support.dz);
            case GRAVITY:
                return solidAt(world, c.x, c.y - 1, c.z);
            default:
                return false;
        }
    }

    private boolean solidAt(World w, int x, int y, int z) {
        return placedSolid.contains(pos(x, y, z)) || w.solid(x, y, z);
    }

    /** Every pending cell, sorted by position, for reports of what could not be built. */
    public List<Cell> snapshot() {
        List<Cell> all = new ArrayList<>(pending);
        for (Section s : sorted.values()) for (List<Cell> slice : s.slices) if (slice != null) all.addAll(slice);
        all.sort(POSITION);
        return all;
    }

    private static long pos(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    /** Orders by section y, then x, then z. */
    private static long sectionKey(int sx, int sy, int sz) {
        return ((long) (sy + 2048) << 44) | ((long) (sx + 2097152) << 22) | (long) (sz + 2097152);
    }
}
