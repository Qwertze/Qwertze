package com.porter.qwerte.manager.local;

import java.util.List;
import java.util.Locale;

/**
 * Which container a local capture belongs to: the server, the dimension and the block position. A double chest is one
 * container, keyed by its lowest half so that opening either half finds the same entry. Pure.
 */
public record ContainerKey(String server, String dimension, int x, int y, int z) {

    /** The lowest of the parts (by x, then y, then z). Null when there are none. */
    public static int[] canonical(List<int[]> parts) {
        int[] best = null;
        for (int[] p : parts) {
            if (p == null || p.length != 3) continue;
            if (best == null || compare(p, best) < 0) best = p;
        }
        return best;
    }

    private static int compare(int[] a, int[] b) {
        if (a[0] != b[0]) return Integer.compare(a[0], b[0]);
        if (a[1] != b[1]) return Integer.compare(a[1], b[1]);
        return Integer.compare(a[2], b[2]);
    }

    /** The key for a container made of these block positions, or null when there are none. */
    public static ContainerKey of(String server, String dimension, List<int[]> parts) {
        int[] c = canonical(parts);
        return c == null ? null : new ContainerKey(server, dimension, c[0], c[1], c[2]);
    }

    private static final java.util.regex.Pattern DEVICE = java.util.regex.Pattern.compile("(con|prn|aux|nul|com[1-9]|lpt[1-9])");

    /**
     * A readable part of a file name for a server address: lower case, anything but letters, digits, dot, dash and
     * underscore becomes an underscore, no leading dot, cut to 80 characters, and a Windows device name (con, nul, aux,
     * prn, com1-9, lpt1-9, with or without an extension) gets an underscore added. Never empty and never a path. Not
     * unique: see {@link #fileName}.
     */
    public static String safeFileName(String server) {
        String s = server == null ? "" : server.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
        while (s.startsWith(".")) s = s.substring(1);
        if (s.length() > 80) s = s.substring(0, 80);
        if (s.isEmpty()) s = "unknown";
        int dot = s.indexOf('.');
        int end = dot < 0 ? s.length() : dot;
        // Windows looks at the part before the first dot, so the underscore goes there
        if (DEVICE.matcher(s.substring(0, end)).matches()) s = s.substring(0, end) + "_" + s.substring(end);
        return s;
    }

    /**
     * The file name (without extension) for a server address: the readable part plus 8 hex digits of a hash of the exact
     * address, so addresses that only differ in characters that were replaced, or in case, never share a file.
     */
    public static String fileName(String server) {
        return safeFileName(server) + "-" + shortHash(server == null ? "" : server);
    }

    static String shortHash(String s) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(d, 0, 4);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public String toString() {
        return dimension + " " + x + " " + y + " " + z;
    }
}
