package com.porter.qwerte.printer.place;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class JobHoldTest {
    @Test
    void aJobHoldsTheEndOnlyWhileItCanProgress() {
        assertTrue(JobHold.canProgress(true, true, false));
        assertFalse(JobHold.canProgress(false, true, false)); // no axe or bucket
        assertFalse(JobHold.canProgress(true, false, false)); // chunk not loaded
        assertFalse(JobHold.canProgress(true, true, true));   // gave up
    }

    @Test
    void jobsThatCannotProgressNeverHoldTheRun() {
        assertFalse(JobHold.any(new boolean[] { false, true }, new boolean[] { true, false }, new boolean[] { false, false }));
        assertFalse(JobHold.any(new boolean[0], new boolean[0], new boolean[0]));
        assertTrue(JobHold.any(new boolean[] { false, true }, new boolean[] { true, true }, new boolean[] { false, false }));
    }
}
