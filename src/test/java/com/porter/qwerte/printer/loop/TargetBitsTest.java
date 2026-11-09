package com.porter.qwerte.printer.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TargetBitsTest {
    @Test
    void countsOpenCellsPerY() {
        TargetBits t = new TargetBits();
        assertTrue(t.add(0, 5, 0));
        assertTrue(t.add(1, 5, 0));
        assertTrue(t.add(100, 7, -40));
        assertEquals(2, t.open(5));
        assertEquals(1, t.open(7));
        assertEquals(0, t.open(6));
        assertEquals(3, t.total());
        assertEquals(5, t.low());
        assertEquals(7, t.high());
    }

    @Test
    void aSecondAddOrAMissingRemoveDoesNotCount() {
        TargetBits t = new TargetBits();
        t.add(3, 10, 3);
        assertFalse(t.add(3, 10, 3));
        assertEquals(1, t.open(10));
        assertFalse(t.remove(4, 10, 3));
        assertEquals(1, t.open(10));
        assertTrue(t.remove(3, 10, 3));
        assertFalse(t.remove(3, 10, 3));
        assertEquals(0, t.open(10));
        assertEquals(0, t.total());
    }

    @Test
    void boundsFollowTheEndsWhenALayerEmpties() {
        TargetBits t = new TargetBits();
        t.add(0, -64, 0);
        t.add(0, 10, 0);
        t.add(0, 320, 0);
        assertEquals(-64, t.low());
        assertEquals(320, t.high());
        t.remove(0, -64, 0);
        t.remove(0, 320, 0);
        assertEquals(10, t.low());
        assertEquals(10, t.high());
        t.remove(0, 10, 0);
        assertTrue(t.low() > t.high());
    }

    @Test
    void negativeYAndChunkEdgesAreCountedRight() {
        TargetBits t = new TargetBits();
        t.add(15, -1, 15);
        t.add(16, -1, 16);
        t.add(-1, 0, -1);
        assertEquals(2, t.open(-1));
        assertEquals(1, t.open(0));
    }

    @Test
    void clearForgetsEverything() {
        TargetBits t = new TargetBits();
        t.add(0, 1, 0);
        t.add(0, 2, 0);
        t.clear();
        assertEquals(0, t.total());
        assertEquals(0, t.open(1));
        assertTrue(t.low() > t.high());
        assertTrue(t.add(0, 1, 0));
        assertEquals(1, t.open(1));
    }

    @Test
    void addAndRemoveStayConstantTimeForALargeBuild() {
        TargetBits t = new TargetBits();
        for (int x = 0; x < 200; x++) for (int z = 0; z < 200; z++) for (int y = 0; y < 10; y++) t.add(x, y, z);
        assertEquals(400000, t.total());
        assertEquals(40000, t.open(4));
        long start = System.nanoTime();
        for (int x = 0; x < 200; x++) for (int z = 0; z < 200; z++) t.remove(x, 4, z);
        // 40000 removals; far under a second even on a slow machine when each is O(1)
        assertTrue(System.nanoTime() - start < 1_000_000_000L);
        assertEquals(0, t.open(4));
        assertEquals(360000, t.total());
    }
}
