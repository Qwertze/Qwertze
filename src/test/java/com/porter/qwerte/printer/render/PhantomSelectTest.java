package com.porter.qwerte.printer.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.porter.qwerte.printer.render.PhantomSelect.Cover;
import org.junit.jupiter.api.Test;

class PhantomSelectTest {
    @Test
    void scopeIsTheActiveLayerOrEverything() {
        assertTrue(PhantomSelect.inScope(null, 70));
        assertTrue(PhantomSelect.inScope(64, 64));
        assertFalse(PhantomSelect.inScope(64, 65));
        assertFalse(PhantomSelect.inScope(-5, -4));
    }

    @Test
    void rangeIsASphereAroundThePlayersBlock() {
        long r2 = 32L * 32;
        assertTrue(PhantomSelect.included(32, 0, 0, 0, 0, 0, r2, null));
        assertFalse(PhantomSelect.included(33, 0, 0, 0, 0, 0, r2, null));
        // the corner of the box is out although every axis is inside
        assertFalse(PhantomSelect.included(20, 20, 20, 0, 0, 0, r2, null));
        assertTrue(PhantomSelect.included(18, 18, 18, 0, 0, 0, r2, null));
    }

    @Test
    void layerScopeCutsByYBeforeRange() {
        long r2 = 32L * 32;
        assertTrue(PhantomSelect.included(3, 10, 3, 0, 10, 0, r2, 10));
        assertFalse(PhantomSelect.included(3, 11, 3, 0, 10, 0, r2, 10));
        assertFalse(PhantomSelect.included(40, 10, 0, 0, 10, 0, r2, 10));
    }

    @Test
    void distanceDoesNotOverflowFarFromTheOrigin() {
        assertFalse(PhantomSelect.included(29_000_000, 0, 0, -29_000_000, 0, 0, 32L * 32, null));
    }

    @Test
    void sectionCoverIsNoneAllOrPartial() {
        // the player in the middle of section 0 (cells 0..15)
        assertEquals(Cover.ALL, PhantomSelect.cover(0, 0, 0, 8, 8, 8, 32L * 32, null));
        assertEquals(Cover.NONE, PhantomSelect.cover(10, 0, 0, 8, 8, 8, 32L * 32, null));
        assertEquals(Cover.PARTIAL, PhantomSelect.cover(2, 0, 0, 8, 8, 8, 32L * 32, null));
        // a section the limit just touches is partial, one it just misses is none
        assertEquals(Cover.PARTIAL, PhantomSelect.cover(1, 0, 0, 0, 0, 0, 16L * 16, null));
        assertEquals(Cover.NONE, PhantomSelect.cover(1, 0, 0, 0, 0, 0, 15L * 15, null));
    }

    @Test
    void sectionCoverWithALayerOnlyLooksAtThatRow() {
        // layer 20 is in section y=1 (cells 16..31); a section of another y is out of scope
        assertEquals(Cover.NONE, PhantomSelect.cover(0, 0, 0, 8, 20, 8, 1000L * 1000, 20));
        assertEquals(Cover.ALL, PhantomSelect.cover(0, 1, 0, 8, 20, 8, 20L * 20, 20));
        // the top of a section is out of a small limit without a layer, the row level with the player is not
        assertEquals(Cover.PARTIAL, PhantomSelect.cover(0, 1, 0, 8, 16, 8, 14L * 14, null));
        assertEquals(Cover.ALL, PhantomSelect.cover(0, 1, 0, 8, 16, 8, 14L * 14, 16));
    }

    @Test
    void nearest2OfAnOutOfScopeSectionIsMax() {
        assertEquals(Long.MAX_VALUE, PhantomSelect.nearest2(0, 0, 0, 0, 0, 0, 40));
        assertEquals(0, PhantomSelect.nearest2(0, 0, 0, 5, 5, 5, null));
        assertEquals(9, PhantomSelect.nearest2(1, 0, 0, 13, 5, 5, null));
    }

    @Test
    void capKeepsTheRangeWhenFewEnoughCellsAreInside() {
        int[] hist = new int[101];
        hist[3] = 10;
        hist[50] = 20;
        assertEquals(100, PhantomSelect.pickLimit(hist, 100, 30));
        assertEquals(100, PhantomSelect.pickLimit(hist, 100, 4096));
    }

    @Test
    void capTakesTheNearestCellsFirst() {
        int[] hist = new int[101];
        hist[1] = 6;
        hist[4] = 6;
        hist[9] = 6;
        hist[16] = 6;
        // 24 cells, cap 12: the limit is the distance where the running total reaches 12
        assertEquals(4, PhantomSelect.pickLimit(hist, 100, 12));
        // cap 13 needs the next shell too (a shell is taken whole)
        assertEquals(9, PhantomSelect.pickLimit(hist, 100, 13));
        assertEquals(1, PhantomSelect.pickLimit(hist, 100, 1));
    }

    @Test
    void capCountsOnlyWhatIsInsideTheRange() {
        int[] hist = new int[26];
        hist[25] = 100;
        // range 4 (16): the shell at 25 is outside, nothing to cap
        assertEquals(16, PhantomSelect.pickLimit(hist, 16, 10));
    }
}
