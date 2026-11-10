package com.porter.qwerte.craft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.porter.qwerte.stash.CraftSettings;
import com.porter.qwerte.stash.Order;
import com.porter.qwerte.stash.StashIndex;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The pure parts of the craft-order runner: the gate (claim and heartbeat agree), the lots of an order, the end-of-order wait, the table swap, the
 * table decision, the hourly fills, and a whole order's chunks run by the same calls the runner makes.
 */
class CraftRunnerPartsTest {
    private static final Recipes R = TestRecipes.table();
    private static final String LOG = "minecraft:spruce_log", STAIRS = "minecraft:spruce_stairs", SLAB = "minecraft:spruce_slab", PLANKS = "minecraft:spruce_planks";

    // The gate

    @Test
    void theGateIsOnAndTheClaimAndTheHeartbeatAlwaysAgree() {
        assertTrue(CraftOrder.EXECUTES, "the runner exists");
        for (boolean crafting : new boolean[] {false, true}) {
            for (boolean setting : new boolean[] {false, true}) {
                for (boolean table : new boolean[] {false, true}) {
                    boolean capable = CraftCapability.capable(crafting, setting, true, table);
                    assertEquals(crafting && setting && table, capable);
                    JsonObject claim = new JsonObject();
                    CraftCapability.claimFlag(claim, capable, false);
                    StashIndex.Craft craft = CraftCapability.heartbeat(capable, "a".repeat(40), "1.21.4", "ok", 2048, List.of("1,2,3"), null);
                    byte[] body = StashIndex.heartbeat(new StashIndex.Built("s", "t", "Bot", "ix", new byte[0]), null, null, -1, null, null, null, null, null, null, null, null, craft);
                    JsonObject hb = JsonParser.parseString(new String(body, StandardCharsets.UTF_8)).getAsJsonObject();
                    assertEquals(capable, claim.has("craftOrders") && claim.get("craftOrders").getAsInt() == 1);
                    assertEquals(capable, hb.has("craftOrders") && hb.get("craftOrders").isJsonObject() && hb.getAsJsonObject("craftOrders").get("enabled").getAsBoolean());
                    if (!capable) assertTrue(hb.has("craftOrders") && hb.get("craftOrders").isJsonNull(), "an explicit null: never given a craft order");
                    else assertEquals(2048, hb.getAsJsonObject("craftOrders").get("maxFills").getAsInt());
                }
            }
        }
    }

    // The lots of an order

    private static Order order(Order.Craft craft, Order.Line... lines) {
        return new Order("o-1", List.of(lines), null, false, null, craft);
    }

    private static Order.Craft stairsBlock(int fills) {
        Order.CraftStep planks = new Order.CraftStep("minecraft:spruce_planks", PLANKS, 4, 3, List.of(new Order.Qty(LOG, 3)));
        Order.CraftStep stairs = new Order.CraftStep("minecraft:spruce_stairs", STAIRS, 4, fills, List.of(new Order.Qty(PLANKS, 12)));
        return new Order.Craft(1, "1.21.4", R.hash(), List.of(new Order.Qty(STAIRS, 8)), List.of(planks, stairs), List.of(new Order.Qty(LOG, 3)), List.of(), 3 + fills, false);
    }

    @Test
    void craftLinesBecomeLoadsOfAStackEachMarkedToCraft() {
        Order o = order(stairsBlock(2), new Order.Line(Order.CRAFT, STAIRS, 8));
        CraftLots.Prepared p = CraftLots.prepare(o, R, 2048, id -> 64);
        assertFalse(p.refused());
        assertEquals(1, p.lots().size());
        assertEquals(Order.Lot.toCraft(STAIRS, 8), p.lots().get(0));
        assertTrue(p.lots().get(0).craft());
        // 130 of a 64-stack item: 64 + 64 + 2
        Order big = order(new Order.Craft(1, "1.21.4", R.hash(), List.of(new Order.Qty(PLANKS, 130)), List.of(), List.of(new Order.Qty(PLANKS, 130)), List.of(), 0, false),
            new Order.Line(Order.CRAFT, PLANKS, 130));
        assertEquals(List.of(64, 64, 2), CraftLots.prepare(big, R, 2048, id -> 64).lots().stream().map(Order.Lot::count).toList());
        assertNull(CraftLots.prepare(order(null, new Order.Line(Order.ITEM, "minecraft:dirt", 4)), R, 2048, id -> 64), "no craft lines: nothing to prepare");
    }

    @Test
    void aFailedCheckOrTooManyLoadsMakesEveryCraftLineShortAndNothingIsCrafted() {
        Order wrongHash = order(new Order.Craft(1, "1.21.4", "0".repeat(40), List.of(new Order.Qty(STAIRS, 8)), List.of(), List.of(), List.of(), 0, false), new Order.Line(Order.CRAFT, STAIRS, 8));
        CraftLots.Prepared p = CraftLots.prepare(wrongHash, R, 2048, id -> 64);
        assertTrue(p.refused());
        assertTrue(p.lots().isEmpty());
        assertTrue(p.ledger().refusedAtStart());
        assertEquals("8 item(s) not crafted: recipe_mismatch", p.ledger().note());
        // 5000 loads of one-high stacks: past the 4096 loads one order may make
        Order.CraftStep step = new Order.CraftStep("minecraft:spruce_stairs", STAIRS, 4, 1250, List.of(new Order.Qty(PLANKS, 6)));
        Order many = order(new Order.Craft(1, "1.21.4", R.hash(), List.of(new Order.Qty(STAIRS, 5000)), List.of(step), List.of(new Order.Qty(PLANKS, 7500)), List.of(), 1250, false),
            new Order.Line(Order.CRAFT, STAIRS, 5000));
        CraftLots.Prepared q = CraftLots.prepare(many, R, 2048, id -> 1);
        assertTrue(q.refused());
        assertTrue(q.refusal().contains("loads"));
    }

    @Test
    void theNextLoadToMakeIsTheFirstLineInOrderThenTheFirstInTheBin() {
        Order.Craft c = new Order.Craft(1, "1.21.4", R.hash(), List.of(new Order.Qty(SLAB, 6), new Order.Qty(STAIRS, 8)), List.of(), List.of(), List.of(), 0, false);
        CraftLedger l = new CraftLedger(c, List.of(new Order.Line(Order.CRAFT, SLAB, 6), new Order.Line(Order.CRAFT, STAIRS, 8)), R, 2048);
        List<Order.Lot> bin = new ArrayList<>(List.of(Order.Lot.toCraft(STAIRS, 8), new Order.Lot("minecraft:dirt", 64), Order.Lot.toCraft(SLAB, 6)));
        assertEquals(2, CraftLots.next(bin, l), "the slab line comes first in the order");
        bin.set(2, bin.get(2).made(6));
        assertTrue(bin.get(2).crafted());
        assertEquals(0, CraftLots.next(bin, l));
        bin.set(0, bin.get(0).withCount(0));
        assertEquals(-1, CraftLots.next(bin, l), "a load of nothing is not made");
        assertEquals(Order.Lot.NONE, new Order.Lot("minecraft:dirt", 1).craftState());
    }

    // The end of an order

    @Test
    void theReportWaitsForTheCarriedItemsToGoBackAndCountsWhatDid() {
        CraftEnd e = new CraftEnd(1000, Map.of(PLANKS, 3L, LOG, 5L));
        assertFalse(e.done(2000, Map.of(PLANKS, 3L, LOG, 5L)));
        assertFalse(e.done(2000, Map.of(PLANKS, 1L)));
        assertEquals(Map.of(PLANKS, 2L, LOG, 5L), e.returned(Map.of(PLANKS, 1L)));
        assertTrue(e.done(2000, Map.of()));
        assertEquals(Map.of(PLANKS, 3L, LOG, 5L), e.returned(Map.of()));
        // Time is the limit: after 90 s what is still carried is simply not counted as returned
        assertFalse(e.done(1000 + CraftEnd.RETURN_WAIT_MS - 1, Map.of(PLANKS, 3L)));
        assertTrue(e.done(1000 + CraftEnd.RETURN_WAIT_MS, Map.of(PLANKS, 3L)));
        assertEquals(Map.of(LOG, 5L), e.returned(Map.of(PLANKS, 3L)));
        assertEquals(90_000, CraftEnd.RETURN_WAIT_MS);
        assertTrue(new CraftEnd(0, Map.of()).done(1, Map.of()));
        assertTrue(new CraftEnd(0, Map.of()).nothingCarried());
    }

    @Test
    void whatAChunkEndingAfterTheOrderCarriesRaisesTheBaseline() {
        CraftEnd e = new CraftEnd(0, Map.of(PLANKS, 1L));
        e.raise(Map.of(PLANKS, 4L, STAIRS, 2L));
        assertEquals(Map.of(PLANKS, 4L, STAIRS, 2L), e.returned(Map.of()));
        assertTrue(e.timeUp(CraftEnd.RETURN_WAIT_MS));
        assertFalse(e.timeUp(1));
    }

    // The table

    @Test
    void theWebsiteTableStandsInOnlyWhileSwappedAndTheSavedOneIsAlwaysTheUsers() {
        TableSwap<String> swap = new TableSwap<>();
        assertEquals("mine", swap.persistedPos("mine"));
        swap.engage("mine", "found");
        assertTrue(swap.engaged());
        // While the website's table is in the live fields, the file gets the user's
        assertEquals("mine", swap.persistedPos("website"));
        assertEquals("found", swap.persistedSource("set"));
        swap.engage("website", "set");
        assertEquals("mine", swap.savedPos(), "engaging twice keeps the first");
        swap.release();
        assertFalse(swap.engaged());
        assertEquals("live", swap.persistedPos("live"));
        // A user with no table of their own
        swap.engage(null, null);
        assertNull(swap.persistedPos("website"));
        assertEquals("", swap.persistedSource("set"));
    }

    @Test
    void theTableDecisionIsMadeBeforeWalking() {
        CraftSettings.Table t = new CraftSettings.Table(10, 64, 10, "minecraft:overworld");
        int[] area = {0, 60, 0, 20, 70, 20};
        String dim = "ResourceKey[minecraft:dimension / minecraft:overworld]";
        assertNull(CraftTable.problem(t, dim, area, dim, true, true));
        assertNull(CraftTable.problem(t, dim, area, dim, false, false), "not loaded yet: walked to");
        assertEquals(CraftOrder.NO_TABLE, CraftTable.problem(null, dim, area, dim, true, true));
        assertEquals(CraftOrder.TABLE_BLOCKED, CraftTable.problem(t, dim, null, dim, true, true), "no area");
        assertEquals(CraftOrder.TABLE_BLOCKED, CraftTable.problem(t, "", area, dim, true, true), "no dimension");
        assertEquals(CraftOrder.TABLE_BLOCKED, CraftTable.problem(new CraftSettings.Table(500, 64, 10, "minecraft:overworld"), dim, area, dim, true, true));
        assertEquals(CraftOrder.TABLE_BLOCKED, CraftTable.problem(t, dim, area, "ResourceKey[minecraft:dimension / minecraft:the_nether]", true, true), "the bot is elsewhere");
        assertEquals(CraftOrder.TABLE_MISSING, CraftTable.problem(t, dim, area, dim, true, false));
        // The words the crafter's reason mapping understands
        assertEquals(CraftOrder.NO_TABLE, CraftOrder.reasonOf(CraftTable.failureText(CraftOrder.NO_TABLE), false, false));
        assertEquals(CraftOrder.TABLE_MISSING, CraftOrder.reasonOf(CraftTable.failureText(CraftOrder.TABLE_MISSING), false, false));
        assertEquals(CraftOrder.TABLE_BLOCKED, CraftOrder.reasonOf(CraftTable.failureText(CraftOrder.TABLE_BLOCKED), false, false));
    }

    @Test
    void theFillsOfTheLastHourAreCounted() {
        CraftFills f = new CraftFills();
        f.add(0, 100);
        f.add(1_000, 50);
        f.add(2_000, 0);
        assertEquals(150, f.lastHour(3_000));
        assertEquals(50, f.lastHour(3_600_001));
        assertEquals(0, f.lastHour(3_700_000));
    }

    // A whole order, the way the runner goes through it

    private static final class Sim {
        final CraftLedger ledger;
        final List<Order.Lot> lots;
        final Map<String, Long> carried = new HashMap<>();

        Sim(Order o) {
            CraftLots.Prepared p = CraftLots.prepare(o, R, 2048, id -> 64);
            assertFalse(p.refused(), p.refusal());
            ledger = p.ledger();
            lots = new ArrayList<>(p.lots());
        }

        /** One chunk, as the runner does it: sub-plan by CraftBatch, "crafted" at once. @return made, or -1 when nothing is left to make */
        int step(int freeSlots, long fillsLeft) {
            int li = CraftLots.next(lots, ledger);
            if (li < 0) return -1;
            Order.Lot lot = lots.get(li);
            ledger.setSurplus(carried);
            CraftBatch.Choice c = CraftBatch.choose(ledger, lot.key(), lot.count(), freeSlots, fillsLeft, id -> 64, m -> 1);
            if (!c.ok()) {
                ledger.addShort(lot.key(), lot.count(), c.reason());
                lots.set(li, lot.withCount(0));
                return 0;
            }
            ledger.chunkStarted();
            Map<String, Long> spent = new HashMap<>(c.plan().fromStock());
            // What the steps made and did not use is carried
            carried.clear();
            for (CraftPlan.Step st : c.plan().steps()) carried.merge(st.recipe().result(), st.made(), Long::sum);
            for (CraftPlan.Step st : c.plan().steps()) for (var e : st.uses().entrySet()) carried.merge(e.getKey(), -e.getValue(), Long::sum);
            carried.values().removeIf(n -> n <= 0);
            ledger.madeChunk(lot.key(), c.count(), c.plan().fills(), spent);
            lots.set(li, lot.made(c.count()));
            long left = carried.getOrDefault(lot.key(), 0L) - c.count();
            if (left > 0) carried.put(lot.key(), left);
            else carried.remove(lot.key());
            return c.count();
        }
    }

    @Test
    void anOrderOfTwoLinesIsMadeInLineOrderWithinTheBudgetAndReportsWhatItMadeAndSpent() {
        Order.CraftStep planks = new Order.CraftStep("minecraft:spruce_planks", PLANKS, 4, 3, List.of(new Order.Qty(LOG, 3)));
        Order.CraftStep stairs = new Order.CraftStep("minecraft:spruce_stairs", STAIRS, 4, 2, List.of(new Order.Qty(PLANKS, 12)));
        Order.CraftStep slab = new Order.CraftStep("minecraft:spruce_slab", SLAB, 6, 1, List.of(new Order.Qty(PLANKS, 3)));
        Order.Craft craft = new Order.Craft(1, "1.21.4", R.hash(), List.of(new Order.Qty(STAIRS, 8), new Order.Qty(SLAB, 6)), List.of(planks, stairs, slab),
            List.of(new Order.Qty(LOG, 4)), List.of(new Order.Qty(PLANKS, 1)), 6, false);
        assertNull(CraftOrder.check(craft, R, 2048));
        Sim sim = new Sim(order(craft, new Order.Line(Order.CRAFT, STAIRS, 8), new Order.Line(Order.CRAFT, SLAB, 6)));
        assertEquals(8, sim.step(27, 2048));
        assertEquals(6, sim.step(27, 2048));
        assertEquals(-1, sim.step(27, 2048));
        JsonObject r = sim.ledger.toResult();
        assertEquals(sim.ledger.fills(), r.get("fills").getAsLong());
        assertTrue(sim.ledger.fills() > 0);
        assertEquals(0, r.getAsJsonArray("short").size());
        assertEquals(8, r.getAsJsonArray("made").get(0).getAsJsonObject().get("made").getAsInt());
        assertEquals(6, r.getAsJsonArray("made").get(1).getAsJsonObject().get("made").getAsInt());
        assertTrue(sim.lots.stream().allMatch(Order.Lot::crafted));
        assertNull(sim.ledger.note());
    }

    @Test
    void aLoadThatTheBudgetCannotMakeIsShortWithTheReasonAndTheOrderCarriesOn() {
        Order.CraftStep stairs = new Order.CraftStep("minecraft:spruce_stairs", STAIRS, 4, 2, List.of(new Order.Qty(PLANKS, 12)));
        Order.Craft craft = new Order.Craft(1, "1.21.4", R.hash(), List.of(new Order.Qty(STAIRS, 8)), List.of(stairs), List.of(new Order.Qty(PLANKS, 6)), List.of(), 2, false);
        Sim sim = new Sim(order(craft, new Order.Line(Order.CRAFT, STAIRS, 8)));
        // 6 planks make one fill of stairs: 4, not 8 (the rest is short)
        int made = sim.step(27, 2048);
        assertEquals(4, made);
        sim.ledger.addShort(STAIRS, 4, CraftOrder.INGREDIENTS_SHORT);
        assertEquals("4 item(s) not crafted: ingredients_short", sim.ledger.note());
        // No fills left: not even one chunk
        Sim capped = new Sim(order(craft, new Order.Line(Order.CRAFT, STAIRS, 8)));
        assertEquals(0, capped.step(27, 0));
        assertEquals("8 item(s) not crafted: cap", capped.ledger.note());
        // No room to carry: inventory_full
        Sim full = new Sim(order(craft, new Order.Line(Order.CRAFT, STAIRS, 8)));
        assertEquals(0, full.step(1, 2048));
        assertEquals("8 item(s) not crafted: inventory_full", full.ledger.note());
        assertNotNull(sim);
    }

    @Test
    void theLedgerSurvivesARestartAndNeverGrowsTheBudget() {
        Order.CraftStep stairs = new Order.CraftStep("minecraft:spruce_stairs", STAIRS, 4, 2, List.of(new Order.Qty(PLANKS, 12)));
        Order.CraftStep planks = new Order.CraftStep("minecraft:spruce_planks", PLANKS, 4, 3, List.of(new Order.Qty(LOG, 3)));
        Order.CraftStep lone = new Order.CraftStep("minecraft:stick", "minecraft:stick", 4, 1, List.of(new Order.Qty(PLANKS, 2)));
        Order.Craft craft = new Order.Craft(1, "1.21.4", R.hash(), List.of(new Order.Qty(STAIRS, 8)), List.of(planks, stairs, lone), List.of(new Order.Qty(LOG, 3)), List.of(), 6, false);
        Order o = order(craft, new Order.Line(Order.CRAFT, STAIRS, 8));
        Sim sim = new Sim(o);
        assertEquals(8, sim.step(27, 2048));
        JsonObject saved = sim.ledger.toSaved();
        CraftLedger back = CraftLots.prepare(o, R, 2048, id -> 64).ledger();
        back.restore(saved);
        assertTrue(back.budget().isEmpty() || back.budget().getOrDefault(LOG, 0L) <= 3, "never more than the take");
        assertEquals(sim.ledger.fills(), back.fills());
        assertEquals(sim.ledger.made(STAIRS), back.made(STAIRS));
        assertSame(R, back.table());
    }
}
