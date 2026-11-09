package com.porter.qwerte.printer.place;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.porter.qwerte.printer.place.PlaceRecipe.Dir;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class LookWindowTest {
    private static final LookWindow.Trig T = LookWindow.Trig.JAVA;

    @Test
    void yawWrapsIntoMinus180To180() {
        assertEquals(0, LookWindow.wrap(0), 1e-9);
        assertEquals(-180, LookWindow.wrap(180), 1e-9);
        assertEquals(-180, LookWindow.wrap(-180), 1e-9);
        assertEquals(170, LookWindow.wrap(-190), 1e-9);
        assertEquals(-170, LookWindow.wrap(190), 1e-9);
        assertEquals(0, LookWindow.wrap(720), 1e-9);
        assertEquals(10, LookWindow.wrap(-710), 1e-9);
        assertEquals(20, LookWindow.diff(-170, 170), 1e-9);
        assertEquals(-20, LookWindow.diff(170, -170), 1e-9);
    }

    @Test
    void anArcAcrossPlusMinus180ContainsBothSidesOfTheSeam() {
        LookWindow north = LookWindow.quadrant(Dir.NORTH); // centred on 180 == -180
        assertTrue(north.contains(180, 0));
        assertTrue(north.contains(-180, 0));
        assertTrue(north.contains(179, 0));
        assertTrue(north.contains(-179, 0));
        assertTrue(north.contains(136.5, 0));
        assertTrue(north.contains(-136.5, 0));
        assertFalse(north.contains(130, 0));
        assertFalse(north.contains(-130, 0));
        assertFalse(north.contains(0, 0));
    }

    @Test
    void quadrantIsPlusMinus44AndMatchesGetHorizontalDirection() {
        for (Dir d : Dir.HORIZONTALS) {
            LookWindow w = LookWindow.quadrant(d);
            for (double off = -44; off <= 44; off += 0.5) {
                double yaw = LookWindow.wrap(d.yawCenter() + off);
                assertTrue(w.contains(yaw, 0), d + " " + off);
                assertEquals(d, Dir.fromYaw(yaw), d + " at " + yaw);
            }
            assertFalse(w.contains(d.yawCenter() + 46, 0));
            assertFalse(w.contains(d.yawCenter() - 46, 0));
        }
        // Direction.fromYRot: south at 0, west at 90, north at 180, east at 270 == -90, with 45 degree edges
        assertEquals(Dir.SOUTH, Dir.fromYaw(0));
        assertEquals(Dir.WEST, Dir.fromYaw(90));
        assertEquals(Dir.NORTH, Dir.fromYaw(180));
        assertEquals(Dir.NORTH, Dir.fromYaw(-180));
        assertEquals(Dir.EAST, Dir.fromYaw(-90));
        assertEquals(Dir.EAST, Dir.fromYaw(270));
        assertEquals(Dir.WEST, Dir.fromYaw(45));
        assertEquals(Dir.SOUTH, Dir.fromYaw(44.9));
        assertEquals(Dir.SOUTH, Dir.fromYaw(-45));
        assertEquals(Dir.EAST, Dir.fromYaw(-45.1));
    }

    @Test
    void intersectingAcrossTheSeamKeepsTheOverlapOnTheRightSide() {
        LookWindow a = new LookWindow(170, 30, -90, 90); // 140..200
        LookWindow b = new LookWindow(-170, 15, -90, 90); // 175..205 == -185..-155
        LookWindow x = a.intersect(b);
        assertNotNull(x);
        // the overlap is 175..200, i.e. centre 187.5 == -172.5, half 12.5
        assertEquals(-172.5, x.yawCenter(), 1e-9);
        assertEquals(12.5, x.yawHalf(), 1e-9);
        assertTrue(x.contains(-172.5, 0));
        assertTrue(x.contains(180, 0));
        assertFalse(x.contains(160, 0));
        assertFalse(x.contains(-150, 0));
        LookWindow y = b.intersect(a);
        assertEquals(x.yawCenter(), y.yawCenter(), 1e-9);
        assertEquals(x.yawHalf(), y.yawHalf(), 1e-9);
    }

    @Test
    void disjointArcsAndPitchRangesDoNotIntersect() {
        assertNull(LookWindow.quadrant(Dir.NORTH).intersect(LookWindow.quadrant(Dir.SOUTH)));
        assertNull(LookWindow.quadrant(Dir.NORTH).intersect(LookWindow.quadrant(Dir.EAST))); // 44 + 44 < 90
        assertNull(LookWindow.lookCone(Dir.UP).intersect(LookWindow.lookCone(Dir.DOWN)));
        assertNull(LookWindow.lookCone(Dir.UP).intersect(LookWindow.lookCone(Dir.NORTH)));
        assertFalse(LookWindow.quadrant(Dir.WEST).intersects(LookWindow.quadrant(Dir.EAST)));
    }

    @Test
    void fullCircleIntersectsAnythingAndAnyIsNeutral() {
        LookWindow q = LookWindow.quadrant(Dir.EAST);
        assertEquals(q, LookWindow.any().intersect(q));
        assertEquals(q, q.intersect(LookWindow.any()));
        LookWindow down = LookWindow.lookCone(Dir.DOWN).intersect(q);
        assertNotNull(down);
        assertEquals(-90, down.yawCenter(), 1e-9);
        assertEquals(44, down.yawHalf(), 1e-9);
        assertEquals(46, down.pitchMin(), 1e-9);
    }

    @Test
    void touchingEdgesAtExactlyOppositeCentresIntersectAtOnePoint() {
        LookWindow a = new LookWindow(90, 90, -90, 90);
        LookWindow b = new LookWindow(-90, 90, -90, 90);
        LookWindow x = a.intersect(b);
        assertNotNull(x);
        assertEquals(0, x.yawHalf(), 1e-6);
    }

    @Test
    void clampGoesToTheNearestEdgeTheShortWayAround() {
        LookWindow north = LookWindow.quadrant(Dir.NORTH);
        assertArrayEquals(new double[] { -136, 10 }, north.clamp(-120, 10), 1e-9);
        assertArrayEquals(new double[] { 136, 10 }, north.clamp(120, 10), 1e-9);
        assertArrayEquals(new double[] { 179, 10 }, north.clamp(179, 10), 1e-9);
        LookWindow cone = LookWindow.lookCone(Dir.NORTH);
        assertArrayEquals(new double[] { 180 - 360, 35 }, cone.clamp(-180, 80), 1e-9);
        assertArrayEquals(new double[] { -180, -35 }, cone.clamp(-180, -80), 1e-9);
    }

    @Test
    void windowsRejectNonsense() {
        assertThrows(IllegalArgumentException.class, () -> new LookWindow(0, -1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new LookWindow(0, 10, 10, -10));
        assertThrows(IllegalArgumentException.class, () -> new LookWindow(Double.NaN, 10, 0, 0));
    }

    @Test
    void samplesStayInsideTheWindow() {
        for (LookWindow w : List.of(LookWindow.any(), LookWindow.quadrant(Dir.NORTH), LookWindow.lookCone(Dir.UP), LookWindow.rotation16(8, 180))) {
            for (double[] s : w.samples()) assertTrue(w.contains(s[0], s[1]), w + " " + s[0] + "," + s[1]);
        }
    }

    // ----- rotation16: signs and banners use yaw + 180, skulls use yaw -----

    @Test
    void rotation16WindowsAreExactlyPlusMinus11AndLandOnTheirSegment() {
        for (int seg = 0; seg < 16; seg++) {
            LookWindow sign = LookWindow.rotation16(seg, 180);
            LookWindow skull = LookWindow.rotation16(seg, 0);
            for (double off = -11; off <= 11; off += 0.25) {
                float signYaw = (float) LookWindow.wrap(seg * 22.5 + 180 + off);
                float skullYaw = (float) LookWindow.wrap(seg * 22.5 + off);
                assertTrue(sign.contains(signYaw, 0));
                assertTrue(skull.contains(skullYaw, 0));
                assertEquals(seg, LookWindow.rotationSegment(signYaw + 180f), "sign seg " + seg + " off " + off);
                assertEquals(seg, LookWindow.rotationSegment(skullYaw), "skull seg " + seg + " off " + off);
            }
            assertFalse(sign.contains(seg * 22.5 + 180 + 12, 0));
            assertFalse(sign.contains(seg * 22.5 + 180 - 12, 0));
        }
    }

    @Test
    void rotationSegmentMatchesVanillaForKnownYaws() {
        // RotationSegment.convertToSegment: Math.round(deg * 16 / 360) & 15. A sign adds 180 first.
        assertEquals(0, LookWindow.rotationSegment(0));
        assertEquals(8, LookWindow.rotationSegment(180));
        assertEquals(12, LookWindow.rotationSegment(270));
        assertEquals(4, LookWindow.rotationSegment(90));
        assertEquals(15, LookWindow.rotationSegment(-22.5f));
        // standing south (yaw 0) a sign gets segment 8
        assertEquals(8, LookWindow.rotationSegment(0 + 180));
        // a segment is 22.5 degrees wide, centred on its multiple
        assertEquals(1, LookWindow.rotationSegment(11.3f));
        assertEquals(0, LookWindow.rotationSegment(11.2f));
    }

    // ----- nearest looking direction -----

    @Test
    void nearestOrderIsAPermutationStartingWithTheLookDirection() {
        Object[][] cases = {
            { 0f, 0f, Dir.SOUTH }, { 90f, 0f, Dir.WEST }, { 180f, 0f, Dir.NORTH }, { -90f, 0f, Dir.EAST },
            { 0f, -80f, Dir.UP }, { 0f, 80f, Dir.DOWN }, { 123f, 10f, Dir.WEST }, { -170f, -5f, Dir.NORTH },
        };
        for (Object[] c : cases) {
            Dir[] order = LookWindow.nearestOrder((Float) c[0], (Float) c[1], T);
            assertEquals(6, order.length);
            assertEquals(6, EnumSet.copyOf(List.of(order)).size());
            assertEquals(c[2], order[0], c[0] + "," + c[1]);
            // the last three are the opposites of the first three, in reverse
            assertEquals(order[0].opposite(), order[5]);
            assertEquals(order[1].opposite(), order[4]);
            assertEquals(order[2].opposite(), order[3]);
        }
    }

    @Test
    void lookConesImplyTheirNearestDirectionEverywhereInside() {
        for (Dir d : Dir.values()) {
            LookWindow cone = LookWindow.lookCone(d);
            for (double yaw = -180; yaw < 180; yaw += 3) {
                for (double pitch = -90; pitch <= 90; pitch += 3) {
                    if (cone.contains(yaw, pitch)) {
                        assertEquals(d, LookWindow.nearest((float) yaw, (float) pitch, T), d + " at " + yaw + "," + pitch);
                    }
                }
            }
            for (double[] s : cone.samples()) assertEquals(d, LookWindow.nearest((float) s[0], (float) s[1], T), d + " sample");
        }
    }

    @Test
    void thePitchSplitBetweenVerticalAndHorizontalDependsOnTheYaw() {
        // looking along an axis the vertical direction wins past 45 degrees ...
        assertEquals(Dir.SOUTH, LookWindow.nearest(0, 44, T));
        assertEquals(Dir.DOWN, LookWindow.nearest(0, 46, T));
        // ... but diagonally it wins from about 35.3 degrees (tan = 0.7071)
        assertEquals(Dir.DOWN, LookWindow.nearest(44.9f, 36, T));
        assertEquals(Dir.SOUTH, LookWindow.nearest(44.9f, 34, T));
        // so the cone's 35 degree limit is safe at every yaw inside the quadrant
        Set<Dir> seen = EnumSet.noneOf(Dir.class);
        for (double yaw = -44; yaw <= 44; yaw += 1) seen.add(LookWindow.nearest((float) yaw, 35f, T));
        assertEquals(Set.of(Dir.SOUTH), seen);
    }

    @Test
    void fromYawAndNearestAgreeOnTheHorizontalDirection() {
        for (double yaw = -180; yaw < 180; yaw += 1.5) {
            Dir h = Dir.fromYaw(yaw);
            Dir n = LookWindow.nearest((float) yaw, 0, T);
            if (Math.abs(Math.abs(LookWindow.wrap(yaw)) % 90 - 45) > 0.6) assertEquals(h, n, "yaw " + yaw);
        }
    }

    @Test
    void facingRelationsPerBlockFamilyAreWhatTheMapperAssumes() {
        // facing = H.opposite, so a furnace facing north needs H = south: yaw 0
        assertEquals(Dir.SOUTH, Dir.NORTH.opposite());
        assertTrue(LookWindow.quadrant(Dir.NORTH.opposite()).contains(0, 0));
        // anvil facing = H.clockwise, so facing east needs H = north
        assertEquals(Dir.NORTH, Dir.EAST.counterClockwise());
        assertEquals(Dir.EAST, Dir.NORTH.clockwise());
        assertEquals(Dir.NORTH, Dir.WEST.clockwise());
        assertEquals(Dir.WEST, Dir.SOUTH.clockwise());
        assertEquals(Dir.SOUTH, Dir.EAST.clockwise());
    }
}
