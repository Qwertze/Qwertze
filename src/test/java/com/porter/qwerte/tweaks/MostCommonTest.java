package com.porter.qwerte.tweaks;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class MostCommonTest {
    private static int pick(String[] keys, int... counts) {
        return MostCommon.pick(keys, counts);
    }

    @Test
    void emptyInputGivesNothing() {
        assertEquals(-1, pick(new String[0]));
        assertEquals(-1, pick(new String[] { null, null }, 0, 0));
    }

    @Test
    void zeroCountsAreEmpty() {
        assertEquals(-1, pick(new String[] { "a", "b" }, 0, 0));
        assertEquals(1, pick(new String[] { "a", "b" }, 0, 1));
    }

    @Test
    void oneItemIsItsOwnWinner() {
        assertEquals(3, pick(new String[] { null, null, null, "dirt" }, 0, 0, 0, 5));
    }

    @Test
    void totalsAreSummedAcrossStacks() {
        // dirt 40 + 40 beats a single stack of 64 cobble
        assertEquals(0, pick(new String[] { "dirt", "cobble", "dirt" }, 40, 64, 40));
    }

    @Test
    void theRepresentativeIsTheFirstStackOfTheWinner() {
        assertEquals(1, pick(new String[] { "gold", "dirt", "gold", "dirt", "dirt" }, 10, 30, 10, 30, 5));
    }

    @Test
    void tieOnTotalGoesToMoreStacks() {
        // a: 64 in one stack; b: 32 + 32
        assertEquals(1, pick(new String[] { "a", "b", "b" }, 64, 32, 32));
    }

    @Test
    void tieOnTotalAndStacksGoesToTheLowestFirstSlot() {
        assertEquals(0, pick(new String[] { "a", "b" }, 10, 10));
        assertEquals(1, pick(new String[] { null, "b", "a" }, 0, 10, 10));
        assertEquals(2, pick(new String[] { "x", "y", "a", "b", "a", "b" }, 0, 0, 5, 5, 5, 5));
    }

    @Test
    void twentySevenDistinctItemsPickTheBiggest() {
        String[] keys = new String[27];
        int[] counts = new int[27];
        for (int i = 0; i < 27; i++) {
            keys[i] = "item" + i;
            counts[i] = i + 1;
        }
        assertEquals(26, MostCommon.pick(keys, counts));
    }

    @Test
    void twentySevenEqualItemsPickTheFirst() {
        String[] keys = new String[27];
        int[] counts = new int[27];
        for (int i = 0; i < 27; i++) {
            keys[i] = "item" + i;
            counts[i] = 1;
        }
        assertEquals(0, MostCommon.pick(keys, counts));
    }
}
