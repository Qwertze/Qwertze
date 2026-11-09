package com.porter.qwerte.printer.place;

import com.porter.qwerte.printer.place.PlaceRecipe.Dir;
import java.util.ArrayList;
import java.util.List;

/**
 * The set of looks (yaw, pitch) from which a placement gives the wanted state: a circular yaw arc and a pitch
 * interval. Yaw is in degrees and wraps (the arc may cross +-180); pitch is in degrees, -90 up to 90 down.
 *
 * <p>Look-to-state relations, read from the vanilla getStateForPlacement bytecode (identical in 1.21.4 and 1.21.11,
 * except that 1.21.11's Mth.sin/cos take a double, which does not change the result). H is
 * BlockPlaceContext.getHorizontalDirection = Direction.fromYRot(yaw): yaw 0 south, 90 west, +-180 north, -90 east,
 * each covering 90 degrees centred on it. L is the nearest looking direction (Direction.orderedByNearest(player)[0]).
 * <ul>
 *   <li>facing = H.opposite: furnace, smoker, blast furnace, carved pumpkin, jack o-lantern, glazed terracotta, loom,
 *       stonecutter, lectern, beehive, bee nest, chiseled bookshelf, ender chest, chest, end portal frame, repeater,
 *       comparator (DiodeBlock), trapdoor on a floor or ceiling click.</li>
 *   <li>facing = H: stairs, door, fence gate, bed (the head side), campfire, decorated pot, floor and ceiling buttons,
 *       levers and grindstones (FaceAttachedHorizontalDirectionalBlock), floor and ceiling bells.</li>
 *   <li>facing = H.clockwise: anvils.</li>
 *   <li>facing = L.opposite: piston, sticky piston, dispenser, dropper, barrel (they face the player).</li>
 *   <li>facing = L: observer (the face looks the way the player looks, the output is behind it).</li>
 *   <li>rotation segment = round((yaw + 180) * 16 / 360) and 15: standing signs, banners. Skulls and heads use
 *       round(yaw * 16 / 360) and 15 with no 180 offset. Each segment covers 22.5 degrees.</li>
 *   <li>No look at all: axis blocks, shulker boxes, end rods, lightning rods, amethyst clusters, hoppers, slabs,
 *       wall-mounted blocks (they follow the clicked face).</li>
 * </ul>
 * When a block is air-placed (the clicked block is the target cell itself) vanilla stops moving the clicked face to the
 * front of Direction ordering, so wall/attach/hanging blocks then depend on L as well; the mapper handles that with
 * {@link #lookCone}.
 */
public record LookWindow(double yawCenter, double yawHalf, double pitchMin, double pitchMax) {
    private static final double EPS = 1e-9;

    public LookWindow {
        if (!(yawHalf >= 0) || Double.isNaN(yawCenter) || Double.isNaN(pitchMin) || Double.isNaN(pitchMax)) throw new IllegalArgumentException("bad window");
        yawCenter = wrap(yawCenter);
        yawHalf = Math.min(180, yawHalf);
        pitchMin = Math.max(-90, pitchMin);
        pitchMax = Math.min(90, pitchMax);
        if (pitchMin > pitchMax) throw new IllegalArgumentException("empty pitch range");
    }

    /** Any look. */
    public static LookWindow any() {
        return new LookWindow(0, 180, -90, 90);
    }

    /** The yaw quadrant that makes H equal {@code h}: +-44 degrees (the true quadrant is +-45), any pitch. */
    public static LookWindow quadrant(Dir h) {
        return new LookWindow(h.yawCenter(), 44, -90, 90);
    }

    /** One rotation segment, exactly: +-11 degrees (the true half width is 11.25) around {@code seg * 22.5 + offsetDeg}. */
    public static LookWindow rotation16(int seg, double offsetDeg) {
        return new LookWindow(seg * 22.5 + offsetDeg, 11, -90, 90);
    }

    /**
     * The looks whose nearest looking direction L is {@code d}. A conservative rectangle inside the real region:
     * horizontal directions need the yaw quadrant and |pitch| below 35 degrees (vertical beats horizontal when
     * tan|pitch| exceeds max(|sin yaw|, |cos yaw|), which is at least 0.707 at the quadrant edge and 0.719 at +-44);
     * up needs pitch -46 or higher up (tan 46 degrees is above 1 so it wins at every yaw), down needs 46 or lower.
     */
    public static LookWindow lookCone(Dir d) {
        return switch (d) {
            case UP -> new LookWindow(0, 180, -90, -46);
            case DOWN -> new LookWindow(0, 180, 46, 90);
            default -> new LookWindow(d.yawCenter(), 44, -35, 35);
        };
    }

    public boolean fullYaw() {
        return yawHalf >= 180;
    }

    public boolean contains(double yaw, double pitch) {
        return pitch >= pitchMin - EPS && pitch <= pitchMax + EPS && (fullYaw() || Math.abs(diff(yaw, yawCenter)) <= yawHalf + EPS);
    }

    public boolean intersects(LookWindow o) {
        return intersect(o) != null;
    }

    /**
     * The overlap, or null when there is none. Two arcs can overlap in two pieces only when their widths add up to
     * more than 360 degrees; no window the mapper builds is wider than 90, and for general input the wider piece is
     * returned.
     */
    public LookWindow intersect(LookWindow o) {
        double lo = Math.max(pitchMin, o.pitchMin), hi = Math.min(pitchMax, o.pitchMax);
        if (lo > hi + EPS) return null;
        if (hi < lo) hi = lo;
        if (fullYaw()) return new LookWindow(o.yawCenter, o.yawHalf, lo, hi);
        if (o.fullYaw()) return new LookWindow(yawCenter, yawHalf, lo, hi);
        double d = diff(o.yawCenter, yawCenter);
        double bestLo = 0, bestHi = -1, bestW = -1;
        for (double shift : new double[] { 0, -360, 360 }) {
            double a = Math.max(-yawHalf, d + shift - o.yawHalf), b = Math.min(yawHalf, d + shift + o.yawHalf);
            if (a <= b + EPS && b - a > bestW) {
                bestW = b - a;
                bestLo = a;
                bestHi = Math.max(a, b);
            }
        }
        if (bestW < 0) return null;
        return new LookWindow(yawCenter + (bestLo + bestHi) / 2, (bestHi - bestLo) / 2, lo, hi);
    }

    /** The middle of the window. */
    public double centerYaw() {
        return yawCenter;
    }

    public double centerPitch() {
        return (pitchMin + pitchMax) / 2;
    }

    /** The nearest look inside the window to a given look: {yaw, pitch}. */
    public double[] clamp(double yaw, double pitch) {
        double y = yaw;
        if (!fullYaw()) {
            double d = diff(yaw, yawCenter);
            if (d > yawHalf) y = yawCenter + yawHalf;
            else if (d < -yawHalf) y = yawCenter - yawHalf;
        }
        return new double[] { wrap(y), Math.max(pitchMin, Math.min(pitchMax, pitch)) };
    }

    /** The middle and four corners pulled slightly inside, for checking that a whole window works. */
    public List<double[]> samples() {
        List<double[]> out = new ArrayList<>();
        out.add(new double[] { centerYaw(), centerPitch() });
        double inset = 1e-3;
        double h = fullYaw() ? 179 : Math.max(0, yawHalf - inset);
        double p0 = Math.min(pitchMin + inset, centerPitch()), p1 = Math.max(pitchMax - inset, centerPitch());
        for (double ys : new double[] { -h, h }) {
            for (double p : new double[] { p0, p1 }) out.add(new double[] { wrap(yawCenter + ys), p });
        }
        return out;
    }

    /** The window shrunk to +-yawBy yaw and +-pitchBy pitch around its middle. */
    public LookWindow narrowed(double yawBy, double pitchBy) {
        double c = centerPitch();
        return new LookWindow(yawCenter, Math.min(yawHalf, yawBy), Math.max(pitchMin, c - pitchBy), Math.min(pitchMax, c + pitchBy));
    }

    /** Wraps a yaw into [-180, 180). */
    public static double wrap(double yaw) {
        double y = yaw % 360;
        if (y >= 180) y -= 360;
        if (y < -180) y += 360;
        return y;
    }

    /** The signed shortest turn from b to a, in [-180, 180). */
    public static double diff(double a, double b) {
        return wrap(a - b);
    }

    /** The rotation segment of a yaw, as RotationSegment.convertToSegment does (round(deg * 16 / 360) and 15). */
    public static int rotationSegment(float yawPlusOffset) {
        return Math.round(yawPlusOffset * (16f / 360f)) & 15;
    }

    /** sin and cos for the direction ordering; the game's own Mth tables are plugged in at runtime. */
    public interface Trig {
        float sin(float radians);

        float cos(float radians);

        Trig JAVA = new Trig() {
            @Override
            public float sin(float r) {
                return (float) Math.sin(r);
            }

            @Override
            public float cos(float r) {
                return (float) Math.cos(r);
            }
        };
    }

    /**
     * Direction.orderedByNearest for a player looking at (yaw, pitch): the six directions, most aligned first. Same
     * comparisons as the vanilla code (read from its bytecode).
     */
    public static Dir[] nearestOrder(float yaw, float pitch, Trig t) {
        float p = pitch * 0.017453292f;
        float y = -yaw * 0.017453292f;
        float sinP = t.sin(p), cosP = t.cos(p), sinY = t.sin(y), cosY = t.cos(y);
        boolean east = sinY > 0, up = sinP < 0, south = cosY > 0;
        float ax = east ? sinY : -sinY;
        float ay = up ? -sinP : sinP;
        float az = south ? cosY : -cosY;
        float bx = ax * cosP, bz = az * cosP;
        Dir dx = east ? Dir.EAST : Dir.WEST;
        Dir dy = up ? Dir.UP : Dir.DOWN;
        Dir dz = south ? Dir.SOUTH : Dir.NORTH;
        if (ax > az) {
            if (ay > bx) return six(dy, dx, dz);
            return bz > ay ? six(dx, dz, dy) : six(dx, dy, dz);
        }
        if (ay > bz) return six(dy, dz, dx);
        return bx > ay ? six(dz, dx, dy) : six(dz, dy, dx);
    }

    private static Dir[] six(Dir a, Dir b, Dir c) {
        return new Dir[] { a, b, c, c.opposite(), b.opposite(), a.opposite() };
    }

    /** The nearest looking direction (BlockPlaceContext.getNearestLookingDirection). */
    public static Dir nearest(float yaw, float pitch, Trig t) {
        return nearestOrder(yaw, pitch, t)[0];
    }
}
