package com.porter.qwerte.printer.place;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.porter.qwerte.printer.place.LayerCursor.Counts;
import com.porter.qwerte.printer.place.LayerCursor.Mode;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class LayerCursorTest {
    /** Open cells per y; a layer is built by taking cells away. */
    private static final class Layers implements Counts {
        final TreeMap<Integer, Integer> open = new TreeMap<>();

        Layers(int... ysAndCounts) {
            for (int i = 0; i < ysAndCounts.length; i += 2) open.put(ysAndCounts[i], ysAndCounts[i + 1]);
        }

        void build(int y, int n) {
            open.merge(y, -n, Integer::sum);
            if (open.get(y) <= 0) open.remove(y);
        }

        @Override public int open(int y) { return open.getOrDefault(y, 0); }
        @Override public int low() { return open.isEmpty() ? Integer.MAX_VALUE : open.firstKey(); }
        @Override public int high() { return open.isEmpty() ? Integer.MIN_VALUE : open.lastKey(); }
        @Override public int total() { return open.values().stream().mapToInt(Integer::intValue).sum(); }
    }

    private static Integer tick(LayerCursor c, Layers l, boolean stalled) {
        return c.update(l, 0, y -> 0, stalled);
    }

    @Test
    void offNeverGates() {
        LayerCursor c = new LayerCursor(Mode.OFF);
        assertNull(tick(c, new Layers(1, 5), false));
        assertTrue(c.eligible(99));
    }

    @Test
    void bottomToTopTakesTheLowestLayerAndMovesUpWhenItIsDone() {
        Layers l = new Layers(5, 2, 3, 2, 9, 1);
        LayerCursor c = new LayerCursor(Mode.BOTTOM_TO_TOP);
        assertEquals(3, tick(c, l, false));
        l.build(3, 1);
        assertEquals(3, tick(c, l, false));
        l.build(3, 1);
        assertEquals(5, tick(c, l, false));
        l.build(5, 2);
        assertEquals(9, tick(c, l, false));
        l.build(9, 1);
        assertNull(tick(c, l, false));
    }

    @Test
    void topToBottomTakesTheHighestLayerFirst() {
        Layers l = new Layers(5, 2, 3, 2, 9, 1);
        LayerCursor c = new LayerCursor(Mode.TOP_TO_BOTTOM);
        assertEquals(9, tick(c, l, false));
        l.build(9, 1);
        assertEquals(5, tick(c, l, false));
        l.build(5, 2);
        assertEquals(3, tick(c, l, false));
    }

    @Test
    void anUnreadyLayerStaysActiveUntilItsLastCellIsBuilt() {
        Layers l = new Layers(0, 3, 1, 3);
        LayerCursor c = new LayerCursor(Mode.BOTTOM_TO_TOP);
        assertEquals(0, tick(c, l, false));
        l.build(0, 2);
        // layer 1 is full of cells, but layer 0 still has one: blocks above it are ignored
        for (int i = 0; i < 10; i++) assertEquals(0, tick(c, l, false));
    }

    @Test
    void mostEfficientPicksTheMostReadyThenTheNearestInHeightThenTheLowerY() {
        Layers l = new Layers(0, 10, 4, 10, 8, 10);
        LayerCursor c = new LayerCursor(Mode.MOST_EFFICIENT);
        // y=8 has the most ready cells
        assertEquals(8, c.update(l, 0, y -> y == 8 ? 7 : 3, false));
        // a tie in ready cells: the nearest to the player (y=4 for a player at 5)
        LayerCursor c2 = new LayerCursor(Mode.MOST_EFFICIENT);
        assertEquals(4, c2.update(l, 5, y -> 3, false));
        // a tie in both: the lower y
        LayerCursor c4 = new LayerCursor(Mode.MOST_EFFICIENT);
        assertEquals(2, c4.update(new Layers(2, 1, 6, 1), 4, y -> 1, false)); // |2-4| = |6-4|: the lower y
    }

    @Test
    void mostEfficientOnlyChoosesAgainWhenTheLayerIsDone() {
        Layers l = new Layers(0, 2, 5, 9);
        LayerCursor c = new LayerCursor(Mode.MOST_EFFICIENT);
        assertEquals(5, c.update(l, 0, y -> y == 5 ? 9 : 2, false));
        // layer 0 now has more ready cells, but 5 is still being printed
        assertEquals(5, c.update(l, 0, y -> y == 5 ? 1 : 50, false));
        l.build(5, 9);
        assertEquals(0, c.update(l, 0, y -> 2, false));
    }

    @Test
    void aStalledLayerIsCarriedOverAfterTheStallTicksAndTriedAgainLater() {
        // A lantern in layer 3 hangs from a block in layer 4: layer 3 can't finish before layer 4 is built
        Layers l = new Layers(3, 1, 4, 2);
        LayerCursor c = new LayerCursor(Mode.BOTTOM_TO_TOP);
        assertEquals(3, tick(c, l, true));
        for (int i = 1; i < LayerCursor.STALL_TICKS; i++) assertEquals(3, tick(c, l, true));
        assertEquals(4, tick(c, l, true)); // carried over
        assertTrue(c.isCarried(3));
        assertTrue(c.working());
        // layer 4 builds fine
        l.build(4, 1);
        assertEquals(4, tick(c, l, false));
        l.build(4, 1);
        // layer 4 is done: the carried layer is tried again, and now it can be built
        assertEquals(3, tick(c, l, false));
        assertFalse(c.isCarried(3));
        l.build(3, 1);
        assertNull(tick(c, l, false));
    }

    @Test
    void aGravityLayerBuildingTopDownIsCarriedOver() {
        Layers l = new Layers(8, 2, 2, 5);
        LayerCursor c = new LayerCursor(Mode.TOP_TO_BOTTOM);
        assertEquals(8, tick(c, l, false));
        for (int i = 0; i < LayerCursor.STALL_TICKS; i++) tick(c, l, true);
        assertEquals(2, tick(c, l, false));
    }

    @Test
    void progressResetsTheStall() {
        Layers l = new Layers(1, 5);
        LayerCursor c = new LayerCursor(Mode.BOTTOM_TO_TOP);
        tick(c, l, false);
        for (int round = 0; round < 4; round++) {
            for (int i = 0; i < LayerCursor.STALL_TICKS - 2; i++) tick(c, l, true);
            l.build(1, 1); // one block built: progress
            assertEquals(1, tick(c, l, true));
        }
        assertFalse(c.isCarried(1));
    }

    @Test
    void neverDeadlocksWhenNothingCanBeBuiltAnywhere() {
        Layers l = new Layers(1, 2, 2, 2, 3, 2);
        LayerCursor c = new LayerCursor(Mode.BOTTOM_TO_TOP);
        int ticks = 0;
        while (!c.free() && ticks++ < 10_000) tick(c, l, true);
        assertTrue(c.free(), "the gate opens after every layer was carried over");
        assertTrue(ticks < 10 * LayerCursor.STALL_TICKS, "within a few rounds: " + ticks);
        assertNull(tick(c, l, true));
        assertFalse(c.working());
        // everything is eligible for wrong blocks while the gate is open
        assertTrue(c.eligible(3));
        // the first block that gets built closes the gate again
        l.build(2, 1);
        Integer a = tick(c, l, false);
        assertEquals(1, a);
        assertFalse(c.free());
        assertEquals(0, c.carriedCount());
    }

    @Test
    void carryingOverTheOnlyLayerOpensTheGateAfterOneRound() {
        Layers l = new Layers(7, 3);
        LayerCursor c = new LayerCursor(Mode.TOP_TO_BOTTOM);
        for (int i = 0; i < LayerCursor.STALL_TICKS * 4 && !c.free(); i++) tick(c, l, true);
        assertTrue(c.free());
    }

    @Test
    void wrongBlocksAreForLayersAlreadyDoneOrTheActiveOne() {
        Layers l = new Layers(2, 1, 5, 1, 9, 1);
        LayerCursor up = new LayerCursor(Mode.BOTTOM_TO_TOP);
        up.update(l, 0, null, false);
        assertTrue(up.eligible(2));
        assertTrue(up.eligible(1));
        assertFalse(up.eligible(5));
        assertFalse(up.eligible(9));
        LayerCursor down = new LayerCursor(Mode.TOP_TO_BOTTOM);
        down.update(l, 0, null, false);
        assertTrue(down.eligible(9));
        assertTrue(down.eligible(12));
        assertFalse(down.eligible(5));
    }

    @Test
    void whenTheBuildIsEmptyTheCursorResets() {
        Layers l = new Layers(4, 1);
        LayerCursor c = new LayerCursor(Mode.BOTTOM_TO_TOP);
        assertEquals(4, tick(c, l, false));
        l.build(4, 1);
        assertNull(tick(c, l, false));
        assertFalse(c.working());
        l.open.putAll(Map.of(2, 1));
        assertEquals(2, tick(c, l, false));
    }
}
