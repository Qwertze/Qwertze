package com.porter.qwerte.core.enchant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.porter.qwerte.core.enchant.PlanRequest.Stock;
import com.porter.qwerte.core.enchant.PlanRequest.Wanted;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The golden cases from the plan (sword, pickaxe, Protection IV chestplate). The tests pin only properties (the plan
 * verifies against the reference anvil and every step is at most 39). The computed totals are NOT asserted: they are
 * written to enchant-vectors.json (with "verified": false) when the QWERTE_WRITE_VECTORS environment variable holds a
 * path, so they can be checked by hand against a public calculator before anyone relies on them.
 */
class GoldenCasesTest {
    private record Case(String id, String item, List<Wanted> wanted, List<Stock> bases, List<Stock> books) {
        Case(String id, String item, List<Wanted> wanted, List<Stock> books) {
            this(id, item, wanted, List.of(PlannerTest.fresh()), books);
        }
    }

    private static List<Case> cases() {
        List<Case> c = new ArrayList<>();
        c.add(new Case("sword-single-books", "diamond_sword",
                PlannerTest.want("sharpness", 5, "looting", 3, "fire_aspect", 2, "knockback", 2, "mending", 1, "unbreaking", 3,
                        "sweeping_edge", 3),
                List.of(PlannerTest.book(1, 0, "sharpness", 5), PlannerTest.book(1, 0, "looting", 3),
                        PlannerTest.book(1, 0, "fire_aspect", 2), PlannerTest.book(1, 0, "knockback", 2),
                        PlannerTest.book(1, 0, "mending", 1), PlannerTest.book(1, 0, "unbreaking", 3),
                        PlannerTest.book(1, 0, "sweeping_edge", 3))));
        c.add(new Case("sword-sharpness-from-two-IV", "diamond_sword",
                PlannerTest.want("sharpness", 5, "looting", 3, "fire_aspect", 2, "knockback", 2, "mending", 1, "unbreaking", 3,
                        "sweeping_edge", 3),
                List.of(PlannerTest.book(2, 0, "sharpness", 4), PlannerTest.book(1, 0, "looting", 3),
                        PlannerTest.book(1, 0, "fire_aspect", 2), PlannerTest.book(1, 0, "knockback", 2),
                        PlannerTest.book(1, 0, "mending", 1), PlannerTest.book(1, 0, "unbreaking", 3),
                        PlannerTest.book(1, 0, "sweeping_edge", 3))));
        c.add(new Case("pickaxe-single-books", "diamond_pickaxe",
                PlannerTest.want("efficiency", 5, "unbreaking", 3, "fortune", 3, "mending", 1),
                List.of(PlannerTest.book(1, 0, "efficiency", 5), PlannerTest.book(1, 0, "unbreaking", 3),
                        PlannerTest.book(1, 0, "fortune", 3), PlannerTest.book(1, 0, "mending", 1))));
        c.add(new Case("pickaxe-prior-work-books", "diamond_pickaxe",
                PlannerTest.want("efficiency", 5, "unbreaking", 3, "fortune", 3, "mending", 1),
                List.of(PlannerTest.book(1, 1, "efficiency", 5), PlannerTest.book(1, 3, "unbreaking", 3),
                        PlannerTest.book(1, 1, "fortune", 3), PlannerTest.book(1, 0, "mending", 1))));
        c.add(new Case("chestplate-protection-iv-single-books", "diamond_chestplate",
                PlannerTest.want("protection", 4, "unbreaking", 3, "mending", 1),
                List.of(PlannerTest.book(1, 0, "protection", 4), PlannerTest.book(1, 0, "unbreaking", 3),
                        PlannerTest.book(1, 0, "mending", 1))));
        c.add(new Case("chestplate-protection-iv-from-two-III", "diamond_chestplate",
                PlannerTest.want("protection", 4, "unbreaking", 3, "mending", 1),
                List.of(PlannerTest.book(2, 0, "protection", 3), PlannerTest.book(1, 0, "unbreaking", 3),
                        PlannerTest.book(1, 0, "mending", 1))));
        // extras, curses and the base rule (user decisions of 2026-11-12)
        c.add(new Case("extras-fitting-extra-carried", "diamond_sword", PlannerTest.want("sharpness", 5),
                List.of(PlannerTest.book(1, 0, "sharpness", 5, "looting", 3))));
        c.add(new Case("extras-conflicting-extra-dropped", "diamond_sword", PlannerTest.want("sharpness", 5, "looting", 3),
                List.of(PlannerTest.book(1, 0, "sharpness", 5), PlannerTest.book(1, 0, "looting", 3, "smite", 3))));
        c.add(new Case("curse-free-plan-preferred", "diamond_sword", PlannerTest.want("sharpness", 5),
                List.of(PlannerTest.book(1, 0, "sharpness", 5, "vanishing_curse", 1), PlannerTest.book(1, 3, "sharpness", 5))));
        c.add(new Case("curse-only-cursed-plan-flagged", "diamond_sword", PlannerTest.want("sharpness", 5),
                List.of(PlannerTest.book(1, 0, "sharpness", 5, "vanishing_curse", 1))));
        c.add(new Case("curse-ordered-no-flag", "diamond_sword", PlannerTest.want("sharpness", 5, "vanishing_curse", 1),
                List.of(PlannerTest.book(1, 0, "sharpness", 5, "vanishing_curse", 1))));
        c.add(new Case("base-lower-level-raised", "diamond_sword", PlannerTest.want("sharpness", 5),
                List.of(new Stock(Stack.item(PlannerTest.map("sharpness", 3), 1), 1)),
                List.of(PlannerTest.book(1, 0, "sharpness", 3), PlannerTest.book(1, 0, "sharpness", 4))));
        c.add(new Case("base-with-unordered-enchant-skipped", "diamond_sword", PlannerTest.want("sharpness", 5),
                List.of(new Stock(Stack.item(PlannerTest.map("sharpness", 5, "looting", 3), 1), 1), PlannerTest.fresh()),
                List.of(PlannerTest.book(1, 0, "sharpness", 5))));
        return c;
    }

    @Test
    void goldenCasesAreSoundOnBothVersions() throws IOException {
        List<Object> out = new ArrayList<>();
        for (String mc : McData.SUPPORTED) {
            McData d = McData.load(mc);
            for (Case c : cases()) {
                Plan p = Planner.plan(d, new PlanRequest(c.item(), c.wanted(), c.bases(), c.books()));
                assertTrue(p.ok(), c.id() + " on " + mc + ": " + p.message());
                assertEquals(List.of(), Planner.verify(d, c.item(), p), c.id() + " on " + mc);
                for (Plan.Step s : p.steps()) {
                    assertTrue(s.cost() <= 39, c.id() + " step " + s.cost());
                }
                for (Wanted w : c.wanted()) {
                    assertEquals(w.level(), p.levels().get(w.id()), c.id());
                }
                if (!c.id().startsWith("extras-fitting") && !c.id().startsWith("curse-only")) {
                    assertTrue(p.extras().isEmpty(), c.id());
                }
                assertEquals(c.id().startsWith("curse-only") ? 1 : 0, p.cursed().size(), c.id());
                out.add(vector(mc, c, p));
            }
        }
        String target = System.getenv("QWERTE_WRITE_VECTORS");
        if (target != null && !target.isBlank()) {
            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put("verified", false);
            doc.put("note", "Computed by qwerte-core " + "1.0.0" + " (ENCHANT-CONTRACT v1.0 rules, ANVIL-RULES-verified.md). "
                    + "NOT checked against a public anvil calculator yet: check each case by hand, then set verified to true "
                    + "per case. Tests pin properties only (brute-force agreement, every step at most 39), never these totals.");
            doc.put("rules", "step cost = rcLeft + rcRight + sum(mult * resulting level) (+1 per conflicting enchant on the left; "
                    + "unsupported enchants are skipped); result rc = 2*max(rc)+1; step must cost at most 39; "
                    + "objective = sum of points(cost), then fewer extras, levels, steps, final rc");
            doc.put("ids", "plain ids as the library uses them (sharpness, diamond_sword); the wire form is namespaced (minecraft:sharpness)");
            doc.put("parity", "plans run with Planner.Limits.DEFAULT (maxMillis 0); once verified, bots must reproduce these steps exactly");
            doc.put("itemsAreNumbered", "0..materials-1 are the materials (0 = base); materials+i is the result of step i");
            doc.put("cases", out);
            Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
            Files.writeString(Path.of(target), gson.toJson(doc) + "\n", StandardCharsets.UTF_8);
        }
    }

    private static Map<String, Object> vector(String mc, Case c, Plan p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.id() + "@" + mc);
        m.put("mc", mc);
        m.put("verified", false);
        m.put("item", c.item());
        List<Object> wanted = new ArrayList<>();
        for (Wanted w : c.wanted()) {
            wanted.add(Map.of("id", w.id(), "lvl", w.level()));
        }
        m.put("wanted", wanted);
        List<Object> books = new ArrayList<>();
        for (Stock s : c.books()) {
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("enchants", s.stack().enchants());
            b.put("rc", s.stack().repairCost());
            b.put("count", s.count());
            books.add(b);
        }
        m.put("stockBooks", books);
        List<Object> bases = new ArrayList<>();
        for (Stock s : c.bases()) {
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("enchants", s.stack().enchants());
            b.put("rc", s.stack().repairCost());
            b.put("count", s.count());
            bases.add(b);
        }
        m.put("stockBases", bases);
        Map<String, Object> r = new LinkedHashMap<>();
        List<Object> mats = new ArrayList<>();
        for (Plan.Material x : p.materials()) {
            Map<String, Object> mm = new LinkedHashMap<>();
            mm.put("kind", x.kind());
            mm.put("enchants", x.enchants());
            mm.put("rc", x.repairCost());
            mats.add(mm);
        }
        r.put("materials", mats);
        List<Object> steps = new ArrayList<>();
        for (Plan.Step s : p.steps()) {
            Map<String, Object> sm = new LinkedHashMap<>();
            sm.put("left", s.left());
            sm.put("right", s.right());
            sm.put("cost", s.cost());
            sm.put("rc", s.repairCost());
            sm.put("points", s.points());
            steps.add(sm);
        }
        r.put("steps", steps);
        r.put("levels", p.levels());
        r.put("extras", p.extras());
        r.put("cursed", p.cursed()); // the wire field is called "curse"
        r.put("totalLevels", p.totalLevels());
        r.put("points", p.points());
        r.put("bottles", p.bottles());
        r.put("finalRepairCost", p.finalRepairCost());
        m.put("result", r);
        return m;
    }
}
