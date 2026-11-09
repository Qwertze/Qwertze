package com.porter.qwerte.printer.schematic;

import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;

/**
 * The arithmetic of a .litematic block storage: the packed bit array, the cell index and the region corners. Pure (no
 * Minecraft classes). The decoder mirrors LitematicaBitArray and the stash manager server's SchematicReader: entries of
 * {@code bits} bits are packed LSB-first one after another, so an entry may span two longs.
 */
public final class LitematicFormat {
    private LitematicFormat() {
    }

    /** Bits per entry: enough for the palette, never fewer than two. */
    public static int bitsFor(int paletteSize) {
        return Math.max(2, Integer.SIZE - Integer.numberOfLeadingZeros(Math.max(0, paletteSize - 1)));
    }

    /** The number of longs a region of {@code volume} cells needs. */
    public static long expectedLength(long volume, int bits) {
        return (volume * bits + 63) / 64;
    }

    /** Index of a cell: y * sizeX * sizeZ + z * sizeX + x (sizes are the absolute ones). */
    public static long index(int x, int y, int z, int sizeX, int sizeZ) {
        return ((long) y * sizeZ + z) * sizeX + x;
    }

    /**
     * One entry of the bit array. The caller has checked {@code data.length == expectedLength(volume, bits)} and that
     * {@code index < volume}, which keeps both words in range.
     */
    public static int get(long[] data, int bits, long index) {
        long bitPos = index * bits;
        int word = (int) (bitPos >>> 6);
        int offset = (int) (bitPos & 63);
        long v = data[word] >>> offset;
        if (offset + bits > 64) v |= data[word + 1] << (64 - offset);
        return (int) (v & ((1L << bits) - 1));
    }

    /** Packs entries the way Litematica writes them (used to build test files). */
    public static long[] pack(int[] entries, int bits) {
        long[] out = new long[(int) expectedLength(entries.length, bits)];
        long mask = (1L << bits) - 1;
        long bitPos = 0;
        for (int e : entries) {
            int word = (int) (bitPos >>> 6);
            int offset = (int) (bitPos & 63);
            out[word] |= ((long) e & mask) << offset;
            if (offset + bits > 64) out[word + 1] |= ((long) e & mask) >>> (64 - offset);
            bitPos += bits;
        }
        return out;
    }

    /** The far end of a region along one axis, relative to its position: size - 1, or size + 1 for a negative size. */
    public static int relEnd(int size) {
        return size >= 0 ? size - 1 : size + 1;
    }

    /** The smaller of the region's two corners along one axis (the corner the container's cell 0 sits at). */
    public static int minCorner(int pos, int size) {
        return Math.min(pos, pos + relEnd(size));
    }

    // ---------- palette entries as one string: name[prop=value,prop=value] with the properties sorted ----------

    public static String stateKey(String name, Map<String, String> properties) {
        if (properties == null || properties.isEmpty()) return name;
        StringBuilder sb = new StringBuilder(name).append('[');
        boolean first = true;
        for (Map.Entry<String, String> e : new TreeMap<>(properties).entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.append(']').toString();
    }

    /** The block id of a {@link #stateKey}. */
    public static String keyName(String key) {
        int i = key.indexOf('[');
        return i < 0 ? key : key.substring(0, i);
    }

    /** The properties of a {@link #stateKey} as name, value pairs in order (empty when it has none). */
    public static String[][] keyProperties(String key) {
        int i = key.indexOf('[');
        if (i < 0 || !key.endsWith("]")) return new String[0][];
        String[] parts = key.substring(i + 1, key.length() - 1).split(",");
        String[][] out = new String[parts.length][];
        int n = 0;
        for (String p : parts) {
            int eq = p.indexOf('=');
            if (eq > 0) out[n++] = new String[] { p.substring(0, eq), p.substring(eq + 1) };
        }
        return Arrays.copyOf(out, n);
    }
}
