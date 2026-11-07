package com.porter.qwerte.pearl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.Test;

class PullDetectorTest {
    private static final String DIM = "ResourceKey[minecraft:dimension / minecraft:overworld]";
    private static final Chamber A = new Chamber("srv", DIM, 100, 60, 100, "A");
    private static final Chamber B = new Chamber("srv", DIM, 102, 60, 100, "B");

    private static PullDetector detector() {
        return new PullDetector(() -> new PullDetector.Config(3, 100, 8, 200));
    }

    @Test
    void pullIntoAChamberTriggers() {
        PullDetector d = detector();
        assertNull(d.update(1, 0, 70, 0, "srv", DIM, List.of(A)));
        assertEquals(A, d.update(2, 100.5, 60.5, 100.5, "srv", DIM, List.of(A)));
    }

    @Test
    void smallJumpsAreLagBacks() {
        PullDetector d = detector();
        d.update(1, 97, 60, 100, "srv", DIM, List.of(A));
        assertNull(d.update(2, 100.5, 60.5, 100.5, "srv", DIM, List.of(A)));
    }

    @Test
    void ownThrowWithinTheWindowBlocks() {
        PullDetector d = detector();
        d.update(1, 0, 70, 0, "srv", DIM, List.of(A));
        d.ownThrow(1);
        assertNull(d.update(50, 100.5, 60.5, 100.5, "srv", DIM, List.of(A)));
    }

    @Test
    void ownThrowOutsideTheWindowDoesNot() {
        PullDetector d = detector();
        d.update(1, 0, 70, 0, "srv", DIM, List.of(A));
        d.ownThrow(1);
        d.update(200, 0, 70, 0, "srv", DIM, List.of(A));
        assertNotNull(d.update(201, 100.5, 60.5, 100.5, "srv", DIM, List.of(A)));
    }

    @Test
    void arrivalFarFromEveryChamberIsIgnored() {
        PullDetector d = detector();
        d.update(1, 0, 70, 0, "srv", DIM, List.of(A));
        assertNull(d.update(2, 300, 70, 300, "srv", DIM, List.of(A)));
    }

    @Test
    void otherServerOrDimensionDoesNotMatch() {
        PullDetector d = detector();
        d.update(1, 0, 70, 0, "other", DIM, List.of(A));
        assertNull(d.update(2, 100.5, 60.5, 100.5, "other", DIM, List.of(A)));
        d.update(3, 0, 70, 0, "srv", "nether", List.of(A));
        assertNull(d.update(4, 100.5, 60.5, 100.5, "srv", "nether", List.of(A)));
    }

    @Test
    void worldChangeIsNotAJump() {
        PullDetector d = detector();
        d.update(1, 0, 70, 0, "srv", DIM, List.of(A));
        assertNull(d.update(2, 100.5, 60.5, 100.5, "srv", "nether", List.of(A)));
        d.reset();
        assertNull(d.update(3, 0, 70, 0, "srv", DIM, List.of(A)));
        d.reset();
        assertNull(d.update(4, 100.5, 60.5, 100.5, "srv", DIM, List.of(A)));
    }

    @Test
    void cooldownLetsOnePullTriggerOnce() {
        PullDetector d = detector();
        d.update(1, 0, 70, 0, "srv", DIM, List.of(A));
        assertNotNull(d.update(2, 100.5, 60.5, 100.5, "srv", DIM, List.of(A)));
        d.markTriggered(2);
        d.update(3, 0, 70, 0, "srv", DIM, List.of(A));
        assertNull(d.update(4, 100.5, 60.5, 100.5, "srv", DIM, List.of(A)));
        d.update(300, 0, 70, 0, "srv", DIM, List.of(A));
        assertNotNull(d.update(301, 100.5, 60.5, 100.5, "srv", DIM, List.of(A)));
    }

    @Test
    void nearestChamberWins() {
        PullDetector d = detector();
        d.update(1, 0, 70, 0, "srv", DIM, List.of(A, B));
        assertEquals(B, d.update(2, 102.4, 60.5, 100.5, "srv", DIM, List.of(A, B)));
    }

    @Test
    void aRefusedStartDoesNotUseUpTheCooldown() {
        PullDetector d = detector();
        d.update(1, 0, 70, 0, "srv", DIM, List.of(A));
        assertNotNull(d.update(2, 100.5, 60.5, 100.5, "srv", DIM, List.of(A)));
        // markTriggered was never called: the next pull still counts
        d.update(3, 0, 70, 0, "srv", DIM, List.of(A));
        assertNotNull(d.update(4, 100.5, 60.5, 100.5, "srv", DIM, List.of(A)));
    }

    @Test
    void anOwnPearlInFlightSuppressesUntilTheWindowAfterItLands() {
        PullDetector d = detector();
        d.update(1, 0, 70, 0, "srv", DIM, List.of(A));
        for (int t = 1; t <= 250; t++) d.ownThrow(t);
        d.update(250, 0, 70, 0, "srv", DIM, List.of(A));
        assertNull(d.update(300, 100.5, 60.5, 100.5, "srv", DIM, List.of(A)));
        d.update(340, 0, 70, 0, "srv", DIM, List.of(A));
        assertNull(d.update(350, 100.5, 60.5, 100.5, "srv", DIM, List.of(A)));
        d.update(351, 0, 70, 0, "srv", DIM, List.of(A));
        assertNotNull(d.update(352, 100.5, 60.5, 100.5, "srv", DIM, List.of(A)));
    }

    @Test
    void serverNamesAreCompared_normalised() {
        PullDetector d = detector();
        d.update(1, 0, 70, 0, "Srv.", DIM, List.of(A));
        assertEquals(A, d.update(2, 100.5, 60.5, 100.5, "SRV:25565", DIM, List.of(A)));
    }
}
