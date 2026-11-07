package com.porter.qwerte.pearl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.porter.qwerte.pearl.ShaftScan.Kind;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ShaftScanTest {
    /** Ground up to y=63 (solid), a shaft at (0, 0) with water at 55..57 and trapdoors, rim ground flush at 63. */
    private static class W implements ShaftScan.World {
        final Map<List<Integer>, Kind> blocks = new HashMap<>();

        W() {
            for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++) for (int y = 50; y <= 63; y++) {
                if (x == 0 && z == 0) continue;
                blocks.put(List.of(x, y, z), Kind.SOLID);
            }
            for (int y = 55; y <= 57; y++) blocks.put(List.of(0, y, 0), Kind.WATER);
        }

        void set(int x, int y, int z, Kind k) {
            blocks.put(List.of(x, y, z), k);
        }

        @Override public Kind kind(int x, int y, int z) {
            Kind k = blocks.get(List.of(x, y, z));
            return k != null ? k : Kind.AIR;
        }
    }

    @Test
    void oneTrapdoorAtTheRim() {
        W w = new W();
        w.set(0, 63, 0, Kind.TRAPDOOR_CLOSED);
        ShaftScan.Result r = ShaftScan.scan(w, 0, 56, 0, 10, 6);
        assertNull(r.error());
        assertEquals(57, r.shaft().waterTop());
        assertEquals(List.of(63), r.shaft().trapYs());
        assertEquals(63, r.shaft().topTrap());
        // Flush ground: feet at 64 on all four sides
        assertEquals(4, r.shaft().rims().stream().filter(rim -> rim.floorY() == 64).count());
        assertEquals(64, r.shaft().rims().get(0).floorY());
    }

    @Test
    void twoTrapdoorsAreBothFound() {
        W w = new W();
        w.set(0, 61, 0, Kind.TRAPDOOR_CLOSED);
        w.set(0, 63, 0, Kind.TRAPDOOR_OPEN);
        ShaftScan.Result r = ShaftScan.scan(w, 0, 57, 0, 10, 6);
        assertEquals(List.of(61, 63), r.shaft().trapYs());
    }

    @Test
    void startingAboveOrBelowTheWaterFindsIt() {
        W w = new W();
        w.set(0, 63, 0, Kind.TRAPDOOR_CLOSED);
        assertNull(ShaftScan.scan(w, 0, 60, 0, 10, 6).error());
        assertNull(ShaftScan.scan(w, 0, 52, 0, 10, 6).error());
    }

    @Test
    void waterTopIsTheTopOfTheRun() {
        W w = new W();
        w.set(0, 58, 0, Kind.WATER);
        w.set(0, 63, 0, Kind.TRAPDOOR_CLOSED);
        assertEquals(58, ShaftScan.scan(w, 0, 55, 0, 10, 6).shaft().waterTop());
    }

    @Test
    void noWaterIsAnError() {
        W w = new W();
        for (int y = 55; y <= 57; y++) w.set(0, y, 0, Kind.AIR);
        w.set(0, 63, 0, Kind.TRAPDOOR_CLOSED);
        assertNotNull(ShaftScan.scan(w, 0, 56, 0, 10, 6).error());
    }

    @Test
    void noTrapdoorIsAnError() {
        assertEquals("no trapdoor above the water", ShaftScan.scan(new W(), 0, 56, 0, 10, 6).error());
    }

    @Test
    void solidBlockAboveTheWaterBlocks() {
        W w = new W();
        w.set(0, 59, 0, Kind.SOLID);
        w.set(0, 63, 0, Kind.TRAPDOOR_CLOSED);
        assertEquals("the shaft is blocked above the water", ShaftScan.scan(w, 0, 56, 0, 10, 6).error());
    }

    @Test
    void noRimIsAnError() {
        W w = new W();
        w.set(0, 63, 0, Kind.TRAPDOOR_CLOSED);
        // A pit around the shaft: no floor next to it
        for (int[] d : new int[][] { { 0, 1 }, { 1, 0 }, { 0, -1 }, { -1, 0 } }) for (int y = 50; y <= 63; y++) w.set(d[0], y, d[1], Kind.AIR);
        assertEquals("no standing spot beside the top of the shaft", ShaftScan.scan(w, 0, 56, 0, 10, 6).error());
    }

    @Test
    void lowerGroundGivesTheLowerRim() {
        W w = new W();
        w.set(0, 63, 0, Kind.TRAPDOOR_CLOSED);
        // Ground one lower on the east side only; the other sides are walled off above the flush level
        for (int[] d : new int[][] { { 0, 1 }, { -1, 0 }, { 0, -1 } }) w.set(d[0], 64, d[1], Kind.SOLID);
        w.set(1, 63, 0, Kind.AIR);
        ShaftScan.Result r = ShaftScan.scan(w, 0, 56, 0, 10, 6);
        // The flush level has none, so the first choice is the lower rim
        assertEquals(new ShaftScan.Rim(1, 0, 63), r.shaft().rims().get(0));
    }
}
