package com.porter.qwerte.pearl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.porter.qwerte.pearl.ShaftScan.Kind;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SaveScanTest {
    /** Ground up to y=63, a shaft at (0, 0) with water 55..57 and a closed trapdoor flush at y=63. */
    private static class W implements ShaftScan.World {
        final Map<List<Integer>, Kind> blocks = new HashMap<>();

        W() {
            for (int x = -4; x <= 4; x++) for (int z = -4; z <= 4; z++) for (int y = 40; y <= 63; y++) {
                if (x == 0 && z == 0) continue;
                blocks.put(List.of(x, y, z), Kind.SOLID);
            }
            for (int y = 55; y <= 57; y++) blocks.put(List.of(0, y, 0), Kind.WATER);
            blocks.put(List.of(0, 63, 0), Kind.TRAPDOOR_CLOSED);
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
    void onTheCoverTrapdoor() {
        // A closed trapdoor is the block the feet are in
        ShaftScan.SaveResult r = ShaftScan.saveScan(new W(), 0, 63, 0, 24);
        assertNull(r.error());
        assertEquals(0, r.found().x());
        assertEquals(0, r.found().z());
        assertEquals(57, r.found().waterTop());
        assertEquals(List.of(63), r.found().trapYs());
        assertEquals(6, r.found().depth());
        assertFalse(r.log().isEmpty());
    }

    @Test
    void onTheCoverWithTwoTrapdoors() {
        W w = new W();
        w.set(0, 62, 0, Kind.TRAPDOOR_OPEN);
        ShaftScan.SaveResult r = ShaftScan.saveScan(w, 0, 63, 0, 24);
        assertNull(r.error());
        assertEquals(List.of(62, 63), r.found().trapYs());
    }

    @Test
    void onTheRimWithOneValidNeighbour() {
        ShaftScan.SaveResult r = ShaftScan.saveScan(new W(), 1, 64, 0, 24);
        assertNull(r.error());
        assertEquals(0, r.found().x());
        assertEquals(0, r.found().z());
        assertEquals(57, r.found().waterTop());
        assertEquals(7, r.found().depth());
    }

    @Test
    void onTheRimBetweenTwoShaftsIsRefused() {
        W w = new W();
        w.set(2, 63, 0, Kind.TRAPDOOR_CLOSED);
        for (int y = 55; y <= 57; y++) w.set(2, y, 0, Kind.WATER);
        for (int y = 58; y <= 62; y++) w.set(2, y, 0, Kind.AIR);
        for (int y = 58; y <= 62; y++) w.set(0, y, 0, Kind.AIR);
        ShaftScan.SaveResult r = ShaftScan.saveScan(w, 1, 64, 0, 24);
        assertNull(r.found());
        assertNotNull(r.error());
        assertTrue(r.error().contains("more than one"));
    }

    @Test
    void noWaterIsRefused() {
        W w = new W();
        for (int y = 55; y <= 57; y++) w.set(0, y, 0, Kind.AIR);
        for (int y = 40; y <= 54; y++) w.set(0, y, 0, Kind.AIR);
        ShaftScan.SaveResult r = ShaftScan.saveScan(w, 0, 63, 0, 24);
        assertNull(r.found());
        assertNotNull(r.error());
        r = ShaftScan.saveScan(w, 1, 64, 0, 24);
        assertNull(r.found());
    }

    @Test
    void waterBeyondTheDepthLimitIsRefused() {
        assertNull(ShaftScan.saveScan(new W(), 0, 63, 0, 5).found());
    }

    @Test
    void insideTheWater() {
        ShaftScan.SaveResult r = ShaftScan.saveScan(new W(), 0, 56, 0, 24);
        assertNull(r.error());
        assertEquals(57, r.found().waterTop());
        assertEquals(List.of(63), r.found().trapYs());
        assertEquals(-1, r.found().depth());
    }

    @Test
    void waterWithoutATrapdoorIsRefused() {
        W w = new W();
        w.set(0, 63, 0, Kind.AIR);
        assertNull(ShaftScan.saveScan(w, 0, 63, 0, 24).found());
    }

    @Test
    void oldChamberStringsStillLoad() {
        Chamber c = Chamber.decode("play.example.net|ResourceKey[minecraft:dimension / minecraft:overworld]|10|60|-20|Old one");
        assertNotNull(c);
        assertEquals("Old one", c.name());
        assertFalse(c.hasShaft());
        assertEquals(10, c.x());
        assertEquals("play.example.net|ResourceKey[minecraft:dimension / minecraft:overworld]|10|60|-20|Old one", c.encode());
    }

    @Test
    void shaftDataRoundTripsAndDamagedDataIsDropped() {
        Chamber c = new Chamber("srv", "dim", 1, 57, 2, "New", 57, List.of(62, 63));
        Chamber back = Chamber.decode(c.encode());
        assertEquals(c, back);
        assertEquals(List.of(62, 63), back.trapYs());
        Chamber damaged = Chamber.decode("srv|dim|1|57|2|New|@shaft=garbage");
        assertNotNull(damaged);
        assertFalse(damaged.hasShaft());
        assertEquals("New", damaged.name());
        Chamber noTraps = Chamber.decode("srv|dim|1|57|2|New|@shaft=57;");
        assertTrue(noTraps.hasShaft());
        assertTrue(noTraps.trapYs().isEmpty());
        Chamber piped = new Chamber("srv", "dim", 1, 57, 2, "a|b", 57, List.of(63));
        assertEquals(piped, Chamber.decode(piped.encode()));
    }
}
