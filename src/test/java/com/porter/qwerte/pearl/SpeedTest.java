package com.porter.qwerte.pearl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SpeedTest {
    @Test
    void safeKeepsTheOldWaitsAndTheFasterModesDrop() {
        SpeedMode.Params safe = SpeedMode.Safe.params(), fast = SpeedMode.Fast.params(), max = SpeedMode.Max.params();
        assertEquals(3, safe.trapCooldown());
        assertEquals(3, safe.coverWait());
        assertEquals(6, safe.minHang());
        assertEquals(3, safe.stillTicks());
        assertEquals(15, safe.verifyTicks());
        assertTrue(safe.elytraBlocksClimb());
        assertFalse(safe.throwWhileClimbing() || safe.sprintSwim() || safe.parallelRestore());

        assertEquals(0, fast.trapCooldown());
        assertEquals(1, fast.trapClicksPerTick());
        assertEquals(0, fast.coverWait());
        assertTrue(fast.parallelRestore());
        assertFalse(fast.elytraBlocksClimb());

        assertEquals(2, max.trapClicksPerTick());
        assertEquals(2, max.coverClicksPerTick());
        assertTrue(max.verifyTicks() < fast.verifyTicks());
        assertTrue(max.minHang() <= fast.minHang() && fast.minHang() <= safe.minHang());
    }

    @Test
    void noModeThrowsOrSprintSwimsOnTheWayUpByDefault() {
        // The careful approach (rim, sneak to the edge, aimed throw) is the same in every mode; only the waits differ
        for (SpeedMode m : SpeedMode.values()) {
            assertFalse(m.params().throwWhileClimbing(), m + " throws while climbing");
            assertFalse(m.params().sprintSwim(), m + " sprint-swims");
        }
        assertEquals(SpeedMode.Fast, SpeedMode.valueOf("Fast"));
        assertTrue(SpeedMode.Fast.params().stillTicks() <= SpeedMode.Safe.params().stillTicks());
        assertTrue(SpeedMode.Max.params().stillTicks() <= SpeedMode.Fast.params().stillTicks());
    }

    @Test
    void timingSummaryHasTheStepAndTheTotal() {
        PhaseTimer t = new PhaseTimer(100, 1_000_000_000L);
        t.mark("pending", 101, 1_050_000_000L);
        t.mark("cover closed", 121, 2_050_000_000L);
        t.mark("verified", 136, 2_800_000_000L);
        List<String> lines = t.summary();
        assertEquals(3, lines.size());
        assertEquals("pending: +1 ticks (50 ms), 1 ticks (50 ms) after the pull", lines.get(0));
        assertEquals("cover closed: +20 ticks (1000 ms), 21 ticks (1050 ms) after the pull", lines.get(1));
        assertEquals("verified: +15 ticks (750 ms), 36 ticks (1800 ms) after the pull", lines.get(2));
        assertEquals(21, t.ticksTo("cover closed"));
        assertEquals(36, t.ticksTo("verified"));
        assertEquals(-1, t.ticksTo("nothing"));
        assertTrue(t.has("pending"));
    }

    /** A 1x1 shaft at (0.5, 0.5) with the water surface at y 60 and the eyes 8 blocks above. */
    private static final double CX = 0.5, CZ = 0.5, SURFACE = 60.89, EYE = 70;

    private static double[] landed(Hang.Aim aim) {
        return aim.path()[aim.path().length - 1];
    }

    @Test
    void aMovingThrowStillLandsInTheMiddle() {
        double[][] cases = {
            // x, z, vx, vz, vy
            { 0.5, 0.5, 0, 0, 0 },
            { 0.5, 0.5, 0.0, 0.0, 0.2 },
            { 0.35, 0.5, 0.05, 0, 0 },
            { 0.7, 0.45, -0.06, 0.02, 0.1 },
            { 0.55, 0.62, 0.0, -0.1, 0 },
        };
        for (double[] c : cases) {
            Hang.Aim aim = Hang.aimMoving(c[0], c[1], c[2], c[3], c[4], false, CX, CZ, EYE, SURFACE);
            assertNotNull(aim, "case " + java.util.Arrays.toString(c));
            double[] end = landed(aim);
            assertEquals(CX, end[0], 0.04, "x " + java.util.Arrays.toString(c));
            assertEquals(CZ, end[2], 0.04, "z " + java.util.Arrays.toString(c));
            assertEquals(SURFACE, end[1], 1e-6);
            assertTrue(Hang.pathFits(aim, CX, CZ), "fits " + java.util.Arrays.toString(c));
        }
    }

    @Test
    void aSpeedNoLeanCanCancelDoesNotFit() {
        // The throw itself is 1.5 a tick: a sideways speed above that can't be thrown back
        assertNull(Hang.aimMoving(0.5, 0.5, 1.7, 0, 0, true, CX, CZ, EYE, SURFACE));
        // A fast run that a backwards lean does cancel is a valid throw, and lands in the middle all the same
        Hang.Aim aim = Hang.aimMoving(0.5, 0.5, 0.6, 0, 0, true, CX, CZ, EYE, SURFACE);
        assertNotNull(aim);
        assertEquals(CX, landed(aim)[0], 0.04);
    }

    @Test
    void noThrowFromBelowTheWaterOrOutsideTheCell() {
        assertNull(Hang.aimMoving(0.5, 0.5, 0, 0, 0, false, CX, CZ, 60.5, SURFACE));
        assertNull(Hang.aimMoving(1.2, 0.5, 0, 0, 0, false, CX, CZ, EYE, SURFACE));
    }

    @Test
    void thePlayersUpwardSpeedIsOnlyCarriedInTheAir() {
        Hang.Aim air = Hang.aimMoving(0.5, 0.5, 0, 0, 0.3, false, CX, CZ, EYE, SURFACE);
        Hang.Aim ground = Hang.aimMoving(0.5, 0.5, 0, 0, 0.3, true, CX, CZ, EYE, SURFACE);
        assertNotNull(air);
        assertNotNull(ground);
        assertTrue(air.ticks() >= ground.ticks(), "an upward speed makes the fall longer");
    }

    @Test
    void theCoverClosesOnlyOncePearlIsBelowItsTrapdoor() {
        assertFalse(Hang.pearlBelow(63.1, 63));
        assertFalse(Hang.pearlBelow(62.9, 63));
        assertTrue(Hang.pearlBelow(62.7, 63));
        // The lowest cover that was opened is the one that matters, and the stasis trapdoor is never in the plan
        TrapRoles r = TrapRoles.classify(57, List.of(58, 63, 64));
        assertEquals(List.of(63, 64), r.coverPlan(Set.of(64, 58, 63)));
    }

    @Test
    void aThrowOnTheWayUpSkipsTheHang() {
        Sequence s = new Sequence(SpeedMode.Fast.params().minHang());
        // On the rim with the pearl already thrown: straight to waiting for it
        assertEquals(Sequence.Phase.WAIT_PEARL, s.advance(new Sequence.Obs(true, false, false, true, false, true, false)));
        // Without a throw it is the old way
        Sequence t = new Sequence(SpeedMode.Fast.params().minHang());
        assertEquals(Sequence.Phase.HANG, t.advance(new Sequence.Obs(true, false, false, false, false, true, false)));
        // The hang is over after the mode's minimum
        for (int i = 0; i < 3; i++) t.advance(new Sequence.Obs(true, true, true, false, false, true, false));
        assertEquals(Sequence.Phase.THROW, t.phase());
    }
}
