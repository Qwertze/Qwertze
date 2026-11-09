package com.porter.qwerte.tweaks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class FrozenGridTest {
    private static final int PX = 100, PY = 50;

    @Test
    void firstAndLastCells() {
        assertEquals(0, FrozenGrid.cellAt(PX, PY, PX + 8, PY + 7));
        assertEquals(26, FrozenGrid.cellAt(PX, PY, PX + 8 + 8 * 18 + 15, PY + 7 + 2 * 18 + 15));
    }

    @Test
    void cellIndexRunsRowByRow() {
        assertEquals(1, FrozenGrid.cellAt(PX, PY, PX + 8 + 18, PY + 7));
        assertEquals(9, FrozenGrid.cellAt(PX, PY, PX + 8, PY + 7 + 18));
        assertEquals(10, FrozenGrid.cellAt(PX, PY, PX + 8 + 18 + 5, PY + 7 + 18 + 5));
    }

    @Test
    void edgesOfACellAre16PixelsWide() {
        assertEquals(0, FrozenGrid.cellAt(PX, PY, PX + 8 + 15, PY + 7 + 15));
        assertEquals(-1, FrozenGrid.cellAt(PX, PY, PX + 8 + 16, PY + 7));
        assertEquals(-1, FrozenGrid.cellAt(PX, PY, PX + 8, PY + 7 + 16));
        assertEquals(-1, FrozenGrid.cellAt(PX, PY, PX + 8 + 17, PY + 7 + 17));
        assertEquals(1, FrozenGrid.cellAt(PX, PY, PX + 8 + 18, PY + 7));
    }

    @Test
    void gapsBetweenCellsAreNothing() {
        for (int col = 0; col < 8; col++) {
            assertEquals(-1, FrozenGrid.cellAt(PX, PY, PX + 8 + col * 18 + 16, PY + 10));
            assertEquals(-1, FrozenGrid.cellAt(PX, PY, PX + 8 + col * 18 + 17, PY + 10));
        }
        assertEquals(-1, FrozenGrid.cellAt(PX, PY, PX + 10, PY + 7 + 16));
        assertEquals(-1, FrozenGrid.cellAt(PX, PY, PX + 10, PY + 7 + 17));
    }

    @Test
    void borderAndOutsideAreNothing() {
        assertEquals(-1, FrozenGrid.cellAt(PX, PY, PX + 7, PY + 7));
        assertEquals(-1, FrozenGrid.cellAt(PX, PY, PX + 8, PY + 6));
        assertEquals(-1, FrozenGrid.cellAt(PX, PY, PX - 5, PY - 5));
        assertEquals(-1, FrozenGrid.cellAt(PX, PY, PX + 8 + 9 * 18, PY + 7));
        assertEquals(-1, FrozenGrid.cellAt(PX, PY, PX + 8, PY + 7 + 3 * 18));
        assertEquals(-1, FrozenGrid.cellAt(PX, PY, PX + 500, PY + 500));
    }

    @Test
    void cellCornersMatchTheHitTest() {
        for (int cell = 0; cell < 27; cell++) {
            assertEquals(cell, FrozenGrid.cellAt(PX, PY, FrozenGrid.cellX(PX, cell), FrozenGrid.cellY(PY, cell)));
            assertEquals(cell, FrozenGrid.cellAt(PX, PY, FrozenGrid.cellX(PX, cell) + 15, FrozenGrid.cellY(PY, cell) + 15));
        }
    }

    @Test
    void insideCoversThePanelOnly() {
        assertTrue(FrozenGrid.inside(PX, PY, PX, PY));
        assertTrue(FrozenGrid.inside(PX, PY, PX + 175, PY + 66));
        assertFalse(FrozenGrid.inside(PX, PY, PX + 176, PY));
        assertFalse(FrozenGrid.inside(PX, PY, PX, PY + 67));
        assertFalse(FrozenGrid.inside(PX, PY, PX - 1, PY));
    }

    @Test
    void panelFollowsTheMouseAndStaysOnScreen() {
        assertEquals(112, FrozenGrid.placeX(100, 1000));
        assertEquals(88, FrozenGrid.placeY(100, 1000));
        assertEquals(1000 - 176, FrozenGrid.placeX(990, 1000));
        assertEquals(0, FrozenGrid.placeY(5, 1000));
        assertEquals(500 - 67, FrozenGrid.placeY(600, 500));
        assertEquals(0, FrozenGrid.placeX(0, 100));
    }
}
