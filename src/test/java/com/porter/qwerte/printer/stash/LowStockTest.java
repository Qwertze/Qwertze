package com.porter.qwerte.printer.stash;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LowStockTest {
    private static final Map<String, Long> NONE = Map.of();

    @Test
    void notTriggeredWhenEnoughIsCarried() {
        assertFalse(LowStock.triggered(Map.of("a", 500L), Map.of("a", 500L), NONE, 64));
        assertFalse(LowStock.triggered(Map.of("a", 500L), Map.of("a", 64L), NONE, 64));
    }

    @Test
    void triggeredBelowTheThresholdWhenTheNeedIsLarger() {
        assertTrue(LowStock.triggered(Map.of("a", 500L), Map.of("a", 63L), NONE, 64));
        assertTrue(LowStock.triggered(Map.of("a", 500L), NONE, NONE, 64));
    }

    @Test
    void aSmallNeedUsesTheNeedAsTheThreshold() {
        // 10 are needed and 10 carried: nothing to order, however high the threshold
        assertFalse(LowStock.triggered(Map.of("a", 10L), Map.of("a", 10L), NONE, 576));
        assertTrue(LowStock.triggered(Map.of("a", 10L), Map.of("a", 9L), NONE, 576));
    }

    @Test
    void whatIsOnOrderCoversTheRest() {
        assertFalse(LowStock.triggered(Map.of("a", 500L), Map.of("a", 10L), Map.of("a", 490L), 64));
        assertTrue(LowStock.triggered(Map.of("a", 500L), Map.of("a", 10L), Map.of("a", 489L), 64));
    }

    @Test
    void anItemNotNeededNeverTriggers() {
        assertFalse(LowStock.triggered(Map.of("a", 0L), NONE, NONE, 64));
        assertFalse(LowStock.triggered(NONE, Map.of("a", 1L), NONE, 64));
    }

    @Test
    void oneLowItemIsEnough() {
        Map<String, Long> need = new LinkedHashMap<>();
        need.put("a", 1000L);
        need.put("b", 1000L);
        assertTrue(LowStock.triggered(need, Map.of("a", 1000L, "b", 5L), NONE, 64));
    }

    @Test
    void shortfallIsNeedMinusCarriedMinusCovered() {
        Map<String, Long> need = Map.of("a", 500L);
        assertEquals(Map.of("a", 300L), LowStock.shortfall(need, Map.of("a", 100L), Map.of("a", 100L)));
        assertTrue(LowStock.shortfall(need, Map.of("a", 300L), Map.of("a", 200L)).isEmpty());
    }

    @Test
    void shortfallListsTriggeredFirstThenTheBiggestThenById() {
        Map<String, Long> need = new LinkedHashMap<>();
        need.put("big", 10_000L);   // carried 5000: short 5000 but not low (above the threshold)
        need.put("low", 200L);      // carried 10: low, short 190
        need.put("mid", 3_000L);    // carried 2000: short 1000, not low
        need.put("alsolow", 100L);  // carried 0: low, short 100
        need.put("done", 5L);       // fully carried
        Map<String, Long> carried = Map.of("big", 5000L, "low", 10L, "mid", 2000L, "done", 5L);
        List<String> order = new ArrayList<>(LowStock.shortfall(need, carried, Map.of(), 64).keySet());
        assertEquals(List.of("low", "alsolow", "big", "mid"), order);
    }

    @Test
    void equalShortfallsAreOrderedById() {
        Map<String, Long> need = new LinkedHashMap<>();
        need.put("z", 100L);
        need.put("b", 100L);
        assertEquals(List.of("b", "z"), new ArrayList<>(LowStock.shortfall(need, Map.of(), Map.of(), 64).keySet()));
    }
}
