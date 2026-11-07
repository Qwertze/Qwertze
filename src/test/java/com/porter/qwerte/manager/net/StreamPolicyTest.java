package com.porter.qwerte.manager.net;

import com.porter.qwerte.manager.net.ViewerStream.End;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** When a stream may start again, and how long it waits between connects. */
class StreamPolicyTest {
    @Test
    void anEndedStreamIsNotRestartedEveryFrame() {
        assertEquals(-1, StreamRestart.delayMs(End.AUTH, 0));
        assertEquals(-1, StreamRestart.delayMs(End.AUTH, 5));
        assertEquals(1_000, StreamRestart.delayMs(End.REVOKED, 0));
        assertTrue(StreamRestart.delayMs(End.REVOKED, 1) >= 5_000);
        for (End e : new End[] {End.FORBIDDEN, End.NOT_FOUND, End.ERROR}) {
            assertEquals(5_000, StreamRestart.delayMs(e, 0));
            assertEquals(10_000, StreamRestart.delayMs(e, 1));
            assertEquals(20_000, StreamRestart.delayMs(e, 2));
            assertEquals(60_000, StreamRestart.delayMs(e, 9));
            assertEquals(60_000, StreamRestart.delayMs(e, 1000));
        }
        assertEquals(0, StreamRestart.delayMs(End.STOPPED, 0));
    }

    @Test
    void rotationReconnectsButNeverFasterThanOneSecond() {
        ReconnectPolicy p = new ReconnectPolicy();
        p.started(10_000);
        // a rotation 45 s later: at once
        assertEquals(0, p.next(55_000, 0, true, false));
        p.started(55_000);
        // a connection that ended 100 ms after it began still waits out the gap
        assertEquals(900, p.next(55_100, 0, true, false));
    }

    @Test
    void threeFreeReconnectsWithoutASnapshotThenBackoff() {
        ReconnectPolicy p = new ReconnectPolicy();
        long now = 100_000;
        for (int i = 0; i < ReconnectPolicy.FREE_RECONNECTS; i++) {
            p.started(now);
            now += 2_000;
            assertEquals(0, p.next(now, 0, false, false));
        }
        p.started(now);
        now += 2_000;
        assertEquals(5_000, p.next(now, 0, false, false));
        p.started(now);
        assertEquals(10_000, p.next(now + 2_000, 0, false, false));
        // a snapshot resets it
        p.started(now);
        assertEquals(0, p.next(now + 2_000, 0, true, false));
        p.started(now);
        assertEquals(0, p.next(now + 2_000, 0, false, false));
    }

    @Test
    void failuresWaitForTheirBackoffOrTheServersAsk() {
        ReconnectPolicy p = new ReconnectPolicy();
        p.started(0);
        assertEquals(5_000, p.next(10, 0, false, true));
        assertEquals(10_000, p.next(20, 0, false, true));
        assertEquals(20_000, p.next(30, 0, false, true));
        assertEquals(40_000, p.next(40, 0, false, true));
        assertEquals(60_000, p.next(50, 0, false, true));
        assertEquals(60_000, p.next(60, 0, false, true));
        // Retry-After longer than the backoff wins
        assertEquals(120_000, p.next(70, 120_000, false, true));
        // a failure after a good connection starts from the bottom again
        assertEquals(5_000, p.next(80, 0, true, true));
    }
}
