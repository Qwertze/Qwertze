package com.porter.qwerte.manager.gui.layout;

import com.porter.qwerte.manager.gui.layout.StashLayout.Layout;
import com.porter.qwerte.manager.gui.layout.StashLayout.R;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LayoutsTest {
    // ---------- FlowLayout ----------

    @Test
    void rowsWrapAndTheGapIsOnlyBetweenItems() {
        double[] w = {40, 40, 40, 40};
        List<int[]> rows = FlowLayout.rows(w, 100, 10);
        assertEquals(2, rows.size());
        assertEquals(0, rows.get(0)[0]);
        assertEquals(2, rows.get(0)[1]);
        assertEquals(2, rows.get(1)[0]);
        assertEquals(4, rows.get(1)[1]);
        // 40 + 10 + 40 = 90 fits; a third would be 140
        assertEquals(90, FlowLayout.rowWidth(w, 0, 2, 10), 0);
        assertEquals(40, FlowLayout.rowWidth(w, 0, 1, 10), 0);
        assertEquals(0, FlowLayout.rowWidth(w, 0, 0, 10), 0);
        // exactly full is allowed: 40 + 10 + 40 + 10 + 0
        assertEquals(1, FlowLayout.rows(new double[] {45, 45}, 100, 10).size());
        assertEquals(2, FlowLayout.rows(new double[] {45, 46}, 100, 10).size());
    }

    @Test
    void anItemWiderThanTheRowGetsItsOwnRow() {
        List<int[]> rows = FlowLayout.rows(new double[] {20, 500, 20, 20}, 100, 5);
        assertEquals(3, rows.size());
        assertEquals(1, rows.get(0)[1]);
        assertEquals(1, rows.get(1)[0]);
        assertEquals(2, rows.get(1)[1]);
        assertEquals(2, rows.get(2)[0]);
        assertEquals(4, rows.get(2)[1]);
    }

    @Test
    void emptyAndZeroWidthItems() {
        assertTrue(FlowLayout.rows(new double[0], 100, 5).isEmpty());
        assertEquals(1, FlowLayout.rows(new double[] {0, 0, 0}, 100, 5).size());
        assertEquals(1, FlowLayout.rows(new double[] {30}, 0, 5).size());
    }

    // ---------- StashLayout ----------

    @Test
    void theGridTakesTheWholePanelWidthAndTheDrawerNeverChangesIt() {
        Layout l = StashLayout.compute(620, 360, 9, 8, 15, 70, 1);
        assertEquals(620 - 2 * StashLayout.PAD, l.grid().w());
        assertEquals(StashLayout.PAD, l.grid().x());
        // the drawer is laid over the right side of the grid, inside it
        assertEquals(StashLayout.DRAWER_W, l.drawer().w());
        assertEquals(l.grid().x() + l.grid().w(), l.drawer().x() + l.drawer().w());
        assertEquals(l.grid().y(), l.drawer().y());
        assertEquals(l.grid().h(), l.drawer().h());
        // the width of the grid does not depend on anything about the details
        assertEquals(l.grid().w(), StashLayout.compute(620, 360, 9, 8, 15, 70, 1).grid().w());
    }

    @Test
    void aNarrowPageGivesTheDrawerThreeQuartersOfTheGrid() {
        Layout l = StashLayout.compute(200, 360, 9, 8, 15, 70, 1);
        assertEquals((200 - 2 * StashLayout.PAD) * 3 / 4, l.drawer().w());
        assertTrue(l.drawer().x() >= l.grid().x());
    }

    @Test
    void rowsGoTopToBottomAndHeightsAddUp() {
        Layout l = StashLayout.compute(620, 360, 9, 8, 15, 70, 1);
        R grid = l.grid();
        assertEquals(9, l.label().h());
        assertTrue(l.label().y() + l.label().h() <= grid.y());
        assertTrue(grid.y() + grid.h() <= l.search().y());
        assertEquals(l.search().y(), l.sort().y());
        assertTrue(l.search().y() + l.search().h() <= l.summary().y());
        assertEquals(8, l.summary().h());
        assertTrue(l.summary().y() + l.summary().h() <= l.hint().y());
        assertEquals(360 - StashLayout.PAD, l.hint().y() + l.hint().h());
        assertTrue(l.search().x() + l.search().w() <= l.sort().x());
        assertEquals(grid.x() + grid.w(), l.sort().x() + l.sort().w());
    }

    @Test
    void aTinyPageStillHasAGrid() {
        Layout l = StashLayout.compute(240, 170, 9, 8, 15, 70, 2);
        assertTrue(l.grid().h() >= GridLayout.SLOT + 4);
        assertTrue(l.grid().w() > 0);
        Layout absurd = StashLayout.compute(1, 1, 9, 8, 15, 70, 2);
        assertTrue(absurd.grid().w() >= 1);
        assertTrue(absurd.drawer().w() >= 1);
    }

    @Test
    void aShortPageReportsItsTrueHeightAndKeepsEverythingInside() {
        for (int h : new int[] {60, 100, 150, 200}) {
            Layout l = StashLayout.compute(300, h, 9, 8, 15, 70, 2);
            assertTrue(l.totalH() >= h);
            assertTrue(l.grid().h() >= StashLayout.GRID_MIN_H);
            assertTrue(l.grid().y() + l.grid().h() <= l.search().y());
            assertTrue(l.search().y() + l.search().h() <= l.summary().y());
            assertTrue(l.summary().y() + l.summary().h() <= l.hint().y());
            assertEquals(l.totalH() - StashLayout.PAD, l.hint().y() + l.hint().h(), "h=" + h);
            assertEquals(l.totalH(), l.panel().y() + l.panel().h());
        }
    }

    @Test
    void aRectContainsItsOwnPointsAndNotTheEdgeAfter() {
        R r = new R(10, 20, 30, 40);
        assertTrue(r.contains(10, 20));
        assertTrue(r.contains(39.5, 59.5));
        assertFalse(r.contains(40, 30));
        assertFalse(r.contains(20, 60));
        assertFalse(r.contains(9, 30));
    }

    // ---------- RoundRect ----------

    @Test
    void theRadiusIsClampedToHalfTheShorterSide() {
        assertEquals(5, RoundRect.clampRadius(5, 100, 100), 0);
        assertEquals(10, RoundRect.clampRadius(50, 100, 20), 0);
        assertEquals(0, RoundRect.clampRadius(-3, 100, 20), 0);
        assertEquals(0, RoundRect.clampRadius(5, 0, 20), 0);
    }

    @Test
    void radiusZeroMakesNoTriangles() {
        assertTrue(RoundRect.corners(0, 0, 100, 50, 0).isEmpty());
        assertTrue(RoundRect.corners(0, 0, 100, 50, -4).isEmpty());
        assertTrue(RoundRect.corners(0, 0, 0, 50, 8).isEmpty());
        assertEquals(0, RoundRect.segments(0));
    }

    @Test
    void cornerPointsStayInsideTheRectangle() {
        for (double r : new double[] {1, 2, 4, 6, 8, 12, 500}) {
            double x = 10;
            double y = 20;
            double w = 100;
            double h = 40;
            List<double[]> tris = RoundRect.corners(x, y, w, h, r);
            int n = RoundRect.segments(RoundRect.clampRadius(r, w, h));
            assertEquals(4 * n, tris.size());
            assertTrue(n >= 4 && n <= 6);
            for (double[] t : tris) {
                assertEquals(6, t.length);
                for (int i = 0; i < 6; i += 2) {
                    assertTrue(t[i] >= x - 1e-9 && t[i] <= x + w + 1e-9, "x " + t[i]);
                    assertTrue(t[i + 1] >= y - 1e-9 && t[i + 1] <= y + h + 1e-9, "y " + t[i + 1]);
                }
            }
        }
    }

    @Test
    void theArcTouchesTheEdges() {
        List<double[]> tris = RoundRect.corners(0, 0, 100, 100, 10);
        boolean topEdge = false;
        boolean leftEdge = false;
        for (double[] t : tris) {
            for (int i = 0; i < 6; i += 2) {
                if (Math.abs(t[i + 1]) < 1e-9 && Math.abs(t[i] - 10) < 1e-9) topEdge = true;
                if (Math.abs(t[i]) < 1e-9 && Math.abs(t[i + 1] - 10) < 1e-9) leftEdge = true;
            }
        }
        assertTrue(topEdge);
        assertTrue(leftEdge);
    }

    @Test
    void trianglesFaceTheSameWayAsMeteorsQuads() {
        // Meteor's quads run top-left, bottom-left, bottom-right: a negative cross product in screen coordinates. The UI
        // pipeline culls the other winding, so a corner with the wrong one would vanish.
        for (double[] t : RoundRect.corners(0, 0, 100, 60, 12)) {
            double cross = (t[2] - t[0]) * (t[5] - t[1]) - (t[3] - t[1]) * (t[4] - t[0]);
            assertTrue(cross < 0, "winding " + java.util.Arrays.toString(t));
        }
    }
}
