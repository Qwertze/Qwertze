package com.porter.qwerte.manager.model;

import com.porter.qwerte.manager.model.CartPlan.Kind;
import com.porter.qwerte.manager.model.CartPlan.Unit;
import com.porter.qwerte.stash.Order;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CartPlanTest {
    private static IndexModel index() {
        return CartTest.index();
    }

    private static Order.Line item(String key, int n) {
        return new Order.Line("item", key, n);
    }

    @Test
    void oneItemLineIsFullStacksThenTheRemainderInBinsOf27() {
        // 28 stacks and a remainder of 10 => 29 slots => 2 shulkers (27 + 2)
        List<Unit> plan = CartPlan.plan(List.of(item("minecraft:diamond", 64 * 28 + 10)), index());
        assertEquals(2, plan.size());
        assertEquals(Kind.BIN, plan.get(0).kind());
        assertEquals(27, plan.get(0).lots().size());
        assertEquals(2, plan.get(1).lots().size());
        assertEquals(10, plan.get(1).lots().get(1).count());
        assertEquals(2, CartPlan.shulkersNeeded(List.of(item("minecraft:diamond", 64 * 28 + 10)), index()));
    }

    @Test
    void mixedMaxStackSizes() {
        // pearls stack to 16: 100 -> 6 full + 4; swords stack to 1: 3 -> 3 slots; diamonds 64: 100 -> 1 + 36
        List<Order.Line> lines = List.of(item("minecraft:ender_pearl", 100), item("minecraft:diamond_sword", 3), item("minecraft:diamond", 100));
        assertEquals(7 + 3 + 2, slots(CartPlan.plan(lines, index())));
        assertEquals(1, CartPlan.shulkersNeeded(lines, index()));
    }

    @Test
    void sameResultAsOrderPack() {
        List<Order.Line> lines = List.of(item("minecraft:cobblestone", 64 * 40), item("minecraft:diamond", 64 * 10 + 5),
            item("minecraft:ender_pearl", 33), item("minecraft:diamond_sword", 12));
        List<Order.Lot> lots = new ArrayList<>();
        for (Order.Line l : lines) lots.addAll(Order.expand(l.key(), l.count(), index().item(l.key()).maxStack()));
        List<List<Order.Lot>> expected = Order.pack(lots);
        List<Unit> plan = CartPlan.plan(lines, index());
        assertEquals(expected.size(), plan.size());
        for (int i = 0; i < expected.size(); i++) assertEquals(expected.get(i), plan.get(i).lots());
    }

    @Test
    void keysAreGroupedInAscendingOrderAndTheBiggestGroupFirst() {
        // cobblestone has the most loads so it comes first; diamond (3 loads) and apple (1 load) follow
        List<Unit> plan = CartPlan.plan(List.of(item("minecraft:diamond", 64 * 3), item("minecraft:apple", 4), item("minecraft:cobblestone", 64 * 5)), index());
        assertEquals(1, plan.size());
        List<String> keys = new ArrayList<>();
        for (Order.Lot l : plan.get(0).lots()) keys.add(l.key());
        assertEquals(List.of("minecraft:cobblestone", "minecraft:cobblestone", "minecraft:cobblestone", "minecraft:cobblestone", "minecraft:cobblestone",
            "minecraft:diamond", "minecraft:diamond", "minecraft:diamond", "minecraft:apple"), keys);
    }

    @Test
    void equalGroupsKeepKeyOrder() {
        List<Unit> plan = CartPlan.plan(List.of(item("minecraft:diamond", 64), item("minecraft:apple", 4)), index());
        assertEquals("minecraft:apple", plan.get(0).lots().get(0).key());
        assertEquals("minecraft:diamond", plan.get(0).lots().get(1).key());
    }

    @Test
    void packedAndEmptyShulkersComeFirstInThatOrder() {
        List<Order.Line> lines = List.of(item("minecraft:diamond", 64), item("minecraft:red_shulker_box", 2), new Order.Line("shulker", "s:aaaaaaaaaa", 3));
        List<Unit> plan = CartPlan.plan(lines, index());
        assertEquals(List.of(Kind.SHULKER, Kind.SHULKER, Kind.SHULKER, Kind.EMPTY, Kind.EMPTY, Kind.BIN), plan.stream().map(Unit::kind).toList());
        assertEquals(6, CartPlan.shulkersNeeded(lines, index()));
    }

    @Test
    void anUnknownKeyCountsWithAStackSizeOf64() {
        assertEquals(1, CartPlan.shulkersNeeded(List.of(item("mod:thing#abc", 64 * 27)), index()));
        assertEquals(2, CartPlan.shulkersNeeded(List.of(item("mod:thing#abc", 64 * 27 + 1)), index()));
    }

    @Test
    void overFiftyFourShulkersIsNoPlan() {
        List<Order.Line> lines = List.of(item("minecraft:diamond", 64 * 27 * 54 + 1));
        assertEquals(55, CartPlan.shulkersNeeded(lines, index()));
        assertNull(CartPlan.plan(lines, index()));
        assertTrue(CartPlan.plan(List.of(item("minecraft:diamond", 64 * 27 * 54)), index()).size() == 54);
    }

    @Test
    void hugeCountsDoNotOverflow() {
        assertTrue(CartPlan.shulkersNeeded(List.of(item("minecraft:diamond", Integer.MAX_VALUE), item("minecraft:red_shulker_box", Integer.MAX_VALUE)), index()) > 54);
    }

    private static int slots(List<Unit> plan) {
        int n = 0;
        for (Unit u : plan) n += u.lots().size();
        return n;
    }
}
