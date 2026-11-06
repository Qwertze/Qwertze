package com.porter.qwerte.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PlaceSearchTest {
    /** A flat world: floor at y=63, air above, optional walls and guards. */
    private static class Flat implements PlaceSearch.View {
        final Set<PlaceSearch.Pos> solid = new HashSet<>();
        final Set<PlaceSearch.Pos> guarded = new HashSet<>();
        int areaMinX = -100, areaMaxX = 100, areaMinZ = -100, areaMaxZ = 100;

        @Override public boolean open(int x, int y, int z) { return y > 63 && !solid.contains(new PlaceSearch.Pos(x, y, z)); }
        @Override public boolean floor(int x, int y, int z) { return y <= 63 || solid.contains(new PlaceSearch.Pos(x, y, z)); }
        @Override public boolean standable(int x, int y, int z) { return open(x, y, z) && open(x, y + 1, z) && floor(x, y - 1, z); }
        @Override public String unsafe(PlaceSearch.Pos spot) { return null; }
        @Override public boolean inArea(PlaceSearch.Pos p) { return p.x() >= areaMinX && p.x() <= areaMaxX && p.z() >= areaMinZ && p.z() <= areaMaxZ; }
        @Override public boolean guarded(PlaceSearch.Pos spot) { return guarded.contains(spot); }
    }

    private static final PlaceSearch.Pos FEET = new PlaceSearch.Pos(0, 64, 0);

    @Test
    void openFloorGivesAnAdjacentSpotWithoutWalking() {
        PlaceSearch.Result r = PlaceSearch.search(FEET, new Flat(), Set.of());
        assertNotNull(r.found());
        assertEquals(0, r.found().steps());
        assertEquals(FEET, r.found().stand());
    }

    @Test
    void walksToTheNearestReachableSpot() {
        Flat w = new Flat();
        for (int[] d : new int[][] { { 0, -1 }, { 1, 0 }, { 0, 1 }, { -1, 0 } }) w.guarded.add(new PlaceSearch.Pos(d[0], 64, d[1]));
        PlaceSearch.Result r = PlaceSearch.search(FEET, w, Set.of());
        assertNotNull(r.found());
        assertTrue(r.found().steps() >= 1);
    }

    @Test
    void standCellMustBeInsideThePerimeter() {
        Flat w = new Flat();
        w.areaMinX = w.areaMaxX = w.areaMinZ = w.areaMaxZ = 0;
        // The perimeter is the bot's own column: every cell beside it, to stand in or to place at, is outside
        PlaceSearch.Result r = PlaceSearch.search(FEET, w, Set.of());
        assertNull(r.found());
        assertEquals(PlaceSearch.OUTSIDE, r.why());
    }

    @Test
    void walledInHasNothingReachable() {
        Flat w = new Flat();
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            if (dx == 0 && dz == 0) continue;
            for (int y = 64; y <= 66; y++) w.solid.add(new PlaceSearch.Pos(dx, y, dz));
        }
        // Nothing beside the bot is open and no walkable cell leads out
        PlaceSearch.Result r = PlaceSearch.search(FEET, w, Set.of());
        assertNull(r.found());
        assertEquals(PlaceSearch.UNREACHABLE, r.why());
    }

    @Test
    void skippedSpotsAreNotChosenAgain() {
        Flat w = new Flat();
        PlaceSearch.Result first = PlaceSearch.search(FEET, w, Set.of());
        PlaceSearch.Result second = PlaceSearch.search(FEET, w, Set.of(first.found().spot()));
        assertNotNull(second.found());
        assertTrue(!second.found().spot().equals(first.found().spot()));
    }
}
