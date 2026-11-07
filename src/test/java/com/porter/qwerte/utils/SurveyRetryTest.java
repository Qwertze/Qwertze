package com.porter.qwerte.utils;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SurveyRetryTest {
    @Test
    void backoffIsOneTwoThenFiveMinutes() {
        assertEquals(1200, SurveyRetry.delayFor(1));
        assertEquals(2400, SurveyRetry.delayFor(2));
        assertEquals(6000, SurveyRetry.delayFor(3));
        assertEquals(6000, SurveyRetry.delayFor(40));
        assertEquals(0, SurveyRetry.delayFor(0));
    }

    @Test
    void waitsUntilDueThenReleasesOnce() {
        SurveyRetry<String> retry = new SurveyRetry<>();
        assertEquals(1200, retry.fail("a", 1000));
        assertTrue(retry.waiting("a", 1000));
        assertTrue(retry.waiting("a", 2199));
        assertEquals(1, retry.remaining("a", 2199));
        assertEquals(List.of(), retry.due(2199));
        assertEquals(List.of("a"), retry.due(2200));
        assertEquals(List.of(), retry.due(2201));
        assertFalse(retry.waiting("a", 2200));
        assertEquals(0, retry.remaining("a", 2200));
    }

    @Test
    void repeatedFailuresWaitLonger() {
        SurveyRetry<String> retry = new SurveyRetry<>();
        retry.fail("a", 0);
        retry.due(1200);
        assertEquals(2400, retry.fail("a", 1300));
        retry.due(10_000);
        assertEquals(6000, retry.fail("a", 10_000));
        assertEquals(3, retry.failures("a"));
    }

    @Test
    void successForgetsTheBackoff() {
        SurveyRetry<String> retry = new SurveyRetry<>();
        retry.fail("a", 0);
        retry.succeed("a");
        assertFalse(retry.waiting("a", 1));
        assertEquals(1200, retry.fail("a", 5));
    }

    @Test
    void keysAreIndependent() {
        SurveyRetry<String> retry = new SurveyRetry<>();
        retry.fail("a", 0);
        retry.fail("b", 600);
        assertEquals(List.of("a"), retry.due(1200));
        assertTrue(retry.waiting("b", 1200));
    }

    @Test
    void oneFailurePerRead() {
        SurveyRetry<String> retry = new SurveyRetry<>();
        assertEquals(1200, retry.failOnce("a", 100));
        assertEquals(-1, retry.failOnce("a", 100));
        assertEquals(1, retry.failures("a"));
        retry.due(1300);
        assertEquals(2400, retry.failOnce("a", 1400));
    }

    @Test
    void describesDelays() {
        assertEquals("30 s", SurveyRetry.describe(600));
        assertEquals("1 min", SurveyRetry.describe(1200));
        assertEquals("5 min", SurveyRetry.describe(6000));
    }

    @Test
    void walkProgressStallsAfterTheLimit() {
        WalkProgress walk = new WalkProgress();
        walk.reset(0, 20);
        assertFalse(walk.stalled(600, 600));
        assertTrue(walk.stalled(601, 600));
    }

    @Test
    void walkProgressResetsOnGettingCloser() {
        WalkProgress walk = new WalkProgress();
        walk.reset(0, 20);
        walk.update(500, 18);
        assertFalse(walk.stalled(1100, 600));
        assertTrue(walk.stalled(1101, 600));
    }

    @Test
    void wanderingOrTinyGainsAreNotProgress() {
        WalkProgress walk = new WalkProgress();
        walk.reset(0, 20);
        walk.update(100, 25);
        walk.update(200, 19.5);
        assertTrue(walk.stalled(601, 600));
        assertEquals(601, walk.idleTicks(601));
    }
}
