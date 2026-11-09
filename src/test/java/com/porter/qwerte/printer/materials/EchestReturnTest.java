package com.porter.qwerte.printer.materials;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.porter.qwerte.printer.materials.EchestReturn.ShulkerSlot;
import java.util.List;
import org.junit.jupiter.api.Test;

class EchestReturnTest {
    private static ShulkerSlot box(int slot, String item, String print) {
        return new ShulkerSlot(slot, item, print);
    }

    @Test
    void theNewBoxesGoBack() {
        List<ShulkerSlot> before = List.of(box(3, "minecraft:shulker_box", "own"));
        List<ShulkerSlot> now = List.of(box(3, "minecraft:shulker_box", "own"), box(10, "minecraft:red_shulker_box", "x"), box(11, "minecraft:red_shulker_box", "y"));
        List<ShulkerSlot> back = EchestReturn.slotsToReturn(now, before, 2);
        assertEquals(List.of(10, 11), back.stream().map(ShulkerSlot::invIndex).toList());
    }

    @Test
    void aBoxOfTheirOwnThatLooksTheSameStays() {
        // the player had one plain box in slot 20; one identical box came out of the chest and went to slot 5
        List<ShulkerSlot> before = List.of(box(20, "minecraft:shulker_box", "same"));
        List<ShulkerSlot> now = List.of(box(5, "minecraft:shulker_box", "same"), box(20, "minecraft:shulker_box", "same"));
        List<ShulkerSlot> back = EchestReturn.slotsToReturn(now, before, 1);
        // exactly one goes back (the count is right; the two are the same box to the game)
        assertEquals(1, back.size());
    }

    @Test
    void nothingMoreThanWasTakenGoesBack() {
        List<ShulkerSlot> now = List.of(box(1, "a", "1"), box(2, "a", "2"), box(3, "a", "3"));
        assertEquals(2, EchestReturn.slotsToReturn(now, List.of(), 2).size());
        assertTrue(EchestReturn.slotsToReturn(now, List.of(), 0).isEmpty());
    }

    @Test
    void nothingNewMeansNothingToReturn() {
        List<ShulkerSlot> same = List.of(box(1, "a", "1"), box(2, "b", "2"));
        assertTrue(EchestReturn.slotsToReturn(same, same, 5).isEmpty());
    }

    @Test
    void anEmptiedBoxHasAnotherFingerprintAndIsStillReturned() {
        List<ShulkerSlot> before = List.of(box(0, "minecraft:shulker_box", "full-own"));
        // the taken box was emptied by unpacking, so its fingerprint is a new one
        List<ShulkerSlot> now = List.of(box(0, "minecraft:shulker_box", "full-own"), box(7, "minecraft:shulker_box", "emptied"));
        List<ShulkerSlot> back = EchestReturn.slotsToReturn(now, before, 1);
        assertEquals(1, back.size());
        assertEquals(7, back.get(0).invIndex());
    }

    @Test
    void theLowestSlotsAreReturnedFirst() {
        List<ShulkerSlot> now = List.of(box(30, "a", "x"), box(4, "a", "y"), box(12, "a", "z"));
        List<ShulkerSlot> back = EchestReturn.slotsToReturn(now, List.of(), 3);
        assertEquals(List.of(4, 12, 30), back.stream().map(ShulkerSlot::invIndex).toList());
    }
}
