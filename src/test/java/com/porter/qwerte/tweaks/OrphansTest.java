package com.porter.qwerte.tweaks;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OrphansTest {
    @Test
    void formatsSevenParts() {
        assertEquals("minecraft:shulker_box|1|-2|3|minecraft:overworld|2b2t.org|Shulker Box",
            Orphans.format("minecraft:shulker_box", 1, -2, 3, "minecraft:overworld", "2b2t.org", "Shulker Box"));
    }

    @Test
    void pipesInServerAndNameAreReplaced() {
        String line = Orphans.format("a:b", 0, 0, 0, "d", "h|ost", "Na|me");
        assertEquals("a:b|0|0|0|d|h/ost|Na/me", line);
        assertNotNull(Orphans.parse(line));
    }

    @Test
    void roundTrips() {
        Orphans.Entry e = Orphans.parse(Orphans.format("minecraft:ender_chest", 10, 64, -30, "the_nether", "singleplayer", "Ender Chest"));
        assertEquals(new Orphans.Entry("minecraft:ender_chest", "10", "64", "-30", "the_nether", "singleplayer", "Ender Chest"), e);
    }

    @Test
    void readsOldSixPartEntries() {
        Orphans.Entry e = Orphans.parse("minecraft:shulker_box|1|2|3|overworld|Shulker Box");
        assertEquals(new Orphans.Entry("minecraft:shulker_box", "1", "2", "3", "overworld", "", "Shulker Box"), e);
        assertTrue(Orphans.here(e, "any.server"));
        assertTrue(Orphans.here(e, "singleplayer"));
    }

    @Test
    void sevenPartsWinOverSix() {
        // Seven fields: the server is field six
        Orphans.Entry e = Orphans.parse("i|1|2|3|d|srv|n");
        assertEquals("srv", e.server());
        assertEquals("n", e.name());
    }

    @Test
    void tooFewPartsIsNull() {
        assertNull(Orphans.parse("a|b|c|d|e"));
        assertNull(Orphans.parse(""));
    }

    @Test
    void extraPipesStayInTheName() {
        Orphans.Entry e = Orphans.parse("i|1|2|3|d|srv|n|ame");
        assertEquals("n|ame", e.name());
    }

    @Test
    void hereMatchesOnlyTheSameServer() {
        Orphans.Entry e = Orphans.parse("i|1|2|3|d|srv|n");
        assertTrue(Orphans.here(e, "srv"));
        assertFalse(Orphans.here(e, "other"));
    }
}
