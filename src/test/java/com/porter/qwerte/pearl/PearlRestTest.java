package com.porter.qwerte.pearl;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PearlRestTest {
    @Test
    void aPearlHoldingItsHeightRestsAfterThreeTicks() {
        PearlRest r = new PearlRest();
        assertFalse(r.update(10, 60.00));
        assertFalse(r.update(11, 60.02));
        assertFalse(r.update(12, 59.98));
        assertTrue(r.update(13, 60.01));
        assertTrue(r.update(13, 60.01), "the same tick twice does not change the answer");
    }

    @Test
    void aMovingPearlNeverRests() {
        PearlRest r = new PearlRest();
        for (int t = 0; t < 50; t++) assertFalse(r.update(t, 70 - t * 0.2));
    }

    @Test
    void aJumpRestartsTheCountAndAGapDoes() {
        PearlRest r = new PearlRest();
        for (int t = 0; t < 5; t++) r.update(t, 60);
        assertFalse(r.update(5, 61));
        assertFalse(r.update(6, 61));
        assertFalse(r.update(7, 61));
        assertTrue(r.update(8, 61));
        assertFalse(r.update(20, 61), "ticks were missed: not known to be resting");
    }
}
