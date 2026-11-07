package com.porter.qwerte.pearl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ColumnAndThrowTest {
    private static final String DIM = "ResourceKey[minecraft:dimension / minecraft:overworld]";
    /** Saved from the rim, 20 blocks above the water. */
    private static final Chamber RIM = new Chamber("2b2t.org", DIM, 100, 80, 100, "Rim");

    private static PullDetector detector() {
        return new PullDetector(() -> new PullDetector.Config(3, 100, 8, 200));
    }

    @Test
    void columnMatchesLandingBelowTheSavedBlock() {
        assertTrue(RIM.inColumn(100.5, 66.0, 100.5, 3));
        assertTrue(RIM.inColumn(100.5, 64.0, 100.5, 3));
        assertFalse(RIM.inColumn(100.5, 63.0, 100.5, 3), "more than 16 below");
        assertTrue(RIM.inColumn(100.5, 84.0, 100.5, 3));
        assertFalse(RIM.inColumn(100.5, 85.0, 100.5, 3), "more than 4 above");
    }

    @Test
    void columnNeedsTheSidewaysRadius() {
        assertTrue(RIM.inColumn(103.5, 70, 100.5, 3));
        assertFalse(RIM.inColumn(104.0, 70, 100.5, 3));
    }

    @Test
    void pullFromTheRimSavedChamberTriggersAtTheWater() {
        PullDetector d = detector();
        d.update(1, 0, 70, 0, "2b2t.org", DIM, List.of(RIM));
        PullDetector.Result r = d.inspect(2, 100.5, 66.0, 100.5, "2b2t.org", DIM, List.of(RIM));
        assertEquals(PullDetector.Reason.PULL, r.reason());
        assertEquals(RIM, r.chamber());
    }

    @Test
    void connectPrefixIsTheSameServer() {
        assertTrue(Chamber.sameServer("connect.2b2t.org", "2b2t.org"));
        assertTrue(Chamber.sameServer("2B2T.org.:25565", "connect.2b2t.org"));
        assertFalse(Chamber.sameServer("2b2t.org", "2b2t.net"));
        PullDetector d = detector();
        d.update(1, 0, 70, 0, "connect.2b2t.org", DIM, List.of(RIM));
        assertEquals(RIM, d.update(2, 100.5, 66.0, 100.5, "connect.2b2t.org", DIM, List.of(RIM)));
    }

    @Test
    void mismatchedServerReportsNoChamberButNamesTheNearest() {
        PullDetector d = detector();
        d.update(1, 0, 70, 0, "other.net", DIM, List.of(RIM));
        PullDetector.Result r = d.inspect(2, 100.5, 66.0, 100.5, "other.net", DIM, List.of(RIM));
        assertEquals(PullDetector.Reason.NO_CHAMBER, r.reason());
        assertEquals(RIM, r.nearest());
        assertFalse(r.nearestServerOk());
        assertTrue(r.nearestDimensionOk());
    }

    @Test
    void ownThrowAndCooldownAreReportedWithTheChamber() {
        PullDetector d = detector();
        d.update(1, 0, 70, 0, "2b2t.org", DIM, List.of(RIM));
        d.ownThrow(1);
        PullDetector.Result r = d.inspect(50, 100.5, 66.0, 100.5, "2b2t.org", DIM, List.of(RIM));
        assertEquals(PullDetector.Reason.OWN_THROW, r.reason());
        assertEquals(RIM, r.chamber());
        assertEquals(49, r.ownThrowAgo());

        PullDetector e = detector();
        e.update(1, 0, 70, 0, "2b2t.org", DIM, List.of(RIM));
        e.markTriggered(1);
        assertEquals(PullDetector.Reason.COOLDOWN, e.inspect(50, 100.5, 66.0, 100.5, "2b2t.org", DIM, List.of(RIM)).reason());
        assertNull(e.update(51, 0, 70, 0, "2b2t.org", DIM, List.of(RIM)));
    }

    @Test
    void anOldFallingStasisPearlIsNotAThrow() {
        // Far from the eyes, fast, but loaded in or thrown long ago
        assertFalse(OwnThrow.isFreshThrow(true, true, 12, 1.0, 0));
        assertFalse(OwnThrow.isFreshThrow(true, true, 1, 1.0, 40));
        // Seen before
        assertFalse(OwnThrow.isFreshThrow(true, false, 1, 1.0, 1));
        // Resting or sinking in water
        assertFalse(OwnThrow.isFreshThrow(true, true, 1, 0.01, 0));
        // Someone else's
        assertFalse(OwnThrow.isFreshThrow(false, true, 1, 2.25, 0));
    }

    @Test
    void aFreshFastPearlNextToTheEyesIsAThrow() {
        assertTrue(OwnThrow.isFreshThrow(true, true, 1.5, 2.25, 0));
        assertTrue(OwnThrow.isFreshThrow(true, true, 3.0, 2.25, 2));
    }

    @Test
    void throttleLetsAKeyThroughOncePerInterval() {
        DebugThrottle t = new DebugThrottle(20);
        assertTrue(t.allow(100, "a"));
        assertFalse(t.allow(101, "a"));
        assertFalse(t.allow(119, "a"));
        assertTrue(t.allow(120, "a"));
        assertTrue(t.allow(101, "b"), "other keys are independent");
        t.clear();
        assertTrue(t.allow(121, "a"));
    }
}
