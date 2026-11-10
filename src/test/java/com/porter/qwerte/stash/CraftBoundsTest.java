package com.porter.qwerte.stash;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Where the website's crafting table may be (ported from the plugin's CraftFixesTest), with the addon's own dimension strings. */
class CraftBoundsTest {
    private static final CraftSettings.Table T = new CraftSettings.Table(10, 64, 10, "minecraft:overworld");
    private static final int[] AREA = {0, 60, 0, 20, 70, 20};

    @Test
    void aTableIsOutOfBoundsWithoutAnAreaOrADimensionOrOutsideThem() {
        assertFalse(CraftBounds.outOfBounds("minecraft:overworld", AREA, T, 64));
        assertTrue(CraftBounds.outOfBounds("minecraft:overworld", null, T, 64), "no area: no limit, so refused");
        assertTrue(CraftBounds.outOfBounds(null, AREA, T, 64), "no recorded dimension");
        assertTrue(CraftBounds.outOfBounds("", AREA, T, 64), "a blank dimension is none");
        assertTrue(CraftBounds.outOfBounds("minecraft:the_nether", AREA, T, 64));
        assertTrue(CraftBounds.outOfBounds("minecraft:overworld", AREA, new CraftSettings.Table(200, 64, 10, "minecraft:overworld"), 64));
        assertFalse(CraftBounds.outOfBounds("minecraft:overworld", AREA, new CraftSettings.Table(84, 64, 10, "minecraft:overworld"), 64));
        assertTrue(CraftBounds.outOfBounds("minecraft:overworld", AREA, new CraftSettings.Table(85, 64, 10, "minecraft:overworld"), 64), "one past the margin");
        assertTrue(CraftBounds.outOfBounds("minecraft:overworld", new int[] {0, 0}, T, 64), "a broken area");
    }

    @Test
    void theHeightIsBetweenMinus64And320EvenInsideTheMargin() {
        int[] tall = {0, -64, 0, 20, 320, 20};
        assertFalse(CraftBounds.outOfBounds("minecraft:overworld", tall, new CraftSettings.Table(10, 320, 10, "minecraft:overworld")));
        assertTrue(CraftBounds.outOfBounds("minecraft:overworld", tall, new CraftSettings.Table(10, 321, 10, "minecraft:overworld")));
        assertTrue(CraftBounds.outOfBounds("minecraft:overworld", tall, new CraftSettings.Table(10, -65, 10, "minecraft:overworld")));
    }

    @Test
    void theContractsMarginIs64() {
        assertEquals(64, CraftBounds.MARGIN);
        assertFalse(CraftBounds.outOfBounds("minecraft:overworld", AREA, new CraftSettings.Table(84, 64, 10, "minecraft:overworld")));
        assertTrue(CraftBounds.outOfBounds("minecraft:overworld", AREA, new CraftSettings.Table(85, 64, 10, "minecraft:overworld")));
    }

    @Test
    void theAddonsOwnDimensionStringIsUnderstood() {
        String raw = "ResourceKey[minecraft:dimension / minecraft:overworld]";
        assertEquals("minecraft:overworld", CraftBounds.normalise(raw));
        assertEquals("minecraft:the_nether", CraftBounds.normalise("ResourceKey[minecraft:dimension / minecraft:the_nether]"));
        assertEquals("minecraft:overworld", CraftBounds.normalise("minecraft:overworld"));
        assertEquals("", CraftBounds.normalise(null));
        assertTrue(CraftBounds.sameDimension(raw, "minecraft:overworld"));
        assertTrue(CraftBounds.sameDimension("overworld", "minecraft:overworld"));
        assertFalse(CraftBounds.sameDimension(raw, "minecraft:the_end"));
        assertFalse(CraftBounds.sameDimension("", ""), "nothing is the same as nothing");
        assertFalse(CraftBounds.outOfBounds(raw, AREA, T));
    }

    @Test
    void aTableSeenStandingStaysOkWhenItsChunkUnloads() {
        assertEquals("unloaded", CraftBounds.unloadedStatus(false));
        assertEquals("ok", CraftBounds.unloadedStatus(true));
    }

    @Test
    void namespacesAreNotStrippedWhenDimensionsAreCompared() {
        assertFalse(CraftBounds.sameDimension("other:overworld", "minecraft:overworld"));
        assertFalse(CraftBounds.sameDimension("mod:the_end", "minecraft:the_end"));
        assertTrue(CraftBounds.sameDimension("mod:the_end", "MOD:THE_END"));
        assertEquals("minecraft:overworld", CraftBounds.fullId("overworld"));
        assertEquals("mod:x", CraftBounds.fullId("mod:x"));
        assertTrue(CraftBounds.outOfBounds("other:overworld", AREA, T));
    }
}
