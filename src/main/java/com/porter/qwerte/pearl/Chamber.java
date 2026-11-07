package com.porter.qwerte.pearl;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A saved stasis chamber: where it is (server, dimension, block) and what it is called. Kept in the module's settings
 * as one string per chamber, "server|dimension|x|y|z|name".
 */
public record Chamber(String server, String dimension, int x, int y, int z, String name, int waterTop, List<Integer> trapYs,
                      int rimDx, int rimDz, int rimFloorY) {
    /** No shaft data saved (old entries, or saved by hand). */
    public static final int UNKNOWN = Integer.MIN_VALUE;

    private static final String SHAFT_MARK = "|@shaft=";

    public Chamber {
        server = normalizeServer(server);
        trapYs = trapYs == null ? List.of() : List.copyOf(trapYs);
    }

    public Chamber(String server, String dimension, int x, int y, int z, String name) {
        this(server, dimension, x, y, z, name, UNKNOWN, List.of());
    }

    public Chamber(String server, String dimension, int x, int y, int z, String name, int waterTop, List<Integer> trapYs) {
        this(server, dimension, x, y, z, name, waterTop, trapYs, UNKNOWN, UNKNOWN, UNKNOWN);
    }

    /** Whether the rim the player stood on (or the first one found) was saved: its direction and floor level. */
    public boolean hasRim() {
        return rimFloorY != UNKNOWN && rimDx != UNKNOWN && rimDz != UNKNOWN;
    }

    /** Whether the water surface and trapdoors of the shaft were saved with it. */
    public boolean hasShaft() {
        return waterTop != UNKNOWN;
    }

    /** Lower case, no trailing dot, no default port: "Play.Example.net.:25565" and "play.example.net" are one server. */
    public static String normalizeServer(String server) {
        String s = server == null ? "" : server.trim().toLowerCase(Locale.ROOT);
        if (s.endsWith(":25565")) s = s.substring(0, s.length() - 6);
        while (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        return s;
    }

    public String encode() {
        String base = server.replace('|', '/') + "|" + dimension.replace('|', '/') + "|" + x + "|" + y + "|" + z + "|" + name;
        if (!hasShaft()) return base;
        StringBuilder traps = new StringBuilder();
        for (int t : trapYs) traps.append(traps.length() == 0 ? "" : ",").append(t);
        // A marked tail after the name (a name may hold '|', the marker tells them apart): "|@shaft=waterTop;trapY,trapY"
        String rim = hasRim() ? ";" + rimDx + "," + rimDz + "," + rimFloorY : "";
        return base + SHAFT_MARK + waterTop + ";" + traps + rim;
    }

    /** Null if the string isn't a chamber. */
    public static Chamber decode(String s) {
        if (s == null) return null;
        String[] parts = s.split("\\|", 6);
        if (parts.length != 6) return null;
        try {
            int water = UNKNOWN, rdx = UNKNOWN, rdz = UNKNOWN, rfy = UNKNOWN;
            List<Integer> traps = new ArrayList<>();
            String name = parts[5];
            int mark = name.lastIndexOf(SHAFT_MARK);
            if (mark >= 0) {
                String tail = name.substring(mark + SHAFT_MARK.length());
                name = name.substring(0, mark);
                // Lenient: a damaged extra just means no shaft data
                try {
                    String[] extra = tail.split(";", -1);
                    int w = Integer.parseInt(extra[0].trim());
                    List<Integer> ts = new ArrayList<>();
                    if (extra.length > 1) for (String t : extra[1].split(",")) if (!t.isBlank()) ts.add(Integer.parseInt(t.trim()));
                    water = w;
                    traps = ts;
                    // The rim is optional and read on its own: a damaged one just means no rim
                    if (extra.length > 2) {
                        try {
                            String[] r = extra[2].split(",");
                            rdx = Integer.parseInt(r[0].trim());
                            rdz = Integer.parseInt(r[1].trim());
                            rfy = Integer.parseInt(r[2].trim());
                        }
                        catch (RuntimeException ignoredRim) {
                            rdx = rdz = rfy = UNKNOWN;
                        }
                    }
                }
                catch (RuntimeException ignored) {
                    water = UNKNOWN;
                    traps = new ArrayList<>();
                }
            }
            return new Chamber(parts[0], parts[1], Integer.parseInt(parts[2].trim()), Integer.parseInt(parts[3].trim()),
                Integer.parseInt(parts[4].trim()), name, water, traps, rdx, rdz, rfy);
        }
        catch (NumberFormatException e) {
            return null;
        }
    }

    public boolean samePlace(Chamber other) {
        return server.equals(other.server) && dimension.equals(other.dimension) && x == other.x && y == other.y && z == other.z;
    }

    /** How far below / above the saved block a landing still counts as being in the chamber's shaft. */
    public static final double COLUMN_BELOW = 16, COLUMN_ABOVE = 4;

    /** Same server, ignoring case, port 25565, a trailing dot and a leading "connect." or "www.". */
    public static boolean sameServer(String a, String b) {
        return canonicalServer(a).equals(canonicalServer(b));
    }

    private static String canonicalServer(String server) {
        String s = normalizeServer(server);
        if (s.startsWith("connect.")) s = s.substring(8);
        else if (s.startsWith("www.")) s = s.substring(4);
        return s;
    }

    /** Horizontal distance from a point to the middle of the chamber's block column. */
    public double horizontalTo(double px, double pz) {
        double dx = px - (x + 0.5), dz = pz - (z + 0.5);
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** Point's y minus the saved block's y (negative: below the saved block). */
    public double verticalTo(double py) {
        return py - y;
    }

    /**
     * Whether a landing is in the chamber's column: within {@code radius} sideways of the saved block, and inside the
     * shaft vertically. The saved block may be at the rim or at the water, so the shaft is searched far below it.
     */
    public boolean inColumn(double px, double py, double pz, double radius) {
        double v = verticalTo(py);
        return horizontalTo(px, pz) <= radius && v >= -COLUMN_BELOW && v <= COLUMN_ABOVE;
    }

    /** Distance from a point to the middle of the chamber's block. */
    public double distanceTo(double px, double py, double pz) {
        double dx = px - (x + 0.5), dy = py - (y + 0.5), dz = pz - (z + 0.5);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** "overworld" from "ResourceKey[minecraft:dimension / minecraft:overworld]" (or from an already short name). */
    public String shortDimension() {
        String d = dimension;
        int slash = d.lastIndexOf("/ ");
        if (slash >= 0) d = d.substring(slash + 2);
        int end = d.indexOf(']');
        if (end >= 0) d = d.substring(0, end);
        d = d.trim();
        if (d.startsWith("minecraft:")) d = d.substring("minecraft:".length());
        return d;
    }
}
