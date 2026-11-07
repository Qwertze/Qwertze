package com.porter.qwerte.pearl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ChamberTest {
    @Test
    void roundTrips() {
        Chamber c = new Chamber("play.example.net", "ResourceKey[minecraft:dimension / minecraft:the_nether]", -5, 64, 12, "Chamber 1");
        assertEquals(c, Chamber.decode(c.encode()));
    }

    @Test
    void nameMayHoldTheSeparator() {
        Chamber c = new Chamber("s", "d", 1, 2, 3, "a|b");
        assertEquals("a|b", Chamber.decode(c.encode()).name());
    }

    @Test
    void garbageIsNull() {
        assertNull(Chamber.decode("nope"));
        assertNull(Chamber.decode("s|d|x|2|3|n"));
        assertNull(Chamber.decode(null));
    }

    @Test
    void shortDimension() {
        assertEquals("the_nether", new Chamber("s", "ResourceKey[minecraft:dimension / minecraft:the_nether]", 0, 0, 0, "n").shortDimension());
        assertEquals("overworld", new Chamber("s", "minecraft:overworld", 0, 0, 0, "n").shortDimension());
    }

    @Test
    void samePlace() {
        Chamber a = new Chamber("s", "d", 1, 2, 3, "a");
        assertTrue(a.samePlace(new Chamber("s", "d", 1, 2, 3, "b")));
        assertFalse(a.samePlace(new Chamber("s", "d", 1, 2, 4, "a")));
    }

    @Test
    void serverIsNormalised() {
        assertEquals("play.example.net", new Chamber("Play.Example.net.:25565", "d", 0, 0, 0, "n").server());
        assertEquals("play.example.net", new Chamber("play.example.net.", "d", 0, 0, 0, "n").server());
        assertEquals("host:25570", new Chamber("HOST:25570", "d", 0, 0, 0, "n").server());
        assertTrue(new Chamber("A.b", "d", 1, 2, 3, "x").samePlace(new Chamber("a.B.", "d", 1, 2, 3, "y")));
    }
}
