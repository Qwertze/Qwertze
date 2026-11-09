package com.porter.qwerte.printer.place;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PacketBudgetTest {
    @Test
    void capacityIsSevenSecondsOfTheRate() {
        assertEquals(385, new PacketBudget(55).capacity());
        assertEquals(7, new PacketBudget(1).capacity());
    }

    @Test
    void zeroMeansOffAndNeverBlocks() {
        PacketBudget b = new PacketBudget(0);
        assertTrue(b.unlimited());
        assertEquals(Integer.MAX_VALUE, b.remaining(0));
        assertTrue(b.trySpend(0, 1_000_000));
        b.record(0, 1_000_000);
        assertTrue(b.canSend(1, Integer.MAX_VALUE));
        assertEquals(0, b.used(0));
        assertEquals(0, b.waitMs(0, 5));
    }

    @Test
    void spendsUpToTheWindowAndRefusesBeyondIt() {
        PacketBudget b = new PacketBudget(10); // 70 per window
        assertTrue(b.trySpend(0, 70));
        assertEquals(0, b.remaining(0));
        assertFalse(b.trySpend(0, 1));
        assertFalse(b.canSend(6999, 1));
        assertEquals(70, b.used(6999));
    }

    @Test
    void theWindowSlidesAndOldSpendingExpiresAtExactlySevenSeconds() {
        PacketBudget b = new PacketBudget(10);
        assertTrue(b.trySpend(0, 30));
        assertTrue(b.trySpend(3000, 40));
        assertEquals(0, b.remaining(3000));
        // the first 30 leave the window at t = 7000
        assertEquals(0, b.remaining(6999));
        assertEquals(30, b.remaining(7000));
        assertTrue(b.trySpend(7000, 30));
        assertFalse(b.trySpend(7000, 1));
        // the 40 from t = 3000 expire at 10000, the 30 from t = 7000 at 14000
        assertEquals(0, b.remaining(9999));
        assertEquals(40, b.remaining(10000));
        assertEquals(70, b.remaining(14000));
    }

    @Test
    void recordCountsExternalPacketsEvenPastTheLimit() {
        PacketBudget b = new PacketBudget(10);
        b.record(0, 100);
        assertEquals(100, b.used(0));
        assertEquals(0, b.remaining(0));
        assertFalse(b.trySpend(0, 1));
        assertEquals(0, b.remaining(6999));
        assertEquals(70, b.remaining(7000));
    }

    @Test
    void sameMillisecondEntriesMerge() {
        PacketBudget b = new PacketBudget(10);
        for (int i = 0; i < 10; i++) assertTrue(b.trySpend(5, 3));
        assertEquals(30, b.used(5));
        assertEquals(40, b.remaining(5));
        assertEquals(0, b.used(7005));
    }

    @Test
    void waitMsSaysWhenThePacketsWillFit() {
        PacketBudget b = new PacketBudget(10);
        assertEquals(0, b.waitMs(0, 70));
        assertEquals(-1, b.waitMs(0, 71));
        b.trySpend(0, 40);
        b.trySpend(2000, 30);
        assertEquals(0, b.waitMs(2000, 0));
        assertEquals(5000, b.waitMs(2000, 40)); // the first 40 expire at 7000
        assertEquals(7000, b.waitMs(2000, 70)); // all of it, when the last 30 expire at 9000
        assertEquals(0, b.waitMs(7000, 40));
    }

    @Test
    void changingTheRateKeepsWhatWasSpent() {
        PacketBudget b = new PacketBudget(10);
        b.trySpend(0, 60);
        b.setPerSecond(20);
        assertEquals(80, b.remaining(0));
        b.setPerSecond(0);
        assertTrue(b.unlimited());
        b.setPerSecond(5);
        assertEquals(0, b.remaining(0));
        b.reset();
        assertEquals(35, b.remaining(0));
    }

    @Test
    void defaultRateRoughlyMatchesOnePlacementPerTick() {
        // look + use + swing = 3 packets a tick = 60 a second, a little over 55 a second: the window throttles
        PacketBudget b = new PacketBudget(55);
        long now = 0;
        int ticks = 0, sent = 0;
        while (ticks < 400) { // 20 seconds
            if (b.trySpend(now, 3)) sent += 3;
            now += 50;
            ticks++;
        }
        long perSecond = sent / 20;
        assertTrue(perSecond <= 60 && perSecond >= 54, "sent " + sent);
        // any 7 second window stays within the capacity
        assertTrue(b.used(now) <= 385);
    }
}
