package com.porter.qwerte.stash;

import com.porter.qwerte.stash.DeliveryBook.Pos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The order's wait for delivery space: the same words and the same bound as the proxy plugin's. */
class SpaceWaitTest {
    private static final Pos BASE = new Pos(0, 64, 0);

    private static DeliveryBook full(int chests) {
        DeliveryBook b = new DeliveryBook();
        b.syncPrimary(List.of(BASE));
        for (int i = 1; i < chests; i++) b.add(List.of(new Pos(0, 64 + i, 0)));
        for (DeliveryBook.Entry e : b.sorted()) b.markFull(e, 1);
        return b;
    }

    @Test
    void roomMeansCarryOn() {
        SpaceWait w = new SpaceWait();
        assertNull(w.step(1000, DeliveryRoom.ROOM, 0, 3));
    }

    @Test
    void aFullStoragePausesWithTheReason() {
        SpaceWait w = new SpaceWait();
        DeliveryRoom room = full(1).room(false, null, 5, 0);
        SpaceWait.Decision d = w.step(1000, room, 1, 3);
        assertEquals("paused: waiting for delivery space: delivery storage full (1 chest), auto expand is off", d.phase());
        assertFalse(d.timedOut());

        DeliveryRoom blocked = full(2).room(true, "stone is in the way above the top chest", 5, 0);
        assertEquals("paused: waiting for delivery space: delivery storage full: can't place higher: stone is in the way above the top chest", w.step(2000, blocked, 1, 3).phase());
    }

    @Test
    void theWaitEndsAfterTwoHoursAndTheTimerRestartsWhenThereWasRoom() {
        SpaceWait w = new SpaceWait();
        DeliveryRoom wait = full(1).room(false, null, 5, 0);
        assertFalse(w.step(1_000_000, wait, 1, 3).timedOut());
        assertFalse(w.step(1_000_000 + SpaceWait.TIMEOUT_MS, wait, 1, 3).timedOut());
        SpaceWait.Decision end = w.step(1_000_000 + SpaceWait.TIMEOUT_MS + 1, wait, 1, 3);
        assertTrue(end.timedOut());
        assertEquals("delivery container full (1/3): waited 120 min for space, delivery storage full (1 chest), auto expand is off", end.endMessage());

        // Room, then full again: waits from the new start
        w.step(5_000_000, DeliveryRoom.ROOM, 2, 3);
        assertFalse(w.step(5_000_001 + SpaceWait.TIMEOUT_MS - 10, wait, 2, 3).timedOut());
    }
}
