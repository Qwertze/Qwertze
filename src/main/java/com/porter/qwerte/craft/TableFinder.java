package com.porter.qwerte.craft;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Which crafting table crafting uses, without the user having to set one: the one selected by hand, then a remembered one
 * (found, placed or crafted earlier), then a placed table found in the stash area, then a table taken from the stash and
 * placed, then one crafted in the 2x2 inventory grid (from 4 planks, or logs made into planks first) and placed. When none
 * of that works crafting fails with {@link #NONE}, and the order goes on exactly as it does without a table. Pure: the
 * world comes in through {@link World}, so the choices are tested without the game. The same order of preference as
 * {@link AnvilFinder}, and as the proxy plugin.
 */
public final class TableFinder {
    private TableFinder() {}

    /** The reason crafting is refused when no table can be had. */
    public static final String NONE = "no crafting table available";

    /** The cause when nothing can be made into a table: no table placed, none in the stash, no planks or logs. */
    public static final String NO_MATERIAL = "no planks or logs in the stash";

    /** {@link #NONE} with the real cause when it is known: "no crafting table available: <cause>". */
    public static String failure(String cause) {
        return cause == null || cause.isBlank() ? NONE : NONE + ": " + cause;
    }

    public static final String TABLE_ID = "minecraft:crafting_table";

    /** The area searched for a placed table is at most this many blocks across (x and z), around the bot. */
    public static final int SEARCH_SPAN = 128;
    /** A search is made at most this often. */
    public static final long SEARCH_EVERY_MS = 10_000L;

    /** Where the table in use came from: set by hand, found placed, placed from the stash, or crafted and placed. */
    public enum Source {
        NONE("none"), SET("set"), FOUND("found"), PLACED("placed"), CRAFTED("crafted");

        public final String label;

        Source(String label) {
            this.label = label;
        }

        public static Source of(String label) {
            for (Source s : values()) if (s.label.equals(label)) return s;
            return NONE;
        }
    }

    /** What a table can be crafted from. */
    public enum Material { NONE, PLANKS, LOGS }

    public record Pos(int x, int y, int z) {
        public double distanceSq(Pos o) {
            double dx = x - o.x, dy = y - o.y, dz = z - o.z;
            return dx * dx + dy * dy + dz * dz;
        }
    }

    /** The table position the stash remembers, and how it got there. */
    public record Memory(Pos pos, Source source) {}

    public interface World {
        /** The block at the position is a crafting table; an unloaded chunk counts as one, nothing is forgotten on a guess. */
        boolean isTable(Pos p);

        /** The nearest placed table in the stash area that the bot can stand next to, or null. */
        Pos search();

        /** A crafting table item the stash can give: not reserved for an order line. */
        boolean stockHasTable();

        /** What a table could be crafted from (the stash's planks, or logs), or NONE. */
        Material craftMaterial();
    }

    public enum Step { USE, FOUND, PLACE_FROM_STOCK, CRAFT, FAIL }

    /**
     * @param forget   the remembered position is gone and is to be dropped
     * @param material for CRAFT: what the table is made from
     */
    public record Decision(Step step, Pos pos, Source source, String failure, boolean forget, Material material) {}

    public static Decision decide(Memory memory, World world) {
        boolean forget = false;
        if (memory != null && memory.pos() != null) {
            if (world.isTable(memory.pos())) return new Decision(Step.USE, memory.pos(), memory.source() == Source.NONE ? Source.SET : memory.source(), null, false, Material.NONE);
            // The table that was set or remembered is gone or broken: forgotten, then looked for again
            forget = true;
        }
        Pos found = world.search();
        if (found != null) return new Decision(Step.FOUND, found, Source.FOUND, null, forget, Material.NONE);
        if (world.stockHasTable()) return new Decision(Step.PLACE_FROM_STOCK, null, Source.PLACED, null, forget, Material.NONE);
        Material made = world.craftMaterial();
        if (made != Material.NONE) return new Decision(Step.CRAFT, null, Source.CRAFTED, null, forget, made);
        return new Decision(Step.FAIL, null, Source.NONE, failure(NO_MATERIAL), forget, Material.NONE);
    }

    /** The nearest of these table cells to {@code from} that pass the test (inside the area, a spot to stand next to); null: nothing. */
    public static Pos nearest(List<Pos> tables, Pos from, Predicate<Pos> usable) {
        List<Pos> ok = new ArrayList<>();
        for (Pos p : tables) if (usable.test(p)) ok.add(p);
        ok.sort(Comparator.comparingDouble(from::distanceSq).thenComparingInt(Pos::x).thenComparingInt(Pos::y).thenComparingInt(Pos::z));
        return ok.isEmpty() ? null : ok.get(0);
    }

    /** The part of [lo, hi] searched: all of it when it is at most {@link #SEARCH_SPAN} across, else that many blocks around {@code center}. */
    public static int[] span(int lo, int hi, int center) {
        if (hi - lo + 1 <= SEARCH_SPAN) return new int[] { lo, hi };
        int from = Math.max(lo, center - SEARCH_SPAN / 2), to = Math.min(hi, from + SEARCH_SPAN - 1);
        from = Math.max(lo, to - SEARCH_SPAN + 1);
        return new int[] { from, to };
    }

    /**
     * What a crafting table can be made from out of this pool: planks (a table is one craft in the 2x2 grid) or logs (planks
     * first), by the recipes of the game version. Any wood type. The item on the never-use list are not used.
     */
    public static Material material(Recipes recipes, Map<String, Long> pool, Set<String> never) {
        if (recipes == null) return Material.NONE;
        CraftPlanner.Result r = CraftPlanner.plan(recipes, TABLE_ID, 1, pool, never, CraftPlanner.Caps.NONE, 0, 0);
        if (!r.ok() || r.plan().count() < 1) return Material.NONE;
        // Only recipes that fit the 2x2 grid are used
        for (CraftPlanner.Step s : r.plan().steps()) if (s.recipe().w() > 2 || s.recipe().h() > 2 || s.recipe().cells().size() > 4) return Material.NONE;
        return r.plan().steps().size() <= 1 ? Material.PLANKS : Material.LOGS;
    }

    /** A value kept for a short time (the pre-check of a craft, which is asked for again and again). */
    public static final class Memo<T> {
        private T value;
        private long at;
        private boolean has;

        public T get(long now, long ttlMs, Supplier<T> make) {
            if (has && now >= at && now - at < ttlMs) return value;
            value = make.get();
            at = now;
            has = true;
            return value;
        }

        public void reset() {
            has = false;
            value = null;
        }
    }

    /** Remembers the outcome of a search for {@link #SEARCH_EVERY_MS}, so the world is not combed more than once in that time. */
    public static final class Throttle {
        private Pos last;
        private long at;
        private boolean has;

        /** The nearest table: searched for now, or the answer of the last search when it was less than 10 s ago. */
        public Pos get(long now, Supplier<Pos> search) {
            if (has && now - at < SEARCH_EVERY_MS && now >= at) return last;
            last = search.get();
            at = now;
            has = true;
            return last;
        }

        public void reset() {
            has = false;
            last = null;
        }
    }
}
