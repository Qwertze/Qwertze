package com.porter.qwerte.manager.local;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Keying, the capture decision and eviction: the pure rules of local mode. */
class LocalRulesTest {
    // ---- keying ----

    @Test
    void aDoubleChestHasOneKeyWhicheverHalfIsGiven() {
        int[] a = { 10, 64, 20 };
        int[] b = { 11, 64, 20 };
        ContainerKey fromA = ContainerKey.of("srv", "minecraft:overworld", List.of(a, b));
        ContainerKey fromB = ContainerKey.of("srv", "minecraft:overworld", List.of(b, a));
        assertEquals(fromA, fromB);
        assertEquals(10, fromA.x());
    }

    @Test
    void theLowestPartIsByXThenYThenZ() {
        assertEquals(5, ContainerKey.canonical(List.of(new int[] { 6, 0, 0 }, new int[] { 5, 9, 9 }))[0]);
        assertEquals(1, ContainerKey.canonical(List.of(new int[] { 5, 2, 0 }, new int[] { 5, 1, 9 }))[1]);
        assertEquals(3, ContainerKey.canonical(List.of(new int[] { 5, 1, 4 }, new int[] { 5, 1, 3 }))[2]);
        assertNull(ContainerKey.canonical(List.of()));
        assertNull(ContainerKey.of("s", "d", List.of()));
    }

    @Test
    void sameBlockInAnotherDimensionOrServerIsAnotherKey() {
        List<int[]> p = List.of(new int[] { 1, 2, 3 });
        assertNotEquals(ContainerKey.of("s", "minecraft:overworld", p), ContainerKey.of("s", "minecraft:the_nether", p));
        assertNotEquals(ContainerKey.of("a", "d", p), ContainerKey.of("b", "d", p));
    }

    @Test
    void fileNamesAreSafeAndNeverEmpty() {
        assertEquals("2b2t.org", ContainerKey.safeFileName("2B2T.org"));
        assertEquals("host_25565", ContainerKey.safeFileName("host:25565"));
        assertEquals("_.._etc", ContainerKey.safeFileName("/../etc").replace("..", ".."), "no path separators survive");
        assertFalse(ContainerKey.safeFileName("../../x").contains("/"));
        assertFalse(ContainerKey.safeFileName("../../x").startsWith("."));
        assertEquals("unknown", ContainerKey.safeFileName(""));
        assertEquals("unknown", ContainerKey.safeFileName(null));
        assertEquals("unknown", ContainerKey.safeFileName("..."));
        assertEquals(80, ContainerKey.safeFileName("a".repeat(500)).length());
    }

    // ---- capture decision ----

    @Test
    void sizesPerBlock() {
        assertEquals(27, CaptureRules.expectedSize(CaptureRules.Kind.CHEST, false));
        assertEquals(54, CaptureRules.expectedSize(CaptureRules.Kind.CHEST, true));
        assertEquals(27, CaptureRules.expectedSize(CaptureRules.Kind.BARREL, false));
        assertEquals(27, CaptureRules.expectedSize(CaptureRules.Kind.SHULKER_BOX, false));
    }

    @Test
    void aFreshMenuRightAfterTheClickWithTheRightSizeIsCaptured() {
        assertTrue(CaptureRules.shouldCapture(true, false, 2, 27 + 36, 27));
        assertTrue(CaptureRules.shouldCapture(true, false, 10, 54 + 36, 54));
    }

    @Test
    void otherMenusAreNotCaptured() {
        assertFalse(CaptureRules.shouldCapture(false, false, 2, 63, 27), "not a new menu");
        assertFalse(CaptureRules.shouldCapture(true, true, 2, 63, 27), "it was already open at the click");
        assertFalse(CaptureRules.shouldCapture(true, false, 11, 63, 27), "opened too long after the click");
        assertFalse(CaptureRules.shouldCapture(true, false, -1, 63, 27));
        assertFalse(CaptureRules.shouldCapture(true, false, 2, 18 + 36, 27), "a plugin menu of another size");
        assertFalse(CaptureRules.shouldCapture(true, false, 2, 63, 54), "a single chest menu for a double chest");
        assertFalse(CaptureRules.shouldCapture(true, false, 2, 90 + 36, 90), "more than 54 slots");
    }

    @Test
    void anEmptyLookingContainerWaitsForItsItemsButIsRecordedLater() {
        assertFalse(CaptureRules.shouldRecord(null, 0, true, 1));
        assertFalse(CaptureRules.shouldRecord(null, 0, true, 2));
        assertTrue(CaptureRules.shouldRecord(null, 0, true, CaptureRules.EMPTY_AFTER_TICKS), "really empty");
        assertTrue(CaptureRules.shouldRecord(null, 123, false, 0), "items arrived");
    }

    @Test
    void afterTheFirstWriteOnlyChangesAreWritten() {
        assertFalse(CaptureRules.shouldRecord(123L, 123, false, 50));
        assertTrue(CaptureRules.shouldRecord(123L, 124, false, 50));
        assertTrue(CaptureRules.shouldRecord(0L, 5, false, 4), "emptied then filled");
        assertTrue(CaptureRules.shouldRecord(5L, 0, true, 9), "emptied");
    }

    // ---- eviction ----

    private record Item(String name, long age) {}

    @Test
    void theOldestGoFirstAndNothingGoesWithinTheCap() {
        List<Item> items = List.of(new Item("c", 30), new Item("a", 10), new Item("b", 20), new Item("d", 40));
        assertTrue(Evict.oldest(items, 4, Item::age).isEmpty());
        assertTrue(Evict.oldest(items, 9, Item::age).isEmpty());
        List<Item> drop = Evict.oldest(items, 2, Item::age);
        assertEquals(List.of("a", "b"), drop.stream().map(Item::name).toList());
        assertEquals(4, Evict.oldest(items, 0, Item::age).size());
    }

    @Test
    void tiesGoInListOrder() {
        List<Item> items = new ArrayList<>(List.of(new Item("x", 1), new Item("y", 1), new Item("z", 1)));
        assertEquals(List.of("x"), Evict.oldest(items, 2, Item::age).stream().map(Item::name).toList());
    }
}
