package com.porter.qwerte.pearl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.porter.qwerte.pearl.TrapClicks.Verdict;
import org.junit.jupiter.api.Test;

class TrapClicksTest {
    private static final long A = 1, B = 2;

    @Test
    void theTimeoutFollowsThePing() {
        assertEquals(3, TrapClicks.timeoutTicks(0));
        assertEquals(3, TrapClicks.timeoutTicks(1));
        assertEquals(5, TrapClicks.timeoutTicks(2));
        assertEquals(11, TrapClicks.timeoutTicks(5));
    }

    @Test
    void aClickedTrapdoorIsPendingAndNotClickedAgain() {
        TrapClicks t = new TrapClicks();
        assertFalse(t.isPending(A));
        t.clicked(A, true, 10);
        assertTrue(t.isPending(A));
        assertTrue(t.anyPending());
        // Judged again and again before the answer or the timeout: still pending, no failure counted
        for (long now = 10; now < 13; now++) {
            assertEquals(Verdict.PENDING, t.judge(A, now, false, -1, 3));
            assertTrue(t.isPending(A));
        }
        assertEquals(0, t.failures(A));
    }

    @Test
    void theServersUpdateEndsTheWaitTheTickAfterItArrives() {
        TrapClicks t = new TrapClicks();
        t.clicked(A, true, 10);
        // Received in tick 12: the game thread applies it in the next tick
        assertEquals(Verdict.PENDING, t.judge(A, 12, false, 12, 5));
        assertEquals(Verdict.OK, t.judge(A, 13, true, 12, 5));
        assertFalse(t.isPending(A));
        assertEquals(0, t.failures(A));
    }

    @Test
    void anUpdateThatRevertsIsAFailureAndAllowsAClick() {
        TrapClicks t = new TrapClicks();
        t.clicked(A, true, 10);
        assertEquals(Verdict.FAILED, t.judge(A, 13, false, 12, 5));
        assertEquals(1, t.failures(A));
        assertFalse(t.isPending(A));
        t.clicked(A, true, 14);
        assertEquals(Verdict.OK, t.judge(A, 16, true, 15, 5));
        assertEquals(0, t.failures(A), "a success starts the count again");
    }

    @Test
    void noUpdateAtAllIsJudgedByTheClientStateAfterTheTimeout() {
        TrapClicks t = new TrapClicks();
        t.clicked(A, true, 10);
        assertEquals(Verdict.PENDING, t.judge(A, 12, true, -1, 3));
        assertEquals(Verdict.OK, t.judge(A, 13, true, -1, 3));
        t.clicked(B, true, 20);
        assertEquals(Verdict.FAILED, t.judge(B, 23, false, -1, 3));
        assertEquals(1, t.failures(B));
    }

    @Test
    void anUpdateFromBeforeTheClickDoesNotCount() {
        TrapClicks t = new TrapClicks();
        t.clicked(A, true, 10);
        assertEquals(Verdict.PENDING, t.judge(A, 11, false, 8, 5));
    }

    @Test
    void onlyJudgedAttemptsAreCountedAndTrapdoorsAreIndependent() {
        TrapClicks t = new TrapClicks();
        t.clicked(A, true, 10);
        t.clicked(B, true, 10);
        assertEquals(2, t.pendingClicks().size());
        assertEquals(Verdict.FAILED, t.judge(A, 13, false, -1, 3));
        assertTrue(t.isPending(B));
        assertEquals(1, t.failures(A));
        assertEquals(0, t.failures(B));
        for (int i = 0; i < 3; i++) {
            t.clicked(A, true, 20 + i * 10);
            t.judge(A, 23 + i * 10, false, -1, 3);
        }
        assertEquals(4, t.failures(A));
        assertEquals(Verdict.NOT_PENDING, t.judge(A, 100, true, -1, 3));
    }

    @Test
    void fastAndMaxWaitForTheServerAndSafeDoesNot() {
        assertFalse(SpeedMode.Safe.params().awaitServer());
        assertTrue(SpeedMode.Fast.params().awaitServer());
        assertTrue(SpeedMode.Max.params().awaitServer());
        // Max clicks more trapdoors in a tick, never the same one twice
        assertEquals(2, SpeedMode.Max.params().trapClicksPerTick());
    }

    @Test
    void safeIsExactlyWhatItWas() {
        SpeedMode.Params old = new SpeedMode.Params(true, 1, 3, false, false, 6, 3, 3, 1, 3, 15, false, false, 0.0005, false);
        assertEquals(old, SpeedMode.Safe.params());
    }

    @Test
    void fastWaitsLessOnTheEdgeThanSafe() {
        SpeedMode.Params safe = SpeedMode.Safe.params(), fast = SpeedMode.Fast.params(), max = SpeedMode.Max.params();
        assertTrue(fast.stillEps() > safe.stillEps());
        assertTrue(fast.briskApproach() && max.briskApproach());
        assertFalse(safe.briskApproach());
        assertTrue(fast.stillTicks() < safe.stillTicks());
    }
}
