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
import com.porter.qwerte.printer.place.PlaceRecipe.Dir;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BuildOrdererTest {
    /** A flat world: solid ground at y < 0 and whatever the test adds; everything else is free. */
    private static final class Flat implements World {
        final Set<List<Integer>> solid = new HashSet<>();
        final Set<List<Integer>> blocked = new HashSet<>();

        @Override
        public boolean solid(int x, int y, int z) {
            return y < 0 || solid.contains(List.of(x, y, z));
        }

        @Override
        public boolean free(int x, int y, int z) {
            return y >= 0 && !solid.contains(List.of(x, y, z)) && !blocked.contains(List.of(x, y, z));
        }

        void put(int x, int y, int z) {
            solid.add(List.of(x, y, z));
        }
    }

    private static Cell solid(int x, int y, int z) {
        return new Cell(x, y, z, Role.SOLID, null, null, null);
    }

    private static Cell attach(int x, int y, int z, Dir support) {
        return new Cell(x, y, z, Role.ATTACHABLE, support, null, null);
    }

    private static Cell at(List<Cell> l, int x, int y, int z) {
        return l.stream().filter(c -> c.x() == x && c.y() == y && c.z() == z).findFirst().orElseThrow();
    }

    private static final Box NONE = Box.NONE;

    private static BuildOrderer orderer(Mode m, boolean bottomUp, boolean air, Cell... cells) {
        BuildOrderer o = new BuildOrderer(m, bottomUp, air);
        for (Cell c : cells) assertTrue(o.add(c));
        return o;
    }

    private static Cell pick(BuildOrderer o, World w, double px, double py, double pz) {
        return o.pick(px, py, pz, 12, NONE, w, null);
    }

    /** Places everything in the order the orderer gives, standing at the origin; returns the order. */
    private static List<Cell> drain(BuildOrderer o, Flat w, double px, double py, double pz, double reach) {
        List<Cell> order = new ArrayList<>();
        Cell c;
        while ((c = o.pick(px, py, pz, reach, NONE, w, null)) != null) {
            order.add(c);
            o.placed(c);
            if (c.role() != Role.ATTACHABLE) w.put(c.x(), c.y(), c.z());
        }
        return order;
    }

    @Test
    void supportsBeforeAttachables() {
        Flat w = new Flat();
        Cell torch = attach(0, 1, 0, Dir.DOWN);
        Cell stone = solid(0, 0, 0);
        BuildOrderer o = orderer(Mode.LAYER_THEN_DISTANCE, true, false, torch, stone);
        assertEquals(stone, pick(o, w, 0, 1, 3)); // the torch is not ready: nothing below it
        assertFalse(o.ready(torch, NONE, w));
        o.placed(stone);
        w.put(0, 0, 0);
        assertTrue(o.ready(torch, NONE, w));
        assertEquals(torch, pick(o, w, 0, 1, 3));
    }

    @Test
    void anAttachableWaitsForItsOwnSupportDirectionNotJustAnyNeighbour() {
        Flat w = new Flat();
        Cell wallTorch = attach(0, 2, 0, Dir.NORTH); // needs a block at z = -1
        BuildOrderer o = orderer(Mode.LAYER_THEN_DISTANCE, true, false, wallTorch);
        w.put(1, 2, 0); // a neighbour on the wrong side
        assertFalse(o.ready(wallTorch, NONE, w));
        w.put(0, 2, -1);
        assertTrue(o.ready(wallTorch, NONE, w));
    }

    @Test
    void placedSolidCellsCountAsSupportsBeforeTheWorldCatchesUp() {
        Flat w = new Flat();
        Cell base = solid(0, 0, 0);
        Cell plate = attach(0, 1, 0, Dir.DOWN);
        BuildOrderer o = orderer(Mode.LAYER_THEN_DISTANCE, true, false, base, plate);
        o.placed(base); // placed, not yet in the world
        assertTrue(o.isPlacedSolid(0, 0, 0));
        assertTrue(o.ready(plate, NONE, w));
        assertEquals(1, o.pending());
    }

    @Test
    void solidBlocksNeedANeighbourUnlessAirplaceIsAllowed() {
        Flat w = new Flat();
        Cell floating = solid(10, 5, 10);
        assertNull(pick(orderer(Mode.LAYER_THEN_DISTANCE, true, false, floating), w, 10, 5, 12));
        assertEquals(floating, pick(orderer(Mode.LAYER_THEN_DISTANCE, true, true, floating), w, 10, 5, 12));
        w.put(10, 4, 10);
        assertEquals(floating, pick(orderer(Mode.LAYER_THEN_DISTANCE, true, false, floating), w, 10, 5, 12));
    }

    @Test
    void gravityBlocksWaitForASolidCellBelow() {
        Flat w = new Flat();
        Cell sand = new Cell(0, 3, 0, Role.GRAVITY, null, null, null);
        Cell pillar = solid(0, 2, 0);
        BuildOrderer o = orderer(Mode.LAYER_THEN_DISTANCE, true, true, sand, pillar);
        assertFalse(o.ready(sand, NONE, w)); // airplace does not help a falling block
        w.put(0, 2, 0);
        assertTrue(o.ready(sand, NONE, w));
        // ground below counts
        assertTrue(o.ready(new Cell(5, 0, 5, Role.GRAVITY, null, null, null), NONE, w));
    }

    @Test
    void gravityBlockIsPlacedAfterTheCellBelowInAFullRun() {
        Flat w = new Flat();
        BuildOrderer o = orderer(Mode.LAYER_THEN_DISTANCE, true, false,
            new Cell(0, 1, 0, Role.GRAVITY, null, null, null), solid(0, 0, 0));
        List<Cell> order = drain(o, w, 0, 1, 3, 8);
        assertEquals(2, order.size());
        assertEquals(0, order.get(0).y());
        assertEquals(1, order.get(1).y());
    }

    @Test
    void twoPartBlocksNeedBothCellsFree() {
        Flat w = new Flat();
        Cell door = new Cell(0, 0, 0, Role.ATTACHABLE, Dir.DOWN, Dir.UP, null);
        BuildOrderer o = orderer(Mode.LAYER_THEN_DISTANCE, true, true, door);
        assertTrue(o.ready(door, NONE, w));
        w.blocked.add(List.of(0, 1, 0)); // something in the top cell
        assertFalse(o.ready(door, NONE, w));
        w.blocked.clear();
        w.put(0, 1, 0);
        assertFalse(o.ready(door, NONE, w));
        // a bed: the head cell sits to the side
        Cell bed = new Cell(4, 0, 4, Role.SOLID, null, Dir.EAST, null);
        BuildOrderer b = orderer(Mode.LAYER_THEN_DISTANCE, true, true, bed);
        assertTrue(b.ready(bed, NONE, w));
        w.blocked.add(List.of(5, 0, 4));
        assertFalse(b.ready(bed, NONE, w));
    }

    @Test
    void neverACellInsideThePlayersBox() {
        Flat w = new Flat();
        Cell here = solid(0, 0, 0);
        Cell there = solid(3, 0, 0);
        BuildOrderer o = orderer(Mode.DISTANCE, true, true, here, there);
        Box box = new Box(-0.3, 0, -0.3, 0.3, 1.8, 0.3); // player standing in cell 0,0,0 (and 0,1,0)
        assertFalse(o.ready(here, box, w));
        assertEquals(there, o.pick(0, 0, 0, 6, box, w, null));
        assertEquals(here, o.pick(0, 0, 0, 6, NONE, w, null));
        // touching the edge is not overlapping
        assertTrue(o.ready(there, new Box(1.0, 0, -0.3, 1.6, 1.8, 0.3), w));
        // the partner cell counts too
        Cell door = new Cell(0, 1, 0, Role.ATTACHABLE, Dir.DOWN, Dir.UP, null);
        Box tall = new Box(-0.3, 1.5, -0.3, 0.3, 3.3, 0.3); // in cell y 1 .. 3, so cell (0,2,0) overlaps
        assertFalse(new BuildOrderer(Mode.DISTANCE, true, true).ready(door, tall, w));
    }

    @Test
    void cellsThatAreNotFreeAreNeverOffered() {
        Flat w = new Flat();
        Cell c = solid(0, 0, 0);
        w.put(0, 0, 0);
        assertNull(pick(orderer(Mode.DISTANCE, true, true, c), w, 0, 0, 3));
    }

    @Test
    void lowerLayersComeFirstEvenWhenFartherAway() {
        Flat w = new Flat();
        BuildOrderer o = orderer(Mode.LAYER_THEN_DISTANCE, true, true, solid(1, 1, 0), solid(5, 0, 0), solid(0, 2, 0));
        List<Cell> order = drain(o, w, 0, 1, 0, 10);
        assertEquals(List.of(0, 1, 2), order.stream().map(Cell::y).toList());
    }

    @Test
    void withinALayerTheNearestComesFirst() {
        Flat w = new Flat();
        BuildOrderer o = orderer(Mode.LAYER_THEN_DISTANCE, true, true, solid(5, 0, 0), solid(2, 0, 0), solid(9, 0, 0), solid(0, 0, 3));
        List<Cell> order = drain(o, w, 0, 0, 0, 12);
        assertEquals(List.of(solid(2, 0, 0), solid(0, 0, 3), solid(5, 0, 0), solid(9, 0, 0)), order);
    }

    @Test
    void distanceModeIgnoresLayersAndTopDownReversesThem() {
        Flat w = new Flat();
        Cell low = solid(8, 0, 0), high = solid(1, 4, 0);
        assertEquals(high, pick(orderer(Mode.DISTANCE, true, true, low, high), w, 0, 4, 0));
        assertEquals(low, pick(orderer(Mode.LAYER_THEN_DISTANCE, true, true, low, high), w, 0, 4, 0));
        assertEquals(high, pick(orderer(Mode.LAYER_THEN_DISTANCE, false, true, low, high), w, 0, 4, 0));
        assertEquals(high, pick(orderer(Mode.LAYER, false, true, low, high), w, 0, 0, 0));
        assertEquals(low, pick(orderer(Mode.LAYER, true, true, low, high), w, 0, 4, 0));
    }

    @Test
    void layerModeBreaksTiesByPositionNotDistance() {
        Flat w = new Flat();
        BuildOrderer o = orderer(Mode.LAYER, true, true, solid(3, 0, 0), solid(1, 0, 5), solid(1, 0, 2));
        assertEquals(solid(1, 0, 2), pick(o, w, 3, 0, 0));
    }

    @Test
    void tiesPreferTheCellCompatibleWithThePreviousLook() {
        Flat w = new Flat();
        Cell south = solid(2, 0, 0).withLook(LookWindow.quadrant(Dir.SOUTH));
        Cell north = solid(-3, 0, 0).withLook(LookWindow.quadrant(Dir.NORTH));
        BuildOrderer o = orderer(Mode.LAYER_THEN_DISTANCE, true, true, south, north);
        // both are 2.5 blocks away: position alone would pick x = -3
        assertEquals(north, o.pick(0, 0, 0, 6, NONE, w, null));
        assertEquals(south, o.pick(0, 0, 0, 6, NONE, w, LookWindow.quadrant(Dir.SOUTH)));
        assertEquals(north, o.pick(0, 0, 0, 6, NONE, w, LookWindow.quadrant(Dir.NORTH)));
        // a cell without a look is compatible with anything
        Cell plain = solid(2, 0, 3);
        Cell other = solid(2, 0, -4).withLook(LookWindow.quadrant(Dir.NORTH));
        BuildOrderer p = orderer(Mode.DISTANCE, true, true, plain, other);
        assertEquals(plain, p.pick(0, 0, 0, 6, NONE, w, LookWindow.quadrant(Dir.SOUTH)));
    }

    @Test
    void reachLimitsPicksAndNearestIgnoresIt() {
        Flat w = new Flat();
        Cell near = solid(2, 0, 0), far = solid(200, 0, 0);
        BuildOrderer o = orderer(Mode.LAYER_THEN_DISTANCE, true, true, far, near);
        assertEquals(near, o.pick(0, 0, 0, 6, NONE, w, null));
        o.placed(near);
        assertNull(o.pick(0, 0, 0, 6, NONE, w, null));
        assertEquals(far, o.nearest(0, 0, 0, NONE, w, null));
        assertEquals(far, o.pick(190, 0, 0, 12, NONE, w, null));
    }

    @Test
    void sectionsAreSixteenCubedAndWorkAcrossNegativeCoordinates() {
        Flat w = new Flat();
        BuildOrderer o = orderer(Mode.LAYER_THEN_DISTANCE, true, true,
            solid(-1, 0, -1), solid(15, 0, 15), solid(16, 0, 16), solid(-17, 0, 0), solid(15, 16, 0));
        assertEquals(5, o.pending());
        // from the origin, a reach of 3 sees only the cell at (-1,0,-1)
        assertEquals(List.of(solid(-1, 0, -1)), o.top(5, 0, 0, 0, 3, NONE, w, null));
        // a reach of 30 reaches the neighbouring sections too: layer 0 by distance, then layer 16
        assertEquals(
            List.of(solid(-1, 0, -1), solid(-17, 0, 0), solid(15, 0, 15), solid(16, 0, 16), solid(15, 16, 0)),
            o.top(5, 0, 0, 0, 30, NONE, w, null));
        for (Cell c : new ArrayList<>(o.snapshot())) assertTrue(o.remove(c));
        assertEquals(0, o.pending());
        assertNull(o.nearest(0, 0, 0, NONE, w, null));
    }

    @Test
    void topReturnsTheBestNInOrder() {
        Flat w = new Flat();
        BuildOrderer o = orderer(Mode.DISTANCE, true, true, solid(1, 0, 0), solid(2, 0, 0), solid(3, 0, 0), solid(4, 0, 0));
        List<Cell> t = o.top(3, 0, 0, 0, 10, NONE, w, null);
        assertEquals(List.of(1, 2, 3), t.stream().map(Cell::x).toList());
        assertTrue(o.top(0, 0, 0, 0, 10, NONE, w, null).isEmpty());
        assertEquals(4, o.top(10, 0, 0, 0, 10, NONE, w, null).size());
    }

    @Test
    void theSameCellsGiveTheSameSequenceInAnyInsertionOrder() {
        List<Cell> cells = new ArrayList<>();
        for (int x = 0; x < 6; x++) {
            for (int z = 0; z < 6; z++) {
                for (int y = 0; y < 3; y++) cells.add(y == 0 || y == 2 || (x + z) % 2 == 0 ? solid(x, y, z) : attach(x, y, z, Dir.DOWN));
            }
        }
        for (Mode m : Mode.values()) {
            for (boolean bottomUp : new boolean[] { true, false }) {
                List<Cell> reference = null;
                for (long seed = 1; seed <= 5; seed++) {
                    List<Cell> shuffled = new ArrayList<>(cells);
                    Collections.shuffle(shuffled, new Random(seed));
                    BuildOrderer o = new BuildOrderer(m, bottomUp, true);
                    for (Cell c : shuffled) o.add(c);
                    List<Cell> order = drain(o, new Flat(), 3, 1, 3, 10);
                    assertEquals(cells.size(), order.size(), m + " " + bottomUp);
                    if (reference == null) reference = order;
                    else assertEquals(reference, order, m + " " + bottomUp + " seed " + seed);
                }
            }
        }
    }

    @Test
    void aBuiltStructureNeverPlacesAnAttachableBeforeItsSupportOrAGravityBlockBeforeTheCellBelow() {
        for (boolean bottomUp : new boolean[] { true, false }) {
            Flat w = new Flat();
            BuildOrderer o = new BuildOrderer(Mode.LAYER_THEN_DISTANCE, bottomUp, false);
            Set<List<Integer>> done = new HashSet<>();
            int total = 0;
            for (int x = 0; x < 4; x++) {
                for (int z = 0; z < 4; z++) {
                    o.add(solid(x, 0, z));
                    total++;
                    if ((x + z) % 2 == 0) {
                        o.add(attach(x, 1, z, Dir.DOWN)); // a plate on the floor
                    } else {
                        o.add(solid(x, 1, z));
                        o.add(new Cell(x, 2, z, Role.GRAVITY, null, null, null)); // sand on that
                        total++;
                    }
                    total++;
                }
            }
            Cell c;
            int placed = 0;
            while ((c = o.pick(1.5, 1, 1.5, 20, NONE, w, null)) != null) {
                if (c.role() == Role.ATTACHABLE) assertTrue(done.contains(List.of(c.x(), c.y() - 1, c.z())), "attachable before its support: " + c);
                if (c.role() == Role.GRAVITY) assertTrue(done.contains(List.of(c.x(), c.y() - 1, c.z())), "gravity block before the cell below: " + c);
                done.add(List.of(c.x(), c.y(), c.z()));
                o.placed(c);
                if (c.role() != Role.ATTACHABLE) w.put(c.x(), c.y(), c.z());
                placed++;
            }
            assertEquals(total, placed, "bottomUp=" + bottomUp);
            assertEquals(0, o.pending());
        }
    }

    @Test
    void duplicatesAreIgnoredAndRemovalAdjustsTheCount() {
        BuildOrderer o = new BuildOrderer(Mode.DISTANCE, true, true);
        assertTrue(o.add(solid(1, 2, 3)));
        assertFalse(o.add(solid(1, 2, 3)));
        assertEquals(1, o.pending());
        assertFalse(o.remove(solid(9, 9, 9)));
        assertTrue(o.remove(solid(1, 2, 3)));
        assertEquals(0, o.pending());
        assertFalse(o.remove(solid(1, 2, 3)));
        assertNull(o.pick(0, 0, 0, 5, NONE, new Flat(), null));
    }

    @Test
    void skippedCellsThatNeverBecomeReadyAreVisibleInTheSnapshot() {
        Flat w = new Flat();
        Cell hanging = attach(0, 10, 0, Dir.UP); // nothing above, ever
        BuildOrderer o = orderer(Mode.LAYER_THEN_DISTANCE, true, true, hanging, solid(0, 0, 0));
        assertEquals(solid(0, 0, 0), pick(o, w, 0, 0, 2));
        o.placed(solid(0, 0, 0));
        w.put(0, 0, 0);
        assertNull(o.nearest(0, 0, 0, NONE, w, null));
        assertEquals(List.of(hanging), o.snapshot());
        assertNotNull(at(o.snapshot(), 0, 10, 0));
    }

    @Test
    void stateSpecsFeedTheOrderer() {
        StateSpec torch = StateSpec.of("minecraft:wall_torch", StateKind.WALL_OR_STANDING, java.util.Map.of("facing", "north"), false);
        Cell c = Cell.of(4, 5, 6, torch, LookWindow.any());
        assertEquals(Role.ATTACHABLE, c.role());
        assertEquals(Dir.SOUTH, c.support());
        StateSpec door = StateSpec.of("minecraft:oak_door", StateKind.DOOR, java.util.Map.of("facing", "north", "half", "lower", "hinge", "left"), false);
        assertEquals(Dir.UP, Cell.of(0, 0, 0, door, null).partner());
        StateSpec sand = StateSpec.of("minecraft:sand", StateKind.FULL, java.util.Map.of(), true);
        assertEquals(Role.GRAVITY, Cell.of(0, 0, 0, sand, null).role());
    }
}
