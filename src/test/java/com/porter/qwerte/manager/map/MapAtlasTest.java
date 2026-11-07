package com.porter.qwerte.manager.map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MapAtlasTest {
    /** Index 4+ is opaque; index n is 0xFF000000 | (n, n*2, n*3). */
    static int[] palette() {
        int[] p = new int[256];
        for (int i = 4; i < 256; i++) p[i] = 0xFF000000 | (i << 16) | (((i * 2) & 0xff) << 8) | ((i * 3) & 0xff);
        return p;
    }

    static byte[] fill(int v) {
        byte[] b = new byte[256];
        java.util.Arrays.fill(b, (byte) v);
        return b;
    }

    static int px(MapAtlas a, int x, int y, int channel) {
        return a.rgba()[(y * MapAtlas.SIZE + x) * 4 + channel] & 0xff;
    }

    @Test
    void paintsArgbAsRgbaAtTheRightPixels() {
        MapAtlas a = new MapAtlas(palette());
        assertTrue(a.recentre(0, 0)); // origin chunk -16,-16
        byte[] t = fill(10);
        t[3 * 16 + 5] = 20; // lz 3, lx 5
        a.put(-16, -16, t);
        assertEquals(10, px(a, 0, 0, 0));
        assertEquals(20, px(a, 0, 0, 1));
        assertEquals(30, px(a, 0, 0, 2));
        assertEquals(255, px(a, 0, 0, 3));
        assertEquals(20, px(a, 5, 3, 0));
        assertEquals(0, px(a, 16, 0, 3));
        a.put(-15, -15, fill(11));
        assertEquals(11, px(a, 16, 16, 0));
    }

    @Test
    void transparentBytesStayTransparent() {
        MapAtlas a = new MapAtlas(palette());
        a.recentre(0, 0);
        a.put(-16, -16, fill(0));
        assertEquals(0, px(a, 3, 3, 3));
        a.put(-16, -16, fill(2));
        assertEquals(0, px(a, 3, 3, 3));
    }

    @Test
    void dropAndClearErase() {
        MapAtlas a = new MapAtlas(palette());
        a.recentre(0, 0);
        a.put(-16, -16, fill(10));
        assertTrue(a.takeDirty());
        assertFalse(a.takeDirty());
        a.drop(-16, -16);
        assertEquals(0, px(a, 1, 1, 3));
        assertTrue(a.isDirty());
        a.put(-16, -16, fill(10));
        a.clear();
        assertEquals(0, px(a, 1, 1, 3));
        assertEquals(0, a.tileCount());
        assertFalse(a.placed());
    }

    @Test
    void tilesBeforeTheWindowIsPlacedAppearOnRecentre() {
        MapAtlas a = new MapAtlas(palette());
        a.put(100, 100, fill(10));
        a.recentre(100, 100); // origin 84,84
        assertEquals(10, px(a, 16 * 16, 16 * 16, 0));
    }

    @Test
    void theWindowMovesOnlyNearItsEdgeAndRepaints() {
        MapAtlas a = new MapAtlas(palette());
        a.recentre(0, 0);
        a.put(0, 0, fill(10));
        assertFalse(a.recentre(5, -5));
        assertEquals(-16, a.originX());
        assertTrue(a.recentre(13, 0)); // 13 - (-16) = 29 >= 28
        assertEquals(-3, a.originX());
        assertEquals(10, px(a, 3 * 16, 16 * 16, 0));
        a.put(-16, 0, fill(11));
        assertTrue(a.has(-16, 0));
    }

    @Test
    void negativeChunkCoordinatesDoNotCollide() {
        MapAtlas a = new MapAtlas(palette());
        a.put(-1, 1, fill(5));
        a.put(1, -1, fill(6));
        a.put(-1, -1, fill(7));
        assertEquals(3, a.tileCount());
        assertTrue(a.has(-1, 1) && a.has(1, -1) && a.has(-1, -1));
    }

    @Test
    void trimKeepsTheNearestAndCapsTheCache() {
        MapAtlas a = new MapAtlas(palette());
        a.recentre(0, 0);
        for (int i = 0; i < MapAtlas.CAP + 200; i++) a.put(i % 100, i / 100, fill(5));
        a.trim();
        assertEquals(MapAtlas.CAP, a.tileCount());
        assertTrue(a.has(0, 0));
        assertFalse(a.has(99, 16));
    }
}
