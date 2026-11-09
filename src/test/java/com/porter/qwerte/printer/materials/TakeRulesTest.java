package com.porter.qwerte.printer.materials;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.porter.qwerte.printer.materials.TakeRules.Decision;
import com.porter.qwerte.printer.materials.TakeRules.Slot;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TakeRulesTest {
    private static final Map<String, Long> WANT = Map.of("minecraft:stone", 100L);

    @Test
    void takesWantedPlainStockInOrder() {
        List<Slot> slots = List.of(new Slot(0, "minecraft:dirt", true, false, true), new Slot(1, "minecraft:stone", true, false, true),
            new Slot(2, "minecraft:stone", true, false, true));
        Map<Integer, Integer> tries = new HashMap<>();
        assertEquals(1, TakeRules.next(slots, WANT, tries).slot());
        assertEquals(1, TakeRules.next(slots, WANT, tries).slot());
        assertEquals(1, TakeRules.next(slots, WANT, tries).slot());
        // the first slot did not move after three clicks: on to the next
        assertEquals(2, TakeRules.next(slots, WANT, tries).slot());
    }

    @Test
    void customStackAndUnwantedItemsAreLeft() {
        List<Slot> slots = List.of(new Slot(0, "minecraft:stone", false, false, true), new Slot(1, "minecraft:dirt", true, false, true));
        Decision d = TakeRules.next(slots, WANT, new HashMap<>());
        assertTrue(d.done());
        assertEquals(-1, d.slot());
    }

    @Test
    void aBoxHoldingSomethingWantedIsTaken() {
        List<Slot> slots = List.of(new Slot(4, "minecraft:shulker_box", true, true, true));
        assertEquals(4, TakeRules.next(slots, Map.of(), new HashMap<>()).slot());
    }

    @Test
    void noRoomIsReportedNotClicked() {
        List<Slot> slots = List.of(new Slot(0, "minecraft:stone", true, false, false));
        Decision d = TakeRules.next(slots, WANT, new HashMap<>());
        assertTrue(d.done());
        assertTrue(d.blocked());
    }

    @Test
    void theClickCountsMustBeClearedBetweenBoxes() {
        // The same slot ids come back with every shulker box: a map that is kept starves the fourth box
        Map<Integer, Integer> tries = new HashMap<>();
        List<Slot> box = List.of(new Slot(0, "minecraft:stone", true, false, true));
        for (int boxNo = 1; boxNo <= 3; boxNo++) {
            for (int i = 0; i < TakeRules.MAX_TRIES; i++) assertEquals(0, TakeRules.next(box, WANT, tries).slot());
            tries.clear(); // what startUnpack and the container visit do
        }
        for (int i = 0; i < TakeRules.MAX_TRIES; i++) assertEquals(0, TakeRules.next(box, WANT, tries).slot());
        assertTrue(TakeRules.next(box, WANT, tries).done(), "the same box is given up on after three clicks");
        // without the clear, a fourth box gets nothing
        assertTrue(TakeRules.next(box, WANT, tries).done());
        tries.clear();
        assertFalse(TakeRules.next(box, WANT, tries).done());
    }

    @Test
    void roomCountsTheSpillAndKeepsTheReserve() {
        // 64 stone, 40 fit onto a partial stack, 24 spill into one slot
        assertTrue(TakeRules.room(64, 64, 40, 3, 2));
        assertFalse(TakeRules.room(64, 64, 40, 2, 2));
        // fits entirely in the partial stacks: no slot used
        assertTrue(TakeRules.room(30, 64, 40, 2, 2));
        // two slots spill
        assertTrue(TakeRules.room(128, 64, 0, 4, 2));
        assertFalse(TakeRules.room(128, 64, 0, 3, 2));
        // the old rule (an empty slot beyond the reserve) would have said yes to this one
        assertFalse(TakeRules.room(200, 64, 0, 3, 2));
    }

    @Test
    void theBoxBudgetLimitsBoxesButNotPlainStock() {
        List<Slot> slots = List.of(new Slot(0, "minecraft:shulker_box", true, true, true), new Slot(1, "minecraft:stone", true, false, true));
        // no budget: the box is skipped, the plain stone is taken
        assertEquals(1, TakeRules.next(slots, WANT, new HashMap<>(), 0).slot());
        // a budget: the first slot (the box) comes first, as before
        assertEquals(0, TakeRules.next(slots, WANT, new HashMap<>(), 1).slot());
        // only a box and no budget: done, and not blocked
        Decision d = TakeRules.next(List.of(slots.get(0)), WANT, new HashMap<>(), 0);
        assertTrue(d.done());
        assertFalse(d.blocked());
    }

    @Test
    void theOldOverloadBehavesAsBefore() {
        List<Slot> slots = List.of(new Slot(4, "minecraft:shulker_box", true, true, true), new Slot(5, "minecraft:stone", true, false, false));
        Map<Integer, Integer> a = new HashMap<>(), b = new HashMap<>();
        for (int i = 0; i < 4; i++) {
            Decision x = TakeRules.next(slots, WANT, a);
            Decision y = TakeRules.next(slots, WANT, b, Integer.MAX_VALUE);
            assertEquals(x, y);
        }
        // the stone that has no room is reported as blocked once the box has been given up on
        assertTrue(TakeRules.next(slots, WANT, a).blocked());
    }
}
