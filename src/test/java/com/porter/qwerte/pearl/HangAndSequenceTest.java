package com.porter.qwerte.pearl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.porter.qwerte.pearl.Sequence.Obs;
import com.porter.qwerte.pearl.Sequence.Phase;
import org.junit.jupiter.api.Test;

class HangAndSequenceTest {
    @Test
    void sneakEdgePositionFits() {
        // Sneaking stops with the middle 0.25 to 0.3 past the edge, so 0.2 to 0.25 from the shaft's middle line
        assertTrue(Hang.fits(0.5 + 0.2, 0.5, 0.5, 0.5));
        assertTrue(Hang.fits(0.5 - 0.25, 0.5 + 0.1, 0.5, 0.5));
    }

    @Test
    void onTheEdgeLineDoesNotFit() {
        assertFalse(Hang.fits(1.0, 0.5, 0.5, 0.5));
        assertFalse(Hang.fits(0.5, 0.0, 0.5, 0.5));
    }

    @Test
    void hangSpotFitsForEveryDirection() {
        for (int[] d : new int[][] { { 0, -1 }, { 1, 0 }, { 0, 1 }, { -1, 0 } }) {
            double[] p = Hang.hangSpot(10.5, 20.5, d[0], d[1]);
            assertTrue(Hang.fits(p[0], p[1], 10.5, 20.5));
        }
    }

    @Test
    void yawFollowsMinecraftsConvention() {
        assertEquals(0, Hang.yawToward(0, 0, 0, 1), 1e-9);
        assertEquals(-90, Hang.yawToward(0, 0, 1, 0), 1e-9);
        assertEquals(90, Hang.yawToward(0, 0, -1, 0), 1e-9);
        assertEquals(180, Math.abs(Hang.yawToward(0, 0, 0, -1)), 1e-9);
    }

    @Test
    void offShaft() {
        assertFalse(Hang.offShaft(10.5, 10.5, 10.5, 10.5, 1, 0));
        assertFalse(Hang.offShaft(11.2, 10.5, 10.5, 10.5, 1, 0));
        assertTrue(Hang.offShaft(11.4, 10.5, 10.5, 10.5, 1, 0));
        assertTrue(Hang.offShaft(10.5, 9.1, 10.5, 10.5, 0, -1));
    }

    @Test
    void pearlMustBeBelowATrapdoorBeforeItCloses() {
        assertFalse(Hang.pearlBelow(63.5, 63));
        assertFalse(Hang.pearlBelow(63.0, 63));
        assertTrue(Hang.pearlBelow(62.5, 63));
    }

    @Test
    void centredThrowGoesStraightDown() {
        Hang.Aim aim = Hang.aim(10.5, 10.5, 10.5, 10.5, 66.2, 62.89);
        assertEquals(90, aim.pitch(), 1e-9);
        assertTrue(Hang.pathFits(aim, 10.5, 10.5));
    }

    @Test
    void offsetThrowLeansAndLandsInTheMiddle() {
        // A real hanging offset: 0.27 east of the middle
        double px = 10.5 + 0.27;
        Hang.Aim aim = Hang.aim(px, 10.5, 10.5, 10.5, 66.2, 62.89);
        assertTrue(aim.pitch() < 90 && aim.pitch() > 80);
        double[] end = aim.path()[aim.path().length - 1];
        assertEquals(10.5, end[0], 0.01);
        assertEquals(10.5, end[2], 0.01);
        assertEquals(62.89, end[1], 1e-9);
        // It moves towards the middle the whole way
        for (int i = 1; i < aim.path().length; i++) assertTrue(Math.abs(aim.path()[i][0] - 10.5) <= Math.abs(aim.path()[i - 1][0] - 10.5) + 1e-9);
        assertTrue(Hang.pathFits(aim, 10.5, 10.5));
    }

    @Test
    void flightFollowsTheGamesOrderGravityThenDragThenMove() {
        // Straight down from eyes at 100.1 (the pearl leaves at 100.0), water surface at 96.0. By hand:
        // vy1 = (-1.5 - 0.03) * 0.99 = -1.5147, y1 = 98.4853
        // vy2 = (-1.5147 - 0.03) * 0.99 = -1.529253, y2 = 96.956047
        // vy3 = (-1.529253 - 0.03) * 0.99 = -1.54366047, y3 = 95.41238653, below the surface
        Hang.Aim aim = Hang.aim(10.5, 10.5, 10.5, 10.5, 100.1, 96.0);
        assertEquals(3, aim.ticks());
        assertEquals(4, aim.path().length);
        assertEquals(100.0, aim.path()[0][1], 1e-9);
        assertEquals(98.4853, aim.path()[1][1], 1e-6);
        assertEquals(96.956047, aim.path()[2][1], 1e-6);
        assertEquals(96.0, aim.path()[3][1], 1e-9);
    }

    @Test
    void aVeryLongFallIsRefused() {
        // The spread grows with every tick of flight until nothing is left of the shaft's width
        Hang.Aim aim = Hang.aim(10.5, 10.5, 10.5, 10.5, 140.0, 62.89);
        assertFalse(Hang.pathFits(aim, 10.5, 10.5));
    }

    @Test
    void yawOfTheAimFacesTheMiddle() {
        Hang.Aim aim = Hang.aim(11.0, 10.5, 10.5, 10.5, 66.2, 62.89);
        assertEquals(90, aim.yaw(), 1e-6);
    }

    @Test
    void aStartPointOnTheWallDoesNotFit() {
        // On the edge line, as when an open trapdoor plate stops the player
        Hang.Aim aim = Hang.aim(11.0, 10.5, 10.5, 10.5, 66.2, 62.89);
        assertFalse(Hang.pathFits(aim, 10.5, 10.5));
    }

    @Test
    void noFallOrTooWideIsNull() {
        assertNull(Hang.aim(10.5, 10.5, 10.5, 10.5, 62.9, 62.89));
        assertNull(Hang.aim(13.5, 10.5, 10.5, 10.5, 66.2, 62.89));
    }

    @Test
    void driftGrowsWithTheFall() {
        assertTrue(Hang.drift(6) > Hang.drift(2));
        assertTrue(Hang.drift(0) < 0.05);
    }

    // Pending

    @Test
    void pendingGoesAsSoonAsNothingBlocks() {
        Pending p = new Pending();
        assertEquals(Pending.Outcome.WAIT, p.step(Pending.Block.UNLOADED, null));
        assertEquals(Pending.Outcome.GO, p.step(Pending.Block.NONE, null));
    }

    @Test
    void pendingWaitsForTransientCausesThenGivesUp() {
        Pending p = new Pending();
        Pending.Outcome o = Pending.Outcome.WAIT;
        for (int i = 1; i < Pending.LIMIT; i++) {
            o = p.step(i % 2 == 0 ? Pending.Block.SCREEN : Pending.Block.DANGER, "x");
            assertEquals(Pending.Outcome.WAIT, o);
        }
        assertEquals(Pending.Outcome.GIVE_UP, p.step(Pending.Block.UNLOADED, null));
        assertEquals("the chamber has not loaded", p.reason());
    }

    @Test
    void noPearlIsFinal() {
        Pending p = new Pending();
        assertEquals(Pending.Outcome.GIVE_UP, p.step(Pending.Block.NO_PEARL, null));
        assertEquals("no ender pearls", p.reason());
    }

    @Test
    void badShaftIsRetriedBrieflyOnly() {
        Pending p = new Pending();
        for (int i = 0; i < Pending.SHAFT_RETRIES; i++) assertEquals(Pending.Outcome.WAIT, p.step(Pending.Block.BAD_SHAFT, "no water found in the shaft"));
        assertEquals(Pending.Outcome.GIVE_UP, p.step(Pending.Block.BAD_SHAFT, "no water found in the shaft"));
        assertEquals("no water found in the shaft", p.reason());
    }

    @Test
    void shaftRetriesCountOnlyTheTicksSpentScanning() {
        Pending p = new Pending();
        for (int i = 0; i < 60; i++) assertEquals(Pending.Outcome.WAIT, p.step(Pending.Block.UNLOADED, null));
        for (int i = 0; i < Pending.SHAFT_RETRIES; i++) assertEquals(Pending.Outcome.WAIT, p.step(Pending.Block.BAD_SHAFT, "no water found in the shaft"));
        assertEquals(Pending.Outcome.GO, p.step(Pending.Block.NONE, null));
    }

    // Sequence

    private static Obs obs(boolean onRim, boolean still, boolean fits, boolean thrown, boolean settled, boolean coverNeeded, boolean coverDone) {
        return new Obs(onRim, still, fits, thrown, settled, coverNeeded, coverDone);
    }

    private static final Obs NOTHING = obs(false, false, false, false, false, true, false);

    @Test
    void fullRunWithCover() {
        Sequence s = new Sequence();
        assertEquals(Phase.EXIT, s.advance(NOTHING));
        assertEquals(Phase.HANG, s.advance(obs(true, false, false, false, false, true, false)));
        for (int i = 0; i < Sequence.MIN_HANG - 1; i++) s.advance(obs(true, true, true, false, false, true, false));
        assertEquals(Phase.HANG, s.phase());
        assertEquals(Phase.THROW, s.advance(obs(true, true, true, false, false, true, false)));
        assertEquals(Phase.THROW, s.advance(NOTHING));
        assertEquals(Phase.WAIT_PEARL, s.advance(obs(true, true, true, true, false, true, false)));
        assertEquals(Phase.COVER, s.advance(obs(true, true, true, true, true, true, false)));
        assertEquals(Phase.DONE, s.advance(obs(true, true, true, true, true, true, true)));
        assertTrue(s.finished());
        assertNull(s.failure());
    }

    @Test
    void noCoverSkipsIt() {
        Sequence s = new Sequence();
        s.advance(obs(true, false, false, false, false, false, false));
        for (int i = 0; i < Sequence.MIN_HANG; i++) s.advance(obs(true, true, true, false, false, false, false));
        s.advance(obs(true, true, true, true, false, false, false));
        assertEquals(Phase.DONE, s.advance(obs(true, true, true, true, true, false, false)));
    }

    @Test
    void standstillTooEarlyIsIgnored() {
        Sequence s = new Sequence();
        s.advance(obs(true, false, false, false, false, true, false));
        assertEquals(Phase.HANG, s.advance(obs(true, true, true, false, false, true, false)));
    }

    @Test
    void aMomentaryMisfitIsRetried() {
        Sequence s = new Sequence();
        s.advance(obs(true, false, false, false, false, true, false));
        for (int i = 0; i < Sequence.MIN_HANG; i++) s.advance(obs(true, true, false, false, false, true, false));
        assertEquals(Phase.HANG, s.phase());
        assertEquals(Phase.THROW, s.advance(obs(true, true, true, false, false, true, false)));
    }

    @Test
    void notFittingFails() {
        Sequence s = new Sequence();
        s.advance(obs(true, false, false, false, false, true, false));
        Phase p = Phase.HANG;
        for (int i = 0; i < Sequence.MIN_HANG + Sequence.FIT_RETRIES; i++) p = s.advance(obs(true, true, false, false, false, true, false));
        assertEquals(Phase.FAILED, p);
        assertNotNull(s.failure());
    }

    @Test
    void everyPhaseTimesOut() {
        Sequence s = new Sequence();
        for (int i = 0; i <= Sequence.EXIT_LIMIT; i++) s.advance(NOTHING);
        assertEquals(Phase.FAILED, s.phase());
        assertEquals(Phase.FAILED, s.advance(NOTHING));

        s = new Sequence();
        s.advance(obs(true, false, false, false, false, true, false));
        for (int i = 0; i <= Sequence.HANG_LIMIT; i++) s.advance(NOTHING);
        assertEquals(Phase.FAILED, s.phase());
    }

    @Test
    void pearlNeverSeenFails() {
        Sequence s = new Sequence();
        s.advance(obs(true, false, false, false, false, true, false));
        for (int i = 0; i < Sequence.MIN_HANG; i++) s.advance(obs(true, true, true, false, false, true, false));
        s.advance(obs(true, true, true, true, false, true, false));
        assertEquals(Phase.WAIT_PEARL, s.phase());
        for (int i = 0; i <= Sequence.WAIT_LIMIT; i++) s.advance(NOTHING);
        assertEquals("the thrown pearl was not seen in the shaft", s.failure());
    }
}
