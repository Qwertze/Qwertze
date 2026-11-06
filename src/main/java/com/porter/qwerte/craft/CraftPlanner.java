package com.porter.qwerte.craft;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Works out how to make an item from what a pool holds: which recipes to run, how many times, and which items of the
 * pool are used up. Pure (no Minecraft classes), so the addon and its tests share it, and it mirrors the proxy plugin's.
 *
 * Rules: depth 3 (the item, what it is made of, what that is made of; the last level is taken from stock only), no
 * item twice on one path, needs are added up over all cells and crafts before they are rounded up to whole crafts,
 * stock is used before anything is crafted, and of several alternatives the most abundant comes first. An item on the
 * never-use list is never used as an ingredient.
 */
public final class CraftPlanner {
    private CraftPlanner() {}

    public static final int MAX_DEPTH = 3;
    public static final int MAX_NODES = 256;
    private static final int TRIAL_BUDGET = 20_000;

    public static final String CAP_REACHED = "cap reached";
    public static final String NOT_ENOUGH = "not enough materials";
    public static final String NO_SHELLS = "shulker shells can't be crafted";

    /** The limits: crafts of the wanted item per order, grid fills per task, crafts of it per hour. */
    public record Caps(int perOrder, int opsPerTask, int perHour) {
        public static final Caps NONE = new Caps(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE);
    }

    /** One recipe run {@code crafts} times. */
    public record Step(Recipes.Recipe recipe, int crafts) {
        public int output() {
            return crafts * recipe.count();
        }

        /** Grid cells filled to do it. */
        public int ops() {
            return crafts * recipe.filled();
        }
    }

    /**
     * @param count how many of the item the steps make (can be fewer than asked for)
     * @param steps recipes to run in this order
     * @param take  what is used up from the pool, by item
     * @param ops   grid cells filled in all
     * @param limited why fewer than asked for are made, or null
     */
    public record Plan(String item, int count, List<Step> steps, Map<String, Integer> take, int ops, String limited) {}

    /** A plan, or why there is none. */
    public record Result(Plan plan, String refusal) {
        public boolean ok() {
            return plan != null;
        }
    }

    /**
     * @param want      how many to make
     * @param pool      what there is, by item (a copy is worked on)
     * @param never     items never used as ingredients
     * @param doneOrder how many were already crafted for this order
     * @param doneHour  how many were already crafted in the last hour
     */
    public static Result plan(Recipes recipes, String item, int want, Map<String, Long> pool, Set<String> never, Caps caps, int doneOrder, int doneHour) {
        if (want <= 0) return new Result(null, "nothing to craft");
        long leftOrder = (long) caps.perOrder() - doneOrder, leftHour = (long) caps.perHour() - doneHour;
        if (leftOrder <= 0) return new Result(null, CAP_REACHED + " (0 more this order)");
        if (leftHour <= 0) return new Result(null, CAP_REACHED + " (0 more this hour)");
        int allowed = (int) Math.min((long) want, Math.min(leftOrder, leftHour));
        String capText = CAP_REACHED + " (" + allowed + " more this " + (leftOrder <= leftHour ? "order" : "hour") + ")";

        Attempt best;
        int lo, hi;
        // The most that can be made, found by halving: what can be made is the same or less with every craft more
        Attempt full = attempt(recipes, item, allowed, pool, never, caps);
        if (full.ok) best = full;
        else {
            Attempt one = attempt(recipes, item, 1, pool, never, caps);
            if (!one.ok) return new Result(null, one.reason == null ? NOT_ENOUGH : one.reason);
            best = one;
            lo = 1;
            hi = allowed - 1;
            while (lo < hi) {
                int mid = (lo + hi + 1) >>> 1;
                Attempt a = attempt(recipes, item, mid, pool, never, caps);
                if (a.ok) {
                    best = a;
                    lo = mid;
                }
                else hi = mid - 1;
            }
        }
        String limited = best.count >= want ? null : best.count == allowed ? capText : (full.reason == null ? NOT_ENOUGH : full.reason);
        return new Result(new Plan(item, best.count, best.steps, best.take, best.ops, limited), null);
    }

    /** "minecraft:iron_ingot" as "iron ingot". */
    public static String pretty(String item) {
        String id = item.startsWith("minecraft:") ? item.substring("minecraft:".length()) : item;
        return id.replace('_', ' ');
    }

    // The search

    private static final class Attempt {
        boolean ok;
        int count, ops;
        String reason;
        List<Step> steps = List.of();
        Map<String, Integer> take = Map.of();
    }

    private static Attempt attempt(Recipes recipes, String item, int count, Map<String, Long> pool, Set<String> never, Caps caps) {
        Run run = new Run(recipes, pool, never);
        Attempt out = new Attempt();
        out.count = count;
        boolean ok = run.craft(item, count, 0, true);
        if (ok && run.nodes > MAX_NODES) {
            ok = false;
            run.fail("too many steps");
        }
        if (ok) {
            int ops = 0;
            for (Step s : run.steps) ops += s.ops();
            if (ops > caps.opsPerTask()) {
                ok = false;
                run.fail(CAP_REACHED + " (" + ops + " grid fills, at most " + caps.opsPerTask() + " a task)");
            }
            out.ops = ops;
        }
        out.ok = ok;
        out.reason = run.reason;
        if (ok) {
            out.steps = List.copyOf(run.steps);
            out.take = new LinkedHashMap<>(run.take);
        }
        return out;
    }

    /** The state of one attempt. */
    private static final class Run {
        final Recipes recipes;
        final Set<String> never;
        final Map<String, Long> stock = new HashMap<>();
        final Map<String, Long> spare = new HashMap<>();
        final Map<String, Integer> take = new LinkedHashMap<>();
        List<Step> steps = new ArrayList<>();
        final Set<String> path = new HashSet<>();
        int nodes, trial;
        String reason;

        Run(Recipes recipes, Map<String, Long> pool, Set<String> never) {
            this.recipes = recipes;
            this.never = never;
            stock.putAll(pool);
        }

        /** A snapshot of what a try changes. */
        record Mark(Map<String, Long> stock, Map<String, Long> spare, Map<String, Integer> take, List<Step> steps, int nodes, String reason) {}

        Mark mark() {
            return new Mark(new HashMap<>(stock), new HashMap<>(spare), new LinkedHashMap<>(take), new ArrayList<>(steps), nodes, reason);
        }

        /** Goes back to a snapshot but keeps what was learnt about why it failed. */
        void undo(Mark m) {
            String why = reason;
            restore(m);
            reason = why;
        }

        void restore(Mark m) {
            stock.clear();
            stock.putAll(m.stock);
            spare.clear();
            spare.putAll(m.spare);
            take.clear();
            take.putAll(m.take);
            steps = new ArrayList<>(m.steps);
            nodes = m.nodes;
            reason = m.reason;
        }

        /** Notes why it can't be done; a specific reason is kept over the general one. */
        void fail(String why) {
            if (reason == null || (reason.equals(NOT_ENOUGH) && !why.equals(NOT_ENOUGH))) reason = why;
        }

        String reasonFor(String item) {
            if (item.equals("minecraft:shulker_shell")) return NO_SHELLS;
            String station = recipes.station(item);
            if (station == null) return NOT_ENOUGH;
            return switch (station) {
                case "smelting", "blasting", "smoking", "campfire_cooking" -> pretty(item) + " needs a furnace";
                case "stonecutting" -> pretty(item) + " needs a stonecutter";
                case "smithing" -> pretty(item) + " needs a smithing table";
                default -> NOT_ENOUGH;
            };
        }

        /** Makes {@code qty} of the item exist: from the spare of earlier crafts and stock first, the rest crafted. */
        boolean craft(String item, int qty, int depth, boolean top) {
            if (qty <= 0) return true;
            if (!top && never.contains(item)) {
                fail(NOT_ENOUGH);
                return false;
            }
            long left = qty;
            if (!top) {
                left -= useSpare(item, left);
                left -= useStock(item, left);
            }
            if (left <= 0) return true;
            if (depth >= MAX_DEPTH || path.contains(item)) {
                fail(recipes.producing(item).isEmpty() ? reasonFor(item) : NOT_ENOUGH);
                return false;
            }
            if (++trial > TRIAL_BUDGET) {
                fail("too many steps");
                return false;
            }

            List<Recipes.Recipe> options = recipes.producing(item);
            if (options.isEmpty()) {
                fail(reasonFor(item));
                return false;
            }
            for (Recipes.Recipe r : options) {
                Mark before = mark();
                path.add(item);
                int crafts = (int) ((left + r.count() - 1) / r.count());
                boolean ok = true;
                // Added up over all cells and crafts, then rounded: every group of cells with the same alternatives is one need
                for (Map.Entry<List<String>, Integer> g : r.groups().entrySet()) {
                    if (!satisfy(g.getKey(), (long) g.getValue() * crafts, depth + 1)) {
                        ok = false;
                        break;
                    }
                }
                path.remove(item);
                if (ok) {
                    nodes++;
                    addStep(r, crafts);
                    long made = (long) crafts * r.count() - left;
                    if (made > 0) spare.merge(item, made, Long::sum);
                    return true;
                }
                undo(before);
            }
            return false;
        }

        void addStep(Recipes.Recipe r, int crafts) {
            if (!steps.isEmpty() && steps.get(steps.size() - 1).recipe() == r) {
                Step last = steps.remove(steps.size() - 1);
                steps.add(new Step(r, last.crafts() + crafts));
            }
            else steps.add(new Step(r, crafts));
        }

        long useSpare(String item, long want) {
            long have = spare.getOrDefault(item, 0L);
            long got = Math.min(have, want);
            if (got > 0) spare.put(item, have - got);
            return Math.max(0, got);
        }

        long useStock(String item, long want) {
            long have = stock.getOrDefault(item, 0L);
            long got = Math.min(have, want);
            if (got > 0) {
                stock.put(item, have - got);
                take.merge(item, (int) got, Integer::sum);
            }
            return Math.max(0, got);
        }

        /** Any of the alternatives, {@code qty} in all: stock first (the most abundant first), then crafted. */
        boolean satisfy(List<String> alts, long qty, int depth) {
            List<String> ranked = new ArrayList<>();
            for (String a : alts) if (!never.contains(a)) ranked.add(a);
            if (ranked.isEmpty()) {
                fail(NOT_ENOUGH);
                return false;
            }
            long left = qty;
            ranked.sort((a, b) -> {
                int c = Long.compare(spare.getOrDefault(b, 0L) + stock.getOrDefault(b, 0L), spare.getOrDefault(a, 0L) + stock.getOrDefault(a, 0L));
                return c != 0 ? c : a.compareTo(b);
            });
            for (String a : ranked) {
                if (left <= 0) break;
                left -= useSpare(a, left);
                left -= useStock(a, left);
            }
            if (left <= 0) return true;

            // The rest is crafted, from the alternative that can give the most
            if (depth >= MAX_DEPTH) {
                fail(ranked.size() == 1 ? reasonFor(ranked.get(0)) : NOT_ENOUGH);
                return false;
            }
            List<String> craftable = new ArrayList<>();
            for (String a : ranked) if (!recipes.producing(a).isEmpty() && !path.contains(a)) craftable.add(a);
            if (craftable.isEmpty()) {
                fail(ranked.size() == 1 ? reasonFor(ranked.get(0)) : NOT_ENOUGH);
                return false;
            }
            Map<String, Integer> most = new HashMap<>();
            for (String a : craftable) most.put(a, maxCraftable(a, left, depth));
            craftable.sort((a, b) -> {
                int c = Integer.compare(most.get(b), most.get(a));
                return c != 0 ? c : a.compareTo(b);
            });
            for (String a : craftable) {
                if (left <= 0) break;
                int q = (int) Math.min(left, most.get(a));
                if (q <= 0) continue;
                Mark before = mark();
                if (craft(a, q, depth, false)) left -= q;
                else undo(before);
            }
            if (left > 0) {
                fail(craftable.size() == 1 ? reasonFor(craftable.get(0)) : NOT_ENOUGH);
                return false;
            }
            return true;
        }

        /** The most of the item (up to {@code limit}) that can be crafted from what is left, by halving. */
        int maxCraftable(String item, long limit, int depth) {
            int hi = (int) Math.min(limit, Integer.MAX_VALUE), lo = 0;
            while (lo < hi) {
                int mid = (lo + hi + 1) >>> 1;
                Mark before = mark();
                String keep = reason;
                boolean ok = craft(item, mid, depth, true);
                restore(before);
                reason = keep;
                if (ok) lo = mid;
                else hi = mid - 1;
            }
            return lo;
        }
    }
}
