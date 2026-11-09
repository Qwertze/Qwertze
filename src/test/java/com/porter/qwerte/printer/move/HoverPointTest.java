package com.porter.qwerte.printer.move;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.porter.qwerte.printer.move.HoverPoint.Cells;
import com.porter.qwerte.printer.move.HoverPoint.Point;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class HoverPointTest {
    private static final double REACH = 3.75;

    private static Cells none() {
        return (x, y, z) -> false;
    }

    private static Cells set(Set<String> cells) {
        return (x, y, z) -> cells.contains(x + "," + y + "," + z);
    }

    private static List<int[]> one(int x, int y, int z) {
        List<int[]> l = new ArrayList<>();
        l.add(new int[] { x, y, z });
        return l;
    }

    @Test
    void theEyesAreWithinReachOfTheTarget() {
        List<int[]> t = one(10, 60, 10);
        Point p = HoverPoint.choose(t, 0, 60, 0, REACH, none(), none());
        assertNotNull(p);
        assertTrue(HoverPoint.within(p.x(), p.y(), p.z(), t.get(0), REACH));
    }

    @Test
    void itIsAboveOrBesideNeverInTheTargetCell() {
        // The target cell itself is blocked (still to be built), as is everything under it
        Set<String> blocked = new HashSet<>();
        for (int y = 0; y <= 60; y++) for (int x = 8; x <= 12; x++) for (int z = 8; z <= 12; z++) blocked.add(x + "," + y + "," + z);
        Point p = HoverPoint.choose(one(10, 60, 10), 10.5, 90, 10.5, REACH, set(blocked), none());
        assertNotNull(p);
        assertFalse(blocked.contains(p.x() + "," + p.y() + "," + p.z()));
        assertFalse(blocked.contains(p.x() + "," + (p.y() + 1) + "," + p.z()));
        assertTrue(p.y() >= 61, "above the layer: " + p);
    }

    @Test
    void headroomIsNeeded() {
        // The cell above the best spot is solid: that spot is passed over
        Set<String> blocked = new HashSet<>();
        for (int x = 5; x <= 15; x++) for (int z = 5; z <= 15; z++) blocked.add(x + "," + 62 + "," + z);
        Point p = HoverPoint.choose(one(10, 60, 10), 10.5, 70, 10.5, REACH, set(blocked), none());
        assertNotNull(p);
        assertFalse(blocked.contains(p.x() + "," + p.y() + "," + p.z()));
        assertFalse(blocked.contains(p.x() + "," + (p.y() + 1) + "," + p.z()));
    }

    @Test
    void nothingFreeGivesNull() {
        assertNull(HoverPoint.choose(one(10, 60, 10), 0, 60, 0, REACH, (x, y, z) -> true, none()));
        assertNull(HoverPoint.choose(List.of(), 0, 60, 0, REACH, none(), none()));
    }

    @Test
    void theNearestPointToThePlayerWins() {
        Point west = HoverPoint.choose(one(10, 60, 10), -50, 62, 10.5, REACH, none(), none());
        Point east = HoverPoint.choose(one(10, 60, 10), 70, 62, 10.5, REACH, none(), none());
        assertNotNull(west);
        assertNotNull(east);
        assertTrue(west.x() < 10);
        assertTrue(east.x() > 10);
    }

    @Test
    void discouragedCellsAreAvoidedWhenAFreeOneIsNear() {
        // The player is above the target; everything straight above is discouraged (future layers)
        Set<String> disc = new HashSet<>();
        for (int y = 61; y <= 80; y++) for (int x = 8; x <= 12; x++) for (int z = 8; z <= 12; z++) disc.add(x + "," + y + "," + z);
        Point p = HoverPoint.choose(one(10, 60, 10), 10.5, 64, 10.5, REACH, none(), set(disc));
        assertNotNull(p);
        assertFalse(disc.contains(p.x() + "," + p.y() + "," + p.z()), "feet not in the schematic: " + p);
    }

    @Test
    void discouragedIsAllowedWhenItIsTheOnlyWay() {
        Point p = HoverPoint.choose(one(10, 60, 10), 10.5, 64, 10.5, REACH, none(), (x, y, z) -> true);
        assertNotNull(p);
    }

    @Test
    void fallsBackToLevelWithTheTargetWhenAboveIsBlocked() {
        // Everything above the target layer is solid (a roof), the player is inside the room beside it
        Set<String> blocked = new HashSet<>();
        for (int y = 61; y <= 70; y++) for (int x = -20; x <= 40; x++) for (int z = -20; z <= 40; z++) blocked.add(x + "," + y + "," + z);
        Point p = HoverPoint.choose(one(10, 60, 10), 5.5, 60, 5.5, REACH, set(blocked), none());
        assertNotNull(p);
        assertTrue(p.y() <= 60);
        assertTrue(HoverPoint.within(p.x(), p.y(), p.z(), new int[] { 10, 60, 10 }, REACH));
    }

    @Test
    void aSpotThatReachesMoreTargetsIsPreferred() {
        // Two targets 6 apart: the point between them reaches both
        List<int[]> t = new ArrayList<>();
        t.add(new int[] { 10, 60, 10 });
        t.add(new int[] { 14, 60, 10 });
        Point p = HoverPoint.choose(t, 12.5, 62, 10.5, REACH, none(), none());
        assertNotNull(p);
        assertTrue(HoverPoint.within(p.x(), p.y(), p.z(), t.get(0), REACH));
        assertTrue(HoverPoint.within(p.x(), p.y(), p.z(), t.get(1), REACH));
    }

    @Test
    void theFirstTargetWithAPointIsUsed() {
        // The first target is walled in completely; the second is free
        Set<String> blocked = new HashSet<>();
        for (int y = 50; y <= 70; y++) for (int x = 0; x <= 20; x++) for (int z = 0; z <= 20; z++) blocked.add(x + "," + y + "," + z);
        List<int[]> t = new ArrayList<>();
        t.add(new int[] { 10, 60, 10 });
        t.add(new int[] { 60, 60, 60 });
        Point p = HoverPoint.choose(t, 50, 60, 50, REACH, set(blocked), none());
        assertNotNull(p);
        assertTrue(HoverPoint.within(p.x(), p.y(), p.z(), t.get(1), REACH));
        assertEquals(false, blocked.contains(p.x() + "," + p.y() + "," + p.z()));
    }
}
