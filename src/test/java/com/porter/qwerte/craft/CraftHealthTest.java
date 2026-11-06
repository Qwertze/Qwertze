package com.porter.qwerte.craft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class CraftHealthTest {
    @Test
    void noResultIsRememberedForTheOrderAndTable() {
        CraftHealth h = new CraftHealth();
        h.note("o1", "t", CraftHealth.NO_RESULT);
        assertEquals(CraftHealth.NO_RESULT, h.problem("o1", "t"));
        assertNull(h.problem("o2", "t"));
        assertNull(h.problem("o1", "other"));
    }

    @Test
    void aSingleCloseIsNotTrouble() {
        CraftHealth h = new CraftHealth();
        h.note("o1", "t", CraftHealth.CLOSED);
        assertNull(h.problem("o1", "t"));
        h.note("o1", "t", CraftHealth.CLOSED);
        assertEquals(CraftHealth.CLOSED, h.problem("o1", "t"));
    }

    @Test
    void aSuccessBreaksTheRunOfCloses() {
        CraftHealth h = new CraftHealth();
        h.note("o1", "t", CraftHealth.CLOSED);
        h.note("o1", "t", null);
        h.note("o1", "t", CraftHealth.CLOSED);
        assertNull(h.problem("o1", "t"));
    }

    @Test
    void roomAndInputProblemsAreNotTableTrouble() {
        CraftHealth h = new CraftHealth();
        h.note("o1", "t", CraftHealth.NO_ROOM);
        h.note("o1", "t", CraftHealth.MISSING);
        assertNull(h.problem("o1", "t"));
    }
}
