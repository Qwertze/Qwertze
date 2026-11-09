package com.porter.qwerte.printer.schematic;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

class LitematicFormatTest {
    @Test
    void bitsGrowWithThePaletteAndNeverDropBelowTwo() {
        assertEquals(2, LitematicFormat.bitsFor(1));
        assertEquals(2, LitematicFormat.bitsFor(2));
        assertEquals(2, LitematicFormat.bitsFor(4));
        assertEquals(3, LitematicFormat.bitsFor(5));
        assertEquals(5, LitematicFormat.bitsFor(17));
        assertEquals(13, LitematicFormat.bitsFor(5000));
        assertEquals(16, LitematicFormat.bitsFor(65536));
    }

    @Test
    void anEntryThatSpansTwoLongsIsJoinedFromBoth() {
        // entry 12 of 5 bits sits at bits 60..64: low four bits (0101) in word 0, the top bit in word 1
        long[] data = { 5L << 60, 1L };
        assertEquals(0b10101, LitematicFormat.get(data, 5, 12));
        assertEquals(0, LitematicFormat.get(data, 5, 11));
    }

    @Test
    void unpackingRoundTripsAcrossLongBoundariesForTwoFiveAndThirteenBits() {
        for (int bits : new int[] { 2, 5, 13 }) {
            Random rnd = new Random(bits);
            int[] entries = new int[301];
            for (int i = 0; i < entries.length; i++) entries[i] = rnd.nextInt(1 << bits);
            long[] packed = LitematicFormat.pack(entries, bits);
            assertEquals(LitematicFormat.expectedLength(entries.length, bits), packed.length);
            int[] back = new int[entries.length];
            for (int i = 0; i < back.length; i++) back[i] = LitematicFormat.get(packed, bits, i);
            assertArrayEquals(entries, back, "bits " + bits);
        }
    }

    @Test
    void theLastEntryIsReadWhenTheArrayEndsExactlyAtItsLastBit() {
        int[] entries = new int[64]; // 64 * 5 bits = 320 = exactly 5 longs
        entries[63] = 31;
        long[] packed = LitematicFormat.pack(entries, 5);
        assertEquals(5, packed.length);
        assertEquals(31, LitematicFormat.get(packed, 5, 63));
    }

    @Test
    void expectedLengthRoundsUp() {
        assertEquals(1, LitematicFormat.expectedLength(1, 2));
        assertEquals(1, LitematicFormat.expectedLength(32, 2));
        assertEquals(2, LitematicFormat.expectedLength(33, 2));
        assertEquals(5, LitematicFormat.expectedLength(61, 5));
    }

    @Test
    void cellsAreOrderedXThenZThenY() {
        assertEquals(1, LitematicFormat.index(1, 0, 0, 3, 4));
        assertEquals(3, LitematicFormat.index(0, 0, 1, 3, 4));
        assertEquals(12, LitematicFormat.index(0, 1, 0, 3, 4));
        assertEquals(3 * 4 * 2 - 1, LitematicFormat.index(2, 1, 3, 3, 4));
    }

    @Test
    void aNegativeSizeExtendsBackwardsFromThePosition() {
        assertEquals(5, LitematicFormat.minCorner(5, 3));
        assertEquals(7, LitematicFormat.relEnd(8));
        assertEquals(-2, LitematicFormat.relEnd(-3));
        assertEquals(3, LitematicFormat.minCorner(5, -3));
        assertEquals(-4, LitematicFormat.minCorner(-4, 1));
    }

    @Test
    void stateKeysSortTheirPropertiesAndTakeApart() {
        String key = LitematicFormat.stateKey("minecraft:oak_stairs", Map.of("half", "bottom", "facing", "north"));
        assertEquals("minecraft:oak_stairs[facing=north,half=bottom]", key);
        assertEquals("minecraft:oak_stairs", LitematicFormat.keyName(key));
        assertArrayEquals(new String[] { "half", "bottom" }, LitematicFormat.keyProperties(key)[1]);
        assertEquals("minecraft:stone", LitematicFormat.stateKey("minecraft:stone", Map.of()));
        assertEquals(0, LitematicFormat.keyProperties("minecraft:stone").length);
    }
}
