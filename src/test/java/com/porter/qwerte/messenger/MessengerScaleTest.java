package com.porter.qwerte.messenger;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MessengerScaleTest {
    @Test
    void floorsAreTheKeptOnes() {
        assertEquals(0.1f, MessengerScale.MIN);
        assertEquals(2.0f, MessengerScale.MAX);
        assertEquals(24, Geometry.MIN_W);
        assertEquals(12, Geometry.MIN_H);
    }

    @Test
    void defaultWindowIsScaleOne() {
        assertEquals(1f, MessengerScale.effective(true, 1f, MessengerScale.DEFAULT_W, MessengerScale.DEFAULT_H));
    }

    @Test
    void autoFollowsTheGeometricMeanAndClamps() {
        assertEquals(1.5f, MessengerScale.auto(495, 285), 1e-4);
        assertEquals(Math.sqrt(2.0), MessengerScale.auto(660, 190), 1e-4); // wide and short: between the two ratios
        assertEquals(MessengerScale.MAX, MessengerScale.auto(5000, 5000));
        assertEquals(1f, MessengerScale.auto(0, 0));
    }

    @Test
    void autoShrinksFarBelowTheOldFloor() {
        assertEquals(0.5f, MessengerScale.effective(true, 1f, 165, 95));
        assertEquals(0.2f, MessengerScale.effective(true, 1f, 66, 38), 1e-4);
        assertEquals(0.1f, MessengerScale.effective(true, 1f, Geometry.MIN_W, Geometry.MIN_H));
        assertEquals(MessengerScale.MIN, MessengerScale.auto(1, 1));
        assertTrue(MessengerScale.auto(100, 60) < 0.75f);
    }

    @Test
    void fixedIsClampedToTheSameBounds() {
        assertEquals(1.25f, MessengerScale.effective(false, 1.25f, 800, 600));
        assertEquals(MessengerScale.MAX, MessengerScale.effective(false, 9f, 2000, 2000));
        assertEquals(MessengerScale.MIN, MessengerScale.effective(false, 0.01f, 800, 600));
        assertEquals(0.3f, MessengerScale.effective(false, 0.3f, 800, 600), 1e-4);
        assertEquals(1f, MessengerScale.clamp(Float.NaN));
    }

    @Test
    void aLargeFixedScaleCanNotPushTheContentsOutOfASmallWindow() {
        float s = MessengerScale.effective(false, 2f, 60, 40);
        assertTrue(60 / s >= MessengerScale.LOGICAL_MIN_W - 1);
        assertTrue(40 / s >= MessengerScale.LOGICAL_MIN_H - 1);
        // the smallest window at 2x: still the floor, never zero or negative
        float t = MessengerScale.effective(false, 2f, Geometry.MIN_W, Geometry.MIN_H);
        assertTrue(t >= MessengerScale.MIN && t <= 2f);
    }

    @Test
    void stepsAreFineWhenSmall() {
        float s = MessengerScale.effective(true, 1f, 100, 58);
        assertEquals(Math.round(s * 100f) / 100f, s, 1e-6);
        float big = MessengerScale.effective(true, 1f, 351, 202);
        assertEquals(Math.round(big * 20f) / 20f, big, 1e-6);
    }

    @Test
    void logicalSizeIsTheRealSizeOverTheScale() {
        assertEquals(330, MessengerScale.logical(330, 1f));
        assertEquals(330, MessengerScale.logical(660, 2f));
        assertEquals(240, MessengerScale.logical(24, 0.1f));
        assertEquals(1, MessengerScale.logical(0, 1f));
    }
}
