package com.porter.qwerte.manager.map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The game's own map colours: needs only MapColor, which does not need a running game. */
class MapPaletteTest {
    @Test
    void ids0To3AreTransparentAndTheRestOpaque() {
        int[] p = MapPalette.argb();
        assertEquals(256, p.length);
        for (int i = 0; i < 4; i++) assertEquals(0, p[i]);
        for (int i = 4; i < 248; i++) assertEquals(0xFF, p[i] >>> 24, "entry " + i);
    }

    @Test
    void grassHasTheVanillaColours() {
        int[] p = MapPalette.argb();
        // colour id 1 (grass, 0x7FB238): brightness 2 is 255/255, 1 is 220/255, 0 is 180/255, 3 is 135/255
        assertEquals(0xFF7FB238, p[1 * 4 + 2]);
        assertEquals(0xFF000000 | ((127 * 220 / 255) << 16) | ((178 * 220 / 255) << 8) | (56 * 220 / 255), p[1 * 4 + 1]);
        assertTrue((p[1 * 4 + 3] & 0xff) < (p[1 * 4 + 0] & 0xff));
    }
}
