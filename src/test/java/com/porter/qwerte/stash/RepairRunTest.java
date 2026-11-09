package com.porter.qwerte.stash;

import com.porter.qwerte.stash.RepairRun.Guard;
import com.porter.qwerte.stash.RepairRun.Verdict;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RepairRunTest {
    @Test
    void batchTakesWhatFitsTheBottles() {
        assertEquals(new RepairRun.Batch(0, 0), RepairRun.pickBatch(List.of(10), 0));
        assertEquals(new RepairRun.Batch(0, 0), RepairRun.pickBatch(List.of(), 50));
        // the first item is always taken, repaired as far as the bottles go
        assertEquals(new RepairRun.Batch(1, 5), RepairRun.pickBatch(List.of(100, 100), 5));
        // a second item only when both fit with the spare
        assertEquals(new RepairRun.Batch(2, 22), RepairRun.pickBatch(List.of(10, 10), 100));
        assertEquals(new RepairRun.Batch(1, 12), RepairRun.pickBatch(List.of(10, 10), 21));
        assertEquals(RepairRun.MAX_BATCH_ITEMS, RepairRun.pickBatch(List.of(1, 1, 1, 1, 1, 1), 1000).items());
        assertEquals(RepairRun.MAX_BATCH_BOTTLES, RepairRun.pickBatch(List.of(300, 5), 1000).bottles());
    }

    @Test
    void aPlayerNearPausesOnceThenGivesUp() {
        Guard g = new Guard();
        assertEquals(Verdict.CONTINUE, g.playerNear(false));
        assertEquals(Verdict.PAUSE, g.playerNear(true));
        assertTrue(g.paused());
        for (int i = 0; i < 100; i++) assertEquals(Verdict.PAUSE, g.playerNear(true));
        for (int i = 0; i < Guard.CALM_TICKS - 1; i++) assertEquals(Verdict.PAUSE, g.playerNear(false));
        assertEquals(Verdict.CONTINUE, g.playerNear(false));
        assertFalse(g.paused());
        assertEquals(Verdict.GIVE_UP, g.playerNear(true));
    }

    @Test
    void vanishedOrbsUseTheSameRetry() {
        Guard g = new Guard();
        assertEquals(Verdict.PAUSE, g.orbsVanished());
        assertEquals(Verdict.GIVE_UP, g.orbsVanished());
        Guard h = new Guard();
        assertEquals(Verdict.PAUSE, h.playerNear(true));
        for (int i = 0; i < Guard.CALM_TICKS; i++) h.playerNear(false);
        assertEquals(Verdict.GIVE_UP, h.orbsVanished());
    }

    @Test
    void theRetryIsOncePerOrderNotPerBatch() {
        Guard first = new Guard();
        assertEquals(Verdict.PAUSE, first.orbsVanished());
        assertEquals(1, first.retries());
        // the next batch of the same order starts with the retry used
        Guard second = new Guard();
        second.preloadRetries(first.retries());
        assertEquals(Verdict.GIVE_UP, second.orbsVanished());
        Guard third = new Guard();
        third.preloadRetries(1);
        assertEquals(Verdict.GIVE_UP, third.playerNear(true));
        assertEquals(Verdict.CONTINUE, new Guard().playerNear(false));
    }

    @Test
    void theCapStopsEverything() {
        Guard g = new Guard();
        for (int i = 0; i < Guard.CAP_TICKS - 1; i++) g.countThrowTick();
        assertFalse(g.capped());
        g.countThrowTick();
        assertTrue(g.capped());
        assertEquals(Verdict.GIVE_UP, g.playerNear(false));
        Guard late = new Guard();
        late.preload(Guard.CAP_TICKS);
        assertTrue(late.capped(), "time used by earlier batches of the order counts");
        assertEquals(20 * 120, Guard.CAP_TICKS);
    }

    @Test
    void tallyBuildsTheResultAndTheNote() {
        RepairRun.Tally t = new RepairRun.Tally();
        assertNull(t.result());
        assertEquals("", t.note());
        t.bottles(0);
        t.add(new RepairRun.Outcome(10, List.of(
            new RepairRun.ItemResult("a", "a2", RepairRun.Status.FULL, 0, 1561, null),
            new RepairRun.ItemResult("b", "b2", RepairRun.Status.PARTIAL, 120, 1561, "no bottles"),
            new RepairRun.ItemResult("c", null, RepairRun.Status.UNTOUCHED, 0, 0, "interrupted")), 100));
        t.skip("d", "no Mending");
        t.skip("e", "no Mending");
        RepairEstimate.Result r = t.result();
        assertEquals(10, r.bottlesUsed());
        assertEquals(1, r.repaired());
        assertEquals(1, r.partial().size());
        assertEquals("b", r.partial().get(0).key());
        assertEquals(3, r.skipped().size());
        assertEquals("4 item(s) not fully repaired: no bottles, interrupted, no Mending", t.note());
    }

    @Test
    void skipsCountPerItemAndSurviveASaveAndLoad() {
        RepairRun.Tally t = new RepairRun.Tally();
        t.skip("stackable", "no Mending", 3);
        t.add(new RepairRun.ItemResult("p", "p2", RepairRun.Status.PARTIAL, 40, 100, "interrupted"));
        assertEquals(3, t.result().skipped().size());
        assertEquals("4 item(s) not fully repaired: no Mending, interrupted", t.note());

        RepairEstimate.Result r = t.result();
        RepairRun.Tally back = new RepairRun.Tally();
        back.load(r.bottlesUsed() + 5, r.repaired() + 1, r.partial(), r.skipped(), t.reasons());
        assertEquals(t.note(), back.note());
        assertEquals(5, back.result().bottlesUsed());
        assertEquals(1, back.result().repaired());
        back.skip("x", "no bottles");
        assertEquals("5 item(s) not fully repaired: no Mending, interrupted, no bottles", back.note());
    }

    @Test
    void anAllRepairedRunHasNoNote() {
        RepairRun.Tally t = new RepairRun.Tally();
        t.add(new RepairRun.Outcome(3, List.of(new RepairRun.ItemResult("a", "a2", RepairRun.Status.FULL, 0, 10, null)), 5));
        assertEquals("", t.note());
        assertEquals(1, t.result().repaired());
    }
}
