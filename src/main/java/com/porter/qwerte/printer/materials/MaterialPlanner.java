package com.porter.qwerte.printer.materials;

import com.porter.qwerte.printer.place.StateKind;
import com.porter.qwerte.printer.place.StateSpec;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * What a build costs and where the missing part comes from. Pure: items are id strings and the block-to-item mapping is
 * passed in, so everything is unit tested.
 *
 * <ul>
 *   <li>A block costs one of its item; a double slab two; candles, sea pickles, turtle eggs and snow layers as many as
 *       their count.</li>
 *   <li>The upper half of a door or double plant and the head of a bed are PART cells: placed by their base cell, they
 *       cost nothing.</li>
 *   <li>A potted plant costs an empty flower pot and the plant (the cost's {@code extra}).</li>
 *   <li>A state the printer skips, and a block that has no item, cost nothing and are counted as skipped with the reason.</li>
 *   <li>The shortfall is what is needed minus what is carried; it is taken from the sources in the order they were given.</li>
 * </ul>
 */
public final class MaterialPlanner {
    private MaterialPlanner() {}

    /** The item a block is placed from. */
    public interface Items {
        /** The item id for a block id, or null when the block has no item (it cannot be built from a hand). */
        String itemOf(String blockId);
    }

    /**
     * @param item  the item id, or null when nothing is needed
     * @param count how many of it, 0 for a PART cell and for a cell that is skipped
     * @param skip  why the cell is not built, or null
     * @param extra a second item the cell uses up (the plant that goes into a flower pot), or null
     */
    public record Cost(String item, int count, String skip, String extra, int extraCount) {
        public Cost(String item, int count, String skip) {
            this(item, count, skip, null, 0);
        }

        /** The same cost plus {@code n} of another item. */
        public Cost withExtra(String extraItem, int n) {
            return new Cost(item, count, skip, extraItem, n);
        }

        public boolean skipped() {
            return skip != null;
        }

        public boolean free() {
            return skip == null && count == 0;
        }
    }

    /** The cost of one cell. */
    public static Cost cost(StateSpec spec, Items items) {
        if (spec.skipped()) return new Cost(null, 0, spec.skipReason());
        if (spec.part()) return new Cost(null, 0, null);
        String item = items.itemOf(spec.blockId());
        if (item == null) return new Cost(null, 0, spec.blockId() + " has no item to place it with");
        return new Cost(item, count(spec), null);
    }

    /** How many of the item one placed state uses. */
    public static int count(StateSpec spec) {
        if (spec.kind() == StateKind.SLAB && "double".equals(spec.props().get("type"))) return 2;
        if (spec.kind() == StateKind.COUNT) {
            int n = 1;
            for (String key : new String[] { "candles", "pickles", "eggs", "layers" }) n = Math.max(n, number(spec.props().get(key)));
            return n;
        }
        return 1;
    }

    private static int number(String s) {
        if (s == null) return 1;
        try {
            return Math.max(1, Math.min(64, Integer.parseInt(s)));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    /** Running totals of what a build needs, and of what it cannot use. */
    public static final class Tally {
        private final Map<String, Long> need = new TreeMap<>();
        private final Map<String, Integer> skipped = new LinkedHashMap<>();

        /** Counts a cell and returns its cost. */
        public Cost add(StateSpec spec, Items items) {
            return add(cost(spec, items));
        }

        /** Counts a cell whose cost is already known. */
        public Cost add(Cost c) {
            if (c.skipped()) skipped.merge(c.skip(), 1, Integer::sum);
            else {
                if (c.count() > 0) need.merge(c.item(), (long) c.count(), Long::sum);
                if (c.extra() != null && c.extraCount() > 0) need.merge(c.extra(), (long) c.extraCount(), Long::sum);
            }
            return c;
        }

        /** A cell left the build (placed, skipped, failed): its cost is no longer needed. */
        public void remove(Cost c) {
            if (c == null || c.skipped()) return;
            take(c.item(), c.count());
            take(c.extra(), c.extraCount());
        }

        private void take(String item, int n) {
            if (item == null || n <= 0) return;
            long left = need.getOrDefault(item, 0L) - n;
            if (left <= 0) need.remove(item);
            else need.put(item, left);
        }

        /** Item id to how many are still needed, sorted by id. */
        public Map<String, Long> need() {
            return java.util.Collections.unmodifiableMap(need);
        }

        public long needOf(String item) {
            return need.getOrDefault(item, 0L);
        }

        /** Reason to number of cells, in the order the reasons were first seen. */
        public Map<String, Integer> skipped() {
            return java.util.Collections.unmodifiableMap(skipped);
        }

        public long total() {
            long t = 0;
            for (long n : need.values()) t += n;
            return t;
        }
    }

    /** One line of the summary: how many are needed, carried, and short. */
    public record Line(String item, long need, long have, long missing) {}

    /** Need minus have per item, the biggest shortfall first, then by id. Items with nothing needed are left out. */
    public static List<Line> lines(Map<String, Long> need, Map<String, Long> have) {
        List<Line> out = new ArrayList<>();
        for (Map.Entry<String, Long> e : need.entrySet()) {
            long n = e.getValue();
            if (n <= 0) continue;
            long h = Math.max(0, have.getOrDefault(e.getKey(), 0L));
            out.add(new Line(e.getKey(), n, h, Math.max(0, n - h)));
        }
        out.sort(Comparator.comparingLong(Line::missing).reversed().thenComparing(Line::item));
        return out;
    }

    /** The shortfall per item (only the items that are short). */
    public static Map<String, Long> missing(Map<String, Long> need, Map<String, Long> have) {
        Map<String, Long> out = new LinkedHashMap<>();
        for (Line l : lines(need, have)) if (l.missing() > 0) out.put(l.item(), l.missing());
        return out;
    }

    /** A place to take items from, with what it holds. */
    public record Source(String name, Map<String, Long> stock) {}

    /** Take {@code count} of {@code item} from the source called {@code source}. */
    public record Allocation(String source, String item, long count) {}

    /** Where each missing item comes from, and what no source can give. */
    public record Supply(List<Allocation> allocations, Map<String, Long> unmet) {}

    /**
     * Fills the shortfall from the sources in the order given: the first source gives as much as it has, the next gives
     * what is still short, and so on.
     */
    public static Supply allocate(Map<String, Long> missing, List<Source> sources) {
        List<Allocation> out = new ArrayList<>();
        Map<String, Long> unmet = new LinkedHashMap<>();
        for (Map.Entry<String, Long> e : missing.entrySet()) {
            long left = e.getValue();
            for (Source s : sources) {
                if (left <= 0) break;
                long has = s.stock().getOrDefault(e.getKey(), 0L);
                if (has <= 0) continue;
                long take = Math.min(has, left);
                out.add(new Allocation(s.name(), e.getKey(), take));
                left -= take;
            }
            if (left > 0) unmet.put(e.getKey(), left);
        }
        return new Supply(out, unmet);
    }
}
