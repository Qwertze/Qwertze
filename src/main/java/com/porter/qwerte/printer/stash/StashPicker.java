package com.porter.qwerte.printer.stash;

import com.porter.qwerte.printer.materials.MaterialPlanner;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.ToIntFunction;

/**
 * Which stash an order for the wanted items goes to. Pure.
 *
 * <p>A stash that already has an order of ours open, or the server limit of open orders, is not used. The rest are ranked:
 * an online bot first, then the one that covers the most of the wanted items (counted in inventory slots), then by key. The
 * wanted items are taken from them in that order ({@link MaterialPlanner#allocate}).
 */
public final class StashPicker {
    private StashPicker() {}

    /** The server limit of open orders for one stash. */
    public static final int MAX_OPEN = 20;

    /**
     * @param key        the stash key (server, a NUL character, stash)
     * @param stash      its name, for messages
     * @param available  what can still be ordered from it: item key (the bare item id for a plain stack) to count
     */
    public record Candidate(String key, String stash, boolean online, boolean hasOurOpenOrder, int openOrders, Map<String, Long> available) {}

    /** What to ask one stash for. */
    public record Pick(Candidate stash, LinkedHashMap<String, Long> lines) {}

    /** The picks in rank order, and what no stash can give. */
    public record Result(List<Pick> picks, Map<String, Long> unmet) {}

    public static boolean usable(Candidate c) {
        return !c.hasOurOpenOrder() && c.openOrders() < MAX_OPEN;
    }

    public static Result pick(Map<String, Long> want, List<Candidate> cands, ToIntFunction<String> maxStack) {
        List<Candidate> ranked = new ArrayList<>();
        for (Candidate c : cands) if (usable(c)) ranked.add(c);
        Map<String, Long> coverage = new LinkedHashMap<>();
        for (Candidate c : ranked) coverage.put(c.key(), slots(want, c, maxStack));
        ranked.sort(Comparator.comparing((Candidate c) -> !c.online())
            .thenComparing((Candidate c) -> -coverage.get(c.key()))
            .thenComparing(Candidate::key));

        List<MaterialPlanner.Source> sources = new ArrayList<>();
        for (Candidate c : ranked) sources.add(new MaterialPlanner.Source(c.key(), c.available()));
        MaterialPlanner.Supply supply = MaterialPlanner.allocate(want, sources);

        Map<String, LinkedHashMap<String, Long>> byStash = new LinkedHashMap<>();
        for (MaterialPlanner.Allocation a : supply.allocations()) {
            byStash.computeIfAbsent(a.source(), k -> new LinkedHashMap<>()).merge(a.item(), a.count(), Long::sum);
        }
        List<Pick> picks = new ArrayList<>();
        for (Candidate c : ranked) {
            LinkedHashMap<String, Long> lines = byStash.get(c.key());
            if (lines != null && !lines.isEmpty()) picks.add(new Pick(c, lines));
        }
        return new Result(picks, supply.unmet());
    }

    private static long slots(Map<String, Long> want, Candidate c, ToIntFunction<String> maxStack) {
        long total = 0;
        for (Map.Entry<String, Long> e : want.entrySet()) {
            long n = Math.min(e.getValue(), c.available().getOrDefault(e.getKey(), 0L));
            if (n <= 0) continue;
            int ms = maxStack.applyAsInt(e.getKey());
            if (ms <= 0) ms = 64;
            total += (n + ms - 1) / ms;
        }
        return total;
    }

    /**
     * Whether a stash server address is this game server: case-insensitive, ignoring the default port, a trailing dot and
     * surrounding blanks.
     */
    public static boolean sameServer(String a, String b) {
        return a != null && b != null && !a.isBlank() && normalize(a).equals(normalize(b));
    }

    private static String normalize(String s) {
        String t = s.trim().toLowerCase(Locale.ROOT);
        if (t.endsWith(":25565")) t = t.substring(0, t.length() - 6);
        while (t.endsWith(".")) t = t.substring(0, t.length() - 1);
        return t;
    }
}
