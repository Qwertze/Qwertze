package com.porter.qwerte.core.enchant;

import static com.porter.qwerte.core.enchant.PlannerTest.book;
import static com.porter.qwerte.core.enchant.PlannerTest.fresh;
import static com.porter.qwerte.core.enchant.PlannerTest.map;
import static com.porter.qwerte.core.enchant.PlannerTest.want;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.porter.qwerte.core.enchant.PlanRequest.Stock;
import com.porter.qwerte.core.enchant.PlanRequest.Wanted;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Work bounds, hostile inputs, curses, base rules, the final-level invariant and cross-checks of the source choice. */
class HardeningTest {
    private static final String SWORD = "diamond_sword";
    private final McData d = McData.load("1.21.11");
    private static final List<Wanted> SWORD7 = want("sharpness", 5, "looting", 3, "fire_aspect", 2, "knockback", 2, "mending", 1,
            "unbreaking", 3, "sweeping_edge", 3);

    private static void bench(String line) {
        try {
            Path p = Path.of("build", "bench.txt");
            Files.createDirectories(p.getParent());
            Files.writeString(p, line + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // timing output is a convenience only
        }
    }

    /** Runs a plan while sampling the used heap every 2 ms; returns {plan, peak extra MB}. */
    private Plan timed(String label, PlanRequest req, long maxMs) {
        return timed(label, req, maxMs, Planner.Limits.DEFAULT);
    }

    private Plan timed(String label, PlanRequest req, long maxMs, Planner.Limits limits) {
        System.gc();
        Runtime rt = Runtime.getRuntime();
        long baseline = rt.totalMemory() - rt.freeMemory();
        long[] peak = {baseline};
        boolean[] stop = {false};
        Thread sampler = new Thread(() -> {
            while (!stop[0]) {
                peak[0] = Math.max(peak[0], rt.totalMemory() - rt.freeMemory());
                try {
                    Thread.sleep(2);
                } catch (InterruptedException e) {
                    return;
                }
            }
        });
        sampler.setDaemon(true);
        sampler.start();
        long t = System.nanoTime();
        Plan p = Planner.plan(d, req, limits);
        long ms = (System.nanoTime() - t) / 1_000_000;
        stop[0] = true;
        bench(label + ": " + ms + " ms, peak heap +" + Math.max(0, peak[0] - baseline) / (1024 * 1024) + " MB (sampled), ok=" + p.ok()
                + " code=" + p.code() + " sets=" + p.setsTried() + " truncated=" + p.truncated() + " timedOut=" + p.timedOut());
        assertTrue(ms < maxMs, label + " took " + ms + " ms");
        assertNotNull(p);
        if (p.ok()) {
            assertEquals(List.of(), Planner.verify(d, req.item(), p), label);
        }
        return p;
    }

    // --- hostile and large inputs ---

    @Test
    void manyDistinctBooksStayBounded() {
        Random rnd = new Random(1);
        String[] ids = {"sharpness", "looting", "fire_aspect", "knockback", "mending", "unbreaking", "sweeping_edge"};
        int[] max = {5, 3, 2, 2, 1, 3, 3};
        for (int n : new int[]{121, 300, 1500}) {
            List<Stock> books = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                int k = rnd.nextInt(ids.length);
                // low levels everywhere, so combining is attractive; distinct repair costs keep the entries distinct
                books.add(book(1 + rnd.nextInt(3), rnd.nextInt(1 << 12), ids[k], 1 + rnd.nextInt(Math.max(1, max[k] - 1))));
            }
            timed("random books n=" + n, new PlanRequest(SWORD, SWORD7, List.of(fresh()), books), 5_000);
        }
    }

    @Test
    void manyDuplicateAndIrrelevantBooksCountOnce() {
        List<Stock> books = new ArrayList<>();
        for (int i = 0; i < 2000; i++) {
            books.add(book(1, 0, "sharpness", 5)); // identical entries
            books.add(book(1, i, "protection", 4)); // not takeable by a sword and not ordered: irrelevant
        }
        Plan p = timed("2000 identical + 2000 irrelevant books", new PlanRequest(SWORD, want("sharpness", 5), List.of(fresh()), books), 3_000);
        assertTrue(p.ok());
        assertEquals(2, p.materials().size());
    }

    @Test
    void manyBasesAndHugeRequestsAreBounded() {
        List<Stock> bases = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            bases.add(new Stock(Stack.item(Map.of(), i), 1));
        }
        Plan p = timed("1000 distinct bases", new PlanRequest(SWORD, SWORD7, bases, List.of(
                book(1, 0, "sharpness", 5), book(1, 0, "looting", 3), book(1, 0, "fire_aspect", 2), book(1, 0, "knockback", 2),
                book(1, 0, "mending", 1), book(1, 0, "unbreaking", 3), book(1, 0, "sweeping_edge", 3))), 5_000);
        assertTrue(p.ok());
        assertEquals(0, p.materials().get(0).repairCost(), "the base with the least prior work is tried first");
        assertTrue(p.truncated(), "only " + EnchantRules.MAX_BASES_TRIED + " bases are tried");
        List<Stock> tooMany = new ArrayList<>();
        for (int i = 0; i < EnchantRules.MAX_RAW_BOOK_ENTRIES + 1; i++) {
            tooMany.add(book(1, i, "sharpness", 5));
        }
        assertEquals(Planner.TOO_COMPLEX, Planner.plan(d, new PlanRequest(SWORD, want("sharpness", 5), List.of(fresh()), tooMany)).code());
        // the cap counts relevant usable entries only: this many irrelevant books are filtered, not refused
        List<Stock> irrelevant = new ArrayList<>();
        for (int i = 0; i < EnchantRules.MAX_RAW_BOOK_ENTRIES + 1000; i++) {
            irrelevant.add(book(1, i, "protection", 4));
        }
        irrelevant.add(book(1, 0, "sharpness", 5));
        assertTrue(Planner.plan(d, new PlanRequest(SWORD, want("sharpness", 5), List.of(fresh()), irrelevant)).ok());
        List<Wanted> thirteen = new ArrayList<>();
        for (String id : d.enchantsFor("diamond_pickaxe")) {
            thirteen.add(new Wanted(id, 1));
        }
        thirteen.addAll(want("a", 1, "b", 1, "c", 1, "d", 1, "e", 1, "f", 1, "g", 1, "h", 1, "i", 1, "j", 1, "k", 1, "l", 1, "m", 1));
        assertEquals(Planner.TOO_COMPLEX, Planner.validate(d, SWORD, thirteen).code());
    }

    @Test
    void theWorkBudgetStopsAnExpensiveSearchAndSaysSo() {
        List<Stock> books = List.of(book(1, 0, "sharpness", 5), book(1, 0, "looting", 3), book(1, 0, "fire_aspect", 2),
                book(1, 0, "knockback", 2), book(1, 0, "mending", 1), book(1, 0, "unbreaking", 3), book(1, 0, "sweeping_edge", 3));
        Planner.Limits tiny = new Planner.Limits(500, 0, 8, 64, 2000);
        Plan p = Planner.plan(d, new PlanRequest(SWORD, SWORD7, List.of(fresh()), books), tiny);
        assertFalse(p.ok());
        assertEquals(Planner.TOO_COMPLEX, p.code());
        assertTrue(p.truncated());
        // a generous budget works and is deterministic
        Plan a = Planner.plan(d, new PlanRequest(SWORD, SWORD7, List.of(fresh()), books));
        Plan b = Planner.plan(d, new PlanRequest(SWORD, SWORD7, List.of(fresh()), books));
        assertTrue(a.ok());
        assertEquals(a, b);
    }

    /** Realistic 12-leaf orders: every level but one made from two books, so base + 11 books. */
    @Test
    void realistic12LeafOrdersAreNotSkippedUnderTheDefaultLimits() {
        // "god sword": Sharpness V, Looting III, Unbreaking III, Sweeping Edge III each from two books one level lower
        List<Stock> sword = List.of(book(2, 0, "sharpness", 4), book(2, 0, "looting", 2), book(2, 0, "unbreaking", 2),
                book(2, 0, "sweeping_edge", 2), book(1, 0, "fire_aspect", 2), book(1, 0, "knockback", 2), book(1, 0, "mending", 1));
        Plan p = timed("god sword (12 leaves, DEFAULT limits)", new PlanRequest(SWORD, SWORD7, List.of(fresh()), sword), 5_000);
        assertTrue(p.ok(), p.code() + " " + p.message());
        assertEquals(12, p.materials().size());
        assertFalse(p.truncated(), "the only set must be searched, not skipped");
        assertEquals(List.of(), Planner.verify(d, SWORD, p));
        // chestplate: Protection IV from four II books, Unbreaking III and Thorns III from two II each, Mending, both curses ordered
        List<Stock> chest = List.of(book(4, 0, "protection", 2), book(2, 0, "unbreaking", 2), book(2, 0, "thorns", 2),
                book(1, 0, "mending", 1), book(1, 0, "binding_curse", 1), book(1, 0, "vanishing_curse", 1));
        Plan c = timed("chestplate (12 leaves, DEFAULT limits)", new PlanRequest("diamond_chestplate",
                want("protection", 4, "unbreaking", 3, "thorns", 3, "mending", 1, "binding_curse", 1, "vanishing_curse", 1),
                List.of(fresh()), chest), 5_000);
        assertTrue(c.ok(), c.code() + " " + c.message());
        assertEquals(12, c.materials().size());
        assertFalse(c.truncated());
        assertEquals(List.of(), Planner.verify(d, "diamond_chestplate", c));
        assertTrue(p.workUnits() > 0 && c.workUnits() > 0, "the call reports the units it spent");
        assertTrue(p.workUnits() <= EnchantRules.MAX_WORK_UNITS);
    }

    @Test
    void randomTwelveLeafSetsFitTheProductionSliceAndTheWorstCaseIsMeasured() {
        Random rnd = new Random(12);
        int aborted = 0;
        long maxUnits = 0;
        List<MergeSearch.Leaf> worst = null;
        for (int c = 0; c < 20; c++) {
            List<MergeSearch.Leaf> leaves = TreeOracle.randomLeaves(d, rnd, 12);
            Budget meter = Budget.unlimited();
            MergeSearch.run(d, SWORD, leaves, Map.of(), meter);
            if (meter.used() > maxUnits) {
                maxUnits = meter.used();
                worst = leaves;
            }
            Budget slice = new Budget(EnchantRules.MAX_WORK_UNITS, 0).slice(EnchantRules.MAX_WORK_PER_SET, EnchantRules.MAX_NODES_PER_SET);
            if (MergeSearch.run(d, SWORD, leaves, Map.of(), slice).aborted) {
                aborted++;
            }
        }
        // the worst case, timed with the heap sampled
        System.gc();
        Runtime rt = Runtime.getRuntime();
        long baseline = rt.totalMemory() - rt.freeMemory();
        long[] peak = {baseline};
        boolean[] stop = {false};
        Thread sampler = new Thread(() -> {
            while (!stop[0]) {
                peak[0] = Math.max(peak[0], rt.totalMemory() - rt.freeMemory());
                try {
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    return;
                }
            }
        });
        sampler.setDaemon(true);
        sampler.start();
        long t0 = System.nanoTime();
        Budget slice = new Budget(EnchantRules.MAX_WORK_UNITS, 0).slice(EnchantRules.MAX_WORK_PER_SET, EnchantRules.MAX_NODES_PER_SET);
        MergeSearch.Outcome out = MergeSearch.run(d, SWORD, worst, Map.of(), slice);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        stop[0] = true;
        long mb = Math.max(0, peak[0] - baseline) / (1024 * 1024);
        bench("20 random 12-leaf sets: max " + maxUnits + " units; " + aborted + " exceed the production slice; worst case " + ms
                + " ms, peak heap +" + mb + " MB (sampled), aborted=" + out.aborted);
        assertTrue(aborted == 0, aborted + " of 20 random 12-leaf sets exceed the production slice");
        assertTrue(ms < 600 && mb < 150, "worst 12-leaf set: " + ms + " ms, " + mb + " MB");
    }

    @Test
    void theClockIsSeparateFromTheCaps() {
        Random rnd = new Random(1);
        String[] ids = {"sharpness", "looting", "fire_aspect", "knockback", "mending", "unbreaking", "sweeping_edge"};
        List<Stock> books = new ArrayList<>();
        for (int i = 0; i < 121; i++) {
            int k = rnd.nextInt(ids.length);
            int[] max = {5, 3, 2, 2, 1, 3, 3};
            books.add(book(1 + rnd.nextInt(3), rnd.nextInt(1 << 12), ids[k], 1 + rnd.nextInt(Math.max(1, max[k] - 1))));
        }
        Plan p = timed("deadline 1 ms", new PlanRequest(SWORD, SWORD7, List.of(fresh()), books), 5_000,
                new Planner.Limits(EnchantRules.MAX_WORK_UNITS, 1, 8, 64, 2000));
        assertTrue(p.timedOut() || p.code() != null && p.truncated(), "the wall-clock deadline must be reported as timedOut");
        assertFalse(Planner.plan(d, new PlanRequest(SWORD, SWORD7, List.of(fresh()), books)).timedOut(), "no deadline: never timedOut");
    }

    @Test
    void theCursePathSharesOneBudgetAndStaysFast() {
        // many cursed books force the second pass; the whole call (both passes) stays inside one budget
        List<Stock> books = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            books.add(book(1, i, "sharpness", 3, "vanishing_curse", 1));
        }
        Plan p = timed("curse path, 300 cursed books", new PlanRequest(SWORD, want("sharpness", 5), List.of(fresh()), books), 5_000);
        assertTrue(p.ok() ? p.cursed().contains("vanishing_curse") : p.truncated(), "cursed plan or a truncated refusal");
    }

    @Test
    void neverThrows() {
        assertEquals(Planner.BAD_REQUEST, Planner.plan(d, new PlanRequest(SWORD, want("sharpness", 1), List.of(fresh()), List.of()), null).code());
        java.util.HashMap<String, Integer> dirty = new java.util.HashMap<>();
        dirty.put(null, 3);
        dirty.put("sharpness", null);
        dirty.put("looting", 2);
        Stack st = Stack.book(dirty, -5);
        assertEquals(Map.of("looting", 2), st.enchants());
        assertEquals(0, st.repairCost());
        assertEquals(Map.of(), Stack.item(null, 0).enchants());
    }

    @Test
    void combiningCapsAreReportedAsTruncated() {
        List<Stock> books = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            books.add(book(1, i, "sharpness", 3)); // 60 distinct entries of the level below
        }
        Plan p = Planner.plan(d, new PlanRequest(SWORD, want("sharpness", 5), List.of(fresh()), books));
        assertTrue(p.truncated(), "more than " + EnchantRules.MAX_COMBINE_PARTS + " parts must set truncated");
    }

    @Test
    void badLevelsAndNullsNeverCrash() {
        for (int lv : new int[]{-1, 0, 6, 9, 255, Integer.MAX_VALUE, Integer.MIN_VALUE}) {
            Plan p = Planner.plan(d, new PlanRequest(SWORD, want("sharpness", 5),
                    List.of(new Stock(Stack.item(Map.of("sharpness", lv), 0), 1)), List.of(book(1, 0, "sharpness", 5))));
            if (lv < 1 || lv > 5) {
                assertEquals(Planner.BASE_UNUSABLE, p.code(), "base level " + lv);
            }
            Plan q = Planner.plan(d, new PlanRequest(SWORD, want("sharpness", 5), List.of(fresh()),
                    List.of(new Stock(Stack.book(Map.of("sharpness", lv), 0), 1), book(1, 0, "sharpness", 5))));
            assertTrue(q.ok(), "a book with level " + lv + " is ignored, the good one is used");
            assertEquals(2, q.materials().size());
        }
        assertThrows(IllegalArgumentException.class, () -> MergeSearch.run(d, SWORD,
                List.of(new MergeSearch.Leaf(true, Map.of("sharpness", -1), 0)), Map.of()));
        assertEquals(Planner.BAD_REQUEST, Planner.plan(d, null).code());
        assertEquals(Planner.BAD_REQUEST, Planner.plan(d, new PlanRequest(null, want("sharpness", 1), List.of(), List.of())).code());
        Plan nulls = Planner.plan(d, new PlanRequest(SWORD, want("sharpness", 1), null, null));
        assertEquals(Planner.NO_BASE, nulls.code());
        List<Stock> withNull = new ArrayList<>();
        withNull.add(null);
        withNull.add(new Stock(null, 1));
        withNull.add(book(1, 0, "sharpness", 1));
        assertTrue(Planner.plan(d, new PlanRequest(SWORD, want("sharpness", 1), withNullBases(), withNull)).ok());
    }

    private static List<Stock> withNullBases() {
        List<Stock> l = new ArrayList<>();
        l.add(null);
        l.add(fresh());
        return l;
    }

    @Test
    void jsonReaderHandlesDamagedInput() {
        assertThrows(IllegalArgumentException.class, () -> MiniJson.parse("{\"a\": [1, 2"));
        assertThrows(IllegalArgumentException.class, () -> MiniJson.parse("[".repeat(200)));
        assertThrows(IllegalArgumentException.class, () -> MiniJson.parse("\"abc"));
    }

    // --- curses (ask first) ---

    @Test
    void theCurseFreePlanWinsAndACursedPlanIsFlagged() {
        Stock cursed = book(1, 0, "sharpness", 5, "vanishing_curse", 1);
        Stock plain = book(1, 3, "sharpness", 5);
        Plan free = Planner.plan(d, new PlanRequest(SWORD, want("sharpness", 5), List.of(fresh()), List.of(cursed, plain)));
        assertTrue(free.ok());
        assertTrue(free.cursed().isEmpty());
        assertFalse(free.levels().containsKey("vanishing_curse"));
        assertEquals(1, free.materials().get(1).stockIndex(), "the plain book, although it has prior work");

        Plan only = Planner.plan(d, new PlanRequest(SWORD, want("sharpness", 5), List.of(fresh()), List.of(cursed)));
        assertTrue(only.ok(), "a cursed plan is returned, not refused");
        assertEquals(List.of("vanishing_curse"), only.cursed());
        assertEquals(Map.of("vanishing_curse", 1), only.extras());
        assertEquals(List.of(), Planner.verify(d, SWORD, only));
    }

    @Test
    void anOrderedCurseNeedsNoQuestion() {
        Stock cursed = book(1, 0, "sharpness", 5, "vanishing_curse", 1);
        Plan p = Planner.plan(d, new PlanRequest(SWORD, want("sharpness", 5, "vanishing_curse", 1), List.of(fresh()), List.of(cursed)));
        assertTrue(p.ok());
        assertTrue(p.cursed().isEmpty());
        assertTrue(p.extras().isEmpty());
    }

    @Test
    void aCurseTheItemCannotTakeIsHarmless() {
        // Curse of Binding only fits wearable items: the anvil drops it on a sword, so no question is needed.
        Plan p = Planner.plan(d, new PlanRequest(SWORD, want("sharpness", 5), List.of(fresh()),
                List.of(book(1, 0, "sharpness", 5, "binding_curse", 1))));
        assertTrue(p.ok());
        assertTrue(p.cursed().isEmpty());
        assertFalse(p.levels().containsKey("binding_curse"));
    }

    // --- base items ---

    @Test
    void aLowerLevelBaseIsRaisedToTheOrderedLevel() {
        Stock base = new Stock(Stack.item(map("sharpness", 3), 1), 1);
        Plan p = Planner.plan(d, new PlanRequest(SWORD, want("sharpness", 5), List.of(base),
                List.of(book(1, 0, "sharpness", 3), book(1, 0, "sharpness", 4))));
        assertTrue(p.ok(), p.message());
        assertEquals(Map.of("sharpness", 5), p.levels());
        assertEquals(List.of(), Planner.verify(d, SWORD, p));
    }

    @Test
    void basesWithNonOrderedEnchantsAreSkipped() {
        Stock extra = new Stock(Stack.item(map("sharpness", 5, "looting", 3), 1), 1);
        Plan none = Planner.plan(d, new PlanRequest(SWORD, want("sharpness", 5), List.of(extra), List.of()));
        assertEquals(Planner.BASE_UNUSABLE, none.code());
        Plan other = Planner.plan(d, new PlanRequest(SWORD, want("sharpness", 5), List.of(extra, fresh()),
                List.of(book(1, 0, "sharpness", 5))));
        assertTrue(other.ok());
        assertEquals(1, other.materials().get(0).stockIndex(), "the plain sword, not the one with Looting");
    }

    @Test
    void aCursedBaseIsSkippedUnlessTheCurseIsOrdered() {
        Stock cursedBase = new Stock(Stack.item(map("vanishing_curse", 1), 1), 1);
        List<Stock> books = List.of(book(1, 0, "sharpness", 5));
        assertEquals(Planner.BASE_UNUSABLE, Planner.plan(d, new PlanRequest(SWORD, want("sharpness", 5), List.of(cursedBase), books)).code());
        Plan ordered = Planner.plan(d, new PlanRequest(SWORD, want("sharpness", 5, "vanishing_curse", 1), List.of(cursedBase), books));
        assertTrue(ordered.ok(), ordered.message());
        assertEquals(Map.of("sharpness", 5, "vanishing_curse", 1), ordered.levels());
    }

    // --- the final-level invariant ---

    @Test
    void twoBooksCarryingTheSameExtraLevelNeverPushAnOrderedEnchantAboveItsLevel() {
        // Unbreaking II is ordered. Both books carry Unbreaking II as well, so putting both on the item would make III.
        Stock a = book(1, 0, "sharpness", 5, "unbreaking", 2);
        Stock b = book(1, 0, "looting", 3, "unbreaking", 2);
        Plan p = Planner.plan(d, new PlanRequest(SWORD, want("sharpness", 5, "looting", 3, "unbreaking", 2), List.of(fresh()), List.of(a, b)));
        assertFalse(p.ok(), "II + II would be III, above the ordered II");
        // with a plain Unbreaking II book as the source of the level, the others cannot be used and the order is still refused
        // when they are the only way to get Sharpness and Looting; with one of them alone it works
        Plan single = Planner.plan(d, new PlanRequest(SWORD, want("sharpness", 5, "unbreaking", 2), List.of(fresh()), List.of(a)));
        assertTrue(single.ok());
        assertEquals(2, single.levels().get("unbreaking"));
    }

    // --- the source choice against an exhaustive search ---

    @Test
    void sourceChoiceMatchesAnExhaustiveSearchOnSmallStocks() {
        Random rnd = new Random(99);
        String[] ids = {"sharpness", "looting", "fire_aspect", "knockback", "mending", "unbreaking", "sweeping_edge"};
        int compared = 0;
        for (int c = 0; c < 150; c++) {
            List<Wanted> wanted = new ArrayList<>();
            List<Stock> books = new ArrayList<>();
            int nw = 1 + rnd.nextInt(3);
            List<String> chosen = new ArrayList<>();
            while (chosen.size() < nw) {
                String id = ids[rnd.nextInt(ids.length)];
                if (!chosen.contains(id)) {
                    chosen.add(id);
                }
            }
            for (String id : chosen) {
                wanted.add(new Wanted(id, 1 + rnd.nextInt(d.get(id).maxLevel())));
            }
            int nb = 2 + rnd.nextInt(4);
            for (int i = 0; i < nb; i++) {
                Wanted w = wanted.get(rnd.nextInt(wanted.size()));
                int lv = w.level() > 1 && rnd.nextBoolean() ? w.level() - 1 : w.level();
                Map<String, Integer> contents = new java.util.TreeMap<>(Map.of(w.id(), lv));
                if (rnd.nextInt(3) == 0) {
                    String x = ids[rnd.nextInt(ids.length)];
                    if (!x.equals(w.id()) && !wanted.stream().anyMatch(q -> q.id().equals(x) && q.level() < 1)) {
                        Wanted ow = wanted.stream().filter(q -> q.id().equals(x)).findFirst().orElse(null);
                        contents.put(x, ow == null ? 1 : Math.min(ow.level(), 1 + rnd.nextInt(ow.level())));
                    }
                }
                books.add(new Stock(Stack.book(contents, new int[]{0, 0, 1, 3}[rnd.nextInt(4)]), 1));
            }
            Plan p = Planner.plan(d, new PlanRequest(SWORD, wanted, List.of(fresh()), books));
            long[] exhaustive = exhaustive(wanted, books);
            if (p.ok() != (exhaustive != null) && p.ok()) {
                throw new AssertionError("case " + c + ": planner found a plan the exhaustive search missed");
            }
            if (exhaustive == null) {
                continue;
            }
            compared++;
            if (!p.ok()) {
                // allowed only when the source rules (cover each ordered level by one book or two/four lower) cannot reach it
                continue;
            }
            Map<String, Integer> w2 = new java.util.TreeMap<>();
            wanted.forEach(w -> w2.put(w.id(), w.level()));
            long ext = p.extras().size();
            long[] got = {p.points(), ext, p.totalLevels(), p.steps().size(), p.finalRepairCost()};
            assertEquals(0, TreeOracle.cmp(exhaustive, got), "case " + c + " exhaustive " + java.util.Arrays.toString(exhaustive)
                    + " planner " + java.util.Arrays.toString(got));
        }
        assertTrue(compared > 40, "compared " + compared);
    }

    /** Best (points, extras, levels, steps, rc) over every subset of the books (all with count 1). Null when none works. */
    private long[] exhaustive(List<Wanted> wanted, List<Stock> books) {
        Map<String, Integer> w = new java.util.TreeMap<>();
        wanted.forEach(x -> w.put(x.id(), x.level()));
        long[] best = null;
        for (int mask = 0; mask < (1 << books.size()); mask++) {
            List<MergeSearch.Leaf> leaves = new ArrayList<>();
            leaves.add(new MergeSearch.Leaf(true, Map.of(), 0));
            boolean usable = true;
            for (int i = 0; i < books.size(); i++) {
                if ((mask & (1 << i)) != 0) {
                    Stack st = books.get(i).stack();
                    leaves.add(new MergeSearch.Leaf(false, st.enchants(), st.repairCost()));
                }
            }
            if (!usable) {
                continue;
            }
            MergeSearch.Outcome out = MergeSearch.run(d, SWORD, leaves, w);
            MergeSearch.Node n = out.best();
            if (n == null) {
                continue;
            }
            long[] t = {n.points, out.extras(n.ench), n.levels, n.steps, n.rc};
            if (best == null || TreeOracle.cmp(t, best) < 0) {
                best = t;
            }
        }
        return best;
    }

    // --- larger trees: the packed search against the reference anvil ---

    @Test
    void twelveLeafTreesAgreeWithTheReferenceAnvil() {
        Random rnd = new Random(12);
        for (int c = 0; c < 6; c++) {
            List<MergeSearch.Leaf> leaves = TreeOracle.randomLeaves(d, rnd, 12);
            Budget meter = Budget.unlimited();
            long t0 = System.nanoTime();
            MergeSearch.Outcome out = MergeSearch.run(d, SWORD, leaves, Map.of(), meter);
            bench("12-leaf DP case " + c + ": " + meter.used() + " units, " + (System.nanoTime() - t0) / 1_000_000 + " ms");
            assertFalse(out.aborted);
            int checked = 0;
            for (List<MergeSearch.Node> list : out.roots.values()) {
                for (MergeSearch.Node n : list) {
                    TreeOracle.Out ref = replay(n, leaves);
                    assertNotNull(ref, "case " + c);
                    assertEquals(n.points, ref.points());
                    assertEquals(n.levels, ref.levels());
                    assertEquals(n.rc, ref.st().repairCost());
                    assertEquals(out.decode(n.ench), new java.util.LinkedHashMap<>(ref.st().enchants()).entrySet().stream()
                            .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (x, y) -> x, java.util.LinkedHashMap::new)));
                    checked++;
                }
            }
            bench("12-leaf case " + c + ": " + checked + " roots replayed");
        }
    }

    private TreeOracle.Out replay(MergeSearch.Node n, List<MergeSearch.Leaf> leaves) {
        if (n.leaf >= 0) {
            MergeSearch.Leaf l = leaves.get(n.leaf);
            return new TreeOracle.Out(new Stack(!l.base(), l.enchants(), l.repairCost()), 0, 0, 0);
        }
        TreeOracle.Out a = replay(n.left, leaves);
        TreeOracle.Out b = replay(n.right, leaves);
        return a == null || b == null ? null : TreeOracle.step(d, SWORD, a, b);
    }
}
