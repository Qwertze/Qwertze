package com.porter.qwerte.printer.stash;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.porter.qwerte.printer.stash.OrderBatch.Limits;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.ToIntFunction;
import org.junit.jupiter.api.Test;

class OrderBatchTest {
    private static final ToIntFunction<String> STACK64 = id -> 64;

    private static Map<String, Long> want(Object... kv) {
        Map<String, Long> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], ((Number) kv[i + 1]).longValue());
        return m;
    }

    @Test
    void shulkersAreCountedInSlotsOf27() {
        assertEquals(1, OrderBatch.shulkers(want("a", 27 * 64), STACK64));
        assertEquals(2, OrderBatch.shulkers(want("a", 27 * 64 + 1), STACK64));
        assertEquals(0, OrderBatch.shulkers(want(), STACK64));
    }

    @Test
    void everyLineRoundsUpToWholeSlots() {
        // two items of 1 each take two slots, not one
        assertEquals(1, OrderBatch.shulkers(want("a", 1, "b", 1), STACK64));
        // 14 lines of 1.5 stacks = 28 slots = 2 shulkers
        Map<String, Long> m = new LinkedHashMap<>();
        for (int i = 0; i < 14; i++) m.put("i" + i, 96L);
        assertEquals(2, OrderBatch.shulkers(m, STACK64));
    }

    @Test
    void stackSizesOfSixteenAndOneAreUsed() {
        ToIntFunction<String> ms = id -> id.equals("pearl") ? 16 : id.equals("tool") ? 1 : 64;
        // 27 pearls stacks of 16 fill one shulker, one more spills to a second
        assertEquals(1, OrderBatch.shulkers(want("pearl", 27 * 16), ms));
        assertEquals(2, OrderBatch.shulkers(want("pearl", 27 * 16 + 1), ms));
        // unstackable: one slot each
        assertEquals(1, OrderBatch.shulkers(want("tool", 27), ms));
        assertEquals(2, OrderBatch.shulkers(want("tool", 28), ms));
    }

    @Test
    void anUnknownStackSizeCountsAs64() {
        assertEquals(1, OrderBatch.shulkers(want("a", 64 * 27), id -> 0));
    }

    @Test
    void capKeepsWholeStacksWithinTheShulkerLimit() {
        Map<String, Integer> out = OrderBatch.cap(want("a", 100_000), STACK64, new Limits(2, 100));
        assertEquals(Map.of("a", 2 * 27 * 64), out);
        assertEquals(2, OrderBatch.shulkers(out, STACK64));
    }

    @Test
    void capNeverGoesOver54EvenIfAskedFor() {
        Map<String, Integer> out = OrderBatch.cap(want("a", 1_000_000), STACK64, new Limits(500, 500));
        assertEquals(54 * 27 * 64, out.get("a"));
        assertEquals(54, OrderBatch.shulkers(out, STACK64));
    }

    @Test
    void capUsesWhatIsLeftOfTheRunLimit() {
        Map<String, Integer> out = OrderBatch.cap(want("a", 1_000_000), STACK64, new Limits(9, 3));
        assertEquals(3 * 27 * 64, out.get("a"));
        assertTrue(OrderBatch.cap(want("a", 10), STACK64, new Limits(9, 0)).isEmpty());
    }

    @Test
    void aPartialLastStackIsAddedWhenASlotIsLeft() {
        Map<String, Integer> out = OrderBatch.cap(want("a", 64 + 10), STACK64, new Limits(1, 5));
        assertEquals(Map.of("a", 74), out);
    }

    @Test
    void noPartialStackWhenTheLastSlotIsTaken() {
        // 27 whole stacks fill the one shulker; the 10 left over do not fit
        Map<String, Integer> out = OrderBatch.cap(want("a", 27 * 64 + 10), STACK64, new Limits(1, 5));
        assertEquals(Map.of("a", 27 * 64), out);
    }

    @Test
    void earlierItemsComeFirstAndLaterOnesAreLeftOutWhenFull() {
        Map<String, Integer> out = OrderBatch.cap(want("a", 27 * 64, "b", 64), STACK64, new Limits(1, 5));
        assertEquals(Map.of("a", 27 * 64), out);
    }

    @Test
    void aSmallItemStillFitsAfterABigOne() {
        Map<String, Integer> out = OrderBatch.cap(want("a", 26 * 64, "b", 5), STACK64, new Limits(1, 5));
        assertEquals(Map.of("a", 26 * 64, "b", 5), out);
    }

    @Test
    void sixteenStackItemsFitFewerPerShulker() {
        ToIntFunction<String> ms = id -> 16;
        Map<String, Integer> out = OrderBatch.cap(want("pearl", 10_000), ms, new Limits(1, 5));
        assertEquals(27 * 16, out.get("pearl"));
    }

    @Test
    void atMost200LinesAreKept() {
        Map<String, Long> m = new LinkedHashMap<>();
        for (int i = 0; i < 300; i++) m.put("item" + i, 1L);
        Map<String, Integer> out = OrderBatch.cap(m, STACK64, new Limits(54, 540, 200));
        assertEquals(200, out.size());
        // and a lower line limit is respected, but 200 is the ceiling whatever is asked
        assertEquals(10, OrderBatch.cap(m, STACK64, new Limits(54, 540, 10)).size());
        assertEquals(200, OrderBatch.cap(m, STACK64, new Limits(54, 540, 5000)).size());
    }

    @Test
    void countsAreClampedToTheIntRange() {
        ToIntFunction<String> big = id -> Integer.MAX_VALUE;
        Map<String, Integer> out = OrderBatch.cap(want("a", Long.MAX_VALUE), big, new Limits(54, 540));
        assertEquals(Integer.MAX_VALUE, out.get("a"));
    }

    @Test
    void nothingWantedGivesNothing() {
        assertTrue(OrderBatch.cap(want(), STACK64, new Limits(9, 27)).isEmpty());
        assertTrue(OrderBatch.cap(want("a", 0), STACK64, new Limits(9, 27)).isEmpty());
    }
}
