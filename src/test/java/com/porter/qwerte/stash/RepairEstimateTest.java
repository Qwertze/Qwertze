package com.porter.qwerte.stash;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.porter.qwerte.stash.RepairEstimate.Facts;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RepairEstimateTest {
    @Test
    void bottlesAreCeilOfDamageOver14() {
        assertEquals(0, RepairEstimate.bottlesFor(0));
        assertEquals(0, RepairEstimate.bottlesFor(-5));
        assertEquals(1, RepairEstimate.bottlesFor(1));
        assertEquals(1, RepairEstimate.bottlesFor(14));
        assertEquals(2, RepairEstimate.bottlesFor(15));
        assertEquals(112, RepairEstimate.bottlesFor(1560));
        assertEquals(Integer.MAX_VALUE / 14 + 1, RepairEstimate.bottlesFor(Integer.MAX_VALUE));
    }

    @Test
    void reasonsFollowTheContractOrder() {
        assertNull(RepairEstimate.reason(new Facts(true, 10, 1)));
        assertEquals("no Mending", RepairEstimate.reason(new Facts(false, 10, 1)));
        assertEquals("no Mending", RepairEstimate.reason(null));
        assertEquals("not damaged", RepairEstimate.reason(new Facts(true, 0, 1)));
        assertEquals("not a single item", RepairEstimate.reason(new Facts(true, 5, 64)));
        assertEquals("no Mending", RepairEstimate.reason(new Facts(false, 0, 64)));
        assertTrue(RepairEstimate.repairable(new Facts(true, 1, 1)));
        assertEquals(0, RepairEstimate.bottlesPerItem(new Facts(false, 100, 1)));
        assertEquals(8, RepairEstimate.bottlesPerItem(new Facts(true, 100, 1)));
    }

    @Test
    void olderBotsAreReadFromTheDetails() {
        assertTrue(RepairEstimate.facts(false, List.of("Unbreaking III", " mending "), 5, 1).mending());
        assertTrue(RepairEstimate.facts(false, List.of("Mending I"), 5, 1).mending());
        assertFalse(RepairEstimate.facts(false, List.of("Mendingx", "Sharpness V"), 5, 1).mending());
        assertTrue(RepairEstimate.facts(true, List.of(), 5, 1).mending());
        assertFalse(RepairEstimate.facts(false, null, 5, 1).mending());
    }

    @Test
    void detailsFallbackIsStrict() {
        assertTrue(RepairEstimate.detailsHaveMending(List.of("Mending IV")));
        assertTrue(RepairEstimate.detailsHaveMending(List.of("mending 2")));
        assertTrue(RepairEstimate.detailsHaveMending(List.of("  MENDING  ")));
        assertFalse(RepairEstimate.detailsHaveMending(List.of("Mending  I")), "two spaces");
        assertFalse(RepairEstimate.detailsHaveMending(List.of("Mending Iron")));
        assertFalse(RepairEstimate.detailsHaveMending(List.of("Mending -1")));
        assertFalse(RepairEstimate.detailsHaveMending(List.of("Not Mending")));
    }

    @Test
    void repairAllWantsOnlyDamagedLinesButAnOwnToggleAlwaysCounts() {
        List<Order.Line> lines = List.of(new Order.Line("item", "worn", 1), new Order.Line("item", "fresh", 1), new Order.Line("item", "stone", 64),
            new Order.Line("item", "fresh2", 1, true));
        Map<String, Facts> index = Map.of("worn", new Facts(true, 28, 1), "fresh", new Facts(true, 0, 1), "stone", new Facts(false, 0, 64),
            "fresh2", new Facts(true, 0, 1));
        RepairEstimate.Estimate e = RepairEstimate.estimate(lines, true, index::get, 10);
        assertEquals(List.of("worn", "fresh2"), e.lines().stream().map(RepairEstimate.LineEstimate::key).toList());
        assertEquals("not damaged", e.lines().get(1).reason());
        assertNull(RepairEstimate.estimate(lines.subList(1, 3), true, index::get, 10), "nothing damaged: no estimate");
    }

    @Test
    void aShulkerBoxItemLineIsNotASingleItem() {
        assertTrue(RepairEstimate.isShulkerKey("minecraft:red_shulker_box"));
        assertTrue(RepairEstimate.isShulkerKey("minecraft:shulker_box#ab12cd34"));
        assertFalse(RepairEstimate.isShulkerKey("minecraft:diamond_pickaxe"));
        assertEquals("not a single item", RepairEstimate.reason("minecraft:blue_shulker_box", new Facts(true, 5, 1)));
        RepairEstimate.Estimate e = RepairEstimate.estimate(List.of(new Order.Line("item", "minecraft:shulker_box", 2, true)), false, k -> new Facts(false, 0, 1), 0);
        assertEquals("not a single item", e.lines().get(0).reason());
    }

    @Test
    void estimateCountsOnlyWantedLines() {
        List<Order.Line> lines = List.of(
            new Order.Line("item", "pick", 2, true),
            new Order.Line("item", "stone", 64, false),
            new Order.Line("shulker", "s:1", 1, true),
            new Order.Line("item", "sword", 1, true),
            new Order.Line("item", "bow", 1, true));
        Map<String, Facts> index = Map.of("pick", new Facts(true, 28, 1), "stone", new Facts(false, 0, 64), "sword", new Facts(false, 3, 1));
        RepairEstimate.Estimate e = RepairEstimate.estimate(lines, false, index::get, 3);
        assertEquals(3, e.lines().size());
        assertEquals(4, e.needed());
        assertEquals(3, e.available());
        assertTrue(e.isShort());
        assertEquals(new RepairEstimate.LineEstimate("pick", 2, true, null, 4), e.lines().get(0));
        assertEquals("no Mending", e.lines().get(1).reason());
        assertEquals(0, e.lines().get(1).bottles());
        assertEquals("no Mending", e.lines().get(2).reason(), "an unknown key is not repairable");
    }

    @Test
    void repairAllWantsEveryItemLineButNeverShulkerLines() {
        List<Order.Line> lines = List.of(new Order.Line("item", "a", 1), new Order.Line("shulker", "s:1", 1));
        RepairEstimate.Estimate e = RepairEstimate.estimate(lines, true, k -> new Facts(true, 14, 1), 10);
        assertEquals(1, e.lines().size());
        assertEquals(1, e.needed());
        assertFalse(e.isShort());
        assertNull(RepairEstimate.estimate(lines, false, k -> null, 5), "null when nothing is wanted");
        assertNull(RepairEstimate.estimate(List.of(), true, k -> null, 5));
    }

    @Test
    void estimateJsonRoundTripsAndMatchesTheContractShape() {
        RepairEstimate.Estimate e = RepairEstimate.estimate(List.of(new Order.Line("item", "k", 1, true), new Order.Line("item", "z", 1, true)), false,
            k -> k.equals("k") ? new Facts(true, 150, 1) : new Facts(false, 1, 1), 40);
        JsonObject o = e.toJson();
        assertEquals(11, o.get("needed").getAsInt());
        assertEquals(40, o.get("available").getAsInt());
        assertFalse(o.get("short").getAsBoolean());
        assertTrue(o.getAsJsonArray("lines").get(0).getAsJsonObject().get("reason").isJsonNull());
        assertEquals(e, RepairEstimate.parseEstimate(JsonParser.parseString(o.toString())));
        assertNull(RepairEstimate.parseEstimate(JsonParser.parseString("5")));
        assertNull(RepairEstimate.parseEstimate(null));
    }

    @Test
    void resultJsonRoundTripsAndClips() {
        RepairEstimate.Result r = new RepairEstimate.Result(10, 2, List.of(new RepairEstimate.Partial("a", 120, 1561)),
            List.of(new RepairEstimate.Skipped("b", "x".repeat(100))));
        JsonObject o = r.toJson();
        assertEquals(60, o.getAsJsonArray("skipped").get(0).getAsJsonObject().get("reason").getAsString().length());
        RepairEstimate.Result back = RepairEstimate.parseResult(JsonParser.parseString(o.toString()));
        assertEquals(10, back.bottlesUsed());
        assertEquals(2, back.repaired());
        assertEquals(120, back.partial().get(0).damage());
        assertEquals(60, back.skipped().get(0).reason().length());
        assertEquals(2, back.notFullyRepaired());
        assertNull(RepairEstimate.parseResult(null));
        assertEquals(0, RepairEstimate.parseResult(JsonParser.parseString("{\"bottlesUsed\":\"x\",\"partial\":5}")).bottlesUsed());
    }

    @Test
    void resultIsCappedAt400Entries() {
        ArrayList<RepairEstimate.Skipped> many = new ArrayList<>();
        for (int i = 0; i < 500; i++) many.add(new RepairEstimate.Skipped("k" + i, "no bottles"));
        JsonObject o = new RepairEstimate.Result(0, 0, List.of(), many).toJson();
        assertEquals(400, o.getAsJsonArray("skipped").size());
        assertEquals(400, RepairEstimate.parseResult(o).skipped().size());
    }

    @Test
    void orderParsesRepairFields() {
        JsonObject o = JsonParser.parseString("{\"id\":\"o1\",\"repairAll\":true,\"lines\":[{\"type\":\"item\",\"key\":\"k\",\"count\":1,\"repair\":true},"
            + "{\"type\":\"item\",\"key\":\"j\",\"count\":2,\"repair\":\"yes\"}]}").getAsJsonObject();
        Order order = Order.parse(o);
        assertTrue(order.repairAll);
        assertTrue(order.lines.get(0).repair());
        assertFalse(order.lines.get(1).repair(), "a wrong type is false");
        assertTrue(order.wantsRepair());
        assertFalse(Order.parse(JsonParser.parseString("{\"id\":\"o\",\"lines\":[{\"type\":\"item\",\"key\":\"k\",\"count\":1}]}").getAsJsonObject()).wantsRepair());
    }
}
