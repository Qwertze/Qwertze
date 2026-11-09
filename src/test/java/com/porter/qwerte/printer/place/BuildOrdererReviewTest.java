package com.porter.qwerte.printer.place;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.porter.qwerte.printer.place.BuildOrderer.Box;
import com.porter.qwerte.printer.place.BuildOrderer.Cell;
import com.porter.qwerte.printer.place.BuildOrderer.Mode;
import com.porter.qwerte.printer.place.BuildOrderer.Role;
import com.porter.qwerte.printer.place.BuildOrderer.World;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BuildOrdererReviewTest {
    /** Ground below y = 0, plus whatever the test puts. */
    private static final class Flat implements World {
        final Set<Long> solid = new HashSet<>();
        boolean unloadedFar;

        static long k(int x, int y, int z) {
            return ((long) x << 40) ^ ((long) z << 16) ^ (y & 0xFFFF);
        }

        @Override
        public boolean solid(int x, int y, int z) {
            return y < 0 || solid.contains(k(x, y, z));
        }

        @Override
        public boolean free(int x, int y, int z) {
            return y >= 0 && !solid.contains(k(x, y, z));
        }

        @Override
        public boolean loaded(int x, int y, int z) {
            return !(unloadedFar && Math.abs(x) > 4000);
        }
    }

    private static Cell solid(int x, int y, int z) {
        return new Cell(x, y, z, Role.SOLID, null, null, null);
    }

    @Test
    void aPlacementThatFailedIsNoLongerASupport() {
        Flat w = new Flat();
        BuildOrderer o = new BuildOrderer(Mode.LAYER_THEN_DISTANCE, true, false);
        Cell a = solid(0, 3, 0), b = solid(1, 3, 0);
        // a floats: nothing under or beside it, so only b-after-a can be ready
        o.add(a);
        o.add(b);
        assertTrue(o.top(5, 0, 3, 0, 10, Box.NONE, w, null).isEmpty(), "nothing to place against");

        o.placed(a);
        assertTrue(o.isPlacedSolid(0, 3, 0));
        assertEquals(List.of(b), o.top(5, 0, 3, 0, 10, Box.NONE, w, null), "b may go against the block just placed");

        // the placement did not hold
        o.unplaced(0, 3, 0);
        o.add(a);
        assertFalse(o.isPlacedSolid(0, 3, 0));
        assertTrue(o.top(5, 0, 3, 0, 10, Box.NONE, w, null).isEmpty(), "the neighbour is no longer ready");
    }

    @Test
    void removalNeedsNoSortAndLeavesTheRest() {
        Flat w = new Flat();
        BuildOrderer o = new BuildOrderer(Mode.LAYER, true, true);
        for (int i = 0; i < 20; i++) o.add(solid(i, 0, 0));
        assertTrue(o.remove(solid(7, 0, 0)));
        assertFalse(o.remove(solid(7, 0, 0)));
        assertEquals(19, o.pending());
        List<Cell> top = o.top(30, 0, 0, 0, 100, Box.NONE, w, null);
        assertEquals(19, top.size());
        assertTrue(top.stream().noneMatch(c -> c.x() == 7));
        assertFalse(o.add(solid(3, 0, 0)), "a second cell at a position is ignored");
    }

    @Test
    void theOrderDoesNotDependOnTheOrderCellsWereAdded() {
        List<Cell> cells = new ArrayList<>();
        Random r = new Random(7);
        for (int i = 0; i < 600; i++) cells.add(solid(r.nextInt(40) - 20, r.nextInt(40), r.nextInt(40) - 20));
        List<Cell> shuffled = new ArrayList<>(cells);
        Collections.shuffle(shuffled, new Random(99));
        for (Mode m : Mode.values()) {
            for (boolean up : new boolean[] { true, false }) {
                BuildOrderer a = new BuildOrderer(m, up, true), b = new BuildOrderer(m, up, true);
                cells.forEach(a::add);
                shuffled.forEach(b::add);
                Flat w = new Flat();
                assertEquals(a.top(64, 0.5, 20, 0.5, 200, Box.NONE, w, null), b.top(64, 0.5, 20, 0.5, 200, Box.NONE, w, null), m + " " + up);
            }
        }
    }

    @Test
    void aFarSearchFindsReadyCellsOutOfReachAndSkipsUnloadedSections() {
        Flat w = new Flat();
        w.unloadedFar = true;
        BuildOrderer o = new BuildOrderer(Mode.LAYER_THEN_DISTANCE, true, true);
        o.add(solid(5000, 0, 0)); // not loaded
        o.add(solid(900, 0, 0));
        Cell far = o.nearest(0, 0, 0, Box.NONE, w, null);
        assertNotNull(far);
        assertEquals(900, far.x());
        assertNull(o.pick(0, 0, 0, 160, Box.NONE, w, null), "out of a 160 block reach");
    }

    @Test
    void theWorkCapStopsASearchAndSaysSo() {
        Flat w = new Flat();
        BuildOrderer o = new BuildOrderer(Mode.LAYER, true, true);
        for (int i = 0; i < 1000; i++) o.add(solid(i, 0, 0));
        o.setWorkCap(100);
        o.top(5, 0, 0, 0, Double.POSITIVE_INFINITY, Box.NONE, w, null);
        assertTrue(o.truncated());
        o.setWorkCap(0);
        o.top(5, 0, 0, 0, Double.POSITIVE_INFINITY, Box.NONE, w, null);
        assertFalse(o.truncated());
    }

    @Test
    void anIdleSectionIsNotSearchedAgainUntilTheCacheRunsOut() {
        Flat w = new Flat();
        BuildOrderer o = new BuildOrderer(Mode.LAYER, true, false);
        o.setIdleCache(5);
        Cell hanging = solid(0, 5, 0); // nothing to place against
        o.add(hanging);
        assertTrue(o.top(3, 0, 0, 0, Double.POSITIVE_INFINITY, Box.NONE, w, null).isEmpty());
        w.solid.add(Flat.k(0, 4, 0)); // now it could go
        assertTrue(o.top(3, 0, 0, 0, Double.POSITIVE_INFINITY, Box.NONE, w, null).isEmpty(), "still cached as idle");
        for (int i = 0; i < 5; i++) o.advance();
        assertEquals(1, o.top(3, 0, 0, 0, Double.POSITIVE_INFINITY, Box.NONE, w, null).size());
    }

    @Test
    void theAcceptTestKeepsUnusableCellsFromTakingPlaces() {
        Flat w = new Flat();
        BuildOrderer o = new BuildOrderer(Mode.LAYER, true, true);
        for (int i = 0; i < 10; i++) o.add(solid(i, 0, 0));
        List<Cell> top = o.top(3, 0, 0, 0, 100, Box.NONE, w, null, c -> c.x() >= 5);
        assertEquals(List.of(5, 6, 7), top.stream().map(Cell::x).toList());
    }

    @Test
    void timingOfHalfAMillionCells() {
        Flat w = new Flat();
        BuildOrderer o = new BuildOrderer(Mode.LAYER_THEN_DISTANCE, true, true);
        List<Cell> all = new ArrayList<>(500_000);
        long t0 = System.nanoTime();
        for (int y = 0; y < 50; y++) {
            for (int x = 0; x < 100; x++) {
                for (int z = 0; z < 100; z++) {
                    Cell c = solid(x, y, z);
                    all.add(c);
                    o.add(c);
                }
            }
        }
        long addMs = (System.nanoTime() - t0) / 1_000_000;
        Random r = new Random(1);
        t0 = System.nanoTime();
        int removed = 0;
        for (Cell c : all) {
            if (r.nextInt(10) < 3) {
                o.remove(c);
                removed++;
            }
        }
        long removeMs = (System.nanoTime() - t0) / 1_000_000;
        assertEquals(500_000 - removed, o.pending());

        t0 = System.nanoTime();
        List<Cell> far = o.top(16, -500, 0, -500, Double.POSITIVE_INFINITY, Box.NONE, w, null);
        long farMs = (System.nanoTime() - t0) / 1_000_000;
        t0 = System.nanoTime();
        List<Cell> near = o.top(65, 50, 3, 50, 5, Box.NONE, w, null);
        long nearMs = (System.nanoTime() - t0) / 1_000_000;
        System.out.println("BuildOrderer 500k cells: add " + addMs + " ms, remove " + removed + " " + removeMs + " ms, far top " + farMs + " ms, near top " + nearMs + " ms");
        assertEquals(16, far.size());
        assertFalse(near.isEmpty());
        assertTrue(addMs < 10_000 && removeMs < 10_000 && farMs < 5_000 && nearMs < 2_000, "generous bounds");
    }
}
