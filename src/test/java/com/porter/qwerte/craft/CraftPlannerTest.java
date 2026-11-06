package com.porter.qwerte.craft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CraftPlannerTest {
    private static final String BOX = "minecraft:shulker_box";
    private static final CraftPlanner.Caps CAPS = new CraftPlanner.Caps(27, 128, 54);

    private static Recipes recipes(String version) {
        Recipes r = Recipes.load(version);
        assertNotNull(r, "table for " + version);
        return r;
    }

    private static Map<String, Long> pool(Object... kv) {
        Map<String, Long> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put("minecraft:" + kv[i], ((Number) kv[i + 1]).longValue());
        return m;
    }

    private static CraftPlanner.Result plan(Recipes r, int want, Map<String, Long> pool) {
        return CraftPlanner.plan(r, BOX, want, pool, Set.of(), CAPS, 0, 0);
    }

    @Test
    void loadsBothTables() {
        for (String v : List.of("1.21.4", "1.21.11")) {
            Recipes r = recipes(v);
            assertEquals(v, r.version());
            assertTrue(r.size() > 800);
            // The shulker box, chest and planks recipes
            Recipes.Recipe box = r.producing(BOX).get(0);
            assertTrue(box.shaped());
            assertEquals(1, box.w());
            assertEquals(3, box.h());
            assertEquals(List.of("minecraft:shulker_shell"), box.cells().get(0));
            assertEquals(List.of("minecraft:chest"), box.cells().get(1));
            assertEquals(3, box.filled());
            Recipes.Recipe chest = r.producing("minecraft:chest").get(0);
            assertEquals(8, chest.filled());
            assertNull(chest.cells().get(4));
            assertEquals(4, r.producing("minecraft:oak_planks").get(0).count());
            assertNull(r.producing("minecraft:shulker_shell").isEmpty() ? null : "has a recipe");
            assertEquals("smelting", r.station("minecraft:stone"));
        }
    }

    @Test
    void fromStockWhenChestIsThere() {
        Recipes r = recipes("1.21.4");
        CraftPlanner.Result res = plan(r, 1, pool("shulker_shell", 2, "chest", 1));
        assertTrue(res.ok());
        assertEquals(1, res.plan().count());
        assertEquals(1, res.plan().steps().size());
        assertEquals(2, res.plan().take().get("minecraft:shulker_shell"));
        assertEquals(1, res.plan().take().get("minecraft:chest"));
        assertEquals(3, res.plan().ops());
        assertNull(res.plan().limited());
    }

    @Test
    void threeLevelsShellsPlusChestFromPlanksFromLog() {
        for (String v : List.of("1.21.4", "1.21.11")) {
            Recipes r = recipes(v);
            CraftPlanner.Result res = plan(r, 1, pool("shulker_shell", 2, "oak_log", 2));
            assertTrue(res.ok(), res.refusal());
            List<CraftPlanner.Step> steps = res.plan().steps();
            assertEquals(3, steps.size());
            assertEquals("minecraft:oak_planks", steps.get(0).recipe().result());
            assertEquals(2, steps.get(0).crafts());
            assertEquals("minecraft:chest", steps.get(1).recipe().result());
            assertEquals(BOX, steps.get(2).recipe().result());
            assertEquals(2, res.plan().take().get("minecraft:oak_log"));
            assertEquals(2, res.plan().take().get("minecraft:shulker_shell"));
            assertEquals(2 + 8 + 3, res.plan().ops());
        }
    }

    @Test
    void needsAreAddedUpBeforeRounding() {
        Recipes r = recipes("1.21.4");
        // 3 boxes need 24 planks = 6 crafts of planks = 6 logs
        CraftPlanner.Result res = plan(r, 3, pool("shulker_shell", 6, "oak_log", 6));
        assertTrue(res.ok(), res.refusal());
        assertEquals(3, res.plan().count());
        assertEquals(6, res.plan().steps().get(0).crafts());
        assertEquals(6, res.plan().take().get("minecraft:oak_log"));
        // 2 boxes need 16 planks = 4 crafts of planks
        CraftPlanner.Result two = plan(r, 2, pool("shulker_shell", 4, "oak_log", 4));
        assertTrue(two.ok());
        assertEquals(4, two.plan().steps().get(0).crafts());
        // 1 box needs 8 planks = 2 logs, whatever else is held
        CraftPlanner.Result one = plan(r, 1, pool("shulker_shell", 2, "oak_log", 5));
        assertEquals(2, one.plan().take().get("minecraft:oak_log"));
    }

    @Test
    void stockBeforeCraftingAndMostAbundantFirst() {
        Recipes r = recipes("1.21.4");
        // Planks in stock are used before logs are crafted; spruce has more than oak
        CraftPlanner.Result res = plan(r, 1, pool("shulker_shell", 2, "oak_planks", 3, "spruce_planks", 5, "oak_log", 4));
        assertTrue(res.ok());
        assertEquals(5, res.plan().take().get("minecraft:spruce_planks"));
        assertEquals(3, res.plan().take().get("minecraft:oak_planks"));
        assertFalse(res.plan().take().containsKey("minecraft:oak_log"));
        assertEquals(2, res.plan().steps().size());
    }

    @Test
    void refusalsWithReasons() {
        Recipes r = recipes("1.21.4");
        assertEquals("shulker shells can't be crafted", plan(r, 1, pool("chest", 1)).refusal());
        assertEquals("shulker shells can't be crafted", plan(r, 1, pool("shulker_shell", 1, "chest", 1)).refusal());
        assertEquals(CraftPlanner.NOT_ENOUGH, plan(r, 1, pool("shulker_shell", 2, "oak_log", 1)).refusal());
        assertEquals(CraftPlanner.NOT_ENOUGH, plan(r, 1, pool("shulker_shell", 2)).refusal());
        // Stone is smelted, not crafted
        CraftPlanner.Result bricks = CraftPlanner.plan(r, "minecraft:stone_bricks", 1, pool("cobblestone", 4), Set.of(), CAPS, 0, 0);
        assertEquals("stone needs a furnace", bricks.refusal());
    }

    @Test
    void depthIsThree() {
        Recipes r = recipes("1.21.4");
        // A crafting table is made of planks (made of logs)
        CraftPlanner.Result res = CraftPlanner.plan(r, "minecraft:crafting_table", 1, pool("oak_log", 1), Set.of(), CAPS, 0, 0);
        assertTrue(res.ok());
        // With nothing to make planks from, a box is refused
        assertFalse(plan(r, 1, pool("shulker_shell", 2)).ok());
    }

    @Test
    void neverUseListIsHonoured() {
        Recipes r = recipes("1.21.4");
        Map<String, Long> p = pool("shulker_shell", 2, "oak_planks", 8, "spruce_planks", 8);
        CraftPlanner.Result res = CraftPlanner.plan(r, BOX, 1, p, Set.of("minecraft:oak_planks", "minecraft:spruce_planks"), CAPS, 0, 0);
        assertFalse(res.ok());
        CraftPlanner.Result ok = CraftPlanner.plan(r, BOX, 1, p, Set.of("minecraft:oak_planks"), CAPS, 0, 0);
        assertTrue(ok.ok());
        assertEquals(8, ok.plan().take().get("minecraft:spruce_planks"));
        assertFalse(ok.plan().take().containsKey("minecraft:oak_planks"));
    }

    @Test
    void capsLimitAndSayWhy() {
        Recipes r = recipes("1.21.4");
        Map<String, Long> big = pool("shulker_shell", 200, "chest", 100);
        CraftPlanner.Result perOrder = CraftPlanner.plan(r, BOX, 40, big, Set.of(), CAPS, 0, 0);
        assertEquals(27, perOrder.plan().count());
        assertEquals("cap reached (27 more this order)", perOrder.plan().limited());
        assertEquals("cap reached (0 more this order)", CraftPlanner.plan(r, BOX, 1, big, Set.of(), CAPS, 27, 0).refusal());
        assertEquals("cap reached (0 more this hour)", CraftPlanner.plan(r, BOX, 1, big, Set.of(), CAPS, 0, 54).refusal());
        // Only 4 more this hour
        assertEquals(4, CraftPlanner.plan(r, BOX, 10, big, Set.of(), CAPS, 0, 50).plan().count());
        // 3 grid fills a box: 128 allows 42, but the order cap decides; with 10 ops only 3 boxes
        CraftPlanner.Result ops = CraftPlanner.plan(r, BOX, 10, big, Set.of(), new CraftPlanner.Caps(27, 10, 54), 0, 0);
        assertEquals(3, ops.plan().count());
        assertTrue(CraftPlanner.plan(r, BOX, 1, big, Set.of(), new CraftPlanner.Caps(27, 2, 54), 0, 0).refusal().startsWith("cap reached (3 grid fills, at most 2 a task"));
    }

    @Test
    void notEnoughMaterialsMakesFewer() {
        Recipes r = recipes("1.21.4");
        CraftPlanner.Result res = plan(r, 5, pool("shulker_shell", 6, "chest", 5));
        assertEquals(3, res.plan().count());
        // Out of shells, which can't be made
        assertEquals(CraftPlanner.NO_SHELLS, res.plan().limited());
    }

    @Test
    void reservationsAreKeptOutOfThePool() {
        Recipes r = recipes("1.21.4");
        // The caller hands over the stash less what the other lines need: 10 planks in the stash, 5 are another line's
        assertTrue(plan(r, 1, pool("shulker_shell", 2, "oak_planks", 10)).ok());
        assertFalse(plan(r, 1, pool("shulker_shell", 2, "oak_planks", 5)).ok());
        assertEquals(CraftPlanner.NOT_ENOUGH, plan(r, 1, pool("shulker_shell", 2, "oak_planks", 5)).refusal());
    }

    @Test
    void doesNotChangeThePool() {
        Recipes r = recipes("1.21.4");
        Map<String, Long> p = pool("shulker_shell", 2, "oak_log", 2);
        plan(r, 1, p);
        assertEquals(2L, p.get("minecraft:oak_log"));
    }
}
