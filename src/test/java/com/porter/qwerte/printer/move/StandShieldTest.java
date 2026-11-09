package com.porter.qwerte.printer.move;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class StandShieldTest {
    @Test
    void blocksAtOrBelowTheFeetWithinTwoAreShielded() {
        // feet at 10, 64, 10
        assertTrue(StandShield.shielded(10, 63, 10, 10, 64, 10), "the block under the feet");
        assertTrue(StandShield.shielded(12, 60, 8, 10, 64, 10), "two aside, far below: still shielded");
        assertTrue(StandShield.shielded(11, 64, 10, 10, 64, 10), "at the feet level");
    }

    @Test
    void blocksAboveTheFeetOrFartherThanTwoAreNot() {
        assertFalse(StandShield.shielded(10, 65, 10, 10, 64, 10), "overhead");
        assertFalse(StandShield.shielded(13, 63, 10, 10, 64, 10), "three aside");
        assertFalse(StandShield.shielded(10, 63, 7, 10, 64, 10));
    }

    @Test
    void aWalkToTakeTheTowerDownMayNotEndNextToIt() {
        int[][] tower = { { 0, 70, 0 }, { 0, 69, 0 }, { 0, 68, 0 } };
        assertTrue(StandShield.badFeet(0, 70, 0, tower), "on top of it");
        assertTrue(StandShield.badFeet(2, 70, 2, tower));
        assertFalse(StandShield.badFeet(3, 70, 0, tower), "three aside");
        assertFalse(StandShield.badFeet(0, 60, 0, new int[][] { { 0, 70, 0 } }), "feet far below a block above them");
    }

    @Test
    void theHighestGoesFirstThenTheNearest() {
        int[][] blocks = { { 0, 68, 0 }, { 5, 70, 0 }, { 1, 70, 0 }, { 0, 69, 0 } };
        assertEquals(2, StandShield.topDown(blocks, 0, 72, 0));
        assertEquals(1, StandShield.topDown(blocks, 6, 72, 0));
        assertEquals(-1, StandShield.topDown(new int[0][], 0, 0, 0));
    }

    @Test
    void aWalkThatEndedNextToTheBlocksFailedAndOneThatEndedAwayDidNot() {
        int[][] blocks = { { 0, 70, 0 }, { 0, 69, 0 } };
        assertTrue(StandShield.walkFailed(1, 70, 1, blocks), "no path: still beside the tower");
        assertFalse(StandShield.walkFailed(5, 70, 0, blocks), "arrived away from it");
        assertFalse(StandShield.walkFailed(0, 60, 0, new int[0][]));
        assertEquals(3, StandShield.MAX_FAILS);
        assertTrue(StandShield.RETRY_TICKS > 0);
    }
}
