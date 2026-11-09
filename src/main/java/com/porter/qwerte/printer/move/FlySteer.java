package com.porter.qwerte.printer.move;

/**
 * Which movement keys to hold to get from the player to a hover point. Pure.
 *
 * <p>The keys are worked out against the player's yaw at that moment, with forward, back, left and right in 45 degree
 * sectors, so the camera is never turned: the Printer's rotations for placing, and a flight module that restores the yaw,
 * are left alone. It rises first when the point is well above (a wall of printed blocks may be in the way) and does not
 * move sideways until it is nearly level with it.
 */
public final class FlySteer {
    public record Keys(boolean forward, boolean back, boolean left, boolean right, boolean up, boolean down) {
        public static final Keys NONE = new Keys(false, false, false, false, false, false);

        public boolean idle() {
            return !forward && !back && !left && !right && !up && !down;
        }
    }

    /** Closer than this horizontally, no key. */
    public static final double ARRIVED_XZ = 0.35;
    /** Vertical dead band: no jump or sneak inside it. */
    public static final double BAND_Y = 0.5;
    /** More than this below the point and it only rises. */
    public static final double RISE_FIRST = 1.2;
    private static final double SECTOR = Math.sin(Math.toRadians(22.5));

    private FlySteer() {}

    /**
     * @param yaw the player's yaw in degrees (Minecraft: 0 faces +z, 90 faces -x)
     * @param hx  the point to hover at: x and z of the centre, y of the feet
     */
    public static Keys steer(double px, double py, double pz, double yaw, double hx, double hy, double hz) {
        double dy = hy - py;
        boolean up = dy > BAND_Y, down = dy < -BAND_Y;
        boolean forward = false, back = false, left = false, right = false;
        double dx = hx - px, dz = hz - pz;
        double dist = Math.hypot(dx, dz);
        if (dist > ARRIVED_XZ && dy <= RISE_FIRST) {
            double rad = Math.toRadians(yaw);
            double fx = -Math.sin(rad), fz = Math.cos(rad); // where forward points
            double rx = -Math.cos(rad), rz = -Math.sin(rad); // where right points
            double f = (dx * fx + dz * fz) / dist, r = (dx * rx + dz * rz) / dist;
            forward = f > SECTOR;
            back = f < -SECTOR;
            right = r > SECTOR;
            left = r < -SECTOR;
        }
        return new Keys(forward, back, left, right, up, down);
    }
}
