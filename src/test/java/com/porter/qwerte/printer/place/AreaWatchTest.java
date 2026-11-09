package com.porter.qwerte.printer.place;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class AreaWatchTest {
    private static final int[] BOX = { 0, 0, 0, 9, 9, 9 }; // cells 0..9, so the box spans 0 to 10

    @Test
    void distanceIsZeroInsideAndMeasuredToTheNearestFaceOutside() {
        assertEquals(0, AreaWatch.distance(BOX, 5, 5, 5), 1e-9);
        assertEquals(0, AreaWatch.distance(BOX, 10, 10, 10), 1e-9);
        assertEquals(5, AreaWatch.distance(BOX, 15, 5, 5), 1e-9);
        assertEquals(5, AreaWatch.distance(BOX, 5, -5, 5), 1e-9);
        assertEquals(5, AreaWatch.distance(BOX, 13, 5, 14), 1e-9); // 3 and 4 off two axes
    }

    @Test
    void outsideMeansFartherThanTheReachFromEveryBox() {
        List<int[]> boxes = List.of(BOX, new int[] { 100, 0, 0, 109, 9, 9 });
        assertFalse(AreaWatch.outside(boxes, 12, 5, 5, 4.5));
        assertTrue(AreaWatch.outside(boxes, 16, 5, 5, 4.5));
        assertFalse(AreaWatch.outside(boxes, 98, 5, 5, 4.5)); // near the second box
        assertFalse(AreaWatch.outside(List.of(), 1000, 0, 0, 4.5));
    }

    @Test
    void theWarningComesOncePerExit() {
        AreaWatch w = new AreaWatch();
        assertFalse(w.update(false));
        assertTrue(w.update(true));
        assertFalse(w.update(true));
        assertFalse(w.update(true));
        assertFalse(w.update(false));
        assertTrue(w.update(true));
        w.reset();
        assertFalse(w.isOutside());
        assertTrue(w.update(true));
    }
}
