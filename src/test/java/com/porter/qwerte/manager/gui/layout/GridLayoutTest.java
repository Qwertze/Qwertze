package com.porter.qwerte.manager.gui.layout;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GridLayoutTest {
    @Test
    void columnsAndRowsFollowTheSpace() {
        assertEquals(1, GridLayout.cols(0, 2));
        assertEquals(1, GridLayout.cols(17, 2));
        assertEquals(1, GridLayout.cols(18, 0));
        assertEquals(1, GridLayout.cols(21, 2));
        assertEquals(1, GridLayout.cols(22, 2));
        assertEquals(9, GridLayout.cols(166, 2));
        assertEquals(8, GridLayout.cols(165, 2));
        assertEquals(17, GridLayout.cols(323, 0));
        assertEquals(18, GridLayout.cols(324, 0));
        assertEquals(1000, GridLayout.cols(18_000, 0));
        assertEquals(1, GridLayout.rows(5, 2));
        assertEquals(6, GridLayout.rows(112, 2));
    }

    @Test
    void theSlotsAreCentred() {
        assertEquals(2, GridLayout.offsetX(166, 2, 9));
        assertEquals(2 + 5, GridLayout.offsetX(176, 2, 9));
        assertEquals(2, GridLayout.offsetX(10, 2, 1), "never left of the padding");
    }

    @Test
    void hitTestWithScrolling() {
        int count = 40;
        int cols = 9;
        int rows = 3;
        // the slot at row 0, col 0 of the view with scroll 2 is cell 18
        assertEquals(18, GridLayout.hit(count, cols, rows, 2, 2, 2, 3, 3));
        assertEquals(18 + 9 + 1, GridLayout.hit(count, cols, rows, 2, 2, 2, 2 + 18 + 1, 2 + 18 + 1));
        assertEquals(-1, GridLayout.hit(count, cols, rows, 2, 2, 2, 1, 5), "left of the first slot");
        assertEquals(-1, GridLayout.hit(count, cols, rows, 2, 2, 2, 5, 1), "above the first slot");
        assertEquals(-1, GridLayout.hit(count, cols, rows, 2, 2, 2, 2 + 9 * 18, 5), "right of the last column");
        assertEquals(-1, GridLayout.hit(count, cols, rows, 2, 2, 2, 5, 2 + 3 * 18), "under the last row");
        // past the end: 40 cells = rows 0..4, the last row has 4 cells
        assertEquals(39, GridLayout.hit(count, cols, rows, 2, 0, 0, 3 * 18 + 1, 2 * 18 + 1));
        assertEquals(-1, GridLayout.hit(count, cols, rows, 2, 0, 0, 4 * 18 + 1, 2 * 18 + 1));
        assertEquals(-1, GridLayout.hit(0, cols, rows, 0, 0, 0, 1, 1));
    }

    @Test
    void scrollLimits() {
        assertEquals(5, GridLayout.totalRows(41, 9));
        assertEquals(0, GridLayout.totalRows(0, 9));
        assertEquals(2, GridLayout.maxScroll(41, 9, 3));
        assertEquals(0, GridLayout.maxScroll(10, 9, 3));
        assertEquals(2, GridLayout.clampScroll(7, 41, 9, 3));
        assertEquals(0, GridLayout.clampScroll(-4, 41, 9, 3));
        // fewer cells than before: the scroll comes back
        assertEquals(0, GridLayout.clampScroll(2, 20, 9, 3));
    }

    @Test
    void reflowKeepsTheFirstVisibleItemVisible() {
        int count = 400;
        int first9 = 5 * 9; // scrolled 5 rows at 9 columns: item 45 is at the top
        int s4 = GridLayout.scrollAfterReflow(9, 5, 4, count, 6);
        assertTrue(s4 * 4 <= first9 && first9 < (s4 + 6) * 4, "item " + first9 + " visible at 4 columns, scroll " + s4);
        int s14 = GridLayout.scrollAfterReflow(4, s4, 14, count, 6);
        assertTrue(s14 * 14 <= first9 && first9 < (s14 + 6) * 14, "item " + first9 + " visible at 14 columns, scroll " + s14);
        assertEquals(0, GridLayout.scrollAfterReflow(9, 0, 4, count, 6));
        // never beyond the end
        assertEquals(GridLayout.maxScroll(10, 2, 3), GridLayout.scrollAfterReflow(9, 50, 2, 10, 3));
    }

    // ---------- the scroll bar ----------

    @Test
    void theThumbIsTheVisibleShareOfTheRowsAndNeverTinyOrBigger() {
        assertEquals(50, GridLayout.thumbH(200, 5, 20, 6), 1e-9);
        assertEquals(12, GridLayout.thumbH(200, 1, 1000, 12), 1e-9);
        assertEquals(200, GridLayout.thumbH(200, 20, 5, 12), 1e-9);
        assertEquals(8, GridLayout.thumbH(8, 1, 1000, 12), 1e-9);
    }

    @Test
    void thePointerPutsTheThumbCentreUnderIt() {
        double trackY = 100, trackH = 200, thumb = 50;
        int max = 15;
        assertEquals(0, GridLayout.scrollFromPointer(100, trackY, trackH, thumb, max));
        assertEquals(0, GridLayout.scrollFromPointer(10, trackY, trackH, thumb, max));
        assertEquals(max, GridLayout.scrollFromPointer(1000, trackY, trackH, thumb, max));
        assertEquals(max, GridLayout.scrollFromPointer(trackY + trackH - thumb / 2, trackY, trackH, thumb, max));
        // the middle of the free travel is the middle scroll
        assertEquals(8, GridLayout.scrollFromPointer(trackY + thumb / 2 + (trackH - thumb) / 2, trackY, trackH, thumb, max));
        // nothing to scroll
        assertEquals(0, GridLayout.scrollFromPointer(150, trackY, trackH, trackH, max));
        assertEquals(0, GridLayout.scrollFromPointer(150, trackY, trackH, thumb, 0));
    }

    @Test
    void theThumbAndThePointerAgree() {
        double trackY = 40, trackH = 180, thumb = 30;
        int max = 12;
        for (int scroll = 0; scroll <= max; scroll++) {
            double top = GridLayout.thumbY(trackY, trackH, thumb, scroll, max);
            assertTrue(top >= trackY && top + thumb <= trackY + trackH + 1e-9);
            // dragging by the thumb's centre gives the scroll that put it there
            assertEquals(scroll, GridLayout.scrollFromPointer(top + thumb / 2, trackY, trackH, thumb, max));
        }
        assertEquals(trackY, GridLayout.thumbY(trackY, trackH, thumb, 0, 0), 1e-9);
    }

    @Test
    void theBarCanBeGrabbedAcrossItsWholeTrack() {
        // grab zone from x 200 to the grid's edge 210, track 40..140
        assertTrue(GridLayout.inBar(205, 90, 200, 210, 40, 100));
        assertTrue(GridLayout.inBar(200, 40, 200, 210, 40, 100));
        assertTrue(GridLayout.inBar(210, 140, 200, 210, 40, 100));
        assertFalse(GridLayout.inBar(199, 90, 200, 210, 40, 100));
        assertFalse(GridLayout.inBar(205, 141, 200, 210, 40, 100));
        assertFalse(GridLayout.inBar(211, 90, 200, 210, 40, 100));
    }
}
