package com.porter.qwerte.printer.move;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.porter.qwerte.printer.move.MovePolicy.Mode;
import com.porter.qwerte.printer.move.MovePolicy.Obs;
import com.porter.qwerte.printer.move.MovePolicy.Way;
import org.junit.jupiter.api.Test;

class MovePolicyTest {
    private static Obs obs(int tick, double distance, int failures, boolean flight, boolean grounded) {
        return new Obs(tick, distance, failures, flight, grounded);
    }

    @Test
    void aLaterWalkStartsItsTrackAgainAfterAGap() {
        MovePolicy p = new MovePolicy(Mode.STAND_THEN_FLY);
        // a first walk that gets nowhere for 50 ticks, then 400 ticks of placing (no observations)
        for (int t = 0; t < 50; t++) assertEquals(Way.WALK, p.update(obs(t, 20, 0, true, true)));
        // the next walk starts at tick 450: it must not fly at once, only after its own STALL_TICKS
        int at = -1;
        for (int t = 450; t < 800 && at < 0; t++) {
            if (p.update(obs(t, 20, 0, true, true)) == Way.FLY) at = t;
        }
        assertEquals(450 + MovePolicy.STALL_TICKS, at);
    }

    @Test
    void aShortGapKeepsTheTrack() {
        MovePolicy p = new MovePolicy(Mode.STAND_THEN_FLY);
        int at = -1;
        for (int t = 0; t < 400 && at < 0; t += 3) { // every third tick: a gap of 3, not a restart
            if (p.update(obs(t, 20, 0, true, true)) == Way.FLY) at = t;
        }
        assertEquals(102, at);
    }

    @Test
    void standNeverFlies() {
        MovePolicy p = new MovePolicy(Mode.STAND);
        for (int t = 0; t < 1000; t++) assertEquals(Way.WALK, p.update(obs(t, 30, t, true, true)));
    }

    @Test
    void flyFliesWhenAvailableOtherwiseWalks() {
        MovePolicy p = new MovePolicy(Mode.FLY);
        assertEquals(Way.FLY, p.update(obs(1, 10, 0, true, true)));
        assertEquals(Way.WALK, p.update(obs(2, 10, 0, false, true)));
        assertEquals(Way.FLY, p.update(obs(3, 10, 0, true, false)));
    }

    @Test
    void thenFlyKeepsWalkingWhileItMakesProgress() {
        MovePolicy p = new MovePolicy(Mode.STAND_THEN_FLY);
        double d = 40;
        for (int t = 0; t < 600; t++) {
            if (t % 20 == 0) d -= 1;
            assertEquals(Way.WALK, p.update(obs(t, d, 0, true, true)), "tick " + t);
        }
    }

    @Test
    void thenFlyFliesAfterAStall() {
        MovePolicy p = new MovePolicy(Mode.STAND_THEN_FLY);
        Way w = Way.WALK;
        int at = -1;
        for (int t = 0; t < 300 && w == Way.WALK; t++) {
            w = p.update(obs(t, 20, 0, true, true));
            if (w == Way.FLY) at = t;
        }
        assertEquals(MovePolicy.STALL_TICKS, at);
    }

    @Test
    void thenFlyFliesAfterRepeatedNoPath() {
        MovePolicy p = new MovePolicy(Mode.STAND_THEN_FLY);
        assertEquals(Way.WALK, p.update(obs(0, 20, 0, true, true)));
        assertEquals(Way.WALK, p.update(obs(5, 20, 1, true, true)));
        assertEquals(Way.FLY, p.update(obs(10, 20, 2, true, true)));
    }

    @Test
    void thenFlyWithoutFlightStaysOnFoot() {
        MovePolicy p = new MovePolicy(Mode.STAND_THEN_FLY);
        for (int t = 0; t < 500; t++) assertEquals(Way.WALK, p.update(obs(t, 20, t, false, true)));
    }

    @Test
    void progressResetsTheStallAndFailureCount() {
        MovePolicy p = new MovePolicy(Mode.STAND_THEN_FLY);
        p.update(obs(0, 20, 0, true, true));
        p.update(obs(5, 20, 1, true, true));
        // Progress: the earlier failure no longer counts
        assertEquals(Way.WALK, p.update(obs(6, 18, 1, true, true)));
        assertEquals(Way.WALK, p.update(obs(7, 18, 2, true, true)));
        assertEquals(Way.FLY, p.update(obs(8, 18, 3, true, true)));
    }

    @Test
    void aJumpInTheTargetStartsTheTrackAgain() {
        MovePolicy p = new MovePolicy(Mode.STAND_THEN_FLY);
        for (int t = 0; t < 90; t++) p.update(obs(t, 5, 0, true, true));
        // New target far away: the 90 ticks spent on the old one don't count
        assertEquals(Way.WALK, p.update(obs(90, 30, 0, true, true)));
        for (int t = 91; t < 190; t++) assertEquals(Way.WALK, p.update(obs(t, 30, 0, true, true)), "tick " + t);
        assertEquals(Way.FLY, p.update(obs(190, 30, 0, true, true)));
    }

    /** Puts the policy into flight at the given tick. */
    private static MovePolicy flying(int tick) {
        MovePolicy p = new MovePolicy(Mode.STAND_THEN_FLY);
        // Observed every tick, as the Printer does while it walks (a gap of more than a few ticks starts the track again)
        for (int t = tick - MovePolicy.STALL_TICKS; t < tick; t++) p.update(obs(t, 20, 0, true, true));
        assertEquals(Way.FLY, p.update(obs(tick, 20, 0, true, false)));
        return p;
    }

    @Test
    void backToWalkingNeedsTheGroundAndTheBackOff() {
        MovePolicy p = flying(1000);
        // In the air: never, however long
        for (int t = 1001; t < 2200; t++) assertEquals(Way.FLY, p.update(obs(t, 20, 0, true, false)), "tick " + t);
        // On the ground, but the ground time has to pass first
        assertEquals(Way.FLY, p.update(obs(2200, 20, 0, true, true)));
        assertEquals(Way.FLY, p.update(obs(2200 + MovePolicy.GROUND_TICKS - 1, 20, 0, true, true)));
        assertEquals(Way.WALK, p.update(obs(2200 + MovePolicy.GROUND_TICKS, 20, 0, true, true)));
    }

    @Test
    void touchingTheGroundForAMomentIsNotEnough() {
        MovePolicy p = flying(1000);
        int t = 1000 + MovePolicy.FIRST_BACKOFF;
        for (int i = 0; i < 10; i++) {
            // Down for 10 ticks, then up again: the ground time restarts
            for (int k = 0; k < 10; k++) assertEquals(Way.FLY, p.update(obs(t++, 20, 0, true, true)));
            assertEquals(Way.FLY, p.update(obs(t++, 20, 0, true, false)));
        }
    }

    @Test
    void notBeforeTheBackOffRunsOut() {
        MovePolicy p = flying(1000);
        for (int t = 1001; t < 1000 + MovePolicy.FIRST_BACKOFF; t++) assertEquals(Way.FLY, p.update(obs(t, 20, 0, true, true)), "tick " + t);
    }

    @Test
    void aFailedTryDoublesTheBackOff() {
        MovePolicy p = flying(1000);
        int t = 1000 + MovePolicy.FIRST_BACKOFF + MovePolicy.GROUND_TICKS;
        int stand = 1000 + MovePolicy.FIRST_BACKOFF;
        Way w = Way.FLY;
        for (int i = stand; i <= t; i++) w = p.update(obs(i, 20, 0, true, true));
        assertEquals(Way.WALK, w);
        // The walk goes nowhere: back to flying after the stall, and the next try waits twice as long
        int back = t + MovePolicy.STALL_TICKS;
        for (int i = t + 1; i < back; i++) assertEquals(Way.WALK, p.update(obs(i, 20, 0, true, true)));
        assertEquals(Way.FLY, p.update(obs(back, 20, 0, true, true)));
        for (int i = back + 1; i < back + 2 * MovePolicy.FIRST_BACKOFF; i++) assertEquals(Way.FLY, p.update(obs(i, 20, 0, true, true)), "tick " + i);
        Way later = Way.FLY;
        for (int i = back + 2 * MovePolicy.FIRST_BACKOFF; i < back + 2 * MovePolicy.FIRST_BACKOFF + 40; i++) later = p.update(obs(i, 20, 0, true, true));
        assertEquals(Way.WALK, later);
    }

    @Test
    void changingTheModeStartsOver() {
        MovePolicy p = flying(1000);
        p.setMode(Mode.STAND);
        assertEquals(Way.WALK, p.update(obs(1001, 20, 0, true, false)));
        p.setMode(Mode.STAND_THEN_FLY);
        assertEquals(Way.WALK, p.update(obs(1002, 20, 0, true, false)));
    }
}
