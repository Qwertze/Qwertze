package com.porter.qwerte.craft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.porter.qwerte.stash.Order;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The checks made before any click for a craft order (ported from the plugin's CraftFlowTest and CraftFixesTest), and the craft block of an order. */
class CraftOrderTest {
    private static final Recipes R = TestRecipes.table();
    private static final String LOG = "minecraft:spruce_log", PLANKS = "minecraft:spruce_planks", STAIRS = "minecraft:spruce_stairs";

    private static Order.Qty q(String id, int n) {
        return new Order.Qty(id, n);
    }

    /** 8 spruce stairs from 3 logs: 3 fills of planks (12 planks), 2 fills of stairs. */
    private static Order.Craft good() {
        Order.CraftStep planks = new Order.CraftStep("minecraft:spruce_planks", PLANKS, 4, 3, List.of(q(LOG, 3)));
        Order.CraftStep stairs = new Order.CraftStep("minecraft:spruce_stairs", STAIRS, 4, 2, List.of(q(PLANKS, 12)));
        return new Order.Craft(1, "1.21.4", R.hash(), List.of(q(STAIRS, 8)), List.of(planks, stairs), List.of(q(LOG, 3)), List.of(), 5, false);
    }

    private static Order.Craft withSteps(Order.Craft c, List<Order.CraftStep> steps) {
        return new Order.Craft(1, c.mc(), c.recipes(), c.lines(), steps, c.take(), c.leftovers(), c.fills(), false);
    }

    @Test
    void aGoodBlockPasses() {
        assertNull(CraftOrder.check(good(), R, 2048));
        assertNull(CraftOrder.check(good(), R, 5));
    }

    @Test
    void everyCheckOfTheOrdersCraftBlockIsMadeBeforeAnyClick() {
        Order.Craft good = good();
        // v
        assertNotNull(CraftOrder.check(new Order.Craft(2, "1.21.4", R.hash(), good.lines(), good.steps(), good.take(), good.leftovers(), good.fills(), false), R, 2048));
        // the hash
        assertNotNull(CraftOrder.check(new Order.Craft(1, "1.21.4", "1".repeat(40), good.lines(), good.steps(), good.take(), good.leftovers(), good.fills(), false), R, 2048));
        // a recipe that is not in the table, or makes something else, or another count
        List<Order.CraftStep> steps = new ArrayList<>(good.steps());
        Order.CraftStep s0 = steps.get(0);
        steps.set(0, new Order.CraftStep("minecraft:nope", s0.result(), s0.count(), s0.fills(), s0.inputs()));
        assertNotNull(CraftOrder.check(withSteps(good, steps), R, 2048));
        steps.set(0, new Order.CraftStep(s0.recipe(), "minecraft:stick", s0.count(), s0.fills(), s0.inputs()));
        assertNotNull(CraftOrder.check(withSteps(good, steps), R, 2048));
        steps.set(0, new Order.CraftStep(s0.recipe(), s0.result(), s0.count() + 1, s0.fills(), s0.inputs()));
        assertNotNull(CraftOrder.check(withSteps(good, steps), R, 2048));
        // an input no cell of the recipe accepts
        steps.set(0, new Order.CraftStep(s0.recipe(), s0.result(), s0.count(), s0.fills(), List.of(q("minecraft:diamond", 3))));
        assertNotNull(CraftOrder.check(withSteps(good, steps), R, 2048));
        // no fills
        steps.set(0, new Order.CraftStep(s0.recipe(), s0.result(), s0.count(), 0, s0.inputs()));
        assertNotNull(CraftOrder.check(withSteps(good, steps), R, 2048));
        // the cap
        assertNotNull(CraftOrder.check(good, R, 4));
        // a take id that is not a plain id
        List<Order.Qty> take = new ArrayList<>(good.take());
        take.set(0, q("minecraft:spruce_log#ab12", 3));
        assertNotNull(CraftOrder.check(new Order.Craft(1, "1.21.4", R.hash(), good.lines(), good.steps(), take, good.leftovers(), good.fills(), false), R, 2048));
        // unreadable, or missing
        assertNotNull(CraftOrder.check(Order.Craft.unreadable(), R, 2048));
        assertNotNull(CraftOrder.check(null, R, 2048));
        // a bot with no table at all matches nothing
        assertNotNull(CraftOrder.check(good, Recipes.empty(), 2048));
    }

    private static Order.Craft block(int lineCount, int stepFills) {
        Order.CraftStep step = new Order.CraftStep("minecraft:spruce_stairs", STAIRS, 4, stepFills, List.of(q(PLANKS, 6)));
        return new Order.Craft(1, "1.21.4", R.hash(), List.of(q(STAIRS, lineCount)), List.of(step), List.of(q(PLANKS, 6)), List.of(), stepFills, false);
    }

    @Test
    void aCraftLineMustBeCoveredByItsStepsAndWithinTheLimits() {
        assertNull(CraftOrder.check(block(8, 2), R, 2048));
        assertNotNull(CraftOrder.check(block(9, 2), R, 2048), "2 fills of 4 make 8, not 9");
        assertNotNull(CraftOrder.check(block(100_001, 30_000), R, 100_000), "a line over 100,000");
        // Made by the steps or taken from the stash as itself
        Order.Craft taken = new Order.Craft(1, "1.21.4", R.hash(), List.of(q(PLANKS, 4)), List.of(), List.of(q(PLANKS, 4)), List.of(), 0, false);
        assertNull(CraftOrder.check(taken, R, 10));
        // The lines together
        List<Order.Qty> lines = new ArrayList<>(), take = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            lines.add(q("minecraft:item_" + i, 100_000));
            take.add(q("minecraft:item_" + i, 100_000));
        }
        Order.Craft many = new Order.Craft(1, "1.21.4", R.hash(), lines, List.of(), take, List.of(), 0, false);
        assertTrue(CraftOrder.check(many, R, 10).contains("in all"));
    }

    @Test
    void thePlainIdRuleIsTheServers() {
        assertTrue(CraftOrder.plain("minecraft:stone"));
        assertTrue(CraftOrder.plain("mod_x:a/b.c-d"));
        assertFalse(CraftOrder.plain("minecraft:spruce_log#ab12"));
        assertFalse(CraftOrder.plain("stone"));
        assertFalse(CraftOrder.plain("Minecraft:Stone"));
        assertFalse(CraftOrder.plain(null));
    }

    @Test
    void everyReasonHasAWordAndTheContractsCodesAreAllThere() {
        assertEquals(11, CraftOrder.REASONS.size());
        for (String r : CraftOrder.REASONS) assertNotNull(CraftOrder.describe(r));
        assertEquals("zzz", CraftOrder.describe("zzz"));
        assertTrue(CraftOrder.REASONS.contains("recipe_mismatch") && CraftOrder.REASONS.contains("table_blocked"));
    }

    // The block as the server sends it

    private static JsonObject order(String craft) {
        return JsonParser.parseString("{\"id\":\"o-1\",\"lines\":[{\"type\":\"item\",\"key\":\"minecraft:dirt\",\"count\":4},"
            + "{\"type\":\"craft\",\"key\":\"minecraft:spruce_stairs\",\"count\":8}]," + craft + "}").getAsJsonObject();
    }

    private static final String BLOCK = "\"craft\":{\"v\":1,\"mc\":\"1.21.4\",\"recipes\":\"" + "ab".repeat(20) + "\","
        + "\"lines\":[{\"key\":\"minecraft:spruce_stairs\",\"count\":8}],"
        + "\"steps\":[{\"recipe\":\"minecraft:spruce_planks\",\"result\":\"minecraft:spruce_planks\",\"count\":4,\"fills\":3,\"inputs\":[{\"id\":\"minecraft:spruce_log\",\"count\":3}]}],"
        + "\"take\":[{\"id\":\"minecraft:spruce_log\",\"count\":3}],\"leftovers\":[{\"id\":\"minecraft:spruce_planks\",\"count\":4}],\"fills\":5}";

    @Test
    void aCraftLineAndItsBlockAreRead() {
        Order o = Order.parse(order(BLOCK));
        assertNotNull(o);
        assertTrue(o.hasCraft());
        assertEquals(1, o.craftLines().size());
        assertEquals("minecraft:spruce_stairs", o.craftLines().get(0).key());
        assertEquals(2, o.lines.size());
        Order.Craft c = o.craft;
        assertNotNull(c);
        assertFalse(c.malformed());
        assertEquals(1, c.v());
        assertEquals("ab".repeat(20), c.recipes());
        assertEquals(1, c.steps().size());
        assertEquals(3, c.steps().get(0).fills());
        assertEquals(new Order.Qty("minecraft:spruce_log", 3), c.take().get(0));
        assertEquals(new Order.Qty("minecraft:spruce_planks", 4), c.leftovers().get(0));
        assertEquals(3, c.stepFills());
        assertEquals(8, o.requested().get("minecraft:spruce_stairs"));
    }

    @Test
    void anOrderWithoutCraftLinesHasNoBlockAndNoCraft() {
        Order o = Order.parse(JsonParser.parseString("{\"id\":\"o-2\",\"lines\":[{\"type\":\"item\",\"key\":\"minecraft:dirt\",\"count\":4}],\"craft\":null}").getAsJsonObject());
        assertNotNull(o);
        assertFalse(o.hasCraft());
        assertNull(o.craft);
        assertTrue(o.craftLines().isEmpty());
        assertNull(Order.parse(JsonParser.parseString("{\"id\":\"o-3\",\"lines\":[{\"type\":\"item\",\"key\":\"minecraft:dirt\",\"count\":4}]}").getAsJsonObject()).craft);
    }

    @Test
    void aBlockThatIsThereButNotWhatTheContractSaysIsUnreadable() {
        for (String bad : new String[] {
            "\"craft\":\"x\"",
            "\"craft\":{\"v\":1}",
            "\"craft\":" + BLOCK.substring(8).replace("\"count\":3}],\"leftovers\"", "\"count\":-3}],\"leftovers\""),
            "\"craft\":" + BLOCK.substring(8).replace("\"fills\":3,", "\"fills\":\"x\","),
        }) {
            Order o = Order.parse(order(bad));
            assertNotNull(o, bad);
            assertTrue(o.hasCraft(), bad);
            assertNotNull(o.craft, bad);
            assertTrue(o.craft.malformed(), bad);
            assertNotNull(CraftOrder.check(o.craft, R, 2048), bad);
        }
        // A craft line with no block at all fails the check too
        assertNotNull(CraftOrder.check(Order.parse(order("\"x\":1")).craft, R, 2048));
    }

    @Test
    void aBlockWithTooManyStepsOrTakeEntriesIsUnreadable() {
        StringBuilder steps = new StringBuilder();
        for (int i = 0; i < 65; i++) {
            if (i > 0) steps.append(',');
            steps.append("{\"recipe\":\"minecraft:a\",\"result\":\"minecraft:a\",\"count\":1,\"fills\":1,\"inputs\":[]}");
        }
        String block = "\"craft\":{\"v\":1,\"mc\":\"1.21.4\",\"recipes\":\"" + "ab".repeat(20) + "\",\"lines\":[],\"steps\":[" + steps + "],\"take\":[],\"leftovers\":[],\"fills\":65}";
        assertTrue(Order.parse(order(block)).craft.malformed());
    }
}
