package com.porter.qwerte.messenger;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MessengerScaleTest {
    @Test
    void defaultWindowIsScaleOne() {
        assertEquals(1f, MessengerScale.effective(true, 1f, MessengerScale.DEFAULT_W, MessengerScale.DEFAULT_H));
    }

    @Test
    void autoFollowsTheTighterSideAndClamps() {
        assertEquals(1.5f, MessengerScale.auto(495, 285), 1e-4);
        assertEquals(1.0f, MessengerScale.auto(660, 190), 1e-4); // the short side limits
        assertEquals(MessengerScale.MAX, MessengerScale.auto(5000, 5000));
        assertEquals(MessengerScale.MIN, MessengerScale.auto(100, 50));
        assertEquals(1f, MessengerScale.auto(0, 0));
    }

    @Test
    void fixedIsClamped() {
        assertEquals(1.25f, MessengerScale.effective(false, 1.25f, 800, 600));
        assertEquals(MessengerScale.MAX, MessengerScale.effective(false, 9f, 2000, 2000));
        assertEquals(MessengerScale.MIN, MessengerScale.effective(false, 0.1f, 800, 600));
        assertEquals(1f, MessengerScale.clamp(Float.NaN));
    }

    @Test
    void neverLeavesLessRoomThanTheSmallestWindow() {
        // a 2x text in the smallest window would give only 110 x 65 logical pixels
        float s = MessengerScale.effective(false, 2f, Geometry.MIN_W, Geometry.MIN_H);
        assertEquals(1f, s);
    }

    @Test
    void stepsOfFiveHundredths() {
        float s = MessengerScale.effective(true, 1f, 351, 202);
        assertEquals(Math.round(s * 20f) / 20f, s);
    }

    @Test
    void logicalSizeIsTheRealSizeOverTheScale() {
        assertEquals(330, MessengerScale.logical(330, 1f));
        assertEquals(330, MessengerScale.logical(660, 2f));
        assertEquals(1, MessengerScale.logical(0, 1f));
    }
}
