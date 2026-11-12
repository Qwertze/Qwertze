package com.porter.qwerte.messenger;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The rounded rectangle made of plain fills, and the title that gets cut. */
class RoundRectTest {
    private record Span(int x1, int y1, int x2, int y2) {
        int area() {
            return (x2 - x1) * (y2 - y1);
        }
    }

    private static List<Span> spans(int x1, int y1, int x2, int y2, int tl, int tr, int br, int bl) {
        List<Span> out = new ArrayList<>();
        RoundRect.spans(x1, y1, x2, y2, tl, tr, br, bl, (a, b, c, d) -> out.add(new Span(a, b, c, d)));
        return out;
    }

    /** How many spans cover each cell. */
    private static int[][] cover(List<Span> spans, int w, int h) {
        int[][] grid = new int[h][w];
        for (Span s : spans) {
            for (int y = s.y1; y < s.y2; y++) {
                for (int x = s.x1; x < s.x2; x++) grid[y][x]++;
            }
        }
        return grid;
    }

    @Test
    void radiusZeroIsOnePlainRectangle() {
        List<Span> s = spans(3, 4, 40, 30, 0, 0, 0, 0);
        assertEquals(List.of(new Span(3, 4, 40, 30)), s);
    }

    @Test
    void spansNeverOverlapAndStayInside() {
        for (int r = 1; r <= 12; r++) {
            for (int[] size : new int[][] {{60, 40}, {20, 20}, {9, 30}, {30, 5}, {1, 1}}) {
                int w = size[0];
                int h = size[1];
                List<Span> s = spans(0, 0, w, h, r, r, r, r);
                int[][] grid = cover(s, w, h);
                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) assertTrue(grid[y][x] <= 1, "overlap at " + x + "," + y + " r=" + r + " " + w + "x" + h);
                }
                for (Span sp : s) assertTrue(sp.x1 >= 0 && sp.y1 >= 0 && sp.x2 <= w && sp.y2 <= h && sp.area() > 0);
            }
        }
    }

    @Test
    void theCornersAreCutAndTheMiddleIsFull() {
        int[][] grid = cover(spans(0, 0, 40, 30, 6, 6, 6, 6), 40, 30);
        assertEquals(0, grid[0][0]);
        assertEquals(0, grid[0][39]);
        assertEquals(0, grid[29][0]);
        assertEquals(0, grid[29][39]);
        assertEquals(1, grid[0][20]);
        assertEquals(1, grid[15][0]);
        assertEquals(1, grid[15][39]);
        assertEquals(1, grid[29][20]);
        // a bigger radius cuts more
        int small = area(spans(0, 0, 40, 30, 2, 2, 2, 2));
        int big = area(spans(0, 0, 40, 30, 8, 8, 8, 8));
        assertTrue(big < small && small < 40 * 30);
    }

    @Test
    void eachCornerHasItsOwnRadius() {
        int[][] grid = cover(spans(0, 0, 30, 30, 8, 0, 0, 0), 30, 30);
        assertEquals(0, grid[0][0]);
        assertEquals(1, grid[0][29]);
        assertEquals(1, grid[29][0]);
        assertEquals(1, grid[29][29]);
    }

    @Test
    void theRadiusIsCutToHalfTheShorterSide() {
        List<Span> s = spans(0, 0, 40, 6, 50, 50, 50, 50);
        int[][] grid = cover(s, 40, 6);
        for (int[] row : grid) {
            for (int c : row) assertTrue(c <= 1);
        }
        assertEquals(1, grid[3][20]);
    }

    @Test
    void emptyShapesGiveNothing() {
        assertTrue(spans(5, 5, 5, 9, 3, 3, 3, 3).isEmpty());
        assertTrue(spans(5, 5, 9, 5, 3, 3, 3, 3).isEmpty());
        assertTrue(spans(9, 5, 5, 9, 3, 3, 3, 3).isEmpty());
    }

    @Test
    void aPanelInsideTheWindowNeverPaintsOutsideItsRoundedShape() {
        // the window 60 x 40 with radius 8; a header 13 high across the top, and a list on the left to the bottom
        int w = 60;
        int h = 40;
        int[][] window = cover(spans(0, 0, w, h, 8, 8, 8, 8), w, h);
        int[][] header = new int[h][w];
        int[][] list = new int[h][w];
        RoundRect.spansIn(0, 0, w, h, 8, 0, 0, w, 13, (a, b, c, d) -> mark(header, a, b, c, d));
        RoundRect.spansIn(0, 0, w, h, 8, 0, 13, 20, h, (a, b, c, d) -> mark(list, a, b, c, d));
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                assertTrue(header[y][x] <= 1 && list[y][x] <= 1, "overlap");
                if (window[y][x] == 0) assertEquals(0, header[y][x] + list[y][x], "painted outside the window at " + x + "," + y);
                boolean inHeader = y < 13;
                boolean inList = y >= 13 && x < 20;
                if (window[y][x] == 1 && inHeader) assertEquals(1, header[y][x]);
                if (window[y][x] == 1 && inList) assertEquals(1, list[y][x]);
            }
        }
    }

    private static void mark(int[][] grid, int x1, int y1, int x2, int y2) {
        for (int y = y1; y < y2; y++) {
            for (int x = x1; x < x2; x++) grid[y][x]++;
        }
    }

    private static int area(List<Span> spans) {
        return spans.stream().mapToInt(Span::area).sum();
    }

    // ---------- the title ----------

    private static final TitleFit.Measure M = String::length; // one pixel per character

    @Test
    void theWholeTitleWhenItFits() {
        var r = TitleFit.fit(M, TitleFit.PREFIX, "Global", 0, 100);
        assertEquals("Messenger - Global", r.text());
        assertFalse(r.badge());
    }

    @Test
    void thePrefixGoesFirst() {
        // "Messenger - Global" is 18; with a 10 wide badge and a gap of 3 only 14 is left
        var r = TitleFit.fit(M, TitleFit.PREFIX, "Global", 10, 27);
        assertEquals("Global", r.text());
        assertTrue(r.badge());
    }

    @Test
    void thenTheNameIsCutWithAnEllipsis() {
        var r = TitleFit.fit(M, TitleFit.PREFIX, "SomeVeryLongPlayerName", 0, 12);
        assertEquals("SomeVeryL...", r.text());
        assertTrue(r.text().length() <= 12);
        assertEquals("...", TitleFit.fit(M, TitleFit.PREFIX, "SomeVeryLongPlayerName", 0, 3).text());
        assertEquals("", TitleFit.fit(M, TitleFit.PREFIX, "SomeVeryLongPlayerName", 0, 2).text());
    }

    @Test
    void theBadgeLeavesRoomAndGoesWhenThereIsNone() {
        var tight = TitleFit.fit(M, TitleFit.PREFIX, "Global", 10, 20);
        assertFalse(tight.badge()); // 20 - 10 - 3 = 7 < the least room for text
        assertEquals("Messenger - Global", tight.text()); // without the badge the whole title fits
        var roomy = TitleFit.fit(M, TitleFit.PREFIX, "Global", 10, 40);
        assertTrue(roomy.badge());
        assertEquals("Messenger - Global", roomy.text());
    }

    @Test
    void noSubjectIsJustMessenger() {
        assertEquals("Messenger", TitleFit.fit(M, TitleFit.PREFIX, "", 0, 50).text());
    }

    @Test
    void aStatusTextHasNoPrefixAndIsCut() {
        assertEquals("Cancelled: w...", TitleFit.fit(M, "", "Cancelled: window too small", 0, 15).text());
    }

    @Test
    void anEllipsisNeverSplitsASurrogatePair() {
        String s = "ab😀cdefgh";
        for (int w = 3; w < 12; w++) {
            String t = TitleFit.ellipsize(M, s, w);
            assertFalse(t.contains("\uD83D") && !t.contains("😀"), t);
        }
    }
}
