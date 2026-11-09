package com.porter.qwerte.printer.place;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.porter.qwerte.printer.place.BuildOrderer.Cell;
import com.porter.qwerte.printer.place.BuildOrderer.Role;
import com.porter.qwerte.printer.place.PlaceRecipe.Dir;
import com.porter.qwerte.printer.place.ScaffoldPlanner.Box;
import com.porter.qwerte.printer.place.ScaffoldPlanner.Chain;
import com.porter.qwerte.printer.place.ScaffoldPlanner.Result;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ScaffoldPlannerTest {
    /** Solid ground below y=0, air above; the schematic wants the cells in {@code wanted}; {@code open} are still to build. */
    private static final class Fake implements ScaffoldPlanner.World {
        final Set<List<Integer>> solid = new HashSet<>();
        final Set<List<Integer>> wanted = new HashSet<>();
        final Map<List<Integer>, Cell> open = new HashMap<>();
        int groundTop = 0; // y below this is solid

        static List<Integer> k(int x, int y, int z) {
            return List.of(x, y, z);
        }

        @Override
        public boolean air(int x, int y, int z) {
            return !solid(x, y, z);
        }

        @Override
        public boolean solid(int x, int y, int z) {
            return y < groundTop || solid.contains(k(x, y, z));
        }

        @Override
        public boolean wanted(int x, int y, int z) {
            return wanted.contains(k(x, y, z));
        }

        @Override
        public Cell open(int x, int y, int z) {
            return open.get(k(x, y, z));
        }

        void want(int x, int y, int z, Role role, Dir support) {
            wanted.add(k(x, y, z));
            open.put(k(x, y, z), new Cell(x, y, z, role, support, null, null));
        }
    }

    private static final List<Box> AREA = List.of(new Box(0, 5, 0, 0, 5, 0));
    private static final long FOREVER = Long.MAX_VALUE;

    private static Result plan(Fake w, int x, int y, int z, int max) {
        return ScaffoldPlanner.plan(w, AREA, x, y, z, max, 0, 0, 0, FOREVER);
    }

    @Test
    void aFloatingCellGetsAColumnFromTheGround() {
        Fake w = new Fake();
        w.want(0, 5, 0, Role.SOLID, null);
        Chain c = plan(w, 0, 5, 0, 6).chain();
        assertNotNull(c);
        assertEquals(5, c.size());
        // The first block is the one on the ground, the last touches the cell
        assertEquals(0, c.blocks().get(0)[1]);
        assertEquals(4, c.blocks().get(4)[1]);
        for (int i = 0; i < 5; i++) assertEquals(i, c.blocks().get(i)[1]);
    }

    @Test
    void eachBlockCanBePlacedInOrder() {
        Fake w = new Fake();
        w.want(3, 4, 2, Role.SOLID, null);
        Chain c = plan(w, 3, 4, 2, 6).chain();
        assertNotNull(c);
        // Place them one by one: every block has a solid neighbour when its turn comes, and the last touches the cell
        for (int[] b : c.blocks()) {
            boolean touches = false;
            for (Dir d : Dir.values()) touches |= w.solid(b[0] + d.dx, b[1] + d.dy, b[2] + d.dz);
            assertTrue(touches, "block " + b[0] + " " + b[1] + " " + b[2] + " has nothing to be placed against");
            w.solid.add(Fake.k(b[0], b[1], b[2]));
        }
        boolean cellTouches = false;
        for (Dir d : Dir.values()) cellTouches |= w.solid(3 + d.dx, 4 + d.dy, 2 + d.dz);
        assertTrue(cellTouches);
    }

    @Test
    void aCellWithASolidNeighbourNeedsNothingButTheSearchStillFindsOneBlock() {
        Fake w = new Fake();
        w.want(0, 0, 0, Role.SOLID, null); // on the ground: its neighbour (0,-1,0) is solid
        // the neighbours of the cell that are air and touch the ground are one-block chains
        Chain c = plan(w, 0, 0, 0, 6).chain();
        assertNotNull(c);
        assertEquals(1, c.size());
    }

    @Test
    void neverInsideACellTheSchematicWantsABlockIn() {
        Fake w = new Fake();
        w.want(0, 5, 0, Role.SOLID, null);
        // a wall of wanted cells straight below it
        for (int y = 0; y <= 4; y++) w.wanted.add(Fake.k(0, y, 0));
        Chain c = plan(w, 0, 5, 0, 6).chain();
        assertNotNull(c, "a detour exists");
        assertEquals(6, c.size());
        for (int[] b : c.blocks()) assertFalse(w.wanted(b[0], b[1], b[2]), "scaffold inside a schematic cell at " + b[0] + " " + b[1] + " " + b[2]);
    }

    @Test
    void airCellsInsideTheBoundsAreAllowed() {
        Fake w = new Fake();
        w.want(0, 5, 0, Role.SOLID, null);
        // the bounds are 1x1x1 at y=5; the column below it is outside the box but within the margin, and air in the schematic
        Chain c = plan(w, 0, 5, 0, 6).chain();
        assertNotNull(c);
        assertTrue(c.blocks().stream().allMatch(b -> !w.wanted(b[0], b[1], b[2])));
    }

    @Test
    void notFartherThanTheMaximumChain() {
        Fake w = new Fake();
        w.want(0, 5, 0, Role.SOLID, null);
        assertNull(plan(w, 0, 5, 0, 4).chain(), "needs 5 blocks");
        assertNotNull(plan(w, 0, 5, 0, 5).chain());
        Fake high = new Fake();
        high.want(0, 5, 0, Role.SOLID, null);
        high.groundTop = -3; // 8 blocks to the ground
        assertNull(plan(high, 0, 5, 0, 99).chain(), "the chain is never longer than " + ScaffoldPlanner.MAX_CHAIN);
    }

    @Test
    void notFartherThanTheMarginFromTheBounds() {
        Fake w = new Fake();
        w.want(0, 5, 0, Role.SOLID, null);
        List<Box> far = List.of(new Box(100, 100, 100, 100, 100, 100));
        assertNull(ScaffoldPlanner.plan(w, far, 0, 5, 0, 6, 0, 0, 0, FOREVER).chain());
        // a box that covers only the top of the column: the ground block is outside the margin
        List<Box> top = List.of(new Box(0, 12, 0, 0, 12, 0));
        w.groundTop = 0;
        assertNull(ScaffoldPlanner.plan(w, top, 0, 5, 0, 6, 0, 0, 0, FOREVER).chain());
    }

    @Test
    void theShortestChainWinsAndAnEqualOneNearestThePlayerWins() {
        Fake w = new Fake();
        w.want(0, 0, 0, Role.SOLID, null);
        // one-block chains on the four sides, each touching the ground; the player stands off to one side
        Result r = ScaffoldPlanner.plan(w, AREA, 0, 0, 0, 6, 5, 0, 0, FOREVER);
        assertEquals(1, r.chain().size());
        assertEquals(1, r.chain().blocks().get(0)[0]);
        Result r2 = ScaffoldPlanner.plan(w, AREA, 0, 0, 0, 6, -5, 0, 0, FOREVER);
        assertEquals(-1, r2.chain().blocks().get(0)[0]);
    }

    @Test
    void anExpiredBudgetStopsTheSearchAndSaysSo() {
        Fake w = new Fake();
        w.want(0, 5, 0, Role.SOLID, null);
        Result r = ScaffoldPlanner.plan(w, AREA, 0, 5, 0, 6, 0, 0, 0, System.nanoTime() - 1);
        assertTrue(r.budgetHit());
        assertNull(r.chain());
    }

    @Test
    void theSearchIsCheap() {
        Fake w = new Fake();
        w.want(0, 5, 0, Role.SOLID, null);
        w.groundTop = -3; // no chain exists: the whole ball of radius 6 is searched
        long t = System.nanoTime();
        for (int i = 0; i < 50; i++) assertNull(plan(w, 0, 5, 0, 6).chain());
        assertTrue((System.nanoTime() - t) / 50 < 20_000_000L, "one failed search under 20 ms");
    }

    // Removal

    private static List<int[]> column(int x, int z, int from, int to) {
        List<int[]> l = new ArrayList<>();
        for (int y = from; y <= to; y++) l.add(new int[] { x, y, z });
        return l;
    }

    private static Fake withColumn(Fake w, List<int[]> col) {
        for (int[] b : col) w.solid.add(Fake.k(b[0], b[1], b[2]));
        return w;
    }

    @Test
    void scaffoldIsKeptWhileTheCellItHelpsIsStillOpen() {
        Fake w = new Fake();
        w.want(0, 5, 0, Role.SOLID, null);
        List<int[]> col = column(0, 0, 0, 4);
        withColumn(w, col);
        assertTrue(ScaffoldPlanner.removable(w, col, false).isEmpty());
    }

    @Test
    void scaffoldGoesOnceTheCellIsBuilt() {
        Fake w = new Fake();
        List<int[]> col = column(0, 0, 0, 4);
        withColumn(w, col);
        w.solid.add(Fake.k(0, 5, 0)); // built, not open any more
        assertEquals(5, ScaffoldPlanner.removable(w, col, false).size());
    }

    @Test
    void scaffoldNextToACellWithOtherSupportIsNotNeeded() {
        Fake w = new Fake();
        w.want(1, 0, 0, Role.SOLID, null); // on the ground: natural support below
        List<int[]> col = column(0, 0, 0, 0);
        withColumn(w, col);
        assertEquals(1, ScaffoldPlanner.removable(w, col, false).size());
    }

    @Test
    void aCellSupportedOnlyByScaffoldKeepsAllOfTheScaffoldAroundIt() {
        Fake w = new Fake();
        w.want(0, 5, 0, Role.SOLID, null);
        List<int[]> two = new ArrayList<>(column(0, 0, 0, 4));
        two.add(new int[] { 1, 5, 0 }); // a second scaffold block beside the cell, held up by nothing (planned)
        withColumn(w, two);
        assertTrue(ScaffoldPlanner.removable(w, two, false).isEmpty());
    }

    @Test
    void aHangingCellKeepsTheBlockItHangsOn() {
        Fake w = new Fake();
        w.want(0, 3, 0, Role.ATTACHABLE, Dir.UP); // hangs under (0,4,0)
        List<int[]> col = column(0, 0, 4, 4);
        withColumn(w, col);
        assertTrue(ScaffoldPlanner.removable(w, col, false).isEmpty());
    }

    @Test
    void unplacedAndUnneededBlocksAreRemovableToo() {
        Fake w = new Fake();
        // plans for a cell that was built some other way: nothing is open
        List<int[]> col = column(2, 2, 0, 3);
        assertEquals(4, ScaffoldPlanner.removable(w, col, false).size());
    }

    @Test
    void withAirPlaceNothingIsNeeded() {
        Fake w = new Fake();
        w.want(0, 5, 0, Role.SOLID, null);
        List<int[]> col = column(0, 0, 0, 4);
        withColumn(w, col);
        assertEquals(5, ScaffoldPlanner.removable(w, col, true).size());
    }

    @Test
    void onlyASolidCellIsHelped() {
        assertTrue(ScaffoldPlanner.helps(new Cell(0, 0, 0, Role.SOLID, null, null, null)));
        assertFalse(ScaffoldPlanner.helps(new Cell(0, 0, 0, Role.ATTACHABLE, Dir.UP, null, null)));
        assertFalse(ScaffoldPlanner.helps(new Cell(0, 0, 0, Role.GRAVITY, null, null, null)));
    }

    @Test
    void theSearchBudgetGrowsWithRepeatedHitsUpToTwoMilliseconds() {
        assertEquals(400_000L, ScaffoldPlanner.budgetNanos(0));
        assertEquals(800_000L, ScaffoldPlanner.budgetNanos(1));
        assertEquals(1_600_000L, ScaffoldPlanner.budgetNanos(2));
        assertEquals(2_000_000L, ScaffoldPlanner.budgetNanos(3));
        assertEquals(2_000_000L, ScaffoldPlanner.budgetNanos(50));
        assertEquals(400_000L, ScaffoldPlanner.budgetNanos(-1));
    }
}
