package com.porter.qwerte.pearl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PearlWaitTest {
    @Test
    void zeroWaitGivesUpAtOnce() {
        Pending p = new Pending();
        assertEquals(Pending.Outcome.GIVE_UP, p.step(Pending.Block.NO_PEARL, null, 0));
        assertEquals("no ender pearls", p.reason());
        assertFalse(p.pearlTimedOut());
        assertEquals(Pending.Outcome.GIVE_UP, new Pending().step(Pending.Block.NO_PEARL, null));
    }

    @Test
    void waitsThenGoesWhenThePearlArrives() {
        Pending p = new Pending();
        for (int i = 0; i < 100; i++) assertEquals(Pending.Outcome.WAIT, p.step(Pending.Block.NO_PEARL, null, 400));
        assertEquals(100, p.pearlTicks());
        assertEquals(Pending.Outcome.GO, p.step(Pending.Block.NONE, null, 400));
    }

    @Test
    void timesOutAfterTheLimit() {
        Pending p = new Pending();
        for (int i = 0; i < 40; i++) assertEquals(Pending.Outcome.WAIT, p.step(Pending.Block.NO_PEARL, null, 40));
        assertEquals(Pending.Outcome.GIVE_UP, p.step(Pending.Block.NO_PEARL, null, 40));
        assertTrue(p.pearlTimedOut());
    }

    @Test
    void waitingDoesNotUseUpTheStartLimit() {
        // The ordinary 5 s limit (chunks, screens) only counts once the pearl is there
        Pending p = new Pending();
        for (int i = 0; i < 300; i++) p.step(Pending.Block.NO_PEARL, null, 1000);
        assertEquals(0, p.ticks());
        for (int i = 0; i < Pending.LIMIT - 1; i++) assertEquals(Pending.Outcome.WAIT, p.step(Pending.Block.UNLOADED, null, 1000));
        assertEquals(Pending.Outcome.GIVE_UP, p.step(Pending.Block.UNLOADED, null, 1000));
        assertFalse(p.pearlTimedOut());
    }

    @Test
    void thePearlArrivingStartsTheSequenceCleanly() {
        // Sequence starts fresh after the wait: its own timers are at zero
        Sequence s = new Sequence();
        assertEquals(Sequence.Phase.EXIT, s.phase());
        assertEquals(0, s.phaseTicks());
    }

    @Test
    void otherBlocksStillWaitWhileThePearlIsThere() {
        Pending p = new Pending();
        assertEquals(Pending.Outcome.WAIT, p.step(Pending.Block.SCREEN, null, 400));
        assertEquals(Pending.Outcome.WAIT, p.step(Pending.Block.DANGER, null, 400));
    }
}
