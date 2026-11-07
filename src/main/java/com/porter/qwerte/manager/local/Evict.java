package com.porter.qwerte.manager.local;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.ToLongFunction;

/** Which items to drop to get under a cap: the oldest first. Pure. */
public final class Evict {
    private Evict() {}

    /**
     * The items to drop so that at most {@code cap} remain: those with the smallest {@code age} value (the oldest time
     * stamps), ties broken by their order in {@code items}. Empty when already within the cap.
     */
    public static <T> List<T> oldest(Collection<T> items, int cap, ToLongFunction<T> age) {
        int excess = items.size() - Math.max(0, cap);
        if (excess <= 0) return List.of();
        List<T> sorted = new ArrayList<>(items);
        sorted.sort((a, b) -> Long.compare(age.applyAsLong(a), age.applyAsLong(b)));
        return new ArrayList<>(sorted.subList(0, excess));
    }
}
