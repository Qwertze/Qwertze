package com.porter.qwerte.manager.model;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.porter.qwerte.stash.Order;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RepairModelTest {
    private static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    @Test
    void indexItemReadsMendingFlagAndFallsBackToDetails() {
        IndexModel m = IndexModel.parse(json("{\"items\":["
            + "{\"key\":\"a\",\"id\":\"minecraft:diamond_pickaxe\",\"count\":1,\"maxStack\":1,\"damage\":140,\"maxDamage\":1561,\"mending\":true,\"details\":[]},"
            + "{\"key\":\"b\",\"id\":\"minecraft:diamond_pickaxe\",\"count\":1,\"maxStack\":1,\"damage\":15,\"maxDamage\":1561,\"details\":[\"Mending I\"]},"
            + "{\"key\":\"c\",\"id\":\"minecraft:diamond_pickaxe\",\"count\":1,\"maxStack\":1,\"damage\":0,\"mending\":true},"
            + "{\"key\":\"d\",\"id\":\"minecraft:stone\",\"count\":9,\"maxStack\":64,\"damage\":3,\"mending\":true},"
            + "{\"key\":\"e\",\"id\":\"minecraft:x\",\"count\":1,\"maxStack\":1,\"damage\":50,\"mending\":true,\"repairable\":false,\"repairBottles\":9}]}"));
        IndexModel.Item a = m.item("a"), b = m.item("b"), c = m.item("c"), d = m.item("d"), e = m.item("e");
        assertTrue(a.mending());
        assertTrue(a.repairable());
        assertEquals(10, a.repairBottles());
        assertTrue(b.mending(), "older bot: from the details");
        assertEquals(2, b.repairBottles());
        assertFalse(c.repairable());
        assertEquals(0, c.repairBottles());
        assertFalse(d.repairable(), "stackable");
        assertFalse(e.repairable(), "the server's own word counts");
        assertEquals(0, e.repairBottles());
    }

    @Test
    void orderEntryReadsRepairFields() {
        OrderModel.Listing l = OrderModel.parse(json("{\"orders\":[{\"id\":\"o1\",\"status\":\"delivered\",\"repairAll\":true,"
            + "\"lines\":[{\"type\":\"item\",\"key\":\"k\",\"count\":1,\"repair\":true}],"
            + "\"repairEstimate\":{\"needed\":12,\"available\":40,\"short\":false,\"lines\":[{\"key\":\"k\",\"count\":1,\"repairable\":true,\"reason\":null,\"bottles\":12}]},"
            + "\"result\":{\"shulkers\":1,\"repair\":{\"bottlesUsed\":10,\"repaired\":2,\"partial\":[{\"key\":\"k\",\"damage\":120,\"maxDamage\":1561}],\"skipped\":[{\"key\":\"j\",\"reason\":\"no Mending\"}]}}}],"
            + "\"available\":{\"items\":{},\"shulkers\":{},\"bottles\":77}}"));
        OrderModel.Entry e = l.orders().get(0);
        assertTrue(e.repairAll());
        assertTrue(e.lines().get(0).repair());
        assertEquals(12, e.repairEstimate().needed());
        assertEquals(10, e.repairResult().bottlesUsed());
        assertEquals(2, e.repairResult().repaired());
        assertEquals("no Mending", e.repairResult().skipped().get(0).reason());
        assertTrue(e.wantsRepair());
        assertEquals(77, l.bottles());
    }

    @Test
    void orderWithoutRepairHasNoneOfIt() {
        OrderModel.Listing l = OrderModel.parse(json("{\"orders\":[{\"id\":\"o1\",\"status\":\"pending\",\"lines\":[{\"type\":\"item\",\"key\":\"k\",\"count\":1}]}],\"available\":{}}"));
        OrderModel.Entry e = l.orders().get(0);
        assertFalse(e.repairAll());
        assertNull(e.repairEstimate());
        assertNull(e.repairResult());
        assertFalse(e.wantsRepair());
        assertEquals(-1, l.bottles(), "not said");
        assertEquals(-1, OrderModel.Listing.EMPTY.bottles());
    }

    @Test
    void botInfoReadsRepairAndLocalSaysYes() {
        assertFalse(BotInfo.parse(json("{}")).repair());
        assertTrue(BotInfo.parse(json("{\"repair\":true}")).repair());
        assertFalse(BotInfo.parse(json("{\"repair\":\"yes\"}")).repair());
        assertFalse(BotInfo.NONE.repair());
        assertEquals("This bot can't repair items; they'll arrive as they are.", BotInfo.NONE.repairWarning(true));
        assertEquals("", BotInfo.NONE.repairWarning(false));
        assertEquals("", BotInfo.NONE.withRepair(true).repairWarning(true));
        BotInfo local = new BotInfo(true, true, "", 5, false, false, false, "", false, false, 0, 0, false, "", "", false, "");
        assertTrue(local.repair(), "the local source: this client can repair");
    }

    @Test
    void cartKeepsRepairTogglesThroughCountChanges() {
        Cart c = new Cart();
        c.replaceAll(List.of(new Order.Line("item", "k", 1, false)));
        c.setRepair(0, true);
        assertTrue(c.lines().get(0).repair());
        c.add("item", "k", 1, IndexModel.empty());
        assertEquals(2, c.lines().get(0).count());
        assertTrue(c.lines().get(0).repair(), "adding more keeps the toggle");
        c.removeAmount("item", "k", 1);
        assertTrue(c.lines().get(0).repair());
        c.setRepairAll(true);
        assertTrue(c.repairAll());
        c.clear();
        assertFalse(c.repairAll());
        assertNotNull(c.lines());
    }
}
