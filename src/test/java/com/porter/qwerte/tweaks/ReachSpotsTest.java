package com.porter.qwerte.tweaks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.porter.qwerte.utils.PlaceSearch.Pos;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ReachSpotsTest {
    /** A flat world: stone at y=63 and below, air above, with sets to break individual rules. */
    private static class Flat implements ReachSpots.View {
        final Set<Pos> solid = new HashSet<>();
        final Set<Pos> noSupport = new HashSet<>();
        final Set<Pos> noRoom = new HashSet<>();
        final Set<Pos> entity = new HashSet<>();
        final Set<Pos> blocked = new HashSet<>();
        boolean supportEverywhere = true;

        @Override public boolean free(int x, int y, int z) { return y >= 64 && !solid.contains(new Pos(x, y, z)); }
        @Override public boolean support(int x, int y, int z) {
            if (noSupport.contains(new Pos(x, y, z))) return false;
            return supportEverywhere && (y <= 63 || solid.contains(new Pos(x, y, z)));
        }
        @Override public boolean headroom(int x, int y, int z) { return !noRoom.contains(new Pos(x, y, z)) && !solid.contains(new Pos(x, y, z)); }
        @Override public boolean inPlayer(int x, int y, int z) { return x == 0 && z == 0 && (y == 64 || y == 65); }
        @Override public boolean entityIn(int x, int y, int z) { return entity.contains(new Pos(x, y, z)); }
        @Override public boolean lineOfSight(int x, int y, int z, double hx, double hy, double hz) { return !blocked.contains(new Pos(x, y, z)); }
    }

    private static final Pos FEET = new Pos(0, 64, 0);
    private static final double EYE_X = 0.5, EYE_Y = 65.62, EYE_Z = 0.5;

    private static ReachSpots.Result find(Flat w, double reach) {
        return ReachSpots.find(EYE_X, EYE_Y, EYE_Z, FEET, reach, w);
    }

    @Test
    void picksAFeetLevelOrthogonalNeighbour() {
        ReachSpots.Result r = find(new Flat(), 4.5);
        assertNotNull(r.spot());
        assertNull(r.why());
        assertEquals(64, r.spot().y());
        assertEquals(1, Math.abs(r.spot().x()) + Math.abs(r.spot().z()));
    }

    @Test
    void orthogonalBeatsDiagonalAndNearerCells() {
        Flat w = new Flat();
        // Every orthogonal neighbour but +x is taken; +x is still chosen over the closer diagonals
        w.entity.add(new Pos(-1, 64, 0));
        w.entity.add(new Pos(0, 64, 1));
        w.entity.add(new Pos(0, 64, -1));
        assertEquals(new Pos(1, 64, 0), find(w, 4.5).spot());
    }

    @Test
    void tiesBreakByXThenYThenZ() {
        // Symmetric: -x, +x, -z, +z are all as good, so the lowest x wins, then the lowest z
        assertEquals(new Pos(-1, 64, 0), find(new Flat(), 4.5).spot());

        Flat w = new Flat();
        w.entity.add(new Pos(-1, 64, 0));
        w.entity.add(new Pos(1, 64, 0));
        assertEquals(new Pos(0, 64, -1), find(w, 4.5).spot());
    }

    @Test
    void theResultIsTheSameEveryTime() {
        Flat w = new Flat();
        assertEquals(find(w, 4.5), find(w, 4.5));
    }

    @Test
    void fallsBackToCellsFurtherOutWhenTheNeighboursAreTaken() {
        Flat w = new Flat();
        for (int[] d : new int[][] { { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 } }) w.entity.add(new Pos(d[0], 64, d[1]));
        // Two cells out in a straight line is nearer than a diagonal with its penalty
        assertEquals(new Pos(-2, 64, 0), find(w, 4.5).spot());
    }

    @Test
    void rejectsAClickableOrOtherwiseBadSupportButUsesTheNext() {
        Flat w = new Flat();
        w.noSupport.add(new Pos(-1, 63, 0));
        assertEquals(new Pos(0, 64, -1), find(w, 4.5).spot());
    }

    @Test
    void rejectsBlockedLidSpace() {
        Flat w = new Flat();
        for (int x = -6; x <= 6; x++) for (int z = -6; z <= 6; z++) w.noRoom.add(new Pos(x, 65, z));
        ReachSpots.Result r = find(w, 4.5);
        assertNull(r.spot());
        assertEquals(ReachSpots.NO_ROOM, r.why());
    }

    @Test
    void neverPicksTheCellsTheFeetAreIn() {
        Flat w = new Flat();
        w.entity.clear();
        ReachSpots.Result r = find(w, 4.5);
        assertNotNull(r.spot());
        assertEquals(false, r.spot().x() == 0 && r.spot().z() == 0 && r.spot().y() == 64);
    }

    @Test
    void rejectsAnEntityInEveryCell() {
        Flat w = new Flat() {
            @Override public boolean entityIn(int x, int y, int z) { return true; }
        };
        ReachSpots.Result r = find(w, 4.5);
        assertNull(r.spot());
        assertEquals(ReachSpots.ENTITY, r.why());
    }

    @Test
    void rejectsWhenNoCellIsFree() {
        Flat w = new Flat() {
            @Override public boolean free(int x, int y, int z) { return false; }
        };
        ReachSpots.Result r = find(w, 4.5);
        assertNull(r.spot());
        assertEquals(ReachSpots.NOT_FREE, r.why());
    }

    @Test
    void rejectsWhenOutOfReach() {
        ReachSpots.Result r = find(new Flat(), 0.2);
        assertNull(r.spot());
        assertEquals(ReachSpots.NO_CELL, r.why());
    }

    @Test
    void keepsAQuarterBlockOfReachInHand() {
        // The nearest free top face is 1.90 from the eyes
        assertNotNull(find(new Flat(), 2.2).spot());
        assertNull(find(new Flat(), 2.1).spot());
    }

    @Test
    void rejectsWithoutALineOfSight() {
        Flat w = new Flat() {
            @Override public boolean lineOfSight(int x, int y, int z, double hx, double hy, double hz) { return false; }
        };
        ReachSpots.Result r = find(w, 4.5);
        assertNull(r.spot());
        assertEquals(ReachSpots.NO_LINE, r.why());
    }

    @Test
    void takesTheOnlyCellInLineOfSight() {
        Flat w = new Flat() {
            @Override public boolean lineOfSight(int x, int y, int z, double hx, double hy, double hz) { return x == 2 && z == 1; }
        };
        assertEquals(new Pos(2, 64, 1), find(w, 4.5).spot());
    }

    @Test
    void aHigherPlatformIsFoundWhenItIsTheOnlyChoice() {
        Flat w = new Flat() {
            @Override public boolean free(int x, int y, int z) { return y == 65 && x == 1 && z == 0; }
            @Override public boolean support(int x, int y, int z) { return true; }
        };
        assertEquals(new Pos(1, 65, 0), find(w, 4.5).spot());
    }

    @Test
    void whyNamesTheFurthestCheckAnyCellPassed() {
        // Some cells have no support, the rest have an entity: the entity is the furthest anything got
        Flat w = new Flat() {
            @Override public boolean support(int x, int y, int z) { return x > 0 && super.support(x, y, z); }
            @Override public boolean entityIn(int x, int y, int z) { return true; }
        };
        assertEquals(ReachSpots.ENTITY, find(w, 4.5).why());
    }

    @Test
    void rejectsASpotWhoseTopFaceIsAboveTheEyes() {
        // Only cells at y=67 are free: their support top is at 67, the eyes are at 65.62
        Flat w = new Flat() {
            @Override public boolean free(int x, int y, int z) { return y == 67; }
            @Override public boolean support(int x, int y, int z) { return true; }
        };
        ReachSpots.Result r = find(w, 4.5);
        assertNull(r.spot());
        assertEquals(ReachSpots.ABOVE_EYES, r.why());
    }

    @Test
    void aTopFaceJustBelowTheEyesIsFine() {
        Flat w = new Flat() {
            @Override public boolean free(int x, int y, int z) { return y == 65 && x == 1 && z == 0; }
            @Override public boolean support(int x, int y, int z) { return true; }
        };
        assertEquals(new Pos(1, 65, 0), find(w, 4.5).spot());
    }
}
