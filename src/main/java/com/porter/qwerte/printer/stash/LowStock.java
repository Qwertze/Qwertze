package com.porter.qwerte.printer.stash;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * When the Printer should order more of an item from a stash. Pure: items are id strings.
 *
 * <p>An item is short when the build still needs it, what is carried (and what is already on order or delivered and not yet
 * picked up: {@code covered}) does not cover the need. An order is triggered when some short item is also low: less than
 * {@code threshold} carried (or less than the whole need when that is smaller).
 */
public final class LowStock {
    private LowStock() {}

    /** True when some item with need > 0 has carried &lt; min(threshold, need) and need - carried - covered &gt; 0. */
    public static boolean triggered(Map<String, Long> need, Map<String, Long> carried, Map<String, Long> covered, long threshold) {
        for (Map.Entry<String, Long> e : need.entrySet()) {
            if (isTriggered(e.getKey(), e.getValue(), carried, covered, threshold)) return true;
        }
        return false;
    }

    private static boolean isTriggered(String item, long need, Map<String, Long> carried, Map<String, Long> covered, long threshold) {
        if (need <= 0) return false;
        long have = Math.max(0, carried.getOrDefault(item, 0L));
        long cov = Math.max(0, covered.getOrDefault(item, 0L));
        return have < Math.min(Math.max(1, threshold), need) && need - have - cov > 0;
    }

    /**
     * What is still to be ordered: need - carried - covered, for the items where that is positive. The triggered items come
     * first, then the biggest shortfall, then the id.
     */
    public static LinkedHashMap<String, Long> shortfall(Map<String, Long> need, Map<String, Long> carried, Map<String, Long> covered) {
        return shortfall(need, carried, covered, 1);
    }

    /** As {@link #shortfall(Map, Map, Map)}, with the threshold that decides which items count as triggered. */
    public static LinkedHashMap<String, Long> shortfall(Map<String, Long> need, Map<String, Long> carried, Map<String, Long> covered, long threshold) {
        record Row(String item, long short_, boolean low) {}
        List<Row> rows = new ArrayList<>();
        for (Map.Entry<String, Long> e : need.entrySet()) {
            long n = e.getValue();
            if (n <= 0) continue;
            long have = Math.max(0, carried.getOrDefault(e.getKey(), 0L));
            long cov = Math.max(0, covered.getOrDefault(e.getKey(), 0L));
            long lack = n - have - cov;
            if (lack <= 0) continue;
            rows.add(new Row(e.getKey(), lack, isTriggered(e.getKey(), n, carried, covered, threshold)));
        }
        rows.sort(Comparator.comparing(Row::low).reversed().thenComparing(Comparator.comparingLong(Row::short_).reversed()).thenComparing(Row::item));
        LinkedHashMap<String, Long> out = new LinkedHashMap<>();
        for (Row r : rows) out.put(r.item(), r.short_());
        return out;
    }
}
