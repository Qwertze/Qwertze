package com.porter.qwerte.pearl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.porter.qwerte.pearl.InvQueue.Step;
import org.junit.jupiter.api.Test;

class InvQueueTest {
    enum K { ELYTRA_OFF, PEARL, BACK }

    @Test
    void idleWithNothingQueued() {
        InvQueue<K> q = new InvQueue<>(3);
        assertEquals(Step.IDLE, q.tick(false, false, 0));
        assertFalse(q.hasWork());
    }

    @Test
    void operationsRunOneAtATimeInTheOrderQueued() {
        InvQueue<K> q = new InvQueue<>(3);
        q.enqueue(K.ELYTRA_OFF);
        q.enqueue(K.PEARL);
        assertEquals(Step.ISSUE, q.tick(false, false, 0));
        assertEquals(K.ELYTRA_OFF, q.current());
        // The pearl is not issued while the first is in flight, whatever the slots show
        assertEquals(Step.WAIT, q.tick(false, false, 0));
        assertEquals(K.ELYTRA_OFF, q.current());
        assertTrue(q.contains(K.PEARL));
        assertEquals(Step.DONE, q.tick(true, false, 0));
        assertEquals(Step.ISSUE, q.tick(false, false, 0));
        assertEquals(K.PEARL, q.current());
    }

    @Test
    void aDuplicateOfAQueuedOrRunningKindIsIgnored() {
        InvQueue<K> q = new InvQueue<>(3);
        q.enqueue(K.PEARL);
        q.enqueue(K.PEARL);
        assertEquals(1, q.queued().size());
        q.tick(false, false, 0);
        q.enqueue(K.PEARL);
        assertTrue(q.queued().isEmpty());
    }

    @Test
    void theResultHasToShowAndHaveHadTimeForARejectionToComeBack() {
        InvQueue<K> q = new InvQueue<>(3);
        q.enqueue(K.PEARL);
        q.tick(false, false, 4);
        // Result visible at once, but with a 4 tick ping the server's answer may still be on its way
        assertEquals(Step.WAIT, q.tick(true, false, 4));
        assertEquals(Step.WAIT, q.tick(true, false, 4));
        assertEquals(Step.WAIT, q.tick(true, false, 4));
        assertEquals(Step.WAIT, q.tick(true, false, 4));
        assertEquals(Step.DONE, q.tick(true, false, 4));
    }

    @Test
    void anAnswerThatKeepsTheResultEndsTheWaitEarly() {
        InvQueue<K> q = new InvQueue<>(3);
        q.enqueue(K.PEARL);
        q.tick(false, false, 10);
        assertEquals(Step.WAIT, q.tick(true, false, 10));
        assertEquals(Step.DONE, q.tick(true, true, 10));
    }

    @Test
    void aRevertedResultIsRetriedWithAFreshPlan() {
        InvQueue<K> q = new InvQueue<>(3);
        q.enqueue(K.PEARL);
        q.tick(false, false, 1);
        assertEquals(Step.WAIT, q.tick(true, false, 1));
        // The server resent the inventory: the state id changed and the guess is gone
        assertEquals(Step.RETRY, q.tick(false, true, 1));
        assertEquals(K.PEARL, q.current());
        assertEquals(1, q.failures());
        assertEquals(Step.WAIT, q.tick(false, false, 1));
        assertEquals(Step.DONE, q.tick(true, true, 1));
    }

    @Test
    void noResultAtAllIsRetriedAfterTheTimeoutAndGivenUpAfterTheFailures() {
        InvQueue<K> q = new InvQueue<>(2);
        q.enqueue(K.PEARL);
        assertEquals(Step.ISSUE, q.tick(false, false, 0));
        int settle = 2;
        int retries = 0, ticks = 0;
        Step last = Step.WAIT;
        while (last != Step.FAILED && ticks < 100) {
            last = q.tick(false, false, 0);
            if (last == Step.RETRY) retries++;
            ticks++;
        }
        assertEquals(Step.FAILED, last);
        assertEquals(2, retries);
        assertTrue(ticks >= 3 * (settle + InvQueue.TIMEOUT_EXTRA), "each attempt waited out its timeout: " + ticks);
        assertTrue(q.takeFailed(K.PEARL));
        assertFalse(q.takeFailed(K.PEARL));
        assertFalse(q.busy());
    }

    @Test
    void waitingBehindAnotherOperationIsNotAFailure() {
        InvQueue<K> q = new InvQueue<>(1);
        q.enqueue(K.ELYTRA_OFF);
        q.enqueue(K.PEARL);
        q.tick(false, false, 0);
        // A long time passes for the first one; the second has not even been issued, so it has no failures
        for (int i = 0; i < 6; i++) q.tick(false, false, 0);
        assertEquals(K.ELYTRA_OFF, q.current());
        assertEquals(Step.DONE, q.tick(true, true, 0));
        assertEquals(Step.ISSUE, q.tick(false, false, 0));
        assertEquals(K.PEARL, q.current());
        assertEquals(0, q.failures());
    }

    @Test
    void aSkippedOperationFreesTheQueueAtOnce() {
        InvQueue<K> q = new InvQueue<>(3);
        q.enqueue(K.BACK);
        q.enqueue(K.PEARL);
        assertEquals(Step.ISSUE, q.tick(false, false, 0));
        q.skip();
        assertEquals(Step.ISSUE, q.tick(false, false, 0));
        assertEquals(K.PEARL, q.current());
    }

    @Test
    void aQueuedOperationCanBeCancelledButNotOneInFlight() {
        InvQueue<K> q = new InvQueue<>(3);
        q.enqueue(K.ELYTRA_OFF);
        q.enqueue(K.PEARL);
        q.tick(false, false, 0);
        q.cancelQueued(K.PEARL);
        q.cancelQueued(K.ELYTRA_OFF);
        assertEquals(K.ELYTRA_OFF, q.current());
        assertFalse(q.contains(K.PEARL));
    }
}
