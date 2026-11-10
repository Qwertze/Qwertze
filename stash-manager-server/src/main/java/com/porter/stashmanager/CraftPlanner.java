package com.porter.stashmanager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * The craft planner (CRAFT-CONTRACT.md, "The craft planner"): what a stash can make from what it holds, crafting-table
 * recipes only. Pure and deterministic: the same table, pool and rows always give the same plan. The routes, the order
 * placement and the Printer's quote all use this one class.
 */
final class CraftPlanner {
    static final int MAX_DEPTH = 4;
    static final double EPS = 1e-9;
    static final int ROUND_CALLS = 50_000;
    static final int PLAN_CALLS = 1_000_000;
    static final long PLAN_NANOS = 3_000_000_000L;
    static final String TOO_LARGE = "plan too large";

    // ---------- eligibility ----------

    /** One alternative-group of a recipe: n cells that take the same alternatives. */
    record Group(List<String> alts, int n) {
    }

    /** An eligible recipe as the planner sees it (alternatives already without never-use items and the recipe's own result). */
    record Rec(String id, String result, int count, List<Group> groups) {
    }

    /** The eligible recipes of a table for one never-use list. Cheap to build; the routes cache it per table and list. */
    static final class Eligible {
        final RecipeTable table;
        final Set<String> neverUse;
        private final Map<String, List<Rec>> byResult = new HashMap<>();
        private final List<Rec> all = new ArrayList<>();

        Eligible(RecipeTable table, Set<String> neverUse) {
            this.table = table;
            this.neverUse = Set.copyOf(neverUse);
            for (RecipeTable.Recipe r : table.recipes()) {
                Rec rec = eligible(r);
                if (rec == null) continue;
                all.add(rec);
                byResult.computeIfAbsent(rec.result(), k -> new ArrayList<>()).add(rec);
            }
        }

        private Rec eligible(RecipeTable.Recipe r) {
            String res = r.result();
            if (isShulkerBox(res) || isBundle(res) || r.id().startsWith("minecraft:dye_")) return null;
            Map<List<String>, Integer> groups = new LinkedHashMap<>();
            for (List<String> cell : r.cells()) {
                if (cell == null) continue;
                List<String> alts = new ArrayList<>();
                for (String a : cell) if (!a.equals(res) && !neverUse(a)) alts.add(a);
                if (alts.isEmpty()) return null;
                groups.merge(List.copyOf(alts), 1, Integer::sum);
            }
            List<Group> out = new ArrayList<>();
            groups.forEach((alts, n) -> out.add(new Group(alts, n)));
            return new Rec(r.id(), res, r.count(), List.copyOf(out));
        }

        boolean neverUse(String id) {
            return isShulkerBox(id) || isBundle(id) || neverUse.contains(id);
        }

        List<Rec> making(String result) {
            return byResult.getOrDefault(result, List.of());
        }

        List<Rec> all() {
            return all;
        }
    }

    static boolean isShulkerBox(String id) {
        return id.startsWith("minecraft:") && id.endsWith("shulker_box");
    }

    static boolean isBundle(String id) {
        return id.equals("minecraft:bundle") || (id.startsWith("minecraft:") && id.endsWith("_bundle"));
    }

    // ---------- input and output ----------

    /** A row to plan: the item, the units to make (need - min(need, available)), and whether the user left it out. */
    record Row(String id, long target, boolean excluded) {
    }

    record Count(String id, long count) {
    }

    /** One merged crafting step: a recipe, how often the grid is filled, and what goes in (by id). */
    record Step(String recipe, String result, int count, long fills, Map<String, Long> inputs) {
    }

    /** What a row came to. status: none, full, partial, short, no_recipe, station, excluded. */
    record RowResult(String id, long target, long craftable, String status, String reason, List<Count> uses, List<Step> steps,
                     List<Count> leftovers) {
    }

    record Plan(List<RowResult> rows, List<Step> steps, List<Count> take, List<Count> leftovers, long fills, boolean truncated) {
        static final Plan EMPTY = new Plan(List.of(), List.of(), List.of(), List.of(), 0, false);
    }

    // ---------- the planner ----------

    private final Eligible eligible;
    private final Function<String, String> names;
    private final LongSupplier nanos;

    CraftPlanner(Eligible eligible, Function<String, String> names) {
        this(eligible, names, System::nanoTime);
    }

    CraftPlanner(Eligible eligible, Function<String, String> names, LongSupplier nanos) {
        this.eligible = eligible;
        this.names = names;
        this.nanos = nanos;
    }

    /**
     * stock: the stash's units by plain item id, already minus what open orders reserve (and craft.take). direct: what the
     * same build or order takes of an id as itself. rows: in plan order.
     */
    Plan plan(Map<String, Long> stock, Map<String, Long> direct, List<Row> rows) {
        return plan(stock, direct, rows, nanos.getAsLong() + PLAN_NANOS);
    }

    /** The same with a deadline (in this planner's nanosecond clock) shared with other work; past it rows are cut as "plan too large". */
    Plan plan(Map<String, Long> stock, Map<String, Long> direct, List<Row> rows, long deadlineNanos) {
        return new Run(stock, direct).run(rows, deadlineNanos);
    }

    private static final class BudgetExceeded extends RuntimeException {
        BudgetExceeded() {
            super(null, null, false, false);
        }
    }

    private record Snap(HashMap<String, Long> taken, HashMap<String, Long> surplus, int steps, HashSet<String> locks) {
    }

    private record Raw(String recipe, String result, int count, long fills, Map<String, Long> inputs) {
    }

    private final class Run {
        final Map<String, Long> stock;
        final Map<String, Long> direct;
        final Map<String, Double> est = new HashMap<>();
        final Map<String, List<Rec>> ordered = new HashMap<>();
        HashMap<String, Long> taken = new HashMap<>();
        HashMap<String, Long> surplus = new HashMap<>();
        ArrayList<Raw> steps = new ArrayList<>();
        HashSet<String> locks = new HashSet<>();
        String reason;
        int rowCalls;
        int planCalls;
        long deadline;

        Run(Map<String, Long> stock, Map<String, Long> direct) {
            this.stock = stock;
            this.direct = direct;
        }

        long start(String id) {
            return Math.max(0, stock.getOrDefault(id, 0L) - direct.getOrDefault(id, 0L));
        }

        long p(String id) {
            return Math.max(0, start(id) - taken.getOrDefault(id, 0L));
        }

        // ---- estimates ----

        double est(String id) {
            Double e = est.get(id);
            if (e != null) return e;
            return start(id) > 0 ? 1.0 : Double.POSITIVE_INFINITY;
        }

        void estimate() {
            for (int round = 0; round < 4; round++) {
                Map<String, Double> next = new HashMap<>();
                for (Rec r : eligible.all()) {
                    double c = cost(r);
                    if (c == Double.POSITIVE_INFINITY) continue;
                    double cur = next.getOrDefault(r.result(), est(r.result()));
                    if (c < cur) next.put(r.result(), c);
                }
                est.putAll(next);
            }
            // The recipes of an item, cheapest first (equal within EPS: by id).
            for (Rec r : eligible.all()) {
                if (cost(r) == Double.POSITIVE_INFINITY) continue;
                ordered.computeIfAbsent(r.result(), k -> new ArrayList<>()).add(r);
            }
            for (List<Rec> l : ordered.values()) l.sort(Comparator.comparingLong((Rec r) -> key(cost(r))).thenComparing(Rec::id));
        }

        double cost(Rec r) {
            double sum = 0;
            for (Group g : r.groups()) {
                double best = Double.POSITIVE_INFINITY;
                for (String a : g.alts()) best = Math.min(best, est(a));
                sum += g.n() * best;
            }
            return sum / r.count();
        }

        // ---- state ----

        Snap snap() {
            return new Snap(new HashMap<>(taken), new HashMap<>(surplus), steps.size(), new HashSet<>(locks));
        }

        void restore(Snap s) {
            taken = new HashMap<>(s.taken());
            surplus = new HashMap<>(s.surplus());
            while (steps.size() > s.steps()) steps.remove(steps.size() - 1);
            locks = new HashSet<>(s.locks());
        }

        boolean fail(String why) {
            reason = why;
            return false;
        }

        void tick() {
            if (++rowCalls > ROUND_CALLS || ++planCalls > PLAN_CALLS || nanos.getAsLong() - deadline > 0) throw new BudgetExceeded();
        }

        String name(String id) {
            String n = names.apply(id);
            return n == null || n.isEmpty() ? Redact.prettyId(id) : n;
        }

        // ---- need ----

        boolean need(String id, long q, int depth, List<String> path) {
            tick();
            long left = q;
            long fromS = Math.min(left, surplus.getOrDefault(id, 0L));
            if (fromS > 0) {
                surplus.merge(id, -fromS, Long::sum);
                left -= fromS;
            }
            if (left > 0 && !eligible.neverUse(id)) {
                long fromP = Math.min(left, p(id));
                if (fromP > 0) {
                    taken.merge(id, fromP, Long::sum);
                    left -= fromP;
                }
            }
            if (left == 0) return true;
            if (depth >= MAX_DEPTH) return fail("too deep");
            if (path.contains(id)) return fail("a recipe loop");
            List<Rec> recipes = ordered.getOrDefault(id, List.of());
            if (recipes.isEmpty()) {
                List<Rec> any = eligible.making(id);
                if (!any.isEmpty()) return fail(lacking(any.get(0))); // has recipes, but nothing to make them from
                String station = eligible.table.otherStations().get(id);
                return fail(station != null ? "needs " + stationName(station) : "not enough " + name(id));
            }
            List<String> inner = new ArrayList<>(path);
            inner.add(id);
            String first = null;
            for (Rec r : recipes) {
                Snap s = snap();
                if (tryRecipe(r, left, depth, inner)) return true;
                if (first == null) first = reason;
                restore(s);
            }
            return fail(first);
        }

        /** Why a recipe can't be made from anything: the first group that has no stock and nothing to craft it from. */
        String lacking(Rec r) {
            for (Group g : r.groups()) {
                boolean any = false;
                for (String a : g.alts()) any |= est(a) != Double.POSITIVE_INFINITY;
                if (!any) return "not enough " + name(g.alts().get(0));
            }
            return "not enough " + name(r.result());
        }

        boolean tryRecipe(Rec r, long left, int depth, List<String> path) {
            long fills = (left + r.count() - 1) / r.count();
            Map<String, Long> inputs = new TreeMap<>();
            List<String> crafted = new ArrayList<>();
            String id = r.result();
            for (Group g : r.groups()) {
                long m = g.n() * fills;
                // Pass 1: stock, the most plentiful first (surplus before the pool).
                List<String> byStock = new ArrayList<>(g.alts());
                byStock.sort(Comparator.comparingLong((String a) -> -(surplus.getOrDefault(a, 0L) + p(a))).thenComparing(Comparator.naturalOrder()));
                for (String a : byStock) {
                    if (m == 0) break;
                    long fromS = Math.min(m, surplus.getOrDefault(a, 0L));
                    if (fromS > 0) {
                        surplus.merge(a, -fromS, Long::sum);
                        m -= fromS;
                        inputs.merge(a, fromS, Long::sum);
                    }
                    long fromP = Math.min(m, p(a));
                    if (fromP > 0) {
                        taken.merge(a, fromP, Long::sum);
                        m -= fromP;
                        inputs.merge(a, fromP, Long::sum);
                    }
                }
                if (m == 0) continue;
                // Pass 2: craft what is still missing, the cheapest alternative first, as much as each can make.
                List<String> cand = new ArrayList<>();
                for (String a : g.alts()) {
                    if (est(a) == Double.POSITIVE_INFINITY || locks.contains(a + "|" + id)) continue;
                    cand.add(a);
                }
                cand.sort(Comparator.comparingLong((String a) -> key(est(a))).thenComparing(Comparator.naturalOrder()));
                String firstFail = null;
                for (String a : cand) {
                    if (m == 0) break;
                    long k = most(a, m, depth + 1, path);
                    if (k < m && firstFail == null) firstFail = reason;
                    if (k > 0 && k < m && !need(a, k, depth + 1, path)) throw new IllegalStateException("a probe that succeeded failed again");
                    if (k > 0) {
                        m -= k;
                        inputs.merge(a, k, Long::sum);
                        crafted.add(a);
                    }
                }
                if (m > 0) {
                    return fail(firstFail != null ? firstFail : "not enough " + name(g.alts().get(0)));
                }
            }
            steps.add(new Raw(r.id(), id, r.count(), fills, inputs));
            surplus.merge(id, fills * r.count() - left, Long::sum);
            for (String a : crafted) locks.add(id + "|" + a);
            return true;
        }

        /**
         * The largest k <= m for which need(a, k) succeeds from the current state. When all of m can be made it is made (the
         * state keeps it); otherwise the state is as it was and the caller commits k.
         */
        long most(String a, long m, int depth, List<String> path) {
            Snap s = snap();
            if (need(a, m, depth, path)) return m;
            restore(s);
            String why = reason;
            long lo = 0, hi = m - 1;
            while (lo < hi) {
                long mid = lo + (hi - lo + 1) / 2;
                boolean good = need(a, mid, depth, path);
                restore(s);
                if (good) lo = mid;
                else hi = mid - 1;
            }
            reason = why; // the reason the whole amount failed
            return lo;
        }

        // ---- rows ----

        Plan run(List<Row> rows, long deadlineNanos) {
            deadline = deadlineNanos;
            estimate();
            List<RowResult> out = new ArrayList<>();
            boolean truncated = false;
            for (Row row : rows) {
                if (row.excluded()) {
                    out.add(new RowResult(row.id(), row.target(), 0, "excluded", null, List.of(), List.of(), List.of()));
                    continue;
                }
                if (row.target() <= 0) {
                    out.add(new RowResult(row.id(), row.target(), 0, "none", null, List.of(), List.of(), List.of()));
                    continue;
                }
                if (truncated) {
                    out.add(new RowResult(row.id(), row.target(), 0, "short", TOO_LARGE, List.of(), List.of(), List.of()));
                    continue;
                }
                Snap s0 = snap();
                try {
                    out.add(planRow(row, s0));
                } catch (BudgetExceeded e) {
                    restore(s0);
                    truncated = true;
                    out.add(new RowResult(row.id(), row.target(), 0, "short", TOO_LARGE, List.of(), List.of(), List.of()));
                }
                rowCalls = 0;
            }
            return finish(out, truncated);
        }

        RowResult planRow(Row row, Snap s0) {
            String id = row.id();
            long target = Math.min(row.target(), Integer.MAX_VALUE); // no order is larger; keeps every product below in range
            Map<String, Long> surplusBefore = new HashMap<>(surplus);
            rowCalls = 0;
            boolean ok = need(id, target, 0, List.of());
            String why = ok ? null : reason;
            long made = target;
            if (!ok) {
                restore(s0);
                long lo = 0, hi = target - 1;
                while (lo < hi) {
                    long mid = lo + (hi - lo + 1) / 2;
                    boolean good = need(id, mid, 0, List.of());
                    restore(s0);
                    if (good) lo = mid;
                    else hi = mid - 1;
                }
                made = lo;
                if (made > 0 && !need(id, made, 0, List.of())) throw new IllegalStateException("a probe that succeeded failed again");
            }
            String status;
            if (made == target) status = "full";
            else if (made > 0) status = "partial";
            else if (eligible.making(id).isEmpty()) status = eligible.table.otherStations().containsKey(id) ? "station" : "no_recipe";
            else status = "short";
            List<Count> uses = new ArrayList<>();
            for (Map.Entry<String, Long> e : new TreeMap<>(taken).entrySet()) {
                long d = e.getValue() - s0.taken().getOrDefault(e.getKey(), 0L);
                if (d > 0) uses.add(new Count(e.getKey(), d));
            }
            List<Count> left = new ArrayList<>();
            for (Map.Entry<String, Long> e : new TreeMap<>(surplus).entrySet()) {
                long d = e.getValue() - surplusBefore.getOrDefault(e.getKey(), 0L);
                if (d > 0) left.add(new Count(e.getKey(), d));
            }
            return new RowResult(id, target, made, status, made == target ? null : why, uses, merge(steps.subList(s0.steps(), steps.size())), left);
        }

        Plan finish(List<RowResult> rows, boolean truncated) {
            List<Step> merged = merge(steps);
            List<Count> take = new ArrayList<>();
            new TreeMap<>(taken).forEach((k, v) -> {
                if (v > 0) take.add(new Count(k, v));
            });
            List<Count> leftovers = new ArrayList<>();
            new TreeMap<>(surplus).forEach((k, v) -> {
                if (v > 0) leftovers.add(new Count(k, v));
            });
            long fills = 0;
            for (Step st : merged) fills += st.fills();
            return new Plan(List.copyOf(rows), order(merged), List.copyOf(take), List.copyOf(leftovers), fills, truncated);
        }
    }

    // ---------- steps ----------

    /** Steps merged per recipe id (fills and inputs added), in order of first commit. */
    private static List<Step> merge(List<Raw> raw) {
        Map<String, Raw> by = new LinkedHashMap<>();
        for (Raw r : raw) {
            Raw old = by.get(r.recipe());
            if (old == null) {
                by.put(r.recipe(), new Raw(r.recipe(), r.result(), r.count(), r.fills(), new TreeMap<>(r.inputs())));
            } else {
                Map<String, Long> in = new TreeMap<>(old.inputs());
                r.inputs().forEach((k, v) -> in.merge(k, v, Long::sum));
                by.put(r.recipe(), new Raw(old.recipe(), old.result(), old.count(), old.fills() + r.fills(), in));
            }
        }
        List<Step> out = new ArrayList<>();
        for (Raw r : by.values()) out.add(new Step(r.recipe(), r.result(), r.count(), r.fills(), Collections.unmodifiableMap(r.inputs())));
        return out;
    }

    /** By height (0 = every input from stock, else 1 + the highest height of the steps making its inputs), then first commit. */
    private static List<Step> order(List<Step> merged) {
        Map<String, List<Step>> makers = new HashMap<>();
        for (Step s : merged) makers.computeIfAbsent(s.result(), k -> new ArrayList<>()).add(s);
        Map<String, Integer> height = new HashMap<>();
        for (Step s : merged) height(s, makers, height, new HashSet<>());
        List<Step> out = new ArrayList<>(merged);
        // List.sort is stable: equal heights keep the first-commit order.
        out.sort(Comparator.comparingInt((Step s) -> height.get(s.recipe())));
        return out;
    }

    private static int height(Step s, Map<String, List<Step>> makers, Map<String, Integer> memo, Set<String> visiting) {
        Integer known = memo.get(s.recipe());
        if (known != null) return known;
        if (!visiting.add(s.recipe())) return 0; // a cycle can't happen (locks), but never loop
        int h = 0;
        for (String in : s.inputs().keySet()) {
            for (Step m : makers.getOrDefault(in, List.of())) {
                if (m == s) continue;
                h = Math.max(h, 1 + height(m, makers, memo, visiting));
            }
        }
        visiting.remove(s.recipe());
        memo.put(s.recipe(), h);
        return h;
    }

    private static long key(double cost) {
        return cost == Double.POSITIVE_INFINITY ? Long.MAX_VALUE : Math.round(cost / EPS);
    }

    static String stationName(String station) {
        return switch (station) {
            case "smelting" -> "a furnace";
            case "blasting" -> "a blast furnace";
            case "smoking" -> "a smoker";
            case "campfire_cooking" -> "a campfire";
            case "stonecutting" -> "a stonecutter";
            case "smithing" -> "a smithing table";
            default -> "a special recipe";
        };
    }
}
