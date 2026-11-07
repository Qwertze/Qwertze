package com.porter.qwerte.manager.gui.frame;

import com.porter.qwerte.manager.gui.frame.PopupLayout.Placement;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PopupAndMaskTest {
    @Test
    void opensBelowTheAnchor() {
        Placement p = PopupLayout.place(100, 50, 80, 14, 60, 12, 5, 400, 300);
        assertFalse(p.flipped());
        assertEquals(64, p.y(), 1e-9);
        assertEquals(100, p.x(), 1e-9);
        assertEquals(80, p.w(), 1e-9);
        assertEquals(60, p.h(), 1e-9);
        assertEquals(5, p.visible());
    }

    @Test
    void flipsAboveWhenThereIsNoRoomBelow() {
        Placement p = PopupLayout.place(100, 270, 80, 14, 60, 12, 5, 400, 300);
        assertTrue(p.flipped());
        assertEquals(270 - 60, p.y(), 1e-9);
        assertEquals(5, p.visible());
    }

    @Test
    void shrinksWhenNeitherSideFits() {
        Placement p = PopupLayout.place(10, 100, 80, 14, 60, 12, 12, 400, 160);
        // below: 160 - 114 = 46 (3 rows); above: 100 (8 rows) -> flips above, all 12 do not fit: 8 rows
        assertTrue(p.flipped());
        assertEquals(8, p.visible());
        assertTrue(p.y() >= 0);
    }

    @Test
    void isClampedAtTheScreenEdges() {
        Placement right = PopupLayout.place(380, 10, 40, 14, 120, 12, 3, 400, 300);
        assertEquals(400, right.x() + right.w(), 1e-9);
        Placement left = PopupLayout.place(-30, 10, 40, 14, 20, 12, 3, 400, 300);
        assertEquals(0, left.x(), 1e-9);
        Placement wide = PopupLayout.place(0, 10, 40, 14, 900, 12, 3, 400, 300);
        assertEquals(400, wide.w(), 1e-9);
    }

    @Test
    void showsAtMostTwelveRowsAndScrolls() {
        Placement p = PopupLayout.place(0, 0, 50, 14, 50, 10, 40, 400, 900);
        assertEquals(PopupLayout.MAX_ROWS, p.visible());
        assertEquals(28, PopupLayout.maxScroll(40, p.visible()));
        assertEquals(28, PopupLayout.clampScroll(99, 40, p.visible()));
        assertEquals(0, PopupLayout.clampScroll(-3, 40, p.visible()));
        assertEquals(0, PopupLayout.maxScroll(5, 12));
        assertEquals(5, PopupLayout.scrollTo(5, 9, 12));
        assertEquals(8, PopupLayout.scrollTo(19, 0, 12));
        assertEquals(3, PopupLayout.scrollTo(5, 3, 12));
    }

    @Test
    void hitTestUsesTheScroll() {
        Placement p = PopupLayout.place(0, 0, 50, 14, 50, 10, 40, 400, 900);
        assertEquals(10 + 0, PopupLayout.rowAt(p, 10, 10, 40, 5, 14 + 1));
        assertEquals(10 + 2, PopupLayout.rowAt(p, 10, 10, 40, 5, 14 + 25));
        assertEquals(-1, PopupLayout.rowAt(p, 10, 0, 40, 60, 20), "right of the list: outside, so the list closes");
        assertEquals(-1, PopupLayout.rowAt(p, 10, 0, 40, 5, 5), "over the anchor, not the list");
        assertEquals(-1, PopupLayout.rowAt(p, 10, 0, 40, 5, 14 + 10 * 12 + 1), "below the last visible row");
        Placement few = PopupLayout.place(0, 0, 50, 14, 50, 10, 2, 400, 900);
        assertEquals(1, PopupLayout.rowAt(few, 10, 0, 2, 5, 14 + 12));
        assertEquals(-1, PopupLayout.rowAt(few, 10, 0, 2, 5, 14 + 21));
    }

    @Test
    void theMaskBlocksOnlyRealOverlap() {
        OverlayMask m = new OverlayMask();
        assertTrue(m.isEmpty());
        assertFalse(m.blocks(0, 0, 100, 100));
        m.set(OverlayMask.POPUP, 10, 10, 20, 20);
        assertFalse(m.isEmpty());
        assertTrue(m.blocks(0, 0, 11, 11));
        assertTrue(m.blocks(15, 15, 2, 2));
        assertTrue(m.blocks(29, 29, 5, 5));
        assertFalse(m.blocks(30, 10, 5, 5), "touching edges don't block");
        assertFalse(m.blocks(0, 0, 10, 10));
        assertFalse(m.blocks(10, 30, 5, 5));
        assertFalse(m.blocks(50, 50, 5, 5));
        m.set(OverlayMask.HOVER, 100, 100, 10, 10);
        assertTrue(m.blocks(105, 105, 1, 1));
        assertTrue(m.blocks(15, 15, 1, 1));
        assertTrue(m.covers(15, 15));
        assertFalse(m.covers(30, 30));
        m.clear(OverlayMask.POPUP);
        assertFalse(m.blocks(15, 15, 1, 1));
        assertTrue(m.blocks(105, 105, 1, 1));
        m.clearAll();
        assertTrue(m.isEmpty());
    }
}
