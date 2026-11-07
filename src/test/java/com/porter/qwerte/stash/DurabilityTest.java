package com.porter.qwerte.stash;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/** The index's durability fields: damage and maxDamage, only for a damaged, damageable item (same rule as the plugin's). */
class DurabilityTest {
    @Test
    void bothOrNeither() {
        assertArrayEquals(new int[] {120, 1561}, StashIndex.durability(120, 1561));
        assertArrayEquals(new int[] {0, 0}, StashIndex.durability(0, 1561));
        assertArrayEquals(new int[] {0, 0}, StashIndex.durability(5, 0));
        assertArrayEquals(new int[] {0, 0}, StashIndex.durability(0, 0));
    }
}
