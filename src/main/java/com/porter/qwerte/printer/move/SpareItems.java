package com.porter.qwerte.printer.move;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * The spare blocks: carried items that are blocks the schematic does not use, that the hotbar does not protect and that are
 * plain full cubes (the caller's spare test). They are what scaffolding builds with and what Baritone may place while it
 * climbs. Pure; generic so the caller keeps its own item type.
 */
public final class SpareItems {
    private SpareItems() {}

    /**
     * @param have        carried count per item
     * @param usedByBuild true for an item the schematic needs
     * @param protectedBy true for an item the hotbar helper protects
     * @param spare       true for an item that makes a good throwaway block
     * @param id          a stable name, used to order equal counts
     * @return the spare items, most plentiful first, then by name
     */
    public static <T> List<T> pick(Map<T, Integer> have, Predicate<T> usedByBuild, Predicate<T> protectedBy, Predicate<T> spare, Function<T, String> id) {
        List<T> out = new ArrayList<>();
        for (Map.Entry<T, Integer> e : have.entrySet()) {
            T it = e.getKey();
            if (e.getValue() == null || e.getValue() < 1 || usedByBuild.test(it) || protectedBy.test(it) || !spare.test(it)) continue;
            out.add(it);
        }
        out.sort((a, b) -> {
            int c = Integer.compare(have.get(b), have.get(a));
            return c != 0 ? c : id.apply(a).compareTo(id.apply(b));
        });
        return out;
    }

    /** How many of the spare items are carried in all. */
    public static <T> int total(Map<T, Integer> have, List<T> spare) {
        int n = 0;
        for (T it : spare) n += Math.max(0, have.getOrDefault(it, 0));
        return n;
    }
}
