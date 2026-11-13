package com.porter.qwerte.core.enchant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.porter.qwerte.core.enchant.PlanRequest.Stock;
import com.porter.qwerte.core.enchant.PlanRequest.Wanted;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class PlannerTest {
    private static final String SWORD = "diamond_sword";
    private final McData d = McData.load("1.21.11");

    static Map<String, Integer> map(Object... kv) {
        TreeMap<String, Integer> m = new TreeMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], (Integer) kv[i + 1]);
        }
        return m;
    }

    static Stock book(int count, int rc, Object... kv) {
        return new Stock(Stack.book(map(kv), rc), count);
    }

    static Stock fresh() {
        return new Stock(Stack.fresh(), 1);
    }

    static List<Wanted> want(Object... kv) {
        List<Wanted> w = new ArrayList<>();
        for (int i = 0; i < kv.length; i += 2) {
            w.add(new Wanted((String) kv[i], (Integer) kv[i + 1]));
        }
        return w;
    }

    private Plan plan(McData data, String item, List<Wanted> w, List<Stock> bases, List<Stock> books) {
        return Planner.plan(data, new PlanRequest(item, w, bases, books));
    }

    private void assertSound(McData data, String item, Plan p) {
        assertTrue(p.ok(), p.code() + ": " + p.message());
        assertEquals(List.of(), Planner.verify(data, item, p));
        for (Plan.Step s : p.steps()) {
            assertTrue(s.cost() <= EnchantRules.MAX_STEP_COST, "step of " + s.cost());
        }
    }

    // --- book combining ---

    @Test
    void twoFourthLevelBooksMakeLevelFive() {
        Plan p = plan(d, SWORD, want("sharpness", 5), List.of(fresh()), List.of(book(2, 0, "sharpness", 4)));
        assertSound(d, SWORD, p);
        assertEquals(Map.of("sharpness", 5), p.levels());
        assertEquals(3, p.materials().size(), "base plus two books");
        assertEquals(2, p.steps().size());
    }

    @Test
    void fourThirdLevelBooksMakeLevelFive() {
        Plan p = plan(d, SWORD, want("sharpness", 5), List.of(fresh()), List.of(book(4, 0, "sharpness", 3)));
        assertSound(d, SWORD, p);
        assertEquals(Map.of("sharpness", 5), p.levels());
        assertEquals(5, p.materials().size(), "base plus four books");
        assertEquals(4, p.steps().size());
    }

    @Test
    void combiningStopsTwoLevelsDeep() {
        Plan p = plan(d, SWORD, want("sharpness", 5), List.of(fresh()), List.of(book(8, 0, "sharpness", 2)));
        assertFalse(p.ok());
        assertEquals(Planner.BOOKS_SHORT, p.code());
        assertTrue(p.message().contains("Sharpness V is missing and there are no two Sharpness IV books"), p.message());
    }

    // --- refusals ---

    @Test
    void missingBookGivesAPlainReason() {
        Plan p = plan(d, SWORD, want("sharpness", 5, "looting", 3), List.of(fresh()), List.of(book(1, 0, "sharpness", 5)));
        assertFalse(p.ok());
        assertEquals(Planner.BOOKS_SHORT, p.code());
        assertEquals("Looting III is missing and there are no two Looting II books", p.message());
    }

    @Test
    void everyConflictPairIsRefusedOnEveryItemThatTakesBoth() {
        int tested = 0;
        for (McData data : List.of(McData.load("1.21.4"), McData.load("1.21.11"))) {
            for (String a : data.enchantments().keySet()) {
                for (String b : data.enchantments().keySet()) {
                    if (a.compareTo(b) >= 0 || !data.conflicts(a, b)) {
                        continue;
                    }
                    for (String item : data.itemsFor(a)) {
                        if (!data.supports(b, item)) {
                            continue;
                        }
                        Plan p = Planner.validate(data, item, want(a, 1, b, 1));
                        assertNotNull(p, data.mc() + " " + a + "+" + b + " on " + item);
                        assertEquals(Planner.CONFLICT, p.code());
                        assertTrue(p.message().contains(Names.display(a)) && p.message().contains(Names.display(b)), p.message());
                        tested++;
                        break;
                    }
                }
            }
        }
        assertTrue(tested >= 20, "tested " + tested);
    }

    @Test
    void invalidOrdersAreRefused() {
        assertEquals(Planner.INVALID_LEVEL, Planner.validate(d, SWORD, want("sharpness", 6)).code(), "over max");
        assertEquals(Planner.INVALID_LEVEL, Planner.validate(d, SWORD, want("sharpness", 0)).code());
        assertEquals(Planner.WRONG_ITEM, Planner.validate(d, SWORD, want("power", 1)).code());
        assertEquals(Planner.WRONG_ITEM, Planner.validate(d, "diamond_pickaxe", want("sharpness", 1)).code());
        assertEquals(Planner.UNKNOWN_ENCHANT, Planner.validate(d, SWORD, want("flurry", 1)).code());
        assertEquals(Planner.DUPLICATE, Planner.validate(d, SWORD, want("sharpness", 1, "sharpness", 2)).code());
        assertEquals(Planner.NOTHING_WANTED, Planner.validate(d, SWORD, List.of()).code());
        assertEquals(null, Planner.validate(d, SWORD, want("sharpness", 5, "looting", 3)));
        // the plan refuses without searching
        Plan p = plan(d, SWORD, want("sharpness", 6), List.of(fresh()), List.of(book(1, 0, "sharpness", 5)));
        assertEquals(Planner.INVALID_LEVEL, p.code());
    }

    @Test
    void noBaseAndUnusableBases() {
        assertEquals(Planner.NO_BASE, plan(d, SWORD, want("sharpness", 1), List.of(), List.of(book(1, 0, "sharpness", 1))).code());
        // base items must not carry unwanted enchantments (this decision covers books only)
        Stock enchantedBase = new Stock(Stack.item(map("looting", 3), 1), 1);
        Plan p = plan(d, SWORD, want("sharpness", 1), List.of(enchantedBase), List.of(book(1, 0, "sharpness", 1)));
        assertEquals(Planner.BASE_UNUSABLE, p.code());
    }

    @Test
    void aBaseThatAlreadyHasSomeOfTheEnchantsIsPreferred() {
        Stock partial = new Stock(Stack.item(map("sharpness", 5), 1), 1);
        Plan p = plan(d, SWORD, want("sharpness", 5), List.of(fresh(), partial), List.of(book(1, 0, "sharpness", 5)));
        assertSound(d, SWORD, p);
        assertEquals(0, p.steps().size());
        assertEquals(1, p.materials().get(0).stockIndex());
        assertEquals(0, p.points());
    }

    // --- prior work and cost ---

    @Test
    void booksWithPriorWorkCostMore() {
        Plan clean = plan(d, SWORD, want("looting", 3), List.of(fresh()), List.of(book(1, 0, "looting", 3)));
        Plan worked = plan(d, SWORD, want("looting", 3), List.of(fresh()), List.of(book(1, 3, "looting", 3)));
        assertSound(d, SWORD, clean);
        assertSound(d, SWORD, worked);
        assertEquals(clean.steps().get(0).cost() + 3, worked.steps().get(0).cost());
        assertTrue(worked.points() > clean.points());
    }

    @Test
    void aSetNeedingAStepOver39IsRefused() {
        Stock heavy = book(1, 15, "sharpness", 5, "looting", 3, "fire_aspect", 2, "unbreaking", 3, "mending", 1,
                "sweeping_edge", 3, "knockback", 2);
        Plan p = plan(d, SWORD, want("sharpness", 5), List.of(fresh()), List.of(heavy));
        assertFalse(p.ok());
        assertEquals(Planner.TOO_EXPENSIVE, p.code());
        assertTrue(p.message().contains("39"), p.message());
    }

    // --- extras on books (always allowed) ---

    @Test
    void anExtraThatFitsTheItemIsCarriedAndPaidFor() {
        Plan p = plan(d, SWORD, want("sharpness", 5), List.of(fresh()), List.of(book(1, 0, "sharpness", 5, "looting", 3)));
        assertSound(d, SWORD, p);
        assertEquals(Map.of("sharpness", 5, "looting", 3), p.levels());
        assertEquals(Map.of("looting", 3), p.extras());
        assertEquals(5 + 2 * 3, p.steps().get(0).cost(), "sharpness 1x5 plus looting 2x3");
    }

    @Test
    void aConflictingExtraCostsOneMoreAndNeverReachesTheItem() {
        Plan p = plan(d, SWORD, want("sharpness", 5, "looting", 3), List.of(fresh()),
                List.of(book(1, 0, "sharpness", 5), book(1, 0, "looting", 3, "smite", 3)));
        assertSound(d, SWORD, p);
        assertFalse(p.levels().containsKey("smite"), "smite conflicts with sharpness");
        assertTrue(p.extras().isEmpty());
        // the book with the extra goes in second: prior work 1 + looting 2x3 + 1 for the dropped smite
        Plan.Step last = p.steps().get(p.steps().size() - 1);
        assertEquals(1 + 6 + EnchantRules.CONFLICT_PENALTY, last.cost());
    }

    @Test
    void whenPointsTieThePlanWithFewerExtrasWins() {
        // A: plain mending book with repair cost 3: 3 + 2 = 5 levels. B: mending + unbreaking III: 2 + 3 = 5 levels.
        Stock plain = book(1, 3, "mending", 1);
        Stock extra = book(1, 0, "mending", 1, "unbreaking", 3);
        for (List<Stock> order : List.of(List.of(plain, extra), List.of(extra, plain))) {
            Plan p = plan(d, SWORD, want("mending", 1), List.of(fresh()), order);
            assertSound(d, SWORD, p);
            assertEquals(5, p.steps().get(0).cost());
            assertTrue(p.extras().isEmpty(), "the plan without extras must win the tie: " + p.levels());
            assertEquals(order.indexOf(plain), p.materials().get(1).stockIndex());
        }
        // and with only the extra book it is accepted
        Plan only = plan(d, SWORD, want("mending", 1), List.of(fresh()), List.of(extra));
        assertSound(d, SWORD, only);
        assertEquals(Map.of("unbreaking", 3), only.extras());
    }

    @Test
    void anExtraThatPushesAStepToFortyIsRefusedAndACheaperPathIsChosen() {
        Stock heavy = book(1, 15, "sharpness", 5, "looting", 3, "fire_aspect", 2, "unbreaking", 3, "mending", 1,
                "sweeping_edge", 3, "knockback", 2); // 15 + 28 = 43 levels
        Stock light = book(1, 0, "sharpness", 5);
        Plan both = plan(d, SWORD, want("sharpness", 5), List.of(fresh()), List.of(heavy, light));
        assertSound(d, SWORD, both);
        assertEquals(1, both.materials().get(1).stockIndex(), "the light book");
        assertTrue(both.extras().isEmpty());
        assertEquals(5, both.steps().get(0).cost());
        Plan onlyHeavy = plan(d, SWORD, want("sharpness", 5), List.of(fresh()), List.of(heavy));
        assertEquals(Planner.TOO_EXPENSIVE, onlyHeavy.code());
    }

    // --- the stack rule and the validator ---

    @Test
    void validatorRejectsAStepWithSeveralItemsOnTheLeft() {
        Plan p = plan(d, SWORD, want("sharpness", 5), List.of(fresh()), List.of(book(1, 0, "sharpness", 5)));
        assertSound(d, SWORD, p);
        List<Plan.Material> tampered = new ArrayList<>(p.materials());
        Plan.Material m = tampered.get(0);
        tampered.set(0, new Plan.Material(m.kind(), m.enchants(), m.repairCost(), 2, m.stockIndex()));
        Plan bad = new Plan(true, null, null, tampered, p.steps(), p.levels(), p.extras(), p.totalLevels(), p.points(),
                p.bottles(), p.finalRepairCost(), p.setsTried(), p.truncated(), p.cursed(), p.timedOut(), p.workUnits());
        List<String> problems = Planner.verify(d, SWORD, bad);
        assertFalse(problems.isEmpty());
        assertTrue(problems.stream().anyMatch(s -> s.contains("exactly one")), problems.toString());
        assertTrue(problems.stream().anyMatch(s -> s.contains("over " + EnchantRules.MAX_STEP_COST) || s.contains("costs")), problems.toString());
    }

    @Test
    void validatorCatchesAWrongCost() {
        Plan p = plan(d, SWORD, want("sharpness", 5), List.of(fresh()), List.of(book(1, 0, "sharpness", 5)));
        List<Plan.Step> steps = new ArrayList<>(p.steps());
        Plan.Step s = steps.get(0);
        steps.set(0, new Plan.Step(s.left(), s.right(), s.cost() + 1, s.repairCost(), s.points()));
        Plan bad = new Plan(true, null, null, p.materials(), steps, p.levels(), p.extras(), p.totalLevels(), p.points(),
                p.bottles(), p.finalRepairCost(), p.setsTried(), p.truncated(), p.cursed(), p.timedOut(), p.workUnits());
        assertFalse(Planner.verify(d, SWORD, bad).isEmpty());
    }

    // --- Lunge and spears (1.21.11 only) ---

    @Test
    void lungeAndSpearEnchantsFollowTheVersion() {
        McData v4 = McData.load("1.21.4");
        Plan lunge = plan(d, "diamond_spear", want("lunge", 3), List.of(fresh()), List.of(book(1, 0, "lunge", 3)));
        assertSound(d, "diamond_spear", lunge);
        assertEquals(Planner.UNKNOWN_ENCHANT, Planner.validate(v4, "diamond_spear", want("lunge", 3)).code());
        assertEquals(Planner.WRONG_ITEM, Planner.validate(d, SWORD, want("lunge", 1)).code());
        // melee enchants take spears in 1.21.11 only
        assertEquals(null, Planner.validate(d, "diamond_spear", want("sharpness", 5, "looting", 3, "fire_aspect", 2)));
        assertEquals(Planner.WRONG_ITEM, Planner.validate(v4, "diamond_spear", want("sharpness", 5)).code());
        assertEquals(Planner.WRONG_ITEM, Planner.validate(d, "diamond_spear", want("sweeping_edge", 3)).code());
        assertEquals(null, Planner.validate(d, SWORD, want("sweeping_edge", 3)));
        // lunge combines freely with sharpness: no conflict
        assertEquals(null, Planner.validate(d, "diamond_spear", want("lunge", 3, "sharpness", 5)));
    }

    // --- sizes and properties ---

    @Test
    void theSevenEnchantSwordPlansFastAndSoundly() {
        List<Stock> books = List.of(book(1, 0, "sharpness", 5), book(1, 0, "looting", 3), book(1, 0, "fire_aspect", 2),
                book(1, 0, "knockback", 2), book(1, 0, "mending", 1), book(1, 0, "unbreaking", 3), book(1, 0, "sweeping_edge", 3));
        long t = System.nanoTime();
        Plan p = plan(d, SWORD, want("sharpness", 5, "looting", 3, "fire_aspect", 2, "knockback", 2, "mending", 1,
                "unbreaking", 3, "sweeping_edge", 3), List.of(fresh()), books);
        long ms = (System.nanoTime() - t) / 1_000_000;
        assertSound(d, SWORD, p);
        assertEquals(8, p.materials().size());
        assertEquals(7, p.steps().size());
        assertTrue(ms < 10_000, "took " + ms + " ms");
    }

    @Test
    void randomRequestsGiveSoundPlansOnBothVersions() {
        Random rnd = new Random(424242L);
        String[] items = {"diamond_sword", "diamond_pickaxe", "diamond_chestplate", "bow", "crossbow", "diamond_boots", "trident"};
        int ok = 0;
        for (int c = 0; c < 300; c++) {
            McData data = McData.load(c % 2 == 0 ? "1.21.4" : "1.21.11");
            String item = items[rnd.nextInt(items.length)];
            List<String> pool = data.enchantsFor(item);
            List<Wanted> wanted = new ArrayList<>();
            List<Stock> books = new ArrayList<>();
            int n = 1 + rnd.nextInt(5);
            for (int i = 0; i < n; i++) {
                String id = pool.get(rnd.nextInt(pool.size()));
                int lv = 1 + rnd.nextInt(data.get(id).maxLevel());
                boolean dup = false;
                for (Wanted w : wanted) {
                    dup |= w.id().equals(id) || data.conflicts(w.id(), id);
                }
                if (dup) {
                    continue;
                }
                wanted.add(new Wanted(id, lv));
                int rc = new int[]{0, 0, 1, 3}[rnd.nextInt(4)];
                Map<String, Integer> contents = new TreeMap<>(Map.of(id, rnd.nextInt(4) == 0 && lv > 1 ? lv - 1 : lv));
                if (rnd.nextInt(3) == 0) {
                    String extra = pool.get(rnd.nextInt(pool.size()));
                    if (!extra.equals(id) && !data.conflicts(extra, id)) {
                        contents.put(extra, 1);
                    }
                }
                books.add(new Stock(Stack.book(contents, rc), 1 + rnd.nextInt(3)));
            }
            if (wanted.isEmpty()) {
                continue;
            }
            Plan p = Planner.plan(data, new PlanRequest(item, wanted, List.of(fresh()), books));
            if (!p.ok()) {
                assertNotNull(p.code());
                assertNotNull(p.message());
                continue;
            }
            ok++;
            assertEquals(List.of(), Planner.verify(data, item, p), "case " + c);
            for (Wanted w : wanted) {
                assertEquals(w.level(), p.levels().get(w.id()), "case " + c);
            }
        }
        assertTrue(ok > 100, "ok plans " + ok);
    }
}
