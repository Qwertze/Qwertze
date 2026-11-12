package com.porter.qwerte.messenger;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Snapping to the screen edges, the saved format with anchors and side, and keeping anchors when the screen changes. */
class EdgeSnapTest {
    private static final int SW = 800;
    private static final int SH = 450;
    private static final Geometry WIN = new Geometry(100, 100, 300, 200);

    // ---------- moving ----------

    @Test
    void snapsInWhenAnEdgeIsWithinTheDistance() {
        Geometry g = EdgeSnap.move(WIN, 5, 100, SW, SH, true, 8);
        assertEquals(0, g.x());
        assertTrue(g.anchored(Geometry.LEFT));
        assertFalse(g.anchored(Geometry.RIGHT));
        // exactly the distance still snaps, one more does not
        assertEquals(0, EdgeSnap.move(WIN, 8, 100, SW, SH, true, 8).x());
        assertEquals(9, EdgeSnap.move(WIN, 9, 100, SW, SH, true, 8).x());
    }

    @Test
    void snapsTheRightAndBottomEdgesFlush() {
        Geometry g = EdgeSnap.move(WIN, SW - 300 - 4, SH - 200 + 6, SW, SH, true, 8);
        assertEquals(SW - 300, g.x());
        assertEquals(SH - 200, g.y());
        assertTrue(g.anchored(Geometry.RIGHT) && g.anchored(Geometry.BOTTOM));
        assertFalse(g.anchored(Geometry.LEFT) || g.anchored(Geometry.TOP));
    }

    @Test
    void slidesFreelyAlongTheEdge() {
        // snapped to the left edge, the y follows the mouse
        for (int y : new int[] {20, 77, 150, 240}) {
            Geometry g = EdgeSnap.move(WIN, 3, y, SW, SH, true, 8);
            assertEquals(0, g.x());
            assertEquals(y, g.y());
        }
        // a mouse that drifts a little off the edge sideways stays snapped
        assertEquals(0, EdgeSnap.move(WIN, 7, 120, SW, SH, true, 8).x());
        assertEquals(0, EdgeSnap.move(WIN, -6, 120, SW, SH, true, 8).x());
    }

    @Test
    void letsGoOnlyWhenTheMouseIsPulledPastTheDistance() {
        // judged from where the mouse would put the window, not from the snapped position
        Geometry snapped = EdgeSnap.move(WIN, 6, 120, SW, SH, true, 8);
        assertEquals(0, snapped.x());
        Geometry pulled = EdgeSnap.move(snapped, 9, 120, SW, SH, true, 8);
        assertEquals(9, pulled.x());
        assertFalse(pulled.anchored(Geometry.LEFT));
        // and it snaps again when brought back
        assertEquals(0, EdgeSnap.move(pulled, 8, 120, SW, SH, true, 8).x());
    }

    @Test
    void twoEdgesMakeACorner() {
        Geometry g = EdgeSnap.move(WIN, 4, SH - 200 - 3, SW, SH, true, 8);
        assertEquals(0, g.x());
        assertEquals(SH - 200, g.y());
        assertEquals(Geometry.LEFT | Geometry.BOTTOM, g.anchors());
    }

    @Test
    void distanceZeroSnapsOnlyFlush() {
        assertEquals(1, EdgeSnap.move(WIN, 1, 100, SW, SH, true, 0).x());
        assertEquals(0, EdgeSnap.move(WIN, 0, 100, SW, SH, true, 0).x());
    }

    @Test
    void offMeansNoAnchorsAndTheOldClamp() {
        Geometry g = EdgeSnap.move(WIN, 3, 5, SW, SH, false, 8);
        assertEquals(3, g.x());
        assertEquals(5, g.y());
        assertEquals(0, g.anchors());
        assertEquals(new Geometry(-1000, 10, 300, 200).clamp(SW, SH).x(), EdgeSnap.move(WIN, -1000, 10, SW, SH, false, 8).x());
    }

    @Test
    void aWindowAsWideAsTheScreenSnapsToBothSides() {
        Geometry wide = new Geometry(0, 100, SW, 100);
        Geometry g = EdgeSnap.move(wide, 5, 100, SW, SH, true, 8);
        assertEquals(0, g.x());
        assertEquals(Geometry.LEFT | Geometry.RIGHT, g.anchors());
    }

    @Test
    void theSideIsKept() {
        Geometry g = EdgeSnap.move(WIN.withSide(ListSide.RIGHT), 3, 3, SW, SH, true, 8);
        assertEquals(ListSide.RIGHT, g.side());
    }

    // ---------- resizing ----------

    @Test
    void theGripSnapsTheRightAndBottomEdges() {
        Geometry g = EdgeSnap.resize(WIN, SW - 5, SH - 7, SW, SH, true, 8);
        assertEquals(SW - 100, g.w());
        assertEquals(SH - 100, g.h());
        assertEquals(100, g.x());
        assertEquals(Geometry.RIGHT | Geometry.BOTTOM, g.anchors());
        Geometry free = EdgeSnap.resize(WIN, SW - 9, SH - 9, SW, SH, true, 8);
        assertEquals(SW - 9 - 100, free.w());
        assertEquals(0, free.anchors());
    }

    @Test
    void resizePullsOutWithTheMouseToo() {
        Geometry snapped = EdgeSnap.resize(WIN, SW - 2, 300, SW, SH, true, 8);
        assertTrue(snapped.anchored(Geometry.RIGHT));
        Geometry pulled = EdgeSnap.resize(snapped, SW - 20, 300, SW, SH, true, 8);
        assertFalse(pulled.anchored(Geometry.RIGHT));
        assertEquals(SW - 20 - 100, pulled.w());
    }

    @Test
    void resizeKeepsTheLeftAndTopAnchors() {
        Geometry corner = new Geometry(0, 0, 300, 200, Geometry.LEFT | Geometry.TOP, ListSide.LEFT);
        Geometry g = EdgeSnap.resize(corner, 400, 250, SW, SH, true, 8);
        assertEquals(Geometry.LEFT | Geometry.TOP, g.anchors());
        assertEquals(400, g.w());
        assertEquals(250, g.h());
    }

    @Test
    void resizeNeverGoesBelowTheMinimum() {
        Geometry g = EdgeSnap.resize(WIN, 0, 0, SW, SH, true, 8);
        assertEquals(Geometry.MIN_W, g.w());
        assertEquals(Geometry.MIN_H, g.h());
    }

    // ---------- the screen changes ----------

    @Test
    void anchoredEdgesFollowTheScreenWhenItChanges() {
        Geometry right = new Geometry(SW - 300, SH - 200, 300, 200, Geometry.RIGHT | Geometry.BOTTOM, ListSide.RIGHT);
        Geometry smaller = right.fit(640, 360);
        assertEquals(640 - 300, smaller.x());
        assertEquals(360 - 200, smaller.y());
        Geometry bigger = right.fit(1920, 1080);
        assertEquals(1920 - 300, bigger.x());
        assertEquals(1080 - 200, bigger.y());
        assertEquals(Geometry.RIGHT | Geometry.BOTTOM, bigger.anchors());
        assertEquals(ListSide.RIGHT, bigger.side());
    }

    @Test
    void theSizeIsClampedBeforeTheAnchorsAreApplied() {
        Geometry right = new Geometry(SW - 300, SH - 200, 300, 200, Geometry.RIGHT | Geometry.BOTTOM, ListSide.LEFT);
        Geometry tiny = right.fit(200, 100);
        assertEquals(200, tiny.w());
        assertEquals(100, tiny.h());
        assertEquals(0, tiny.x());
        assertEquals(0, tiny.y());
    }

    @Test
    void leftAndTopAnchorsStayAtZero() {
        Geometry g = new Geometry(0, 0, 300, 200, Geometry.LEFT | Geometry.TOP, ListSide.LEFT).fit(1000, 600);
        assertEquals(0, g.x());
        assertEquals(0, g.y());
    }

    @Test
    void aWindowThatIsNotSnappedKeepsTodaysClamp() {
        Geometry loose = new Geometry(700, 400, 300, 200);
        assertEquals(loose.clamp(640, 360), loose.fit(640, 360));
    }

    @Test
    void anAnchorThatCanNoLongerHoldIsDropped() {
        // full width at the old screen, left and right snapped; the screen gets wider but the window does not
        Geometry full = new Geometry(0, 10, 800, 100, Geometry.LEFT | Geometry.RIGHT, ListSide.LEFT);
        Geometry g = full.fit(1200, 450);
        assertEquals(0, g.x());
        assertEquals(Geometry.LEFT, g.anchors());
    }

    // ---------- the saved form ----------

    @Test
    void v2RoundTrips() {
        Geometry g = new Geometry(-3, 7, 330, 190, Geometry.RIGHT | Geometry.BOTTOM, ListSide.RIGHT);
        assertEquals("v2:-3,7,330,190,12,R", g.encode());
        assertEquals(g, Geometry.decode(g.encode()));
    }

    @Test
    void v1StillReads() {
        Geometry g = Geometry.decode("v1:10,20,300,150");
        assertEquals(new Geometry(10, 20, 300, 150, 0, ListSide.LEFT), g);
    }

    @Test
    void badV2IsRefused() {
        assertNull(Geometry.decode("v2:1,2,3,4"));
        assertNull(Geometry.decode("v2:1,2,3,4,16,L"));
        assertNull(Geometry.decode("v2:1,2,3,4,-1,L"));
        assertNull(Geometry.decode("v2:1,2,3,4,0,X"));
        assertNull(Geometry.decode("v2:1,2,3,4,0,"));
        assertNull(Geometry.decode("v2:1,2,0,4,0,L"));
        assertNull(Geometry.decode("v2:a,2,3,4,0,L"));
        assertNull(Geometry.decode("v3:1,2,3,4,0,L"));
    }

    // ---------- the list side ----------

    @Test
    void autoUsesAHysteresis() {
        ListSideMode auto = ListSideMode.AUTO;
        // a 300 wide window on an 800 wide screen: the centre is x + 150
        assertEquals(ListSide.RIGHT, auto.resolve(ListSide.LEFT, 300, 300, 800)); // centre 450 = 56.25%
        assertEquals(ListSide.LEFT, auto.resolve(ListSide.LEFT, 290, 300, 800)); // centre 440 = 55%: not more than 55
        assertEquals(ListSide.RIGHT, auto.resolve(ListSide.RIGHT, 220, 300, 800)); // centre 370 = 46.25%: stays
        assertEquals(ListSide.LEFT, auto.resolve(ListSide.RIGHT, 200, 300, 800)); // centre 350 = 43.75%
        assertEquals(ListSide.RIGHT, auto.resolve(ListSide.RIGHT, 210, 300, 800)); // centre 360 = 45%: not less than 45
        assertEquals(ListSide.LEFT, auto.resolve(null, 250, 300, 800)); // no side yet: left until it passes 55%
    }

    @Test
    void fixedSidesIgnoreWherethewindowIs() {
        assertEquals(ListSide.LEFT, ListSideMode.LEFT.resolve(ListSide.RIGHT, 700, 300, 800));
        assertEquals(ListSide.RIGHT, ListSideMode.RIGHT.resolve(ListSide.LEFT, 0, 300, 800));
    }

    // ---------- the overlay frame after the focused frame (anchored windows stayed off screen at the bottom) ----------

    private static final int GUI_W = 960;
    private static final int GUI_H = 540;

    private static boolean fullyOn(Geometry g, int sw, int sh) {
        return g.x() >= 0 && g.y() >= 0 && g.x() + g.w() <= sw && g.y() + g.h() <= sh;
    }

    /** The window snapped while focused (the focused screen's size), then drawn by the overlay at the same GUI size. */
    @Test
    void anAnchoredWindowStaysPutFromTheFocusedFrameToTheOverlayFrame() {
        Geometry w = new Geometry(300, 200, 330, 190);
        int[][] raws = {
            {2, 200},                           // left
            {GUI_W - 330 - 3, 200},             // right
            {300, 4},                           // top
            {300, GUI_H - 190 - 5},             // bottom
            {GUI_W - 330 - 2, GUI_H - 190 - 2}, // bottom right corner
            {3, GUI_H - 190 - 3},               // bottom left corner
        };
        for (int[] raw : raws) {
            Geometry focused = EdgeSnap.move(w, raw[0], raw[1], GUI_W, GUI_H, true, 8);
            assertNotEquals(0, focused.anchors());
            assertTrue(fullyOn(focused, GUI_W, GUI_H));
            // several frames of each kind, in turn, with the same GUI size: nothing moves, nothing leaves the screen
            Geometry g = focused;
            for (int frame = 0; frame < 6; frame++) {
                g = g.fit(GUI_W, GUI_H);
                assertEquals(focused, g);
                assertTrue(fullyOn(g, GUI_W, GUI_H));
            }
        }
    }

    /**
     * Meteor's Render2DEvent carries the GUI width as the screen height too. Fed to fit(), that puts a window anchored to
     * the bottom below the screen (and not one anchored to another edge): the bug the user saw. The module no longer uses
     * the event's sizes, but this is why it must not.
     */
    @Test
    void aHeightThatIsReallyTheWidthPutsOnlyBottomAnchorsOffScreen() {
        Geometry w = new Geometry(300, 200, 330, 190);
        Geometry left = EdgeSnap.move(w, 2, 200, GUI_W, GUI_H, true, 8);
        Geometry right = EdgeSnap.move(w, GUI_W - 330 - 3, 200, GUI_W, GUI_H, true, 8);
        Geometry top = EdgeSnap.move(w, 300, 4, GUI_W, GUI_H, true, 8);
        Geometry bottom = EdgeSnap.move(w, 300, GUI_H - 190 - 5, GUI_W, GUI_H, true, 8);
        Geometry corner = EdgeSnap.move(w, GUI_W - 330 - 2, GUI_H - 190 - 2, GUI_W, GUI_H, true, 8);
        assertTrue(fullyOn(left.fit(GUI_W, GUI_W), GUI_W, GUI_H));
        assertTrue(fullyOn(right.fit(GUI_W, GUI_W), GUI_W, GUI_H));
        assertTrue(fullyOn(top.fit(GUI_W, GUI_W), GUI_W, GUI_H));
        assertFalse(fullyOn(bottom.fit(GUI_W, GUI_W), GUI_W, GUI_H));
        assertFalse(fullyOn(corner.fit(GUI_W, GUI_W), GUI_W, GUI_H));
    }
}
