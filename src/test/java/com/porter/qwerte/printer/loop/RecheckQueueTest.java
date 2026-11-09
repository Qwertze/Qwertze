package com.porter.qwerte.printer.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class RecheckQueueTest {
    @Test
    void aCellIsHandedOutOnlyWhenDueAndInReach() {
        RecheckQueue q = new RecheckQueue();
        q.add(1, 2, 3, 0, 100);
        assertTrue(q.poll(50, 10, 3, c -> true).isEmpty()); // not due
        assertTrue(q.poll(100, 10, 3, c -> false).isEmpty()); // out of reach
        assertEquals(1, q.size());
        List<RecheckQueue.Cell> out = q.poll(100, 10, 3, c -> true);
        assertEquals(1, out.size());
        assertEquals(2, out.get(0).y());
        assertEquals(0, q.size());
    }

    @Test
    void aCellIsCheckedAtMostThreeTimes() {
        RecheckQueue q = new RecheckQueue();
        q.add(5, 5, 5, 0, 10);
        long now = 10;
        int checks = 0;
        for (int i = 0; i < 10; i++) {
            List<RecheckQueue.Cell> out = q.poll(now, 10, 3, c -> true);
            for (RecheckQueue.Cell c : out) {
                checks++;
                q.again(c, now, 10);
            }
            now += 10;
        }
        assertEquals(RecheckQueue.MAX_CHECKS, checks);
        assertEquals(0, q.size());
        // finished again after being fixed: still no more checks
        q.add(5, 5, 5, now, 10);
        assertEquals(0, q.size());
        assertEquals(RecheckQueue.MAX_CHECKS, q.checksOf(5, 5, 5));
    }

    @Test
    void theExamineBudgetBoundsTheWorkPerCall() {
        RecheckQueue q = new RecheckQueue();
        for (int i = 0; i < 100; i++) q.add(i, 0, 0, 0, 0);
        // only the cells at x >= 50 are in reach, and only 8 are looked at per call
        int seen = 0;
        for (int call = 0; call < 20; call++) {
            seen += q.poll(1, 8, 2, c -> c.x() >= 50).size();
        }
        assertTrue(seen > 0 && seen <= 40); // at most 2 per call
        assertEquals(100 - seen, q.size());
    }

    @Test
    void maxBoundsWhatComesBackPerCall() {
        RecheckQueue q = new RecheckQueue();
        for (int i = 0; i < 10; i++) q.add(i, 0, 0, 0, 0);
        assertEquals(3, q.poll(1, 100, 3, c -> true).size());
    }

    @Test
    void theQueueIsCappedAndTheOldestRecordsGo() {
        RecheckQueue q = new RecheckQueue();
        for (int i = 0; i < RecheckQueue.MAX_RECORDS + 100; i++) q.add(i, 0, 0, 0, 0);
        assertEquals(RecheckQueue.MAX_RECORDS, q.size());
        // the first 100 were dropped: the first one handed out is x = 100
        assertEquals(100, q.poll(1, 1, 1, c -> true).get(0).x());
    }
}
