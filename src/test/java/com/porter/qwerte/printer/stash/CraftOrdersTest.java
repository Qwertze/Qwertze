package com.porter.qwerte.printer.stash;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.porter.qwerte.manager.model.CraftModel;
import com.porter.qwerte.printer.stash.CraftQuote.Count;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CraftOrdersTest {
    private static final String STASH = "srv\u0000main";

    private static final String QUOTE = """
        {"ok":true,"craft":{"canOrder":true,"reason":null,"message":null},
         "lines":[{"id":"minecraft:spruce_stairs","want":8,"craftable":8,"reason":null,"uses":[{"id":"minecraft:spruce_log","count":2}]},
                  {"id":"minecraft:spruce_fence","want":3,"craftable":0,"reason":"not enough Spruce Log","uses":[]},
                  {"id":"minecraft:oak_planks","want":100,"craftable":40,"reason":"not enough Oak Log","uses":[{"id":"minecraft:oak_log","count":10}]}],
         "take":[{"id":"minecraft:spruce_log","count":2},{"id":"minecraft:oak_log","count":10}],
         "leftovers":[{"id":"minecraft:spruce_planks","count":3}],"fills":4,"truncated":false}
        """;

    private static final Function<String, String> NAME = id -> {
        String p = id.substring(id.indexOf(':') + 1);
        StringBuilder sb = new StringBuilder();
        for (String w : p.split("_")) sb.append(sb.length() == 0 ? "" : " ").append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        return sb.toString();
    };

    private static CraftQuote.Quote quote() {
        return CraftQuote.parse(JsonParser.parseString(QUOTE).getAsJsonObject());
    }

    private static LinkedHashMap<String, Long> longs(Object... kv) {
        LinkedHashMap<String, Long> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], ((Number) kv[i + 1]).longValue());
        return m;
    }

    private static int maxStack(String id) {
        return id.endsWith("_bucket") ? 16 : 64;
    }

    // ---------- the quote ----------

    @Test
    void parsesTheQuote() {
        CraftQuote.Quote q = quote();
        assertTrue(q.canOrder());
        assertEquals("", q.reason());
        assertEquals(3, q.lines().size());
        assertEquals(8, q.lines().get(0).craftable());
        assertEquals(List.of(new Count("minecraft:spruce_log", 2)), q.lines().get(0).uses());
        assertEquals("not enough Spruce Log", q.lines().get(1).reason());
        assertEquals(4, q.fills());
        assertFalse(q.truncated());
        assertEquals(2, q.take().size());
    }

    @Test
    void aQuoteThatCanNotBeOrderedKeepsItsReason() {
        CraftQuote.Quote q = CraftQuote.parse(JsonParser.parseString(
            "{\"ok\":true,\"craft\":{\"canOrder\":false,\"reason\":\"no_table\",\"message\":\"Set a crafting table\"},\"lines\":[]}").getAsJsonObject());
        assertFalse(q.canOrder());
        assertEquals("no_table", q.reason());
        assertTrue(CraftQuote.codeText("no_table").contains("crafting table"));
        assertTrue(CraftQuote.codeText("no_bot").contains("1.15.0"));
        assertNull(CraftQuote.parse(new JsonObject()));
        assertNull(CraftQuote.parse(null));
    }

    @Test
    void theRequestAsksForTheMissingItemsAndTellsWhatTheOrderTakesItself() {
        JsonObject body = CraftQuote.body("srv", "main", longs("minecraft:spruce_stairs", 8, "minecraft:zero", 0), Map.of("minecraft:oak_planks", 60));
        assertEquals("srv", body.get("server").getAsString());
        assertEquals("main", body.get("stash").getAsString());
        assertEquals(1, body.getAsJsonArray("want").size());
        assertEquals("minecraft:spruce_stairs", body.getAsJsonArray("want").get(0).getAsJsonObject().get("id").getAsString());
        assertEquals(60, body.getAsJsonArray("direct").get(0).getAsJsonObject().get("count").getAsInt());
        // no item lines: no direct key
        assertFalse(CraftQuote.body("srv", "main", longs("minecraft:a", 1), Map.of()).has("direct"));
    }

    @Test
    void aRefusedOrderNamesTheCodeInCraft() {
        assertEquals("no_table", CraftQuote.refusalCode(JsonParser.parseString("{\"ok\":false,\"error\":\"x\",\"craft\":\"no_table\"}").getAsJsonObject()));
        assertEquals("", CraftQuote.refusalCode(JsonParser.parseString("{\"error\":\"x\"}").getAsJsonObject()));
        assertEquals("", CraftQuote.refusalCode(JsonParser.parseString("{\"craft\":{\"a\":1}}").getAsJsonObject()));
    }

    // ---------- quote to craft lines ----------

    @Test
    void theQuoteBecomesCraftLinesForTheCraftableAmounts() {
        CraftOrders.Applied a = CraftOrders.apply(quote(),
            longs("minecraft:spruce_stairs", 8, "minecraft:spruce_fence", 3, "minecraft:oak_planks", 100));
        assertEquals(longs("minecraft:spruce_stairs", 8, "minecraft:oak_planks", 40), a.craft());
        // what is left is still missing: the fence, and the planks the stash can't make
        assertEquals(longs("minecraft:spruce_fence", 3, "minecraft:oak_planks", 60), a.rest());
        assertEquals(List.of(new Count("minecraft:spruce_log", 2), new Count("minecraft:oak_log", 10)), a.uses());
        assertTrue(a.any());
    }

    @Test
    void neverMoreThanWhatIsWanted() {
        // the quote was for 100 oak planks but only 30 are wanted by now: 30 of the 40 craftable, and its uses scale (rounded up)
        CraftOrders.Applied a = CraftOrders.apply(quote(), longs("minecraft:oak_planks", 30));
        assertEquals(longs("minecraft:oak_planks", 30), a.craft());
        assertTrue(a.rest().isEmpty());
        assertEquals(List.of(new Count("minecraft:oak_log", 8)), a.uses());
    }

    @Test
    void nothingIsOrderedFromAQuoteThatCanNotBeOrdered() {
        CraftQuote.Quote q = new CraftQuote.Quote(false, "no_table", "", quote().lines(), List.of(), List.of(), 0, false);
        CraftOrders.Applied a = CraftOrders.apply(q, longs("minecraft:spruce_stairs", 8));
        assertFalse(a.any());
        assertEquals(longs("minecraft:spruce_stairs", 8), a.rest());
        // an item the quote did not mention stays missing
        assertEquals(longs("minecraft:x", 5), CraftOrders.apply(quote(), longs("minecraft:x", 5)).rest());
        assertFalse(CraftOrders.apply(null, longs("minecraft:x", 5)).any());
    }

    // ---------- the order that fits the limits ----------

    @Test
    void anOrderHoldsTheItemLinesAndTheCraftLines() {
        CraftOrders.Draft d = new CraftOrders.Draft(STASH, longs("minecraft:oak_planks", 64), longs("minecraft:spruce_stairs", 8),
            List.of(new Count("minecraft:spruce_log", 2)));
        List<CraftOrders.Built> built = CraftOrders.assemble(List.of(d), 9, 27, CraftOrdersTest::maxStack);
        assertEquals(1, built.size());
        CraftOrders.Built b = built.get(0);
        assertEquals(Map.of("minecraft:oak_planks", 64), b.direct());
        assertEquals(Map.of("minecraft:spruce_stairs", 8), b.craft());
        assertEquals(List.of(new Count("minecraft:spruce_log", 2)), b.uses());
        assertEquals(1, b.shulkers());
    }

    @Test
    void withoutCraftLinesTheOrderIsTheOldItemOrder() {
        LinkedHashMap<String, Long> want = longs("minecraft:a", 64L * 40, "minecraft:b", 64L * 40);
        List<CraftOrders.Built> built = CraftOrders.assemble(
            List.of(new CraftOrders.Draft(STASH, want, new LinkedHashMap<>(), List.of())), 2, 27, CraftOrdersTest::maxStack);
        assertEquals(OrderBatch.cap(want, CraftOrdersTest::maxStack, new OrderBatch.Limits(2, 27)), built.get(0).direct());
        assertTrue(built.get(0).craft().isEmpty());
        assertEquals(2, built.get(0).shulkers());
    }

    @Test
    void craftLinesTakeTheSlotsTheItemLinesLeave() {
        // one shulker is 27 slots: 26 stacks of planks leave one slot, so 64 of 100 stairs fit
        CraftOrders.Draft d = new CraftOrders.Draft(STASH, longs("minecraft:oak_planks", 64 * 26), longs("minecraft:spruce_stairs", 100),
            List.of(new Count("minecraft:spruce_log", 40)));
        CraftOrders.Built b = CraftOrders.assemble(List.of(d), 1, 27, CraftOrdersTest::maxStack).get(0);
        assertEquals(64 * 26, b.direct().get("minecraft:oak_planks"));
        assertEquals(64, b.craft().get("minecraft:spruce_stairs"));
        // the stash items it uses shrink with the crafting that was cut (40 * 64 / 100, rounded up)
        assertEquals(List.of(new Count("minecraft:spruce_log", 26)), b.uses());
        assertEquals(1, b.shulkers());
    }

    @Test
    void anItemOnlyShortfallStillLeavesRoomForCraftingInTheNextOrder() {
        // two stashes: the first order uses 1 shulker, the run has 1 left, so the second order (craft only) gets nothing
        CraftOrders.Draft first = new CraftOrders.Draft(STASH, longs("minecraft:a", 64 * 27), new LinkedHashMap<>(), List.of());
        CraftOrders.Draft second = new CraftOrders.Draft("srv\u0000two", new LinkedHashMap<>(), longs("minecraft:b", 8), List.of(new Count("minecraft:c", 1)));
        List<CraftOrders.Built> built = CraftOrders.assemble(List.of(first, second), 9, 1, CraftOrdersTest::maxStack);
        assertEquals(1, built.size());
        assertEquals(STASH, built.get(0).stashKey());
    }

    @Test
    void aCraftOnlyOrderIsKept() {
        CraftOrders.Draft d = new CraftOrders.Draft(STASH, new LinkedHashMap<>(), longs("minecraft:spruce_stairs", 8), List.of(new Count("minecraft:spruce_log", 2)));
        List<CraftOrders.Built> built = CraftOrders.assemble(List.of(d), 9, 27, CraftOrdersTest::maxStack);
        assertEquals(1, built.size());
        assertTrue(built.get(0).direct().isEmpty());
        assertEquals(Map.of("minecraft:spruce_stairs", 8), built.get(0).craft());
    }

    @Test
    void craftLinesCountForTheShulkersLikeItemLines() {
        // 8 stairs and 8 buckets (stack 16) are 1 slot each; with 26 slots of items that is 28 slots: 2 shulkers
        assertEquals(2, CraftOrders.shulkers(Map.of("minecraft:oak_planks", 64 * 26), Map.of("minecraft:stairs", 8, "minecraft:water_bucket", 8), CraftOrdersTest::maxStack));
        assertEquals(1, CraftOrders.shulkers(Map.of("minecraft:oak_planks", 64 * 26), Map.of("minecraft:stairs", 8), CraftOrdersTest::maxStack));
    }

    // ---------- words ----------

    @Test
    void theChatSaysWhatWasOrderedAndWhatIsCrafted() {
        String s = CraftOrders.summary(longs("minecraft:oak_planks", 64), longs("minecraft:spruce_stairs", 8), List.of(new Count("minecraft:spruce_log", 2)), NAME);
        assertEquals("64 Oak Planks + crafting 8 Spruce Stairs (from 2 Spruce Log)", s);
        assertEquals("64 Oak Planks", CraftOrders.summary(longs("minecraft:oak_planks", 64), new LinkedHashMap<>(), List.of(), NAME));
        assertEquals("crafting 8 Spruce Stairs (from 2 Spruce Log, 3 Stick)",
            CraftOrders.summary(new LinkedHashMap<>(), longs("minecraft:spruce_stairs", 8),
                List.of(new Count("minecraft:spruce_log", 2), new Count("minecraft:stick", 3)), NAME));
        assertEquals("crafting 8 Spruce Stairs", CraftOrders.summary(new LinkedHashMap<>(), longs("minecraft:spruce_stairs", 8), List.of(), NAME));
    }

    @Test
    void longListsAreCut() {
        LinkedHashMap<String, Long> many = new LinkedHashMap<>();
        for (int i = 0; i < 7; i++) many.put("minecraft:item_" + i, 1L);
        String s = CraftOrders.summary(many, new LinkedHashMap<>(), List.of(), NAME);
        assertTrue(s.endsWith("and 3 more"), s);
    }

    @Test
    void aFinishedOrdersCraftingIsToldInPlainWords() {
        CraftModel.Result r = new CraftModel.Result(11,
            List.of(new CraftModel.Made("minecraft:spruce_stairs", 8, 8), new CraftModel.Made("minecraft:oak_planks", 40, 24)),
            List.of(new CraftModel.Shortfall("minecraft:spruce_fence", 3, "ingredients_short")), List.of(), List.of());
        assertEquals("made 8 Spruce Stairs, 24 of 40 Oak Planks; could not make 3 Spruce Fence - the ingredients ran short",
            CraftOrders.resultNote(r, NAME));
        assertEquals("", CraftOrders.resultNote(null, NAME));
        assertEquals("includes crafted 8 Spruce Stairs", CraftOrders.craftedWords(longs("minecraft:spruce_stairs", 8), NAME));
        assertEquals("", CraftOrders.craftedWords(new LinkedHashMap<>(), NAME));
    }

    // ---------- the ledger ----------

    @Test
    void craftedItemsOnTheWayAreSupplySoThePrinterDoesNotOrderThemAgain() {
        OrderLedger ledger = new OrderLedger();
        // 64 planks ordered as items and 8 stairs ordered crafted: the ledger holds both
        Map<String, Long> supply = new LinkedHashMap<>();
        supply.put("minecraft:oak_planks", 64L);
        supply.put("minecraft:spruce_stairs", 8L);
        ledger.placed(new OrderLedger.Placed("o-1", STASH, supply, 0, Map.of("minecraft:spruce_stairs", 8L)));
        assertEquals(supply, ledger.covered(0));
        assertTrue(ledger.onOpenOrder("minecraft:spruce_stairs"));
        assertEquals(STASH, ledger.stashFor("minecraft:spruce_stairs", 0));
        // the build still needs 8 stairs and carries none: they are covered, nothing is short
        Map<String, Long> need = Map.of("minecraft:spruce_stairs", 8L, "minecraft:oak_planks", 64L);
        assertTrue(LowStock.shortfall(need, Map.of(), ledger.covered(0), 1).isEmpty());
        assertFalse(LowStock.triggered(need, Map.of(), ledger.covered(0), 1));
        // without the craft line the stairs would be ordered again
        OrderLedger without = new OrderLedger();
        without.placed(new OrderLedger.Placed("o-2", STASH, Map.of("minecraft:oak_planks", 64L), 0));
        assertEquals(8L, LowStock.shortfall(need, Map.of(), without.covered(0), 1).get("minecraft:spruce_stairs"));
    }

    @Test
    void aDeliveredCraftedOrderStaysCoveredUntilCollected() {
        OrderLedger ledger = new OrderLedger();
        ledger.placed(new OrderLedger.Placed("o-1", STASH, Map.of("minecraft:spruce_stairs", 8L), 0, Map.of("minecraft:spruce_stairs", 8L)));
        List<OrderLedger.Event> ev = ledger.update(STASH,
            List.of(new OrderLedger.Seen("o-1", "delivered", "", "made 8 Spruce Stairs")), 1000);
        assertEquals(1, ev.size());
        assertEquals(OrderLedger.Kind.DELIVERED, ev.get(0).kind());
        assertEquals("made 8 Spruce Stairs", ev.get(0).craftNote());
        assertEquals(Map.of("minecraft:spruce_stairs", 8L), ev.get(0).order().craft());
        assertEquals(Map.of("minecraft:spruce_stairs", 8L), ledger.covered(2000));
    }

    @Test
    void aDeliveryWithUncraftedLinesStopsCountingThemAndRestsTheCrafting() {
        OrderLedger ledger = new OrderLedger();
        Map<String, Long> supply = new LinkedHashMap<>();
        supply.put("minecraft:oak_planks", 64L);
        supply.put("minecraft:spruce_stairs", 8L);
        supply.put("minecraft:spruce_fence", 3L);
        Map<String, Long> craft = new LinkedHashMap<>();
        craft.put("minecraft:spruce_stairs", 8L);
        craft.put("minecraft:spruce_fence", 3L);
        ledger.placed(new OrderLedger.Placed("o-1", STASH, supply, 0, craft));
        // the bot made the stairs, and said the fence was short
        List<OrderLedger.Event> ev = ledger.update(STASH, List.of(new OrderLedger.Seen("o-1", "delivered", "", "",
            Map.of("minecraft:spruce_stairs", 8L), Map.of("minecraft:spruce_fence", 3L))), 1000);
        assertEquals(OrderLedger.Kind.DELIVERED, ev.get(0).kind());
        Map<String, Long> covered = ledger.covered(2000);
        assertEquals(64L, covered.get("minecraft:oak_planks"));
        assertEquals(8L, covered.get("minecraft:spruce_stairs"));
        assertFalse(covered.containsKey("minecraft:spruce_fence"));
        // the fence is not crafted again straight away, the stairs are not held back
        assertTrue(ledger.craftBlocked(STASH, "minecraft:spruce_fence", 2000));
        assertFalse(ledger.craftBlocked(STASH, "minecraft:spruce_stairs", 2000));
        assertFalse(ledger.craftBlocked(STASH, "minecraft:spruce_fence", 1000 + OrderLedger.CRAFT_SHORT_BLOCK_MS + 1));
    }

    @Test
    void aDeliveryWithMadeCountsOnlyBlocksWhatFellShort() {
        OrderLedger ledger = new OrderLedger();
        ledger.placed(new OrderLedger.Placed("o-1", STASH, Map.of("minecraft:spruce_stairs", 8L), 0, Map.of("minecraft:spruce_stairs", 8L)));
        // 5 of 8 made, no short list: the rest is the difference
        ledger.update(STASH, List.of(new OrderLedger.Seen("o-1", "delivered", "", "",
            Map.of("minecraft:spruce_stairs", 5L), Map.of())), 1000);
        assertEquals(5L, ledger.covered(2000).get("minecraft:spruce_stairs"));
        assertTrue(ledger.craftBlocked(STASH, "minecraft:spruce_stairs", 2000));
        // a delivery that says nothing about crafting is taken as complete
        OrderLedger quiet = new OrderLedger();
        quiet.placed(new OrderLedger.Placed("o-2", STASH, Map.of("minecraft:spruce_stairs", 8L), 0, Map.of("minecraft:spruce_stairs", 8L)));
        quiet.update(STASH, List.of(new OrderLedger.Seen("o-2", "delivered", "")), 1000);
        assertEquals(8L, quiet.covered(2000).get("minecraft:spruce_stairs"));
        assertFalse(quiet.craftBlocked(STASH, "minecraft:spruce_stairs", 2000));
    }

    @Test
    void thePlacedOrderKnowsItsItemLinesApartFromTheCraftedPart() {
        OrderLedger.Placed p = new OrderLedger.Placed("o", STASH, Map.of("minecraft:a", 70L, "minecraft:b", 8L), 0, Map.of("minecraft:a", 6L, "minecraft:b", 8L));
        assertEquals(Map.of("minecraft:a", 64L), p.direct());
    }

    @Test
    void usesShrinkWithTheCraftingThatIsKept() {
        List<Count> uses = List.of(new Count("minecraft:spruce_log", 10), new Count("minecraft:stick", 1));
        assertEquals(List.of(new Count("minecraft:spruce_log", 5), new Count("minecraft:stick", 1)), CraftOrders.scaledUses(uses, 4, 8));
        assertEquals(uses, CraftOrders.scaledUses(uses, 8, 8));
        assertTrue(CraftOrders.scaledUses(uses, 0, 8).isEmpty());
    }

    @Test
    void hugeCountsFromTheServerAreClampedAndNeverOverflow() {
        CraftQuote.Quote q = CraftQuote.parse(JsonParser.parseString(
            "{\"craft\":{\"canOrder\":true},\"lines\":[{\"id\":\"minecraft:a\",\"want\":9000000000000000000,\"craftable\":9000000000000000000,"
                + "\"uses\":[{\"id\":\"minecraft:b\",\"count\":9000000000000000000}]}],\"fills\":9000000000000000000}").getAsJsonObject());
        assertEquals(CraftQuote.MAX_COUNT, q.lines().get(0).craftable());
        assertEquals(CraftQuote.MAX_COUNT, q.lines().get(0).uses().get(0).count());
        CraftOrders.Applied a = CraftOrders.apply(q, new LinkedHashMap<>(Map.of("minecraft:a", CraftQuote.MAX_COUNT / 2)));
        assertEquals(CraftQuote.MAX_COUNT / 2, a.craft().get("minecraft:a"));
        assertEquals(CraftQuote.MAX_COUNT / 2, a.uses().get(0).count());
        assertEquals(Long.MAX_VALUE / 2, CraftOrders.mulDivCeil(Long.MAX_VALUE, Long.MAX_VALUE, 1));
        assertEquals(3, CraftOrders.mulDivCeil(5, 5, 10));
    }

    @Test
    void idsThatAreNotItemIdsAreDropped() {
        CraftQuote.Quote q = CraftQuote.parse(JsonParser.parseString(
            "{\"craft\":{\"canOrder\":true},\"lines\":[{\"id\":\"Minecraft:A B\",\"craftable\":1},{\"id\":\"minecraft:ok\",\"craftable\":1,"
                + "\"uses\":[{\"id\":\"bad id\",\"count\":1},{\"id\":\"minecraft:fine\",\"count\":1}]}]}").getAsJsonObject());
        assertEquals(1, q.lines().size());
        assertEquals(1, q.lines().get(0).uses().size());
        assertTrue(CraftQuote.validId("minecraft:oak_planks"));
        assertFalse(CraftQuote.validId("oak_planks"));
        assertFalse(CraftQuote.validId(null));
    }

    @Test
    void aCodeWeDoNotKnowIsNeverShownInChat() {
        String words = CraftQuote.codeText("&4evil server text");
        assertFalse(words.contains("evil"), words);
        assertEquals("crafting is not available for this stash", words);
    }

    @Test
    void anOrderWithCraftLinesIsFoundAgainOnlyWithTheSameCraftLines() {
        Map<String, Long> items = Map.of("minecraft:oak_planks", 64L);
        Map<String, Long> craft = Map.of("minecraft:spruce_stairs", 8L);
        OrderAdopt.Listed both = new OrderAdopt.Listed("o-1", true, true, "me", items, craft);
        OrderAdopt.Listed itemsOnly = new OrderAdopt.Listed("o-2", true, true, "me", items, Map.of());
        assertEquals("o-1", OrderAdopt.find(List.of(itemsOnly, both), "me", items, craft, id -> false));
        assertEquals("o-2", OrderAdopt.find(List.of(itemsOnly, both), "me", items, Map.of(), id -> false));
        assertNull(OrderAdopt.find(List.of(itemsOnly), "me", items, craft, id -> false));
        // the old signature means "no craft lines"
        assertEquals("o-2", OrderAdopt.find(List.of(both, itemsOnly), "me", items, id -> false));
        OrderAdopt.Outcome none = OrderAdopt.resolve(List.of(), "me", items, craft, id -> false, 2);
        assertNull(none.id());
        assertEquals(2, none.chargeShulkers());
        assertNotNull(OrderAdopt.resolve(List.of(both), "me", items, craft, id -> false, 2).id());
    }
}
