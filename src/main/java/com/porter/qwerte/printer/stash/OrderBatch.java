package com.porter.qwerte.printer.stash;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.ToIntFunction;

/**
 * How much of what is wanted fits in one order. The Stash Manager server counts an order's shulkers as
 * {@code ceil(sum of ceil(count / maxStack) over the item lines / 27)} and refuses more than 54 (and more than 200 lines).
 * This adds whole stacks while that count stays within the limits, then a partial last stack if one more slot is left. Pure.
 */
public final class OrderBatch {
    private OrderBatch() {}

    public static final int SLOTS = 27;
    public static final int SERVER_MAX_SHULKERS = 54;
    public static final int SERVER_MAX_LINES = 200;

    /**
     * @param maxShulkersPerOrder   1..54
     * @param shulkersLeftThisRun   what the per-run cap still allows (0 or less: nothing)
     * @param maxLines              lines per order (the server takes 200)
     */
    public record Limits(int maxShulkersPerOrder, int shulkersLeftThisRun, int maxLines) {
        public Limits(int maxShulkersPerOrder, int shulkersLeftThisRun) {
            this(maxShulkersPerOrder, shulkersLeftThisRun, SERVER_MAX_LINES);
        }

        /** The most shulkers the order may need. */
        public int shulkers() {
            return Math.max(0, Math.min(Math.min(Math.max(1, maxShulkersPerOrder), SERVER_MAX_SHULKERS), shulkersLeftThisRun));
        }

        public int lines() {
            return Math.max(1, Math.min(maxLines, SERVER_MAX_LINES));
        }
    }

    private static int stack(ToIntFunction<String> maxStack, String id) {
        int s = maxStack.applyAsInt(id);
        return s <= 0 ? 64 : s;
    }

    /** The shulkers an order of these lines needs, the server's way. */
    public static int shulkers(Map<String, ? extends Number> lines, ToIntFunction<String> maxStack) {
        long slots = 0;
        for (Map.Entry<String, ? extends Number> e : lines.entrySet()) {
            long n = e.getValue().longValue();
            if (n <= 0) continue;
            int ms = stack(maxStack, e.getKey());
            slots += (n + ms - 1) / ms;
        }
        return (int) ((slots + SLOTS - 1) / SLOTS);
    }

    /**
     * The lines to order: {@code want} (item id to count, in priority order) cut to the limits. Counts are clamped to the int
     * range; items that no longer fit are left out.
     */
    public static LinkedHashMap<String, Integer> cap(Map<String, Long> want, ToIntFunction<String> maxStack, Limits limits) {
        LinkedHashMap<String, Integer> out = new LinkedHashMap<>();
        long slotsLeft = (long) limits.shulkers() * SLOTS;
        for (Map.Entry<String, Long> e : want.entrySet()) {
            if (slotsLeft <= 0 || out.size() >= limits.lines()) break;
            long n = e.getValue();
            if (n <= 0) continue;
            int ms = stack(maxStack, e.getKey());
            long whole = n / ms;
            long rest = n % ms;
            long takeWhole = Math.min(whole, slotsLeft);
            long count = takeWhole * ms;
            slotsLeft -= takeWhole;
            if (takeWhole == whole && rest > 0 && slotsLeft >= 1) {
                count += rest;
                slotsLeft--;
            }
            if (count <= 0) continue;
            out.put(e.getKey(), (int) Math.min(Integer.MAX_VALUE, count));
        }
        return out;
    }
}
