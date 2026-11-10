package com.porter.stashmanager;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/** The contract's craft planner vectors (CRAFT-CONTRACT.md) on the synthetic table, plus the planner's own rules. */
class CraftPlannerTest {
    static final RecipeTable TABLE = RecipeFixtures.table();

    static String m(String s) {
        return "minecraft:" + s;
    }

    record R(String id, long need, boolean excluded) {
    }

    /** What the build planner does around the craft planner: reserved, direct takes, targets. */
    static CraftPlanner.Plan run(CraftPlanner.Eligible e, Map<String, Long> stock, Map<String, Long> reserved, List<R> rows) {
        Map<String, Long> pool = new HashMap<>();
        stock.forEach((k, v) -> pool.put(m(k), Math.max(0, v - reserved.getOrDefault(m(k), 0L))));
        Map<String, Long> direct = new HashMap<>();
        List<CraftPlanner.Row> rs = new ArrayList<>();
        for (R r : rows) {
            String id = m(r.id());
            long need = r.need();
            boolean excluded = r.excluded();
            long available = pool.getOrDefault(id, 0L);
            if (!excluded) direct.merge(id, Math.min(need, available), Long::sum);
            rs.add(new CraftPlanner.Row(id, need - Math.min(need, available), excluded));
        }
        return new CraftPlanner(e, RecipeFixtures::name).plan(pool, direct, rs);
    }

    static CraftPlanner.Plan run(Map<String, Long> stock, Map<String, Long> reserved, List<R> rows) {
        return run(new CraftPlanner.Eligible(TABLE, Set.of()), stock, reserved, rows);
    }

    static R row(String id, long need) {
        return new R(id, need, false);
    }

    static Map<String, Long> stock(Object... kv) {
        Map<String, Long> out = new TreeMap<>();
        for (int i = 0; i < kv.length; i += 2) out.put((String) kv[i], ((Number) kv[i + 1]).longValue());
        return out;
    }

    static List<Long> craftable(CraftPlanner.Plan p) {
        return p.rows().stream().map(CraftPlanner.RowResult::craftable).toList();
    }

    static Map<String, Long> counts(List<CraftPlanner.Count> l) {
        Map<String, Long> out = new TreeMap<>();
        l.forEach(c -> out.put(c.id().replace("minecraft:", ""), c.count()));
        return out;
    }

    static final List<R> THREE = List.of(row("spruce_stairs", 8), row("spruce_slab", 6), row("spruce_fence", 3));

    @Test
    void vector1() {
        CraftPlanner.Plan p = run(stock("spruce_log", 10), Map.of(), THREE);
        assertEquals(List.of(8L, 6L, 3L), craftable(p));
        assertEquals(stock("spruce_log", 6), counts(p.take()));
        assertEquals(stock("spruce_planks", 3, "stick", 2), counts(p.leftovers()));
        assertEquals(List.of("full", "full", "full"), p.rows().stream().map(CraftPlanner.RowResult::status).toList());
        assertFalse(p.truncated());
        // Steps merged and in order: planks first, then what uses them, the fence last.
        assertEquals(List.of("minecraft:spruce_planks", "minecraft:spruce_stairs", "minecraft:spruce_slab", "minecraft:stick", "minecraft:spruce_fence"),
            p.steps().stream().map(s -> s.recipe()).toList());
        assertEquals(List.of(6L, 2L, 1L, 1L, 1L), p.steps().stream().map(CraftPlanner.Step::fills).toList());
        assertEquals(11, p.fills());
        CraftPlanner.Step planks = p.steps().get(0);
        assertEquals(4, planks.count());
        assertEquals(Map.of("minecraft:spruce_log", 6L), planks.inputs());
        // Per row: the first row alone used 3 logs and made 12 planks for 8 stairs.
        assertEquals(stock("spruce_log", 3), counts(p.rows().get(0).uses()));
        assertEquals(2, p.rows().get(0).steps().size());
    }

    @Test
    void vector2NotEnoughLogsForTheFence() {
        CraftPlanner.Plan p = run(stock("spruce_log", 4), Map.of(), THREE);
        assertEquals(List.of(8L, 6L, 0L), craftable(p));
        CraftPlanner.RowResult fence = p.rows().get(2);
        assertEquals("short", fence.status());
        assertEquals("not enough Spruce Log", fence.reason());
        assertEquals(stock("spruce_log", 4), counts(p.take()));
        assertEquals(stock("spruce_planks", 1), counts(p.leftovers()));
    }

    @Test
    void vector3PartialStairs() {
        CraftPlanner.Plan p = run(stock("spruce_log", 2), Map.of(), List.of(row("spruce_stairs", 8)));
        assertEquals(List.of(4L), craftable(p));
        assertEquals("partial", p.rows().get(0).status());
        assertNotNull(p.rows().get(0).reason());
        assertEquals(stock("spruce_log", 2), counts(p.take()));
        assertEquals(stock("spruce_planks", 2), counts(p.leftovers()));
    }

    @Test
    void vector4DirectTakesComeFirst() {
        CraftPlanner.Plan p = run(stock("spruce_planks", 10, "spruce_log", 2), Map.of(m("spruce_planks"), 4L),
            List.of(row("spruce_planks", 5), row("spruce_stairs", 4)));
        assertEquals(List.of(0L, 4L), craftable(p));
        assertEquals("none", p.rows().get(0).status(), "all five come from the stash directly");
        assertEquals(stock("spruce_planks", 1, "spruce_log", 2), counts(p.take()));
        assertEquals(stock("spruce_planks", 3), counts(p.leftovers()));
    }

    @Test
    void vector4bWithoutLogs() {
        CraftPlanner.Plan p = run(stock("spruce_planks", 10), Map.of(m("spruce_planks"), 4L),
            List.of(row("spruce_planks", 5), row("spruce_stairs", 4)));
        assertEquals(List.of(0L, 0L), craftable(p));
        assertTrue(p.take().isEmpty());
        assertTrue(p.leftovers().isEmpty());
        assertEquals("short", p.rows().get(1).status());
    }

    @Test
    void vector5aStickFromPlanksIsCheaper() {
        CraftPlanner.Plan p = run(stock("oak_planks", 4, "bamboo", 14), Map.of(), List.of(row("ladder", 3)));
        assertEquals(List.of(3L), craftable(p));
        assertEquals(stock("oak_planks", 4), counts(p.take()));
        assertEquals(stock("stick", 1), counts(p.leftovers()));
    }

    @Test
    void vector5bThePlanksRouteFailsAndIsRestored() {
        CraftPlanner.Plan p = run(stock("oak_planks", 2, "bamboo", 14), Map.of(), List.of(row("ladder", 3)));
        assertEquals(List.of(3L), craftable(p));
        assertEquals(stock("bamboo", 14), counts(p.take()));
        assertTrue(p.leftovers().isEmpty());
        assertEquals(List.of("minecraft:stick_from_bamboo_item", "minecraft:ladder"), p.steps().stream().map(CraftPlanner.Step::recipe).toList());
    }

    @Test
    void vector6aTieBetweenBlockAndNuggetsGoesToTheId() {
        CraftPlanner.Plan p = run(stock("iron_block", 1), Map.of(), List.of(row("iron_bars", 16)));
        assertEquals(List.of(16L), craftable(p));
        assertEquals(stock("iron_block", 1), counts(p.take()));
        assertEquals(stock("iron_ingot", 3), counts(p.leftovers()));
    }

    @Test
    void vector6bTheBlockRouteIsALoopAndIsRestored() {
        CraftPlanner.Plan p = run(stock("iron_nugget", 54), Map.of(), List.of(row("iron_bars", 16)));
        assertEquals(List.of(16L), craftable(p));
        assertEquals(stock("iron_nugget", 54), counts(p.take()));
        assertTrue(p.leftovers().isEmpty());
    }

    @Test
    void vector7DyeRecipesAreNotEligible() {
        CraftPlanner.Plan p = run(stock("red_bed", 1, "white_dye", 1), Map.of(), List.of(row("white_bed", 1)));
        assertEquals(List.of(0L), craftable(p));
        assertEquals("not enough White Wool", p.rows().get(0).reason());
        assertTrue(p.take().isEmpty());
    }

    @Test
    void vector8ExcludedRowsAreSkipped() {
        List<R> rows = List.of(new R("spruce_stairs", 8, true), row("spruce_slab", 6), row("spruce_fence", 3));
        CraftPlanner.Plan p = run(stock("spruce_log", 10), Map.of(), rows);
        assertEquals(List.of(0L, 6L, 3L), craftable(p));
        assertEquals("excluded", p.rows().get(0).status());
        assertEquals(stock("spruce_log", 3), counts(p.take()));
        assertEquals(stock("spruce_planks", 3, "stick", 2), counts(p.leftovers()));
    }

    @Test
    void vector9TheTargetIsNotRounded() {
        CraftPlanner.Plan p = run(stock("spruce_log", 10), Map.of(), List.of(row("spruce_slab", 10)));
        assertEquals(List.of(10L), craftable(p));
        assertEquals(stock("spruce_log", 2), counts(p.take()));
        assertEquals(stock("spruce_planks", 2, "spruce_slab", 2), counts(p.leftovers()));
    }

    @Test
    void vector10ReservedCraftTakesShrinkThePool() {
        CraftPlanner.Plan p = run(stock("spruce_log", 10), Map.of(m("spruce_log"), 6L), THREE);
        assertEquals(List.of(8L, 6L, 0L), craftable(p));
        assertEquals(stock("spruce_log", 4), counts(p.take()));
        assertEquals(stock("spruce_planks", 1), counts(p.leftovers()));
    }

    @Test
    void vector11TheAlternativesAreSplitInIdOrder() {
        CraftPlanner.Plan p = run(stock("oak_log", 1, "birch_log", 1), Map.of(), List.of(row("chest", 1)));
        assertEquals(List.of(1L), craftable(p));
        assertEquals(stock("birch_log", 1, "oak_log", 1), counts(p.take()));
        assertTrue(p.leftovers().isEmpty());
    }

    // ---------- the planner's other rules ----------

    @Test
    void neverUseItemsAreNotTakenAndNotUsed() {
        CraftPlanner.Eligible e = new CraftPlanner.Eligible(TABLE, Set.of(m("spruce_log")));
        CraftPlanner.Plan p = run(e, stock("spruce_log", 10), Map.of(), THREE);
        assertEquals(List.of(0L, 0L, 0L), craftable(p));
        assertTrue(p.take().isEmpty());
        // The shulker-box and bundle rules are fixed.
        assertTrue(CraftPlanner.isShulkerBox("minecraft:red_shulker_box"));
        assertTrue(CraftPlanner.isShulkerBox("minecraft:shulker_box"));
        assertTrue(CraftPlanner.isBundle("minecraft:bundle"));
        assertTrue(CraftPlanner.isBundle("minecraft:red_bundle"));
        assertFalse(CraftPlanner.isBundle("minecraft:bundle_of_stuff"));
    }

    @Test
    void rowsWithoutARecipeOrWithAStation() {
        CraftPlanner.Plan p = run(stock("spruce_log", 10), Map.of(), List.of(row("stone", 5), row("diamond", 2), row("white_bed", 0)));
        assertEquals("station", p.rows().get(0).status());
        assertEquals("needs a furnace", p.rows().get(0).reason());
        assertEquals("no_recipe", p.rows().get(1).status());
        assertEquals("none", p.rows().get(2).status(), "nothing to make");
    }

    @Test
    void shulkerBoxesAndBundlesAreNeverCrafted() {
        Map<String, com.google.gson.JsonObject> r = new TreeMap<>(RecipeFixtures.recipes());
        r.put("shulker_box", RecipeFixtures.j(RecipeFixtures.shaped("{\"#\":\"minecraft:shulker_shell\"}", "[\"##\",\"##\"]", "minecraft:shulker_box", 1)));
        r.put("bundle", RecipeFixtures.j(RecipeFixtures.shaped("{\"#\":\"minecraft:string\"}", "[\"##\",\"##\"]", "minecraft:bundle", 1)));
        RecipeTable t = RecipeNormalizer.normalize("1.99.9", r, RecipeFixtures.tags(), Set.of()).table();
        CraftPlanner.Eligible e = new CraftPlanner.Eligible(t, Set.of());
        assertTrue(e.making("minecraft:shulker_box").isEmpty());
        assertTrue(e.making("minecraft:bundle").isEmpty());
        assertFalse(e.making("minecraft:spruce_planks").isEmpty());
        assertTrue(e.making("minecraft:white_bed").stream().noneMatch(x -> x.id().startsWith("minecraft:dye_")));
    }

    @Test
    void eligibilityDropsAlternativesEqualToTheResult() {
        // A copy recipe: the template needs itself (and diamonds) to make two.
        Map<String, com.google.gson.JsonObject> r = new TreeMap<>();
        r.put("copy", RecipeFixtures.j(RecipeFixtures.shaped("{\"D\":\"minecraft:diamond\",\"T\":\"minecraft:template\",\"S\":\"minecraft:stone\"}",
            "[\"DTD\",\"DSD\",\"DDD\"]", "minecraft:template", 2)));
        RecipeTable t = RecipeNormalizer.normalize("1.99.9", r, Map.of(), Set.of()).table();
        assertNotNull(t.byId("minecraft:copy"));
        assertTrue(new CraftPlanner.Eligible(t, Set.of()).making("minecraft:template").isEmpty(), "the template cell has no alternative left");
    }

    @Test
    void deterministicAcrossRuns() {
        CraftPlanner.Plan a = run(stock("spruce_log", 10, "oak_log", 3), Map.of(), THREE);
        CraftPlanner.Plan b = run(stock("spruce_log", 10, "oak_log", 3), Map.of(), THREE);
        assertEquals(a, b);
    }

    @Test
    void depthLimitStopsLongChains() {
        // a <- b <- c <- d <- e <- f: crafting f from a would need 5 levels below the item asked for.
        Map<String, com.google.gson.JsonObject> r = new TreeMap<>();
        String[] chain = {"a", "b", "c", "d", "e", "f"};
        for (int i = 1; i < chain.length; i++) {
            r.put("make_" + chain[i], RecipeFixtures.j(RecipeFixtures.shapeless("[\"minecraft:" + chain[i - 1] + "\"]", "minecraft:" + chain[i], 1)));
        }
        RecipeTable t = RecipeNormalizer.normalize("1.99.9", r, Map.of(), Set.of()).table();
        CraftPlanner.Eligible e = new CraftPlanner.Eligible(t, Set.of());
        CraftPlanner.Plan e4 = run(e, stock("a", 5), Map.of(), List.of(row("e", 2)));
        assertEquals(List.of(2L), craftable(e4), "four crafting levels are fine");
        CraftPlanner.Plan f5 = run(e, stock("a", 5), Map.of(), List.of(row("f", 2)));
        assertEquals(List.of(0L), craftable(f5));
        assertEquals("too deep", f5.rows().get(0).reason());
        CraftPlanner.Plan e4b = run(e, stock("b", 5), Map.of(), List.of(row("f", 2)));
        assertEquals(List.of(2L), craftable(e4b));
    }

    @Test
    void theBudgetCutsThePlanShort() {
        AtomicLong clock = new AtomicLong();
        // A clock that jumps 4 s every call: the 3 s limit is over right away.
        CraftPlanner planner = new CraftPlanner(new CraftPlanner.Eligible(TABLE, Set.of()), RecipeFixtures::name, () -> clock.addAndGet(4_000_000_000L));
        Map<String, Long> pool = Map.of(m("spruce_log"), 10L);
        CraftPlanner.Plan p = planner.plan(pool, Map.of(), List.of(new CraftPlanner.Row(m("spruce_stairs"), 8, false), new CraftPlanner.Row(m("spruce_slab"), 6, false)));
        assertTrue(p.truncated());
        assertEquals(List.of(0L, 0L), craftable(p));
        assertEquals(CraftPlanner.TOO_LARGE, p.rows().get(0).reason());
        assertEquals(CraftPlanner.TOO_LARGE, p.rows().get(1).reason());
        assertTrue(p.take().isEmpty());
    }

    @Test
    void anAbsurdTargetDoesNotOverflow() {
        CraftPlanner planner = new CraftPlanner(new CraftPlanner.Eligible(TABLE, Set.of()), RecipeFixtures::name);
        CraftPlanner.Plan p = planner.plan(Map.of(m("spruce_log"), 10L), Map.of(), List.of(new CraftPlanner.Row(m("spruce_slab"), Long.MAX_VALUE, false)));
        assertEquals(1, p.rows().size());
        assertEquals("partial", p.rows().get(0).status());
        assertEquals(78, p.rows().get(0).craftable(), "10 logs = 40 planks = 13 fills of 3 planks = 78 slabs");
    }

}
