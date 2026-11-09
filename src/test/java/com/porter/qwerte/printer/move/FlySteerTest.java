package com.porter.qwerte.printer.move;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.porter.qwerte.printer.move.FlySteer.Keys;
import org.junit.jupiter.api.Test;

class FlySteerTest {
    /** Facing +z (yaw 0), the point 5 blocks ahead at the same height. */
    @Test
    void forwardWhenThePointIsAhead() {
        Keys k = FlySteer.steer(0, 64, 0, 0, 0, 64, 5);
        assertEquals(new Keys(true, false, false, false, false, false), k);
    }

    @Test
    void sidesFollowTheYaw() {
        // Facing +z: -x is to the right, +x to the left
        assertEquals(new Keys(false, false, false, true, false, false), FlySteer.steer(0, 64, 0, 0, -5, 64, 0));
        assertEquals(new Keys(false, false, true, false, false, false), FlySteer.steer(0, 64, 0, 0, 5, 64, 0));
        // Facing -x (yaw 90): -x is ahead
        assertEquals(new Keys(true, false, false, false, false, false), FlySteer.steer(0, 64, 0, 90, -5, 64, 0));
        // Facing +x (yaw 270, or -90): +x is ahead
        assertEquals(new Keys(true, false, false, false, false, false), FlySteer.steer(0, 64, 0, -90, 5, 64, 0));
        // Facing -z (yaw 180): +z is behind
        assertEquals(new Keys(false, true, false, false, false, false), FlySteer.steer(0, 64, 0, 180, 0, 64, 5));
    }

    @Test
    void diagonalsPressTwoKeys() {
        Keys k = FlySteer.steer(0, 64, 0, 0, 5, 64, 5);
        assertTrue(k.forward() && k.left());
        assertFalse(k.back() || k.right());
    }

    @Test
    void thePointIsReachedWhenCloseEnough() {
        assertTrue(FlySteer.steer(0, 64, 0, 33, 0.2, 64.2, 0.2).idle());
    }

    @Test
    void verticalFollowsTheBand() {
        assertTrue(FlySteer.steer(0, 64, 0, 0, 0, 64.4, 0).idle());
        assertTrue(FlySteer.steer(0, 64, 0, 0, 0, 64.6, 0).up());
        assertTrue(FlySteer.steer(0, 64, 0, 0, 0, 63.6, 0).idle());
        assertTrue(FlySteer.steer(0, 64, 0, 0, 0, 63.4, 0).down());
        assertTrue(FlySteer.steer(0, 64, 0, 0, 0, 63.0, 0).down());
    }

    @Test
    void risesBeforeMovingSideways() {
        Keys k = FlySteer.steer(0, 64, 0, 0, 0, 70, 10);
        assertTrue(k.up());
        assertFalse(k.forward() || k.back() || k.left() || k.right());
        // Level enough: both
        Keys near = FlySteer.steer(0, 69.2, 0, 0, 0, 70, 10);
        assertTrue(near.up() && near.forward());
    }

    @Test
    void descendsWhileMoving() {
        Keys k = FlySteer.steer(0, 70, 0, 0, 0, 64, 10);
        assertTrue(k.down());
        assertTrue(k.forward());
    }

    @Test
    void neverBothDirectionsOnOneAxis() {
        for (int yaw = -360; yaw <= 360; yaw += 15) {
            for (int a = 0; a < 360; a += 10) {
                double dx = Math.cos(Math.toRadians(a)) * 6, dz = Math.sin(Math.toRadians(a)) * 6;
                Keys k = FlySteer.steer(0, 64, 0, yaw, dx, 64, dz);
                assertFalse(k.forward() && k.back());
                assertFalse(k.left() && k.right());
                assertFalse(k.up() && k.down());
                assertFalse(k.idle(), "yaw " + yaw + " angle " + a);
            }
        }
    }

    /** Following the keys step by step really gets there. */
    @Test
    void followingTheKeysReachesThePoint() {
        double x = 3, y = 64, z = -4, yaw = 40;
        double hx = 12.5, hy = 70, hz = 9.5;
        for (int i = 0; i < 400; i++) {
            Keys k = FlySteer.steer(x, y, z, yaw, hx, hy, hz);
            if (k.idle()) break;
            double rad = Math.toRadians(yaw);
            double f = (k.forward() ? 1 : 0) - (k.back() ? 1 : 0), r = (k.right() ? 1 : 0) - (k.left() ? 1 : 0);
            double len = Math.hypot(f, r);
            if (len > 0) {
                x += (f * -Math.sin(rad) + r * -Math.cos(rad)) / len * 0.2;
                z += (f * Math.cos(rad) + r * -Math.sin(rad)) / len * 0.2;
            }
            y += ((k.up() ? 1 : 0) - (k.down() ? 1 : 0)) * 0.2;
        }
        assertTrue(Math.hypot(hx - x, hz - z) < 0.6, "x " + x + " z " + z);
        assertTrue(Math.abs(hy - y) <= 0.7, "y " + y);
    }
}
