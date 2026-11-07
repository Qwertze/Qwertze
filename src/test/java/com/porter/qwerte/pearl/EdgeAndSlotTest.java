package com.porter.qwerte.pearl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.porter.qwerte.pearl.EdgeApproach.Surface;
import com.porter.qwerte.pearl.ShaftScan.Kind;
import com.porter.qwerte.pearl.SlotWatch.Verdict;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EdgeAndSlotTest {
    /** The real geometry: water top -43, stasis trapdoor -42, cover -41, rim floor -40, one rim at offset (0, 1). */
    private static final double CX = 100.5, CZ = 200.5;

    @Test
    void theEdgeTargetIsOnTheRimAxisAboutAQuarterBlockFromTheMiddle() {
        double[] t = EdgeApproach.target(CX, CZ, 0, 1);
        assertEquals(CX, t[0], 1e-9, "no sideways offset");
        assertEquals(CZ + 0.27, t[1], 1e-9);
        double[] w = EdgeApproach.target(CX, CZ, -1, 0);
        assertEquals(CX - 0.27, w[0], 1e-9);
        assertEquals(CZ, w[1], 1e-9);
        assertEquals(0.27, EdgeApproach.TARGET_ALONG, 1e-9);
    }

    @Test
    void alongIsMeasuredTowardsTheRim() {
        // On the rim block, 1.0 from the middle; and at the target
        assertEquals(1.0, EdgeApproach.along(CX, CZ + 1.0, CX, CZ, 0, 1), 1e-9);
        assertEquals(0.27, EdgeApproach.along(CX + 0.05, CZ + 0.27, CX, CZ, 0, 1), 1e-9);
        // The other side of the shaft is negative along this rim
        assertTrue(EdgeApproach.along(CX, CZ - 0.5, CX, CZ, 0, 1) < 0);
        assertEquals(0.4, EdgeApproach.along(CX - 0.4, CZ, CX, CZ, -1, 0), 1e-9);
    }

    @Test
    void theWalkBackStopsAtTheTargetWithoutOvershooting() {
        // A sneaking walk of 0.065 a tick from the rim block, straight along the axis
        double along = 1.0, v = -0.065;
        int ticks = 0;
        while (EdgeApproach.shouldWalk(along, v, EdgeApproach.TARGET_ALONG) && ticks < 100) {
            along += v;
            ticks++;
        }
        assertTrue(ticks < 100);
        assertTrue(along <= EdgeApproach.TARGET_ALONG + 0.09, "stopped near the target: " + along);
        assertTrue(along >= EdgeApproach.TARGET_ALONG - 0.07, "not past it by more than a step: " + along);
        // Already at the target: no step at all; and standing still on the rim block: walk
        assertFalse(EdgeApproach.shouldWalk(0.25, 0, EdgeApproach.TARGET_ALONG));
        assertTrue(EdgeApproach.shouldWalk(1.0, 0, EdgeApproach.TARGET_ALONG));
    }

    @Test
    void neverWalkOverAClosedTrapdoor() {
        // The cover at -41 is the block under the rim floor (-40); it has to be open
        Map<Integer, Kind> shaft = new HashMap<>();
        shaft.put(-43, Kind.WATER);
        shaft.put(-42, Kind.TRAPDOOR_OPEN);
        shaft.put(-41, Kind.TRAPDOOR_OPEN);
        shaft.put(-40, Kind.AIR);
        int floorY = -40;
        assertEquals(Surface.OK, EdgeApproach.checkAhead(shaft.get(floorY - 1), shaft.get(floorY)));

        shaft.put(-41, Kind.TRAPDOOR_CLOSED);
        assertEquals(Surface.CLOSED_COVER, EdgeApproach.checkAhead(shaft.get(floorY - 1), shaft.get(floorY)));

        // Closed at the level of the feet, a slab to step on as well
        shaft.put(-41, Kind.TRAPDOOR_OPEN);
        shaft.put(-40, Kind.TRAPDOOR_CLOSED);
        assertEquals(Surface.CLOSED_COVER, EdgeApproach.checkAhead(shaft.get(floorY - 1), shaft.get(floorY)));

        // Something solid in the opening
        shaft.put(-40, Kind.AIR);
        shaft.put(-41, Kind.SOLID);
        assertEquals(Surface.BLOCKED, EdgeApproach.checkAhead(shaft.get(floorY - 1), shaft.get(floorY)));
        assertEquals(Surface.OK, EdgeApproach.checkAhead(Kind.WATER, Kind.AIR));
    }

    @Test
    void slotHijackDecision() {
        // Held slot moved without us
        assertEquals(Verdict.HELD_SLOT_CHANGED, SlotWatch.judge(2, 5, false, false));
        assertEquals(Verdict.HELD_SLOT_CHANGED, SlotWatch.judge(2, 5, true, true));
        // Still ours and the pearl is there
        assertEquals(Verdict.OK, SlotWatch.judge(2, 2, true, false));
        // Ours, no pearl: the server answered (inventory sent again) or nothing did
        assertEquals(Verdict.REVERTED_BY_SERVER, SlotWatch.judge(2, 2, false, true));
        assertEquals(Verdict.SLOT_CHANGED, SlotWatch.judge(2, 2, false, false));
        assertEquals("another mod changed your held slot (slot 3 -> 6)", SlotWatch.describe(Verdict.HELD_SLOT_CHANGED, 2, 5));
        assertTrue(SlotWatch.describe(Verdict.REVERTED_BY_SERVER, 2, 2).contains("server"));
    }

    @Test
    void theRimIsSavedWithTheChamberAndOldStringsStillLoad() {
        Chamber c = new Chamber("srv", "dim", 1, -43, 2, "farm", -43, List.of(-42, -41), 0, 1, -40);
        assertTrue(c.hasRim());
        Chamber back = Chamber.decode(c.encode());
        assertEquals(c, back);
        assertEquals(1, back.rimDz());
        assertEquals(-40, back.rimFloorY());
        // dev.10.x strings: shaft but no rim; and no shaft at all
        Chamber noRim = Chamber.decode("srv|dim|1|-43|2|farm|@shaft=-43;-42,-41");
        assertNotNull(noRim);
        assertFalse(noRim.hasRim());
        assertTrue(noRim.hasShaft());
        assertEquals(List.of(-42, -41), noRim.trapYs());
        assertFalse(Chamber.decode("srv|dim|1|-43|2|farm").hasShaft());
        // A damaged rim keeps the shaft
        Chamber damaged = Chamber.decode("srv|dim|1|-43|2|farm|@shaft=-43;-42,-41;x,y");
        assertTrue(damaged.hasShaft());
        assertFalse(damaged.hasRim());
        // A name with the separator still round-trips with a rim
        Chamber piped = new Chamber("srv", "dim", 1, -43, 2, "a|b", -43, List.of(-41), 1, 0, -40);
        assertEquals(piped, Chamber.decode(piped.encode()));
    }
}
