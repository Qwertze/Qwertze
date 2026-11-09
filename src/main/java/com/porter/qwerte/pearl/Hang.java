package com.porter.qwerte.pearl;

/**
 * The maths of throwing from the rim. A sneaking player can't walk off an edge: the game stops them when one more step
 * would leave their 0.6 wide box with nothing under it, which is with their middle about 0.25 to 0.3 blocks past the
 * edge. A pearl thrown straight down leaves from the player's x and z, so from there it falls inside a 1x1 shaft.
 */
public final class Hang {
    /** Half the width of a thrown pearl. */
    public static final double PEARL_HALF = 0.125;
    /** How far from the wall the pearl must stay, so it doesn't brush it. */
    public static final double MARGIN = 0.03;
    /** Where the player's middle is, from the edge of the shaft, when they hang over it (used to test the line). */
    public static final double INSET = 0.23;
    /** The player's middle is this far from the middle of the shaft along the rim direction, or more, when fully off it. */
    public static final double CLEAR_OF_SHAFT = 0.85;

    private Hang() {}

    /** The pearl, leaving at (px, pz), stays within the shaft cell centred at (cx, cz) (block middle). */
    public static boolean fits(double px, double pz, double cx, double cz) {
        double limit = 0.5 - PEARL_HALF - MARGIN;
        return Math.abs(px - cx) <= limit && Math.abs(pz - cz) <= limit;
    }

    /** The yaw, in Minecraft's degrees, that faces from (fx, fz) to (tx, tz). */
    public static double yawToward(double fx, double fz, double tx, double tz) {
        return Math.toDegrees(Math.atan2(tz - fz, tx - fx)) - 90.0;
    }

    /** The middle of the player's expected hanging spot for a rim in direction (dx, dz): over the shaft, INSET from its edge. */
    public static double[] hangSpot(double cx, double cz, int dx, int dz) {
        return new double[] { cx + dx * (0.5 - INSET), cz + dz * (0.5 - INSET) };
    }

    /** The player's middle is on the neighbouring block, no longer over the shaft. */
    public static boolean offShaft(double px, double pz, double cx, double cz, int dx, int dz) {
        return (px - cx) * dx + (pz - cz) * dz >= CLEAR_OF_SHAFT;
    }

    /** A trapdoor may be closed once the pearl is below it: its top (a pearl is 0.25 high) clear of the trapdoor with a margin. */
    public static boolean pearlBelow(double pearlY, int trapY) {
        return pearlY + 0.3 < trapY;
    }

    /** Standard deviation of the sideways speed a thrown pearl gets from the game's random spread, per tick. */
    public static final double SPREAD = 0.0075 * 1.5;

    /** The aim of a throw: yaw and pitch in Minecraft's degrees, the tick count, the pearl's path as (x, y, z) per tick. */
    public record Aim(double yaw, double pitch, int ticks, double[][] path) {}

    /** How far the pearl may stray sideways after {@code tick} ticks of flight (3 sigma of the spread, plus slack). */
    public static double drift(int tick) {
        return 3 * SPREAD * tick + 0.02;
    }

    /**
     * The throw from the player's real position (px, pz), eyes at eyeY (the pearl leaves 0.1 lower), that lands the
     * pearl in the middle of the shaft (cx, cz) at the water surface: pitch 90 when already in the middle, otherwise
     * leaning towards it so the sideways speed carries it exactly over the offset during the fall. Null when the
     * pearl would have to be thrown more than 45 degrees off the vertical, or there is no fall.
     */
    public static Aim aim(double px, double pz, double cx, double cz, double eyeY, double surfaceY) {
        double dx = cx - px, dz = cz - pz;
        double d = Math.hypot(dx, dz);
        double yaw = d < 1e-4 ? 0 : yawToward(px, pz, cx, cz);
        double ux = d < 1e-4 ? 0 : dx / d, uz = d < 1e-4 ? 0 : dz / d;
        double y0 = eyeY - 0.1;
        if (y0 <= surfaceY) return null;
        // Thrown from outside the shaft's own cell the pearl would cross the rim: never a clean drop down a 1x1 shaft
        if (d > 0.5) return null;

        double pitch = 90;
        if (d >= 1e-4) {
            if (lateral(45, y0, surfaceY) < d) return null;
            double lo = 45, hi = 90;
            for (int i = 0; i < 60; i++) {
                double mid = (lo + hi) / 2;
                if (lateral(mid, y0, surfaceY) > d) lo = mid;
                else hi = mid;
            }
            pitch = (lo + hi) / 2;
        }

        // The path once more, with its points
        java.util.List<double[]> points = new java.util.ArrayList<>();
        Flight f = new Flight(pitch, y0);
        points.add(new double[] { px, y0, pz });
        for (int t = 1; t <= 80; t++) {
            double prevH = f.h, prevY = f.y;
            f.step();
            if (f.y <= surfaceY) {
                double lat = prevH + (f.h - prevH) * ((prevY - surfaceY) / (prevY - f.y));
                points.add(new double[] { px + ux * lat, surfaceY, pz + uz * lat });
                return new Aim(yaw, pitch, t, points.toArray(new double[0][]));
            }
            points.add(new double[] { px + ux * f.h, f.y, pz + uz * f.h });
        }
        return null;
    }

    /**
     * A thrown pearl in flight. One step is the game's own order for a thrown projectile: gravity is applied to the
     * velocity (0.03 down), then the drag (0.99) scales it, and only then does the pearl move by that velocity.
     * h is the distance travelled sideways, y the height, vh and vy the velocities.
     */
    private static final class Flight {
        double h, y, vh, vy;

        Flight(double pitchDeg, double y0) {
            double rad = Math.toRadians(pitchDeg);
            vh = 1.5 * Math.cos(rad);
            vy = -1.5 * Math.sin(rad);
            y = y0;
        }

        void step() {
            vy = (vy - 0.03) * 0.99;
            vh *= 0.99;
            h += vh;
            y += vy;
        }
    }

    /** How far sideways a pearl thrown at this pitch has gone when it gets down to the surface. */
    private static double lateral(double pitchDeg, double y0, double surfaceY) {
        Flight f = new Flight(pitchDeg, y0);
        for (int t = 1; t <= 80; t++) {
            double prevH = f.h, prevY = f.y;
            f.step();
            if (f.y <= surfaceY) return prevH + (f.h - prevH) * ((prevY - surfaceY) / (prevY - f.y));
        }
        return f.h;
    }

    /** Where, and after how many ticks, a pearl with this pitch and vertical speed gets down to the surface: sideways factor s. */
    private record Landing(double s, int ticks, double frac) {}

    /**
     * The pearl's sideways displacement is its initial sideways velocity times s: the drag scales the whole sideways
     * velocity alike, so s depends only on the fall (pitch and the vertical speed it starts with).
     */
    private static Landing landing(double pitchDeg, double y0, double startVy, double surfaceY) {
        double vy = -1.5 * Math.sin(Math.toRadians(pitchDeg)) + startVy;
        double y = y0, s = 0, factor = 1;
        for (int t = 1; t <= 80; t++) {
            double prevY = y;
            vy = (vy - 0.03) * 0.99;
            factor *= 0.99;
            y += vy;
            if (y <= surfaceY) return new Landing(s + (prevY - surfaceY) / (prevY - y) * factor, t, (prevY - surfaceY) / (prevY - y));
            s += factor;
        }
        return null;
    }

    /**
     * The throw from a moving player. The pearl leaves with the player's sideways velocity added to its own (and the
     * vertical one too when they are not on the ground), so the lean is chosen to cancel what the player carries and to
     * land the pearl in the middle of the shaft. Position and velocity are those of the moment of the throw (the caller
     * predicts one tick ahead). Null when no pitch between 45 and 90 degrees does it, or there is no fall.
     */
    public static Aim aimMoving(double px, double pz, double vx, double vz, double vy, boolean onGround,
                                double cx, double cz, double eyeY, double surfaceY) {
        double y0 = eyeY - 0.1;
        if (y0 <= surfaceY) return null;
        double dx = cx - px, dz = cz - pz;
        if (Math.hypot(dx, dz) > 0.5) return null;
        double pvy = onGround ? 0 : vy;

        double bestPitch = Double.NaN, bestErr = Double.MAX_VALUE;
        Landing bestLanding = null;
        for (double pitch = 90; pitch >= 45 - 1e-9; pitch -= 0.1) {
            Landing l = landing(pitch, y0, pvy, surfaceY);
            if (l == null || l.s() < 1e-6) continue;
            double need = Math.hypot(dx / l.s() - vx, dz / l.s() - vz);
            // The sideways error at the water, in blocks
            double err = Math.abs(need - 1.5 * Math.cos(Math.toRadians(pitch))) * l.s();
            if (err < bestErr) {
                bestErr = err;
                bestPitch = pitch;
                bestLanding = l;
            }
        }
        if (bestLanding == null || bestErr > 0.03) return null;

        double s = bestLanding.s();
        double ax = dx / s - vx, az = dz / s - vz;
        double an = Math.hypot(ax, az);
        double yaw = an < 1e-6 ? 0 : yawToward(0, 0, ax, az);
        double ux = an < 1e-6 ? 0 : ax / an, uz = an < 1e-6 ? 0 : az / an;
        double cos = 1.5 * Math.cos(Math.toRadians(bestPitch));
        double wx = cos * ux + vx, wz = cos * uz + vz;

        java.util.List<double[]> points = new java.util.ArrayList<>();
        points.add(new double[] { px, y0, pz });
        double vyNow = -1.5 * Math.sin(Math.toRadians(bestPitch)) + pvy;
        double y = y0, acc = 0, factor = 1;
        for (int t = 1; t <= bestLanding.ticks(); t++) {
            double prevY = y;
            vyNow = (vyNow - 0.03) * 0.99;
            factor *= 0.99;
            y += vyNow;
            if (t == bestLanding.ticks()) {
                double sl = acc + bestLanding.frac() * factor;
                points.add(new double[] { px + wx * sl, surfaceY, pz + wz * sl });
            }
            else {
                acc += factor;
                points.add(new double[] { px + wx * acc, y, pz + wz * acc });
            }
        }
        return new Aim(yaw, bestPitch, bestLanding.ticks(), points.toArray(new double[0][]));
    }

    /** Every point of the path stays inside the shaft cell, with room for the pearl and the spread so far. */
    public static boolean pathFits(Aim aim, double cx, double cz) {
        for (int i = 0; i < aim.path().length; i++) {
            double limit = 0.5 - PEARL_HALF - drift(i);
            if (Math.abs(aim.path()[i][0] - cx) > limit || Math.abs(aim.path()[i][2] - cz) > limit) return false;
        }
        return true;
    }
}
