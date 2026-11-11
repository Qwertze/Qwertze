package com.porter.qwerte.stash;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.ToIntFunction;

/**
 * Turns the kit lines of an order (or one restock copy) into units (KIT-CONTRACT v2, revision 4, "Kit orders: bot execution"): a ready unit per complete
 * copy to deliver, and a build per kit to make: its base (the fullest incomplete copy that fits), its loads gathered by match key within {@code take}
 * (exact slots: unnamed first, least damaged, then the lowest key; any-variant slots: plain first, then unnamed, least damaged, the lowest key; one
 * stack of each kind first). A kit whose loads can't all be had is still planned with what there is when it holds two kinds ({@link BuildUnit#kitShort}):
 * it is never delivered, it is stored as an incomplete copy. Pure: the sorter makes the {@link Scan} from its containers. A port of the plugin's
 * {@code KitPlanner}, over index keys and counts.
 */
public final class KitPlan {
    private KitPlan() {
    }

    /** One stack of a copy: its index key, match key, item id (with namespace) and count. */
    public record Stack(String key, String match, String id, int count) {
    }

    /** A copy of a template lying in the stash: the shulker's group key and its stacks. */
    public record Copy(String group, List<Stack> stacks) {
        public int filled() {
            int n = 0;
            for (Stack s : stacks) n += s.count();
            return n;
        }
    }

    /**
     * @param count   usable stacks by index key
     * @param matchOf the match key of each index key
     * @param damage  the least damage seen on each index key
     * @param named   index keys of stacks with a custom name; {@code enchanted} of enchanted ones
     * @param ready   complete copies by template id (pickable containers, not claimed)
     * @param bases   incomplete copies in the template's chain target by template id, the fullest first
     */
    public record Scan(Map<String, Long> count, Map<String, String> matchOf, Map<String, Integer> damage, Set<String> named, Set<String> enchanted,
                       Map<String, List<Copy>> ready, Map<String, List<Copy>> bases) {
        /** The same scan with no incomplete copy to start from (a restock job that builds a new copy). */
        public Scan withoutBases() {
            return new Scan(count, matchOf, damage, named, enchanted, ready, Map.of());
        }
    }

    /** A complete copy to deliver as it is. */
    public record ReadyUnit(String template, String name, String group) {
    }

    /** A stack of the base copy that the kit keeps: its index key and count. */
    public record Held(String key, int count) {
    }

    /** One load to gather: an index key and how many. */
    public record Lot(String key, int count) {
    }

    /**
     * A kit to build.
     * @param base     the group key of the incomplete copy built on, or null (an empty shulker)
     * @param held     the stacks the base already holds
     * @param lots     what is gathered, one stack of each kind first
     * @param color    the template's colour: null any, "plain", or a dye colour
     * @param kitShort the kit can't be completed: it is stored as an incomplete copy, never delivered
     */
    public record BuildUnit(String template, String name, String color, String base, List<Held> held, List<Lot> lots, boolean kitShort, List<KitResult.Miss> missing) {
    }

    /** @param ready the ready units, then the builds */
    public record Plan(List<ReadyUnit> ready, List<BuildUnit> builds, List<KitResult.Short> shorts) {
    }

    private static String full(String id) {
        return id.contains(":") ? id : "minecraft:" + id;
    }

    /**
     * The candidate index keys for a slot, in the order they are taken. Exact slots: unnamed before named, least damaged, the lowest key. Any-variant
     * slots: plain first, then unnamed, least damaged, the lowest key. {@code restock}: an any-variant slot never takes an enchanted stack.
     */
    static List<String> candidates(KitTemplate.Slot slot, Scan scan, boolean restock) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Long> e : scan.count().entrySet()) {
            if (e.getValue() <= 0) continue;
            String key = e.getKey();
            String m = scan.matchOf().get(key);
            if (m == null) continue;
            if (!(slot.any() ? KitTemplate.baseOf(m).equals(slot.base()) : m.equals(slot.match()))) continue;
            if (restock && slot.any() && scan.enchanted().contains(key)) continue;
            out.add(key);
        }
        Comparator<String> byDamage = Comparator.comparingInt((String k) -> scan.damage().getOrDefault(k, 0)).thenComparing(k -> k);
        Comparator<String> unnamedFirst = Comparator.comparing((String k) -> scan.named().contains(k) ? 1 : 0);
        if (slot.any()) out.sort(Comparator.comparing((String k) -> k.equals(slot.base()) ? 0 : 1).thenComparing(unnamedFirst).thenComparing(byDamage));
        else out.sort(unnamedFirst.thenComparing(byDamage));
        return out;
    }

    /**
     * @param empties       the empty shulkers the order's other units have not taken (any colour)
     * @param claimedGroups group keys of complete copies other orders hold, left out
     * @param maxUnits      the most units one order may have (54)
     */
    public static Plan plan(KitOrder.Snapshot snap, Scan scan, ToIntFunction<String> maxStack, int empties, Set<String> claimedGroups, int maxUnits, boolean restock) {
        List<ReadyUnit> readyUnits = new ArrayList<>();
        List<BuildUnit> buildUnits = new ArrayList<>();
        List<KitResult.Short> shorts = new ArrayList<>();
        Map<String, Long> avail = new HashMap<>(scan.count());
        Map<String, Integer> budget = new HashMap<>();
        for (KitOrder.Take t : snap.take()) budget.merge(t.match(), t.count(), Integer::sum);
        int emptiesLeft = empties;
        // Copies are used by identity: identical complete copies share a group key, and each one is its own kit
        Set<Copy> taken = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

        for (KitOrder.Line line : snap.lines()) {
            KitTemplate t = line.template();
            int found = 0;
            int wantReady = Math.min(line.ready(), line.count());
            for (Copy c : scan.ready().getOrDefault(t.id(), List.of())) {
                if (found >= wantReady || readyUnits.size() + buildUnits.size() >= maxUnits) break;
                if (claimedGroups.contains(c.group()) || !taken.add(c)) continue;
                readyUnits.add(new ReadyUnit(t.id(), t.name(), c.group()));
                found++;
            }
            int build = line.count() - found;
            List<Copy> bases = new ArrayList<>(scan.bases().getOrDefault(t.id(), List.of()));
            for (int i = 0; i < build; i++) {
                if (buildUnits.size() + readyUnits.size() >= maxUnits) {
                    shorts.add(new KitResult.Short(t.id(), build - i, List.of(), KitOrder.CAP, null));
                    break;
                }
                // What this kit draws is given back when it is not built (no shulker, fewer than two kinds)
                Map<String, Long> availBefore = new HashMap<>(avail);
                Map<String, Integer> budgetBefore = new HashMap<>(budget);
                Copy baseCopy = null;
                Map<Integer, Stack> held = new HashMap<>();
                for (Copy b : bases) {
                    Map<Integer, Stack> fit = fitBase(t, b);
                    if (fit != null && taken.add(b)) {
                        baseCopy = b;
                        held = fit;
                        break;
                    }
                }
                if (baseCopy != null) bases.remove(baseCopy);
                List<Held> heldList = new ArrayList<>();
                List<Lot> lots = new ArrayList<>();
                List<KitResult.Miss> missing = new ArrayList<>();
                int stacks = 0;
                int slotsLeft = 0;
                for (KitTemplate.Slot sl : t.slots()) if (sl != null) slotsLeft++;
                for (int s = 0; s < t.slots().size(); s++) {
                    KitTemplate.Slot slot = t.slots().get(s);
                    if (slot == null) continue;
                    slotsLeft--;
                    Stack have = held.get(s);
                    int need = slot.count(), max = Math.max(1, maxStack.applyAsInt(slot.base()));
                    List<String> keys;
                    if (have != null) {
                        // The base holds part of the slot (or all of it): the missing count is added to that very stack
                        heldList.add(new Held(have.key(), have.count()));
                        stacks++;
                        need = slot.count() - have.count();
                        if (need <= 0) continue;
                        keys = List.of(have.key());
                    } else {
                        keys = candidates(slot, scan, restock);
                    }
                    String budgetKey = slot.any() ? slot.base() + "#*" : slot.match();
                    Map<String, Long> availSlot = new HashMap<>(avail);
                    Map<String, Integer> budgetSlot = new HashMap<>(budget);
                    int got = 0;
                    List<Lot> mine = new ArrayList<>();
                    for (String key : keys) {
                        if (got >= need) break;
                        long usable = Math.min(Math.min(need - got, budget.getOrDefault(budgetKey, 0)), avail.getOrDefault(key, 0L));
                        if (usable <= 0) continue;
                        avail.put(key, avail.getOrDefault(key, 0L) - usable);
                        budget.merge(budgetKey, (int) -usable, Integer::sum);
                        mine.add(new Lot(key, (int) usable));
                        got += (int) usable;
                    }
                    // A kit holds at most 27 stacks, so a slot that would need more (two variants) is unavailable; a topped-up slot adds no stack
                    // (its missing count merges into the base's)
                    int added = have != null ? 0 : mine.size();
                    if (stacks + added + slotsLeft > KitTemplate.SLOTS) {
                        avail = availSlot;
                        budget = budgetSlot;
                        mine = new ArrayList<>();
                        got = 0;
                        added = 0;
                    }
                    stacks += added;
                    lots.addAll(mine);
                    if (got < need) missing.add(new KitResult.Miss(slot.match(), slot.key(), need - got));
                }
                lots = oneOfEachFirst(lots);
                boolean haveBase = baseCopy != null;
                if (!haveBase && emptiesLeft <= 0) {
                    avail = availBefore;
                    budget = budgetBefore;
                    shorts.add(new KitResult.Short(t.id(), 1, List.of(), KitOrder.NO_SHULKER, null));
                    continue;
                }
                boolean kitShort = !missing.isEmpty();
                // A kit of fewer than two kinds is not stored as an incomplete copy: nothing is started, nothing is taken
                if (kitShort && kinds(heldList, lots) < 2) {
                    avail = availBefore;
                    budget = budgetBefore;
                    shorts.add(new KitResult.Short(t.id(), 1, missing, KitOrder.ITEMS_SHORT, KitResult.STORED_RETURNED));
                    continue;
                }
                if (!haveBase) emptiesLeft--;
                buildUnits.add(new BuildUnit(t.id(), t.name(), t.color(), haveBase ? baseCopy.group() : null, heldList, lots, kitShort, missing));
            }
        }
        return new Plan(readyUnits, buildUnits, shorts);
    }

    /** The distinct item ids among the held stacks and the lots. */
    static int kinds(List<Held> held, List<Lot> lots) {
        Set<String> bases = new LinkedHashSet<>();
        for (Held h : held) bases.add(KitTemplate.baseOf(h.key()));
        for (Lot l : lots) bases.add(KitTemplate.baseOf(l.key()));
        return bases.size();
    }

    /** The first stack of each kind (by index key) goes first, so the kit has its range early; the rest keep their order. */
    static List<Lot> oneOfEachFirst(List<Lot> lots) {
        List<Lot> first = new ArrayList<>(), rest = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Lot l : lots) (seen.add(l.key()) ? first : rest).add(l);
        first.addAll(rest);
        return first;
    }

    /**
     * How an incomplete copy fits the template slot by slot: every stack of it sits in a slot of the template whose exact count it is (an exact slot of
     * its match key, or an any-variant slot of its item). Null when some stack does not fit (such a copy is not used as a base).
     * @return template slot index to the stack there
     */
    static Map<Integer, Stack> fitBase(KitTemplate t, Copy copy) {
        Map<Integer, Stack> out = new HashMap<>();
        for (Stack in : copy.stacks()) {
            int at = -1;
            // A slot of exactly its count first, else one it can still be topped up to
            for (int pass = 0; pass < 2 && at < 0; pass++) {
                for (int s = 0; s < t.slots().size() && at < 0; s++) {
                    KitTemplate.Slot slot = t.slots().get(s);
                    if (slot == null || out.containsKey(s)) continue;
                    if (pass == 0 ? slot.count() != in.count() : slot.count() < in.count()) continue;
                    if (slot.any() ? slot.base().equals(full(in.id())) : slot.match().equals(in.match())) at = s;
                }
            }
            if (at < 0) return null;
            out.put(at, in);
        }
        return out;
    }
}
