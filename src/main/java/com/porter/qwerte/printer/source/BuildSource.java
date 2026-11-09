package com.porter.qwerte.printer.source;

import com.porter.qwerte.printer.schematic.Transform;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * What the printer builds: a set of regions placed in the world, scanned in budgeted slices and looked up by position.
 * States handed out are already mirrored and rotated for the placement and are never air. Client thread only.
 */
public interface BuildSource {
    /** A short description for chat, such as the file or placement name. */
    String label();

    /** How the source was obtained: "file", "litematica", "litematica+file", ... */
    String mode();

    /** Warnings from loading (a newer file version, a fallback that was taken), to show once. */
    List<String> notes();

    /** The world box of each region. */
    List<Area> areas();

    /** Cells across all regions, air included. */
    long volume();

    Cursor start();

    /**
     * Resolves palette entries into block states (the data fixer and registry lookups, which are slow the first time)
     * for at most {@code budgetNanos}, and returns true once everything is resolved. Pass 3 calls this once per tick
     * on the client thread until it returns true; {@link #scan} does the same itself and reports "not finished" until
     * it is. {@link #stateAt} resolves a single entry on demand, so it is correct either way but not budgeted.
     */
    boolean resolveSome(long budgetNanos);

    /**
     * Continues a scan: hands every non-air, buildable cell to the sink until the time budget is spent. Returns true
     * when every cell has been seen.
     */
    boolean scan(Cursor cursor, long budgetNanos, Sink sink);

    /**
     * The state the schematic wants at a world position (air included), or null where the schematic has nothing
     * (outside every region, a structure_void, an unknown block).
     */
    BlockState stateAt(BlockPos pos);

    /** Blocks that could not be resolved, and palette indexes that were bad, with how often they were hit. */
    Map<String, Integer> problems();

    record Area(String name, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        public boolean contains(int x, int y, int z) {
            return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
        }
    }

    interface Sink {
        void accept(int x, int y, int z, BlockState state);
    }

    /** Where a scan has got to. */
    final class Cursor {
        int region;
        long cell;
    }

    /** Names of things that could not be used, counted; at most {@link #MAX} distinct. */
    final class Problems {
        public static final int MAX = 50;
        private final Map<String, Integer> map = new LinkedHashMap<>();

        public void add(String what) {
            if (map.containsKey(what) || map.size() < MAX) map.merge(what, 1, Integer::sum);
        }

        public Map<String, Integer> view() {
            return java.util.Collections.unmodifiableMap(map);
        }
    }

    /** Where cells go in the world; the Litematica fallback swaps the forward direction for Litematica's own. */
    interface Mapper {
        int[] toWorld(int cx, int cy, int cz);

        /** Like {@link #toWorld(int, int, int)} into a caller-owned array (no allocation per cell). */
        default void toWorld(int cx, int cy, int cz, int[] out) {
            int[] w = toWorld(cx, cy, cz);
            out[0] = w[0];
            out[1] = w[1];
            out[2] = w[2];
        }

        /** The cell at a world position, not bounds-checked; null when this mapper can't invert. */
        int[] toLocal(int wx, int wy, int wz);

        static Mapper of(Transform t) {
            return new Mapper() {
                @Override
                public int[] toWorld(int cx, int cy, int cz) {
                    return t.toWorld(cx, cy, cz);
                }

                @Override
                public int[] toLocal(int wx, int wy, int wz) {
                    return t.toLocal(wx, wy, wz);
                }
            };
        }
    }

    /** One region of a placed source: its size, its mapping to the world and its (already transformed) states. */
    abstract class PlacedRegion {
        final String name;
        final int sizeX, sizeY, sizeZ;
        final Mapper mapper;

        protected PlacedRegion(String name, int sizeX, int sizeY, int sizeZ, Mapper mapper) {
            this.name = name;
            this.sizeX = sizeX;
            this.sizeY = sizeY;
            this.sizeZ = sizeZ;
            this.mapper = mapper;
        }

        /** The state of a cell for the placement (air included), or null to skip it (unknown, structure_void, bad data). */
        protected abstract BlockState cell(int x, int y, int z);

        long volume() {
            return (long) sizeX * sizeY * sizeZ;
        }

        /** Resolves what it can before {@code deadlineNanos} (a nanoTime value); true when nothing is left. */
        protected boolean resolveSome(long deadlineNanos) {
            return true;
        }

        Area area() {
            int[] lo = { Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE };
            int[] hi = { Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE };
            for (int i = 0; i < 8; i++) {
                int[] w = mapper.toWorld((i & 1) == 0 ? 0 : sizeX - 1, (i & 2) == 0 ? 0 : sizeY - 1, (i & 4) == 0 ? 0 : sizeZ - 1);
                for (int a = 0; a < 3; a++) {
                    lo[a] = Math.min(lo[a], w[a]);
                    hi[a] = Math.max(hi[a], w[a]);
                }
            }
            return new Area(name, lo[0], lo[1], lo[2], hi[0], hi[1], hi[2]);
        }
    }

    /** A source made of {@link PlacedRegion}s. */
    final class Placed implements BuildSource {
        private final String label;
        private final String mode;
        private final List<String> notes;
        private final List<PlacedRegion> regions;
        private final List<Area> areas = new ArrayList<>();
        private final Problems problems;
        private final long volume;

        public Placed(String label, String mode, List<String> notes, List<PlacedRegion> regions, Problems problems) {
            this.label = label;
            this.mode = mode;
            this.notes = List.copyOf(notes);
            this.regions = List.copyOf(regions);
            this.problems = problems;
            long v = 0;
            for (PlacedRegion r : regions) {
                areas.add(r.area());
                v += r.volume();
            }
            this.volume = v;
        }

        @Override
        public String label() {
            return label;
        }

        @Override
        public String mode() {
            return mode;
        }

        @Override
        public List<String> notes() {
            return notes;
        }

        @Override
        public List<Area> areas() {
            return java.util.Collections.unmodifiableList(areas);
        }

        @Override
        public long volume() {
            return volume;
        }

        @Override
        public Cursor start() {
            return new Cursor();
        }

        private int resolving;

        @Override
        public boolean resolveSome(long budgetNanos) {
            long end = System.nanoTime() + budgetNanos;
            while (resolving < regions.size()) {
                if (!regions.get(resolving).resolveSome(end)) return false;
                resolving++;
            }
            return true;
        }

        @Override
        public boolean scan(Cursor c, long budgetNanos, Sink sink) {
            long end = System.nanoTime() + budgetNanos;
            if (!resolveSome(budgetNanos)) return false;
            while (c.region < regions.size()) {
                PlacedRegion r = regions.get(c.region);
                long vol = r.volume();
                long layer = (long) r.sizeX * r.sizeZ;
                // Coordinates are stepped, not divided out of the index for every cell
                int x = (int) (c.cell % r.sizeX);
                int z = (int) ((c.cell / r.sizeX) % r.sizeZ);
                int y = (int) (c.cell / layer);
                int[] w = new int[3];
                while (c.cell < vol) {
                    if ((c.cell & 63) == 0 && System.nanoTime() - end > 0) return false;
                    c.cell++;
                    BlockState s = r.cell(x, y, z);
                    if (s != null && !s.isAir()) {
                        r.mapper.toWorld(x, y, z, w);
                        sink.accept(w[0], w[1], w[2], s);
                    }
                    if (++x == r.sizeX) {
                        x = 0;
                        if (++z == r.sizeZ) {
                            z = 0;
                            y++;
                        }
                    }
                }
                c.region++;
                c.cell = 0;
            }
            return true;
        }

        @Override
        public BlockState stateAt(BlockPos pos) {
            int x = pos.getX(), y = pos.getY(), z = pos.getZ();
            BlockState air = null;
            for (int i = 0; i < regions.size(); i++) {
                if (!areas.get(i).contains(x, y, z)) continue;
                PlacedRegion r = regions.get(i);
                int[] l = r.mapper.toLocal(x, y, z);
                if (l == null || l[0] < 0 || l[1] < 0 || l[2] < 0 || l[0] >= r.sizeX || l[1] >= r.sizeY || l[2] >= r.sizeZ) continue;
                BlockState s = r.cell(l[0], l[1], l[2]);
                if (s == null) continue;
                if (!s.isAir()) return s;
                air = s; // an overlapping region that builds something wins over one that is air here
            }
            return air;
        }

        @Override
        public Map<String, Integer> problems() {
            return problems.view();
        }
    }
}
